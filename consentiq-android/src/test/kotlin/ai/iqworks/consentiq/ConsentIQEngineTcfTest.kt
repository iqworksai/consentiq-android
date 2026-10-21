package ai.iqworks.consentiq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentIQEngineTcfTest {
    private val dispatcher = StandardTestDispatcher()
    private val http = FakeHttpExecutor()
    private val storage = InMemoryStorage()
    private val store = ConsentStore(storage)
    private val logger = RecordingLogger()
    private val state = MutableStateFlow(ConsentIQState())
    private val prefs = FakeSharedPreferences()
    private val host = FakeTcfHost()

    private val opens = mutableListOf<Unit>()
    private val closes = mutableListOf<Unit>()
    private val accepts = mutableListOf<AcceptInfo>()
    private val rejects = mutableListOf<RejectInfo>()
    private val saves = mutableListOf<SaveInfo>()
    private val errors = mutableListOf<ErrorInfo>()
    private val changes = mutableListOf<Map<String, Boolean>>()
    private val sheetShown = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        http.on("POST", "/api/public/consent", body = Fixtures.SUBMIT_OK)
        http.on("GET", "/api/public/consent/${Fixtures.PROPERTY_KEY}/${store.getOrCreateSubjectId()}", body = Fixtures.NO_CONSENT)
        serve(Fixtures.tcfJson())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun serve(tcf: String?) {
        http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson(tcf = tcf))
    }

    private fun TestScope.engine(autoShow: Boolean = true): ConsentIQEngine {
        val config =
            ConsentIQConfig(
                propertyKey = Fixtures.PROPERTY_KEY,
                apiUrl = "http://stub",
                autoShow = autoShow,
                onConsentChange = { consent, _ -> changes += consent },
                onOpen = {
                    opens += Unit
                    sheetShown += state.value.isSheetVisible
                },
                onAccept = { accepts += it },
                onReject = { rejects += it },
                onSave = { saves += it },
                onClose = { closes += Unit },
                onError = { errors += it },
            )
        val api = ConsentIQApiClient(Fixtures.PROPERTY_KEY, "http://stub", http, dispatcher)
        return ConsentIQEngine(config, store, api, logger, state, tcfHost = host, tcfKeys = TcfKeyStore(prefs))
    }

    private fun answer(vararg events: TcfEvent) {
        host.onStart = { page -> events.forEach(page::send) }
    }

    private fun consentPosts() = http.requests.filter { it.method == "POST" }

    @Test
    fun withoutATcfBlockTheSheetRunsAndNoKeysAreWritten() =
        runTest(dispatcher) {
            serve(null)

            engine().initialize()

            assertTrue(host.opened.isEmpty())
            assertTrue(prefs.values.isEmpty())
            assertFalse(state.value.isTcfActive)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun aCmpIdBelowTwoOrADisabledBlockStaysOutOfTcf() =
        runTest(dispatcher) {
            for (tcf in listOf(Fixtures.tcfJson(cmpId = 0), Fixtures.tcfJson(cmpId = 1), Fixtures.tcfJson(enabled = false))) {
                serve(tcf)

                engine().initialize()

                assertTrue(host.opened.isEmpty())
                assertTrue(prefs.values.isEmpty())
                assertFalse(state.value.isTcfActive)
                assertTrue(state.value.isSheetVisible)
            }
        }

    @Test
    fun gdprNotApplyingWritesZeroAndKeepsTheSheet() =
        runTest(dispatcher) {
            serve(Fixtures.tcfJson(gdprApplies = false))

            engine().initialize()

            assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
            assertEquals(1, prefs.getInt("IABTCF_CmpSdkVersion", -1))
            assertEquals(0, prefs.getInt("IABTCF_gdprApplies", -1))
            assertTrue(host.opened.isEmpty())
            assertFalse(state.value.isTcfActive)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun withoutTheWebMessageListenerItFailsClosed() =
        runTest(dispatcher) {
            host.unavailableWith = TcfOutcome.Failed(TCF_UNSUPPORTED, "web message listener unsupported")

            engine().initialize()

            assertTrue(prefs.values.isEmpty())
            assertNull(store.tcfCmpId())
            assertEquals(listOf(ErrorInfo(TCF_UNSUPPORTED, "web message listener unsupported")), errors)
            assertTrue(host.opened.isEmpty())
            assertFalse(state.value.isTcfActive)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun aCurrentDecisionWritesGdprAppliesAndShowsNothing() =
        runTest(dispatcher) {
            answer(pageEvent("current"))

            engine().initialize()

            assertEquals(listOf("http://stub/api/public/tcf/mobile?key=${Fixtures.PROPERTY_KEY}" to false), host.opened)
            assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
            assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
            val page = host.pages.single()
            assertEquals(0, page.presented)
            assertEquals(1, page.released)
            assertTrue(opens.isEmpty())
            assertTrue(state.value.isTcfActive)
            assertFalse(state.value.isSheetVisible)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun aDecisionWritesTheKeysSavesStoredAndAppliesConsentWithoutSubmitting() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))

            engine().initialize()

            val page = host.pages.single()
            assertEquals(1, page.presented)
            assertEquals(1, page.released)
            assertEquals(1, opens.size)
            assertEquals(listOf(false), sheetShown)
            assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
            assertEquals(Fixtures.TCF_STRING, prefs.getString("IABTCF_TCString", null))
            assertEquals("_1_", prefs.getString("IABTCF_PublisherRestrictions2", null))
            assertEquals(Fixtures.TCF_STRING, JSONObject(storage.values.getValue(ConsentStore.TCF_KEY)).getString("tcString"))

            val consent = mapOf("necessary" to true, "analytics" to true, "marketing" to false)
            assertEquals(consent, state.value.consent)
            assertEquals(consent, store.loadConsent())
            assertEquals(listOf(consent), changes)
            assertEquals(listOf(SaveInfo(consent)), saves)
            assertTrue(accepts.isEmpty() && rejects.isEmpty())
            assertTrue(consentPosts().isEmpty())
            assertFalse(state.value.isSheetVisible)
            assertTrue(state.value.isTcfActive)
        }

    @Test
    fun aDecisionGrantingEverythingIsAnAcceptAndOnlyRequiredIsAReject() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson(consent = """{"necessary":true,"analytics":true,"marketing":true}""")))
            engine().initialize()
            assertEquals("all", accepts.single().scope)

            answer(
                pageEvent("show"),
                pageJson(Fixtures.decisionJson(consent = """{"necessary":true,"analytics":false,"marketing":false}""")),
            )
            engine().initialize()
            assertEquals(mapOf("necessary" to true, "analytics" to false, "marketing" to false), rejects.single().consent)
            assertTrue(saves.isEmpty())
        }

    @Test
    fun thePagesScopeChoosesTheCallbackOverInference() =
        runTest(dispatcher) {
            val everything = """{"necessary":true,"analytics":true,"marketing":true}"""
            val requiredOnly = """{"necessary":true,"analytics":false,"marketing":false}"""

            answer(pageEvent("show"), pageJson(Fixtures.decisionJson(consent = everything, scope = "custom")))
            engine().initialize()
            assertEquals(1, saves.size)
            assertTrue(accepts.isEmpty())

            answer(pageEvent("show"), pageJson(Fixtures.decisionJson(consent = requiredOnly, scope = "all")))
            engine().initialize()
            assertEquals("all", accepts.single().scope)
            assertTrue(rejects.isEmpty())

            answer(pageEvent("show"), pageJson(Fixtures.decisionJson(scope = "none")))
            engine().initialize()
            assertEquals(1, rejects.size)
            assertEquals(1, saves.size)
        }

    @Test
    fun notApplicableWritesZeroAndFallsBackToTheSheet() =
        runTest(dispatcher) {
            answer(pageEvent("not-applicable"))

            engine().initialize()

            assertEquals(0, prefs.getInt("IABTCF_gdprApplies", -1))
            assertEquals(1, host.pages.single().released)
            assertFalse(state.value.isTcfActive)
            assertTrue(state.value.isSheetVisible)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun aPageErrorIsReportedAndFallsBackToTheSheet() =
        runTest(dispatcher) {
            answer(pageJson("""{"type":"error","code":"404"}"""))

            engine().initialize()

            assertEquals(listOf(ErrorInfo(TCF_PAGE_ERROR, "404")), errors)
            assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
            assertEquals(1, host.pages.single().released)
            assertFalse(state.value.isTcfActive)
            assertTrue(state.value.isSheetVisible)
            assertTrue(logger.warnings.any { it.contains(TCF_PAGE_ERROR) })
        }

    @Test
    fun aPageThatNeverLoadsTimesOut() =
        runTest(dispatcher) {
            host.sendReady = false

            engine().initialize()

            assertEquals(TCF_TIMEOUT, errors.single().code)
            assertTrue(
                host.pages
                    .single()
                    .scripts
                    .isEmpty(),
            )
            assertEquals(1, host.pages.single().released)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun aPageThatNeverAnswersTimesOut() =
        runTest(dispatcher) {
            engine().initialize()

            assertEquals(TCF_TIMEOUT, errors.single().code)
            assertEquals(
                1,
                host.pages
                    .single()
                    .scripts.size,
            )
            assertEquals(1, host.pages.single().released)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun aRendererCrashAfterShowFallsBack() =
        runTest(dispatcher) {
            answer(pageEvent("show"), TcfEvent.Failed("renderer gone"))

            engine().initialize()

            assertEquals(listOf(ErrorInfo(TCF_LOAD_FAILED, "renderer gone")), errors)
            assertEquals(1, host.pages.single().released)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun startCarriesTheSubjectStoredDecisionAndCategoryMap() =
        runTest(dispatcher) {
            store.saveTcf("""{"tcString":"${Fixtures.TCF_STRING}","vendorIds":[1],"restrictionsKey":"[]"}""")
            store.saveConsent(mapOf("necessary" to true, "analytics" to true))
            answer(pageEvent("current"))
            val engine = engine()

            engine.initialize()

            val options =
                JSONObject(
                    host.pages
                        .single()
                        .scripts
                        .single()
                        .removePrefix("window.consentiqTcfStart(")
                        .removeSuffix(")"),
                )
            assertEquals(engine.subjectId, options.getString("subjectId"))
            assertEquals(Fixtures.TCF_STRING, options.getJSONObject("stored").getString("tcString"))
            assertTrue(options.getJSONObject("consent").getBoolean("analytics"))
            assertFalse(options.getBoolean("reopen"))
        }

    @Test
    fun reopeningAStoredDecisionOpensTheDetailLayerAndClosedKeepsTheKeys() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            val engine = engine()
            engine.initialize()
            val written = HashMap(prefs.values)

            answer(pageEvent("show"), pageEvent("closed"))
            engine.showTcf()

            assertEquals(true, host.opened.last().second)
            assertTrue(
                JSONObject(
                    host.pages
                        .last()
                        .scripts
                        .single()
                        .removePrefix("window.consentiqTcfStart(")
                        .removeSuffix(")"),
                ).getBoolean("reopen"),
            )
            assertEquals(1, host.pages.last().presented)
            assertEquals(1, host.pages.last().released)
            assertEquals(1, closes.size)
            assertEquals(written, prefs.values)
            assertTrue(state.value.isTcfActive)
        }

    @Test
    fun showTcfWithoutAStoredDecisionIsAFirstPrompt() =
        runTest(dispatcher) {
            val engine = engine(autoShow = false)
            engine.initialize()
            assertTrue(host.opened.isEmpty())
            assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
            assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
            assertTrue(state.value.isTcfActive)

            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            engine.showTcf()

            assertEquals(false, host.opened.single().second)
            assertEquals(1, saves.size)
        }

    @Test
    fun theCategorySheetNeverOpensInTcfMode() =
        runTest(dispatcher) {
            answer(pageEvent("current"))
            val engine = engine()
            engine.initialize()

            engine.showSheet()

            assertFalse(state.value.isSheetVisible)
            assertTrue(opens.isEmpty())
        }

    @Test
    fun aFailureWithConsentAlreadyFoundShowsNoSheet() =
        runTest(dispatcher) {
            http.on(
                "GET",
                "/api/public/consent/${Fixtures.PROPERTY_KEY}/${store.getOrCreateSubjectId()}",
                body = Fixtures.savedConsentJson(),
            )
            answer(pageJson("""{"type":"error","code":"start-failed"}"""))

            engine().initialize()

            assertEquals(TCF_PAGE_ERROR, errors.single().code)
            assertFalse(state.value.isSheetVisible)
        }

    @Test
    fun resetForgetsTheStoredDecision() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            val engine = engine()
            engine.initialize()

            engine.resetConsent()

            assertNull(store.loadTcf())
            assertEquals(setOf("IABTCF_CmpSdkID", "IABTCF_CmpSdkVersion", "IABTCF_gdprApplies"), prefs.values.keys)
            assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
        }

    @Test
    fun resetLeavesAnotherCmpsKeysAlone() =
        runTest(dispatcher) {
            serve(null)
            prefs.values["IABTCF_CmpSdkID"] = 300
            prefs.values["IABTCF_TCString"] = "other-cmp"
            prefs.values["IABTCF_PurposeConsents"] = "1"
            val before = HashMap(prefs.values)
            val engine = engine()
            engine.initialize()

            engine.resetConsent()

            assertEquals(before, prefs.values)
        }

    @Test
    fun resetLeavesTheKeysAloneOnceAnotherCmpHasTakenThemOver() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            val engine = engine()
            engine.initialize()
            prefs.values["IABTCF_CmpSdkID"] = 300

            engine.resetConsent()

            assertEquals(Fixtures.TCF_STRING, prefs.getString("IABTCF_TCString", null))
        }

    @Test
    fun turningTcfOffRemovesEveryKeyThisSdkWroteAndTheMarker() =
        runTest(dispatcher) {
            for (off in listOf(null, Fixtures.tcfJson(enabled = false), Fixtures.tcfJson(cmpId = 1))) {
                serve(Fixtures.tcfJson())
                answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
                engine().initialize()
                prefs.values["app_setting"] = true
                assertTrue(prefs.contains("IABTCF_TCString"))

                serve(off)
                engine().initialize()

                assertEquals(setOf("app_setting"), prefs.values.keys)
                assertNull(store.tcfCmpId())
                assertNull(store.loadTcf())
                assertFalse(state.value.isTcfActive)
            }
        }

    @Test
    fun turningTcfOffLeavesKeysAnotherCmpHasTakenOver() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            engine().initialize()
            prefs.values["IABTCF_CmpSdkID"] = 300
            val before = HashMap(prefs.values)

            serve(null)
            engine().initialize()

            assertEquals(before, prefs.values)
            assertNull(store.tcfCmpId())
        }

    @Test
    fun resetClearsTheDecisionKeysEvenWhenTcfDidNotRunThisSession() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            engine().initialize()

            http.on("GET", "/api/public/config/${Fixtures.PROPERTY_KEY}", status = 500, body = "{}")
            val offline = engine()
            offline.initialize()
            assertFalse(state.value.isTcfActive)

            offline.resetConsent()

            assertEquals(setOf("IABTCF_CmpSdkID", "IABTCF_CmpSdkVersion", "IABTCF_gdprApplies"), prefs.values.keys)
        }

    @Test
    fun anExpiredServerConsentClearsTheDecisionAndPromptsAgain() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            engine().initialize()
            assertTrue(prefs.contains("IABTCF_TCString"))

            http.on("GET", "/api/public/consent/${Fixtures.PROPERTY_KEY}/${store.getOrCreateSubjectId()}", body = Fixtures.EXPIRED)
            answer(pageEvent("current"))
            engine().initialize()

            assertTrue(TCF_DECISION_KEYS.none { prefs.contains(it) })
            assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
            assertNull(store.loadTcf())
            val options =
                JSONObject(
                    host.pages
                        .last()
                        .scripts
                        .single()
                        .removePrefix("window.consentiqTcfStart(")
                        .removeSuffix(")"),
                )
            assertTrue(options.isNull("stored"))
        }

    @Test
    fun aTransientFailureFallsBackAndTheNextExplicitOpenRetriesTcf() =
        runTest(dispatcher) {
            answer(pageJson("""{"type":"error","code":"start-failed"}"""))
            val engine = engine()
            engine.initialize()
            assertTrue(state.value.isSheetVisible)
            assertTrue(engine.canOpenTcf)

            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            engine.showTcf()

            assertEquals(2, host.opened.size)
            assertTrue(state.value.isTcfActive)
            assertFalse(state.value.isSheetVisible)
            assertEquals(1, saves.size)
        }

    @Test
    fun aSheetDecisionInFallbackClearsTheOwnedDecisionKeys() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            val engine = engine()
            engine.initialize()
            assertTrue(prefs.contains("IABTCF_TCString"))

            answer(pageJson("""{"type":"error","code":"decision-failed"}"""))
            engine.showTcf()
            assertTrue(state.value.isSheetVisible)

            engine.acceptAll()

            assertTrue(TCF_DECISION_KEYS.none { prefs.contains(it) })
            assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
            assertNull(store.loadTcf())
            assertEquals(1, consentPosts().size)
        }

    @Test
    fun anOpenDuringARunIsQueuedAndRunsAsAReopen() =
        runTest(dispatcher) {
            answer(pageEvent("show"))
            val engine = engine()
            val init = launch { engine.initialize() }
            advanceUntilIdle()
            assertEquals(1, host.opened.size)

            engine.showTcf()
            engine.showTcf()
            assertEquals(1, host.opened.size)

            answer(pageEvent("show"), pageEvent("closed"))
            host.pages.single().send(pageJson(Fixtures.decisionJson()))
            advanceUntilIdle()
            init.join()

            assertEquals(listOf(false, true), host.opened.map { it.second })
            assertEquals(1, saves.size)
            assertEquals(1, closes.size)
        }

    @Test
    fun tcfOffFromThePageRetiresTheOwnedKeysWithoutAnError() =
        runTest(dispatcher) {
            answer(pageEvent("show"), pageJson(Fixtures.decisionJson()))
            val engine = engine()
            engine.initialize()

            answer(pageJson("""{"type":"error","code":"tcf-off"}"""))
            engine.showTcf()

            assertTrue(prefs.values.keys.none { it.startsWith("IABTCF_") })
            assertNull(store.tcfCmpId())
            assertTrue(errors.isEmpty())
            assertFalse(engine.canOpenTcf)
            assertTrue(state.value.isSheetVisible)
        }

    @Test
    fun aCrashOpeningTheWebViewReportsAndFallsBack() =
        runTest(dispatcher) {
            host.openFailure = IllegalStateException("no provider")

            engine().initialize()

            assertEquals(listOf(ErrorInfo(TCF_UNAVAILABLE, "no provider")), errors)
            assertTrue(state.value.isSheetVisible)
        }
}
