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

    /** Cover page content: a dark title page in the app's colours. [big] is the headline number (e.g. exposure) with [bigLabel] under it. */
    data class Cover(val kicker: String, val title: String, val subtitle: String, val big: String? = null, val bigLabel: String = "", val lines: List<String> = emptyList(), val footer: String = "")

    private fun drawCover(doc: PdfDocument, c: Cover): Int {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val cv = page.canvas
        cv.drawColor(0xFF050706.toInt())
        val volt = 0xFFD2FF3F.toInt(); val text = 0xFFEEF2E8.toInt(); val muted = 0xFF8E978B.toInt(); val coral = 0xFFFF5B3A.toInt()
        fun paint(size: Float, color: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face; letterSpacing = spacing }
        fun block(t: String, p: TextPaint, y: Float, w: Int = W - 2 * M - 20): Float {
            val l = StaticLayout.Builder.obtain(t, 0, t.length, p, w).build()
            cv.save(); cv.translate(M + 10f, y); l.draw(cv); cv.restore(); return y + l.height
        }
        val line = Paint().apply { color = volt; strokeWidth = 2f }
        cv.drawLine(M.toFloat(), M + 4f, M + 60f, M + 4f, line)
        cv.drawLine(M.toFloat(), M + 4f, M.toFloat(), M + 64f, line)
        var y = block(c.kicker.uppercase(), paint(10f, volt, Typeface.MONOSPACE, 0.18f), M + 90f)
        y = block(c.title, paint(34f, text, Typeface.SERIF), y + 14f)
        y = block(c.subtitle, paint(12f, muted), y + 8f)
        c.big?.let { big ->
            val bigColor = big.toIntOrNull()?.let { if (it >= 50) coral else volt } ?: volt
            y = block(big, paint(110f, bigColor, Typeface.SERIF), y + 50f, W)
            y = block(c.bigLabel.uppercase(), paint(10f, muted, Typeface.MONOSPACE, 0.18f), y + 2f)
        }
        y += 40f
        c.lines.forEach { y = block("· $it", paint(12f, text), y + 8f) }
        block(c.footer, paint(9f, muted), H - M - 20f)
        doc.finishPage(page)
        return 1
    }

    fun fromMarkdown(md: String, cover: Cover? = null): ByteArray {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var y = M.toFloat(); var n = if (cover != null) drawCover(doc, cover) else 0
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
