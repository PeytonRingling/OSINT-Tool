package vip.ringling.recon.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** Shared spring specs. Bouncy for spatial moves, critically damped for fades/color. */
object ReconMotion {
    fun <T> spatial() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    fun <T> snappy() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    fun <T> effects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    fun <T> ring() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)
}
