package ai.iqworks.consentiq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentIQEngineTest {
    private val dispatcher = StandardTestDispatcher()
    private val http = FakeHttpExecutor()
    private val storage = InMemoryStorage()
    private val store = ConsentStore(storage)
    private val logger = RecordingLogger()
    private val state = MutableStateFlow(ConsentIQState())

    private val opens = mutableListOf<Unit>()
    private val closes = mutableListOf<Unit>()
    private val accepts = mutableListOf<AcceptInfo>()
    private val rejects = mutableListOf<RejectInfo>()
    private val saves = mutableListOf<SaveInfo>()
    private val changes = mutableListOf<Pair<Map<String, Boolean>, Map<String, Boolean>>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson())
        http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun config(
        subjectId: String? = null,
        autoGenerateSubjectId: Boolean = true,
        autoShow: Boolean = true,
        language: String? = null,
    ) = ConsentIQConfig(
        propertyKey = Fixtures.PROPERTY_KEY,
        apiUrl = "http://stub",
        subjectId = subjectId,
        autoGenerateSubjectId = autoGenerateSubjectId,
        autoShow = autoShow,
        language = language,
        onConsentChange = { consent, marketing -> changes += consent to marketing },
        onOpen = { opens += Unit },
        onAccept = { accepts += it },
        onReject = { rejects += it },
        onSave = { saves += it },
        onClose = { closes += Unit },
    )

    private fun TestScope.engine(config: ConsentIQConfig = config()): ConsentIQEngine {
        val api =
            ConsentIQApiClient(
                propertyKey = Fixtures.PROPERTY_KEY,
                baseUrl = "http://stub",
                http = http,
                ioDispatcher = dispatcher,
            )
        return ConsentIQEngine(config, store, api, logger, state)
    }

    private fun FakeHttpExecutor.onConsentLookup(body: String) {
        on("GET", "/api/public/consent/${Fixtures.PROPERTY_KEY}/${store.getOrCreateSubjectId()}", body = body)
    }

    @Test
    fun neverGivenShowsTheSheetAndFiresOnOpen() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine()

            engine.initialize()

            val current = state.value
            assertFalse(current.isLoading)
            assertTrue(current.isSheetVisible)
            assertEquals(1, opens.size)
            assertEquals(DEFAULT_CONSENT, current.consent)
            assertEquals("DPDPA", current.regulation)
            assertEquals("en", current.language)
            assertNotNull(current.config)
            assertNull(storage.values["consentiq_consent"])
            assertEquals(engine.subjectId, storage.values["consentiq_subject_id"])
            assertEquals(
                listOf("/api/public/config/${Fixtures.PROPERTY_KEY}", "/api/public/consent/${Fixtures.PROPERTY_KEY}/${engine.subjectId}"),
                http.requests.map { it.url.removePrefix("http://stub") },
            )
        }

    @Test
    fun existingServerConsentIsAdoptedCachedAndNoSheet() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.savedConsentJson(analytics = true, email = true))
            val engine = engine()

            engine.initialize()

            val current = state.value
            assertFalse(current.isLoading)
            assertFalse(current.isSheetVisible)
            assertTrue(opens.isEmpty())
            assertTrue(current.hasConsent("analytics"))
            assertFalse(current.hasConsent("marketing"))
            assertTrue(current.hasMarketingConsent("email"))
            assertEquals(true, JSONObject(storage.values.getValue("consentiq_consent")).getBoolean("analytics"))
        }

    @Test
    fun expiredServerConsentClearsTheCacheAndShowsTheSheet() =
        runTest(dispatcher) {
            store.saveConsent(mapOf("necessary" to true, "analytics" to true))
            http.onConsentLookup(Fixtures.EXPIRED)
            val engine = engine()

            engine.initialize()

            val current = state.value
            assertNull(storage.values["consentiq_consent"])
            assertTrue(current.isSheetVisible)
            assertEquals(1, opens.size)
            assertEquals(DEFAULT_CONSENT, current.consent)
            assertEquals(engine.subjectId, storage.values["consentiq_subject_id"])
        }

    @Test
    fun localCacheIsUsedWhenServerHasNothing() =
        runTest(dispatcher) {
            store.saveConsent(mapOf("necessary" to true, "analytics" to true, "marketing" to false))
            http.onConsentLookup(Fixtures.NO_CONSENT)

            engine().initialize()

            val current = state.value
            assertFalse(current.isSheetVisible)
            assertTrue(opens.isEmpty())
            assertTrue(current.hasConsent("analytics"))
            assertFalse(current.isLoading)
        }

    @Test
    fun localCacheIsUsedWhenConsentLookupFails() =
        runTest(dispatcher) {
            store.saveConsent(mapOf("necessary" to true, "analytics" to true))
            http.on("GET", "/api/public/consent/${Fixtures.PROPERTY_KEY}/${store.getOrCreateSubjectId()}", status = 500, body = "{}")

            engine().initialize()

            assertFalse(state.value.isSheetVisible)
            assertTrue(state.value.hasConsent("analytics"))
            assertTrue(logger.warnings.any { it.startsWith("[ConsentIQ] Could not restore consent") })
        }

    @Test
    fun configFailureLeavesTheAppUsable() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", status = 404, body = """{"error":"Property not found"}""")

            engine().initialize()

            val current = state.value
            assertFalse(current.isLoading)
            assertFalse(current.isSheetVisible)
            assertNull(current.config)
            assertEquals(DEFAULT_CONSENT, current.consent)
            assertEquals(listOf("[ConsentIQ] Failed to initialize: Property not found"), logger.errors)
        }

    @Test
    fun autoShowFalseNeverOpensTheSheet() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)

            engine(config(autoShow = false)).initialize()

            assertFalse(state.value.isSheetVisible)
            assertTrue(opens.isEmpty())
        }

    @Test
    fun callerLanguageWinsOverPropertyDefault() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)

            engine(config(language = "ar")).initialize()

            assertEquals("ar", state.value.language)
        }

    @Test
    fun propertyDefaultLanguageIsUsedWhenCallerGivesNone() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(defaultLanguage = "hi"))
            http.onConsentLookup(Fixtures.NO_CONSENT)

            engine().initialize()

            assertEquals("hi", state.value.language)
        }

    @Test
    fun suppliedSubjectIdIsUsedAndNotPersisted() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/consent/${Fixtures.PROPERTY_KEY}/user-42", body = Fixtures.NO_CONSENT)
            val engine = engine(config(subjectId = "user-42"))

            engine.initialize()

            assertEquals("user-42", engine.subjectId)
            assertNull(storage.values["consentiq_subject_id"])
        }

    @Test
    fun noSubjectIdWhenAutoGenerationIsOff() =
        runTest(dispatcher) {
            val engine = engine(config(autoGenerateSubjectId = false))

            engine.initialize()
            engine.acceptAll()

            assertEquals("", engine.subjectId)
            assertEquals(1, http.requests.size)
            assertTrue(http.posts().isEmpty())
            assertTrue(state.value.hasConsent("analytics"))
        }

    @Test
    fun captchaWarningIsLoggedExactlyOnce() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(requireCaptcha = true))
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine()

            engine.initialize()
            engine.acceptAll()
            engine.rejectAll()

            assertEquals(1, logger.warnings.count { it == ConsentIQEngine.CAPTCHA_WARNING })
            assertEquals(
                "[ConsentIQ] This property requires a captcha, which mobile SDKs cannot satisfy. " +
                    "Turn off \"Require captcha\" for this property in the ConsentIQ dashboard.",
                ConsentIQEngine.CAPTCHA_WARNING,
            )
        }

    @Test
    fun captchaWarningIsNotLoggedWhenNotRequired() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)

            engine().initialize()

            assertTrue(logger.warnings.none { it == ConsentIQEngine.CAPTCHA_WARNING })
        }

    @Test
    fun acceptAllEnablesEveryEnabledCategoryPostsAndFiresCallbacks() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine()
            engine.initialize()

            engine.acceptAll()

            val current = state.value
            assertFalse(current.isSheetVisible)
            assertEquals(
                mapOf("necessary" to true, "analytics" to true, "marketing" to true, "preferences" to false, "social" to false),
                current.consent,
            )
            val body = http.posts().single()
            assertEquals(Fixtures.PROPERTY_KEY, body.getString("propertyKey"))
            assertEquals(engine.subjectId, body.getString("subjectId"))
            assertEquals("sdk", body.getString("consentMethod"))
            assertTrue(body.getJSONObject("cookieConsents").getBoolean("analytics"))
            assertTrue(body.getJSONObject("cookieConsents").getBoolean("marketing"))
            assertFalse(body.getJSONObject("cookieConsents").getBoolean("preferences"))
            assertEquals(0, body.getJSONObject("marketingConsents").length())
            assertEquals(1, accepts.size)
            assertEquals("all", accepts.single().scope)
            assertEquals(current.consent, accepts.single().consent)
            assertEquals(1, changes.size)
            assertEquals(current.consent, changes.single().first)
            assertTrue(closes.isEmpty())
            assertEquals(current.consent, store.loadConsent())
        }

    @Test
    fun rejectAllKeepsOnlyRequiredCategories() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.savedConsentJson(analytics = true, marketing = true, email = true))
            val engine = engine()
            engine.initialize()

            engine.rejectAll()

            val current = state.value
            assertEquals(
                mapOf("necessary" to true, "analytics" to false, "marketing" to false, "preferences" to false, "social" to false),
                current.consent,
            )
            assertFalse(current.isSheetVisible)
            val body = http.posts().single()
            assertTrue(body.getJSONObject("cookieConsents").getBoolean("necessary"))
            assertFalse(body.getJSONObject("cookieConsents").getBoolean("analytics"))
            assertTrue(body.getJSONObject("marketingConsents").getBoolean("email"))
            assertEquals(1, rejects.size)
            assertEquals(current.consent, rejects.single().consent)
            assertEquals(1, changes.size)
            assertTrue(closes.isEmpty())
        }

    @Test
    fun savePreferencesForcesNecessaryPostsAndFiresOnSave() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine()
            engine.initialize()

            engine.savePreferences(mapOf("necessary" to false, "analytics" to true, "marketing" to false))

            val current = state.value
            assertEquals(mapOf("necessary" to true, "analytics" to true, "marketing" to false), current.consent)
            assertFalse(current.isSheetVisible)
            val body = http.posts().single()
            assertTrue(body.getJSONObject("cookieConsents").getBoolean("necessary"))
            assertTrue(body.getJSONObject("cookieConsents").getBoolean("analytics"))
            assertEquals(1, saves.size)
            assertEquals(current.consent, saves.single().consent)
            assertTrue(accepts.isEmpty())
            assertTrue(rejects.isEmpty())
            assertTrue(closes.isEmpty())
        }

    @Test
    fun updateConsentMergesAndUpdateMarketingConsentPostsBoth() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine()
            engine.initialize()

            engine.updateConsent(mapOf("analytics" to true, "necessary" to false))
            engine.updateMarketingConsent(mapOf("email" to true))

            assertTrue(state.value.hasConsent("analytics"))
            assertTrue(state.value.hasConsent("necessary"))
            assertTrue(state.value.hasMarketingConsent("email"))
            val posts = http.posts()
            assertEquals(2, posts.size)
            assertTrue(posts[1].getJSONObject("cookieConsents").getBoolean("analytics"))
            assertTrue(posts[1].getJSONObject("marketingConsents").getBoolean("email"))
            assertEquals(2, changes.size)
            assertEquals(mapOf("email" to true), changes[1].second)
        }

    @Test
    fun failedPostLogsAndKeepsLocalState() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            http.on("POST", "/api/public/consent", status = 400, body = """{"error":"Captcha verification failed. Please try again."}""")
            val engine = engine()
            engine.initialize()

            engine.acceptAll()

            assertTrue(state.value.hasConsent("analytics"))
            assertFalse(state.value.isSheetVisible)
            assertEquals(1, accepts.size)
            assertTrue(changes.isEmpty())
            assertNull(store.loadConsent())
            assertEquals(
                listOf("[ConsentIQ] Failed to submit consent: Captcha verification failed. Please try again."),
                logger.errors,
            )
        }

    @Test
    fun sheetVisibilityCallbacks() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.savedConsentJson())
            val engine = engine()
            engine.initialize()
            assertFalse(state.value.isSheetVisible)

            engine.showSheet()
            assertTrue(state.value.isSheetVisible)
            assertEquals(1, opens.size)

            engine.hideSheet()
            assertFalse(state.value.isSheetVisible)
            assertTrue(closes.isEmpty())

            engine.showSheet()
            engine.dismissSheet()
            assertFalse(state.value.isSheetVisible)
            assertEquals(1, closes.size)
            assertEquals(2, opens.size)
        }

    @Test
    fun resetConsentClearsStateStorageAndRotatesTheSubjectId() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.savedConsentJson(analytics = true, email = true))
            val engine = engine()
            engine.initialize()
            val before = engine.subjectId

            engine.resetConsent()

            assertEquals(DEFAULT_CONSENT, state.value.consent)
            assertTrue(state.value.marketingConsent.isEmpty())
            assertNull(store.loadConsent())
            assertTrue(engine.subjectId.isNotEmpty())
            assertTrue(before != engine.subjectId)
            assertEquals(engine.subjectId, storage.values["consentiq_subject_id"])
        }
}
