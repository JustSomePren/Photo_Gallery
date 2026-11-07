package com.justsomepren.gallery.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Places from a Google Maps Timeline export (Timeline.json from the phone, or an older Takeout file).
 * Google does not document the format and it changes, so the file is read loosely: any object with
 * a time and a position counts, wherever it sits.
 */
class Timeline(private val points: List<Point>) {
    data class Point(val from: Long, val to: Long, val lat: Double, val lon: Double)

    val size get() = points.size

    /** The place at that moment: inside a stay, or the nearest point within two hours. */
    fun placeAt(millis: Long): Pair<Double, Double>? {
        var best: Point? = null
        var bestGap = Long.MAX_VALUE
        // Binary search for the first point starting after the moment, then look around it.
        var low = 0
        var high = points.size
        while (low < high) {
            val mid = (low + high) / 2
            if (points[mid].from <= millis) low = mid + 1 else high = mid
        }
        for (i in maxOf(0, low - 50) until minOf(points.size, low + 50)) {
            val p = points[i]
            val gap = when {
                millis < p.from -> p.from - millis
                millis > p.to -> millis - p.to
                else -> 0
            }
            if (gap < bestGap) {
                best = p
                bestGap = gap
            }
        }
        return best?.takeIf { bestGap <= TWO_HOURS }?.let { it.lat to it.lon }
    }

    companion object {
        private const val TWO_HOURS = 2 * 60 * 60 * 1000L
        private val degrees = Regex("""(-?\d+(?:\.\d+)?)°,\s*(-?\d+(?:\.\d+)?)°""")
        private val geo = Regex("""geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")

        fun read(reader: Reader): Timeline {
            val points = mutableListOf<Point>()
            JsonReader(reader).use { readValue(it, points) }
            return Timeline(points.sortedBy { it.from })
        }

        /** What an object found out; a position found deeper in is handed up to the object that has the time. */
        private class Found {
            var lat: Double? = null
            var lon: Double? = null
            var time: Long? = null
            var start: Long? = null
            var end: Long? = null
            var latE7: Long? = null
            var lonE7: Long? = null

            fun position(): Pair<Double, Double>? =
                if (lat != null && lon != null) lat!! to lon!!
                else if (latE7 != null && lonE7 != null) latE7!! / 1e7 to lonE7!! / 1e7
                else null
        }

        private fun readValue(reader: JsonReader, points: MutableList<Point>): Found? = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader, points)
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var first: Found? = null
                while (reader.hasNext()) readValue(reader, points)?.let { if (first == null) first = it }
                reader.endArray()
                first
            }
            else -> {
                reader.skipValue()
                null
            }
        }

        private fun readObject(reader: JsonReader, points: MutableList<Point>): Found {
            val found = Found()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (reader.peek()) {
                    JsonToken.STRING -> {
                        val text = reader.nextString()
                        when (name) {
                            "time", "timestamp" -> found.time = parseTime(text)
                            "startTime", "startTimestamp" -> found.start = parseTime(text)
                            "endTime", "endTimestamp" -> found.end = parseTime(text)
                            else -> (degrees.find(text) ?: geo.find(text))?.let {
                                if (found.lat == null) {
                                    found.lat = it.groupValues[1].toDouble()
                                    found.lon = it.groupValues[2].toDouble()
                                }
                            }
                        }
                        if (name == "timestampMs") found.time = text.toLongOrNull()
                    }
                    JsonToken.NUMBER -> when (name) {
                        "latitudeE7" -> found.latE7 = reader.nextLong()
                        "longitudeE7" -> found.lonE7 = reader.nextLong()
                        else -> reader.skipValue()
                    }
                    JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> {
                        val inner = readValue(reader, points)
                        if (found.position() == null) inner?.position()?.let { (lat, lon) ->
                            found.lat = lat
                            found.lon = lon
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val position = found.position()
            val from = found.time ?: found.start
            val to = found.time ?: found.end ?: found.start
            if (position != null && from != null && to != null) {
                points += Point(from, to, position.first, position.second)
                // Handed to the parent as "used", so a stay is not counted twice.
                return Found()
            }
            return found
        }

        private fun parseTime(text: String): Long? =
            runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}
