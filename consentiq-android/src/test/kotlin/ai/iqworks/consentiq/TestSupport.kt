package ai.iqworks.consentiq

import org.json.JSONObject

class InMemoryStorage : ConsentStorage {
    val values = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class RecordingLogger : ConsentIQLogger {
    val debugs = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun debug(message: String) {
        debugs += message
    }

    override fun warn(message: String) {
        warnings += message
    }

    override fun error(message: String) {
        errors += message
    }
}

class FakeHttpExecutor : HttpExecutor {
    val requests = mutableListOf<HttpRequest>()
    private val routes = LinkedHashMap<String, (HttpRequest) -> HttpResponse>()

    fun on(
        method: String,
        path: String,
        handler: (HttpRequest) -> HttpResponse,
    ) {
        routes["$method $path"] = handler
    }

    fun on(
        method: String,
        path: String,
        status: Int = 200,
        body: String,
    ) = on(method, path) { HttpResponse(status, body) }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val path =
            request.url
                .substringAfter("://")
                .substringAfter('/')
                .let { "/$it" }
        val handler = routes["${request.method} $path"] ?: return HttpResponse(404, """{"error":"No route for ${request.method} $path"}""")
        return handler(request)
    }

    fun posts(): List<JSONObject> = requests.filter { it.method == "POST" }.map { JSONObject(it.body!!) }
}

internal class FakeCaptchaProvider(
    private val result: () -> String = { "tok" },
) : CaptchaTokenProvider {
    var calls = 0

    override suspend fun token(): String {
        calls++
        return result()
    }
}

object Fixtures {
    const val PROPERTY_KEY = "pk_test_android"
    const val SITE_KEY = "0x4AAAAAAAtest"

    fun configJson(
        requireCaptcha: Boolean = false,
        turnstileSiteKey: String? = if (requireCaptcha) SITE_KEY else null,
        defaultLanguage: String = "en",
        noticeUrl: String? = "https://consent.example.test/api/public/privacy/pk_test_android?notice=n1",
        primaryColor: String? = "#123456",
        resolvedRegulation: String? = "DPDPA",
    ): String {
        val notice = if (noticeUrl == null) "null" else "\"$noticeUrl\""
        val color = if (primaryColor == null) "" else "\"primaryColor\": \"$primaryColor\","
        val regulation = if (resolvedRegulation == null) "null" else "\"$resolvedRegulation\""
        val siteKey = if (turnstileSiteKey == null) "null" else "\"$turnstileSiteKey\""
        return """
            {
              "property": {
                "id": "prop-1", "name": "Android App", "identifier": "ai.iqworks.example", "platform": "android",
                "defaultLanguage": "$defaultLanguage", "supportedLanguages": ["en", "ar"]
              },
              "banner": {
                "position": "bottom", "layout": "banner", "theme": "dark", "showLogo": false,
                $color
                "cornerStyle": "rounded", "showCookieIcon": false, "overlay": false,
                "noticeUrl": $notice
              },
              "categories": [
                { "code": "necessary", "enabled": true, "defaultState": true, "name": "Necessary", "description": "Required", "isRequired": true },
                { "code": "analytics", "enabled": true, "defaultState": false, "name": "Analytics", "isRequired": false },
                { "code": "marketing", "enabled": true, "defaultState": false, "name": "Marketing", "description": null, "isRequired": false }
              ],
              "marketingChannels": [
                { "code": "email", "name": "Email", "description": "Email updates", "requiresDoubleOptin": false }
              ],
              "geoRules": [ { "countries": ["IN"], "regulation": "DPDPA", "requireExplicitConsent": true } ],
              "resolvedRegulation": $regulation,
              "privacyNotices": [ { "id": "n1", "name": "Cookie notice", "contentTranslations": { "en": "<p>Hi</p>" }, "noticeType": "cookie", "version": 3 } ],
              "formConsentSources": [ { "selector": null, "isAutoResolved": false, "privacyNotice": { "id": "n1", "name": "Cookie notice", "type": "cookie" } } ],
              "privacyNoticeUrl": "/api/public/privacy/pk_test_android",
              "requireCaptcha": $requireCaptcha,
              "turnstileSiteKey": $siteKey,
              "autoBlock": false,
              "translations": {
                "en": { "bannerDescription": "We use cookies.", "categories": { "analytics": { "title": "Analytics", "description": "Usage stats" } } },
                "ar": { "bannerDescription": null, "categories": { "analytics": { "title": "التحليلات", "description": "إحصاءات" } } }
              },
              "labels": {
                "en": { "title": "We value your privacy", "acceptAll": "Accept all", "acceptRequired": "Accept only required", "reject": "Reject all", "customize": "Customize", "save": "Save preferences", "preferencesTitle": "Cookie preferences", "viewNotice": "View cookie notice" },
                "ar": { "title": "نحن نحترم خصوصيتك", "acceptAll": "قبول الكل", "acceptRequired": "قبول الضروري فقط", "reject": "رفض الكل", "customize": "تخصيص", "save": "حفظ التفضيلات", "preferencesTitle": "تفضيلات", "viewNotice": "عرض الإشعار" }
              },
              "rtl": ["ar", "ur", "ks", "sd"],
              "someFutureField": { "ignored": true }
            }
            """.trimIndent()
    }

    fun savedConsentJson(
        analytics: Boolean = true,
        marketing: Boolean = false,
        email: Boolean = true,
    ): String =
        """
        {
          "consent": {
            "id": "consent-1",
            "cookieConsents": { "necessary": true, "analytics": $analytics, "marketing": $marketing, "preferences": false, "social": false },
            "marketingConsents": { "email": $email },
            "consentedAt": "2026-09-01T00:00:00.000Z",
            "expiresAt": null,
            "regulationApplied": "DPDPA",
            "consentVersion": null
          }
        }
        """.trimIndent()

    const val NO_CONSENT = """{"consent":null}"""
    const val EXPIRED = """{"consent":null,"expired":true}"""
    const val SUBMIT_OK = """{"success":true,"consentId":"c-123","proofHash":"abc"}"""
}
