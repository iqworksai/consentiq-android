package ai.iqworks.consentiq

import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

internal const val TCF_UNSUPPORTED = "tcf-unsupported"
internal const val TCF_UNAVAILABLE = "tcf-unavailable"
internal const val TCF_TIMEOUT = "tcf-timeout"
internal const val TCF_LOAD_FAILED = "tcf-load-failed"
internal const val TCF_PAGE_ERROR = "tcf-page-error"

internal const val TCF_OFF = "tcf-off"

internal const val TCF_READY_TIMEOUT_MS = 15_000L

// The page reads the config and the vendor list before it can answer.
internal const val TCF_ANSWER_TIMEOUT_MS = 45_000L

// Covers the page's bounded consent record, so a decision made just before back still lands.
internal const val TCF_CLOSE_GRACE_MS = 10_000L
internal const val TCF_KEY_PREFIX = "IABTCF_"
internal const val TCF_GDPR_APPLIES = "IABTCF_gdprApplies"
internal const val TCF_CMP_SDK_ID = "IABTCF_CmpSdkID"
internal const val TCF_CMP_SDK_VERSION = "IABTCF_CmpSdkVersion"
internal const val TCF_PUBLISHER_RESTRICTIONS = "IABTCF_PublisherRestrictions"
internal val TCF_RESTRICTION_PURPOSES = 1..11

// What a decision writes, less CmpSdkID, CmpSdkVersion and gdprApplies, which stay true after a reset.
internal val TCF_DECISION_KEYS: List<String> =
    listOf(
        "IABTCF_TCString",
        "IABTCF_VendorConsents",
        "IABTCF_VendorLegitimateInterests",
        "IABTCF_DisclosedVendors",
        "IABTCF_PurposeConsents",
        "IABTCF_PurposeLegitimateInterests",
        "IABTCF_SpecialFeaturesOptIns",
        "IABTCF_PublisherConsent",
        "IABTCF_PublisherLegitimateInterests",
        "IABTCF_PublisherCustomPurposesConsents",
        "IABTCF_PublisherCustomPurposesLegitimateInterests",
        "IABTCF_PolicyVersion",
        "IABTCF_PublisherCC",
        "IABTCF_PurposeOneTreatment",
        "IABTCF_UseNonStandardTexts",
    ) + TCF_RESTRICTION_PURPOSES.map { "$TCF_PUBLISHER_RESTRICTIONS$it" }

private const val MIN_CMP_ID = 2

internal fun TcfSettings?.isCmpActive(): Boolean = this != null && enabled && cmpId >= MIN_CMP_ID

internal fun tcfPageUrl(
    baseUrl: String,
    propertyKey: String,
): String = "${baseUrl.trimEnd('/')}/api/public/tcf/mobile?key=${URLEncoder.encode(propertyKey, "UTF-8")}"

internal sealed interface TcfMessage {
    data object Ready : TcfMessage

    data object Show : TcfMessage

    data object Current : TcfMessage

    data object NotApplicable : TcfMessage

    data object Closed : TcfMessage

    data class Decision(
        val tcString: String,
        val keys: Map<String, Any>,
        val stored: String,
        val consent: Map<String, Boolean>,
        val scope: TcfScope? = null,
    ) : TcfMessage

    data class Error(
        val code: String?,
    ) : TcfMessage
}

internal fun parseTcfMessage(data: String?): TcfMessage? = parseTcfEvent(data)?.message

internal fun parseTcfEvent(data: String?): TcfEvent.Page? {
    if (data == null) return null
    val json =
        try {
            JSONObject(data)
        } catch (e: JSONException) {
            return null
        }
    val message = parseTcfMessage(json) ?: return null
    return TcfEvent.Page(message, json.stringOrNull("token"))
}

private fun parseTcfMessage(json: JSONObject): TcfMessage? =
    when (json.stringOrNull("type")) {
        "ready" -> TcfMessage.Ready
        "show" -> TcfMessage.Show
        "current" -> TcfMessage.Current
        "not-applicable" -> TcfMessage.NotApplicable
        "closed" -> TcfMessage.Closed
        "decision" -> parseDecision(json) ?: TcfMessage.Error("invalid-decision")
        "error" -> TcfMessage.Error(json.stringOrNull("code"))
        else -> null
    }

private fun parseDecision(json: JSONObject): TcfMessage.Decision? {
    val tcString = json.stringOrNull("tcString")?.takeIf { it.isNotBlank() } ?: return null
    val keys = parseTcfKeys(json.objectOrNull("keys") ?: return null)
    if (keys.isEmpty()) return null
    val stored = json.objectOrNull("stored") ?: return null
    return TcfMessage.Decision(
        tcString,
        keys,
        stored.toString(),
        booleanMap(json.objectOrNull("consent")),
        parseTcfScope(json.stringOrNull("scope")),
    )
}

internal enum class TcfScope { ALL, NONE, CUSTOM }

internal fun parseTcfScope(value: String?): TcfScope? =
    when (value) {
        "all" -> TcfScope.ALL
        "none" -> TcfScope.NONE
        "custom" -> TcfScope.CUSTOM
        else -> null
    }

// Only IABTCF_ keys reach the app's default preferences, and only as the two types the spec allows.
internal fun parseTcfKeys(json: JSONObject): Map<String, Any> {
    val keys = LinkedHashMap<String, Any>()
    for (key in json.keys()) {
        if (!key.startsWith(TCF_KEY_PREFIX)) continue
        when (val value = json.opt(key)) {
            is String -> keys[key] = value
            is Number -> value.asIntOrNull()?.let { keys[key] = it }
        }
    }
    return keys
}

private fun Number.asIntOrNull(): Int? {
    val asDouble = toDouble()
    if (asDouble % 1.0 != 0.0 || asDouble < Int.MIN_VALUE || asDouble > Int.MAX_VALUE) return null
    return asDouble.toInt()
}

internal fun tcfStartScript(
    subjectId: String,
    stored: JSONObject?,
    consent: Map<String, Boolean>?,
    reopen: Boolean,
    token: String,
): String {
    val options =
        JSONObject()
            .put("subjectId", subjectId)
            .put("stored", stored ?: JSONObject.NULL)
            .put("consent", consent?.toJson() ?: JSONObject.NULL)
            .put("reopen", reopen)
            .put("token", token)
    return "window.consentiqTcfStart($options)"
}

internal fun newTcfToken(): String = UUID.randomUUID().toString()

internal enum class TcfNavigation { ALLOW, BLOCK, EXTERNAL }

internal fun tcfNavigation(
    url: String,
    isMainFrame: Boolean,
    pageUrl: String,
    shown: Boolean,
): TcfNavigation =
    when {
        isAllowedCaptchaNavigation(url, isMainFrame, pageUrl) -> TcfNavigation.ALLOW
        isMainFrame && shown && originOf(url) != null -> TcfNavigation.EXTERNAL
        else -> TcfNavigation.BLOCK
    }

internal interface TcfKeyWriter {
    fun write(keys: Map<String, Any>)

    fun writeGdprApplies(value: Int)

    fun writeCmp(
        id: Int,
        version: Int?,
    )

    fun clearDecision(cmpId: Int)

    fun clearAll(cmpId: Int)

    companion object {
        val NONE: TcfKeyWriter =
            object : TcfKeyWriter {
                override fun write(keys: Map<String, Any>) {}

                override fun clearDecision(cmpId: Int) {}

                override fun clearAll(cmpId: Int) {}

                override fun writeGdprApplies(value: Int) {}

                override fun writeCmp(
                    id: Int,
                    version: Int?,
                ) {}
            }
    }
}

internal sealed interface TcfEvent {
    data class Page(
        val message: TcfMessage,
        val token: String? = null,
    ) : TcfEvent

    data class Failed(
        val reason: String,
    ) : TcfEvent

    data class Back(
        val interacted: Boolean,
    ) : TcfEvent
}

internal class TcfException(
    val code: String,
    reason: String,
) : Exception(reason)

internal interface TcfPage {
    fun start(script: String)

    fun present()

    fun dismiss()

    fun release()
}

internal interface TcfHost {
    fun unavailable(): TcfOutcome.Failed?

    fun open(
        url: String,
        reopen: Boolean,
        onEvent: (TcfEvent) -> Unit,
    ): TcfPage

    companion object {
        val UNAVAILABLE: TcfHost =
            object : TcfHost {
                override fun unavailable(): TcfOutcome.Failed = TcfOutcome.Failed(TCF_UNAVAILABLE, "no web view host")

                override fun open(
                    url: String,
                    reopen: Boolean,
                    onEvent: (TcfEvent) -> Unit,
                ): TcfPage = throw TcfException(TCF_UNAVAILABLE, "no web view host")
            }
    }
}

internal sealed interface TcfOutcome {
    data object Current : TcfOutcome

    data object NotApplicable : TcfOutcome

    data object Off : TcfOutcome

    data object Closed : TcfOutcome

    data class Decided(
        val consent: Map<String, Boolean>,
        val scope: TcfScope? = null,
    ) : TcfOutcome

    data class Failed(
        val code: String,
        val message: String?,
    ) : TcfOutcome
}
