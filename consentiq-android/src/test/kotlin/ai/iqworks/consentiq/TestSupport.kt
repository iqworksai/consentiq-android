package ai.iqworks.consentiq

import android.content.SharedPreferences
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

class FakeSharedPreferences : SharedPreferences {
    val values = LinkedHashMap<String, Any>()
    var applies = 0
    var commits = 0

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = (values[key] ?: return defValue) as String

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    // Mirrors the platform: reading a key back as the wrong type throws instead of converting.
    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = (values[key] ?: return defValue) as Int

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = (values[key] ?: return defValue) as Long

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = (values[key] ?: return defValue) as Float

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = (values[key] ?: return defValue) as Boolean

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    private inner class Editor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any>()
        private val removes = mutableSetOf<String>()

        private fun put(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            if (value == null) removes += key else puts[key] = value
            return this
        }

        override fun putString(
            key: String,
            value: String?,
        ) = put(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ) = put(key, values)

        override fun putInt(
            key: String,
            value: Int,
        ) = put(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ) = put(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ) = put(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removes += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            removes += values.keys
            return this
        }

        override fun commit(): Boolean {
            commits++
            flush()
            return true
        }

        override fun apply() {
            applies++
            flush()
        }

        private fun flush() {
            for (key in removes) values.remove(key)
            values.putAll(puts)
        }
    }
}

internal class FakeTcfPage(
    private val onEvent: (TcfEvent) -> Unit,
    private val onStart: (page: FakeTcfPage) -> Unit,
) : TcfPage {
    val scripts = mutableListOf<String>()
    var token: String? = null
    var presented = 0
    var dismissed = 0
    var released = 0

    // Page events without a token get this run's token, as the real page stamps every message after start.
    fun send(event: TcfEvent) {
        onEvent(if (event is TcfEvent.Page && event.token == null && token != null) event.copy(token = token) else event)
    }

    override fun start(script: String) {
        scripts += script
        token = startOptions(script).getString("token")
        onStart(this)
    }

    override fun present() {
        presented++
    }

    override fun dismiss() {
        dismissed++
    }

    override fun release() {
        released++
    }
}

internal class FakeTcfHost(
    var unavailableWith: TcfOutcome.Failed? = null,
    var sendReady: Boolean = true,
    var openFailure: RuntimeException? = null,
    var onStart: (page: FakeTcfPage) -> Unit = {},
) : TcfHost {
    val opened = mutableListOf<Pair<String, Boolean>>()
    val pages = mutableListOf<FakeTcfPage>()

    override fun unavailable(): TcfOutcome.Failed? = unavailableWith

    override fun open(
        url: String,
        reopen: Boolean,
        onEvent: (TcfEvent) -> Unit,
    ): TcfPage {
        opened += url to reopen
        openFailure?.let { throw it }
        val page = FakeTcfPage(onEvent) { onStart(it) }
        pages += page
        if (sendReady) onEvent(TcfEvent.Page(TcfMessage.Ready))
        return page
    }
}

internal fun startOptions(script: String): JSONObject = JSONObject(script.removePrefix("window.consentiqTcfStart(").removeSuffix(")"))

internal fun pageJson(json: String): TcfEvent = TcfEvent.Page(requireNotNull(parseTcfMessage(json)))

internal fun pageEvent(type: String): TcfEvent = pageJson("""{"type":"$type"}""")

object Fixtures {
    const val PROPERTY_KEY = "pk_test_android"
    const val SITE_KEY = "0x4AAAAAAAtest"

    fun configJson(
        requireCaptcha: Boolean = false,
        turnstileSiteKey: String? = if (requireCaptcha) SITE_KEY else null,
        defaultLanguage: String = "en",
        noticeUrl: String? = "https://consent.example.test/api/public/privacy/pk_test_android?notice=n1",
        primaryColor: String? = "#123456",
        bannerColors: String = "",
        resolvedRegulation: String? = "DPDPA",
        tcf: String? = null,
    ): String {
        val tcfBlock = if (tcf == null) "" else "\"tcf\": $tcf,"
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
                $bannerColors
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
              $tcfBlock
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

    fun tcfJson(
        enabled: Boolean = true,
        gdprApplies: Boolean = true,
        cmpId: Int = 2,
        cmpVersion: Int = 1,
    ): String =
        """
        {
          "enabled": $enabled, "gdprApplies": $gdprApplies, "vendorIds": [1, 755],
          "publisherCountryCode": "DE",
          "publisherRestrictions": [ { "purposeId": 2, "restrictionType": 1, "vendorIds": [755] } ],
          "repromptMonths": 13, "tcfPolicyVersion": 5, "cmpId": $cmpId, "cmpVersion": $cmpVersion,
          "vendorListUrl": "/api/public/tcf/pk_test_android/vendor-list?v=abc",
          "someFutureField": true
        }
        """.trimIndent()

    const val TCF_STRING = "CQAbCdEAbCdEAAHABBENBdFgAP_gAEPgAAAAKYtV"

    fun decisionJson(
        consent: String = """{"necessary":true,"analytics":true,"marketing":false}""",
        restrictions: String = ", \"IABTCF_PublisherRestrictions2\": \"_1_\"",
        scope: String? = null,
    ): String =
        """
        {
          "type": "decision",
          "tcString": "$TCF_STRING",
          "keys": {
            "IABTCF_CmpSdkID": 2, "IABTCF_CmpSdkVersion": 1, "IABTCF_PolicyVersion": 5, "IABTCF_gdprApplies": 1,
            "IABTCF_PublisherCC": "DE", "IABTCF_PurposeOneTreatment": 0, "IABTCF_UseNonStandardTexts": 0,
            "IABTCF_TCString": "$TCF_STRING", "IABTCF_VendorConsents": "0101", "IABTCF_PurposeConsents": "1100000000"$restrictions
          },
          "stored": { "tcString": "$TCF_STRING", "vendorIds": [1, 755], "restrictionsKey": "[]" },
          "consent": $consent${if (scope == null) "" else ", \"scope\": \"$scope\""}
        }
        """.trimIndent()

    const val NO_CONSENT = """{"consent":null}"""
    const val EXPIRED = """{"consent":null,"expired":true}"""
    const val SUBMIT_OK = """{"success":true,"consentId":"c-123","proofHash":"abc"}"""
}
