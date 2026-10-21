package ai.iqworks.consentiq

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TcfSessionTest {
    private val prefs = FakeSharedPreferences()
    private val storage = InMemoryStorage()
    private val host = FakeTcfHost()
    private val session =
        TcfSession(host, TcfKeyStore(prefs), ConsentStore(storage), "https://consent.iqworks.ai/api/public/tcf/mobile?key=k")
    private var shows = 0

    private suspend fun run(reopen: Boolean = false) = session.run("subject-1", reopen) { shows++ }

    @Test
    fun everyRunSendsAFreshTokenAndMessagesWithAnotherTokenAreDropped() =
        runTest {
            host.onStart = { page ->
                page.send(TcfEvent.Page(TcfMessage.Current, token = "forged"))
                page.send(pageJson(Fixtures.decisionJson()).let { (it as TcfEvent.Page).copy(token = "forged") })
                page.send(TcfEvent.Page(TcfMessage.NotApplicable, token = "also-forged"))
                page.send(pageEvent("current"))
            }

            assertEquals(TcfOutcome.Current, run())
            assertEquals(TcfOutcome.Current, run())

            val tokens = host.pages.map { it.token!! }
            assertEquals(2, tokens.toSet().size)
            assertTrue(tokens.all { it.isNotBlank() && it != "forged" })
            assertFalse(prefs.contains("IABTCF_TCString"))
            assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
        }

    @Test
    fun aMessageWithoutATokenAfterStartIsDropped() =
        runTest {
            host.onStart = { page ->
                page.token = null
                page.send(pageEvent("current"))
            }

            assertEquals(TCF_TIMEOUT, (run() as TcfOutcome.Failed).code)
            assertFalse(prefs.contains("IABTCF_gdprApplies"))
        }

    @Test
    fun tcfOffIsItsOwnOutcomeAndNeverWritesGdprAppliesZero() =
        runTest {
            host.onStart = { it.send(pageJson("""{"type":"error","code":"tcf-off"}""")) }

            assertEquals(TcfOutcome.Off, run())
            assertFalse(prefs.contains("IABTCF_gdprApplies"))
        }

    @Test
    fun onlyTheGeoAnswerWritesGdprAppliesZero() =
        runTest {
            host.onStart = { it.send(pageEvent("not-applicable")) }

            assertEquals(TcfOutcome.NotApplicable, run())
            assertEquals(0, prefs.getInt("IABTCF_gdprApplies", -1))
        }

    @Test
    fun aPageThatAnswersWithin45SecondsIsWaitedFor() =
        runTest {
            host.onStart = { page ->
                launch {
                    delay(40_000)
                    page.send(pageEvent("current"))
                }
            }

            assertEquals(TcfOutcome.Current, run())
        }

    @Test
    fun aPageSilentFor45SecondsTimesOut() =
        runTest {
            val outcome = async { run() }
            advanceTimeBy(44_999)
            runCurrent()
            assertFalse(outcome.isCompleted)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(TCF_TIMEOUT, (outcome.await() as TcfOutcome.Failed).code)
        }

    @Test
    fun aCrashOpeningTheWebViewIsAFailureAndNeverThrows() =
        runTest {
            host.openFailure = IllegalStateException("Failed to load WebView provider")

            assertEquals(TcfOutcome.Failed(TCF_UNAVAILABLE, "Failed to load WebView provider"), run())
        }

    @Test
    fun aCrashInsideTheRunReleasesThePage() =
        runTest {
            host.onStart = { throw IllegalStateException("evaluateJavascript after destroy") }

            assertEquals(TCF_UNAVAILABLE, (run() as TcfOutcome.Failed).code)
            assertEquals(1, host.pages.single().released)
        }

    @Test
    fun backIsIgnoredOnAFirstPrompt() =
        runTest {
            host.onStart = { page ->
                page.send(pageEvent("show"))
                page.send(TcfEvent.Back(interacted = false))
                page.send(pageJson(Fixtures.decisionJson()))
            }

            assertTrue(run() is TcfOutcome.Decided)
            assertEquals(0, host.pages.single().dismissed)
            assertEquals(1, shows)
        }

    @Test
    fun backOnAnUntouchedReopenedPageClosesAtOnce() =
        runTest {
            host.onStart = { page ->
                page.send(pageEvent("show"))
                page.send(TcfEvent.Back(interacted = false))
            }

            assertEquals(TcfOutcome.Closed, run(reopen = true))
            assertEquals(1, host.pages.single().dismissed)
            assertEquals(1, host.pages.single().released)
        }

    @Test
    fun backAfterInteractionStillLandsADecisionInFlight() =
        runTest {
            host.onStart = { page ->
                page.send(pageEvent("show"))
                page.send(TcfEvent.Back(interacted = true))
                launch {
                    delay(8_000)
                    page.send(pageJson(Fixtures.decisionJson()))
                }
            }

            assertTrue(run(reopen = true) is TcfOutcome.Decided)
            assertEquals(1, host.pages.single().dismissed)
            assertEquals(Fixtures.TCF_STRING, prefs.getString("IABTCF_TCString", null))
        }

    @Test
    fun backAfterInteractionWithNoDecisionClosesAfterTheGrace() =
        runTest {
            host.onStart = { page ->
                page.send(pageEvent("show"))
                page.send(TcfEvent.Back(interacted = true))
            }

            val outcome = async { run(reopen = true) }
            advanceTimeBy(TCF_CLOSE_GRACE_MS - 1)
            runCurrent()
            assertFalse(outcome.isCompleted)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(TcfOutcome.Closed, outcome.await())
        }
}
