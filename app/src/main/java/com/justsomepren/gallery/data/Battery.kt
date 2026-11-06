package com.justsomepren.gallery.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class BatteryState(val level: Int, val charging: Boolean, val powerSave: Boolean) {
    fun saver(threshold: Int) = powerSave || (!charging && level < threshold)
}

/** Only listens while someone collects it, i.e. while the app is on screen. */
fun Context.batteryFlow(): Flow<BatteryState> = callbackFlow {
    val power = getSystemService(PowerManager::class.java)
    fun read(intent: Intent) = BatteryState(
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1),
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        powerSave = power.isPowerSaveMode,
    )

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(read(intent))
        }
    }
    registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        ?.let { trySend(read(it)) }
    awaitClose { unregisterReceiver(receiver) }
}
