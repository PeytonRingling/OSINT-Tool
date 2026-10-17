package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityGraphTest {
    private fun f(id: String, m: ModuleId, vararg q: String) = Finding(id, m, id, "d", Severity.INFO, pivots = q.map { Pivot(it, it) })

    @Test fun buildsTargetToModuleToLeadsAndNeverInventsEdges() {
        val g = EntityGraph.build("a@b.com", listOf(
            f("1", ModuleId.IDENTITY, "alice", "alice.dev"), f("2", ModuleId.CODE, "alice", "a@b.com"), f("3", ModuleId.DORKS, "ignored"), f("4", ModuleId.BREACH),
        ))
        assertEquals(2, g.entityCount)                       // alice shared by two modules, the target itself and dorks are excluded
        assertEquals(setOf("m:IDENTITY", "m:CODE"), g.nodes.filter { it.kind == EntityNode.Kind.MODULE }.map { it.id }.toSet())
        assertEquals(2, g.edges.count { it.to == "e:alice" })  // one edge per module that reported it
        assertTrue(g.edges.none { it.to == "e:ignored" })
    }

    @Test fun layoutPlacesEveryNodeAndRingsGrowOutward() {
        val g = EntityGraph.build("x", listOf(f("1", ModuleId.IDENTITY, "a", "b", "c"), f("2", ModuleId.CODE, "d")))
        val pos = EntityGraph.layout(g)
        assertEquals(g.nodes.size, pos.size)
        fun r(id: String) = pos.getValue(id).let { kotlin.math.hypot(it.first, it.second) }
        assertTrue(r("e:a") > r("m:IDENTITY") && r("m:IDENTITY") > r(EntityGraph.ROOT_ID))
    }

    @Test fun capsLeadCount() {
        val g = EntityGraph.build("x", listOf(f("1", ModuleId.IDENTITY, *Array(100) { "u$it" })))
        assertEquals(40, g.entityCount)
    }
}
