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

/**
 * Two fingers apart = +1 (bigger), together = -1 (smaller), one step per pinch, like in Google Photos.
 * While pinching the content follows the fingers; after it settles into the new size.
 * [onStep] returns how many times bigger the new layout is, or 1 when it is not comparable (then it fades in).
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
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    pinching = true
                    zoom = (zoom * event.calculateZoom()).coerceIn(0.5f, 2f)
                    val center = event.calculateCentroid()
                    pivot = TransformOrigin(center.x / size.width, center.y / size.height)
                    event.changes.forEach { it.consume() }
                    scope.launch { scale.snapTo(zoom) }
                }
            } while (event.changes.any { it.pressed })

            if (pinching) {
                val direction = if (zoom > STEP) 1 else if (zoom < 1 / STEP) -1 else 0
                scope.launch {
                    val bigger = if (direction != 0) step(direction) else 0f
                    when {
                        bigger == 1f -> {
                            scale.snapTo(1f)
                            fade.snapTo(0.2f)
                            fade.animateTo(1f)
                        }
                        bigger > 0f -> scale.snapTo(zoom / bigger)
                    }
                    scale.animateTo(1f, spring(stiffness = 400f))
                }
            }
        }
    }.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        transformOrigin = pivot
        alpha = fade.value
    }
}
