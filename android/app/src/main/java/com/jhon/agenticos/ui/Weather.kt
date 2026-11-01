package com.jhon.agenticos.ui

import android.content.Context
import com.jhon.agenticos.core.atomicWrite
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Weather for one fixed city (no location permission), straight from Open-Meteo (no key, not via the PC), cached in
 *  filesDir/weather.json and dated. Everything here touches disk or network: call it off the main thread. */
object Weather {
    class Place(val name: String, val where: String, val lat: Double, val lon: Double)
    class Now(val city: String, val temp: Int, val code: Int, val hi: Int?, val rain: Int?, val at: Long)

    const val FRESH_MS = 30 * 60_000L

    /** The city he picked (default Manila), from the UI process's prefs. */
    fun place(c: Context): Place {
        val p = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
        return Place(p.getString("glance_city", null) ?: "Manila", p.getString("glance_where", null) ?: "Philippines",
            p.getFloat("glance_lat", 14.5995f).toDouble(), p.getFloat("glance_lon", 120.9842f).toDouble())
    }

    fun setPlace(c: Context, p: Place) {
        c.getSharedPreferences("aos", Context.MODE_PRIVATE).edit().putString("glance_city", p.name).putString("glance_where", p.where)
            .putFloat("glance_lat", p.lat.toFloat()).putFloat("glance_lon", p.lon.toFloat()).apply()
        file(c).delete()  // the old city's weather must never show under the new name
    }

    /** The last copy on disk, if it's for the city picked now. */
    fun cached(c: Context): Now? = try {
        val o = JSONObject(file(c).readText())
        val p = place(c)
        if (o.getString("key") != key(p)) null else parse(p.name, o.getString("body"), o.getLong("at"))
    } catch (_: Exception) { null }

    /** Fetch, cache, return; null when offline or Open-Meteo is down (the old copy stays). */
    fun fetch(c: Context): Now? = try {
        val p = place(c)
        val body = get("https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1")
        val now = System.currentTimeMillis()
        parse(p.name, body, now).also {
            atomicWrite(file(c), JSONObject().put("key", key(p)).put("at", now).put("body", body).toString())
        }
    } catch (_: Exception) { null }

    /** City search (Open-Meteo geocoding). Throws when offline so the caller can say so. */
    fun search(name: String): List<Place> {
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&name=" + URLEncoder.encode(name.trim(), "UTF-8")))
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
            Place(r.getString("name"), listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", "),
                r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** WMO weather code in words. */
    fun words(code: Int) = when (code) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"
        in 71..77 -> "Snow"; in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> "Weather unknown"
    }

    private fun parse(city: String, body: String, at: Long): Now {
        val o = JSONObject(body)
        val cur = o.getJSONObject("current"); val d = o.getJSONObject("daily")
        fun first(k: String) = d.optJSONArray(k)?.optDouble(0, Double.NaN)?.takeUnless { it.isNaN() }?.roundToInt()
        return Now(city, cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"),
            first("temperature_2m_max"), first("precipitation_probability_max"), at)
    }

    private fun key(p: Place) = "${p.lat},${p.lon}"
    private fun file(c: Context) = File(c.filesDir, "weather.json")

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 8000
        try {
            if (con.responseCode != 200) throw java.io.IOException("open-meteo ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    }
}
