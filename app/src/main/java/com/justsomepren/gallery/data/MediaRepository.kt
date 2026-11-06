package com.justsomepren.gallery.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mime: String,
    val isVideo: Boolean,
    val date: Long,
    val bucketId: Long,
    val bucketName: String,
    val isFavorite: Boolean,
)

/**
 * Reads straight from MediaStore. Android already indexes the photos,
 * so the app keeps no own database and does no background scanning.
 */
class MediaRepository(private val context: Context) {

    fun loadAll(): List<MediaItem> =
        (query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) +
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
            .sortedByDescending { it.date }

    private fun query(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_FAVORITE,
        )
        val result = mutableListOf<MediaItem>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = if (c.isNull(2)) 0L else c.getLong(2)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    mime = c.getString(1) ?: if (isVideo) "video/*" else "image/*",
                    isVideo = isVideo,
                    date = if (taken > 0) taken else c.getLong(3) * 1000,
                    bucketId = c.getLong(4),
                    bucketName = c.getString(5) ?: "Bez názvu",
                    isFavorite = c.getInt(6) == 1,
                )
            }
        }
        return result
    }
}

sealed interface Source {
    data object All : Source
    data object Favorites : Source
    data class Folder(val bucketId: Long, val name: String) : Source
    data class Album(val id: String, val name: String) : Source
}

val Source.title: String
    get() = when (this) {
        Source.All -> "Fotky"
        Source.Favorites -> "Oblíbené"
        is Source.Folder -> name
        is Source.Album -> name
    }

fun List<MediaItem>.filterBy(source: Source, albums: List<CustomAlbum>): List<MediaItem> =
    when (source) {
        Source.All -> this
        Source.Favorites -> filter { it.isFavorite }
        is Source.Folder -> filter { it.bucketId == source.bucketId }
        is Source.Album -> {
            val ids = albums.find { it.id == source.id }?.mediaIds?.toSet().orEmpty()
            filter { it.id in ids }
        }
    }
