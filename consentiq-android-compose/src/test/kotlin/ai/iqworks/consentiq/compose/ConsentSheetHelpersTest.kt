package ai.iqworks.consentiq.compose

import ai.iqworks.consentiq.BannerConfig
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentSheetHelpersTest {
    @Test
    fun langParamIsAppendedWithQuestionMarkWhenUrlHasNoQuery() {
        assertEquals("https://x.test/notice?lang=en", withLangParam("https://x.test/notice", "en"))
    }

    @Test
    fun langParamIsAppendedWithAmpersandWhenUrlHasAQuery() {
        assertEquals(
            "https://x.test/api/public/privacy/pk?notice=n1&lang=ar-EG",
            withLangParam("https://x.test/api/public/privacy/pk?notice=n1", "ar-EG"),
        )
    }

    @Test
    fun langParamKeepsTheFragmentLast() {
        assertEquals("https://x.test/notice?lang=hi#top", withLangParam("https://x.test/notice#top", "hi"))
    }

    @Test
    fun langParamIsUrlEncoded() {
        assertEquals("https://x.test/n?lang=zh+Hans", withLangParam("https://x.test/n", "zh Hans"))
    }

    @Test
    fun nonHttpOrMissingUrlsAreDropped() {
        assertNull(withLangParam(null, "en"))
        assertNull(withLangParam("", "en"))
        assertNull(withLangParam("/api/public/privacy/pk", "en"))
        assertNull(withLangParam("javascript:alert(1)", "en"))
    }

    @Test
    fun hexColorsParse() {
        assertEquals(Color(0xF9, 0x73, 0x16), parseHexColor("#f97316"))
        assertEquals(Color(0xF9, 0x73, 0x16), parseHexColor("F97316"))
        assertEquals(Color(0xFF, 0x00, 0xAA), parseHexColor("#f0a"))
    }

    @Test
    fun invalidHexColorsReturnNull() {
        assertNull(parseHexColor(null))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#12"))
        assertNull(parseHexColor("#gggggg"))
        assertNull(parseHexColor("rgb(1,2,3)"))
        assertNull(parseHexColor("-12345"))
        assertNull(parseHexColor("#12345g"))
    }

    private fun banner(
        bg: String?,
        text: String?,
    ) = BannerConfig(
        position = "bottom",
        layout = "banner",
        theme = "light",
        showLogo = false,
        logoUrl = null,
        primaryColor = "#f97316",
        cornerStyle = "rounded",
        showCookieIcon = false,
        cookieIconUrl = null,
        overlay = false,
        noticeUrl = null,
        bannerBgColor = bg,
        bannerTextColor = text,
    )

    @Test
    fun withoutAValidBackgroundTheSheetKeepsTheAppTheme() {
        assertNull(sheetPalette(banner(bg = null, text = null)))
        assertNull(sheetPalette(banner(bg = null, text = "#ffffff")))
        assertNull(sheetPalette(banner(bg = "navy", text = "#ffffff")))
        assertNull(sheetPalette(banner(bg = "#12", text = null)))
    }

    @Test
    fun aDarkBackgroundUsesTheGivenTextAndDarkSecondaries() {
        val palette = sheetPalette(banner(bg = "#111827", text = "#fde68a"))!!

        assertTrue(palette.isDark)
        assertEquals(Color(0x11, 0x18, 0x27), palette.container)
        assertEquals(Color(0xFD, 0xE6, 0x8A), palette.text)
        assertEquals(Color(0xFF9CA3AF), palette.secondary)
        assertEquals(Color(0xFF374151), palette.outline)
    }

    @Test
    fun aLightBackgroundFallsBackToDarkTextWhenTheTextColorIsMissingOrInvalid() {
        val missing = sheetPalette(banner(bg = "#fff", text = null))!!
        val invalid = sheetPalette(banner(bg = "#ffffff", text = "white"))!!

        assertFalse(missing.isDark)
        assertEquals(Color.White, missing.container)
        assertEquals(Color(0xFF111827), missing.text)
        assertEquals(Color(0xFF4B5563), missing.secondary)
        assertEquals(missing, invalid)
        assertEquals(Color(0xFFF9FAFB), sheetPalette(banner(bg = "#000", text = null))!!.text)
    }

    @Test
    fun lightOrDarkFollowsTheYiqRuleAt150() {
        assertTrue(isLightBackground(parseHexColor("#969696")!!))
        assertFalse(isLightBackground(parseHexColor("#959595")!!))
        assertTrue(isLightBackground(Color.White))
        assertFalse(isLightBackground(Color.Black))

        // Both disagree with WCAG luminance, which would call green light and #a0a0a0 dark.
        assertTrue(sheetPalette(banner(bg = "#00ff00", text = null))!!.isDark)
        assertFalse(sheetPalette(banner(bg = "#a0a0a0", text = null))!!.isDark)
    }

    @Test
    fun thePaletteRecolorsTheSurfaceRolesTheSheetReads() {
        val palette = sheetPalette(banner(bg = "#111827", text = "#f9fafb"))!!
        val scheme = palette.applyTo(lightColorScheme())

        assertEquals(palette.container, scheme.surfaceContainerLow)
        assertEquals(palette.container, scheme.surface)
        assertEquals(palette.text, scheme.onSurface)
        assertEquals(palette.secondary, scheme.onSurfaceVariant)
        assertEquals(palette.outline, scheme.outlineVariant)
    }
}
