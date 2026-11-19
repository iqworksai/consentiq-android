package ai.iqworks.consentiq

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TcfKeyStoreTest {
    private val prefs = FakeSharedPreferences()
    private val keys = TcfKeyStore(prefs)

    private fun decisionKeys(restrictions: String = ", \"IABTCF_PublisherRestrictions2\": \"_1_\""): Map<String, Any> =
        parseTcfKeys(JSONObject(Fixtures.decisionJson(restrictions = restrictions)).getJSONObject("keys"))

    @Test
    fun numbersAreReadableWithGetIntAndStringsWithGetString() {
        keys.write(decisionKeys())

        assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
        assertFalse(prefs.contains("IABTCF_CmpSdkID"))
        assertFalse(prefs.contains("IABTCF_CmpSdkVersion"))
        assertEquals(5, prefs.getInt("IABTCF_PolicyVersion", -1))
        assertEquals(0, prefs.getInt("IABTCF_PurposeOneTreatment", -1))
        assertEquals(Fixtures.TCF_STRING, prefs.getString("IABTCF_TCString", null))
        assertEquals("DE", prefs.getString("IABTCF_PublisherCC", null))
        assertEquals("_1_", prefs.getString("IABTCF_PublisherRestrictions2", null))
    }

    @Test
    fun restrictionKeysMissingFromADecisionAreRemoved() {
        prefs.values["IABTCF_PublisherRestrictions1"] = "1"
        prefs.values["IABTCF_PublisherRestrictions2"] = "old"
        prefs.values["IABTCF_PublisherRestrictions11"] = "2"
        prefs.values["IABTCF_PublisherRestrictions12"] = "kept"
        prefs.values["app_setting"] = true

        keys.write(decisionKeys())

        assertFalse(prefs.contains("IABTCF_PublisherRestrictions1"))
        assertEquals("_1_", prefs.getString("IABTCF_PublisherRestrictions2", null))
        assertFalse(prefs.contains("IABTCF_PublisherRestrictions11"))
        assertTrue(prefs.contains("IABTCF_PublisherRestrictions12"))
        assertTrue(prefs.getBoolean("app_setting", false))

        keys.write(decisionKeys(restrictions = ""))
        assertTrue((1..11).none { prefs.contains("IABTCF_PublisherRestrictions$it") })
    }

    @Test
    fun aKeyOnceStoredAsAStringIsRewrittenAsAnInt() {
        prefs.values["IABTCF_gdprApplies"] = "1"

        keys.write(decisionKeys())

        assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
    }

    @Test
    fun gdprAppliesAndCmpAreWrittenAsInts() {
        keys.writeCmp(2, 1)
        keys.writeGdprApplies(0)

        assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
        assertEquals(1, prefs.getInt("IABTCF_CmpSdkVersion", -1))
        assertEquals(0, prefs.getInt("IABTCF_gdprApplies", -1))

        val fresh = FakeSharedPreferences()
        TcfKeyStore(fresh).writeCmp(7, null)
        assertEquals(7, fresh.getInt("IABTCF_CmpSdkID", -1))
        assertFalse(fresh.contains("IABTCF_CmpSdkVersion"))
    }

    @Test
    fun thePageCanNeverSetTheCmpKeysThatOwnershipDependsOn() {
        keys.writeCmp(2, 1)

        keys.write(decisionKeys() + mapOf("IABTCF_CmpSdkID" to 300, "IABTCF_CmpSdkVersion" to 9))

        assertEquals(2, prefs.getInt("IABTCF_CmpSdkID", -1))
        assertEquals(1, prefs.getInt("IABTCF_CmpSdkVersion", -1))
        assertEquals(Fixtures.TCF_STRING, prefs.getString("IABTCF_TCString", null))
    }

    @Test
    fun clearingADecisionRemovesItsKeysAndKeepsCmpAndGdprApplies() {
        for (key in TCF_DECISION_KEYS) prefs.values[key] = "x"
        prefs.values["IABTCF_CmpSdkID"] = 2
        prefs.values["IABTCF_CmpSdkVersion"] = 1
        prefs.values["IABTCF_gdprApplies"] = 1
        prefs.values["app_setting"] = true

        keys.clearDecision(2)

        assertEquals(26, TCF_DECISION_KEYS.size)
        assertEquals(setOf("IABTCF_CmpSdkID", "IABTCF_CmpSdkVersion", "IABTCF_gdprApplies", "app_setting"), prefs.values.keys)
    }

    @Test
    fun clearingAllAlsoRemovesCmpAndGdprApplies() {
        for (key in TCF_DECISION_KEYS) prefs.values[key] = "x"
        prefs.values["IABTCF_CmpSdkID"] = 2
        prefs.values["IABTCF_CmpSdkVersion"] = 1
        prefs.values["IABTCF_gdprApplies"] = 1
        prefs.values["app_setting"] = true

        keys.clearAll(300)
        assertEquals(30, prefs.values.size)

        keys.clearAll(2)
        assertEquals(setOf("app_setting"), prefs.values.keys)
    }

    @Test
    fun clearingLeavesKeysAloneUnlessThisCmpWroteThem() {
        prefs.values["IABTCF_TCString"] = "other-cmp"

        keys.clearDecision(2)
        prefs.values["IABTCF_CmpSdkID"] = 300
        keys.clearDecision(2)
        prefs.values["IABTCF_CmpSdkID"] = "2"
        keys.clearDecision(2)

        assertEquals("other-cmp", prefs.getString("IABTCF_TCString", null))
    }

    @Test
    fun everyWriteIsOneApply() {
        keys.write(decisionKeys())
        keys.writeGdprApplies(1)
        keys.writeCmp(2, 1)

        assertEquals(3, prefs.applies)
        assertEquals(0, prefs.commits)
    }

    @Test
    fun theStoreIsTheAppsDefaultPreferencesFile() {
        val context = PrefsContext("ai.iqworks.example", prefs)

        TcfKeyStore(context).writeGdprApplies(1)

        assertEquals("ai.iqworks.example_preferences", context.requestedName)
        assertEquals(Context.MODE_PRIVATE, context.requestedMode)
        assertEquals(1, prefs.getInt("IABTCF_gdprApplies", -1))
        assertSame(prefs, TcfKeyStore.defaultSharedPreferences(context))
    }

    private class PrefsContext(
        private val pkg: String,
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        var requestedName: String? = null
        var requestedMode: Int = -1

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = pkg

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences {
            requestedName = name
            requestedMode = mode
            return prefs
        }
    }
}
