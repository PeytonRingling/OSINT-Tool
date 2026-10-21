package vip.ringling.recon.domain

import kotlin.math.cos
import kotlin.math.sin

/** A point on the entity graph. ROOT is the searched target, MODULE a source that produced leads, ENTITY a lead (handle, domain, e-mail, ...) that can be searched next. */
data class EntityNode(val id: String, val label: String, val kind: Kind, val query: String? = null, val parent: String? = null, val type: InputType? = null, val sources: List<String> = emptyList(), val hot: Boolean = false) {
    enum class Kind { ROOT, MODULE, ENTITY }
}

/** Edge between two nodes; entities found by more than one module get one edge per module. */
data class EntityEdge(val from: String, val to: String)

data class EntityGraphModel(val nodes: List<EntityNode>, val edges: List<EntityEdge>, /** Id of the lead that most connects this target to its exposure (see [EntityGraph.build]), if any. */ val weakest: String? = null) {
    fun reason(id: String): String? = nodes.firstOrNull { it.id == id && it.kind == EntityNode.Kind.ENTITY }?.let { n ->
        listOfNotNull("Listed by ${n.sources.joinToString(", ")}", if (n.hot) "one of those sources flagged it as exposed" else null).joinToString(" · ")
    }

    val entityCount: Int get() = nodes.count { it.kind == EntityNode.Kind.ENTITY }
}

/**
 * Builds the lead graph from what the findings themselves report (their `pivots`): target -> modules -> leads. Nothing is inferred; a lead
 * appears under every module that listed it. Pure, so it is unit-tested.
 */
object EntityGraph {
    const val ROOT_ID = "root"
    private const val MAX_ENTITIES = 40

    fun build(target: String, findings: List<Finding>): EntityGraphModel {
        val nodes = mutableListOf(EntityNode(ROOT_ID, target, EntityNode.Kind.ROOT, type = null))
        val edges = mutableListOf<EntityEdge>()
        val seen = linkedMapOf<String, EntityNode>()
        val moduleOrder = linkedSetOf<ModuleId>()
        val perModule = linkedMapOf<ModuleId, MutableList<String>>()
        val sourcesOf = mutableMapOf<String, LinkedHashSet<String>>(); val hotOf = mutableSetOf<String>()
        findings.filter { it.module != ModuleId.DORKS }.forEach { f ->
            f.pivots.forEach { p ->
                val key = p.query.trim().lowercase()
                if (key.isEmpty() || key == target.trim().lowercase() || (seen.size >= MAX_ENTITIES && key !in seen)) return@forEach
                if (key !in seen) seen[key] = EntityNode("e:$key", p.label.take(40), EntityNode.Kind.ENTITY, p.query.trim(), parent = "m:${f.module.name}", type = InputDetector.detect(p.query.trim()))
                moduleOrder += f.module
                sourcesOf.getOrPut(key) { linkedSetOf() } += Exposure.label(f.module)
                if (f.severity == Severity.EXPOSED) hotOf += key
                val list = perModule.getOrPut(f.module) { mutableListOf() }
                if (key !in list) list += key
            }
        }
        moduleOrder.forEach { m -> nodes += EntityNode("m:${m.name}", Exposure.label(m), EntityNode.Kind.MODULE, parent = ROOT_ID); edges += EntityEdge(ROOT_ID, "m:${m.name}") }
        val enriched = seen.map { (k, n) -> n.copy(sources = sourcesOf[k].orEmpty().toList(), hot = k in hotOf) }
        enriched.forEach { nodes += it }
        // Weakest link: the lead reached through an exposed finding and listed by the most sources (ties keep discovery order).
        val weakest = enriched.filter { it.hot || it.sources.size >= 2 }.maxWithOrNull(compareBy<EntityNode> { if (it.hot) 1 else 0 }.thenBy { it.sources.size })?.id
        perModule.forEach { (m, keys) -> keys.forEach { k -> edges += EntityEdge("m:${m.name}", "e:$k") } }
        return EntityGraphModel(nodes, edges, weakest)
    }

    /** Positions in unit space (root at 0,0, modules on a ring of radius 1, each module's leads fanned out beyond it). */
    fun layout(model: EntityGraphModel): Map<String, Pair<Float, Float>> {
        val pos = mutableMapOf(ROOT_ID to (0f to 0f))
        val modules = model.nodes.filter { it.kind == EntityNode.Kind.MODULE }
        val angleOf = mutableMapOf<String, Double>()
        modules.forEachIndexed { i, m ->
            val a = Math.toRadians(-90.0 + i * 360.0 / modules.size)
            angleOf[m.id] = a; pos[m.id] = cos(a).toFloat() to sin(a).toFloat()
        }
        val children = model.nodes.filter { it.kind == EntityNode.Kind.ENTITY }.groupBy { it.parent }
        children.forEach { (parent, list) ->
            val base = angleOf[parent] ?: 0.0
            val span = minOf(2 * Math.PI / maxOf(modules.size, 1) * 0.9, 1.5)
            list.forEachIndexed { k, e ->
                val a = if (list.size == 1) base else base - span / 2 + span * k / (list.size - 1)
                val r = 1.9f + (k % 2) * 0.45f
                pos[e.id] = (r * cos(a)).toFloat() to (r * sin(a)).toFloat()
            }
        }
        return pos
    }
}
