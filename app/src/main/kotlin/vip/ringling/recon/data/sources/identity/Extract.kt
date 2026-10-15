package vip.ringling.recon.data.sources.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Data-driven profile enrichment for a site hit. kind=json: paths are dot-separated keys / array indexes ("0.name", "data.user.bio";
 * `*` over an array joins every element's remaining path). kind=xml: paths are CSS selectors into the XML document.
 * kind=meta: paths are meta property/name values (og:title, description...). Transforms after `|`: date (first 10 chars), num, host.
 */
@Serializable
data class Extract(
    val kind: String = "json",
    /** Label -> path. Well-known labels (Name, Location, Website, Email, Bio) are correlated across sites. */
    val facts: Map<String, String> = emptyMap(),
    val avatar: String? = null,
    /** Paths whose values are handles on OTHER services (to pivot into a username search). */
    val handles: List<String> = emptyList(),
)

data class Extracted(val facts: List<Pair<String, String>>, val avatar: String?, val handles: List<String>)

object Extractor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extract(spec: Extract, body: String): Extracted? = runCatching {
        val get: (String) -> List<String> = when (spec.kind) {
            "json" -> { val root = json.parseToJsonElement(body); { p -> jsonValues(root, p) } }
            "xml" -> { val doc = Jsoup.parse(body, "", Parser.xmlParser()); { p -> xmlValues(doc, p) } }
            "meta" -> { val doc = Jsoup.parse(body); { p -> metaValues(doc, p) } }
            else -> return null
        }
        val facts = spec.facts.flatMap { (label, path) -> get(path).map { label to it } }
        Extracted(
            facts.filter { it.second.isNotBlank() },
            spec.avatar?.let { get(it).firstOrNull() },
            spec.handles.flatMap(get).map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() },
        )
    }.getOrNull()

    private fun transform(v: String, t: String?): String = when (t) {
        "date" -> v.take(10)
        "num" -> v.substringBefore('.')
        "host" -> v.removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
        else -> v
    }.trim()

    private fun jsonValues(root: JsonElement, path: String): List<String> {
        val (p, t) = path.substringBefore('|') to path.substringAfter('|', "").ifEmpty { null }
        var cur = listOf(root)
        for (seg in p.split('.').filter { it.isNotEmpty() }) {
            cur = cur.flatMap { e ->
                when {
                    seg == "*" -> (e as? JsonArray)?.toList() ?: (e as? JsonObject)?.values?.toList().orEmpty()
                    e is JsonObject -> listOfNotNull(e[seg])
                    e is JsonArray -> listOfNotNull(seg.toIntOrNull()?.let { e.getOrNull(it) })
                    else -> emptyList()
                }
            }
        }
        return cur.mapNotNull { e ->
            when (e) {
                is JsonNull -> null
                is JsonPrimitive -> e.content
                else -> null
            }
        }.map { transform(it, t) }.filter { it.isNotEmpty() && it != "null" }
    }

    private fun xmlValues(doc: org.jsoup.nodes.Document, path: String): List<String> {
        val (p, t) = path.substringBefore('|') to path.substringAfter('|', "").ifEmpty { null }
        return doc.select(p).map { it.text() }.filter { it.isNotBlank() }.map { transform(it, t) }
    }

    private fun metaValues(doc: org.jsoup.nodes.Document, path: String): List<String> {
        val (p, t) = path.substringBefore('|') to path.substringAfter('|', "").ifEmpty { null }
        return doc.select("meta[property=$p], meta[name=$p]").map { it.attr("content") }.filter { it.isNotBlank() }.map { transform(it, t) }
    }
}
