package com.justsomepren.gallery.data

import android.content.Context
import android.util.Size
import androidx.annotation.VisibleForTesting
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.content
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Names a trip from a few of its photos with Gemini Nano, the AI that runs on the phone itself
 * (Pixel 9 and newer). The photos never leave the phone.
 */
object TripNamer {
    private const val PROMPT = "Tyto fotky jsou z jednoho výletu. Navrhni pro něj krátký český název " +
        "(1 až 3 slova), třeba „Hory na sněhu“ nebo „Pláž u moře“. Odpověz jen názvem, bez uvozovek."

    /** Replaces the question to the phone in tests, which have no AI. */
    @VisibleForTesting
    var fakeStatus: Int? = null

    /** AVAILABLE, or why not: not on this phone, or the model still has to download. */
    suspend fun status(): Int = fakeStatus ?: runCatching {
        withTimeoutOrNull(5_000) { Generation.getClient().use { it.checkStatus() } }
    }.getOrNull() ?: FeatureStatus.UNAVAILABLE

    /** Asks the phone to fetch the model; it does so in the background. Only on Wi-Fi. */
    suspend fun download(context: Context) {
        if (!onWifi(context)) return
        runCatching { Generation.getClient().use { model -> model.download().collect {} } }
    }

    /** A name, or null when the AI is not ready or answers something unusable. */
    suspend fun name(context: Context, photos: List<MediaItem>): String? = runCatching {
        Generation.getClient().use { model ->
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return@use null
            val images = sample(photos).mapNotNull {
                runCatching { context.contentResolver.loadThumbnail(it.uri, Size(512, 512), null) }.getOrNull()
            }
            if (images.isEmpty()) return@use null
            val request = generateContentRequest(content {
                images.forEach { image(it) }
                text(PROMPT)
            }) {
                temperature = 0.2f
                maxOutputTokens = 20
            }
            clean(model.generateContent(request).candidates.firstOrNull()?.text)
        }
    }.getOrNull()

    /** Favourites first, the rest spread over the trip; photos only, at most four. */
    fun sample(photos: List<MediaItem>): List<MediaItem> {
        val stills = photos.filter { !it.isVideo && !it.cloud }.sortedBy { it.date }
        val favorites = stills.filter { it.isFavorite }.take(2)
        val rest = stills - favorites.toSet()
        val spread = (0 until minOf(4 - favorites.size, rest.size)).map { rest[it * rest.size / minOf(4 - favorites.size, rest.size)] }
        return favorites + spread
    }

    /** The first line without quotes and a final full stop; something long or empty is not a name. */
    fun clean(answer: String?): String? {
        val line = answer?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val name = line.trim('"', '„', '“', '”', '\'', '*', '.', ' ').replaceFirstChar { it.uppercase() }
        return name.takeIf { it.isNotEmpty() && it.length <= 40 && it.split(' ').size <= 5 }
    }
}

private inline fun <T> com.google.mlkit.genai.prompt.GenerativeModel.use(block: (com.google.mlkit.genai.prompt.GenerativeModel) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
