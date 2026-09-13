package ai.iqworks.consentiq.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }
}
