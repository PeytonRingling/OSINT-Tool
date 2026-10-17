package vip.ringling.recon.data.sources.dorks

import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** Ready-made search operators ("dorks") for the input. Pure link generation: nothing is requested until the user taps one. */
class DorkSource @Inject constructor() : OsintSource {
    override val id = ModuleId.DORKS
    override val supportedInputTypes = setOf(InputType.EMAIL, InputType.USERNAME, InputType.PHONE, InputType.DOMAIN, InputType.IP, InputType.WALLET)

    override suspend fun run(input: ScanInput): Flow<Finding> = flowOf(listOfNotNull(Dorks.finding(input), Variants.finding(input))).flatMapConcat { it.asFlow() }
}

object Dorks {
    private fun google(q: String) = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    private fun bing(q: String) = "https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    private fun ddg(q: String) = "https://duckduckgo.com/?q=" + URLEncoder.encode(q, "UTF-8")

    fun queries(input: ScanInput): List<String> {
        val r = input.raw
        return when (input.type) {
            InputType.EMAIL -> {
                val local = r.substringBefore('@'); val dom = r.substringAfter('@')
                listOf("\"$r\"", "\"$r\" -site:$dom", "site:pastebin.com \"$r\"", "site:github.com \"$r\"", "site:linkedin.com \"$local\" \"$dom\"", "\"$r\" filetype:pdf OR filetype:xls OR filetype:doc",
                    "intext:\"$r\" (resume OR cv OR contact)", "\"$local\" site:gravatar.com", "\"$r\" (leak OR dump OR breach)")
            }
            InputType.USERNAME -> listOf("\"$r\"", "inurl:$r", "intitle:\"$r\"", "site:reddit.com \"$r\"", "site:twitter.com OR site:x.com \"$r\"", "site:github.com \"$r\"", "site:pastebin.com \"$r\"", "\"$r\" (forum OR profile OR member)", "site:youtube.com \"$r\"")
            InputType.PHONE -> {
                val d = r.filter(Char::isDigit)
                val v = buildList {
                    add("\"$r\""); add("\"+$d\"")
                    if (d.length == 11 && d.startsWith("1")) { val n = d.drop(1); add("\"(${n.take(3)}) ${n.substring(3, 6)}-${n.drop(6)}\""); add("\"${n.take(3)}-${n.substring(3, 6)}-${n.drop(6)}\""); add("\"${n.take(3)}.${n.substring(3, 6)}.${n.drop(6)}\"") }
                    else if (d.length == 10) { add("\"(${d.take(3)}) ${d.substring(3, 6)}-${d.drop(6)}\""); add("\"${d.take(3)}-${d.substring(3, 6)}-${d.drop(6)}\""); add("\"${d.take(3)}.${d.substring(3, 6)}.${d.drop(6)}\"") }
                }
                v + listOf("${v.first()} (scam OR spam OR \"who called\")", "${v.first()} site:facebook.com", "${v.first()} (craigslist OR offerup OR marketplace)")
            }
            InputType.DOMAIN -> listOf("site:$r", "site:*.$r -www", "site:$r filetype:pdf", "site:$r (filetype:xls OR filetype:xlsx OR filetype:doc OR filetype:docx)", "site:$r intitle:\"index of\"", "site:$r inurl:admin OR inurl:login",
                "\"@$r\" -site:$r", "site:pastebin.com \"$r\"", "site:github.com \"$r\"", "site:$r ext:env OR ext:sql OR ext:log OR ext:bak", "inurl:$r -site:$r")
            InputType.IP -> listOf("\"$r\"", "\"$r\" (abuse OR spam OR attack OR scan)", "site:pastebin.com \"$r\"", "ip:$r", "\"$r\" site:github.com")
            InputType.WALLET -> listOf("\"$r\"", "\"$r\" (scam OR fraud OR ransom OR phishing)", "site:reddit.com \"$r\"", "site:bitcointalk.org \"$r\"", "site:github.com \"$r\"", "site:pastebin.com \"$r\"")
            else -> emptyList()
        }
    }

    fun finding(input: ScanInput): Finding {
        val qs = queries(input)
        return Finding(
            "DORKS-all", ModuleId.DORKS, "${qs.size} search dorks", "Operator searches tuned to this ${input.type.label.lowercase()}. Tap to open in your browser; nothing is sent until you do.",
            Severity.INFO, confidence = Confidence.HIGH,
            links = qs.map { Link(it, google(it)) } + listOfNotNull(qs.firstOrNull()?.let { Link("Same search in Bing", bing(it)) }, qs.firstOrNull()?.let { Link("Same search in DuckDuckGo", ddg(it)) }),
        )
    }
}

/** Common ways one person varies a handle across sites. Guesses to search next, never matches. Pure and offline. */
object Variants {
    private val seps = listOf('.', '_', '-')

    fun handle(raw: String, max: Int = 10): List<String> {
        val h = raw.lowercase().trim()
        if (h.length < 3) return emptyList()
        val out = LinkedHashSet<String>()
        val present = seps.filter { it in h }
        out += h.filter { it !in seps }                                         // jane.doe -> janedoe
        present.forEach { p -> seps.filter { it != p }.forEach { q -> out += h.replace(p, q) } } // jane.doe -> jane_doe, jane-doe
        val digits = h.takeLastWhile { it.isDigit() }
        if (digits.isNotEmpty() && digits.length < h.length) out += h.dropLast(digits.length) else { out += h + "1"; out += h + "123" }
        out += "the$h".takeIf { h.length <= 12 }.orEmpty()
        out += "real$h".takeIf { h.length <= 12 }.orEmpty()
        return out.filter { it.length >= 3 && it != h }.take(max)
    }

    fun finding(input: ScanInput): Finding? {
        val variants = when (input.type) {
            InputType.USERNAME -> handle(input.raw)
            InputType.EMAIL -> handle(input.raw.substringBefore('@')).map { it + "@" + input.raw.substringAfter('@') }
            else -> return null
        }
        if (variants.isEmpty()) return null
        return Finding(
            "DORKS-variants", ModuleId.DORKS, "${variants.size} handle variants",
            "Ways the same person might spell this ${if (input.type == InputType.EMAIL) "address" else "handle"} elsewhere. These are guesses, not matches: tap one to search it.",
            Severity.INFO, confidence = Confidence.LOW, pivots = variants.map { Pivot(it, it) },
        )
    }
}
