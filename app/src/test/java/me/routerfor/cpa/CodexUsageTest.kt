package me.routerfor.cpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex window classification.
 *
 * The bug these cases pin down: the app used to call primary "主额度" and secondary "次额度" and
 * draw whichever of the two the account returned. Codex moves its budgets between those two
 * slots depending on the plan, so a Pro account - which has a weekly window and no 5-hour one -
 * showed a single row labelled as a 5-hour budget. The fix keys off limit_window_seconds, which
 * is what the management panel does; these tests state that mapping in the form of payloads.
 */
class CodexUsageTest {

    private fun windows(json: String): List<Quota.Window> = Quota.CodexUsage.parse(json).windows

    /** A Pro-style account: one weekly window in the primary slot, no 5-hour budget at all. */
    @Test
    fun weeklyOnlyAccountGetsOneWeeklyRow() {
        val body = """
            {"rate_limit":{"allowed":true,"limit_reached":false,
              "primary_window":{"used_percent":42.5,"limit_window_seconds":604800,
                                "reset_after_seconds":360000},
              "secondary_window":null}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals("exactly one window should be drawn", 1, rows.size)
        assertEquals("每周额度", rows[0].label)
        assertEquals(42.5, rows[0].usedPercent!!, 0.001)
    }

    /** A Plus/team-style account: both budgets, ordered as the panel orders them. */
    @Test
    fun fiveHourAndWeeklyAreLabelledBySecondsNotBySlot() {
        val body = """
            {"rate_limit":{
              "primary_window":{"used_percent":10,"limit_window_seconds":18000,"reset_after_seconds":9000},
              "secondary_window":{"used_percent":60,"limit_window_seconds":604800,
                                  "reset_after_seconds":300000}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(2, rows.size)
        assertEquals("5 小时额度", rows[0].label)
        assertEquals(10.0, rows[0].usedPercent!!, 0.001)
        assertEquals("每周额度", rows[1].label)
        assertEquals(60.0, rows[1].usedPercent!!, 0.001)
    }

    /**
     * The slots are not fixed: Codex has been seen putting the weekly window first. The label has
     * to follow the seconds value, otherwise the weekly budget gets reported as a 5-hour one.
     */
    @Test
    fun labelFollowsSecondsWhenTheOrderIsSwapped() {
        val body = """
            {"rate_limit":{
              "primary_window":{"used_percent":88,"limit_window_seconds":604800},
              "secondary_window":{"used_percent":5,"limit_window_seconds":18000}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(2, rows.size)
        assertEquals("5 小时额度", rows[0].label)
        assertEquals(5.0, rows[0].usedPercent!!, 0.001)
        assertEquals("每周额度", rows[1].label)
        assertEquals(88.0, rows[1].usedPercent!!, 0.001)
    }

    /** Team plans report a ~30-day window, which must be called monthly rather than weekly. */
    @Test
    fun monthLongWindowIsLabelledMonthly() {
        val body = """
            {"rate_limit":{
              "primary_window":{"used_percent":20,"limit_window_seconds":2592000}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(1, rows.size)
        assertEquals("每月额度", rows[0].label)
    }

    /**
     * Without any seconds at all the panel falls back to window order; so does this. The point of
     * the fallback is that it only runs when nothing identified itself - a payload that names one
     * window's duration must not have its other window guessed into the empty slot.
     */
    @Test
    fun orderFallbackOnlyAppliesWhenNoWindowIdentifiesItself() {
        val body = """
            {"rate_limit":{
              "primary_window":{"used_percent":33},
              "secondary_window":{"used_percent":77}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(2, rows.size)
        assertEquals("5 小时额度", rows[0].label)
        assertEquals("每周额度", rows[1].label)
    }

    @Test
    fun aSingleIdentifiedWindowDoesNotDragTheOtherIntoTheFiveHourSlot() {
        val body = """
            {"rate_limit":{
              "primary_window":{"used_percent":33,"limit_window_seconds":604800},
              "secondary_window":{"used_percent":77}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(1, rows.size)
        assertEquals("每周额度", rows[0].label)
    }

    /**
     * limit_reached is the only exhaustion signal on payloads without a percentage. Showing the
     * row at 100% is better than dropping it: a silently missing row reads as "no limit".
     */
    @Test
    fun limitReachedShowsAsFullWhenNoPercentageIsGiven() {
        val body = """
            {"rate_limit":{"limit_reached":true,
              "primary_window":{"limit_window_seconds":18000}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(1, rows.size)
        assertEquals(100.0, rows[0].usedPercent!!, 0.001)
    }

    /** Extra per-feature budgets are named, so they cannot be mistaken for the main budget. */
    @Test
    fun additionalFeatureBudgetsAreNamed() {
        val body = """
            {"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":604800}},
             "additional_rate_limits":[
               {"limit_name":"GPT-5 Codex","metered_feature":"codex",
                "rate_limit":{"primary_window":{"used_percent":90,"limit_window_seconds":18000}}}]}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(2, rows.size)
        assertEquals("每周额度", rows[0].label)
        assertEquals("GPT-5 Codex 5 小时额度", rows[1].label)
        assertEquals(90.0, rows[1].usedPercent!!, 0.001)
    }

    /** Code review has its own budget on some plans and must be labelled as such. */
    @Test
    fun codeReviewBudgetIsLabelled() {
        val body = """
            {"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":604800}},
             "code_review_rate_limit":{"primary_window":{"used_percent":55,"limit_window_seconds":18000}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(2, rows.size)
        assertEquals("代码审查 5 小时额度", rows[1].label)
    }

    /** An authenticated but unentitled account answers with nothing usable; that is not an error. */
    @Test
    fun emptyPayloadYieldsNoWindowsAndNoError() {
        val result = Quota.CodexUsage.parse("{}")
        assertTrue(result.windows.isEmpty())
        assertNull(result.error)
    }

    /** A reset countdown identifies the window when the explicit seconds field is missing. */
    @Test
    fun resetCountdownIdentifiesTheWindowOnOldPayloads() {
        val body = """
            {"rate_limit":{"primary_window":{"used_percent":50,"resets_in_seconds":1200}}}
        """.trimIndent()
        val rows = windows(body)
        assertEquals(1, rows.size)
        assertEquals("5 小时额度", rows[0].label)
    }
}
