package ai.iqworks.consentiq

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

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

internal fun captchaPageUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/api/public/captcha/mobile"

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
        "token" -> json.stringOrNull("token")?.takeIf { it.isNotBlank() }?.let { CaptchaMessage.Token(it) } ?: CaptchaMessage.Error(null)
        "error" -> CaptchaMessage.Error(json.stringOrNull("code"))
        else -> null
    }
}
