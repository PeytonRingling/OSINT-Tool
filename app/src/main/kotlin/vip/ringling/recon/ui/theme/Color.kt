package vip.ringling.recon.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// "Volt" — acid lime on true black. Severity hues are kept apart from the accent on purpose.
private val Volt = Color(0xFFC8F751)
private val VoltDeep = Color(0xFF3D5200)

val ReconDarkScheme: ColorScheme = darkColorScheme(
    primary = Volt,
    onPrimary = Color(0xFF131A00),
    primaryContainer = Color(0xFF2B3C00),
    onPrimaryContainer = Color(0xFFE3FF8F),
    secondary = Color(0xFFB7C29A),
    onSecondary = Color(0xFF1B2208),
    secondaryContainer = Color(0xFF2A3118),
    onSecondaryContainer = Color(0xFFD3DFB4),
    tertiary = Color(0xFF6DB8FF),
    onTertiary = Color(0xFF00233F),
    tertiaryContainer = Color(0xFF0B3558),
    onTertiaryContainer = Color(0xFFD1E8FF),
    error = Color(0xFFFF7A66),
    onError = Color(0xFF3A0A02),
    errorContainer = Color(0xFF5A1A0F),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color.Black,
    onBackground = Color(0xFFECECE8),
    surface = Color.Black,
    onSurface = Color(0xFFECECE8),
    surfaceVariant = Color(0xFF1B1B20),
    onSurfaceVariant = Color(0xFFA8A8B0),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF26262C),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF111114),
    surfaceContainerHigh = Color(0xFF18181C),
    surfaceContainerHighest = Color(0xFF212127),
    outline = Color(0xFF8A8A93),
    outlineVariant = Color(0xFF2B2B32),
    inverseSurface = Color(0xFFECECE8),
    inverseOnSurface = Color(0xFF111114),
    inversePrimary = VoltDeep,
    scrim = Color.Black,
)

val ReconLightScheme: ColorScheme = lightColorScheme(
    primary = VoltDeep,
    onPrimary = Color.White,
    primaryContainer = Volt,
    onPrimaryContainer = Color(0xFF172000),
    secondary = Color(0xFF55603C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE6BB),
    onSecondaryContainer = Color(0xFF141F02),
    tertiary = Color(0xFF0A5DA8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E7FF),
    onTertiaryContainer = Color(0xFF001C38),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F4EE),
    onBackground = Color(0xFF16160F),
    surface = Color(0xFFF5F4EE),
    onSurface = Color(0xFF16160F),
    surfaceVariant = Color(0xFFE4E3D6),
    onSurfaceVariant = Color(0xFF4A4A40),
    surfaceDim = Color(0xFFDAD9CE),
    surfaceBright = Color(0xFFFBFAF5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF5),
    surfaceContainer = Color(0xFFEFEEE5),
    surfaceContainerHigh = Color(0xFFE9E8DE),
    surfaceContainerHighest = Color(0xFFE2E1D6),
    outline = Color(0xFF6E6E62),
    outlineVariant = Color(0xFFCFCEC1),
    inverseSurface = Color(0xFF2B2B24),
    inverseOnSurface = Color(0xFFF5F4EE),
    inversePrimary = Volt,
)

/** Semantic severity colors (not part of the Material scheme). */
@Immutable
data class SeverityColors(
    val clean: Color, val onClean: Color, val cleanContainer: Color,
    val info: Color, val onInfo: Color, val infoContainer: Color,
    val exposed: Color, val onExposed: Color, val exposedContainer: Color,
)

val DarkSeverity = SeverityColors(
    clean = Color(0xFF2EE6C5), onClean = Color(0xFF00241E), cleanContainer = Color(0xFF07312B),
    info = Color(0xFF6DB8FF), onInfo = Color(0xFF00233F), infoContainer = Color(0xFF0B3558),
    exposed = Color(0xFFFF7A66), onExposed = Color(0xFF3A0A02), exposedContainer = Color(0xFF5A1A0F),
)

val LightSeverity = SeverityColors(
    clean = Color(0xFF00695A), onClean = Color.White, cleanContainer = Color(0xFFBFF5E6),
    info = Color(0xFF0A5DA8), onInfo = Color.White, infoContainer = Color(0xFFD3E7FF),
    exposed = Color(0xFFB42318), onExposed = Color.White, exposedContainer = Color(0xFFFFDAD4),
)
