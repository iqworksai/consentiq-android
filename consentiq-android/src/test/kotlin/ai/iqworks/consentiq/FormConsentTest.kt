package ai.iqworks.consentiq

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FormConsentTest {
    private val dispatcher = StandardTestDispatcher()
    private val http = FakeHttpExecutor()
    private val logger = RecordingLogger()
    private val submitted = mutableListOf<FormConsentResult>()
    private val changes = mutableListOf<Map<String, Boolean>>()

    private val configPath = "/api/public/config/${Fixtures.PROPERTY_KEY}"

    @Before
    fun setUp() {
        http.on("GET", configPath, body = Fixtures.configJson())
    }

    private fun form(
        subjectId: String = "user-1",
        channels: List<String> = FormConsent.DEFAULT_CHANNELS,
        sharedConfig: PropertyConfig? = null,
        captcha: CaptchaTokenProvider = CaptchaTokenProvider.UNAVAILABLE,
    ) = FormConsent(
        propertyKey = Fixtures.PROPERTY_KEY,
        subjectId = subjectId,
        subjectType = "user",
        channels = channels,
        onConsentSubmitted = { submitted += it },
        onConsentChange = { changes += it },
        api = ConsentIQApiClient(Fixtures.PROPERTY_KEY, "http://stub", http, dispatcher),
        logger = logger,
        sharedConfig = { key, baseUrl -> sharedConfig?.takeIf { key == Fixtures.PROPERTY_KEY && baseUrl == "http://stub" } },
        captcha = { captcha },
    )

    private fun captchaConfig(): PropertyConfig = parsePropertyConfig(JSONObject(Fixtures.configJson(requireCaptcha = true)))

    private fun configRequests() = http.requests.count { it.method == "GET" && it.url.endsWith(configPath) }

    @Test
    fun defaultsToTheFourChannelsOff() {
        assertEquals(
            mapOf("email" to false, "sms" to false, "whatsapp" to false, "push" to false),
            FormConsent(propertyKey = Fixtures.PROPERTY_KEY, subjectId = "user-1").consents.value,
        )
    }

    @Test
    fun toggleAndSetReportChanges() {
        val form = form()

        form.toggleChannel("email")
        form.setChannel("sms", true)
        form.toggleChannel("sms")

        assertTrue(form.consents.value.getValue("email"))
        assertFalse(form.consents.value.getValue("sms"))
        assertEquals(3, changes.size)
        assertEquals(form.consents.value, changes.last())
    }

    @Test
    fun submitPostsMarketingConsentsAsUserFormConsent() =
        runTest(dispatcher) {
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val form = form(channels = listOf("email", "sms"))
            form.setChannel("email", true)

            val result = form.submit()

            assertNotNull(result)
            assertEquals("c-123", result!!.consentId)
            assertEquals("abc", result.proofHash)
            assertEquals(mapOf("email" to true, "sms" to false), result.consents)
            val body = http.posts().single()
            assertEquals(Fixtures.PROPERTY_KEY, body.getString("propertyKey"))
            assertEquals("user-1", body.getString("subjectId"))
            assertEquals("user", body.getString("subjectType"))
            assertEquals("form", body.getString("consentMethod"))
            assertFalse(body.has("cookieConsents"))
            assertTrue(body.getJSONObject("marketingConsents").getBoolean("email"))
            assertFalse(body.getJSONObject("marketingConsents").getBoolean("sms"))
            assertEquals(listOf(result), submitted)
            assertFalse(form.isSubmitting.value)
        }

    @Test
    fun submitWithoutSubjectIdIsANoOp() =
        runTest(dispatcher) {
            assertNull(form(subjectId = "").submit())
            assertTrue(http.requests.isEmpty())
        }

    @Test
    fun failedSubmitLogsAndReturnsNull() =
        runTest(dispatcher) {
            http.on("POST", "/api/public/consent", status = 500, body = """{"error":"Failed to save consent"}""")
            val form = form()

            assertNull(form.submit())

            assertEquals(listOf("[ConsentIQ] Failed to submit form consent: Failed to save consent"), logger.errors)
            assertTrue(submitted.isEmpty())
            assertFalse(form.isSubmitting.value)
        }

    @Test
    fun captchaTokenComesFromTheSharedConfigWithoutFetchingAgain() =
        runTest(dispatcher) {
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val captcha = FakeCaptchaProvider { "tok" }
            val form = form(sharedConfig = captchaConfig(), captcha = captcha)

            assertNotNull(form.submit())

            assertEquals("tok", http.posts().single().getString("captchaToken"))
            assertEquals(1, captcha.calls)
            assertEquals(0, configRequests())
        }

    @Test
    fun configIsFetchedOncePerInstanceWhenNotShared() =
        runTest(dispatcher) {
            http.on("GET", configPath, body = Fixtures.configJson(requireCaptcha = true))
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val captcha = FakeCaptchaProvider { "tok" }
            val form = form(captcha = captcha)

            assertNotNull(form.submit())
            assertNotNull(form.submit())

            assertEquals(1, configRequests())
            assertEquals(2, captcha.calls)
            assertTrue(http.posts().all { it.getString("captchaToken") == "tok" })
        }

    @Test
    fun captchaFailureSendsNothingAndReturnsNull() =
        runTest(dispatcher) {
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val form = form(sharedConfig = captchaConfig(), captcha = FakeCaptchaProvider { throw CaptchaException("timeout") })
            form.setChannel("email", true)

            assertNull(form.submit())

            assertTrue(http.posts().isEmpty())
            assertTrue(submitted.isEmpty())
            assertTrue(form.consents.value.getValue("email"))
            assertFalse(form.isSubmitting.value)
            assertEquals(listOf("[ConsentIQ] Captcha check failed; consent not submitted"), logger.warnings)
            assertTrue(logger.errors.isEmpty())
        }

    @Test
    fun captchaIsSkippedWhenNotRequired() =
        runTest(dispatcher) {
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val captcha = FakeCaptchaProvider()

            assertNotNull(form(captcha = captcha).submit())

            assertEquals(0, captcha.calls)
            assertFalse(http.posts().single().has("captchaToken"))
        }

    @Test
    fun configFetchFailureStillSubmitsWithoutAToken() =
        runTest(dispatcher) {
            http.on("GET", configPath, status = 503, body = "{}")
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val captcha = FakeCaptchaProvider()
            val form = form(captcha = captcha)

            assertNotNull(form.submit())
            assertNotNull(form.submit())

            assertEquals(0, captcha.calls)
            assertEquals(2, configRequests())
            assertTrue(http.posts().none { it.has("captchaToken") })
        }

    @Test
    fun resetTurnsEveryChannelOff() {
        val form = form(channels = listOf("email", "push"))
        form.setChannel("email", true)
        form.setChannel("push", true)

        form.reset()

        assertEquals(mapOf("email" to false, "push" to false), form.consents.value)
    }
}
