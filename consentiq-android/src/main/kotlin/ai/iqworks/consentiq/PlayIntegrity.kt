package ai.iqworks.consentiq

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume

internal const val INTEGRITY_HEADER = "X-ConsentIQ-Integrity"

/** A Play Integrity token bound to [nonce], or null where none can be had. */
internal fun interface IntegrityTokenSource {
    suspend fun token(nonce: String): String?
}

// What binds the token to this exact submission: base64url, unpadded, of the body's SHA-256. Classic
// requests take it as the nonce, which must be web-safe base64 without wrapping, 16 to 500 characters.
internal fun integrityNonce(body: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))

private const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

// java.util.Base64 needs API 26, above this SDK's minimum.
internal fun base64Url(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
        out.append(BASE64_URL[b0 shr 2])
        out.append(BASE64_URL[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
        if (b1 >= 0) out.append(BASE64_URL[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
        if (b2 >= 0) out.append(BASE64_URL[b2 and 0x3f])
        i += 3
    }
    return out.toString()
}

/**
 * Proves a submission comes from this app, installed from Google Play on a genuine device. Where the
 * check cannot run (no Play services, an emulator, an app not installed from Play, the daily quota spent)
 * it returns no headers and ConsentIQ asks for a captcha instead.
 */
internal class AppAttestation(
    private val source: IntegrityTokenSource?,
    private val logger: ConsentIQLogger,
    private val hash: (String) -> String = ::integrityNonce,
) {
    suspend fun headers(body: String): Map<String, String> {
        val source = source ?: return emptyMap()
        val token =
            try {
                source.token(hash(body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("[ConsentIQ] Play Integrity unavailable: ${e.message}")
                null
            }
        return if (token.isNullOrEmpty()) emptyMap() else mapOf(INTEGRITY_HEADER to token)
    }
}

// Classic requests, not the Standard API: only a Classic token can be decrypted with the response keys
// the customer downloads from Play Console, which is how ConsentIQ verifies it without calling Google.
internal class PlayIntegrityTokenSource(
    private val context: Context,
    private val cloudProjectNumber: Long? = null,
    private val timeoutMs: Long = PLAY_INTEGRITY_TIMEOUT_MS,
) : IntegrityTokenSource {
    override suspend fun token(nonce: String): String? =
        withinPlayTimeout(timeoutMs) {
            val request = IntegrityTokenRequest.builder().setNonce(nonce)
            cloudProjectNumber?.let { request.setCloudProjectNumber(it) }
            IntegrityManagerFactory
                .create(context.applicationContext)
                .requestIntegrityToken(request.build())
                .await()
                .token()
        }
}

internal const val PLAY_INTEGRITY_TIMEOUT_MS = 10_000L

// A plain exception, not a CancellationException, so the caller falls back to the captcha.
internal class PlayIntegrityUnavailableException(
    reason: String,
) : Exception("Play Integrity $reason")

internal suspend fun <T : Any> withinPlayTimeout(
    timeoutMs: Long,
    block: suspend () -> T,
): T = withTimeoutOrNull(timeoutMs) { block() } ?: throw PlayIntegrityUnavailableException("timeout")

internal suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWith(Result.failure(it)) }
        addOnCanceledListener { continuation.resumeWith(Result.failure(PlayIntegrityUnavailableException("cancelled"))) }
    }
