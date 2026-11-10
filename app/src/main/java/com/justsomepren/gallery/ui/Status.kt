package com.justsomepren.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.Event

/** Upload state and running events in one row above the photos. */
@Composable
fun StatusStrip(vm: GalleryViewModel, onOpenSending: () -> Unit) {
    val queue by vm.prefs.queue.collectAsStateWithLifecycle()
    val failed by vm.prefs.failed.collectAsStateWithLifecycle()
    val uploading by vm.prefs.uploading.collectAsStateWithLifecycle()
    val events by vm.prefs.events.collectAsStateWithLifecycle()
    val accounts by vm.prefs.accounts.collectAsStateWithLifecycle()
    val mobileData by vm.prefs.mobileData.collectAsStateWithLifecycle()

    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val status = queueStatus(queue.size, failed.size, uploading, mobileData)
        if (status != null || accounts.isNotEmpty()) {
            AssistChip(
                onClick = onOpenSending,
                label = { Text(status ?: "Vše odesláno") },
                leadingIcon = {
                    val icon = when {
                        failed.isNotEmpty() -> Icons.Default.ErrorOutline
                        status != null -> Icons.Default.CloudUpload
                        else -> Icons.Default.CloudDone
                    }
                    Icon(icon, null, Modifier.size(18.dp))
                },
            )
        }
        events.filter { it.isActive }.forEach { EventChip(it) { vm.editingEvent = it } }
        AssistChip(
            onClick = { vm.newEvent() },
            label = { Text("Událost") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
        )
    }
}

fun queueStatus(queued: Int, failed: Int, uploading: Pair<Int, Int>?, mobileData: Boolean): String? = when {
    uploading != null -> "Odesílám ${uploading.first} z ${uploading.second}"
    queued > 0 -> "$queued čeká · odešle se na ${if (mobileData) "síti" else "Wi-Fi"}"
    failed > 0 -> "$failed se nepodařilo odeslat"
    else -> null
}

@Composable
fun EventChip(event: Event, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(event.name + (event.account?.takeIf { event.autoSend }?.let { " → ${it.substringBefore('@')}" } ?: "")) },
        leadingIcon = { Box(Modifier.size(12.dp).background(Color(event.color), CircleShape)) },
        trailingIcon = {
            if (event.autoSend) Icon(Icons.Default.CloudSync, "Posílá automaticky", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        },
    )
}
