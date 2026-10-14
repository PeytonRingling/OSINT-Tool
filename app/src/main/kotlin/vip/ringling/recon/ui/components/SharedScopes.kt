package vip.ringling.recon.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

@OptIn(ExperimentalSharedTransitionApi::class)
class SharedScopes(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

/** Null on large screens (list-detail panes) and in screenshot tests, where no shared element is needed. */
val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

/** Container that morphs from a list row into the detail header. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedFinding(id: String): Modifier {
    val s = LocalSharedScopes.current ?: return this
    return with(s.shared) {
        this@sharedFinding.sharedBounds(
            sharedContentState = rememberSharedContentState("finding-$id"),
            animatedVisibilityScope = s.visibility,
        )
    }
}
