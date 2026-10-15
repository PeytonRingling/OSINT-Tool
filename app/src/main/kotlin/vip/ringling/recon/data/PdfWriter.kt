package vip.ringling.recon.data

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Minimal text-to-PDF: renders Markdown-ish lines (headings bold/larger) onto A4 pages. No network, no temp files. */
object PdfWriter {
    private const val W = 595; private const val H = 842; private const val M = 40

    fun fromMarkdown(md: String): ByteArray {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var y = M.toFloat(); var n = 0
        fun newPage() { page?.let(doc::finishPage); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++n).create()); y = M.toFloat() }
        newPage()
        for (line in md.lines()) {
            val (text, size, bold) = when {
                line.startsWith("### ") -> Triple(line.removePrefix("### "), 12f, true)
                line.startsWith("## ") -> Triple(line.removePrefix("## "), 15f, true)
                line.startsWith("# ") -> Triple(line.removePrefix("# "), 20f, true)
                else -> Triple(line.replace("**", "").removePrefix("> ").removePrefix("_Note:_ ").let { if (line.startsWith("_Note:_")) "Note: $it" else it }, 10f, false)
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; color = 0xFF111111.toInt() }
            val layout = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, W - 2 * M).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            if (y + layout.height > H - M) newPage()
            page!!.canvas.save(); page!!.canvas.translate(M.toFloat(), y); layout.draw(page!!.canvas); page!!.canvas.restore()
            y += layout.height + if (bold) 4f else 1f
        }
        page?.let(doc::finishPage)
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }
}
