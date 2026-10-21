package ai.iqworks.consentiq

import android.content.Context
import android.content.SharedPreferences

// apply() updates the in-memory values and change listeners vendor SDKs read at once; commit() would add a disk wait on the main thread.
internal class TcfKeyStore(
    private val prefs: SharedPreferences,
) : TcfKeyWriter {
    constructor(context: Context) : this(defaultSharedPreferences(context))

    override fun write(keys: Map<String, Any>) {
        val editor = prefs.edit()
        for ((key, value) in keys) {
            if (key in NATIVE_OWNED) continue
            when (value) {
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            }
        }
        for (purpose in TCF_RESTRICTION_PURPOSES) {
            val key = "$TCF_PUBLISHER_RESTRICTIONS$purpose"
            if (key !in keys) editor.remove(key)
        }
        editor.apply()
    }

    override fun writeGdprApplies(value: Int) {
        prefs.edit().putInt(TCF_GDPR_APPLIES, value).apply()
    }

    override fun writeCmp(
        id: Int,
        version: Int?,
    ) {
        val editor = prefs.edit().putInt(TCF_CMP_SDK_ID, id)
        if (version != null) editor.putInt(TCF_CMP_SDK_VERSION, version)
        editor.apply()
    }

    // Another CMP may own these keys; they are only ours to clear while CmpSdkID is still the one this SDK wrote.
    override fun clearDecision(cmpId: Int) = removeIfOwned(cmpId, TCF_DECISION_KEYS)

    override fun clearAll(cmpId: Int) =
        removeIfOwned(
            cmpId,
            TCF_DECISION_KEYS + listOf(TCF_CMP_SDK_ID, TCF_CMP_SDK_VERSION, TCF_GDPR_APPLIES),
        )

    private fun removeIfOwned(
        cmpId: Int,
        keys: List<String>,
    ) {
        val owner =
            try {
                prefs.getInt(TCF_CMP_SDK_ID, -1)
            } catch (e: ClassCastException) {
                return
            }
        if (owner != cmpId) return
        val editor = prefs.edit()
        for (key in keys) editor.remove(key)
        editor.apply()
    }

    companion object {
        // Written only from the config: the clean-up ownership check reads CmpSdkID, so the page never sets it.
        private val NATIVE_OWNED = setOf(TCF_CMP_SDK_ID, TCF_CMP_SDK_VERSION)

        fun defaultPreferencesName(packageName: String): String = "${packageName}_preferences"

        // The file PreferenceManager.getDefaultSharedPreferences returns, without depending on androidx.preference.
        fun defaultSharedPreferences(context: Context): SharedPreferences {
            val app = context.applicationContext ?: context
            return app.getSharedPreferences(defaultPreferencesName(app.packageName), Context.MODE_PRIVATE)
        }
    }
}
