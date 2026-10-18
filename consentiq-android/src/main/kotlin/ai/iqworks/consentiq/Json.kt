package ai.iqworks.consentiq

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.objectOrNull(key: String): JSONObject? = if (has(key) && !isNull(key)) optJSONObject(key) else null

internal fun JSONObject.arrayOrEmpty(key: String): JSONArray =
    if (has(key) && !isNull(key)) optJSONArray(key) ?: JSONArray() else JSONArray()

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }

internal fun booleanMap(json: JSONObject?): Map<String, Boolean> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, Boolean>()
    for (key in json.keys()) {
        if (!json.isNull(key)) result[key] = json.optBoolean(key, false)
    }
    return result
}

internal fun Map<String, Boolean>.toJson(): JSONObject {
    val json = JSONObject()
    for ((key, value) in this) json.put(key, value)
    return json
}

internal fun parsePropertyConfig(json: JSONObject): PropertyConfig {
    val property = json.getJSONObject("property")
    val banner = json.objectOrNull("banner") ?: JSONObject()
    return PropertyConfig(
        property =
            PropertyInfo(
                id = property.optString("id"),
                name = property.optString("name"),
                identifier = property.optString("identifier"),
                platform = property.optString("platform"),
                defaultLanguage = property.stringOrNull("defaultLanguage"),
                supportedLanguages = property.arrayOrEmpty("supportedLanguages").strings(),
            ),
        banner =
            BannerConfig(
                position = banner.stringOrNull("position"),
                layout = banner.stringOrNull("layout"),
                theme = banner.stringOrNull("theme"),
                showLogo = banner.optBoolean("showLogo", false),
                logoUrl = banner.stringOrNull("logoUrl"),
                primaryColor = banner.stringOrNull("primaryColor"),
                cornerStyle = banner.stringOrNull("cornerStyle"),
                showCookieIcon = banner.optBoolean("showCookieIcon", false),
                cookieIconUrl = banner.stringOrNull("cookieIconUrl"),
                overlay = banner.optBoolean("overlay", false),
                noticeUrl = banner.stringOrNull("noticeUrl"),
            ),
        categories =
            json.arrayOrEmpty("categories").objects().map {
                CategoryInfo(
                    code = it.optString("code"),
                    enabled = it.optBoolean("enabled", true),
                    defaultState = it.optBoolean("defaultState", false),
                    name = it.optString("name"),
                    description = it.stringOrNull("description"),
                    isRequired = it.optBoolean("isRequired", false),
                )
            },
        marketingChannels =
            json.arrayOrEmpty("marketingChannels").objects().map {
                MarketingChannelInfo(
                    code = it.optString("code"),
                    name = it.optString("name"),
                    description = it.stringOrNull("description"),
                    requiresDoubleOptin = it.optBoolean("requiresDoubleOptin", false),
                )
            },
        geoRules =
            json.arrayOrEmpty("geoRules").objects().map {
                GeoRule(
                    countries = it.arrayOrEmpty("countries").strings(),
                    regulation = it.optString("regulation"),
                    requireExplicitConsent = it.optBoolean("requireExplicitConsent", false),
                )
            },
        resolvedRegulation = json.stringOrNull("resolvedRegulation"),
        privacyNotices =
            json.arrayOrEmpty("privacyNotices").objects().map {
                PrivacyNoticeInfo(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    contentTranslations = stringMap(it.objectOrNull("contentTranslations")),
                    noticeType = it.stringOrNull("noticeType"),
                    version = it.intOrNull("version"),
                )
            },
        formConsentSources =
            json.arrayOrEmpty("formConsentSources").objects().map {
                val notice = it.objectOrNull("privacyNotice") ?: JSONObject()
                FormConsentSource(
                    selector = it.stringOrNull("selector"),
                    isAutoResolved = it.optBoolean("isAutoResolved", false),
                    privacyNotice =
                        FormConsentNotice(
                            id = notice.optString("id"),
                            name = notice.optString("name"),
                            type = notice.stringOrNull("type"),
                        ),
                )
            },
        privacyNoticeUrl = json.stringOrNull("privacyNoticeUrl"),
        requireCaptcha = json.optBoolean("requireCaptcha", false),
        turnstileSiteKey = json.stringOrNull("turnstileSiteKey"),
        autoBlock = json.optBoolean("autoBlock", false),
        translations = parseTranslations(json.objectOrNull("translations")),
        labels = parseLabels(json.objectOrNull("labels")),
        rtl = json.arrayOrEmpty("rtl").strings(),
    )
}

private fun stringMap(json: JSONObject?): Map<String, String> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (key in json.keys()) {
        if (!json.isNull(key)) result[key] = json.opt(key).toString()
    }
    return result
}

private fun parseTranslations(json: JSONObject?): Map<String, TranslationSet> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, TranslationSet>()
    for (lang in json.keys()) {
        val set = json.optJSONObject(lang) ?: continue
        val categories = LinkedHashMap<String, CategoryTranslation>()
        val cats = set.objectOrNull("categories")
        if (cats != null) {
            for (code in cats.keys()) {
                val entry = cats.optJSONObject(code) ?: continue
                categories[code] =
                    CategoryTranslation(
                        title = entry.optString("title"),
                        description = entry.optString("description"),
                    )
            }
        }
        result[lang] = TranslationSet(bannerDescription = set.stringOrNull("bannerDescription"), categories = categories)
    }
    return result
}

private fun parseLabels(json: JSONObject?): Map<String, BannerLabels> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, BannerLabels>()
    for (lang in json.keys()) {
        val entry = json.optJSONObject(lang) ?: continue
        result[lang] =
            BannerLabels(
                title = entry.optString("title"),
                acceptAll = entry.optString("acceptAll"),
                acceptRequired = entry.optString("acceptRequired"),
                reject = entry.optString("reject"),
                customize = entry.optString("customize"),
                save = entry.optString("save"),
                preferencesTitle = entry.optString("preferencesTitle"),
                viewNotice = entry.optString("viewNotice"),
            )
    }
    return result
}

internal fun parseSavedConsent(json: JSONObject): SavedConsent =
    SavedConsent(
        id = json.optString("id"),
        cookieConsents = booleanMap(json.objectOrNull("cookieConsents")),
        marketingConsents = booleanMap(json.objectOrNull("marketingConsents")),
        consentedAt = json.optString("consentedAt"),
        expiresAt = json.stringOrNull("expiresAt"),
        regulationApplied = json.stringOrNull("regulationApplied"),
        consentVersion = json.stringOrNull("consentVersion"),
    )

internal fun parseConsentLookup(json: JSONObject): ConsentLookup =
    ConsentLookup(
        consent = json.objectOrNull("consent")?.let(::parseSavedConsent),
        expired = json.optBoolean("expired", false),
    )

internal fun parseSubmitResult(json: JSONObject): SubmitResult =
    SubmitResult(
        success = json.optBoolean("success", false),
        consentId = json.optString("consentId"),
        proofHash = json.optString("proofHash"),
    )

internal fun parseVerifyResult(json: JSONObject): VerifyResult =
    VerifyResult(
        verified = json.optBoolean("verified", false),
        status = json.stringOrNull("status"),
        statusMessage = json.stringOrNull("statusMessage"),
        consent =
            json.objectOrNull("consent")?.let {
                VerifiedConsent(
                    id = it.optString("id"),
                    consentedAt = it.optString("consentedAt"),
                    proofHash = it.stringOrNull("proofHash"),
                    expiresAt = it.stringOrNull("expiresAt"),
                    regulationApplied = it.stringOrNull("regulationApplied"),
                    consentMethod = it.stringOrNull("consentMethod"),
                )
            },
    )

internal fun ConsentSubmission.toJson(propertyKey: String): JSONObject {
    val json = JSONObject()
    json.put("propertyKey", propertyKey)
    json.put("subjectId", subjectId)
    subjectType?.let { json.put("subjectType", it) }
    cookieConsents?.let { json.put("cookieConsents", it.toJson()) }
    marketingConsents?.let { json.put("marketingConsents", it.toJson()) }
    geoCountry?.let { json.put("geoCountry", it) }
    geoRegion?.let { json.put("geoRegion", it) }
    geoSource?.let { json.put("geoSource", it) }
    json.put("consentMethod", consentMethod)
    consentVersion?.let { json.put("consentVersion", it) }
    captchaToken?.let { json.put("captchaToken", it) }
    return json
}
