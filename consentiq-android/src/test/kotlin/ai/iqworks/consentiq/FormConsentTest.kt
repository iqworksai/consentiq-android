package ai.iqworks.consentiq

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FormConsentTest {
    private val dispatcher = StandardTestDispatcher()
    private val http = FakeHttpExecutor()
    private val logger = RecordingLogger()
    private val submitted = mutableListOf<FormConsentResult>()
    private val changes = mutableListOf<Map<String, Boolean>>()

    private fun form(
        subjectId: String = "user-1",
        channels: List<String> = FormConsent.DEFAULT_CHANNELS,
    ) = FormConsent(
        propertyKey = Fixtures.PROPERTY_KEY,
        subjectId = subjectId,
        channels = channels,
        onConsentSubmitted = { submitted += it },
        onConsentChange = { changes += it },
        api = ConsentIQApiClient(Fixtures.PROPERTY_KEY, "http://stub", http, dispatcher),
        logger = logger,
    )

    @Test
    fun defaultsToTheFourChannelsOff() {
        assertEquals(
            mapOf("email" to false, "sms" to false, "whatsapp" to false, "push" to false),
            form().consents.value,
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
    fun resetTurnsEveryChannelOff() {
        val form = form(channels = listOf("email", "push"))
        form.setChannel("email", true)
        form.setChannel("push", true)

        form.reset()

        assertEquals(mapOf("email" to false, "push" to false), form.consents.value)
    }
}
