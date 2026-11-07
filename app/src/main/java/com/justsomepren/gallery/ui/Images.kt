package com.justsomepren.gallery.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Thumbnails come from the system thumbnail cache (cheap), full images are
 * decoded only as big as the screen needs.
 */
object ImageCache {
    private val io = Dispatchers.IO.limitedParallelism(4)

    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val full = LruCache<String, Bitmap>(3)

    @VisibleForTesting
    fun clear() {
        thumbs.evictAll()
        full.evictAll()
    }

    /** Lets screenshot tests show pictures without real files. */
    @VisibleForTesting
    var fake: ((Uri) -> Bitmap)? = null

    /** Bumped when files change, so screens holding a picture load it again. */
    var version by mutableIntStateOf(0)
        private set

    /** Drops cached pictures of edited files. */
    fun forget(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val prefixes = uris.map { it.toString() }
        for (cache in listOf(thumbs, full)) {
            cache.snapshot().keys.filter { key -> prefixes.any { key.startsWith("$it@") || key.startsWith("$it#") } }
                .forEach(cache::remove)
        }
        version++
    }

    fun cachedThumbnail(uri: Uri, size: Int): Bitmap? = thumbs.get("$uri@$size")

    suspend fun thumbnail(resolver: ContentResolver, uri: Uri, size: Int) =
        load(thumbs, "$uri@$size") {
            fake?.invoke(uri) ?: if (uri.scheme == "file") decode(resolver, uri, size) else resolver.loadThumbnail(uri, Size(size, size), null)
        }

    suspend fun fullImage(resolver: ContentResolver, uri: Uri, maxSide: Int) =
        load(full, "$uri#$maxSide") { fake?.invoke(uri) ?: decode(resolver, uri, maxSide) }

    /** Also reads a downloaded preview of a photo that is only in Google Photos (a file, not in MediaStore). */
    private fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = min(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
            if (scale < 1f) {
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }

    private suspend fun load(cache: LruCache<String, Bitmap>, key: String, block: () -> Bitmap): Bitmap? =
        cache.get(key) ?: withContext(io) { runCatching(block).getOrNull() }?.also { cache.put(key, it) }
}

@Composable
fun Thumbnail(
    uri: Uri,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val resolver = LocalContext.current.contentResolver
    val bitmap = rememberLoaded(uri, sizePx, ImageCache.version, initial = ImageCache.cachedThumbnail(uri, sizePx)) {
        ImageCache.thumbnail(resolver, uri, sizePx)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            val image = remember(it) { it.asImageBitmap() }
            Image(image, null, Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** Returns [initial] right away and loads the value in the background if it is missing. */
@Composable
fun <T : Any> rememberLoaded(vararg keys: Any, initial: T? = null, load: suspend () -> T?): T? {
    var value by remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(*keys) {
        if (value == null) value = load()
    }
    return value
}

/** One thumbnail size for the grid and the calendar, so they share the cache. */
@Composable
fun gridThumbPx(): Int = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp / 4).roundToPx() }
