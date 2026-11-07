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
        return runs(awayDays.toList(), byDay.keys).mapNotNull { days ->
            val tripSpots = days.flatMap { byDay.getValue(it) }
            // One afternoon with a couple of photos is not a trip.
            if (days.size < 2 && tripSpots.size < 8) return@mapNotNull null
            proposal(placeName(tripSpots, places, homeCountry), days, tripSpots.size)
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

    /**
     * Without places: days whose photos carry another time zone than home make a trip abroad.
     * Home may have two zones (summer and winter time), both an hour apart.
     */
    fun byTimeZone(zones: Map<Long, Int>, skip: Set<Long>): List<TripProposal> {
        if (zones.isEmpty()) return emptyList()
        val byDays = zones.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val home = byDays.first().key
        val homeZones = setOf(home) + byDays.drop(1).take(1).map { it.key }.filter { kotlin.math.abs(it - home) == 60 }
        val away = zones.filter { (day, zone) -> zone !in homeZones && day !in skip }
        return runs(away.keys.sorted(), zones.keys).mapNotNull { days ->
            if (days.size < 2) return@mapNotNull null
            val zone = days.mapNotNull { away[it] }.groupingBy { it }.eachCount().maxBy { it.value }.key
            proposal("Výlet ${utc(zone)}", days, days.size)
        }
    }

    /** Without places or zones: several days in a row with far more photos than usual. */
    fun byBursts(perDay: Map<Long, Int>, skip: Set<Long>): List<TripProposal> {
        if (perDay.size < 7) return emptyList()
        val usual = perDay.values.sorted()[perDay.size / 2]
        val busy = perDay.filter { (day, count) -> day !in skip && count >= maxOf(15, usual * 3) }.keys.sorted()
        return runs(busy, perDay.keys).filter { it.size >= 2 }.map { days ->
            proposal("Možná výlet", days, days.sumOf { perDay.getValue(it) })
        }
    }

    /** Groups sorted days into runs; a gap of up to two days without photos is allowed, a day with others is not. */
    private fun runs(days: List<Long>, withPhotos: Set<Long>): List<List<Long>> {
        val runs = mutableListOf<MutableList<Long>>()
        for (day in days) {
            val current = runs.lastOrNull()
            val brokenBy = current != null && (current.last() + 1 until day).any { it in withPhotos }
            if (current != null && day - current.last() <= MAX_GAP_DAYS + 1 && !brokenBy) current += day else runs += mutableListOf(day)
        }
        return runs
    }

    private fun proposal(name: String, days: List<Long>, photos: Int) = TripProposal(
        name = "$name – ${monthTitle(java.time.YearMonth.from(days.first().toDate())).lowercase()}",
        startDay = days.first(),
        endDay = days.last(),
        photos = photos,
    )

    private fun utc(minutes: Int): String {
        val sign = if (minutes < 0) "-" else "+"
        val h = kotlin.math.abs(minutes) / 60
        val m = kotlin.math.abs(minutes) % 60
        return "UTC$sign$h" + if (m > 0) ":%02d".format(m) else ""
    }

    private fun List<Double>.median() = sorted()[size / 2]
}

/** What one photo says about where it was taken: a place, a time zone, both or neither. */
data class PhotoPlace(val lat: Double?, val lon: Double?, val offsetMinutes: Int?)

/**
 * Where photos were taken and in which time zone, read once from their files and kept in a small
 * text file, so the next search only reads new photos. Places from a Timeline export fill gaps.
 */
class SpotCache(private val context: Context) {
    private val file = File(context.filesDir, "locations.tsv")
    private val known: MutableMap<String, PhotoPlace> = read()

    fun spot(item: MediaItem): Spot? = known[item.key]?.let { p ->
        if (p.lat != null && p.lon != null) Spot(item.day, p.lat, p.lon) else null
    }

    fun offset(item: MediaItem): Int? = known[item.key]?.offsetMinutes

    fun isKnown(item: MediaItem) = item.key in known

    /** Reads place and time zone of photos not read yet. */
    fun readMissing(items: List<MediaItem>, onProgress: (Int, Int) -> Unit) {
        val missing = items.filterNot(::isKnown)
        val lines = StringBuilder()
        missing.forEachIndexed { index, item ->
            val place = runCatching { readPlace(item) }.getOrNull() ?: PhotoPlace(null, null, null)
            known[item.key] = place
            lines.append(line(item.key, place))
            if (index % 200 == 199 || index == missing.lastIndex) {
                file.appendText(lines.toString())
                lines.clear()
                onProgress(index + 1, missing.size)
            }
        }
    }

    /** Gives photos without a place the one from the Timeline closest in time; returns how many got one. */
    fun fillFrom(timeline: Timeline, items: List<MediaItem>): Int {
        val lines = StringBuilder()
        var filled = 0
        for (item in items) {
            val current = known[item.key]
            if (current?.lat != null) continue
            val (lat, lon) = timeline.placeAt(item.date) ?: continue
            val place = PhotoPlace(lat, lon, current?.offsetMinutes)
            known[item.key] = place
            lines.append(line(item.key, place))
            filled++
        }
        if (filled > 0) file.appendText(lines.toString())
        return filled
    }

    private fun line(key: String, p: PhotoPlace) =
        "$key\t${p.lat ?: "-"}\t${p.lon ?: "-"}\t${p.offsetMinutes ?: "-"}\n"

    private fun readPlace(item: MediaItem): PhotoPlace {
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
            }
            val match = text?.let { Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(it) }
            return PhotoPlace(match?.groupValues?.get(1)?.toDouble(), match?.groupValues?.get(2)?.toDouble(), null)
        }
        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return PhotoPlace(null, null, null)
        val latLon = exif.latLong
        return PhotoPlace(latLon?.get(0), latLon?.get(1), parseOffset(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)))
    }

    private fun read(): MutableMap<String, PhotoPlace> {
        if (!file.exists()) return mutableMapOf()
        val map = mutableMapOf<String, PhotoPlace>()
        // Later lines win: a place from the Timeline is added after the photo's own line.
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size < 4) continue
            map[p[0]] = PhotoPlace(p[1].toDoubleOrNull(), p[2].toDoubleOrNull(), p[3].toIntOrNull())
        }
        return map
    }
}

/** "+08:00" → 480 minutes. */
fun parseOffset(text: String?): Int? {
    val m = Regex("""([+-])(\d{2}):(\d{2})""").matchEntire(text?.trim() ?: return null) ?: return null
    val minutes = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
    return if (m.groupValues[1] == "-") -minutes else minutes
}
