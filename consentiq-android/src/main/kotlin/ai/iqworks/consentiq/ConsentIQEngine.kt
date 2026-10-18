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
) {
    @Volatile
    var subjectId: String = ""
        private set

    @Volatile
    private var loadedConfig: PropertyConfig? = null

    fun loadedConfigFor(
        propertyKey: String,
        baseUrl: String,
    ): PropertyConfig? = loadedConfig?.takeIf { config.propertyKey == propertyKey && api.baseUrl == baseUrl.trimEnd('/') }

    suspend fun initialize() {
        state.value = ConsentIQState()
        loadedConfig = null
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
                    lookup?.expired == true -> store.clearConsent()
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
            if (config.autoShow && !found) {
                state.update { it.copy(isSheetVisible = true) }
                config.onOpen?.invoke()
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
        submit(rejected, state.value.marketingConsent)
        config.onReject?.invoke(RejectInfo(consent = rejected))
    }

    suspend fun savePreferences(consent: Map<String, Boolean>) {
        val next = withNecessary(consent)
        state.update { it.copy(consent = next, isSheetVisible = false) }
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
        state.update { it.copy(isSheetVisible = true) }
        config.onOpen?.invoke()
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
        subjectId = resolveSubjectId()
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
