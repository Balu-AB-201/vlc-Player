/*
 * VLC for Android — online lyrics support
 *
 * This file adds an independent LRCLIB client and LRC/word-timestamp parser.
 * VLC for Android is licensed under the GPL; see the repository's COPYING file.
 */
package org.videolan.vlc.lyrics

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

data class LyricsResult(
    val title: String,
    val artist: String,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val durationSeconds: Int?
) {
    val displayText: String
        get() = syncedLyrics?.takeIf { it.isNotBlank() }
            ?: plainLyrics.orEmpty()
}

data class TimedLyricWord(val timestampMs: Long, val text: String)

data class TimedLyricLine(
    val timestampMs: Long,
    val text: String,
    val words: List<TimedLyricWord> = emptyList()
)

/**
 * Queries LRCLIB using both title and artist, then falls back to a scored search.
 * Network work is always dispatched off the main thread.
 */
object LyricsRepository {
    private const val API_BASE = "https://lrclib.net/api"
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 7_000

    suspend fun search(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return@withContext null

        val durationSeconds = durationMs
            ?.takeIf { it > 0L }
            ?.div(1_000L)
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()

        val exactQuery = Uri.parse("$API_BASE/get").buildUpon()
            .appendQueryParameter("track_name", cleanTitle)
            .appendQueryParameter("artist_name", cleanArtist)
            .apply {
                album?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    appendQueryParameter("album_name", it)
                }
                durationSeconds?.let { appendQueryParameter("duration", it.toString()) }
            }
            .build().toString()

        val exact = requestJson(exactQuery)
        if (exact is JSONObject) parseResult(exact)?.let { return@withContext it }

        val searchQuery = Uri.parse("$API_BASE/search").buildUpon()
            .appendQueryParameter("q", "$cleanArtist $cleanTitle")
            .build().toString()
        val results = requestJson(searchQuery) as? JSONArray ?: return@withContext null

        (0 until results.length())
            .mapNotNull { index -> (results.opt(index) as? JSONObject)?.let(::parseResult) }
            .filter { it.displayText.isNotBlank() }
            .maxByOrNull { candidate ->
                var score = 0
                if (normalize(candidate.title) == normalize(cleanTitle)) score += 6
                if (normalize(candidate.artist).contains(normalize(cleanArtist)) ||
                    normalize(cleanArtist).contains(normalize(candidate.artist))) score += 5
                if (durationSeconds != null && candidate.durationSeconds != null) {
                    val difference = abs(durationSeconds - candidate.durationSeconds)
                    score += when {
                        difference <= 2 -> 4
                        difference <= 5 -> 2
                        difference <= 10 -> 1
                        else -> -3
                    }
                }
                if (!candidate.syncedLyrics.isNullOrBlank()) score += 2
                if (!candidate.plainLyrics.isNullOrBlank()) score += 1
                score
            }
            ?.takeIf { candidate ->
                normalize(candidate.title) == normalize(cleanTitle) &&
                    (normalize(candidate.artist).contains(normalize(cleanArtist)) ||
                        normalize(cleanArtist).contains(normalize(candidate.artist)))
            }
    }

    private fun requestJson(url: String): Any? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "VLC-Android-Lyrics/1.0")
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = body.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResult(json: JSONObject): LyricsResult? {
        val title = json.optString("trackName").trim()
        val artist = json.optString("artistName").trim()
        if (title.isBlank() || artist.isBlank()) return null
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (plain == null && synced == null) return null
        val duration = json.optDouble("duration", Double.NaN)
            .takeIf { it.isFinite() && it > 0 }
            ?.toInt()
        return LyricsResult(title, artist, plain, synced, duration)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

/** Parses standard LRC timestamps plus optional inline word timestamps. */
object LrcParser {
    private val lineTimestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val wordTimestamp = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")

    fun parse(input: String): List<TimedLyricLine> {
        if (input.isBlank()) return emptyList()
        var offsetMs = 0L
        offsetTag.findAll(input).lastOrNull()?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { offsetMs = it }

        val parsed = mutableListOf<TimedLyricLine>()
        input.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (offsetTag.containsMatchIn(line)) return@forEach

            val tags = lineTimestamp.findAll(line).mapNotNull { match ->
                timestampToMs(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                )?.let { (it + offsetMs).coerceAtLeast(0L) }
            }.toList()
            if (tags.isEmpty()) return@forEach

            val text = lineTimestamp.replace(line, "").trim()
            val words = parseWords(text, offsetMs)
            tags.forEach { timestamp ->
                parsed += TimedLyricLine(
                    timestampMs = timestamp,
                    text = words.takeIf { it.isNotEmpty() }?.joinToString("") { it.text } ?: text,
                    words = words
                )
            }
        }
        return parsed.sortedBy { it.timestampMs }
    }

    private fun parseWords(text: String, offsetMs: Long): List<TimedLyricWord> {
        if (!wordTimestamp.containsMatchIn(text)) return emptyList()
        val words = mutableListOf<TimedLyricWord>()
        var cursor = 0
        var currentTimestamp: Long? = null
        wordTimestamp.findAll(text).forEach { match ->
            val preceding = text.substring(cursor, match.range.first)
            currentTimestamp?.let { words += TimedLyricWord(it, preceding) }
            currentTimestamp = timestampToMs(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3]
            )?.let { (it + offsetMs).coerceAtLeast(0L) }
            cursor = match.range.last + 1
        }
        currentTimestamp?.let { words += TimedLyricWord(it, text.substring(cursor)) }
        return words.filter { it.text.isNotEmpty() }
    }

    private fun timestampToMs(minutes: String, seconds: String, fraction: String): Long? {
        val min = minutes.toLongOrNull() ?: return null
        val sec = seconds.toLongOrNull() ?: return null
        if (sec !in 0..59) return null
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L) ?: return null
            2 -> fraction.toLongOrNull()?.times(10L) ?: return null
            else -> fraction.take(3).toLongOrNull() ?: return null
        }
        return min * 60_000L + sec * 1_000L + millis
    }
}
