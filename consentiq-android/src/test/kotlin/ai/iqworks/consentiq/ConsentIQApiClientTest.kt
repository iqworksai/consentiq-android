package ai.iqworks.consentiq

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ConsentIQApiClientTest {
    private lateinit var server: StubHttpServer
    private val received get() = server.received

    @Before
    fun startServer() {
        server = StubHttpServer()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun baseUrl() = server.baseUrl

    private fun route(
        path: String,
        status: Int = 200,
        body: String,
    ) = server.route(path, status, body)

    private fun client() = ConsentIQApiClient(Fixtures.PROPERTY_KEY, baseUrl())

    @Test
    fun getConfigHitsTheConfigUrlAndParsesTheResponse() =
        runTest {
            route("/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson())

            val config = client().getConfig()

            assertEquals("GET", received.single().method)
            assertEquals("/api/public/config/${Fixtures.PROPERTY_KEY}", received.single().path)
            assertEquals("prop-1", config.property.id)
            assertEquals("android", config.property.platform)
            assertEquals(listOf("en", "ar"), config.property.supportedLanguages)
            assertEquals("#123456", config.banner.primaryColor)
            assertEquals("DPDPA", config.resolvedRegulation)
            assertEquals(3, config.categories.size)
            assertTrue(config.categories.first { it.code == "necessary" }.isRequired)
            assertNull(config.categories.first { it.code == "analytics" }.description)
            assertNull(config.categories.first { it.code == "marketing" }.description)
            assertEquals("Accept all", config.labels.getValue("en").acceptAll)
            assertEquals("قبول الكل", config.labels.getValue("ar").acceptAll)
            assertEquals(listOf("ar", "ur", "ks", "sd"), config.rtl)
            assertEquals("We use cookies.", config.translations.getValue("en").bannerDescription)
            assertNull(config.translations.getValue("ar").bannerDescription)
            assertEquals(
                "Usage stats",
                config.translations
                    .getValue("en")
                    .categories
                    .getValue("analytics")
                    .description,
            )
            assertEquals(3, config.privacyNotices.single().version)
            assertEquals(
                "<p>Hi</p>",
                config.privacyNotices
                    .single()
                    .contentTranslations
                    .getValue("en"),
            )
            assertNull(config.formConsentSources.single().selector)
            assertNull(config.turnstileSiteKey)
            assertFalse(config.requireCaptcha)
        }

    @Test
    fun configWithNullableFieldsAbsent() =
        runTest {
            route(
                "/api/public/config/${Fixtures.PROPERTY_KEY}",
                body = Fixtures.configJson(noticeUrl = null, primaryColor = null, resolvedRegulation = null),
            )

            val config = client().getConfig()

            assertNull(config.banner.noticeUrl)
            assertNull(config.banner.primaryColor)
            assertNull(config.banner.bannerBgColor)
            assertNull(config.banner.bannerTextColor)
            assertNull(config.resolvedRegulation)
        }

    @Test
    fun bannerColorsAreParsedTolerantly() =
        runTest {
            route(
                "/api/public/config/${Fixtures.PROPERTY_KEY}",
                body = Fixtures.configJson(bannerColors = """"bannerBgColor": "#111827", "bannerTextColor": null,"""),
            )

            val banner = client().getConfig().banner

            assertEquals("#111827", banner.bannerBgColor)
            assertNull(banner.bannerTextColor)
        }

    @Test
    fun labelAndTranslationLookupFallsBackFromRegionToBaseToEnglish() =
        runTest {
            route("/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson())
            val config = client().getConfig()

            assertEquals("قبول الكل", config.labelsFor("ar-EG")?.acceptAll)
            assertEquals("Accept all", config.labelsFor("fr")?.acceptAll)
            assertEquals(
                "التحليلات",
                config
                    .translationsFor("ar-EG")
                    ?.categories
                    ?.get("analytics")
                    ?.title,
            )
            assertTrue(config.isRtl("ar-EG"))
            assertTrue(config.isRtl("AR"))
            assertFalse(config.isRtl("en"))
        }

    @Test
    fun getConsentReturnsSavedConsent() =
        runTest {
            route("/api/public/consent/${Fixtures.PROPERTY_KEY}/subj-1", body = Fixtures.savedConsentJson())

            val lookup = client().getConsent("subj-1")

            assertEquals("/api/public/consent/${Fixtures.PROPERTY_KEY}/subj-1", received.single().path)
            assertFalse(lookup.expired)
            val consent = lookup.consent
            assertNotNull(consent)
            assertEquals("consent-1", consent!!.id)
            assertEquals(true, consent.cookieConsents["analytics"])
            assertEquals(false, consent.cookieConsents["marketing"])
            assertEquals(true, consent.marketingConsents["email"])
            assertNull(consent.expiresAt)
            assertNull(consent.consentVersion)
            assertEquals("DPDPA", consent.regulationApplied)
        }

    @Test
    fun getConsentPassesExpiredThrough() =
        runTest {
            route("/api/public/consent/${Fixtures.PROPERTY_KEY}/subj-1", body = Fixtures.EXPIRED)

            val lookup = client().getConsent("subj-1")

            assertNull(lookup.consent)
            assertTrue(lookup.expired)
        }

    @Test
    fun getConsentTreatsMissingExpiredAsFalse() =
        runTest {
            route("/api/public/consent/${Fixtures.PROPERTY_KEY}/subj-1", body = Fixtures.NO_CONSENT)

            val lookup = client().getConsent("subj-1")

            assertNull(lookup.consent)
            assertFalse(lookup.expired)
        }

    @Test
    fun submitConsentPostsJsonBodyWithPropertyKey() =
        runTest {
            route("/api/public/consent", body = Fixtures.SUBMIT_OK)

            val result =
                client().submitConsent(
                    ConsentSubmission(
                        subjectId = "subj-1",
                        cookieConsents = mapOf("necessary" to true, "analytics" to true),
                        marketingConsents = mapOf("email" to true),
                        consentMethod = "sdk",
                    ),
                )

            val request = received.single()
            assertEquals("POST", request.method)
            assertEquals("/api/public/consent", request.path)
            assertEquals("application/json", request.headers["content-type"])
            val body = JSONObject(request.body)
            assertEquals(Fixtures.PROPERTY_KEY, body.getString("propertyKey"))
            assertEquals("subj-1", body.getString("subjectId"))
            assertEquals("sdk", body.getString("consentMethod"))
            assertTrue(body.getJSONObject("cookieConsents").getBoolean("analytics"))
            assertTrue(body.getJSONObject("marketingConsents").getBoolean("email"))
            assertFalse(body.has("subjectType"))
            assertFalse(body.has("consentVersion"))
            assertFalse(body.has("captchaToken"))
            assertTrue(result.success)
            assertEquals("c-123", result.consentId)
            assertEquals("abc", result.proofHash)
        }

    @Test
    fun formSubmissionCarriesSubjectTypeAndMethod() =
        runTest {
            route("/api/public/consent", body = Fixtures.SUBMIT_OK)

            client().submitConsent(
                ConsentSubmission(
                    subjectId = "user-9",
                    subjectType = "user",
                    marketingConsents = mapOf("email" to true, "sms" to false),
                    consentMethod = "form",
                ),
            )

            val body = JSONObject(received.single().body)
            assertEquals("user", body.getString("subjectType"))
            assertEquals("form", body.getString("consentMethod"))
            assertFalse(body.has("cookieConsents"))
            assertFalse(body.getJSONObject("marketingConsents").getBoolean("sms"))
        }

    @Test
    fun verifyConsentParsesResponse() =
        runTest {
            route(
                "/api/public/verify/c-123",
                body =
                    """
                    {"verified":true,"status":"verified","statusMessage":"Verified by ConsentIQ.",
                     "consent":{"id":"c-123","consentedAt":"2026-09-01T00:00:00.000Z","proofHash":"abc"}}
                    """.trimIndent(),
            )

            val result = client().verifyConsent("c-123")

            assertTrue(result.verified)
            assertEquals("verified", result.status)
            assertEquals("c-123", result.consent?.id)
            assertEquals("abc", result.consent?.proofHash)
        }

    @Test
    fun errorBodyMessageIsSurfaced() =
        runTest {
            route("/api/public/config/${Fixtures.PROPERTY_KEY}", status = 404, body = """{"error":"Property not found"}""")

            try {
                client().getConfig()
                fail("expected ConsentIQException")
            } catch (e: ConsentIQException) {
                assertEquals("Property not found", e.message)
                assertEquals(404, e.status)
            }
        }

    @Test
    fun errorWithoutJsonBodyFallsBackToHttpStatus() =
        runTest {
            route("/api/public/consent", status = 502, body = "Bad gateway")

            try {
                client().submitConsent(ConsentSubmission(subjectId = "s", cookieConsents = mapOf("necessary" to true)))
                fail("expected ConsentIQException")
            } catch (e: ConsentIQException) {
                assertEquals("HTTP 502", e.message)
                assertEquals(502, e.status)
            }
        }

    @Test
    fun baseUrlTrailingSlashIsTrimmed() =
        runTest {
            route("/api/public/config/${Fixtures.PROPERTY_KEY}", body = Fixtures.configJson())

            ConsentIQApiClient(Fixtures.PROPERTY_KEY, baseUrl() + "/").getConfig()

            assertEquals("/api/public/config/${Fixtures.PROPERTY_KEY}", received.single().path)
        }
}
