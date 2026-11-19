package ai.iqworks.consentiq

public data class ConsentIQConfig(
    val propertyKey: String,
    val apiUrl: String = DEFAULT_API_URL,
    val subjectId: String? = null,
    val autoGenerateSubjectId: Boolean = true,
    val autoShow: Boolean = true,
    val language: String? = null,
    val debug: Boolean = false,
    // Your server's signature for this person, sent unread with every submission for the subjectId you supply.
    val identityToken: String? = null,
    // Only for an app also distributed outside Google Play; a Play app needs no project number.
    val playIntegrityCloudProjectNumber: Long? = null,
    val onConsentChange: ((consent: Map<String, Boolean>, marketing: Map<String, Boolean>) -> Unit)? = null,
    val onOpen: (() -> Unit)? = null,
    val onAccept: ((AcceptInfo) -> Unit)? = null,
    val onReject: ((RejectInfo) -> Unit)? = null,
    val onSave: ((SaveInfo) -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
    val onError: ((ErrorInfo) -> Unit)? = null,
)

internal const val SITE_SUBJECT_SOURCE = "site"

// ConsentIQ refuses a subject id containing ':', which would let it pose as an id of another kind.
internal fun isValidSubjectId(subjectId: String): Boolean = ':' !in subjectId

internal const val INVALID_SUBJECT_ID_ERROR = "invalid_subject_id"
internal const val INVALID_SUBJECT_ID_MESSAGE = "ConsentIQ subjectId may not contain ':'"

public data class ConsentIQState(
    val consent: Map<String, Boolean> = DEFAULT_CONSENT,
    val marketingConsent: Map<String, Boolean> = emptyMap(),
    val config: PropertyConfig? = null,
    val isLoading: Boolean = true,
    val isSheetVisible: Boolean = false,
    val language: String = "en",
    val regulation: String? = null,
    val isTcfActive: Boolean = false,
) {
    public fun hasConsent(category: String): Boolean = consent[category] == true

    public fun hasMarketingConsent(channel: String): Boolean = marketingConsent[channel] == true
}
