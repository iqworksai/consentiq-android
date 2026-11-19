package ai.iqworks.consentiq

import org.json.JSONObject

/**
 * A decision ConsentIQ has not recorded yet (no answer, a 5xx, a 429, an unanswered challenge), resent
 * once per launch without a captcha.
 */
internal data class PendingDecision(
    val cookieConsents: Map<String, Boolean>,
    val marketingConsents: Map<String, Boolean>,
    val attempts: Int,
    val createdAtMs: Long,
    // Whose decision it is and where it goes: resent only to the same person, property and server.
    val subjectId: String,
    val propertyKey: String,
    val apiUrl: String,
    // What it was decided against: one made under another notice or category set is never resent.
    val noticeVersion: Int? = null,
    val categoryCodes: List<String> = emptyList(),
) {
    fun isSpent(nowMs: Long): Boolean = attempts >= MAX_ATTEMPTS || nowMs - createdAtMs > MAX_AGE_MS

    fun belongsTo(
        subjectId: String,
        propertyKey: String,
        apiUrl: String,
    ): Boolean = this.subjectId == subjectId && this.propertyKey == propertyKey && this.apiUrl == apiUrl

    fun matches(
        noticeVersion: Int?,
        categoryCodes: List<String>,
    ): Boolean = this.noticeVersion == noticeVersion && this.categoryCodes == categoryCodes

    fun toJson(): JSONObject =
        JSONObject()
            .put("cookieConsents", cookieConsents.toJson())
            .put("marketingConsents", marketingConsents.toJson())
            .put("attempts", attempts)
            .put("createdAtMs", createdAtMs)
            .put("subjectId", subjectId)
            .put("propertyKey", propertyKey)
            .put("apiUrl", apiUrl)
            .put("noticeVersion", noticeVersion ?: JSONObject.NULL)
            .put("categoryCodes", org.json.JSONArray(categoryCodes))

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun fromJson(json: JSONObject): PendingDecision =
            PendingDecision(
                cookieConsents = booleanMap(json.getJSONObject("cookieConsents")),
                marketingConsents = booleanMap(json.getJSONObject("marketingConsents")),
                attempts = json.getInt("attempts"),
                createdAtMs = json.getLong("createdAtMs"),
                subjectId = json.getString("subjectId"),
                propertyKey = json.getString("propertyKey"),
                apiUrl = json.getString("apiUrl"),
                noticeVersion = if (json.isNull("noticeVersion")) null else json.optInt("noticeVersion"),
                categoryCodes =
                    json.optJSONArray("categoryCodes")?.let { codes -> List(codes.length()) { codes.getString(it) } }.orEmpty(),
            )
    }
}

// A refusal that resending the same decision cannot change. No answer, a 5xx, a 429 or a challenge left
// unanswered may go through later.
internal fun isFinalStatus(status: Int): Boolean = status == 400 || status == 403 || status == 404 || status == 409 || status == 422

internal const val CONSENT_REFUSED_ERROR = "consent_refused"
