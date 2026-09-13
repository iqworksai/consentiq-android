package ai.iqworks.consentiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentStoreTest {
    private val storage = InMemoryStorage()
    private val store = ConsentStore(storage)

    @Test
    fun consentRoundTripsAsJsonUnderTheConsentKey() {
        val consent = mapOf("necessary" to true, "analytics" to true, "marketing" to false)

        store.saveConsent(consent)

        assertTrue(storage.values.containsKey("consentiq_consent"))
        assertEquals(consent, store.loadConsent())
    }

    @Test
    fun loadConsentReturnsNullWhenNothingStored() {
        assertNull(store.loadConsent())
    }

    @Test
    fun loadConsentReturnsNullOnCorruptJson() {
        storage.putString("consentiq_consent", "not json")

        assertNull(store.loadConsent())
    }

    @Test
    fun subjectIdIsGeneratedOnceAndPersistedUnderTheSubjectKey() {
        val first = store.getOrCreateSubjectId()
        val second = store.getOrCreateSubjectId()

        assertEquals(first, second)
        assertEquals(first, storage.values["consentiq_subject_id"])
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first))
    }

    @Test
    fun clearConsentRemovesOnlyTheConsentCache() {
        store.saveConsent(mapOf("necessary" to true))
        val subject = store.getOrCreateSubjectId()

        store.clearConsent()

        assertNull(store.loadConsent())
        assertEquals(subject, store.getSubjectId())
    }

    @Test
    fun clearAllRemovesConsentAndSubjectId() {
        store.saveConsent(mapOf("necessary" to true))
        val subject = store.getOrCreateSubjectId()

        store.clearAll()

        assertNull(store.loadConsent())
        assertNull(store.getSubjectId())
        assertFalse(storage.values.containsKey("consentiq_subject_id"))
        assertNotEquals(subject, store.getOrCreateSubjectId())
    }

    @Test
    fun storageFailuresAreSwallowed() {
        val failing =
            object : ConsentStorage {
                override fun getString(key: String): String? = throw IllegalStateException("disk")

                override fun putString(
                    key: String,
                    value: String,
                ) = throw IllegalStateException("disk")

                override fun remove(key: String) = throw IllegalStateException("disk")
            }
        val fragile = ConsentStore(failing)

        fragile.saveConsent(mapOf("necessary" to true))
        fragile.clearAll()
        assertNull(fragile.loadConsent())
        assertNull(fragile.getSubjectId())
        assertTrue(fragile.getOrCreateSubjectId().isNotEmpty())
    }
}
