package ai.iqworks.consentiq

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayIntegrityTest {
    @Test
    fun base64UrlMatchesTheRfcVectorsUnpadded() {
        val vectors =
            mapOf(
                "" to "",
                "f" to "Zg",
                "fo" to "Zm8",
                "foo" to "Zm9v",
                "foob" to "Zm9vYg",
                "fooba" to "Zm9vYmE",
                "foobar" to "Zm9vYmFy",
            )
        for ((input, expected) in vectors) assertEquals(input, expected, base64Url(input.toByteArray()))
        assertEquals("-_-_", base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte())))
    }

    @Test
    fun theRequestHashIsTheBodysSha256() {
        assertEquals("AVq9f1zFei3ZS3WQ8ErYCEJzkF7jPsXOvq5iJ2qX-GI", integrityRequestHash("""{"a":1}"""))
    }

    @Test
    fun aTokenBecomesTheHeaderAndNothingElseDoes() =
        runTest {
            val hashes = mutableListOf<String>()
            val logger = RecordingLogger()

            val signed =
                AppAttestation(
                    { hash ->
                        hashes += hash
                        "token-1"
                    },
                    logger,
                ).headers("body")
            assertEquals(mapOf("X-ConsentIQ-Integrity" to "token-1"), signed)
            assertEquals(listOf(integrityRequestHash("body")), hashes)

            assertEquals(emptyMap<String, String>(), AppAttestation(null, logger).headers("body"))
            assertEquals(emptyMap<String, String>(), AppAttestation({ null }, logger).headers("body"))
            assertEquals(
                emptyMap<String, String>(),
                AppAttestation({ throw IllegalStateException("no play services") }, logger).headers("body"),
            )
            assertTrue(logger.debugs.any { it.contains("Play Integrity unavailable") })
        }

    @Test
    fun aSubmitCarriesATokenBoundToItsExactBody() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val http = FakeHttpExecutor()
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val hashes = mutableListOf<String>()
            val api = ConsentIQApiClient(Fixtures.PROPERTY_KEY, "http://stub", http, dispatcher)
            api.attestation =
                AppAttestation(
                    { hash ->
                        hashes += hash
                        "token-1"
                    },
                    RecordingLogger(),
                )

            api.submitConsent(ConsentSubmission(subjectId = "s", cookieConsents = mapOf("necessary" to true)))

            val request = http.requests.single()
            assertEquals("token-1", request.headers["X-ConsentIQ-Integrity"])
            assertEquals(listOf(integrityRequestHash(request.body!!)), hashes)
        }

    @Test
    fun withoutAttestationASubmitSendsNoHeader() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val http = FakeHttpExecutor()
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val api = ConsentIQApiClient(Fixtures.PROPERTY_KEY, "http://stub", http, dispatcher)

            api.submitConsent(ConsentSubmission(subjectId = "s"))

            assertFalse(
                http.requests
                    .single()
                    .headers
                    .containsKey("X-ConsentIQ-Integrity"),
            )
        }
}

class PlayIntegrityTimeoutTest {
    @Test
    fun aPlayCallThatNeverAnswersFallsBackInsteadOfHanging() =
        kotlinx.coroutines.test.runTest {
            val failure =
                runCatching { withinPlayTimeout(1_000L) { kotlinx.coroutines.awaitCancellation() } }.exceptionOrNull()

            assertTrue(failure is PlayIntegrityUnavailableException)
        }

    @Test
    fun aCancelledPlayTaskFailsTheCallInsteadOfHanging() =
        kotlinx.coroutines.test.runTest {
            val failure = runCatching { CancelledTask<String>().await() }.exceptionOrNull()

            assertTrue(failure is PlayIntegrityUnavailableException)
        }

    @Test
    fun aHungPlayCallLeavesNoHeaderSoTheCaptchaCoversIt() =
        kotlinx.coroutines.test.runTest {
            val attestation =
                AppAttestation(
                    IntegrityTokenSource { withinPlayTimeout(1_000L) { kotlinx.coroutines.awaitCancellation() } },
                    RecordingLogger(),
                )

            assertEquals(emptyMap<String, String>(), attestation.headers("{}"))
        }
}

private class CancelledTask<T> : com.google.android.gms.tasks.Task<T>() {
    override fun isComplete() = true

    override fun isSuccessful() = false

    override fun isCanceled() = true

    override fun getResult(): T = throw IllegalStateException()

    override fun <X : Throwable> getResult(exceptionType: Class<X>): T = throw IllegalStateException()

    override fun getException(): Exception? = null

    override fun addOnSuccessListener(listener: com.google.android.gms.tasks.OnSuccessListener<in T>) = this

    override fun addOnSuccessListener(
        executor: java.util.concurrent.Executor,
        listener: com.google.android.gms.tasks.OnSuccessListener<in T>,
    ) = this

    override fun addOnSuccessListener(
        activity: android.app.Activity,
        listener: com.google.android.gms.tasks.OnSuccessListener<in T>,
    ) = this

    override fun addOnFailureListener(listener: com.google.android.gms.tasks.OnFailureListener) = this

    override fun addOnFailureListener(
        executor: java.util.concurrent.Executor,
        listener: com.google.android.gms.tasks.OnFailureListener,
    ) = this

    override fun addOnFailureListener(
        activity: android.app.Activity,
        listener: com.google.android.gms.tasks.OnFailureListener,
    ) = this

    override fun addOnCanceledListener(listener: com.google.android.gms.tasks.OnCanceledListener): com.google.android.gms.tasks.Task<T> {
        listener.onCanceled()
        return this
    }
}
