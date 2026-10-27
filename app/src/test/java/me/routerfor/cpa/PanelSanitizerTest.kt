package me.routerfor.cpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitiser's failure mode is silence: if upstream renames a minified identifier, the old
 * rules replace nothing and the panel still renders - just with the promotion back. The first
 * version of this file shipped exactly that bug, and the screenshot check did not catch it
 * because the sponsor page is two taps deep.
 *
 * These cases therefore assert the *shape* the rules key off (domains, display strings, array
 * members) rather than the current variable names, so a renamed bundle still passes.
 */
class PanelSanitizerTest {

    /** A stand-in for the minified bundle: arbitrary short names, real payloads. */
    private fun bundle(n: List<String>): String = buildString {
        append("<html><head><script>(function(){")
        append("var ").append(n[0]).append("=`apikeyFun`,")
        append(n[1]).append("=`APIKEY.FUN`,")
        append(n[2]).append("=`https://apikey.fan/register?aff=AKCPA`,")
        append(n[3]).append("=`https://apikey.fan/dashboard`,")
        append(n[4]).append("=`https://api.apikey.fan`,")
        append(n[5]).append("=`https://slb.apikey.fan`;")
        append("var ").append(n[6]).append("=[").append(n[7]).append("];")
        append("var q=").append(n[8]).append(".find(e=>e.baseUrl??`https://api.apikey.fan`);")
        append("var r=[{path:`/quick-start`,element:f({fixedBrand:`apikeyFun`})}];")
        append("var s=`https://api.fenno.ai/register?aff=DQFAMNB6CBLY`,t=`https://api.fenno.ai`;")
        append("})();</script></head><body></body></html>")
    }

    private val current = bundle(
        listOf("US", "WS", "GS", "KS", "qS", "JS", "Ww", "`kimi`,`gemini`,`apikeyFun`,`fennoAI`,`qiniuCloud`", "eC")
    )

    /** The same document as the previous upstream build, where every name differed. */
    private val older = bundle(
        listOf("dS", "sS", "cS", "lS", "uS", "JS", "Ww", "`kimi`,`apikeyFun`,`fennoAI`", "eC")
    )

    @Test
    fun stripsEveryDomainAndReferralTag() {
        for (html in listOf(current, older)) {
            val out = PanelSanitizer.strip(html)
            for (marker in listOf(
                "apikey.fan", "fenno.ai", "APIKEY.FUN", "FennoAI", "aff=AKCPA", "aff=DQFAMNB6CBLY"
            )) {
                assertFalse("still contains " + marker, out.contains(marker))
            }
        }
    }

    @Test
    fun resultReportsCleanOnlyWhenItIs() {
        // The raw bundle is dirty; only the stripped one may report clean.
        assertTrue(PanelSanitizer.hasSponsorContent(current))
        assertTrue(PanelSanitizer.hasSponsorContent(older))
        assertFalse(PanelSanitizer.hasSponsorContent(PanelSanitizer.strip(current)))
        assertFalse(PanelSanitizer.hasSponsorContent(PanelSanitizer.strip(older)))
    }

    /** A rule that blanks a host but leaves its referral tag behind must not count as clean. */
    @Test
    fun leftoverReferralTagIsStillSponsorContent() {
        assertTrue(PanelSanitizer.hasSponsorContent("https://example.test/register?aff=AKCPA"))
    }

    /**
     * The provider list is a minified array literal; a removal that eats a comma turns the whole
     * bundle into a syntax error and the panel renders blank.
     */
    @Test
    fun providerArrayStaysSyntacticallyValid() {
        val out = PanelSanitizer.strip(current)
        val array = Regex("Ww=\\[[^\\]]*\\]").find(out)?.value
            ?: error("provider array disappeared from the bundle")
        assertFalse("sponsor brand still in the array", array.contains("apikeyFun"))
        assertFalse("sponsor brand still in the array", array.contains("fennoAI"))
        assertTrue("stock providers were removed too", array.contains("`kimi`"))
        assertTrue("stock providers were removed too", array.contains("`qiniuCloud`"))
        assertFalse("dangling commas", out.contains(",,"))
        assertFalse("leading comma", array.contains("[,"))
    }

    /** Repointing the route keeps /quick-start alive without pointing it at a sponsor. */
    @Test
    fun quickStartRouteSurvivesButChangesBrand() {
        val out = PanelSanitizer.strip(current)
        assertTrue("route was deleted instead of repointed", out.contains("`/quick-start`"))
        assertTrue(out.contains("fixedBrand:`openaiCompatibility`"))
    }

    /** Content without promotion must come back byte-identical. */
    @Test
    fun neutralDocumentIsUntouched() {
        val plain = "<html><body><div>Kimi and Gemini</div></body></html>"
        assertEquals(plain, PanelSanitizer.strip(plain))
    }
}