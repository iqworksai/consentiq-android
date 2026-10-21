package ai.iqworks.consentiq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ConsentIQEngine(
    private val config: ConsentIQConfig,
    private val store: ConsentStore,
    private val api: ConsentIQApiClient,
    private val logger: ConsentIQLogger,
    private val state: MutableStateFlow<ConsentIQState>,
    private val captcha: CaptchaTokenProvider = CaptchaTokenProvider.UNAVAILABLE,
    private val tcfHost: TcfHost = TcfHost.UNAVAILABLE,
    private val tcfKeys: TcfKeyWriter = TcfKeyWriter.NONE,
    private val tcfReadyTimeoutMs: Long = TCF_READY_TIMEOUT_MS,
    private val tcfAnswerTimeoutMs: Long = TCF_ANSWER_TIMEOUT_MS,
) {
    @Volatile
    var subjectId: String = ""
        private set

    @Volatile
    private var loadedConfig: PropertyConfig? = null

    private var tcfRunning = false
    private var tcfOpenQueued = false

    @Volatile
    var canOpenTcf: Boolean = false
        private set

    val isTcfActive: Boolean
        get() = state.value.isTcfActive

    fun loadedConfigFor(
        propertyKey: String,
        baseUrl: String,
    ): PropertyConfig? = loadedConfig?.takeIf { config.propertyKey == propertyKey && api.baseUrl == baseUrl.trimEnd('/') }

    suspend fun initialize() {
        state.value = ConsentIQState()
        loadedConfig = null
        canOpenTcf = false
        tcfOpenQueued = false
        try {
            subjectId = resolveSubjectId()
            val propertyConfig = api.getConfig()
            loadedConfig = propertyConfig
            val language =
                config.language
                    ?: propertyConfig.property.defaultLanguage?.takeIf { it.isNotBlank() }
                    ?: "en"
            state.update {
                it.copy(config = propertyConfig, regulation = propertyConfig.resolvedRegulation, language = language)
            }
            val tcfActive = startTcf(propertyConfig.tcf)
            canOpenTcf = tcfActive
            state.update { it.copy(isTcfActive = tcfActive) }

            var found = false
            if (subjectId.isNotEmpty()) {
                val lookup =
                    runCatching { api.getConsent(subjectId) }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.warn("[ConsentIQ] Could not restore consent: ${e.message}")
                        }.getOrNull()
                val existing = lookup?.consent
                when {
                    existing != null -> {
                        val cookie = withNecessary(existing.cookieConsents)
                        state.update { it.copy(consent = cookie, marketingConsent = existing.marketingConsents) }
                        store.saveConsent(cookie)
                        found = true
                    }
                    lookup?.expired == true -> {
                        store.clearConsent()
                        clearTcfDecision()
                    }
                    else -> {
                        val local = store.loadConsent()
                        if (local != null) {
                            state.update { it.copy(consent = withNecessary(local)) }
                            found = true
                        }
                    }
                }
            }

            state.update { it.copy(isLoading = false) }
            if (tcfActive) {
                if (config.autoShow) runTcf(reopen = false, sheetOnFallback = !found)
            } else if (config.autoShow && !found) {
                showSheet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[ConsentIQ] Failed to initialize: ${e.message}")
            state.update { it.copy(isLoading = false) }
        }
    }

    suspend fun acceptAll() {
        val categories = state.value.config?.categories
        val accepted = LinkedHashMap(DEFAULT_CONSENT)
        if (categories != null) {
            for (category in categories) if (category.enabled) accepted[category.code] = true
        } else {
            for (key in accepted.keys) accepted[key] = true
        }
        accepted[NECESSARY] = true
        state.update { it.copy(consent = accepted, isSheetVisible = false) }
        supersedeTcfDecision()
        submit(accepted, state.value.marketingConsent)
        config.onAccept?.invoke(AcceptInfo(scope = "all", consent = accepted))
    }

    suspend fun rejectAll() {
        val rejected = LinkedHashMap(DEFAULT_CONSENT)
        state.value.config
            ?.categories
            ?.forEach { rejected[it.code] = it.isRequired }
        rejected[NECESSARY] = true
        state.update { it.copy(consent = rejected, isSheetVisible = false) }
        supersedeTcfDecision()
        submit(rejected, state.value.marketingConsent)
        config.onReject?.invoke(RejectInfo(consent = rejected))
    }

    suspend fun savePreferences(consent: Map<String, Boolean>) {
        val next = withNecessary(consent)
        state.update { it.copy(consent = next, isSheetVisible = false) }
        supersedeTcfDecision()
        submit(next, state.value.marketingConsent)
        config.onSave?.invoke(SaveInfo(consent = next))
    }

    suspend fun updateConsent(updates: Map<String, Boolean>) {
        val next = withNecessary(state.value.consent + updates)
        state.update { it.copy(consent = next) }
        submit(next, state.value.marketingConsent)
    }

    suspend fun updateMarketingConsent(updates: Map<String, Boolean>) {
        val next = state.value.marketingConsent + updates
        state.update { it.copy(marketingConsent = next) }
        submit(state.value.consent, next)
    }

    fun showSheet() {
        if (isTcfActive) return
        state.update { it.copy(isSheetVisible = true) }
        config.onOpen?.invoke()
    }

    // An open while a run is in progress is queued and runs as a re-open once that run ends.
    suspend fun showTcf() {
        if (!canOpenTcf) return
        if (tcfRunning) {
            tcfOpenQueued = true
            return
        }
        state.update { it.copy(isTcfActive = true, isSheetVisible = false) }
        runTcf(reopen = store.loadTcf() != null, sheetOnFallback = true)
    }

    private fun startTcf(settings: TcfSettings?): Boolean {
        if (settings == null || !settings.isCmpActive()) {
            retireTcfKeys()
            return false
        }
        if (settings.gdprApplies) {
            tcfHost.unavailable()?.let {
                reportTcfFailure(it)
                return false
            }
        }
        try {
            tcfKeys.writeCmp(settings.cmpId, settings.cmpVersion)
            store.saveTcfCmpId(settings.cmpId)
            tcfKeys.writeGdprApplies(if (settings.gdprApplies) 1 else 0)
        } catch (e: RuntimeException) {
            reportTcfFailure(TcfOutcome.Failed(TCF_UNAVAILABLE, "could not write the TCF keys"))
            return false
        }
        return settings.gdprApplies
    }

    private suspend fun runTcf(
        reopen: Boolean,
        sheetOnFallback: Boolean,
    ) {
        tcfRunning = true
        try {
            runTcfOnce(reopen, sheetOnFallback)
            while (tcfOpenQueued) {
                tcfOpenQueued = false
                if (!canOpenTcf) {
                    showSheet()
                    break
                }
                state.update { it.copy(isTcfActive = true, isSheetVisible = false) }
                runTcfOnce(reopen = true, sheetOnFallback = true)
            }
        } finally {
            tcfRunning = false
            tcfOpenQueued = false
        }
    }

    private suspend fun runTcfOnce(
        reopen: Boolean,
        sheetOnFallback: Boolean,
    ) {
        val session =
            TcfSession(tcfHost, tcfKeys, store, tcfPageUrl(api.baseUrl, config.propertyKey), tcfReadyTimeoutMs, tcfAnswerTimeoutMs)
        when (val outcome = session.run(subjectId, reopen) { config.onOpen?.invoke() }) {
            TcfOutcome.Current -> Unit
            TcfOutcome.Closed -> config.onClose?.invoke()
            is TcfOutcome.Decided -> applyTcfConsent(outcome.consent, outcome.scope)
            TcfOutcome.NotApplicable -> {
                canOpenTcf = false
                fallBackToSheet(sheetOnFallback)
            }
            TcfOutcome.Off -> {
                canOpenTcf = false
                retireTcfKeys()
                fallBackToSheet(sheetOnFallback)
            }
            is TcfOutcome.Failed -> {
                reportTcfFailure(outcome)
                fallBackToSheet(sheetOnFallback)
            }
        }
    }

    // A failure leaves canOpenTcf as it was, so the next explicit open tries the TCF screens again.
    private fun fallBackToSheet(show: Boolean) {
        state.update { it.copy(isTcfActive = false) }
        if (show) showSheet()
    }

    // A decision made on the category sheet replaces any TC string this SDK wrote earlier.
    private fun supersedeTcfDecision() {
        if (!isTcfActive) clearTcfDecision()
    }

    private fun reportTcfFailure(failure: TcfOutcome.Failed) {
        logger.warn("[ConsentIQ] TCF unavailable (${failure.code}); using the consent sheet")
        failure.message?.let { logger.debug("[ConsentIQ] TCF: $it") }
        config.onError?.invoke(ErrorInfo(failure.code, failure.message))
    }

    // The page has already recorded this consent with the TC string, so it is applied locally and not submitted again.
    private fun applyTcfConsent(
        consent: Map<String, Boolean>,
        scope: TcfScope?,
    ) {
        if (consent.isEmpty()) return
        val next = withNecessary(consent)
        state.update { it.copy(consent = next, isSheetVisible = false) }
        store.saveConsent(next)
        config.onConsentChange?.invoke(next, state.value.marketingConsent)
        when (scope ?: inferScope(next)) {
            TcfScope.ALL -> config.onAccept?.invoke(AcceptInfo(scope = "all", consent = next))
            TcfScope.NONE -> config.onReject?.invoke(RejectInfo(consent = next))
            TcfScope.CUSTOM -> config.onSave?.invoke(SaveInfo(consent = next))
        }
    }

    private fun inferScope(consent: Map<String, Boolean>): TcfScope {
        val enabled =
            state.value.config
                ?.categories
                ?.filter { it.enabled }
                .orEmpty()
        return when {
            enabled.all { consent[it.code] == true } -> TcfScope.ALL
            enabled.all { it.isRequired || consent[it.code] != true } -> TcfScope.NONE
            else -> TcfScope.CUSTOM
        }
    }

    fun hideSheet() {
        state.update { it.copy(isSheetVisible = false) }
    }

    fun dismissSheet() {
        state.update { it.copy(isSheetVisible = false) }
        config.onClose?.invoke()
    }

    fun resetConsent() {
        state.update { it.copy(consent = DEFAULT_CONSENT, marketingConsent = emptyMap()) }
        store.clearAll()
        clearTcfDecision()
        subjectId = resolveSubjectId()
    }

    // TCF was turned off for the property: drop what this SDK wrote so vendor SDKs stop reading an old consent.
    private fun retireTcfKeys() {
        val cmpId = store.tcfCmpId() ?: return
        store.clearTcf()
        store.clearTcfCmpId()
        try {
            tcfKeys.clearAll(cmpId)
        } catch (e: RuntimeException) {
            logger.warn("[ConsentIQ] Could not clear the TCF keys")
        }
    }

    private fun clearTcfDecision() {
        store.clearTcf()
        val cmpId = store.tcfCmpId() ?: return
        try {
            tcfKeys.clearDecision(cmpId)
        } catch (e: RuntimeException) {
            logger.warn("[ConsentIQ] Could not clear the TCF keys")
        }
    }

    private fun resolveSubjectId(): String =
        config.subjectId?.takeIf { it.isNotBlank() }
            ?: if (config.autoGenerateSubjectId) store.getOrCreateSubjectId() else ""

    private suspend fun submit(
        cookie: Map<String, Boolean>,
        marketing: Map<String, Boolean>,
    ) {
        if (subjectId.isEmpty()) {
            logger.debug("[ConsentIQ] No subject id; consent kept locally only")
            return
        }
        val captchaToken =
            if (loadedConfig.requiresCaptcha()) {
                captcha.tokenOrWarn(logger) ?: return
            } else {
                null
            }
        try {
            api.submitConsent(
                ConsentSubmission(
                    subjectId = subjectId,
                    cookieConsents = cookie,
                    marketingConsents = marketing,
                    consentMethod = "sdk",
                    captchaToken = captchaToken,
                ),
            )
            store.saveConsent(cookie)
            config.onConsentChange?.invoke(cookie, marketing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[ConsentIQ] Failed to submit consent: ${e.message}")
        }
    }

    private fun withNecessary(consent: Map<String, Boolean>): Map<String, Boolean> = LinkedHashMap(consent).apply { put(NECESSARY, true) }

    companion object {
        const val NECESSARY = "necessary"
    }
}
