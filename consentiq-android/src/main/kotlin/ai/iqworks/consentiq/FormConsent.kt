package ai.iqworks.consentiq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public data class FormConsentResult(
    val consentId: String,
    val proofHash: String,
    val consents: Map<String, Boolean>,
)

public class FormConsent internal constructor(
    private val propertyKey: String,
    private val subjectId: String,
    private val subjectType: String,
    private val channels: List<String>,
    private val onConsentSubmitted: ((FormConsentResult) -> Unit)?,
    private val onConsentChange: ((Map<String, Boolean>) -> Unit)?,
    private val api: ConsentIQApiClient,
    private val logger: ConsentIQLogger,
    private val sharedConfig: (propertyKey: String, baseUrl: String) -> PropertyConfig?,
    private val captcha: () -> CaptchaTokenProvider,
) {
    public constructor(
        propertyKey: String,
        apiUrl: String = DEFAULT_API_URL,
        subjectId: String,
        subjectType: String = "user",
        channels: List<String> = DEFAULT_CHANNELS,
        onConsentSubmitted: ((FormConsentResult) -> Unit)? = null,
        onConsentChange: ((Map<String, Boolean>) -> Unit)? = null,
        api: ConsentIQApiClient = ConsentIQApiClient(propertyKey, apiUrl),
        logger: ConsentIQLogger = ConsentIQLogger.NONE,
    ) : this(
        propertyKey = propertyKey,
        subjectId = subjectId,
        subjectType = subjectType,
        channels = channels,
        onConsentSubmitted = onConsentSubmitted,
        onConsentChange = onConsentChange,
        api = api,
        logger = logger,
        sharedConfig = ConsentIQ::loadedConfigFor,
        captcha = { ConsentIQ.captchaProviderFor(api.baseUrl) },
    )

    private val configMutex = Mutex()

    @Volatile
    private var fetchedConfig: PropertyConfig? = null

    private val mutableConsents = MutableStateFlow(channels.associateWith { false })
    public val consents: StateFlow<Map<String, Boolean>> = mutableConsents.asStateFlow()

    private val mutableSubmitting = MutableStateFlow(false)
    public val isSubmitting: StateFlow<Boolean> = mutableSubmitting.asStateFlow()

    public fun toggleChannel(channel: String) {
        setChannel(channel, mutableConsents.value[channel] != true)
    }

    public fun setChannel(
        channel: String,
        value: Boolean,
    ) {
        mutableConsents.update { it + (channel to value) }
        onConsentChange?.invoke(mutableConsents.value)
    }

    public suspend fun submit(): FormConsentResult? {
        if (subjectId.isBlank()) return null
        mutableSubmitting.value = true
        try {
            val snapshot = mutableConsents.value
            val captchaToken =
                if (propertyConfig().requiresCaptcha()) {
                    captcha().tokenOrWarn(logger) ?: return null
                } else {
                    null
                }
            val result =
                api.submitConsent(
                    ConsentSubmission(
                        subjectId = subjectId,
                        subjectType = subjectType,
                        marketingConsents = snapshot,
                        consentMethod = "form",
                        captchaToken = captchaToken,
                    ),
                )
            val outcome = FormConsentResult(result.consentId, result.proofHash, snapshot)
            onConsentSubmitted?.invoke(outcome)
            return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[ConsentIQ] Failed to submit form consent: ${e.message}")
            return null
        } finally {
            mutableSubmitting.value = false
        }
    }

    public fun reset() {
        mutableConsents.update { channels.associateWith { false } }
    }

    private suspend fun propertyConfig(): PropertyConfig? {
        sharedConfig(propertyKey, api.baseUrl)?.let { return it }
        fetchedConfig?.let { return it }
        return configMutex.withLock {
            fetchedConfig ?: try {
                api.getConfig().also { fetchedConfig = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("[ConsentIQ] Could not load the property config before submitting form consent")
                null
            }
        }
    }

    public companion object {
        public val DEFAULT_CHANNELS: List<String> = listOf("email", "sms", "whatsapp", "push")
    }
}
