package com.justsomepren.gallery.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

    fun cachedThumbnail(uri: Uri, size: Int): Bitmap? = thumbs.get("$uri@$size")

    suspend fun thumbnail(resolver: ContentResolver, uri: Uri, size: Int) =
        load(thumbs, "$uri@$size") { resolver.loadThumbnail(uri, Size(size, size), null) }

    suspend fun fullImage(resolver: ContentResolver, uri: Uri, maxSide: Int) =
        load(full, "$uri#$maxSide") {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val scale = min(1f, maxSide.toFloat() / max(info.size.width, info.size.height))
                if (scale < 1f) {
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
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
    val bitmap = rememberBitmap(uri, sizePx, ImageCache.cachedThumbnail(uri, sizePx)) {
        ImageCache.thumbnail(resolver, uri, sizePx)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            val image = remember(it) { it.asImageBitmap() }
            Image(image, null, Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/** Shows [initial] right away and loads the bitmap in the background if it is missing. */
@Composable
fun rememberBitmap(uri: Uri, size: Int, initial: Bitmap? = null, load: suspend () -> Bitmap?): Bitmap? {
    var bitmap by remember(uri, size) { mutableStateOf(initial) }
    LaunchedEffect(uri, size) {
        if (bitmap == null) bitmap = load()
    }
    return bitmap
}
