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
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Bumped by resetConsent, so a submit that started before a reset cannot store its 429 after it.
    private var resetGeneration = 0

    // When the server's own record for this person was made, from the last lookup.
    private var serverConsentedAtMs: Long? = null

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
                // A decision dropped unrecorded leaves nothing granted: neither an older server record nor
                // the local copy answers for it, so the sheet asks again.
                if (!store.isUnrecorded(config.propertyKey)) found = restoreConsent()
                when (resendPending()) {
                    ResendOutcome.RECORDED -> found = true
                    ResendOutcome.DROPPED -> found = false
                    ResendOutcome.NONE -> Unit
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

    private suspend fun restoreConsent(): Boolean {
        // An id the app chose is read only with its identity token; without one the local copy stands,
        // since the open lookup reads only ids this SDK generated.
        val siteSubject = subjectSource != null
        val lookup =
            if (siteSubject && config.identityToken == null) {
                null
            } else {
                try {
                    api.getConsent(subjectId, identityTokenForSubject)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConsentIQException) {
                    if (e.status == 403) {
                        // The token did not verify for this subject, so this device's copy cannot answer
                        // for them: the sheet asks again, and the app is told.
                        logger.error("[ConsentIQ] Consent lookup refused: ${e.message}")
                        store.clearConsent()
                        config.onError?.invoke(ErrorInfo(CONSENT_REFUSED_ERROR, e.message))
                        return false
                    }
                    logger.warn("[ConsentIQ] Could not restore consent: ${e.message}")
                    null
                } catch (e: Exception) {
                    logger.warn("[ConsentIQ] Could not restore consent: ${e.message}")
                    null
                }
            }
        val existing = lookup?.consent
        serverConsentedAtMs = existing?.consentedAt?.let(::parseTimestampMs)
        when {
            existing != null -> {
                val cookie = withNecessary(existing.cookieConsents)
                state.update { it.copy(consent = cookie, marketingConsent = existing.marketingConsents) }
                store.saveConsent(cookie, subjectId)
                return true
            }
            lookup?.expired == true -> {
                store.clearConsent()
                clearTcfDecision()
            }
            else -> {
                val local = store.loadConsent(subjectId)
                if (local != null) {
                    state.update { it.copy(consent = withNecessary(local)) }
                    return true
                }
            }
        }
        return false
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
        val outcome =
            session.run(subjectId, reopen, siteSubject = subjectSource != null, identityToken = identityTokenForSubject) {
                config.onOpen?.invoke()
            }
        when (outcome) {
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
        if (subjectId.isNotEmpty()) store.saveConsent(next, subjectId)
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
        resetGeneration++
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

    private fun resolveSubjectId(): String {
        val provided = config.subjectId?.takeIf { it.isNotBlank() }
        if (provided != null) {
            if (isValidSubjectId(provided)) return provided
            logger.error("[ConsentIQ] $INVALID_SUBJECT_ID_MESSAGE")
            config.onError?.invoke(ErrorInfo(INVALID_SUBJECT_ID_ERROR, INVALID_SUBJECT_ID_MESSAGE))
            return ""
        }
        return if (config.autoGenerateSubjectId) store.getOrCreateSubjectId() else ""
    }

    // The app's own subject id, rather than one this SDK generated.
    private val subjectSource: String?
        get() = if (!config.subjectId.isNullOrBlank() && subjectId == config.subjectId) SITE_SUBJECT_SOURCE else null

    // The identity token, only where it can verify: with the app's own subject id. A generated id is
    // random, so a token signed for the app's user never matches it.
    private val identityTokenForSubject: String?
        get() = if (subjectSource != null) config.identityToken else null

    private val noticeVersion: Int?
        get() = loadedConfig?.banner?.noticeVersion

    private val categoryCodes: List<String>
        get() =
            loadedConfig
                ?.categories
                ?.map { it.code }
                ?.sorted()
                .orEmpty()

    private class UnrecordedDecision(
        val cookie: Map<String, Boolean>,
        val marketing: Map<String, Boolean>,
        val subjectId: String,
        val generation: Int,
    )

    private enum class ResendOutcome { NONE, RECORDED, DROPPED }

    private suspend fun submit(
        cookie: Map<String, Boolean>,
        marketing: Map<String, Boolean>,
    ) {
        if (subjectId.isEmpty()) {
            logger.debug("[ConsentIQ] No subject id; consent kept locally only")
            return
        }
        // The decision applies at once and stands locally; the server record makes it stand across launches.
        store.saveConsent(cookie, subjectId)
        val decision = UnrecordedDecision(cookie, marketing, subjectId, resetGeneration)
        val captchaToken =
            if (loadedConfig.requiresCaptcha()) {
                captcha.tokenOrWarn(logger) ?: return settleFailure(null, CAPTCHA_FAILED_WARNING, decision)
            } else {
                null
            }
        try {
            submitAnsweringChallenge(captchaToken, { captcha }, logger) { token ->
                api.submitConsent(
                    ConsentSubmission(
                        subjectId = decision.subjectId,
                        cookieConsents = cookie,
                        marketingConsents = marketing,
                        consentMethod = "sdk",
                        captchaToken = token,
                        identityToken = identityTokenForSubject,
                        subjectSource = subjectSource,
                    ),
                )
            } ?: return settleFailure(null, CAPTCHA_FAILED_WARNING, decision)
            if (decision.generation != resetGeneration) return
            store.clearPending()
            store.clearUnrecorded()
            config.onConsentChange?.invoke(cookie, marketing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConsentIQException) {
            logger.error("[ConsentIQ] Failed to submit consent: ${e.message}")
            settleFailure(e.status, e.message, decision)
        } catch (e: Exception) {
            logger.error("[ConsentIQ] Failed to submit consent: ${e.message}")
            settleFailure(null, e.message, decision)
        }
    }

    // A submit ConsentIQ did not record. Retryable (no answer, a 5xx, a 429, an unanswered challenge)
    // keeps it as the pending decision, replacing any earlier one. Anything else, or any failure on a
    // property that requires a captcha on every submit (a resend cannot carry one), is final: the
    // decision and its local copy are dropped, the next launch asks again, and the app is told.
    private fun settleFailure(
        status: Int?,
        message: String?,
        decision: UnrecordedDecision,
    ) {
        // A submit that started before a reset belongs to the state the reset cleared.
        if (decision.generation != resetGeneration) return
        if ((status != null && isFinalStatus(status)) || loadedConfig.requiresCaptcha()) {
            dropUnrecordedDecision()
            config.onError?.invoke(ErrorInfo(CONSENT_REFUSED_ERROR, message))
            return
        }
        store.savePending(
            PendingDecision(
                decision.cookie,
                decision.marketing,
                0,
                now(),
                decision.subjectId,
                config.propertyKey,
                config.apiUrl,
                noticeVersion,
                categoryCodes,
            ),
        )
    }

    // The decision was never recorded and never will be: its local copy goes with it, and the next launch
    // asks again with nothing granted rather than restoring an older choice.
    private fun dropUnrecordedDecision() {
        store.clearPending()
        store.clearConsent()
        store.markUnrecorded(config.propertyKey)
    }

    // At launch the dropped decision's local copy may already be applied, so it is taken back too.
    private fun dropAtLaunch(): ResendOutcome {
        dropUnrecordedDecision()
        state.update { it.copy(consent = DEFAULT_CONSENT, marketingConsent = emptyMap()) }
        return ResendOutcome.DROPPED
    }

    // Resends a decision ConsentIQ has not recorded, once per launch and never with a captcha.
    private suspend fun resendPending(): ResendOutcome {
        val pending = store.loadPending() ?: return ResendOutcome.NONE
        if (!pending.belongsTo(subjectId, config.propertyKey, config.apiUrl)) {
            // Another person's, property's or server's: never sent here.
            store.clearPending()
            return ResendOutcome.NONE
        }
        // A record the server made after this decision is the newer choice, so it wins.
        if (serverConsentedAtMs?.let { it > pending.createdAtMs } == true) {
            store.clearPending()
            return ResendOutcome.NONE
        }
        if (pending.isSpent(now()) || !pending.matches(noticeVersion, categoryCodes) || loadedConfig.requiresCaptcha()) {
            return dropAtLaunch()
        }
        store.savePending(pending.copy(attempts = pending.attempts + 1))
        try {
            api.submitConsent(
                ConsentSubmission(
                    subjectId = subjectId,
                    cookieConsents = pending.cookieConsents,
                    marketingConsents = pending.marketingConsents,
                    consentMethod = "sdk",
                    identityToken = identityTokenForSubject,
                    subjectSource = subjectSource,
                    noticeVersion = pending.noticeVersion,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConsentIQException) {
            logger.debug("[ConsentIQ] Pending consent not recorded: ${e.message}")
            if (isFinalStatus(e.status)) {
                config.onError?.invoke(ErrorInfo(CONSENT_REFUSED_ERROR, e.message))
                return dropAtLaunch()
            }
            return ResendOutcome.NONE
        } catch (e: Exception) {
            logger.debug("[ConsentIQ] Pending consent not recorded: ${e.message}")
            return ResendOutcome.NONE
        }
        store.clearPending()
        store.clearUnrecorded()
        val cookie = withNecessary(pending.cookieConsents)
        state.update { it.copy(consent = cookie, marketingConsent = pending.marketingConsents) }
        store.saveConsent(cookie, subjectId)
        config.onConsentChange?.invoke(cookie, pending.marketingConsents)
        return ResendOutcome.RECORDED
    }

    // java.time needs API 26; this library supports 24.
    private fun parseTimestampMs(value: String): Long? =
        listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
            runCatching {
                java.text
                    .SimpleDateFormat(pattern, java.util.Locale.US)
                    .apply { isLenient = false }
                    .parse(value)
                    ?.time
            }.getOrNull()
        }

    private fun withNecessary(consent: Map<String, Boolean>): Map<String, Boolean> = LinkedHashMap(consent).apply { put(NECESSARY, true) }

    companion object {
        const val NECESSARY = "necessary"
    }
}
