package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** Monthly timeline: loads month buckets immediately, and fetches each month's assets lazily when near the viewport. */
class TimelineState(val filter: Filter, newestFirst: Boolean, private val scope: CoroutineScope) : ImageSource {
    var newestFirst by mutableStateOf(newestFirst)
        private set
    var months by mutableStateOf<List<Month>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    val loaded = mutableStateMapOf<String, List<Asset>>()
    private val inflight = HashSet<String>()
    private var startOffsets = IntArray(0)
    private var totalCount = 0
    val grid = GridState()
    private val rowCache = HashMap<String, List<LayoutRow>>()
    /** Cached grid rows per month (header + photos) so loading one month never rebuilds others. */
    private val gridRowCache = HashMap<String, List<GridRow>>()

    /** Partner-photos setting with which the current list was loaded (reloads if toggled). */
    private var withPartners = Settings.partners

    init { start() }

    private fun start() {
        loading = true
        withPartners = Settings.partners
        scope.launch {
            runCatching { Immich.months(filter, newestFirst) }
                .onSuccess { months = it; computeOffsets(); error = null }
                .onFailure { error = it.message; Log.w("Photos", "months", it) }
            loading = false
        }
    }

    fun flipOrder() = setOrder(!newestFirst)

    fun setOrder(newest: Boolean) {
        newestFirst = newest
        reload()
    }

    /** Clear caches and reload from scratch (order or partner setting changed). */
    fun reload() {
        loaded.clear(); rowCache.clear(); gridRowCache.clear(); inflight.clear(); months = emptyList()
        grid.lastFocused = null
        scope.launch { grid.list.scrollToItem(0) }
        start()
    }

    /** Apply changed Timeline settings to an existing root timeline (main timeline / favorites) if needed. */
    fun applySettings() {
        if (filter.albumId == null && filter.personId == null && newestFirst != Settings.newestFirst) { setOrder(Settings.newestFirst); return }
        if (filter.albumId == null && filter.personId == null && !filter.favorites && withPartners != Settings.partners) reload()
    }

    private fun computeOffsets() {
        startOffsets = IntArray(months.size)
        var s = 0
        months.forEachIndexed { i, m -> startOffsets[i] = s; s += m.count }
        totalCount = s
    }

    fun loadMonth(m: String) {
        if (loaded.containsKey(m) || !inflight.add(m)) return
        scope.launch {
            runCatching { Immich.month(filter, newestFirst, m) }
                .onSuccess { a ->
                    // Pre-decode ThumbHashes for the first rows of the month (avoid decoding 600 at once to prevent GC/eviction)
                    withContext(Dispatchers.Default) { a.take(60).forEach { x -> x.thumbhash?.let(ThumbHash::bitmap) } }
                    val i = months.indexOfFirst { it.key == m }
                    if (i >= 0 && months[i].count != a.size) {
                        months = months.toMutableList().also { l -> l[i] = Month(m, a.size) }; computeOffsets()
                    }
                    loaded[m] = a
                }
                .onFailure { Log.w("Photos", "month $m", it) }
            inflight.remove(m)
        }
    }

    fun layoutRows(m: Month, width: Float, height: Float): List<LayoutRow>? {
        val a = loaded[m.key] ?: return null
        return rowCache.getOrPut("${m.key}|$width|$height") {
            PhotoLayout.rows(m.key, PhotoLayout.byDay(a, withYear = false), width, height, GAP, GROUP_GAP)
        }
    }

    /** Grid rows across all months; [height] is row height from Settings (small / normal / large). */
    fun gridRows(width: Float, height: Float): Pair<List<GridRow>, IntArray> {
        val out = ArrayList<GridRow>()
        val monthOfRow = ArrayList<Int>()
        months.forEachIndexed { i, m ->
            val isLoaded = loaded.containsKey(m.key)
            val r = gridRowCache.getOrPut("${m.key}|$width|$height|$isLoaded|${m.count}") {
                val hdr = HeaderRow("k-${m.key}", monthTitle(m.key), Language.plural(R.plurals.items, m.count, m.count))
                val rows = layoutRows(m, width, height)
                if (rows == null) listOf(hdr, PlaceholderRow("w-${m.key}", m.key, PhotoLayout.estimateHeight(m.count, width, height, HEADER_HEIGHT)))
                else listOf(hdr) + rows.map { PhotoGridRow(it) }
            }
            out.addAll(r)
            repeat(r.size) { monthOfRow.add(i) }
        }
        return out to monthOfRow.toIntArray()
    }

    override val count get() = totalCount
    override fun at(i: Int): Asset? {
        if (i < 0 || i >= totalCount) return null
        var lo = 0; var hi = startOffsets.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (startOffsets[mid] <= i) lo = mid else hi = mid - 1 }
        val m = months[lo]
        val l = loaded[m.key] ?: run { loadMonth(m.key); return null }
        // Prefetch the adjacent month near the boundary so viewer swiping stays seamless
        months.getOrNull(lo + 1)?.let { if (i - startOffsets[lo] > m.count - 6) loadMonth(it.key) }
        return l.getOrNull(i - startOffsets[lo])
    }

    fun indexOf(a: Asset): Int {
        months.forEachIndexed { i, m -> loaded[m.key]?.indexOfFirst { it.id == a.id }?.takeIf { it >= 0 }?.let { return startOffsets[i] + it } }
        return -1
    }

    fun startOfMonth(monthIndex: Int) = startOffsets.getOrElse(monthIndex) { 0 }
}

@Composable
fun TimelineScreen(screen: Screen.Timeline, nav: Nav) {
    val state = screen.state
    val scope = rememberCoroutineScope()
    // Root timeline uses the selected launcher app title even if the screen existed before appearance changed
    val title = if (screen.withMemories) stringResource(Settings.appearance.label) else screen.title
    var datePickerOpen by remember { mutableStateOf(false) }
    var jumpLabel by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val dateBtn = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(datePickerOpen) { if (wasOpen && !datePickerOpen && state.grid.focusTarget == null) { delay(16); runCatching { dateBtn.requestFocus() } }; wasOpen = datePickerOpen }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        // Read rowHeight here so changing Photo size rebuilds rows once
        val rowHeight = Settings.rowSize.height
        val (rows, monthOfRow) = remember(state.months, state.loaded.size, width, rowHeight) { state.gridRows(width, rowHeight) }
        val headerCount = 1 // top header item (title + controls + memories)

        // Load visible and upcoming months (short debounce skips transient months during fast scroll)
        LaunchedEffect(rows) {
            snapshotFlow { state.grid.list.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collectLatest { visible ->
                    if (visible.isEmpty()) return@collectLatest
                    delay(80)
                    val from = (visible.first() - headerCount).coerceAtLeast(0)
                    val to = (visible.last() - headerCount).coerceAtMost(monthOfRow.size - 1)
                    if (to < 0) { state.months.firstOrNull()?.let { state.loadMonth(it.key) }; return@collectLatest }
                    val mFrom = monthOfRow.getOrElse(from) { 0 }
                    val mTo = monthOfRow.getOrElse(to) { 0 }
                    for (mi in (mFrom - 1)..(mTo + 2)) state.months.getOrNull(mi)?.let { state.loadMonth(it.key) }
                }
        }
        // First visit: focus first photo; returning from another section: focus last selected photo
        LaunchedEffect(state.months.isNotEmpty(), state.loaded.isNotEmpty()) {
            // Brief delay prevents Android from focusing the navigation drawer on initial window attach
            delay(400)
            if (state.grid.focusTarget == null) {
                val last = state.grid.lastFocused
                if (last != null) { state.grid.focusTarget = last.id; return@LaunchedEffect }
                val m = state.months.firstOrNull() ?: return@LaunchedEffect
                state.loaded[m.key]?.firstOrNull()?.let { state.grid.focusTarget = it.id }
            }
        }

        val currentRows = rememberUpdatedState(rows)
        fun jumpToMonth(monthIndex: Int) {
            val m = state.months.getOrNull(monthIndex) ?: return
            jumpLabel = monthTitle(m.key)
            state.loadMonth(m.key)
            scope.launch {
                val i = currentRows.value.indexOfFirst { it.key == "k-${m.key}" }
                Log.i("Photos", "jump to ${m.key}: row $i")
                if (i >= 0) state.grid.list.scrollToItem(i + headerCount)
                // Wait for month to arrive, then focus its first photo
                repeat(100) {
                    state.loaded[m.key]?.firstOrNull()?.let { state.grid.focusTarget = it.id; return@launch }
                    delay(30)
                }
            }
        }
        val currentMonth by remember(monthOfRow) {
            derivedStateOf { monthOfRow.getOrElse((state.grid.list.firstVisibleItemIndex - headerCount + 1).coerceAtLeast(0)) { 0 } }
        }

        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    // FF / RW: jump to next / previous month
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jumpToMonth(currentMonth + 1); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { jumpToMonth((currentMonth - 1).coerceAtLeast(0)); true }
                    // Play/Pause: start slideshow from the focused photo
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val a = state.grid.lastFocused ?: state.loaded[state.months.firstOrNull()?.key ?: ""]?.firstOrNull()
                        a?.let { x -> state.indexOf(x).takeIf { it >= 0 }?.let { nav.view(state, it, title, true, state.grid) } }
                        true
                    }
                    else -> false
                }
            },
        ) {
            when {
                state.error != null && state.months.isEmpty() -> EmptyMessage(stringResource(R.string.immich_unreachable), state.error, Icons.Filled.CloudOff)
                !state.loading && state.months.isEmpty() -> EmptyMessage(
                    stringResource(if (screen.state.filter.favorites) R.string.no_favorites_yet else R.string.no_photos_yet),
                    if (screen.state.filter.favorites) stringResource(R.string.favorites_tip) else null,
                    if (screen.state.filter.favorites) Icons.Filled.FavoriteBorder else Icons.Filled.PhotoLibrary,
                )
            }
            PhotoGrid(
                rows = rows,
                state = state.grid,
                onClick = { a -> state.indexOf(a).takeIf { it >= 0 }?.let { nav.view(state, it, title, false, state.grid) } },
                onPlaceholder = state::loadMonth,
                headerCount = headerCount,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "top", contentType = "top") {
                    Column {
                        // Optional greeting above the main timeline: automatic ("Good evening, Anna") or custom text
                        val greeting = if (screen.withMemories) greetingText() else null
                        ScreenHeader(greeting ?: title, if (greeting != null) title else screen.sub) {
                            ChoiceChip(
                                selected = false, onClick = { state.flipOrder() }, modifier = Modifier.focusRequester(first),
                                leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.width(18.dp)) },
                                text = stringResource(if (state.newestFirst) R.string.newest_first else R.string.oldest_first),
                            )
                            ChoiceChip(
                                selected = false, onClick = { datePickerOpen = true }, modifier = Modifier.focusRequester(dateBtn),
                                leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.width(18.dp)) },
                                text = stringResource(R.string.go_to_date),
                            )
                        }
                        if (screen.withMemories && Settings.memories) MemoriesRow(nav)
                    }
                }
            }
            TimelineScrollBar(state, monthOfRow, headerCount, currentMonth, Modifier.align(Alignment.CenterEnd), goTo = ::jumpToMonth) {
                // Cancelled on scrollbar: restore focus to last photo or first visible month's first photo
                val target = state.grid.lastFocused?.id ?: state.months.getOrNull(currentMonth)?.let { state.loaded[it.key]?.firstOrNull()?.id }
                state.grid.focusTarget = target
            }
            AnimatedVisibility(jumpLabel != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    jumpLabel.orEmpty(), style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
            LaunchedEffect(jumpLabel) { if (jumpLabel != null) { delay(900); jumpLabel = null } }
        }
        if (datePickerOpen) DatePickerOverlay(state, close = { datePickerOpen = false }, pick = { datePickerOpen = false; jumpToMonth(it) })
    }
}

/** Greeting according to Settings; first name comes from `/users/me`, time of day from the local clock. */
@Composable
fun greetingText(): String? {
    val g = Settings.greeting
    if (g == Greeting.OFF) return null
    if (g == Greeting.CUSTOM) return Settings.greetingText.trim().ifEmpty { null }
    LaunchedEffect(Immich.state) { Immich.loadMe() }
    val name = Immich.me?.name?.trim()?.substringBefore(' ')?.ifEmpty { null }
    val hour = LocalTime.now().hour
    val part = stringResource(when (hour) { in 6..11 -> R.string.greeting_morning; in 12..17 -> R.string.greeting_afternoon; in 18..23 -> R.string.greeting_evening; else -> R.string.greeting_night })
    return if (name != null) stringResource(R.string.greeting_with_name, part, name) else part
}

/**
 * Timeline scrollbar: estimates position from month item counts (since months load lazily):
 * first visible row -> month -> progress within that month -> photo index in the library.
 */
@Composable
private fun TimelineScrollBar(state: TimelineState, monthOfRow: IntArray, headerCount: Int, monthIndex: Int, modifier: Modifier, goTo: (Int) -> Unit, back: () -> Unit) {
    val list = state.grid.list
    val months = state.months
    if (state.count == 0 || months.isEmpty()) return
    // First grid row index for each month
    val monthStartRow = remember(monthOfRow) {
        val out = IntArray(months.size) { monthOfRow.size }
        for (r in monthOfRow.indices.reversed()) out[monthOfRow[r]] = r
        for (m in out.indices.reversed()) if (out[m] == monthOfRow.size && m + 1 < out.size) out[m] = out[m + 1]
        out
    }
    fun photoNumber(): Int {
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull() ?: return 0
        if (!list.canScrollForward) return state.count - 1
        val part = if (first.size > 0) (-first.offset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
        val r = (first.index - headerCount + part).coerceAtLeast(0f)
        val m = monthOfRow.getOrElse(r.toInt()) { 0 }.coerceIn(0, months.lastIndex)
        val start = monthStartRow[m]
        val end = if (m + 1 < monthStartRow.size) monthStartRow[m + 1] else monthOfRow.size
        val inside = if (end > start) ((r - start) / (end - start)).coerceIn(0f, 1f) else 0f
        return (state.startOfMonth(m) + inside * months[m].count).toInt().coerceIn(0, state.count - 1)
    }
    // Year ticks along the track (follows newest-first or oldest-first order)
    val years = remember(months) {
        val out = ArrayList<Pair<Float, String>>()
        var prev = ""
        months.forEachIndexed { i, m ->
            val y = m.key.take(4)
            if (y != prev) { prev = y; if (y.toIntOrNull()?.let { it > 1970 } == true) out.add(state.startOfMonth(i).toFloat() / state.count to y) }
        }
        out
    }
    val current = rememberUpdatedState(monthIndex)
    val goNow = rememberUpdatedState(goTo); val backNow = rememberUpdatedState(back)
    val steps = remember(months) {
        fun year(i: Int) = months[i].key.take(4)
        ScrollSteps(
            count = months.size,
            fraction = { i -> state.startOfMonth(i).toFloat() / (state.count - 1).coerceAtLeast(1) },
            label = { i -> monthTitle(months[i].key) },
            sub = { i -> Language.plural(R.plurals.items, months[i].count, months[i].count) },
            largeStep = { i, dir ->
                // Jump to the first month (in list order) of the previous/next year
                var j = i
                if (dir > 0) { while (j < months.lastIndex && year(j) == year(i)) j++ }
                else { while (j > 0 && year(j) == year(i)) j--; while (j > 0 && year(j - 1) == year(j)) j-- }
                j
            },
            current = { current.value },
            goTo = { goNow.value(it) },
            back = { backNow.value() },
            perMonth = true,
        )
    }
    ScrollBar(
        fraction = { photoNumber().toFloat() / (state.count - 1).coerceAtLeast(1) },
        movement = { list.firstVisibleItemIndex * 7919 + list.firstVisibleItemScrollOffset / 200 },
        scrollable = { list.canScrollForward || list.canScrollBackward },
        label = { months.getOrNull(monthIndex)?.let { monthTitle(it.key) } },
        sub = { "${Language.number(photoNumber() + 1)} / ${Language.number(state.count)}" },
        markers = years,
        modifier = modifier,
        steps = steps,
    )
}

/** Pick year, then month. */
@Composable
private fun DatePickerOverlay(state: TimelineState, close: () -> Unit, pick: (Int) -> Unit) {
    var year by remember { mutableStateOf<Int?>(null) }
    val byYear = remember(state.months) {
        state.months.withIndex().groupBy { it.value.key.take(4).toIntOrNull() ?: 0 }
    }
    BackHandler { if (year != null) year = null else close() }
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xF00A0A0A)).trapFocus().padding(48.dp)) {
        Column {
            Text(year?.toString() ?: stringResource(R.string.go_to_year), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            val y = year
            if (y == null) {
                LazyVerticalGrid(GridCells.Fixed(8), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(byYear.keys.toList(), key = { it }) { yr ->
                        val n = byYear[yr].orEmpty().sumOf { it.value.count }
                        ActionButton(if (yr <= 1970) stringResource(R.string.no_date) else "$yr", Language.number(n), if (yr == byYear.keys.first()) Modifier.focusRequester(fr) else Modifier) {
                            if (byYear[yr].orEmpty().size == 1) pick(byYear[yr]!!.first().index) else year = yr
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(byYear[y].orEmpty(), key = { it.value.key }) { (i, m) ->
                        ActionButton(monthTitle(m.key).substringBefore(' '), Language.number(m.count), if (i == byYear[y]!!.first().index) Modifier.focusRequester(fr) else Modifier) { pick(i) }
                    }
                }
            }
        }
    }
    LaunchedEffect(year) { delay(50); runCatching { fr.requestFocus() } }
}

@Composable
fun ActionButton(title: String, sub: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Explicit text colors so typography styles don't override Surface's focusedContentColor
    var focus by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, modifier = modifier.height(64.dp).onFocusChanged { focus = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surface, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (focus) AppColors.onPrimary.copy(alpha = 0.8f) else AppColors.muted, maxLines = 1) }
        }
    }
}

@Composable
fun ScreenHeader(title: String, sub: String?, buttons: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
    }
}

/** Centered empty/error state with a large muted icon. */
@Composable
fun EmptyMessage(title: String, text: String?, icon: ImageVector = Icons.Filled.PhotoLibrary) {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(bottom = 18.dp).background(AppColors.surface, CircleShape).padding(22.dp)) {
            Icon(icon, null, Modifier.width(40.dp).height(40.dp), tint = AppColors.muted)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** "On this day" memories row at the top of the timeline, matching Immich. */
@Composable
fun MemoriesRow(nav: Nav) {
    val list = Memories.today
    LaunchedEffect(Unit) { Memories.loadToday() }
    if (list.isEmpty()) return
    // Negative start offset + horizontal padding keeps the focused card's scaled border inside bounds
    LazyRow(
        Modifier.fillMaxWidth().offset(x = (-10).dp).padding(top = 2.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(list, key = { it.id }) { m -> MemoryCard(m, nav) }
    }
}

@Composable
fun MemoryCard(m: Memory, nav: Nav, width: Float = 168f) {
    val cover = m.assets[m.assets.size / 2]
    Card(
        onClick = { nav.view(ListSource(m.assets), 0, yearsAgo(m.yearsAgo), true, null) },
        modifier = Modifier.width(width.dp).height((width * 1.25f).dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxSize().background(AppColors.surfaceHigh)) {
            AsyncImage(cardImage(cover.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(bottomScrim))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text("${m.year}", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text(yearsAgo(m.yearsAgo), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text(pluralStringResource(R.plurals.photos, m.assets.size, Language.number(m.assets.size)), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
            }
        }
    }
}

/** Today's memories, fetched once per calendar day. */
object Memories {
    var today by mutableStateOf<List<Memory>>(emptyList())
        private set
    private var fetchedDay: LocalDate? = null

    suspend fun loadToday() {
        val d = LocalDate.now()
        if (fetchedDay == d) return
        runCatching { Immich.memories(d) }.onSuccess { today = it; fetchedDay = d }.onFailure { Log.w("Photos", "memories", it) }
    }
}
