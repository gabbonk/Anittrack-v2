package com.example.mangahub

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

data class NewsItem(val title: String, val link: String, val date: String, val source: String)
data class AnimeSlot(val title: String, val url: String, val nextAiring: ZonedDateTime)
data class MangaChapter(val title: String, val chapter: String, val readableAt: ZonedDateTime, val link: String)

object Feeds {

    // Trusted news sources (RSS). Add more feeds here, e.g. "https://url" to "Source name"
    private val newsFeeds = listOf(
        "https://www.animenewsnetwork.com/all/rss.xml" to "Anime News Network"
    )

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "MangaHub/1.0 (Android app)")
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun fetchNews(): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        for ((url, source) in newsFeeds) {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(get(url)))
            var inItem = false
            var tag = ""
            var title = ""
            var link = ""
            var date = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "item") {
                            inItem = true
                            title = ""; link = ""; date = ""
                        }
                    }
                    XmlPullParser.TEXT -> if (inItem) {
                        when (tag) {
                            "title" -> title += parser.text
                            "link" -> link += parser.text
                            "pubDate" -> date += parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            inItem = false
                            if (title.isNotBlank()) {
                                out += NewsItem(title.trim(), link.trim(), date.trim(), source)
                            }
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    // Anime airing on a given weekday (Jikan API = unofficial MyAnimeList API)
    fun fetchSchedule(day: DayOfWeek): List<AnimeSlot> {
        val json = JSONObject(get("https://api.jikan.moe/v4/schedules?filter=${day.name.lowercase()}"))
        val data = json.getJSONArray("data")
        val out = mutableListOf<AnimeSlot>()
        for (i in 0 until data.length()) {
            val anime = data.getJSONObject(i)
            val time = anime.optJSONObject("broadcast")?.optString("time")
            val next = nextAiring(day, time) ?: continue
            out += AnimeSlot(anime.optString("title"), anime.optString("url"), next)
        }
        return out.sortedBy { it.nextAiring }
    }

    // Japan broadcasts are in JST, so compute the next airing in JST, then convert to the phone's zone
    private fun nextAiring(day: DayOfWeek, time: String?): ZonedDateTime? {
        val t = time?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        val nowJst = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
        var next = nowJst.with(TemporalAdjusters.nextOrSame(day)).with(t)
        if (next.isBefore(nowJst)) next = next.plusWeeks(1)
        return next.withZoneSameInstant(ZoneId.systemDefault())
    }

    // Latest English manga/manhwa chapters from MangaDex
    fun fetchMangaChapters(): List<MangaChapter> {
        val url = "https://api.mangadex.org/chapter?limit=40" +
                "&translatedLanguage%5B%5D=en" +
                "&order%5BreadableAt%5D=desc" +
                "&includes%5B%5D=manga" +
                "&contentRating%5B%5D=safe" +
                "&contentRating%5B%5D=suggestive"
        val data = JSONObject(get(url)).getJSONArray("data")
        val out = mutableListOf<MangaChapter>()
        for (i in 0 until data.length()) {
            val chapter = data.getJSONObject(i)
            val attrs = chapter.getJSONObject("attributes")
            var name = "Unknown title"
            val rels = chapter.getJSONArray("relationships")
            for (j in 0 until rels.length()) {
                val rel = rels.getJSONObject(j)
                if (rel.getString("type") == "manga") {
                    val en = rel.optJSONObject("attributes")?.optJSONObject("title")?.optString("en")
                    if (!en.isNullOrBlank()) name = en
                }
            }
            val readable = ZonedDateTime.parse(attrs.getString("readableAt"))
                .withZoneSameInstant(ZoneId.systemDefault())
            out += MangaChapter(
                title = name,
                chapter = attrs.optString("chapter", "?"),
                readableAt = readable,
                link = "https://mangadex.org/chapter/${chapter.getString("id")}"
            )
        }
        return out
    }
}
