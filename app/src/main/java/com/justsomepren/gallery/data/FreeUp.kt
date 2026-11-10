package com.justsomepren.gallery.data

/**
 * Photos that may leave the phone: camera photos this app uploaded, still the same size as when sent.
 * Favorites stay when [keepFavorites].
 */
fun freeUpCandidates(media: List<MediaItem>, prefs: Prefs, keepFavorites: Boolean): List<Pair<MediaItem, CloudCopy>> =
    media.filter { it.isCameraShot && !(keepFavorites && it.isFavorite) }.mapNotNull { item ->
        prefs.cloudCopies(item.key).firstOrNull { it.size == item.size }?.let { item to it }
    }

/** Of the candidates, those Google confirmed just now: the item is there, under the same file name. */
fun confirmedInCloud(candidates: List<Pair<MediaItem, CloudCopy>>, there: Map<String, String>): List<MediaItem> =
    candidates.filter { (item, copy) -> there[copy.mediaItemId] == item.name }.map { it.first }
