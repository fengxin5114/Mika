package com.wangzan101.mika.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePlanTest {
    private data class Row(val id: String, val at: Long)

    private val old = listOf(Row("a", 1), Row("b", 2))
    private val imported = listOf(Row("b", 20), Row("c", 30))

    @Test
    fun byIdAddsReplacesOrKeeps() {
        val decisions = MergePlan.byId(imported, old, Row::id) { i, e -> i.at > e.at }
        assertEquals(
            listOf(
                MergeDecision(MergeAction.Replace, Row("b", 20), Row("b", 2)),
                MergeDecision(MergeAction.Add, Row("c", 30)),
            ),
            decisions,
        )
    }

    @Test
    fun byIdKeepsWhenNotNewer() {
        val decisions = MergePlan.byId(imported, old, Row::id) { i, e -> i.at > e.at }
        assertEquals(MergeAction.Replace, decisions[0].action)
        val reversed = MergePlan.byId(listOf(Row("b", 0)), old, Row::id) { i, e -> i.at > e.at }
        assertEquals(MergeAction.Keep, reversed.single().action)
    }

    @Test
    fun fillMissingOnlyAddsUnknown() {
        val decisions = MergePlan.fillMissing(imported, old + Row("c", 1), Row::id)
        assertTrue(decisions.all { it.action == MergeAction.Keep })
    }

    @Test
    fun missingKeysDoesNotOverwrite() {
        val merged = MergePlan.missingKeys(mapOf("a" to 1, "b" to 2), mapOf("a" to 9))
        assertEquals(mapOf("b" to 2), merged)
    }

    @Test
    fun protectiveSetRules() {
        assertEquals(setOf("a", "b", "c"), MergePlan.union(setOf("b", "c"), setOf("a", "b")))
        assertEquals(setOf("b"), MergePlan.intersect(setOf("b", "c"), setOf("a", "b")))
        assertTrue(!MergePlan.stricter(true, false))
        assertTrue(!MergePlan.stricter(false, true))
        assertTrue(MergePlan.stricter(true, true))
    }

    @Test
    fun reportAccumulatesSameSection() {
        val builder = ImportReportBuilder()
        builder.add("conversations", added = 2, kept = 1)
        builder.add("conversations", replaced = 3)
        builder.unavailable("provider:p1")
        builder.warn("x")
        val report = builder.build()
        assertEquals(5, report.section("conversations")!!.added + report.section("conversations")!!.replaced)
        assertEquals(listOf("provider:p1"), report.unavailableSecrets)
        assertEquals(listOf("x"), report.warnings)
    }
}
