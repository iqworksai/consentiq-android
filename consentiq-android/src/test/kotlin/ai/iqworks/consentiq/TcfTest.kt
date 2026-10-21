package ai.iqworks.consentiq

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcfTest {
    private val page = "https://consent.iqworks.ai/api/public/tcf/mobile?key=pk_test_android"

    private fun settings(json: String): TcfSettings? = parsePropertyConfig(JSONObject(Fixtures.configJson(tcf = json))).tcf

    @Test
    fun configParsesTheTcfBlockAndIgnoresUnknownFields() {
        val tcf = settings(Fixtures.tcfJson())!!

        assertEquals(
            TcfSettings(
                enabled = true,
                gdprApplies = true,
                vendorIds = listOf(1, 755),
                publisherCountryCode = "DE",
                publisherRestrictions = listOf(TcfPublisherRestriction(purposeId = 2, restrictionType = 1, vendorIds = listOf(755))),
                repromptMonths = 13,
                tcfPolicyVersion = 5,
                cmpId = 2,
                cmpVersion = 1,
                vendorListUrl = "/api/public/tcf/pk_test_android/vendor-list?v=abc",
            ),
            tcf,
        )
    }

    @Test
    fun theTcfBlockIsOptionalAndEveryFieldTolerant() {
        assertNull(parsePropertyConfig(JSONObject(Fixtures.configJson())).tcf)

        val empty = settings("{}")!!
        assertFalse(empty.enabled)
        assertTrue(empty.gdprApplies)
        assertEquals(0, empty.cmpId)
        assertNull(empty.cmpVersion)
        assertNull(empty.tcfPolicyVersion)
        assertTrue(empty.vendorIds.isEmpty())
        assertTrue(empty.publisherRestrictions.isEmpty())

        val odd = settings("""{"enabled":true,"tcfPolicyVersion":null,"vendorIds":[1,"x",null,3],"publisherRestrictions":null}""")!!
        assertNull(odd.tcfPolicyVersion)
        assertEquals(listOf(1, 3), odd.vendorIds)
        assertTrue(odd.publisherRestrictions.isEmpty())
    }

    @Test
    fun tcfRunsOnlyWhenEnabledWithARealCmpId() {
        val base = settings(Fixtures.tcfJson())!!

        assertTrue(base.isCmpActive())
        assertTrue(base.copy(cmpId = 300).isCmpActive())
        assertFalse(base.copy(cmpId = 1).isCmpActive())
        assertFalse(base.copy(cmpId = 0).isCmpActive())
        assertFalse(base.copy(enabled = false).isCmpActive())
        assertFalse((null as TcfSettings?).isCmpActive())
    }

    @Test
    fun pageUrlHangsOffTheApiUrl() {
        assertEquals(page, tcfPageUrl(DEFAULT_API_URL, "pk_test_android"))
        assertEquals(page, tcfPageUrl("https://consent.iqworks.ai/", "pk_test_android"))
        assertEquals("http://10.0.2.2:3000/api/public/tcf/mobile?key=a%26b", tcfPageUrl("http://10.0.2.2:3000", "a&b"))
    }

    @Test
    fun everyPageMessageIsParsed() {
        assertEquals(TcfMessage.Ready, parseTcfMessage("""{"type":"ready"}"""))
        assertEquals(TcfMessage.Show, parseTcfMessage("""{"type":"show"}"""))
        assertEquals(TcfMessage.Current, parseTcfMessage("""{"type":"current"}"""))
        assertEquals(TcfMessage.NotApplicable, parseTcfMessage("""{"type":"not-applicable"}"""))
        assertEquals(TcfMessage.Closed, parseTcfMessage("""{"type":"closed"}"""))
        assertEquals(TcfMessage.Error("no-cmp-id"), parseTcfMessage("""{"type":"error","code":"no-cmp-id"}"""))
        assertEquals(TcfMessage.Error(null), parseTcfMessage("""{"type":"error"}"""))
        assertNull(parseTcfMessage("""{"type":"token","token":"t"}"""))
        assertNull(parseTcfMessage("""{"no":"type"}"""))
        assertNull(parseTcfMessage("not json"))
        assertNull(parseTcfMessage(null))
    }

    @Test
    fun aDecisionCarriesItsKeysStoredObjectAndConsent() {
        val decision = parseTcfMessage(Fixtures.decisionJson()) as TcfMessage.Decision

        assertEquals(Fixtures.TCF_STRING, decision.tcString)
        assertEquals(2, decision.keys["IABTCF_CmpSdkID"])
        assertEquals(1, decision.keys["IABTCF_gdprApplies"])
        assertEquals("DE", decision.keys["IABTCF_PublisherCC"])
        assertEquals("_1_", decision.keys["IABTCF_PublisherRestrictions2"])
        assertEquals(Fixtures.TCF_STRING, JSONObject(decision.stored).getString("tcString"))
        assertEquals(mapOf("necessary" to true, "analytics" to true, "marketing" to false), decision.consent)
        assertNull(decision.scope)
    }

    @Test
    fun aDecisionScopeIsParsedAndAnUnknownScopeIsAbsent() {
        fun scopeOf(scope: String?) = (parseTcfMessage(Fixtures.decisionJson(scope = scope)) as TcfMessage.Decision).scope

        assertEquals(TcfScope.ALL, scopeOf("all"))
        assertEquals(TcfScope.NONE, scopeOf("none"))
        assertEquals(TcfScope.CUSTOM, scopeOf("custom"))
        assertNull(scopeOf("everything"))
        assertNull(scopeOf(null))
    }

    @Test
    fun aMalformedDecisionIsAnError() {
        val invalid = TcfMessage.Error("invalid-decision")
        val stored = """"stored":{"tcString":"x"}"""

        assertEquals(invalid, parseTcfMessage("""{"type":"decision","keys":{"IABTCF_gdprApplies":1},$stored}"""))
        assertEquals(invalid, parseTcfMessage("""{"type":"decision","tcString":"x",$stored}"""))
        assertEquals(invalid, parseTcfMessage("""{"type":"decision","tcString":"x","keys":{},$stored}"""))
        assertEquals(invalid, parseTcfMessage("""{"type":"decision","tcString":"x","keys":{"IABTCF_gdprApplies":1}}"""))
        assertEquals(invalid, parseTcfMessage("""{"type":"decision","tcString":"x","keys":{"IABTCF_gdprApplies":1},"stored":"s"}"""))
    }

    @Test
    fun keysKeepIntegersAndStringsUnderTheIabPrefixOnly() {
        val keys =
            parseTcfKeys(
                JSONObject(
                    """
                    {
                      "IABTCF_gdprApplies": 1, "IABTCF_PolicyVersion": 5.0, "IABTCF_TCString": "CQ",
                      "IABTCF_Fraction": 1.5, "IABTCF_Flag": true, "IABTCF_Object": {}, "IABTCF_Null": null,
                      "IABTCF_TooBig": 99999999999, "other_key": 1
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(mapOf("IABTCF_gdprApplies" to 1, "IABTCF_PolicyVersion" to 5, "IABTCF_TCString" to "CQ"), keys)
        assertTrue(keys["IABTCF_gdprApplies"] is Int)
    }

    @Test
    fun startScriptPassesTheOptionsAsJson() {
        val stored = JSONObject("""{"tcString":"CQ","vendorIds":[1],"restrictionsKey":"[]"}""")
        val script = tcfStartScript("subject-1", stored, mapOf("necessary" to true, "analytics" to false), reopen = true, token = "run-1")

        assertTrue(script.startsWith("window.consentiqTcfStart({") && script.endsWith("})"))
        val options = JSONObject(script.removePrefix("window.consentiqTcfStart(").removeSuffix(")"))
        assertEquals("subject-1", options.getString("subjectId"))
        assertEquals("CQ", options.getJSONObject("stored").getString("tcString"))
        assertFalse(options.getJSONObject("consent").getBoolean("analytics"))
        assertTrue(options.getBoolean("reopen"))
        assertEquals("run-1", options.getString("token"))

        val first =
            JSONObject(
                tcfStartScript(
                    "s\"'</script>",
                    null,
                    null,
                    reopen = false,
                    token = "run-2",
                ).removePrefix("window.consentiqTcfStart(").removeSuffix(")"),
            )
        assertEquals("s\"'</script>", first.getString("subjectId"))
        assertTrue(first.isNull("stored"))
        assertTrue(first.isNull("consent"))
        assertFalse(first.getBoolean("reopen"))
    }

    @Test
    fun navigationStaysOnThePageAndOpensLinksOutsideOnceShown() {
        assertEquals(TcfNavigation.ALLOW, tcfNavigation(page, isMainFrame = true, pageUrl = page, shown = false))
        assertEquals(
            TcfNavigation.ALLOW,
            tcfNavigation("https://challenges.cloudflare.com/x", isMainFrame = false, pageUrl = page, shown = true),
        )
        assertEquals(TcfNavigation.ALLOW, tcfNavigation("about:blank", isMainFrame = false, pageUrl = page, shown = false))
        assertEquals(
            TcfNavigation.EXTERNAL,
            tcfNavigation("https://vendor.example/privacy", isMainFrame = true, pageUrl = page, shown = true),
        )
        assertEquals(
            TcfNavigation.EXTERNAL,
            tcfNavigation("http://vendor.example/privacy", isMainFrame = true, pageUrl = page, shown = true),
        )
        assertEquals(
            TcfNavigation.BLOCK,
            tcfNavigation("https://vendor.example/privacy", isMainFrame = true, pageUrl = page, shown = false),
        )
        assertEquals(TcfNavigation.BLOCK, tcfNavigation("intent://scan/#Intent;end", isMainFrame = true, pageUrl = page, shown = true))
        assertEquals(TcfNavigation.BLOCK, tcfNavigation("javascript:alert(1)", isMainFrame = true, pageUrl = page, shown = true))
        assertEquals(TcfNavigation.BLOCK, tcfNavigation("https://vendor.example/frame", isMainFrame = false, pageUrl = page, shown = true))
    }
}
