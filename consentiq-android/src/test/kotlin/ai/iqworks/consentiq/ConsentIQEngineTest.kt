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

    private fun TestScope.engine(
        config: ConsentIQConfig = config(),
        captcha: CaptchaTokenProvider = CaptchaTokenProvider.UNAVAILABLE,
        now: () -> Long = System::currentTimeMillis,
    ): ConsentIQEngine {
        val api =
            ConsentIQApiClient(
                propertyKey = Fixtures.PROPERTY_KEY,
                baseUrl = "http://stub",
                http = http,
                ioDispatcher = dispatcher,
            )
        return ConsentIQEngine(config, store, api, logger, state, captcha, now = now)
    }

    private val errors = mutableListOf<ErrorInfo>()

    private fun refuse(
        status: Int,
        error: String = "refused",
    ) = http.on("POST", "/api/public/consent", status = status, body = """{"error":"$error"}""")

    private fun accept() = http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)

    private fun launchConfig() = config(autoShow = false).copy(onError = { errors += it })

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
            store.saveConsent(mapOf("necessary" to true, "analytics" to true, "marketing" to false), store.getOrCreateSubjectId())
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
            store.saveConsent(mapOf("necessary" to true, "analytics" to true), store.getOrCreateSubjectId())
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
            val engine = engine(config(subjectId = "user-42"))

            engine.initialize()

            assertEquals("user-42", engine.subjectId)
            assertNull(storage.values["consentiq_subject_id"])
        }

    @Test
    fun aSuppliedSubjectIsRestoredOnlyWithItsIdentityToken() =
        runTest(dispatcher) {
            val path = "/api/public/consent/${Fixtures.PROPERTY_KEY}/user-42"
            http.on("POST", path, body = Fixtures.savedConsentJson(analytics = true, email = true))

            engine(config(subjectId = "user-42").copy(identityToken = "v2.1.sig")).initialize()

            val restore = http.requests.single { it.url.endsWith(path) }
            assertEquals("POST", restore.method)
            assertEquals("v2.1.sig", JSONObject(restore.body!!).getString("identityToken"))
            assertTrue(state.value.hasConsent("analytics"))
        }

    @Test
    fun aSuppliedSubjectWithoutATokenIsNeverLookedUpAndTheLocalCopyStands() =
        runTest(dispatcher) {
            store.saveConsent(mapOf("necessary" to true, "analytics" to true), "user-42")

            engine(config(subjectId = "user-42")).initialize()

            assertTrue(http.requests.none { it.url.contains("/api/public/consent/") })
            assertTrue(state.value.hasConsent("analytics"))
            assertFalse(state.value.isSheetVisible)
        }

    @Test
    fun aGeneratedSubjectIsStillLookedUpWithoutAToken() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)

            engine(config().copy(identityToken = "v2.1.sig")).initialize()

            val restore = http.requests.single { it.url.contains("/api/public/consent/") }
            assertEquals("GET", restore.method)
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
    fun requiredCaptchaSendsAFreshTokenWithEverySubmitAndNoneAtInit() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(requireCaptcha = true))
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val captcha = FakeCaptchaProvider { "tok" }
            val engine = engine(captcha = captcha)

            engine.initialize()
            assertEquals(0, captcha.calls)

            engine.acceptAll()
            engine.updateMarketingConsent(mapOf("email" to true))

            val posts = http.posts()
            assertEquals(2, posts.size)
            assertTrue(posts.all { it.getString("captchaToken") == "tok" })
            assertEquals(2, captcha.calls)
            assertEquals(2, changes.size)
            assertTrue(logger.warnings.isEmpty())
        }

    @Test
    fun captchaFailureSendsNothingKeepsLocalStateAndWarns() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(requireCaptcha = true))
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine(captcha = FakeCaptchaProvider { throw CaptchaException("timeout") })
            engine.initialize()

            engine.acceptAll()

            assertTrue(http.posts().isEmpty())
            assertTrue(state.value.hasConsent("analytics"))
            assertFalse(state.value.isSheetVisible)
            assertEquals(1, accepts.size)
            assertTrue(changes.isEmpty())
            assertNull(store.loadConsent())
            assertEquals(listOf("[ConsentIQ] Captcha check failed; consent not submitted"), logger.warnings)
            assertTrue(logger.errors.isEmpty())
            assertTrue(logger.debugs.none { it.contains(engine.subjectId) })
        }

    @Test
    fun aChallengeIsAnsweredOnceAndTheConsentRecorded() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            var posts = 0
            http.on("POST", "/api/public/consent") {
                posts++
                if (posts == 1) HttpResponse(428, """{"error":"captcha_required"}""") else HttpResponse(200, Fixtures.SUBMIT_OK)
            }
            val captcha = FakeCaptchaProvider { "tok" }
            val engine = engine(captcha = captcha)
            engine.initialize()

            engine.acceptAll()

            val sent = http.posts()
            assertEquals(2, sent.size)
            assertFalse(sent[0].has("captchaToken"))
            assertEquals("tok", sent[1].getString("captchaToken"))
            assertEquals(1, captcha.calls)
            assertEquals(1, changes.size)
            assertNotNull(store.loadConsent())
        }

    @Test
    fun aCancelledChallengeSendsNothingMoreAndKeepsLocalState() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            http.on("POST", "/api/public/consent", status = 428, body = """{"error":"captcha_required"}""")
            val engine = engine(captcha = FakeCaptchaProvider { throw CaptchaException("cancelled") })
            engine.initialize()

            engine.acceptAll()

            assertEquals(1, http.posts().size)
            assertTrue(state.value.hasConsent("analytics"))
            assertTrue(changes.isEmpty())
            // A challenge left unanswered may pass later: the decision stands locally and waits to be resent.
            assertNotNull(store.loadConsent(engine.subjectId))
            assertNotNull(store.loadPending())
            assertEquals(listOf("[ConsentIQ] Captcha check failed; consent not submitted"), logger.warnings)
        }

    @Test
    fun theIdentityTokenGoesWithEverySubmitForTheAppsOwnSubject() =
        runTest(dispatcher) {
            http.on("POST", "/api/public/consent/${Fixtures.PROPERTY_KEY}/user-42", body = Fixtures.NO_CONSENT)
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            val engine = engine(config = config(subjectId = "user-42").copy(identityToken = "v2.1.sig"))
            engine.initialize()

            engine.acceptAll()
            engine.rejectAll()

            // The first post is the token-checked restore; the two after it are the submits.
            assertEquals(listOf("v2.1.sig", "v2.1.sig"), http.posts().drop(1).map { it.getString("identityToken") })
        }

    @Test
    fun theIdentityTokenNeverGoesWithAGeneratedSubject() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine(config = config().copy(identityToken = "v2.1.sig"))
            engine.initialize()

            engine.acceptAll()

            assertFalse(http.posts().single().has("identityToken"))
        }

    @Test
    fun aRefusedRestoreDropsTheLocalCopyAsksAgainAndTellsTheApp() =
        runTest(dispatcher) {
            store.saveConsent(mapOf("necessary" to true, "analytics" to true), "user-42")
            http.on(
                "POST",
                "/api/public/consent/${Fixtures.PROPERTY_KEY}/user-42",
                status = 403,
                body = """{"error":"Identity token did not verify"}""",
            )

            engine(config(subjectId = "user-42").copy(identityToken = "v2.1.sig", onError = { errors += it })).initialize()

            assertNull(store.loadConsent("user-42"))
            assertFalse(state.value.hasConsent("analytics"))
            assertTrue(state.value.isSheetVisible)
            assertEquals(listOf(ErrorInfo("consent_refused", "Identity token did not verify")), errors)
        }

    @Test
    fun anAppChosenSubjectIsMarkedSiteAndAGeneratedOneIsNot() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
            engine(config = config(subjectId = "user-42")).apply { initialize() }.acceptAll()
            engine(config = config()).apply { initialize() }.acceptAll()

            val (site, generated) = http.posts()
            assertEquals("site", site.getString("subjectSource"))
            assertFalse(generated.has("subjectSource"))
        }

    @Test
    fun aSubjectIdWithAColonIsReportedAndNothingIsSubmitted() =
        runTest(dispatcher) {
            val engine = engine(config(subjectId = "site:user-42").copy(onError = { errors += it }))
            engine.initialize()
            engine.acceptAll()

            assertEquals("", engine.subjectId)
            assertTrue(http.posts().isEmpty())
            assertEquals(listOf(ErrorInfo("invalid_subject_id", "ConsentIQ subjectId may not contain ':'")), errors)
        }

    @Test
    fun aResentDecisionKeepsTheSiteMark() =
        runTest(dispatcher) {
            refuse(429)
            engine(launchConfig().copy(subjectId = "user-a")).apply { initialize() }.acceptAll()

            accept()
            engine(launchConfig().copy(subjectId = "user-a")).initialize()

            assertEquals(listOf("site", "site"), http.posts().map { it.getString("subjectSource") })
        }

    @Test
    fun aTooManyRequestsAnswerKeepsTheDecisionAndTheNextLaunchSendsItWithoutACaptcha() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429, "Too many requests")
            val captcha = FakeCaptchaProvider { "tok" }
            engine(launchConfig(), captcha).apply { initialize() }.acceptAll()
            assertNotNull(store.loadPending())
            assertTrue(changes.isEmpty())

            accept()
            engine(launchConfig(), captcha).initialize()

            val posts = http.posts()
            assertEquals(2, posts.size)
            assertFalse(posts[1].has("captchaToken"))
            assertTrue(posts[1].getJSONObject("cookieConsents").getBoolean("analytics"))
            assertNull(store.loadPending())
            assertTrue(state.value.hasConsent("analytics"))
            assertEquals(true, store.loadConsent()?.get("analytics"))
            assertEquals(1, changes.size)
            assertEquals(0, captcha.calls)
        }

    @Test
    fun theRetryIsQuietOnAChallengeAndStopsAfterThreeLaunches() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            val captcha = FakeCaptchaProvider { "tok" }
            engine(launchConfig(), captcha).apply { initialize() }.acceptAll()

            refuse(428, "captcha_required")
            repeat(3) { engine(launchConfig(), captcha).initialize() }
            assertEquals(0, captcha.calls)
            assertEquals(4, http.posts().size)
            assertEquals(3, store.loadPending()?.attempts)

            engine(launchConfig(), captcha).initialize()
            assertEquals(4, http.posts().size)
            assertNull(store.loadPending())
        }

    @Test
    fun aPendingDecisionOlderThanSevenDaysIsDropped() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val start = 1_700_000_000_000L
            refuse(429)
            engine(launchConfig(), now = { start }).apply { initialize() }.acceptAll()

            accept()
            engine(launchConfig(), now = { start + 8L * 24 * 60 * 60 * 1000 }).initialize()

            assertEquals(1, http.posts().size)
            assertNull(store.loadPending())
        }

    @Test
    fun refusalsThatResendingCannotChangeDropTheDecision() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            for (status in listOf(400, 404, 409, 422)) {
                refuse(429)
                engine(launchConfig()).apply { initialize() }.acceptAll()
                assertNotNull("$status", store.loadPending())

                refuse(status)
                engine(launchConfig()).apply { initialize() }.rejectAll()

                assertNull("$status", store.loadPending())
            }
        }

    @Test
    fun aForbiddenAnswerReachesTheAppAndIsNotRetried() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(403, "Identity token did not verify")
            engine(launchConfig()).apply { initialize() }.acceptAll()
            engine(launchConfig()).initialize()

            assertEquals(1, http.posts().size)
            assertEquals(listOf(ErrorInfo("consent_refused", "Identity token did not verify")), errors)
            assertNull(store.loadPending())
        }

    @Test
    fun aForbiddenAnswerToTheRetryReachesTheAppToo() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            engine(launchConfig()).apply { initialize() }.acceptAll()

            refuse(403, "Verified identity required")
            engine(launchConfig()).initialize()

            assertEquals(listOf(ErrorInfo("consent_refused", "Verified identity required")), errors)
            assertNull(store.loadPending())
        }

    @Test
    fun aPropertyThatRequiresACaptchaKeepsNothingToRetry() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(requireCaptcha = true))
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            engine(launchConfig(), FakeCaptchaProvider { "tok" }).apply { initialize() }.acceptAll()

            assertNull(store.loadPending())
        }

    @Test
    fun aPendingDecisionIsNeverSentForSomeoneElse() =
        runTest(dispatcher) {
            refuse(429)
            engine(launchConfig().copy(subjectId = "user-a")).apply { initialize() }.acceptAll()
            assertNotNull(store.loadPending())

            accept()
            engine(launchConfig().copy(subjectId = "user-b")).initialize()

            assertEquals(1, http.posts().size)
            assertNull(store.loadPending())
        }

    @Test
    fun aPendingDecisionIsNeverSentToAnotherServer() =
        runTest(dispatcher) {
            refuse(429)
            engine(launchConfig().copy(subjectId = "user-a")).apply { initialize() }.acceptAll()

            accept()
            engine(launchConfig().copy(subjectId = "user-a", apiUrl = "http://staging.stub")).initialize()

            assertEquals(1, http.posts().size)
            assertNull(store.loadPending())
        }

    @Test
    fun aNewDecisionReplacesOneStillWaiting() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            val engine = engine(launchConfig()).apply { initialize() }
            engine.acceptAll()
            assertNotNull(store.loadPending())

            refuse(500)
            engine.rejectAll()

            // The newer choice replaces the one waiting.
            assertEquals(false, store.loadPending()?.cookieConsents?.get("analytics"))
        }

    @Test
    fun aNewerRecordOnTheServerWinsOverTheWaitingDecision() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val decidedAt = 1_700_000_000_000L
            refuse(429)
            engine(launchConfig(), now = { decidedAt }).apply { initialize() }.acceptAll()

            accept()
            http.onConsentLookup(Fixtures.savedConsentJson())
            engine(launchConfig(), now = { decidedAt + 60_000 }).initialize()

            assertEquals("the record dated 2026-09-01 is newer than the decision", 1, http.posts().size)
            assertNull(store.loadPending())
        }

    @Test
    fun anOlderRecordOnTheServerDoesNotStopTheResend() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val decidedAt = 1_800_000_000_000L
            refuse(429)
            engine(launchConfig(), now = { decidedAt }).apply { initialize() }.acceptAll()

            accept()
            http.onConsentLookup(Fixtures.savedConsentJson())
            engine(launchConfig(), now = { decidedAt + 60_000 }).initialize()

            assertEquals(2, http.posts().size)
            assertNull(store.loadPending())
        }

    @Test
    fun aTooManyRequestsAnswerThatArrivesAfterAResetIsIgnored() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            lateinit var engine: ConsentIQEngine
            http.on("POST", "/api/public/consent") {
                engine.resetConsent()
                HttpResponse(429, """{"error":"Too many requests"}""")
            }
            engine = engine(launchConfig()).apply { initialize() }

            engine.acceptAll()

            assertNull(store.loadPending())
        }

    @Test
    fun aStoredDecisionFromAnOlderVersionIsDiscarded() =
        runTest(dispatcher) {
            val storage = InMemoryStorage()
            val olderFormat = ConsentStore(storage)
            storage.putString(
                ConsentStore.PENDING_KEY,
                """{"cookieConsents":{},"marketingConsents":{},"attempts":0,"createdAtMs":0}""",
            )

            assertNull(olderFormat.loadPending())
            assertNull(storage.getString(ConsentStore.PENDING_KEY))
        }

    @Test
    fun clearAllForgetsAPendingDecision() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            engine(launchConfig()).apply { initialize() }.acceptAll()

            store.clearAll()

            assertNull(store.loadPending())
        }

    @Test
    fun captchaIsSkippedWhenNotRequired() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val captcha = FakeCaptchaProvider()
            val engine = engine(captcha = captcha)
            engine.initialize()

            engine.acceptAll()

            assertEquals(0, captcha.calls)
            assertFalse(http.posts().single().has("captchaToken"))
        }

    @Test
    fun captchaIsSkippedWithoutASiteKey() =
        runTest(dispatcher) {
            http.on(
                "GET",
                "/api/public/config/${Fixtures.PROPERTY_KEY}",
                body = Fixtures.configJson(requireCaptcha = true, turnstileSiteKey = null),
            )
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val captcha = FakeCaptchaProvider()
            val engine = engine(captcha = captcha)
            engine.initialize()

            engine.acceptAll()

            assertEquals(0, captcha.calls)
            assertFalse(http.posts().single().has("captchaToken"))
        }

    @Test
    fun loadedConfigIsSharedOnlyForTheSamePropertyAndServer() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            val engine = engine()
            assertNull(engine.loadedConfigFor(Fixtures.PROPERTY_KEY, "http://stub"))

            engine.initialize()

            assertNotNull(engine.loadedConfigFor(Fixtures.PROPERTY_KEY, "http://stub"))
            assertNotNull(engine.loadedConfigFor(Fixtures.PROPERTY_KEY, "http://stub/"))
            assertNull(engine.loadedConfigFor("pk_other", "http://stub"))
            assertNull(engine.loadedConfigFor(Fixtures.PROPERTY_KEY, "http://elsewhere"))
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

    private fun noticeVersion(version: Int) =
        http.on(
            "GET",
            "/api/public/config/${Fixtures.PROPERTY_KEY}",
            body = Fixtures.configJson(bannerColors = "\"noticeVersion\": $version,"),
        )

    @Test
    fun everyRetryableFailureKeepsTheDecision() =
        runTest(dispatcher) {
            for (status in listOf(500, 503, 429)) {
                store.clearAll()
                http.onConsentLookup(Fixtures.NO_CONSENT)
                refuse(status)
                val engine = engine(launchConfig()).apply { initialize() }
                engine.acceptAll()
                assertNotNull("$status", store.loadPending())
                assertEquals("$status", true, store.loadConsent(engine.subjectId)?.get("analytics"))
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun aFinalRefusalDropsTheDecisionAndItsLocalCopyMarksItAndTellsTheApp() =
        runTest(dispatcher) {
            for (status in listOf(400, 403, 404, 409, 422)) {
                store.clearAll()
                errors.clear()
                http.onConsentLookup(Fixtures.NO_CONSENT)
                refuse(status)
                val engine = engine(launchConfig()).apply { initialize() }
                engine.acceptAll()
                assertNull("$status", store.loadPending())
                assertNull("$status", store.loadConsent(engine.subjectId))
                assertTrue("$status", store.isUnrecorded(Fixtures.PROPERTY_KEY))
                assertEquals("$status", 1, errors.size)
            }
        }

    @Test
    fun whileTheMarkerStandsTheNextLaunchRestoresNothingAndAsks() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(400)
            engine(launchConfig()).apply { initialize() }.acceptAll()
            http.onConsentLookup(Fixtures.savedConsentJson())
            val lookupsBefore = http.requests.count { it.url.contains("/api/public/consent/") }

            engine(config().copy(onError = { errors += it })).initialize()

            assertEquals(lookupsBefore, http.requests.count { it.url.contains("/api/public/consent/") })
            assertEquals(DEFAULT_CONSENT, state.value.consent)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun theNextRecordedDecisionClearsTheMarker() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(400)
            val engine = engine(launchConfig()).apply { initialize() }
            engine.acceptAll()
            accept()
            engine.rejectAll()
            assertFalse(store.isUnrecorded(Fixtures.PROPERTY_KEY))
        }

    @Test
    fun anyFailureOnARequireCaptchaPropertyIsFinal() =
        runTest(dispatcher) {
            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(requireCaptcha = true))
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(503)
            engine(launchConfig(), FakeCaptchaProvider { "tok" }).apply { initialize() }.acceptAll()
            assertNull(store.loadPending())
            assertTrue(store.isUnrecorded(Fixtures.PROPERTY_KEY))
            assertEquals(1, errors.size)
        }

    @Test
    fun aPendingDecisionCarriesItsNoticeVersionAndIsResentWithIt() =
        runTest(dispatcher) {
            noticeVersion(4)
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            engine(launchConfig()).apply { initialize() }.acceptAll()
            assertEquals(4, store.loadPending()?.noticeVersion)

            accept()
            engine(launchConfig()).initialize()
            assertEquals(4, http.posts().last().getInt("noticeVersion"))
        }

    @Test
    fun aPendingDecisionMadeUnderAnotherNoticeIsDroppedAndMarked() =
        runTest(dispatcher) {
            noticeVersion(4)
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            engine(launchConfig()).apply { initialize() }.acceptAll()

            noticeVersion(5)
            accept()
            engine(launchConfig()).initialize()

            assertEquals(1, http.posts().size)
            assertNull(store.loadPending())
            assertTrue(store.isUnrecorded(Fixtures.PROPERTY_KEY))
            assertEquals(DEFAULT_CONSENT, state.value.consent)
        }

    @Test
    fun aFinalRefusalOfAResendTellsTheApp() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(429)
            engine(launchConfig()).apply { initialize() }.acceptAll()
            refuse(422)
            engine(launchConfig()).initialize()
            assertEquals(1, errors.size)
            assertTrue(store.isUnrecorded(Fixtures.PROPERTY_KEY))
        }

    @Test
    fun resetClearsTheMarkerAndThePendingDecision() =
        runTest(dispatcher) {
            http.onConsentLookup(Fixtures.NO_CONSENT)
            refuse(400)
            val engine = engine(launchConfig()).apply { initialize() }
            engine.acceptAll()
            engine.resetConsent()
            assertFalse(store.isUnrecorded(Fixtures.PROPERTY_KEY))
            assertNull(store.loadPending())
        }

    @Test
    fun aLocalCopyWrittenForAnotherSubjectIsNotInherited() =
        runTest(dispatcher) {
            store.saveConsent(mapOf("necessary" to true, "analytics" to true), "user-a")
            engine(config(subjectId = "user-b")).initialize()
            assertEquals(DEFAULT_CONSENT, state.value.consent)
            assertTrue(state.value.isSheetVisible)
        }
}
