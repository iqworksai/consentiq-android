package ai.iqworks.consentiq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    @Test
    fun emailsCompareByCase() {
        assertTrue(sameIdentifier("Alice@Example.com", " alice@example.com "))
        assertFalse(sameIdentifier("alice@example.com", "bob@example.com"))
    }

    @Test
    fun phonesCompareByDigitsOnlyWhenBothReadAsPhones() {
        assertTrue(sameIdentifier("+1 (202) 555-0143", "+12025550143"))
        assertFalse(sameIdentifier("+12025550143", "+12025550144"))
    }

    @Test
    fun anythingElseMustMatchExactly() {
        assertFalse(sameIdentifier("alice", "bob"))
        assertFalse(sameIdentifier("user-1", "User-1"))
        assertTrue(sameIdentifier("user-1", "user-1"))
    }

    @Test
    fun withoutIdentityForTheTokenGoesAsIs() {
        assertEquals("user-1" to "v2.1.sig", signedSubject("v2.1.sig", null, "user-1"))
        assertEquals("A@x.com" to null, signedSubject(null, "a@x.com", "A@x.com"))
    }
}
