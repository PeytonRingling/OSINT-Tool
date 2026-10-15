package vip.ringling.recon.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import vip.ringling.recon.R

/** Instrument Serif: page titles and large numerals (risk score, record counts). Regular + italic. */
val SerifFont = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int) = FontFamily(
    listOf(400, 500, 600, 700).map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

/** Geist: body. */
val BodyFont = variable(R.font.geist)

/** Geist Mono: labels, values and tags. */
val MonoFont = variable(R.font.geist_mono)

/** Kept for older call sites. */
val DisplayFont = SerifFont

private fun serif(size: Int, line: Int, italic: Boolean = false) = TextStyle(
    fontFamily = SerifFont, fontWeight = FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.01f).em,
)

private fun body(size: Int, weight: FontWeight, line: Int) = TextStyle(fontFamily = BodyFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private fun mono(size: Float, line: Int, tracking: Float, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = MonoFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

/** Material roles mapped onto the three faces: serif for display/headline, Geist for title/body, Geist Mono for labels. */
val ReconTypography = Typography(
    displayLarge = serif(104, 88), displayMedium = serif(72, 62), displaySmall = serif(52, 48),
    headlineLarge = serif(38, 42, italic = true), headlineMedium = serif(30, 34), headlineSmall = serif(26, 30),
    titleLarge = serif(24, 28), titleMedium = body(15, FontWeight.SemiBold, 20), titleSmall = body(13, FontWeight.SemiBold, 18),
    bodyLarge = body(15, FontWeight.Normal, 22), bodyMedium = body(14, FontWeight.Normal, 20), bodySmall = body(12, FontWeight.Normal, 17),
    labelLarge = mono(12f, 16, 0.08f, FontWeight.Medium), labelMedium = mono(10.5f, 14, 0.14f), labelSmall = mono(9.5f, 13, 0.12f),
)

/** Named text styles from the design spec. */
object ReconType {
    /** Mono label, uppercase by convention: 10.5sp with +0.14em tracking. */
    val label = mono(10.5f, 14, 0.14f)
    val labelSmall = mono(9.5f, 13, 0.12f)
    val value = mono(13f, 18, 0.0f)
    val tag = mono(10.5f, 14, 0.08f, FontWeight.Medium)
    val button = mono(11f, 14, 0.12f, FontWeight.Medium)
    val pageTitle = serif(60, 54, italic = true)
    val numeral = serif(104, 86)
}
