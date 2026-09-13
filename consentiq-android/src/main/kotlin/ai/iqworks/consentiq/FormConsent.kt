package ai.iqworks.consentiq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public data class FormConsentResult(
    val consentId: String,
    val proofHash: String,
    val consents: Map<String, Boolean>,
)

public class FormConsent(
    propertyKey: String,
    apiUrl: String = DEFAULT_API_URL,
    private val subjectId: String,
    private val subjectType: String = "user",
    private val channels: List<String> = DEFAULT_CHANNELS,
    private val onConsentSubmitted: ((FormConsentResult) -> Unit)? = null,
    private val onConsentChange: ((Map<String, Boolean>) -> Unit)? = null,
    private val api: ConsentIQApiClient = ConsentIQApiClient(propertyKey, apiUrl),
    private val logger: ConsentIQLogger = ConsentIQLogger.NONE,
) {
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
            val result =
                api.submitConsent(
                    ConsentSubmission(
                        subjectId = subjectId,
                        subjectType = subjectType,
                        marketingConsents = snapshot,
                        consentMethod = "form",
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

    public companion object {
        public val DEFAULT_CHANNELS: List<String> = listOf("email", "sms", "whatsapp", "push")
    }
}
