package ai.iqworks.consentiq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentIQObjectTest {
    private val dispatcher = StandardTestDispatcher()
    private val http = FakeHttpExecutor()
    private val storage = InMemoryStorage()
    private val store = ConsentStore(storage)
    private val logger = RecordingLogger()
    private val opens = mutableListOf<Unit>()

    private val config =
        ConsentIQConfig(
            propertyKey = Fixtures.PROPERTY_KEY,
            apiUrl = "http://stub",
            onOpen = { opens += Unit },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson())
        http.on("GET", "/api/public/consent/${Fixtures.PROPERTY_KEY}/${store.getOrCreateSubjectId()}", body = Fixtures.NO_CONSENT)
        http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun api() = ConsentIQApiClient(Fixtures.PROPERTY_KEY, "http://stub", http, dispatcher, logger)

    @Test
    fun initRunsTheStateMachineOnMainAndIsIdempotentPerConfig() =
        runTest(dispatcher) {
            val first = ConsentIQ.initWith(config, store, api(), logger)
            advanceUntilIdle()

            assertTrue(ConsentIQ.isInitialized)
            assertFalse(ConsentIQ.state.value.isLoading)
            assertTrue(ConsentIQ.state.value.isSheetVisible)
            assertEquals(1, opens.size)
            assertEquals(store.getSubjectId(), ConsentIQ.subjectId)

            val again = ConsentIQ.initWith(config, store, api(), logger)
            advanceUntilIdle()

            assertSame(first, again)
            assertEquals(1, opens.size)
            assertEquals(2, http.requests.size)
        }

    @Test
    fun asyncWrappersRunOnTheMainScope() =
        runTest(dispatcher) {
            ConsentIQ.initWith(config, store, api(), logger)
            advanceUntilIdle()

            ConsentIQ.acceptAllAsync()
            advanceUntilIdle()

            assertTrue(ConsentIQ.hasConsent("analytics"))
            assertFalse(ConsentIQ.state.value.isSheetVisible)
            assertNotNull(http.posts().singleOrNull())

            ConsentIQ.showSheet()
            assertTrue(ConsentIQ.state.value.isSheetVisible)
            ConsentIQ.dismissSheet()
            assertFalse(ConsentIQ.state.value.isSheetVisible)

            ConsentIQ.updateMarketingConsentAsync(mapOf("email" to true))
            advanceUntilIdle()
            assertTrue(ConsentIQ.hasMarketingConsent("email"))
            assertEquals(2, http.posts().size)
        }

    @Test
    fun injectedCaptchaProviderSignsSubmitsAndTheLoadedConfigIsShared() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(requireCaptcha = true))
            val captcha = FakeCaptchaProvider { "tok" }
            ConsentIQ.initWith(config.copy(language = "hi"), store, api(), logger, captcha)
            advanceUntilIdle()

            ConsentIQ.acceptAllAsync()
            advanceUntilIdle()

            assertEquals("tok", http.posts().single().getString("captchaToken"))
            assertEquals(1, captcha.calls)
            assertTrue(ConsentIQ.loadedConfigFor(Fixtures.PROPERTY_KEY, "http://stub")!!.requireCaptcha)
            assertNull(ConsentIQ.loadedConfigFor("pk_other", "http://stub"))
        }

    @Test
    fun captchaIsUnavailableBeforeInitGivesAContext() {
        assertSame(CaptchaTokenProvider.UNAVAILABLE, ConsentIQ.captchaProviderFor("http://stub"))
    }

    @Test
    fun aDifferentConfigReinitialises() =
        runTest(dispatcher) {
            ConsentIQ.initWith(config, store, api(), logger)
            advanceUntilIdle()
            val requestsAfterFirst = http.requests.size

            ConsentIQ.initWith(config.copy(language = "ar"), store, api(), logger)
            advanceUntilIdle()

            assertEquals("ar", ConsentIQ.state.value.language)
            assertEquals(requestsAfterFirst + 2, http.requests.size)
        }
}
