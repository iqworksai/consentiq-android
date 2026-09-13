package ai.iqworks.consentiq

public data class ConsentIQConfig(
    val propertyKey: String,
    val apiUrl: String = DEFAULT_API_URL,
    val subjectId: String? = null,
    val autoGenerateSubjectId: Boolean = true,
    val autoShow: Boolean = true,
    val language: String? = null,
    val debug: Boolean = false,
    val onConsentChange: ((consent: Map<String, Boolean>, marketing: Map<String, Boolean>) -> Unit)? = null,
    val onOpen: (() -> Unit)? = null,
    val onAccept: ((AcceptInfo) -> Unit)? = null,
    val onReject: ((RejectInfo) -> Unit)? = null,
    val onSave: ((SaveInfo) -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
)

public data class ConsentIQState(
    val consent: Map<String, Boolean> = DEFAULT_CONSENT,
    val marketingConsent: Map<String, Boolean> = emptyMap(),
    val config: PropertyConfig? = null,
    val isLoading: Boolean = true,
    val isSheetVisible: Boolean = false,
    val language: String = "en",
    val regulation: String? = null,
) {
    public fun hasConsent(category: String): Boolean = consent[category] == true

    public fun hasMarketingConsent(channel: String): Boolean = marketingConsent[channel] == true
}
