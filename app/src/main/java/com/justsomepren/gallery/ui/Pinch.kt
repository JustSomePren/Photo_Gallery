package com.justsomepren.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

private const val STEP = 1.3f

/** Column counts the grid steps through, from one big photo to a dense overview. */
val GRID_COLUMNS = listOf(1, 2, 3, 4, 5, 6, 8, 10, 14)

/** [bigger] steps towards bigger photos (fewer columns), negative towards smaller ones. */
fun nextColumns(current: Int, bigger: Int): Int {
    val index = GRID_COLUMNS.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: GRID_COLUMNS.lastIndex
    return GRID_COLUMNS[(index - bigger).coerceIn(0, GRID_COLUMNS.lastIndex)]
}

/**
 * Two fingers apart = bigger, together = smaller, like in Google Photos. The content follows the
 * fingers and changes size whenever the pinch goes far enough, so one long pinch can pass several sizes.
 * [onStep] gets +1 or -1 and returns how many times bigger the new layout is: 0 when there is no further
 * size, 1 when the layout is of another kind (then it fades in and that pinch ends there).
 * One finger keeps scrolling and selecting as before.
 */
fun Modifier.pinchToZoom(onStep: (Int) -> Float): Modifier = composed {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val fade = remember { Animatable(1f) }
    var pivot by remember { mutableStateOf(TransformOrigin.Center) }

    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var pinching = false
            var done = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    pinching = true
                    zoom = (zoom * event.calculateZoom()).coerceIn(0.6f, 1.7f)
                    val center = event.calculateCentroid()
                    pivot = TransformOrigin(center.x / size.width, center.y / size.height)
                    event.changes.forEach { it.consume() }
                    if (!done && (zoom > STEP || zoom < 1 / STEP)) {
                        val bigger = step(if (zoom > 1) 1 else -1)
                        when (bigger) {
                            0f -> {}
                            1f -> {
                                done = true
                                zoom = 1f
                                scope.launch {
                                    fade.snapTo(0.2f)
                                    fade.animateTo(1f)
                                }
                            }
                            else -> zoom /= bigger
                        }
                    }
                    scope.launch { scale.snapTo(if (done) 1f else zoom) }
                }
            } while (event.changes.any { it.pressed })

            if (pinching) scope.launch { scale.animateTo(1f, spring(stiffness = 400f)) }
        }
    }.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        transformOrigin = pivot
        alpha = fade.value
    }
}
