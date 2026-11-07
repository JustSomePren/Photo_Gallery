package com.justsomepren.gallery.data

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.round

/** A photo's place: where it was taken, on which day. */
data class Spot(val day: Long, val lat: Double, val lon: Double)

/** A trip found in the photos, waiting for the user to confirm it. */
data class TripProposal(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startDay: Long,
    val endDay: Long,
    val photos: Int,
)

/**
 * Finds trips from where photos were taken: the place with photos on most days is home,
 * days far from it in a row make a trip. Pure computation, everything stays on the phone.
 */
object TripFinder {
    private const val AWAY_KM = 50.0
    private const val MAX_GAP_DAYS = 2

    fun find(spots: List<Spot>, places: Places): List<TripProposal> {
        if (spots.isEmpty()) return emptyList()
        val (homeLat, homeLon) = home(spots)
        val homeCountry = places.nearest(homeLat, homeLon)?.country

        val byDay = spots.groupBy { it.day }.toSortedMap()
        val awayDays = byDay.filter { (_, daySpots) ->
            val lat = daySpots.map { it.lat }.median()
            val lon = daySpots.map { it.lon }.median()
            distanceKm(lat, lon, homeLat, homeLon) > AWAY_KM
        }.keys

        // Days away in a row make one trip; a gap without photos does not break it, a day at home does.
        val trips = mutableListOf<MutableList<Long>>()
        for (day in awayDays) {
            val current = trips.lastOrNull()
            val gapAtHome = current != null && (current.last() + 1 until day).any { it in byDay }
            if (current != null && day - current.last() <= MAX_GAP_DAYS + 1 && !gapAtHome) current += day else trips += mutableListOf(day)
        }

        return trips.mapNotNull { days ->
            val tripSpots = days.flatMap { byDay.getValue(it) }
            // One afternoon with a couple of photos is not a trip.
            if (days.size < 2 && tripSpots.size < 8) return@mapNotNull null
            TripProposal(
                name = "${placeName(tripSpots, places, homeCountry)} – ${monthTitle(java.time.YearMonth.from(days.first().toDate())).lowercase()}",
                startDay = days.first(),
                endDay = days.last(),
                photos = tripSpots.size,
            )
        }
    }

    /** The 20 km area with photos on the most different days. */
    private fun home(spots: List<Spot>): Pair<Double, Double> {
        val cell = { s: Spot -> round(s.lat * 5) to round(s.lon * 5) }
        val homeCell = spots.groupBy(cell).maxBy { (_, inCell) -> inCell.map { it.day }.distinct().size }.value
        return homeCell.map { it.lat }.median() to homeCell.map { it.lon }.median()
    }

    /** Abroad the country ("Tchaj-wan"), at home the city most photos were taken near ("Brno"). */
    private fun placeName(spots: List<Spot>, places: Places, homeCountry: String?): String {
        val cities = spots.mapNotNull { places.nearest(it.lat, it.lon) }
        if (cities.isEmpty()) return "Výlet"
        val country = cities.groupingBy { it.country }.eachCount().maxBy { it.value }.key
        if (country != homeCountry) return countryName(country)
        return cities.filter { it.country == country }.groupingBy { it.name }.eachCount().maxBy { it.value }.key
    }

    private fun List<Double>.median() = sorted()[size / 2]
}

/**
 * Where photos were taken, read once from their files and kept in a small text file,
 * so the next search only reads new photos.
 */
class SpotCache(private val context: Context) {
    private val file = File(context.filesDir, "locations.tsv")
    private val known: MutableMap<String, Pair<Double, Double>?> = read()

    fun spot(item: MediaItem): Spot? = known[item.key]?.let { Spot(item.day, it.first, it.second) }

    fun isKnown(item: MediaItem) = item.key in known

    /** Reads the place of photos not read yet; returns how many it read. */
    fun readMissing(items: List<MediaItem>, onProgress: (Int, Int) -> Unit): Int {
        val missing = items.filterNot(::isKnown)
        val lines = StringBuilder()
        missing.forEachIndexed { index, item ->
            val place = runCatching { readPlace(item) }.getOrNull()
            known[item.key] = place
            lines.append(item.key).append('\t').append(place?.let { "${it.first}\t${it.second}" } ?: "-").append('\n')
            if (index % 200 == 199 || index == missing.lastIndex) {
                file.appendText(lines.toString())
                lines.clear()
                onProgress(index + 1, missing.size)
            }
        }
        return missing.size
    }

    private fun readPlace(item: MediaItem): Pair<Double, Double>? {
        val uri = originalUri(context, item.uri)
        if (item.isVideo) {
            // ISO 6709, e.g. "+25.0330+121.5654/"
            val text = MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                } finally {
                    release()
                }
            } ?: return null
            val match = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(text) ?: return null
            return match.groupValues[1].toDouble() to match.groupValues[2].toDouble()
        }
        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return null
        return exif.latLong?.let { it[0] to it[1] }
    }

    private fun read(): MutableMap<String, Pair<Double, Double>?> {
        if (!file.exists()) return mutableMapOf()
        return file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            when {
                p.size == 2 && p[1] == "-" -> p[0] to null
                p.size == 3 -> p[0] to (p[1].toDouble() to p[2].toDouble())
                else -> null
            }
        }.toMap().toMutableMap()
    }
}
