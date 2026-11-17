package ai.iqworks.consentiq

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

public class ConsentIQApiClient(
    private val propertyKey: String,
    baseUrl: String = DEFAULT_API_URL,
    private val http: HttpExecutor = HttpUrlConnectionExecutor(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: ConsentIQLogger = ConsentIQLogger.NONE,
) {
    public val baseUrl: String = baseUrl.trimEnd('/')

    // Signs each submission so ConsentIQ knows it came from this app; null sends nothing.
    internal var attestation: AppAttestation? = null

    public suspend fun getConfig(): PropertyConfig {
        logger.debug("[ConsentIQ] Fetching config for property $propertyKey")
        return parsePropertyConfig(request("GET", "$baseUrl/api/public/config/$propertyKey"))
    }

    /**
     * The stored consent for [subjectId]. An id the app chose is read with its [identityToken], from the
     * records ConsentIQ verified; an id this SDK generated is read without one.
     */
    public suspend fun getConsent(
        subjectId: String,
        identityToken: String? = null,
    ): ConsentLookup {
        logger.debug("[ConsentIQ] Fetching consent")
        val url = "$baseUrl/api/public/consent/$propertyKey/${encodePathSegment(subjectId)}"
        if (identityToken == null) return parseConsentLookup(request("GET", url))
        return parseConsentLookup(request("POST", url, JSONObject().put("identityToken", identityToken).toString()))
    }

    public suspend fun submitConsent(submission: ConsentSubmission): SubmitResult {
        logger.debug("[ConsentIQ] Submitting consent via ${submission.consentMethod}")
        val body = submission.toJson(propertyKey).toString()
        val headers = attestation?.headers(body).orEmpty()
        return parseSubmitResult(request("POST", "$baseUrl/api/public/consent", body, headers))
    }

    public suspend fun verifyConsent(consentId: String): VerifyResult =
        parseVerifyResult(request("GET", "$baseUrl/api/public/verify/$consentId"))

    private suspend fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val response =
            withContext(ioDispatcher) {
                http.execute(HttpRequest(method = method, url = url, body = body, headers = headers))
            }
        if (response.status !in 200..299) {
            val message = runCatching { JSONObject(response.body).stringOrNull("error") }.getOrNull()
            throw ConsentIQException(message ?: "HTTP ${response.status}", response.status)
        }
        return try {
            JSONObject(response.body)
        } catch (e: org.json.JSONException) {
            throw ConsentIQException("Invalid JSON response", response.status)
        }
    }
}

internal fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
