package me.routerfor.cpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text boxes are the only place a user types structured data, so the parsing rules are
 * pinned here: what they type has to reach the kernel as the model name and alias it was meant
 * to be, and typing back what the list row showed has to round-trip.
 */
class CustomProvidersTest {

    @Test
    fun aBareModelNameNeedsNoAlias() {
        val models = CustomProviders.parseModels("deepseek-chat")
        assertEquals(1, models.size)
        assertEquals("deepseek-chat", models[0].name)
        assertEquals("", models[0].alias)
        // The whole point of a blank alias: the client calls the model by its own name.
        assertEquals("deepseek-chat", models[0].effectiveAlias)
        assertEquals("deepseek-chat", models[0].label)
    }

    @Test
    fun equalsSignSeparatesNameFromAlias() {
        val models = CustomProviders.parseModels("deepseek-reasoner = r1")
        assertEquals("deepseek-reasoner", models[0].name)
        assertEquals("r1", models[0].alias)
        assertEquals("deepseek-reasoner  →  r1", models[0].label)
    }

    @Test
    fun theArrowTheListRowPrintsIsAcceptedBack() {
        // The row shows "name  →  alias"; someone copying it into the editor must not end up
        // with the arrow inside the model name.
        val arrow = CustomProviders.parseModels("moonshotai/kimi-k2  →  kimi-k2")
        assertEquals("moonshotai/kimi-k2", arrow[0].name)
        assertEquals("kimi-k2", arrow[0].alias)
    }

    @Test
    fun blankLinesAndSurroundingSpaceAreIgnored() {
        val models = CustomProviders.parseModels(
            "  \n deepseek-chat \n\n deepseek-reasoner = r1\n  \n"
        )
        assertEquals(2, models.size)
        assertEquals("deepseek-chat", models[0].name)
        assertEquals("r1", models[1].alias)
    }

    @Test
    fun modelsTextRoundTripsThroughParseModels() {
        val original = listOf(
            CustomModel("deepseek-chat", ""),
            CustomModel("deepseek-reasoner", "r1")
        )
        val text = CustomProviders.modelsText(original)
        assertEquals("deepseek-chat\ndeepseek-reasoner = r1", text)
        val parsed = CustomProviders.parseModels(text)
        assertEquals(original[0].name, parsed[0].name)
        assertEquals("", parsed[0].alias)
        assertEquals(original[1].alias, parsed[1].alias)
    }

    @Test
    fun aliasEqualToNameIsNotPrintedTwice() {
        // Writing "m = m" would show up in the editor as a distinction the user never made.
        assertEquals("m", CustomProviders.modelsText(listOf(CustomModel("m", "m"))))
    }

    @Test
    fun keysAreDeduplicatedInOrderAndBlanksDropped() {
        val keys = CustomProviders.parseKeys("  sk-b \n\n sk-a \n sk-b \n   \n")
        assertEquals(listOf("sk-b", "sk-a"), keys)
    }

    @Test
    fun summaryNamesWhatIsThere() {
        val provider = CustomProvider(
            name = "deepseek",
            baseUrl = "https://api.deepseek.com/v1",
            keys = listOf("sk-1"),
            models = listOf(CustomModel("deepseek-chat", ""), CustomModel("deepseek-reasoner", "r1"))
        )
        val summary = provider.summary()
        assertTrue(summary, summary.startsWith("https://api.deepseek.com/v1"))
        assertTrue(summary, summary.contains("2 个模型"))
        assertTrue(summary, summary.contains("1 个密钥"))
    }

    @Test
    fun aDisabledProviderSaysSo() {
        val provider = CustomProvider("x", "https://x/v1", models = listOf(CustomModel("m", "")))
        assertTrue(provider.copy(disabled = true).summary().contains("已停用"))
    }
}
