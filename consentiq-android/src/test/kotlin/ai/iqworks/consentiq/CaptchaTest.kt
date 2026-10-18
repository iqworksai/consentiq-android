package ai.iqworks.consentiq

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaTest {
    private val page = "https://consent.iqworks.ai/api/public/captcha/mobile"

    @Test
    fun pageUrlHangsOffTheApiUrl() {
        assertEquals(page, captchaPageUrl(DEFAULT_API_URL))
        assertEquals(page, captchaPageUrl("https://consent.iqworks.ai/"))
        assertEquals("http://10.0.2.2:3000/api/public/captcha/mobile", captchaPageUrl("http://10.0.2.2:3000"))
    }

    @Test
    fun originDropsPathAndDefaultPort() {
        assertEquals("https://consent.iqworks.ai", originOf(page))
        assertEquals("https://consent.iqworks.ai", originOf("https://Consent.IQWorks.ai:443/x"))
        assertEquals("http://10.0.2.2:3000", originOf("http://10.0.2.2:3000/api/public/captcha/mobile"))
        assertNull(originOf("file:///android_asset/x.html"))
        assertNull(originOf("not a url"))
    }

    @Test
    fun mainFrameMayOnlyLoadTheCaptchaPage() {
        assertTrue(isAllowedCaptchaNavigation(page, isMainFrame = true, pageUrl = page))
        assertFalse(isAllowedCaptchaNavigation("$page?next=1", isMainFrame = true, pageUrl = page))
        assertFalse(isAllowedCaptchaNavigation("https://consent.iqworks.ai/", isMainFrame = true, pageUrl = page))
        assertFalse(isAllowedCaptchaNavigation("https://challenges.cloudflare.com/x", isMainFrame = true, pageUrl = page))
    }

    @Test
    fun subframesMayOnlyLoadCloudflareChallenges() {
        assertTrue(isAllowedCaptchaNavigation("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/x", false, page))
        assertTrue(isAllowedCaptchaNavigation("about:blank", false, page))
        assertTrue(isAllowedCaptchaNavigation("about:srcdoc", false, page))
        assertFalse(isAllowedCaptchaNavigation("http://challenges.cloudflare.com/x", false, page))
        assertFalse(isAllowedCaptchaNavigation("https://challenges.cloudflare.com.evil.test/x", false, page))
        assertFalse(isAllowedCaptchaNavigation("intent://scan/#Intent;end", false, page))
        assertFalse(isAllowedCaptchaNavigation(page, false, page))
    }

    @Test
    fun bridgeMessagesAreParsed() {
        assertEquals(CaptchaMessage.Ready, parseCaptchaMessage("""{"type":"ready"}"""))
        assertEquals(CaptchaMessage.Token("t-1"), parseCaptchaMessage("""{"type":"token","token":"t-1"}"""))
        assertEquals(CaptchaMessage.Error(null), parseCaptchaMessage("""{"type":"token","token":""}"""))
        assertEquals(CaptchaMessage.Error("300030"), parseCaptchaMessage("""{"type":"error","code":"300030"}"""))
        assertEquals(CaptchaMessage.Error(null), parseCaptchaMessage("""{"type":"error"}"""))
        assertNull(parseCaptchaMessage("""{"type":"other"}"""))
        assertNull(parseCaptchaMessage("not json"))
        assertNull(parseCaptchaMessage(null))
    }

    @Test
    fun captchaIsRequiredOnlyWithASiteKey() {
        val base = parsePropertyConfig(JSONObject(Fixtures.configJson()))
        assertFalse(base.requiresCaptcha())
        assertTrue(base.copy(requireCaptcha = true, turnstileSiteKey = "k").requiresCaptcha())
        assertFalse(base.copy(requireCaptcha = true, turnstileSiteKey = null).requiresCaptcha())
        assertFalse(base.copy(requireCaptcha = true, turnstileSiteKey = " ").requiresCaptcha())
        assertFalse(base.copy(requireCaptcha = false, turnstileSiteKey = "k").requiresCaptcha())
        assertFalse((null as PropertyConfig?).requiresCaptcha())
    }

    @Test
    fun tokenOrWarnReturnsTheTokenOrLogsTheFixedWarning() =
        runTest {
            val logger = RecordingLogger()

            assertEquals("tok", FakeCaptchaProvider { "tok" }.tokenOrWarn(logger))
            assertTrue(logger.warnings.isEmpty())

            assertNull(FakeCaptchaProvider { throw CaptchaException("turnstile error 300030") }.tokenOrWarn(logger))
            assertNull(CaptchaTokenProvider.UNAVAILABLE.tokenOrWarn(logger))
            assertEquals(List(2) { "[ConsentIQ] Captcha check failed; consent not submitted" }, logger.warnings)
        }

    @Test
    fun submissionCarriesTheTokenOnlyWhenPresent() {
        val without = ConsentSubmission(subjectId = "s").toJson(Fixtures.PROPERTY_KEY)
        val withToken = ConsentSubmission(subjectId = "s", captchaToken = "tok").toJson(Fixtures.PROPERTY_KEY)

        assertFalse(without.has("captchaToken"))
        assertEquals("tok", withToken.getString("captchaToken"))
    }
}
