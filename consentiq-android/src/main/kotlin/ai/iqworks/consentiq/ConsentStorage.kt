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

    /** The local copy, only when it was written for this subject: another subject never inherits it. */
    public fun loadConsent(subjectId: String): Map<String, Boolean>? {
        val owner = runCatching { storage.getString(CONSENT_SUBJECT_KEY) }.getOrNull()
        return if (owner == subjectId) loadConsent() else null
    }

    public fun saveConsent(
        consent: Map<String, Boolean>,
        subjectId: String,
    ) {
        saveConsent(consent)
        runCatching { storage.putString(CONSENT_SUBJECT_KEY, subjectId) }
    }

    public fun clearConsent() {
        runCatching {
            storage.remove(CONSENT_KEY)
            storage.remove(CONSENT_SUBJECT_KEY)
        }
    }

    public fun clearAll() {
        runCatching {
            storage.remove(CONSENT_KEY)
            storage.remove(CONSENT_SUBJECT_KEY)
            storage.remove(SUBJECT_ID_KEY)
            storage.remove(TCF_KEY)
            storage.remove(PENDING_KEY)
            storage.remove(UNRECORDED_KEY)
        }
    }

    internal fun isUnrecorded(propertyKey: String): Boolean = runCatching { storage.getString(UNRECORDED_KEY) }.getOrNull() == propertyKey

    internal fun markUnrecorded(propertyKey: String) {
        runCatching { storage.putString(UNRECORDED_KEY, propertyKey) }
    }

    internal fun clearUnrecorded() {
        runCatching { storage.remove(UNRECORDED_KEY) }
    }

    internal fun loadPending(): PendingDecision? {
        val raw = runCatching { storage.getString(PENDING_KEY) }.getOrNull() ?: return null
        val pending = runCatching { PendingDecision.fromJson(JSONObject(raw)) }.getOrNull()
        if (pending == null) clearPending()
        return pending
    }

    internal fun savePending(pending: PendingDecision) {
        runCatching { storage.putString(PENDING_KEY, pending.toJson().toString()) }
    }

    internal fun clearPending() {
        runCatching { storage.remove(PENDING_KEY) }
    }

    internal fun loadTcf(): JSONObject? {
        val raw = runCatching { storage.getString(TCF_KEY) }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    internal fun saveTcf(stored: String) {
        runCatching { storage.putString(TCF_KEY, stored) }
    }

    internal fun clearTcf() {
        runCatching { storage.remove(TCF_KEY) }
    }

    internal fun tcfCmpId(): Int? = runCatching { storage.getString(TCF_CMP_ID_KEY)?.toIntOrNull() }.getOrNull()

    internal fun saveTcfCmpId(id: Int) {
        runCatching { storage.putString(TCF_CMP_ID_KEY, id.toString()) }
    }

    internal fun clearTcfCmpId() {
        runCatching { storage.remove(TCF_CMP_ID_KEY) }
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
        public const val TCF_KEY: String = "consentiq_tcf"
        public const val PENDING_KEY: String = "consentiq_pending"

        /** The subject the local copy was written for. */
        public const val CONSENT_SUBJECT_KEY: String = "consentiq_consent_subject"

        /**
         * Set to the property key when a decision was dropped without ConsentIQ recording it. While it
         * stands the sheet asks again with nothing granted. The next recorded decision clears it.
         */
        public const val UNRECORDED_KEY: String = "consentiq_unrecorded_decision"
        internal const val TCF_CMP_ID_KEY: String = "consentiq_tcf_cmp_id"

        public fun generateSubjectId(): String = UUID.randomUUID().toString()
    }
}
