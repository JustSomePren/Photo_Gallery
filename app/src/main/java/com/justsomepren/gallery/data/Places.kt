package com.justsomepren.gallery.data

import android.content.Context
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class City(val name: String, val country: String, val lat: Double, val lon: Double, val population: Int)

/**
 * Cities with more than 15 000 people, bundled with the app (GeoNames, CC BY 4.0),
 * so places get a name without internet.
 */
class Places(cities: List<City>) {
    private val grid = cities.groupBy { cell(it.lat, it.lon) }

    /** The closest city, looking in the surrounding 1° cells (about 100 km). */
    fun nearest(lat: Double, lon: Double): City? {
        val (row, col) = cell(lat, lon)
        return (-1..1).flatMap { dr -> (-1..1).flatMap { dc -> grid[row + dr to col + dc].orEmpty() } }
            .minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }
    }

    private fun cell(lat: Double, lon: Double) = floor(lat).toInt() to floor(lon).toInt()

    companion object {
        @Volatile
        private var loaded: Places? = null

        fun get(context: Context): Places = loaded ?: synchronized(this) {
            loaded ?: Places(read(context)).also { loaded = it }
        }

        private fun read(context: Context): List<City> =
            context.assets.open("cities.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) null else City(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toInt())
                }.toList()
            }
    }
}

/** Country name in Czech, e.g. "TW" → "Tchaj-wan". */
fun countryName(code: String): String = Locale("", code).getDisplayCountry(Locale("cs")).ifBlank { code }

fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * 6371 * asin(sqrt(a))
}
