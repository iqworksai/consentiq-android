package ai.iqworks.consentiq

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

public interface ConsentStorage {
    public fun getString(key: String): String?

    public fun putString(
        key: String,
        value: String,
    )

    public fun remove(key: String)
}

public class SharedPreferencesStorage(
    private val prefs: SharedPreferences,
) : ConsentStorage {
    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    public companion object {
        public const val PREFS_NAME: String = "consentiq"
    }
}

public class ConsentStore(
    private val storage: ConsentStorage,
) {
    public fun loadConsent(): Map<String, Boolean>? {
        val raw = runCatching { storage.getString(CONSENT_KEY) }.getOrNull() ?: return null
        return runCatching { booleanMap(JSONObject(raw)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    public fun saveConsent(consent: Map<String, Boolean>) {
        runCatching { storage.putString(CONSENT_KEY, consent.toJson().toString()) }
    }

    public fun clearConsent() {
        runCatching { storage.remove(CONSENT_KEY) }
    }

    public fun clearAll() {
        runCatching {
            storage.remove(CONSENT_KEY)
            storage.remove(SUBJECT_ID_KEY)
        }
    }

    public fun getSubjectId(): String? = runCatching { storage.getString(SUBJECT_ID_KEY) }.getOrNull()

    public fun getOrCreateSubjectId(): String {
        val existing = getSubjectId()
        if (!existing.isNullOrEmpty()) return existing
        val fresh = generateSubjectId()
        runCatching { storage.putString(SUBJECT_ID_KEY, fresh) }
        return fresh
    }

    public companion object {
        public const val CONSENT_KEY: String = "consentiq_consent"
        public const val SUBJECT_ID_KEY: String = "consentiq_subject_id"

        public fun generateSubjectId(): String = UUID.randomUUID().toString()
    }
}
