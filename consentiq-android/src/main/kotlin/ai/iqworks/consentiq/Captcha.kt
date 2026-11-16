package ai.iqworks.consentiq

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

internal interface CaptchaTokenProvider {
    suspend fun token(): String

    companion object {
        val UNAVAILABLE: CaptchaTokenProvider =
            object : CaptchaTokenProvider {
                override suspend fun token(): String = throw CaptchaException("unavailable")
            }
    }
}

internal class CaptchaException(
    reason: String,
) : Exception(reason)

internal const val CAPTCHA_FAILED_WARNING = "[ConsentIQ] Captcha check failed; consent not submitted"

internal fun PropertyConfig?.requiresCaptcha(): Boolean = this != null && requireCaptcha && !turnstileSiteKey.isNullOrBlank()

internal suspend fun CaptchaTokenProvider.tokenOrWarn(logger: ConsentIQLogger): String? =
    try {
        token()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is CaptchaException) logger.debug("[ConsentIQ] Captcha check: ${e.message}")
        logger.warn(CAPTCHA_FAILED_WARNING)
        null
    }

// The key binds each token to the property, so it cannot be spent on another one.
internal fun captchaPageUrl(
    baseUrl: String,
    propertyKey: String,
): String = "${baseUrl.trimEnd('/')}/api/public/captcha/mobile?key=${URLEncoder.encode(propertyKey, "UTF-8")}"

// ConsentIQ asks for a captcha when a submission looks automated, whatever the property's setting.
internal val ConsentIQException.isCaptchaRequired: Boolean
    get() = status == 428 && message == "captcha_required"

/**
 * Sends a submission and, when the server answers 428 captcha_required, solves one challenge and
 * sends it once more. Returns null, having logged the captcha warning, when that challenge fails.
 */
internal suspend fun submitAnsweringChallenge(
    firstToken: String?,
    captcha: () -> CaptchaTokenProvider,
    logger: ConsentIQLogger,
    send: suspend (captchaToken: String?) -> SubmitResult,
): SubmitResult? =
    try {
        send(firstToken)
    } catch (e: ConsentIQException) {
        if (!e.isCaptchaRequired) throw e
        val token = captcha().tokenOrWarn(logger) ?: return null
        send(token)
    }

internal fun originOf(url: String): String? {
    val uri =
        try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val defaultPort =
        when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> return null
        }
    return if (uri.port == -1 || uri.port == defaultPort) "$scheme://$host" else "$scheme://$host:${uri.port}"
}

internal fun isAllowedCaptchaNavigation(
    url: String,
    isMainFrame: Boolean,
    pageUrl: String,
): Boolean =
    if (isMainFrame) {
        url == pageUrl
    } else {
        url.startsWith(CHALLENGES_PREFIX) || url in TURNSTILE_BLANK_FRAMES
    }

private const val CHALLENGES_PREFIX = "https://challenges.cloudflare.com/"

// Turnstile builds some of its frames from about:blank and about:srcdoc; Cloudflare requires both.
private val TURNSTILE_BLANK_FRAMES = setOf("about:blank", "about:srcdoc")

internal sealed interface CaptchaMessage {
    data object Ready : CaptchaMessage

    data object Interactive : CaptchaMessage

    data class Token(
        val token: String,
    ) : CaptchaMessage

    data class Error(
        val code: String?,
    ) : CaptchaMessage
}

internal fun parseCaptchaMessage(data: String?): CaptchaMessage? {
    if (data == null) return null
    val json =
        try {
            JSONObject(data)
        } catch (e: JSONException) {
            return null
        }
    return when (json.stringOrNull("type")) {
        "ready" -> CaptchaMessage.Ready
        "interactive" -> CaptchaMessage.Interactive
        "token" -> json.stringOrNull("token")?.takeIf { it.isNotBlank() }?.let { CaptchaMessage.Token(it) } ?: CaptchaMessage.Error(null)
        "error" -> CaptchaMessage.Error(json.stringOrNull("code"))
        else -> null
    }
}
