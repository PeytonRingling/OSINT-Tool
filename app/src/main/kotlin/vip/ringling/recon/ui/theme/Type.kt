package vip.ringling.recon.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import vip.ringling.recon.R

/** The single display face. Everything else is the system sans. */
val DisplayFont = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private val Sans = FontFamily.SansSerif

private fun display(size: Int, weight: FontWeight, line: Int, tracking: Float) = TextStyle(
    fontFamily = DisplayFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
)

private fun sans(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
)

val ReconTypography = Typography(
    displayLarge = display(76, FontWeight.Bold, 80, -0.04f),
    displayMedium = display(56, FontWeight.Bold, 60, -0.035f),
    displaySmall = display(40, FontWeight.Bold, 44, -0.03f),
    headlineLarge = display(32, FontWeight.Medium, 38, -0.02f),
    headlineMedium = display(26, FontWeight.Medium, 32, -0.015f),
    headlineSmall = display(22, FontWeight.Medium, 28, -0.01f),
    titleLarge = display(20, FontWeight.Medium, 26, -0.005f),
    titleMedium = sans(16, FontWeight.Medium, 22, 0.005f),
    titleSmall = sans(14, FontWeight.Medium, 20, 0.005f),
    bodyLarge = sans(16, FontWeight.Normal, 24, 0.01f),
    bodyMedium = sans(14, FontWeight.Normal, 20, 0.01f),
    bodySmall = sans(12, FontWeight.Normal, 16, 0.02f),
    labelLarge = sans(14, FontWeight.Medium, 20, 0.01f),
    labelMedium = sans(12, FontWeight.Medium, 16, 0.04f),
    labelSmall = sans(11, FontWeight.Medium, 16, 0.05f),
)
