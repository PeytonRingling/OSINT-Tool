package vip.ringling.recon.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** "Recon // Signal" design tokens. */
@Immutable
class SignalColors(
    val ground: Color, val panel: Color, val hairline: Color, val text: Color, val muted: Color,
    val signal: Color, val onSignal: Color,
    val alert: Color, val alertText: Color, val info: Color, val infoText: Color, val safe: Color, val safeText: Color,
    /** Dim LED / disabled indicator. */
    val ledOff: Color,
    /** Hairline for inputs and secondary buttons. */
    val line: Color,
)

val SignalDark = SignalColors(
    ground = Color(0xFF050706), panel = Color(0xFF0B0F0C), hairline = Color(0xFFD2FF3F).copy(alpha = 0.14f),
    text = Color(0xFFEEF2E8), muted = Color(0xFF8E978B),
    signal = Color(0xFFD2FF3F), onSignal = Color(0xFF0A1000),
    alert = Color(0xFFFF5B3A), alertText = Color(0xFFFF8466),
    info = Color(0xFF5CC8FF), infoText = Color(0xFF7FD3FF),
    safe = Color(0xFF46F0B0), safeText = Color(0xFF5BF2BC),
    ledOff = Color(0xFF2C322B), line = Color.White.copy(alpha = 0.16f),
)

val SignalLight = SignalColors(
    ground = Color(0xFFF3F5EC), panel = Color(0xFFFFFFFF), hairline = Color(0xFF3E5200).copy(alpha = 0.18f),
    text = Color(0xFF111610), muted = Color(0xFF566053),
    signal = Color(0xFF3E5200), onSignal = Color(0xFFFFFFFF),
    alert = Color(0xFFC2331A), alertText = Color(0xFFB02A12),
    info = Color(0xFF0A6AA8), infoText = Color(0xFF075A8F),
    safe = Color(0xFF00805A), safeText = Color(0xFF00694A),
    ledOff = Color(0xFFC9CFC2), line = Color.Black.copy(alpha = 0.18f),
)

private fun scheme(s: SignalColors, dark: Boolean): ColorScheme {
    val sig = s.signal
    return if (dark) darkColorScheme(
        primary = sig, onPrimary = s.onSignal,
        primaryContainer = Color(0xFF1B2306), onPrimaryContainer = sig,
        secondary = s.muted, onSecondary = s.ground,
        secondaryContainer = Color(0xFF151A14), onSecondaryContainer = s.text,
        tertiary = s.info, onTertiary = Color(0xFF00233F),
        tertiaryContainer = s.info.copy(alpha = 0.18f).compositeOver(s.ground), onTertiaryContainer = s.infoText,
        error = s.alertText, onError = Color(0xFF1A0804),
        errorContainer = s.alert.copy(alpha = 0.18f).compositeOver(s.ground), onErrorContainer = s.alertText,
        background = s.ground, onBackground = s.text,
        surface = s.ground, onSurface = s.text,
        surfaceVariant = Color(0xFF141A15), onSurfaceVariant = s.muted,
        surfaceDim = s.ground, surfaceBright = Color(0xFF1A201B),
        surfaceContainerLowest = s.ground, surfaceContainerLow = Color(0xFF070A08),
        surfaceContainer = s.panel, surfaceContainerHigh = Color(0xFF111612), surfaceContainerHighest = Color(0xFF181E19),
        outline = s.muted, outlineVariant = Color(0xFF232B22),
        inverseSurface = s.text, inverseOnSurface = s.ground, inversePrimary = Color(0xFF3E5200), scrim = Color.Black,
    ) else lightColorScheme(
        primary = sig, onPrimary = s.onSignal,
        primaryContainer = Color(0xFFDCEB9C), onPrimaryContainer = Color(0xFF1B2600),
        secondary = s.muted, onSecondary = Color.White,
        secondaryContainer = Color(0xFFE5EAD9), onSecondaryContainer = s.text,
        tertiary = s.info, onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD3E9F7), onTertiaryContainer = s.infoText,
        error = s.alertText, onError = Color.White,
        errorContainer = Color(0xFFFFDAD3), onErrorContainer = s.alertText,
        background = s.ground, onBackground = s.text, surface = s.ground, onSurface = s.text,
        surfaceVariant = Color(0xFFE3E8D8), onSurfaceVariant = s.muted,
        surfaceDim = Color(0xFFD9DECF), surfaceBright = Color.White,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAF2),
        surfaceContainer = s.panel, surfaceContainerHigh = Color(0xFFEDF0E4), surfaceContainerHighest = Color(0xFFE5E9DA),
        outline = s.muted, outlineVariant = Color(0xFFCDD3C2),
        inverseSurface = Color(0xFF1E231C), inverseOnSurface = Color(0xFFF3F5EC), inversePrimary = Color(0xFFD2FF3F),
    )
}

val ReconDarkScheme: ColorScheme = scheme(SignalDark, true)
val ReconLightScheme: ColorScheme = scheme(SignalLight, false)

/** Semantic status colors (not part of the Material scheme). `x` = fill/dot, `onX`/`xText` = text on the tinted container. */
@Immutable
data class SeverityColors(
    val clean: Color, val onClean: Color, val cleanContainer: Color,
    val info: Color, val onInfo: Color, val infoContainer: Color,
    val exposed: Color, val onExposed: Color, val exposedContainer: Color,
    val cleanText: Color = clean, val infoText: Color = info, val exposedText: Color = exposed,
)

val DarkSeverity = SignalDark.let { s ->
    SeverityColors(
        clean = s.safe, onClean = Color(0xFF00241A), cleanContainer = s.safe.copy(alpha = 0.14f).compositeOver(s.ground),
        info = s.info, onInfo = Color(0xFF00233F), infoContainer = s.info.copy(alpha = 0.14f).compositeOver(s.ground),
        exposed = s.alert, onExposed = Color(0xFF1A0804), exposedContainer = s.alert.copy(alpha = 0.16f).compositeOver(s.ground),
        cleanText = s.safeText, infoText = s.infoText, exposedText = s.alertText,
    )
}

val LightSeverity = SignalLight.let { s ->
    SeverityColors(
        clean = s.safe, onClean = Color.White, cleanContainer = Color(0xFFD2F3E5),
        info = s.info, onInfo = Color.White, infoContainer = Color(0xFFD3E9F7),
        exposed = s.alert, onExposed = Color.White, exposedContainer = Color(0xFFFFDAD3),
        cleanText = s.safeText, infoText = s.infoText, exposedText = s.alertText,
    )
}
