package vip.ringling.recon.domain

data class CaseTarget(val type: InputType, val value: String, val addedAt: Long) {
    val key: String get() = "${type.name}:$value"
}

data class CaseNote(val id: Long, val text: String, val ts: Long)

data class CaseFile(val id: String, val name: String, val createdAt: Long, val number: Int, val targets: List<CaseTarget>, val notes: List<CaseNote>, val tags: List<String> = emptyList())

/** A case target together with its newest scan (null until it has been scanned). */
data class CaseTargetState(val target: CaseTarget, val latest: ScanSnapshot?, val scans: List<ScanSnapshot>) {
    val risk: Int? get() = latest?.takeIf { !it.isArea }?.risk
    val records: Int? get() = latest?.records
    val isArea: Boolean get() = target.type == InputType.ZIP
}

enum class CaseStatus(val label: String) { CRITICAL("Critical"), MODERATE("Moderate"), CLEAN("Clean"), AREA("Area scan"), UNSCANNED("Not scanned") }

enum class EdgeKind(val label: String) { SAME_HANDLE("SAME HANDLE"), SAME_DOMAIN("SAME DOMAIN"), NONE("NO LINK YET") }

data class CaseEdge(val a: Int, val b: Int, val kind: EdgeKind)

data class CasePivot(val query: String, val title: String, val reason: String)

/** Derived views of a case. Pure functions of the stored scans and targets (nothing is inferred beyond what the data shows). */
object CaseLogic {
    /** Highest risk among the scored targets; areas have no score. Null when nothing scored has been scanned. */
    fun exposure(states: List<CaseTargetState>): Int? = states.mapNotNull { it.risk }.maxOrNull()

    fun status(states: List<CaseTargetState>): CaseStatus {
        if (states.isNotEmpty() && states.all { it.isArea }) return CaseStatus.AREA
        val e = exposure(states) ?: return CaseStatus.UNSCANNED
        return when { e >= 50 -> CaseStatus.CRITICAL; e >= 14 -> CaseStatus.MODERATE; else -> CaseStatus.CLEAN }
    }

    fun exposedTotal(states: List<CaseTargetState>): Int = states.filter { !it.isArea }.sumOf { it.latest?.exposed ?: 0 }

    private fun handleOf(t: CaseTarget): String? = when (t.type) {
        InputType.EMAIL -> t.value.substringBefore('@').substringBefore('+').lowercase().takeIf { it.length >= 3 }
        InputType.USERNAME -> t.value.removePrefix("@").lowercase().takeIf { it.length >= 3 }
        else -> null
    }

    private fun domainOf(t: CaseTarget): String? = when (t.type) {
        InputType.EMAIL -> t.value.substringAfter('@', "").lowercase().takeIf { it.isNotEmpty() }
        InputType.DOMAIN -> t.value.lowercase()
        else -> null
    }

    /** Auto-edges: a username equal to an email's local part (or two such emails) is a SAME HANDLE; an email under a domain target is SAME DOMAIN. */
    fun edges(targets: List<CaseTarget>): List<CaseEdge> {
        val linked = mutableListOf<CaseEdge>()
        for (i in targets.indices) for (j in i + 1 until targets.size) {
            val a = targets[i]; val b = targets[j]
            val ha = handleOf(a); val hb = handleOf(b)
            val da = domainOf(a); val db = domainOf(b)
            when {
                ha != null && ha == hb -> linked += CaseEdge(i, j, EdgeKind.SAME_HANDLE)
                da != null && da == db && (a.type == InputType.DOMAIN) != (b.type == InputType.DOMAIN) -> linked += CaseEdge(i, j, EdgeKind.SAME_DOMAIN)
            }
        }
        // Targets with no link at all get a dashed "no link yet" edge to the first target.
        val connected = linked.flatMap { listOf(it.a, it.b) }.toSet()
        val loose = targets.indices.filter { it != 0 && it !in connected }
        return linked + loose.map { CaseEdge(0, it, EdgeKind.NONE) }
    }

    /**
     * Suggested next scans: pivots found in the targets' latest findings, plus the handle of an email when no username target matches it.
     * Anything already in the case is skipped.
     */
    fun pivots(targets: List<CaseTarget>, findingsByTarget: Map<String, List<Finding>>, limit: Int = 8): List<CasePivot> {
        val have = targets.map { it.value.lowercase() }.toSet()
        val out = linkedMapOf<String, CasePivot>()
        targets.filter { it.type == InputType.EMAIL }.forEach { t ->
            val h = handleOf(t)
            if (h != null && targets.none { it.type == InputType.USERNAME && handleOf(it) == h } && h !in have) {
                out.putIfAbsent(h.lowercase(), CasePivot(h, "Handle matches the email name", "Search \"$h\" across more sites."))
            }
        }
        targets.forEach { t ->
            findingsByTarget[t.key].orEmpty().forEach { f ->
                f.pivots.forEach { p ->
                    val q = p.query.trim()
                    if (q.isNotEmpty() && q.lowercase() !in have) out.putIfAbsent(q.lowercase(), CasePivot(q, p.label, "Found in ${f.module.label} for ${t.value}."))
                }
            }
        }
        return out.values.take(limit)
    }
}

/** Free-text search over a case: name, tags, target values and note text (case-insensitive, every word must match somewhere). */
object CaseSearch {
    fun matches(c: CaseFile, query: String): Boolean {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = buildString {
            append(c.name.lowercase()).append(' '); c.tags.forEach { append(it).append(' ') }
            c.targets.forEach { append(it.value.lowercase()).append(' ') }; c.notes.forEach { append(it.text.lowercase()).append(' ') }
        }
        return words.all { it in hay }
    }
}
