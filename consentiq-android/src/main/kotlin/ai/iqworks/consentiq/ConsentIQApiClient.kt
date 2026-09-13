package ai.iqworks.consentiq

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

public class ConsentIQApiClient(
    private val propertyKey: String,
    baseUrl: String = DEFAULT_API_URL,
    private val http: HttpExecutor = HttpUrlConnectionExecutor(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: ConsentIQLogger = ConsentIQLogger.NONE,
) {
    public val baseUrl: String = baseUrl.trimEnd('/')

    public suspend fun getConfig(): PropertyConfig {
        logger.debug("[ConsentIQ] Fetching config for property $propertyKey")
        return parsePropertyConfig(request("GET", "$baseUrl/api/public/config/$propertyKey"))
    }

    public suspend fun getConsent(subjectId: String): ConsentLookup {
        logger.debug("[ConsentIQ] Fetching consent for subject $subjectId")
        return parseConsentLookup(request("GET", "$baseUrl/api/public/consent/$propertyKey/$subjectId"))
    }

    public suspend fun submitConsent(submission: ConsentSubmission): SubmitResult {
        logger.debug("[ConsentIQ] Submitting consent for subject ${submission.subjectId}")
        return parseSubmitResult(request("POST", "$baseUrl/api/public/consent", submission.toJson(propertyKey)))
    }

    public suspend fun verifyConsent(consentId: String): VerifyResult =
        parseVerifyResult(request("GET", "$baseUrl/api/public/verify/$consentId"))

    private suspend fun request(
        method: String,
        url: String,
        body: JSONObject? = null,
    ): JSONObject {
        val response =
            withContext(ioDispatcher) {
                http.execute(HttpRequest(method = method, url = url, body = body?.toString()))
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
