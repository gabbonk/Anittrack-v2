package com.example.mangahub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("News") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Releases") })
            }
            if (tab == 0) NewsScreen() else ReleasesScreen()
        }
    }
}

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

fun countdown(target: ZonedDateTime, nowMs: Long): String {
    val d = Duration.between(Instant.ofEpochMilli(nowMs), target.toInstant())
    if (d.isNegative) return "Airing now / just aired"
    val days = d.toDays()
    val hours = d.toHours() % 24
    val mins = d.toMinutes() % 60
    return when {
        days > 0 -> "in ${days}d ${hours}h"
        hours > 0 -> "in ${hours}h ${mins}m"
        else -> "in ${mins}m"
    }
}

// ---------- NEWS TAB ----------

@Composable
fun NewsScreen() {
    val context = LocalContext.current
    var newsList by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            newsList = withContext(Dispatchers.IO) { Feeds.fetchNews() }
        } catch (e: Exception) {
            error = e.message ?: "Could not load news"
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Latest news",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { reload += 1 }) { Text("Refresh") }
        }

        when {
            loading -> CircularProgressIndicator(Modifier.padding(16.dp))
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            newsList.isEmpty() -> Text("No news found.")
            else -> LazyColumn {
                items(newsList) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { openUrl(context, item.link) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${item.source} · ${item.date}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- RELEASES TAB ----------

@Composable
fun ReleasesScreen() {
    val context = LocalContext.current
    var selectedDay by remember { mutableStateOf(LocalDate.now().dayOfWeek) }
    var anime by remember { mutableStateOf<List<AnimeSlot>>(emptyList()) }
    var manga by remember { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var loadingAnime by remember { mutableStateOf(true) }
    var loadingManga by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(selectedDay) {
        loadingAnime = true
        anime = try {
            withContext(Dispatchers.IO) { Feeds.fetchSchedule(selectedDay) }
        } catch (e: Exception) {
            emptyList()
        }
        loadingAnime = false
    }

    LaunchedEffect(Unit) {
        manga = try {
            withContext(Dispatchers.IO) { Feeds.fetchMangaChapters() }
        } catch (e: Exception) {
            emptyList()
        }
        loadingManga = false
    }

    // Refresh countdowns every 30 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text("Anime airing", style = MaterialTheme.typography.titleLarge)
            LazyRow(Modifier.padding(vertical = 8.dp)) {
                items(DayOfWeek.values().toList()) { day ->
                    FilterChip(
                        selected = day == selectedDay,
                        onClick = { selectedDay = day },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
        }

        if (loadingAnime) {
            item { CircularProgressIndicator(Modifier.padding(8.dp)) }
        } else if (anime.isEmpty()) {
            item { Text("No anime found for this day (or failed to load).") }
        }

        items(anime) { slot ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { openUrl(context, slot.url) }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(slot.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        slot.nextAiring.format(DateTimeFormatter.ofPattern("EEE HH:mm")),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        countdown(slot.nextAiring, now),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text("Latest manga & manhwa chapters", style = MaterialTheme.typography.titleLarge)
            Text(
                "Community translations from MangaDex. Upcoming dates aren't published, so these are the newest releases.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (loadingManga) {
            item { CircularProgressIndicator(Modifier.padding(8.dp)) }
        }

        items(manga) { ch ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { openUrl(context, ch.link) }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(ch.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Chapter ${ch.chapter} · ${ch.readableAt.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
