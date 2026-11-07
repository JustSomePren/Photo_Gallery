package com.justsomepren.gallery.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

/** Everything the app does not do itself is handed over to Google Photos. */
object GooglePhotos {
    const val PACKAGE = "com.google.android.apps.photos"

    fun open(context: Context, item: MediaItem) = start(
        context,
        viewIntent(item).setPackage(PACKAGE),
        fallback = viewIntent(item),
    )

    fun edit(context: Context, item: MediaItem) = start(
        context,
        Intent(Intent.ACTION_EDIT).setDataAndType(item.uri, item.mime).setPackage(PACKAGE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        fallback = viewIntent(item).setPackage(PACKAGE),
    )

    private fun viewIntent(item: MediaItem) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(item.uri, item.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun search(context: Context, query: String) = start(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/search/" + Uri.encode(query)))
            .setPackage(PACKAGE),
        fallback = context.packageManager.getLaunchIntentForPackage(PACKAGE),
    )

    /** Opens any map app; works offline with downloaded maps. */
    fun showOnMap(context: Context, lat: Double, lon: Double) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))

    fun openApp(context: Context) =
        start(context, context.packageManager.getLaunchIntentForPackage(PACKAGE))

    fun appSettings(context: Context, pkg: String = PACKAGE) = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )

    fun batterySaverSettings(context: Context) =
        start(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))

    fun manageMediaSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")),
    )

    fun canManageMedia(context: Context) = MediaStore.canManageMedia(context)

    // ---- Sending to the cloud apps ----

    fun shareIntent(items: List<MediaItem>): Intent {
        val uris = ArrayList(items.map { it.uri })
        val type = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {
                clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
    }

    fun share(context: Context, items: List<MediaItem>) =
        start(context, Intent.createChooser(shareIntent(items), "Sdílet"))

    fun send(context: Context, target: UploadTarget, items: List<MediaItem>) = start(
        context,
        shareIntent(items).setComponent(ComponentName(target.packageName, target.activityName)),
    )

    /** Installed apps that accept several photos at once, with a readable label. */
    fun uploadApps(context: Context): List<Pair<String, UploadTarget>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)).map { info ->
            val app = info.activityInfo.applicationInfo.loadLabel(pm).toString()
            val activity = info.loadLabel(pm).toString()
            val label = if (activity == app) app else "$app – $activity"
            label to UploadTarget(label, info.activityInfo.packageName, info.activityInfo.name)
        }.sortedBy { it.first }
    }

    fun defaultTargets(context: Context): List<UploadTarget> =
        uploadApps(context).map { it.second }.mapNotNull { target ->
            when (target.packageName) {
                PACKAGE -> target.copy(name = "Google Fotky")
                else -> null
            }
        }.distinctBy { it.packageName }

    /** Most photos one request can carry; more would exceed Android's message size limit. */
    const val MAX_ITEMS = 1000

    private fun start(context: Context, intent: Intent?, fallback: Intent? = null) {
        try {
            context.startActivity(intent ?: throw ActivityNotFoundException())
        } catch (e: RuntimeException) {
            // Not installed, no longer exported (SecurityException) or too many photos.
            if (fallback != null) start(context, fallback)
            else Toast.makeText(context, "Aplikace není k dispozici", Toast.LENGTH_SHORT).show()
        }
    }
}
