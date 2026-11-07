package com.justsomepren.gallery.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

private const val STEP = 1.3f

/**
 * Two fingers apart = +1 (bigger), together = -1 (smaller), like in Google Photos.
 * One finger keeps scrolling and selecting as before.
 */
fun Modifier.pinchToZoom(onStep: (Int) -> Unit): Modifier = composed {
    val step by rememberUpdatedState(onStep)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (zoom > STEP || zoom < 1 / STEP) {
                        step(if (zoom > 1) 1 else -1)
                        zoom = 1f
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
