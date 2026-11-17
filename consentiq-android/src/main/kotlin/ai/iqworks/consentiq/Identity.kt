package ai.iqworks.consentiq

/**
 * The subject to send and the identity token to send with it. A token is signed over one exact
 * identifier, and ConsentIQ verifies it against the subject as sent, so when the submitted identifier is
 * the signed one written differently (an email's case, a phone's spacing) the signed form goes instead.
 * Any other identifier goes unsigned. The server is the judge.
 */
internal fun signedSubject(
    identityToken: String?,
    identityFor: String?,
    identifier: String,
): Pair<String, String?> {
    if (identityToken.isNullOrEmpty()) return identifier to null
    if (identityFor == null) return identifier to identityToken
    return if (sameIdentifier(identityFor, identifier)) identityFor to identityToken else identifier to null
}

/**
 * Whether two identifiers name the same person: emails by case, phones by their digits once both read as
 * one (an optional leading plus, then digits with spaces, dots, dashes or brackets). Anything else must
 * match exactly, so two names never collapse to the same empty digit string.
 */
internal fun sameIdentifier(
    first: String,
    second: String,
): Boolean {
    val lhs = first.trim()
    val rhs = second.trim()
    if ('@' in lhs || '@' in rhs) return lhs.lowercase() == rhs.lowercase()
    val lhsDigits = phoneDigits(lhs)
    val rhsDigits = phoneDigits(rhs)
    return if (lhsDigits != null && rhsDigits != null) lhsDigits == rhsDigits else lhs == rhs
}

private val PHONE_SHAPE = Regex("""^\+?[\d\s().-]+$""")
private val PHONE_SEPARATORS = Regex("""[\s().-]""")
private val PHONE_DIGITS = Regex("""^\+?\d{7,15}$""")

private fun phoneDigits(value: String): String? {
    if (!PHONE_SHAPE.matches(value)) return null
    val digits = value.replace(PHONE_SEPARATORS, "")
    return if (PHONE_DIGITS.matches(digits)) digits else null
}
