package ai.iqworks.consentiq

public const val DEFAULT_API_URL: String = "https://consent.iqworks.ai"

public val DEFAULT_CONSENT: Map<String, Boolean> =
    mapOf(
        "necessary" to true,
        "analytics" to false,
        "marketing" to false,
        "preferences" to false,
        "social" to false,
    )

public data class PropertyConfig(
    val property: PropertyInfo,
    val banner: BannerConfig,
    val categories: List<CategoryInfo>,
    val marketingChannels: List<MarketingChannelInfo>,
    val geoRules: List<GeoRule>,
    val resolvedRegulation: String?,
    val privacyNotices: List<PrivacyNoticeInfo>,
    val formConsentSources: List<FormConsentSource>,
    val privacyNoticeUrl: String?,
    val requireCaptcha: Boolean,
    val turnstileSiteKey: String?,
    val autoBlock: Boolean,
    val translations: Map<String, TranslationSet>,
    val labels: Map<String, BannerLabels>,
    val rtl: List<String>,
) {
    public fun labelsFor(language: String): BannerLabels? = labels[language] ?: labels[baseTag(language)] ?: labels["en"]

    public fun translationsFor(language: String): TranslationSet? =
        translations[language] ?: translations[baseTag(language)] ?: translations["en"]

    public fun isRtl(language: String): Boolean = rtl.contains(baseTag(language))
}

internal fun baseTag(language: String): String = language.substringBefore('-').lowercase()

public data class PropertyInfo(
    val id: String,
    val name: String,
    val identifier: String,
    val platform: String,
    val defaultLanguage: String?,
    val supportedLanguages: List<String>,
)

public data class BannerConfig(
    val position: String?,
    val layout: String?,
    val theme: String?,
    val showLogo: Boolean,
    val logoUrl: String?,
    val primaryColor: String?,
    val cornerStyle: String?,
    val showCookieIcon: Boolean,
    val cookieIconUrl: String?,
    val overlay: Boolean,
    val noticeUrl: String?,
)

public data class CategoryInfo(
    val code: String,
    val enabled: Boolean,
    val defaultState: Boolean,
    val name: String,
    val description: String?,
    val isRequired: Boolean,
)

public data class MarketingChannelInfo(
    val code: String,
    val name: String,
    val description: String?,
    val requiresDoubleOptin: Boolean,
)

public data class GeoRule(
    val countries: List<String>,
    val regulation: String,
    val requireExplicitConsent: Boolean,
)

public data class PrivacyNoticeInfo(
    val id: String,
    val name: String,
    val contentTranslations: Map<String, String>,
    val noticeType: String?,
    val version: Int?,
)

public data class FormConsentSource(
    val selector: String?,
    val isAutoResolved: Boolean,
    val privacyNotice: FormConsentNotice,
)

public data class FormConsentNotice(
    val id: String,
    val name: String,
    val type: String?,
)

public data class TranslationSet(
    val bannerDescription: String?,
    val categories: Map<String, CategoryTranslation>,
)

public data class CategoryTranslation(
    val title: String,
    val description: String,
)

public data class BannerLabels(
    val title: String,
    val acceptAll: String,
    val acceptRequired: String,
    val reject: String,
    val customize: String,
    val save: String,
    val preferencesTitle: String,
    val viewNotice: String,
)

public data class SavedConsent(
    val id: String,
    val cookieConsents: Map<String, Boolean>,
    val marketingConsents: Map<String, Boolean>,
    val consentedAt: String,
    val expiresAt: String?,
    val regulationApplied: String?,
    val consentVersion: String?,
)

public data class ConsentLookup(
    val consent: SavedConsent?,
    val expired: Boolean,
)

public data class ConsentSubmission(
    val subjectId: String,
    val subjectType: String? = null,
    val cookieConsents: Map<String, Boolean>? = null,
    val marketingConsents: Map<String, Boolean>? = null,
    val geoCountry: String? = null,
    val geoRegion: String? = null,
    val geoSource: String? = null,
    val consentMethod: String = "sdk",
    val consentVersion: String? = null,
)

public data class SubmitResult(
    val success: Boolean,
    val consentId: String,
    val proofHash: String,
)

public data class VerifyResult(
    val verified: Boolean,
    val status: String?,
    val statusMessage: String?,
    val consent: VerifiedConsent?,
)

public data class VerifiedConsent(
    val id: String,
    val consentedAt: String,
    val proofHash: String?,
    val expiresAt: String?,
    val regulationApplied: String?,
    val consentMethod: String?,
)

public data class AcceptInfo(
    val scope: String,
    val consent: Map<String, Boolean>,
)

public data class RejectInfo(
    val consent: Map<String, Boolean>,
)

public data class SaveInfo(
    val consent: Map<String, Boolean>,
)

public class ConsentIQException(
    message: String,
    public val status: Int,
) : Exception(message)
