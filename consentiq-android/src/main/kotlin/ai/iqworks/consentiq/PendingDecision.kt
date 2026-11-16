package ai.iqworks.consentiq

import org.json.JSONObject

/** A decision ConsentIQ asked to resend later (429), retried once per launch without a captcha. */
internal data class PendingDecision(
    val cookieConsents: Map<String, Boolean>,
    val marketingConsents: Map<String, Boolean>,
    val attempts: Int,
    val createdAtMs: Long,
) {
    fun isSpent(nowMs: Long): Boolean = attempts >= MAX_ATTEMPTS || nowMs - createdAtMs > MAX_AGE_MS

    fun toJson(): JSONObject =
        JSONObject()
            .put("cookieConsents", cookieConsents.toJson())
            .put("marketingConsents", marketingConsents.toJson())
            .put("attempts", attempts)
            .put("createdAtMs", createdAtMs)

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun fromJson(json: JSONObject): PendingDecision =
            PendingDecision(
                cookieConsents = booleanMap(json.getJSONObject("cookieConsents")),
                marketingConsents = booleanMap(json.getJSONObject("marketingConsents")),
                attempts = json.getInt("attempts"),
                createdAtMs = json.getLong("createdAtMs"),
            )
    }
}

// A refusal that resending the same decision cannot change.
internal val ConsentIQException.dropsTheDecision: Boolean
    get() = status == 400 || status == 404 || status == 409 || status == 422

internal const val CONSENT_REFUSED_ERROR = "consent_refused"
