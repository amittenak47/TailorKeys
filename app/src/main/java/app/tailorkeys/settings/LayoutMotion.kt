package app.tailorkeys.settings

import app.tailorkeys.LayoutEntry

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal val LocalLayoutSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalLayoutVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
internal val LocalLayoutReducedMotion = staticCompositionLocalOf { false }
// Dynamic: flipping it on Try must only recompose the morphing cards, not the whole settings tree.
internal val LocalLayoutEntering = compositionLocalOf { true }
internal val LayoutEmphasis = CubicBezierEasing(.2f, 0f, 0f, 1f)

/** System animation scale zero must still allow the specified short, stationary cross-fade. */
@Composable
internal fun ReducedLayoutCrossfade(target: LayoutEntry?, content: @Composable (LayoutEntry?) -> Unit) {
    var previous by remember { mutableStateOf(target) }
    val alpha = remember { Animatable(1f) }
    var animatingTarget by remember { mutableStateOf(target) }
    val unscaled = remember { object : MotionDurationScale { override val scaleFactor = 1f } }
    LaunchedEffect(target) {
        if (previous != target) {
            withContext(unscaled) {
                alpha.snapTo(0f)
                animatingTarget = target
                alpha.animateTo(1f, tween(120))
            }
            previous = target
        }
    }
    Box(Modifier.fillMaxSize()) {
        val layers = if (previous == target) listOf(target) else listOf(previous, target)
        layers.forEach { entry -> key(entry != null) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val progress = if (animatingTarget == target) alpha.value else 0f
                this.alpha = if (previous == target) 1f else if (entry == target) progress else 1f - progress
            }) { content(entry) }
        } }
    }
}

/**
 * False during most of the page's enter transition, then true. Work that would compete with the morph for
 * frames (preference writes, the IME sliding in, big loads) waits for it. It starts at 65% of the morph: with
 * the emphasized easing the card has covered about 95% of its path by then, so a short hitch is hard to see
 * and the IME slide overlaps the morph's tail instead of starting after it.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun rememberLayoutSettled(): Boolean {
    val visibility = LocalLayoutVisibility.current
    val reduced = LocalLayoutReducedMotion.current
    var settled by remember { mutableStateOf(visibility == null && !reduced) }
    LaunchedEffect(visibility) {
        if (visibility != null) snapshotFlow {
            visibility.transition.run {
                !isRunning && currentState == EnterExitState.Visible ||
                    targetState == EnterExitState.Visible && totalDurationNanos > 0 && playTimeNanos >= totalDurationNanos * 13 / 20
            }
        }.first { it }
        // The reduced cross-fade runs unscaled for 120ms.
        else if (reduced) delay(120)
        settled = true
    }
    return settled
}

@Composable
internal fun Modifier.layoutMorph(key: String): Modifier {
    if (LocalLayoutReducedMotion.current) return this
    val shared = LocalLayoutSharedScope.current ?: return this
    val visibility = LocalLayoutVisibility.current ?: return this
    val duration = if (LocalLayoutEntering.current) 360 else 300
    return with(shared) {
        sharedBounds(rememberSharedContentState(key), visibility,
            boundsTransform = { _, _ -> tween(duration, easing = LayoutEmphasis) },
            enter = fadeIn(tween(190, delayMillis = if (duration == 360) 150 else 120)),
            exit = fadeOut(tween(if (duration == 360) 140 else 90)),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds())
    }
}
