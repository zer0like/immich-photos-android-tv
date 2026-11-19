package com.immichclient.tv

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Screensaver (DreamService) matching the viewer's aesthetic: full-screen photo (crossfade, Ken Burns), clock + date
 * top-left, country outline map with dot bottom-right (CountryMap.kt), and a countdown progress ring + date/place
 * bottom-left. Source, interval, and HUD elements are configurable (Settings -> Screensaver). Left/Right = prev/next
 * photo; any other key (except volume) wakes/closes the screensaver.
 *
 * Memory-conscious (runs alongside other apps): decodes previews at screen size via the shared Coil cache and never
 * retains more than three images at once (current, outgoing crossfade, and prefetched next).
 */

/** UI state for a single screensaver run. */
class DreamState {
    var current by mutableStateOf<Asset?>(null)
    /** Increments on every photo change so the countdown ring restarts. */
    var round by mutableIntStateOf(0)
    /** Center message shown only while no photo is on screen yet. */
    var message by mutableStateOf<Int?>(null)
    /** Fetch failed while a photo is already visible: keep showing the photo with a small offline cloud icon. */
    var offlineWarning by mutableStateOf(false)
    /** Remote key request: -1 previous, +1 next (conflated so only the latest counts). */
    val stepRequests = Channel<Int>(Channel.CONFLATED)
    val history = ArrayList<Asset>()
    var historyIndex = -1
    /** Test flag only (PreviewActivity, `--es test_error pairing|key|net|empty`): simulates that error. */
    var testError: String? = null
    private var lastKeyTime = 0L

    /** Left/Right key: throttled to 1x per 600 ms so rapid presses don't pile up large preview decodes. */
    fun sendStep(dir: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyTime < 600) return
        lastKeyTime = now
        stepRequests.trySend(dir)
    }
}

/**
 * Feeds photos according to settings: All (random batches via `/search/random`), Albums / Favorites / People
 * (weighted random month -> random photo, keeping at most 3 months cached), or Today's memories. Videos are skipped;
 * recently shown photos are avoided. Falls back to random photos if the chosen source is empty.
 */
class DreamRepository {
    private val queue = ArrayDeque<Asset>()
    private val recent = ArrayDeque<String>()
    private var months: Map<Filter, List<Month>>? = null
    private var monthsSig: String? = null
    /** asset ID -> name of the selected album/person it came from (for source attribution). */
    private val sourceNameById = HashMap<String, String>()
    // Sequential order: sorted month keys, cursor, and current month's buffer
    private var seqMonths: List<String>? = null
    private var seqFilterMonths: Map<Filter, Set<String>> = emptyMap()
    private var seqSig: String? = null
    private var seqIdx = 0
    private var resumeAfter: LocalDateTime? = null
    private val seqBuf = ArrayDeque<Asset>()
    private val monthCache = object : LinkedHashMap<String, List<Asset>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Asset>>?) = size > 3
    }
    private var memories: List<Asset>? = null
    private var memoryDay: LocalDate? = null
    private var memoryIndex = 0
    /** Photos actually from the configured source (not the fallback): only those get a source label. */
    private val sourceIds = LinkedHashSet<String>()
    /** Pre-selected and prefetched next asset, see [prefetchNext]. */
    private var lookahead: Asset? = null
    /** Prevents next() and prefetchNext() from fetching overlapping batches concurrently. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    fun isFromSource(id: String) = id in sourceIds
    fun sourceNameOf(id: String): String? = sourceNameById[id]

    suspend fun next(): Asset? = lock.withLock {
        lookahead?.let { lookahead = null; return@withLock it }
        fetchNext()
    }

    /** Pick the next photo ahead of time and enqueue its preview while the current photo is shown. */
    suspend fun prefetchNext(ctx: Context) = lock.withLock {
        if (lookahead != null) return@withLock
        val a = runCatching { fetchNext() }.onFailure { Log.w("Photos", "screensaver prefetch", it) }.getOrNull() ?: return@withLock
        lookahead = a
        SingletonImageLoader.get(ctx).enqueue(previewRequest(ctx, a.id))
    }

    /** Source as timeline filters with optional display names; empty = nothing selected yet. */
    private fun sourceFilters(): List<Pair<Filter, String?>> {
        val s = Settings
        val albums = s.pairs(s.dreamAlbums, s.dreamAlbumNames).map { (id, n) -> Filter(albumId = id) to n.ifBlank { null } }
        val people = s.pairs(s.dreamPeople, s.dreamPersonNames).map { (id, n) -> Filter(personId = id) to n.ifBlank { null } }
        return when (s.dreamSource) {
            DreamSource.ALL -> listOf(Filter() to null)
            DreamSource.MEMORIES -> emptyList()
            DreamSource.ALBUM -> albums
            DreamSource.FAVORITES -> listOf(Filter(favorites = true) to null)
            DreamSource.PERSON -> people
            DreamSource.COMBINED -> albums + people
        }
    }

    private suspend fun fetchNext(): Asset? {
        if (Settings.dreamSource == DreamSource.MEMORIES) return markSource(fromMemories()) ?: fromRandomBatch()
        val fs = sourceFilters()
        if (fs.isEmpty()) return fromRandomBatch() // nothing chosen yet: fall back to random photos
        if (Settings.dreamOrder != DreamOrder.RANDOM) return markSource(fromSequence(fs)) ?: fromRandomBatch()
        if (Settings.dreamSource == DreamSource.ALL) return fromRandomBatch()
        return markSource(fromMonths(fs)) ?: fromRandomBatch()
    }

    private fun yearOf(m: Month) = m.key.take(4).toIntOrNull() ?: 0

    /** Month lists across all filters (from the chosen year); ignores failing filters (deleted album) if others succeed. */
    private suspend fun monthsOf(fs: List<Filter>): Map<Filter, List<Month>> {
        val fromYear = Settings.dreamFromYear
        val per = LinkedHashMap<Filter, List<Month>>()
        var err: Throwable? = null
        for (f in fs) runCatching { per[f] = Immich.months(f, true).filter { it.count > 0 && yearOf(it) >= fromYear } }.onFailure { err = it }
        if (per.isEmpty()) err?.let { throw it }
        return per
    }

    /**
     * Sequential order (oldest/newest first) across all filters combined: month by month, merged and sorted by date.
     * Wraps around at the end and saves the cursor so the next screensaver run continues from there.
     */
    private suspend fun fromSequence(fs: List<Pair<Filter, String?>>): Asset? {
        val oldest = Settings.dreamOrder == DreamOrder.OLDEST
        val sig = "${Settings.dreamSource}|${fs.map { it.first }}|${Settings.dreamFromYear}|$oldest".hashCode().toString()
        if (seqSig != sig || seqMonths == null) {
            val per = monthsOf(fs.map { it.first })
            seqFilterMonths = per.mapValues { (_, ms) -> ms.map { it.key }.toSet() }
            val keys = per.values.flatten().map { it.key }.distinct().sorted().let { if (oldest) it else it.reversed() }
            seqMonths = keys; seqSig = sig; seqBuf.clear(); seqIdx = 0; resumeAfter = null
            val saved = Settings.dreamPosition.split('\t')
            if (saved.getOrNull(0) == sig) saved.getOrNull(1)?.let { d ->
                runCatching { LocalDateTime.parse(d) }.getOrNull()?.let { dt ->
                    resumeAfter = dt
                    val ym = d.take(7)
                    seqIdx = keys.indexOfFirst { if (oldest) it.take(7) >= ym else it.take(7) <= ym }.coerceAtLeast(0)
                }
            }
            Log.i("Photos", "screensaver: sequential, ${keys.size} months, starting at ${keys.getOrNull(seqIdx)} (after $resumeAfter)")
        }
        val ms = seqMonths.orEmpty()
        if (ms.isEmpty()) return null
        var wraps = 0
        while (seqBuf.isEmpty()) {
            if (seqIdx >= ms.size) { seqIdx = 0; resumeAfter = null; if (++wraps > 1) return null }
            val key = ms[seqIdx++]
            val merged = LinkedHashMap<String, Pair<Asset, String?>>()
            for ((f, name) in fs) if (key in seqFilterMonths[f].orEmpty())
                Immich.month(f, true, key).filter { !it.video }.forEach { merged.putIfAbsent(it.id, it to name) }
            var photos = merged.values.sortedBy { it.first.date }.let { if (oldest) it else it.reversed() }
            resumeAfter?.let { h -> photos = photos.filter { if (oldest) it.first.date > h else it.first.date < h } }
            resumeAfter = null
            for ((a, name) in photos) { name?.let { sourceNameById[a.id] = it }; seqBuf.addLast(a) }
        }
        val a = seqBuf.removeFirst()
        Settings.dreamPosition = "$sig\t${a.date}"
        return a
    }

    private fun markSource(a: Asset?): Asset? {
        if (a != null) { sourceIds.add(a.id); if (sourceIds.size > 120) sourceIds.remove(sourceIds.first()).also { if (sourceNameById.size > 400) sourceNameById.clear() } }
        return a
    }

    private suspend fun fromRandomBatch(): Asset? {
        if (queue.size < 3) queue.addAll(Immich.random(40, Settings.dreamFromYear).filter { !it.video && it.id !in recent && queue.none { w -> w.id == it.id } })
        return queue.removeFirstOrNull()?.let { rememberRecent(it) }
    }

    /** Random photo from one or more sources, weighted by month photo count (larger albums appear proportionally more often). */
    private suspend fun fromMonths(sources: List<Pair<Filter, String?>>): Asset? {
        val fs = sources.map { it.first }
        val sig = "$fs|${Settings.dreamFromYear}"
        if (monthsSig != sig || months == null) {
            months = monthsOf(fs); monthsSig = sig; monthCache.clear(); recent.clear()
        }
        val all = months.orEmpty().flatMap { (f, ms) -> ms.map { f to it } }
        val total = all.sumOf { it.second.count }
        if (total == 0) return null
        var last: Asset? = null
        repeat(10) {
            var r = Random.nextInt(total)
            val (f, m) = all.first { r -= it.second.count; r < 0 }
            val list = monthCache.getOrPut("$f|${m.key}") { Immich.month(f, true, m.key) }
            val a = list.filter { !it.video }.randomOrNull() ?: return@repeat
            last = a
            sources.firstOrNull { it.first == f }?.second?.let { sourceNameById[a.id] = it }
            if (a.id !in recent) return rememberRecent(a, total)
        }
        return last?.let { rememberRecent(it, total) }
    }

    private suspend fun fromMemories(): Asset? {
        val today = LocalDate.now()
        if (memories == null || memoryDay != today) {
            memories = Immich.memories(today).flatMap { it.assets }.filter { !it.video }.shuffled()
            memoryDay = today; memoryIndex = 0
        }
        val l = memories ?: return null
        if (l.isEmpty()) return null
        val fromYear = Settings.dreamFromYear
        repeat(l.size) { val a = l[memoryIndex++ % l.size]; if (a.date.year >= fromYear) return a }
        return null
    }

    private fun rememberRecent(a: Asset, total: Int = Int.MAX_VALUE): Asset {
        recent.addLast(a.id)
        val max = (total / 2).coerceIn(2, 80)
        while (recent.size > max) recent.removeFirst()
        return a
    }
}

/** Map fetch error -> user message: rejected key, deleted source, or network unreachable. */
private fun errorMessageFor(e: Throwable): Int {
    val m = e.message.orEmpty()
    return when {
        m.startsWith("Immich 401") || m.startsWith("Immich 403") -> R.string.dream_key_error
        m.startsWith("Immich 4") -> R.string.dream_no_photos
        else -> R.string.dream_no_connection
    }
}

/** Source label shown bottom-left (album name, "3 years ago", ...); null for "All photos" or fallback photos. */
@Composable
private fun sourceLabel(a: Asset, repo: DreamRepository): String? {
    if (Settings.dreamSource == DreamSource.ALL || !repo.isFromSource(a.id)) return null
    return when (Settings.dreamSource) {
        DreamSource.ALL -> null
        DreamSource.ALBUM -> repo.sourceNameOf(a.id) ?: Settings.dreamAlbumNames.singleOrNull()
        DreamSource.PERSON -> repo.sourceNameOf(a.id) ?: Settings.dreamPersonNames.singleOrNull()
        DreamSource.COMBINED -> repo.sourceNameOf(a.id)
        DreamSource.FAVORITES -> stringResource(R.string.section_favorites)
        DreamSource.MEMORIES -> (LocalDate.now().year - a.date.year).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.years_ago, it, it) }
    }
}

private val TEXT_SHADOW = Shadow(Color(0xD9000000), Offset(0f, 2f), 10f)

/** Gentle pixel-shift of clock/text (a few dp every 4 minutes) to prevent OLED burn-in. */
private val DRIFT = listOf(Offset(0f, 0f), Offset(10f, 6f), Offset(-6f, 12f), Offset(12f, -4f), Offset(-10f, -8f), Offset(4f, 10f))

/** Trim memory cache after each transition to ~3 previews (current, previous, next); rest stays on disk. */
private const val MEMORY_LIMIT = 24L * 1024 * 1024

@Composable
fun DreamScreen(state: DreamState) {
    val ctx = LocalContext.current
    val repo = remember { DreamRepository() }
    val seconds = Settings.dreamSeconds

    // Main loop: pick photo -> fetch preview -> display -> wait for interval or remote key
    LaunchedEffect(Unit) {
        if (Immich.key.isBlank() || Immich.server.isBlank() || state.testError == "pairing") {
            state.message = R.string.dream_not_paired; return@LaunchedEffect
        }
        val loader = SingletonImageLoader.get(ctx)
        suspend fun fetchNext(): Asset? = when (state.testError) {
            "empty" -> null
            "net" -> throw java.net.ConnectException("test")
            "key" -> throw java.io.IOException("Immich 401: test")
            else -> repo.next()
        }
        fun onFailure(m: Int) { if (state.current == null) state.message = m else state.offlineWarning = true }
        var direction = 1
        while (isActive) {
            val g = state.history
            var waitMs = 20_000L
            val a: Asset? = when {
                direction < 0 && state.historyIndex > 0 -> { state.historyIndex--; g[state.historyIndex] }
                direction < 0 -> state.current // already at oldest in history: stay put
                direction > 0 && state.historyIndex < g.lastIndex -> { state.historyIndex++; g[state.historyIndex] }
                else -> runCatching { fetchNext() }.fold(
                    onSuccess = { n -> n?.also { g.add(it); if (g.size > 30) g.removeAt(0); state.historyIndex = g.lastIndex } },
                    onFailure = { e ->
                        Log.w("Photos", "screensaver: ${e.message}")
                        val m = errorMessageFor(e)
                        if (m != R.string.dream_no_connection) waitMs = 60_000L
                        onFailure(m); null
                    },
                )
            }
            if (a == null) {
                if (state.current == null && state.message == null) state.message = R.string.dream_no_photos
                direction = withTimeoutOrNull(waitMs) { state.stepRequests.receive() } ?: 1
                continue
            }
            if (a.id != state.current?.id) {
                // Pre-decode the preview (max 8 s) before swapping so we never show a blank/half-loaded frame
                val ok = withTimeoutOrNull(8_000) { runCatching { loader.execute(previewRequest(ctx, a.id)) is SuccessResult }.getOrDefault(false) } == true
                if (!ok) {
                    Log.w("Photos", "screensaver: preview ${a.id} failed to load")
                    onFailure(R.string.dream_no_connection)
                    direction = withTimeoutOrNull(waitMs) { state.stepRequests.receive() } ?: 1
                    continue
                }
                state.message = null
                state.offlineWarning = false
                state.current = a
                // After transition settles: trim cache and prefetch the following photo
                launch { delay(1800); loader.memoryCache?.trimToSize(MEMORY_LIMIT); repo.prefetchNext(ctx) }
            }
            state.round++
            direction = withTimeoutOrNull(Settings.dreamSeconds * 1000L) { state.stepRequests.receive() } ?: 1
        }
    }

    // Anti-burn-in: slowly shift static overlays by a few dp periodically
    val driftTarget by produceState(DRIFT[0]) { var i = 0; while (true) { delay(4 * 60_000L); i++; value = DRIFT[i % DRIFT.size] } }
    val drift by animateOffsetAsState(driftTarget, tween(4000), label = "drift")
    val shiftMod = Modifier.offset { IntOffset((drift.x * density).roundToInt(), (drift.y * density).roundToInt()) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val current = state.current
        val kenBurns = Settings.dreamKenBurns

        AnimatedContent(
            targetState = current, contentKey = { it?.id },
            // scale 1->1 holds the outgoing photo for 1.4 s while the incoming one (on top) fades over it in
            // DreamPhoto (via ModulateAlpha, avoiding an extra layer). Prevents any dark dip mid-crossfade.
            transitionSpec = { scaleIn(tween(1400), initialScale = 1f) togetherWith scaleOut(tween(1400), targetScale = 1f) },
            label = "dream",
        ) { a ->
            val alpha by transition.animateFloat(transitionSpec = { tween(1100) }, label = "alpha") { if (it == EnterExitState.PreEnter) 0f else 1f }
            if (a != null) DreamPhoto(a, active = a.id == current?.id, kenBurns = kenBurns, durationMs = seconds * 1000L + 2500, alpha = { alpha })
        }

        val dreamMap = Settings.dreamMap.scale
        if (dreamMap > 0f) CountryMapOverlay(target = current?.takeIf { it.lat != null && it.lon != null }, widthPx = widthPx, heightPx = heightPx, sizeScale = dreamMap)

        if (Settings.dreamClock) ClockOverlay(Modifier.align(Alignment.TopStart).then(shiftMod).padding(start = 48.dp, top = 36.dp))

        // Bottom-left: countdown ring + date and place/source
        if (current != null) Row(Modifier.align(Alignment.BottomStart).then(shiftMod).padding(start = 48.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            val ring = remember { Animatable(0f) }
            LaunchedEffect(state.round) { ring.snapTo(0f); if (state.round > 0) ring.animateTo(1f, tween(seconds * 1000, easing = LinearEasing)) }
            if (Settings.dreamRing) ProgressRing({ ring.value }, Modifier.size(22.dp))
            if (state.offlineWarning) Icon(Icons.Filled.CloudOff, null, Modifier.padding(start = if (Settings.dreamRing) 10.dp else 0.dp).size(20.dp), tint = Color(0xB3FFFFFF))
            if (Settings.dreamInfo) Column(Modifier.padding(start = if (Settings.dreamRing || state.offlineWarning) 16.dp else 0.dp)) {
                Text(current.date.format(DateTimeFmt.dayLong).replaceFirstChar { it.titlecase(Language.locale) }, style = TextStyle(fontFamily = Overpass, fontSize = 18.sp, color = Color.White, shadow = TEXT_SHADOW), maxLines = 1)
                // When country map is visible, city/country is already shown beside the map dot
                val place = listOfNotNull(current.city, current.country).joinToString(", ").takeIf { it.isNotEmpty() && !(Settings.dreamMap != MapSize.OFF && current.lat != null) }
                val source = sourceLabel(current, repo)
                // Other albums containing this photo (excluding the selected source album itself); when "Show other album"
                // is on, those replace the source label (e.g. "Birthday" instead of "Screensaver")
                val sourceAlbums = if (Settings.dreamSource == DreamSource.ALBUM || Settings.dreamSource == DreamSource.COMBINED) Settings.dreamAlbumNames.toSet() + listOfNotNull(source) else setOfNotNull(source)
                val otherAlbums = albumNames(current.id).filter { it !in sourceAlbums }
                val albums = otherAlbums.takeIf { it.isNotEmpty() }?.let(::albumLine)
                val shownSource = source.takeUnless { albums != null && Settings.dreamOtherAlbum }
                val line = listOfNotNull(place, shownSource, albums).joinToString("  ·  ")
                if (line.isNotEmpty()) Text(line, style = TextStyle(fontFamily = Overpass, fontSize = 15.sp, color = Color(0xD9FFFFFF), shadow = TEXT_SHADOW), maxLines = 1)
            }
        }

        if (current == null) state.message?.let { m ->
            Column(Modifier.align(Alignment.Center).then(shiftMod).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (m == R.string.dream_no_connection) Icons.Filled.CloudOff else Icons.Filled.PhotoLibrary, null, Modifier.size(44.dp), tint = AppColors.muted)
                Text(stringResource(m), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Clock + date in the app language; updates once per minute. */
@Composable
private fun ClockOverlay(modifier: Modifier) {
    val now by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now().withSecond(0).withNano(0); delay(1000) } }
    Column(modifier) {
        Text(now.format(DateTimeFmt.time), style = TextStyle(fontFamily = Overpass, fontSize = 64.sp, fontWeight = FontWeight(600), color = Color.White, shadow = TEXT_SHADOW), maxLines = 1)
        Text(now.format(DateTimeFmt.dayLong).replaceFirstChar { it.titlecase(Language.locale) }, style = TextStyle(fontFamily = Overpass, fontSize = 22.sp, color = Color(0xE6FFFFFF), shadow = TEXT_SHADOW), maxLines = 1)
    }
}

/**
 * Single photo: landscape shots near 3:2..16:9 fill the screen (cropped), others fit with a blurred ThumbHash backdrop.
 * Ken Burns: slow zoom + subtle horizontal pan only on the active photo (outgoing keeps its zoom so it doesn't snap).
 */
@Composable
private fun DreamPhoto(a: Asset, active: Boolean, kenBurns: Boolean, durationMs: Long, alpha: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        val fill = a.ratio in 1.45f..2.0f
        val zoom = remember { Animatable(1f) }
        val panDir = remember(a.id) { Random.nextInt(-1, 2) }
        LaunchedEffect(a.id, kenBurns) { if (kenBurns && active) zoom.animateTo(1.08f, tween(durationMs.toInt(), easing = LinearEasing)) }
        val mod = Modifier.fillMaxSize().graphicsLayer {
            val z = zoom.value
            scaleX = z; scaleY = z
            // Pan stays inside edges ((z-1)/2 slack on each side)
            translationX = (z - 1f) * size.width * 0.5f * panDir
        }
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null && !fill && Settings.blurredBackground) {
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            Image(hash, null, Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true), contentScale = ContentScale.FillBounds)
        }
        AsyncImage(previewImage(a.id), null, mod, contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
    }
}

/**
 * The system DreamService itself. Compose inside a DreamService needs LifecycleOwner, SavedStateRegistryOwner,
 * and ViewModelStoreOwner in the view tree; this service implements all three (attached = CREATED, dreaming = RESUMED).
 * Key events arrive via dispatchKeyEvent (interactive mode) rather than Compose focus.
 */
class PhotoDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val state = DreamState()

    override fun onCreate() {
        super.onCreate()
        // Runs in the same process as the app (required by Android 11+; see manifest). Free caches/MapView memory
        // before starting so the foreground dream process doesn't starve or trigger background kills.
        (application as? App)?.releaseMemory("screensaver")
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val view = ComposeView(Language.wrap(this))
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setContent { PhotosTheme { DreamScreen(state) } }
        setContentView(view)
        isAttached = true
        Log.i("Photos", "screensaver: started (${Settings.dreamSource}, ${Settings.dreamSeconds} s)")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) state.sendStep(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            // Let volume keys pass through without dismissing the screensaver
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
        }
        // Any other key: wake on KEY_UP so the release event doesn't leak into the launcher underneath and click a tile.
        // If held down or if wakeUp() is ignored by the system, force finish after 1.5 s so it never hangs.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) wakeLatestAfter(1500)
        if (event.action == KeyEvent.ACTION_UP) wake()
        return true
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var woken = false

    private fun wake() {
        if (woken) return
        woken = true
        Log.i("Photos", "screensaver: key pressed, waking up")
        runCatching { wakeUp() }.onFailure { Log.w("Photos", "screensaver wakeUp", it); finish() }
        // Safety net: if still attached after 2 s, finish forcibly
        handler.postDelayed({ if (isAttached) { Log.w("Photos", "screensaver: wakeUp stalled, finish()"); runCatching { finish() } } }, 2000)
    }

    private fun wakeLatestAfter(ms: Long) = handler.postDelayed({ if (!woken) wake() }, ms)

    private var isAttached = false

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        handler.removeCallbacksAndMessages(null)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDetachedFromWindow()
        Log.i("Photos", "screensaver: stopped")
    }
}

/** Settings -> Screensaver -> Preview: same screensaver UI in a regular Activity (also useful for testing). */
class PreviewActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immich.selectAccount(this, null)
        val state = DreamState().apply { testError = intent.getStringExtra("test_error") }
        setContent {
            PhotosTheme {
                Box(
                    Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key.nativeKeyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { state.sendStep(-1); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { state.sendStep(1); true }
                            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> false
                            else -> { finish(); true }
                        }
                    },
                ) { DreamScreen(state) }
            }
        }
    }
}
