package vip.ringling.recon.data.sources.phone

import org.jsoup.Jsoup

data class CallerReport(val date: String?, val text: String, val callers: List<String>)

data class NotesResult(
    /** Pages of community comments (about 20 per page); 0 when nobody has reported the number. */
    val pages: Int,
    val reports: List<CallerReport>,
)

/** 800notes.com: a free, community-written caller-report database. Page 1 only; comments are unverified. */
object Notes800 {
    fun parse(html: String): NotesResult {
        val doc = Jsoup.parse(html)
        val reports = doc.select("li.oos_contlet").map { li ->
            CallerReport(
                date = li.selectFirst("time[datetime]")?.attr("datetime")?.take(10),
                text = li.selectFirst(".oos_contletBody")?.text().orEmpty().replace(Regex("\\s+"), " ").trim(),
                callers = li.select("ul.callDetails li").map { it.text().trim() }
                    .filter { it.startsWith("Caller:", ignoreCase = true) }.map { it.substringAfter(':').trim() }.filter { it.isNotEmpty() },
            )
        }.filter { it.text.isNotEmpty() }
        val pagerNumbers = doc.select(".oos_pager a, .oos_pager span").mapNotNull { it.ownText().trim().toIntOrNull() }
        return NotesResult(if (reports.isEmpty()) 0 else (pagerNumbers.maxOrNull() ?: 1), reports)
    }

    /** NANP numbers only (+1): 800notes' URL is 1-AAA-EEE-NNNN. */
    fun urlFor(base: String, e164: String): String? {
        val d = e164.filter(Char::isDigit)
        if (d.length != 11 || !d.startsWith("1")) return null
        return "$base/Phone.aspx/1-${d.substring(1, 4)}-${d.substring(4, 7)}-${d.substring(7)}"
    }
}
