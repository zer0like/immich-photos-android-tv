package com.immichclient.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Paginated result list (search, places): fetches the next page when approaching the bottom. */
class SearchResults(private val scope: CoroutineScope) {
    var assets by mutableStateOf<List<Asset>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)
    private var nextPage: Int? = null
    private var loader: (suspend (Int) -> Page)? = null
    private var job: Job? = null
    val grid = GridState()

    fun start(l: suspend (Int) -> Page) {
        job?.cancel()
        loader = l; assets = emptyList(); nextPage = 1; error = null; searched = true; loading = false
        scope.launch { grid.list.scrollToItem(0) }
        loadMore()
    }

    /** Reset back to initial suggestions state. */
    fun clear() {
        job?.cancel()
        loader = null; assets = emptyList(); nextPage = null; error = null; searched = false; loading = false
    }

    fun loadMore() {
        val p = nextPage ?: return
        val l = loader ?: return
        if (loading) return
        loading = true
        job = scope.launch {
            val out = runCatching { l(p) }
            if (loader !== l) return@launch // superseded by a newer search or clear()
            out.onSuccess { r -> assets = assets + r.assets.filter { a -> assets.none { it.id == a.id } }; nextPage = r.next }
                .onFailure { error = it.message; nextPage = null; Log.w("Photos", "search", it) }
            loading = false
        }
    }
}

private const val TOP_PEOPLE = 10

enum class SearchMode(@StringRes val label: Int) { SMART(R.string.mode_smart), TEXT(R.string.mode_text), FILENAME(R.string.mode_filename) }
enum class SearchType(@StringRes val label: Int, val video: Boolean?) { ALL(R.string.type_all, null), PHOTO(R.string.type_photos, false), VIDEO(R.string.type_videos, true) }
enum class SearchSort(@StringRes val label: Int) { RELEVANCE(R.string.sort_relevance), NEWEST(R.string.sort_newest), OLDEST(R.string.sort_oldest) }

class SearchState(scope: CoroutineScope) {
    var query by mutableStateOf("")
    var mode by mutableStateOf(SearchMode.SMART)
    var type by mutableStateOf(SearchType.ALL)
    var sort by mutableStateOf(SearchSort.RELEVANCE)
    val results = SearchResults(scope)
    /** Focus the first result once a new search completes. */
    var focusOnResult = false
    var people by mutableStateOf<List<Person>>(emptyList())

    init {
        scope.launch { runCatching { Immich.people() }.onSuccess { people = it } }
        scope.launch { Immich.warmSearch() }
    }

    /** Query-less filter by media type only (all photos / all videos by date). */
    val typeOnly get() = query.isBlank() && type != SearchType.ALL

    fun search(c: Context) {
        val t = query.trim()
        val ty = type
        if (t.isEmpty()) {
            // Blank text: with Photos/Videos selected, list all of that type by date; otherwise reset
            if (ty.video == null) { if (results.searched) results.clear(); return }
            if (sort == SearchSort.RELEVANCE) sort = SearchSort.NEWEST
            focusOnResult = true
            results.start { p -> Immich.searchText(null, "", p, ty.video) }
            return
        }
        RecentSearches.add(c, t)
        focusOnResult = true
        val m = mode
        results.start { p ->
            when (m) {
                SearchMode.SMART -> Immich.searchSmart(t, p, ty.video)
                SearchMode.TEXT -> Immich.searchText(t, "ocr", p, ty.video)
                SearchMode.FILENAME -> Immich.searchText(t, "originalFileName", p, ty.video)
            }
        }
    }
}

object RecentSearches {
    private const val MAX = 10
    fun list(c: Context): List<String> =
        c.getSharedPreferences("search", Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun add(c: Context, t: String) {
        val l = (listOf(t) + list(c).filter { !it.equals(t, true) }).take(MAX)
        c.getSharedPreferences("search", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
    }
}

@Composable
fun SearchScreen(state: SearchState, nav: Nav) {
    val ctx = LocalContext.current
    var inputOpen by remember { mutableStateOf(false) }
    val searchField = remember { FocusRequester() }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) { state.query = t; state.search(ctx) }
    }
    val voicePrompt = stringResource(R.string.voice_prompt)
    val canVoice = remember {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        val sorted = remember(state.results.assets, state.sort) {
            when (state.sort) {
                SearchSort.RELEVANCE -> state.results.assets
                SearchSort.NEWEST -> state.results.assets.sortedByDescending { it.date }
                SearchSort.OLDEST -> state.results.assets.sortedBy { it.date }
            }
        }
        val rowHeight = Settings.rowSize.height
        val rows = remember(sorted, width, rowHeight) { rowsFor(sorted, width, byDay = state.sort != SearchSort.RELEVANCE, height = rowHeight) }
        val source = remember(sorted) { ListSource(sorted) }
        LaunchedEffect(rows) {
            if (state.focusOnResult && sorted.isNotEmpty()) { state.focusOnResult = false; state.results.grid.focusTarget = sorted.first().id }
        }
        // Load next page when scrolling near the end
        LaunchedEffect(rows) {
            snapshotFlow { state.results.grid.list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rows.isNotEmpty()) state.results.loadMore() }
        }
        PhotoGrid(
            rows = rows, state = state.results.grid, headerCount = 1,
            onClick = { a -> nav.view(source, sorted.indexOf(a), if (state.typeOnly) ctx.getString(state.type.label) else ctx.getString(R.string.search_title, state.query), false, state.results.grid) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "top") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ScreenHeader(stringResource(R.string.search), null)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            onClick = { inputOpen = true },
                            modifier = Modifier.weight(1f).height(56.dp).focusRequester(searchField),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surfaceHigh, focusedContainerColor = Color(0xFF34343A)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, AppColors.primary), shape = RoundedCornerShape(28.dp)),
                            ),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, null, tint = AppColors.muted)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    state.query.ifEmpty { stringResource(R.string.search_hint) },
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                    color = if (state.query.isEmpty()) AppColors.muted else AppColors.text,
                                )
                            }
                        }
                        if (canVoice) Surface(
                            onClick = {
                                runCatching {
                                    voiceLauncher.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Language.locale.toLanguageTag())
                                            .putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt),
                                    )
                                }
                            },
                            modifier = Modifier.size(56.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surfaceHigh, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, stringResource(R.string.voice_search)) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (m in SearchMode.entries) ChoiceChip(selected = state.mode == m, onClick = { state.mode = m; state.search(ctx) }, text = stringResource(m.label))
                        Spacer(Modifier.width(18.dp))
                        for (t in SearchType.entries) ChoiceChip(selected = state.type == t, onClick = { state.type = t; state.search(ctx) }, text = stringResource(t.label))
                    }
                    if (state.results.assets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (v in SearchSort.entries) ChoiceChip(selected = state.sort == v, onClick = { state.sort = v }, text = stringResource(v.label))
                    }
                    when {
                        state.results.loading && state.results.assets.isEmpty() -> Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                        state.results.error != null && state.results.assets.isEmpty() -> Text(stringResource(R.string.search_failed, state.results.error.orEmpty()), color = AppColors.muted)
                        state.results.searched && state.results.assets.isEmpty() -> Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                        state.results.searched -> Text(pluralStringResource(R.plurals.results, sorted.size, Language.number(sorted.size) + if (state.results.loading) "+" else ""), style = MaterialTheme.typography.bodySmall)
                        else -> Suggestions(state, nav) { state.query = it; state.search(ctx) }
                    }
                }
            }
        }
        ListScrollBar(state.results.grid, rows, sorted.size, state.results.loading, Modifier.align(Alignment.CenterEnd))
    }
    LaunchedEffect(Unit) { delay(30); if (!state.results.searched) runCatching { searchField.requestFocus() } }
    // Restore focus to search bar when keyboard closes (otherwise Android moves focus to the drawer)
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(inputOpen) { if (wasOpen && !inputOpen) { delay(16); runCatching { searchField.requestFocus() } }; wasOpen = inputOpen }
    if (inputOpen) TextInputOverlay(stringResource(R.string.search), state.query, close = { inputOpen = false }) { state.query = it; inputOpen = false; state.search(ctx) }
}

@Composable
private fun Suggestions(state: SearchState, nav: Nav, onSearch: (String) -> Unit) {
    val ctx = LocalContext.current
    val recent = remember { RecentSearches.list(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent) { t ->
                    ChoiceChip(selected = false, onClick = { onSearch(t) }, leadingIcon = { Icon(Icons.Filled.History, null, Modifier.width(18.dp)) }, text = t)
                }
            }
        }
        Text(stringResource(R.string.try_suggestions), style = MaterialTheme.typography.titleMedium)
        val suggestions = stringArrayResource(R.array.suggestions).toList()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(suggestions) { t ->
                ChoiceChip(selected = false, onClick = { onSearch(t) }, text = t)
            }
        }
        if (state.people.isNotEmpty()) {
            Text(stringResource(R.string.people), style = MaterialTheme.typography.titleMedium)
            val personLabel = stringResource(R.string.person)
            // Top 10 people (Immich order: favorites first, then photo count) + "All people" button
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.people.take(TOP_PEOPLE), key = { it.id }) { p ->
                    ChoiceChip(
                        selected = false,
                        onClick = { nav.open(Screen.Timeline(p.name, personLabel, Filter(personId = p.id), true, false, nav.scope)) },
                        leadingIcon = { PersonAvatar(p, 26.dp) }, text = p.name,
                    )
                }
                if (state.people.size > TOP_PEOPLE) item(key = "all") {
                    ChoiceChip(
                        selected = false, onClick = { nav.open(Screen.People(state.people)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                        text = stringResource(R.string.all_people, state.people.size),
                    )
                }
            }
        }
    }
}

/**
 * Custom on-screen D-Pad keyboard: Google TV's default IME often defers to a mobile-phone bridge rather than
 * a remote-friendly layout. Supports a-z (plus Cyrillic for Russian) and digits (smart search and album filter
 * are accent-insensitive); physical keyboards also type directly with full Unicode support.
 * [live] receives intermediate text updates (e.g. live filtering albums while typing).
 */
@Composable
fun TextInputOverlay(title: String, initial: String, live: ((String) -> Unit)? = null, actionLabel: String = stringResource(R.string.action_search), close: () -> Unit, done: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var latin by remember { mutableStateOf(Language.code != "ru") }
    val first = remember { FocusRequester() }
    fun update(t: String) { text = t; live?.invoke(t) }
    BackHandler { close() }
    Box(
        Modifier.fillMaxSize().background(Color(0xF00A0A0A)).trapFocus().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = e.nativeKeyEvent
            when {
                n.keyCode == KeyEvent.KEYCODE_DEL -> { update(text.dropLast(1)); true }
                n.keyCode == KeyEvent.KEYCODE_SEARCH -> { done(text); true }
                n.unicodeChar > 0 && n.keyCode !in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) -> {
                    update(text + n.unicodeChar.toChar()); true
                }
                else -> false
            }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.padding(top = 56.dp).width(700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(AppColors.surfaceHigh, RoundedCornerShape(28.dp))
                    .border(2.dp, AppColors.primary, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = AppColors.muted)
                Spacer(Modifier.width(14.dp))
                Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Box(Modifier.width(2.dp).height(26.dp).background(AppColors.primary))
            }
            Spacer(Modifier.height(6.dp))
            val chars = if (latin) "abcdefghijklmnopqrstuvwxyz1234567890" else "абвгдеёжзийклмнопрстуфхцчшщъыьэюя1234567890"
            val cols = if (latin) 10 else 11
            chars.chunked(cols).forEachIndexed { ri, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { ci, c ->
                        KeyButton(c.toString(), Modifier.weight(1f).then(if (ri == 0 && ci == 0) Modifier.focusRequester(first) else Modifier)) { update(text + c) }
                    }
                    // Pad last row so keys stay aligned in columns
                    repeat(cols - row.length) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(stringResource(R.string.key_space), Modifier.weight(if (Language.code == "ru") 2.5f else 3f)) { update("$text ") }
                KeyButton("⌫", Modifier.weight(1f)) { update(text.dropLast(1)) }
                KeyButton(stringResource(R.string.key_clear), Modifier.weight(if (Language.code == "ru") 1.8f else 2f)) { update("") }
                if (Language.code == "ru") KeyButton(if (latin) "АБВ" else "ABC", Modifier.weight(1.2f)) { latin = !latin }
                KeyButton(actionLabel, Modifier.weight(if (Language.code == "ru") 3.5f else 4f), primary = true) { done(text) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.height(52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) AppColors.primary.copy(alpha = 0.25f) else AppColors.surface, contentColor = AppColors.text,
            focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleMedium) }
    }
}

/** Paginated flat photo list (e.g. all photos from one city), grouped by day with year. */
@Composable
fun ResultListScreen(screen: Screen.ResultList, nav: Nav) {
    val res = screen.results
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        val rowHeight = Settings.rowSize.height
        val rows = remember(res.assets, width, rowHeight) { rowsFor(res.assets, width, byDay = screen.byDay, height = rowHeight) }
        val source = remember(res.assets) { ListSource(res.assets) }
        LaunchedEffect(rows) {
            snapshotFlow { res.grid.list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rows.isNotEmpty()) res.loadMore() }
        }
        LaunchedEffect(rows.isNotEmpty()) {
            if (rows.isNotEmpty() && res.grid.lastFocused == null) res.grid.focusTarget = res.assets.firstOrNull()?.id
        }
        PhotoGrid(
            rows = rows, state = res.grid, headerCount = 1,
            onClick = { a -> nav.view(source, res.assets.indexOf(a), screen.title, false, res.grid) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "top") {
                ScreenHeader(screen.title, listOfNotNull(screen.sub, if (res.assets.isNotEmpty()) pluralStringResource(R.plurals.items, res.assets.size, res.assets.size) + (if (res.loading) "+" else "") else null).joinToString("  ·  "))
                if (res.error != null && res.assets.isEmpty()) Text(stringResource(R.string.loading_failed, res.error.orEmpty()), color = AppColors.muted)
            }
        }
        ListScrollBar(res.grid, rows, res.assets.size, res.loading, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * Scrollbar for a flat list (search results, city): day header of the first visible row + "N / M".
 * Interactive steps jump by day (rows with headers) or by 5% chunks when ungrouped.
 */
@Composable
fun ListScrollBar(grid: GridState, rows: List<GridRow>, total: Int, loading: Boolean, modifier: Modifier, headerCount: Int = 1) {
    if (rows.isEmpty()) return
    val list = grid.list
    val scope = rememberCoroutineScope()
    val firstPhoto = remember(rows) { firstPhotoPerRow(rows) }
    fun currentRow() = (list.firstVisibleItemIndex - headerCount).coerceIn(0, rows.lastIndex)
    fun headerOf(row: Int): String? {
        var r = row
        while (r > 0 && (rows[r] as? PhotoGridRow)?.row?.hasHeader != true) r--
        return (rows[r] as? PhotoGridRow)?.row?.chunks?.firstOrNull()?.header
    }
    val steps = remember(rows, total) {
        val headers = rows.indices.filter { (rows[it] as? PhotoGridRow)?.row?.hasHeader == true }
        val anchors = if (headers.size >= 3) headers else (0 until 20).map { it * rows.size / 20 }.distinct()
        ScrollSteps(
            count = anchors.size,
            fraction = { i -> firstPhoto[anchors[i]].toFloat() / (total - 1).coerceAtLeast(1) },
            label = { i -> headerOf(anchors[i]) },
            sub = { i -> "${Language.number(firstPhoto[anchors[i]] + 1)} / ${Language.number(total)}" },
            largeStep = largeStepBy(10, anchors.size),
            current = { anchors.indexOfLast { it <= currentRow() }.coerceAtLeast(0) },
            goTo = { i ->
                val r = anchors[i]
                scope.launch {
                    list.scrollToItem(r + headerCount)
                    grid.focusTarget = (rows[r] as? PhotoGridRow)?.row?.chunks?.firstOrNull()?.cells?.firstOrNull()?.asset?.id
                }
            },
            back = { grid.focusTarget = grid.lastFocused?.id },
        )
    }
    ScrollBar(
        fraction = { fractionOf(list, headerCount) },
        movement = { list.firstVisibleItemIndex * 7919 + list.firstVisibleItemScrollOffset / 200 },
        scrollable = { list.canScrollForward || list.canScrollBackward },
        label = { headerOf(currentRow()) },
        sub = { "${Language.number(firstPhoto[currentRow()] + 1)} / ${Language.number(total)}" + if (loading) "+" else "" },
        modifier = modifier,
        steps = steps,
    )
}

/** Past week's memories: grouped by day with year cards, matching "On this day" in Immich. */
@Composable
fun MemoriesScreen(state: MemoriesState, nav: Nav) {
    val first = remember { FocusRequester() }
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader(stringResource(R.string.section_memories), stringResource(R.string.memories_sub)) }
        if (state.ready && state.days.all { it.second.isEmpty() }) item { EmptyMessage(stringResource(R.string.no_memories), null) }
        state.days.forEachIndexed { di, (day, list) ->
            if (list.isEmpty()) return@forEachIndexed
            item(key = day.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dayTitle(day, false), style = MaterialTheme.typography.titleLarge)
                    LazyRow(
                        Modifier.offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        items(list, key = { it.id }) { m ->
                            Box(if (di == state.days.indexOfFirst { it.second.isNotEmpty() } && m == list.first()) Modifier.focusRequester(first) else Modifier) {
                                MemoryCard(m, nav, width = 190f)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.ready) { delay(50); runCatching { first.requestFocus() } }
}

class MemoriesState(scope: CoroutineScope) {
    var days by mutableStateOf<List<Pair<java.time.LocalDate, List<Memory>>>>(emptyList())
    var ready by mutableStateOf(false)

    init {
        scope.launch {
            val today = java.time.LocalDate.now()
            val dates = (0..6).map { today.minusDays(it.toLong()) }
            days = dates.map { it to emptyList() }
            dates.forEachIndexed { i, d ->
                runCatching { Immich.memories(d) }.onSuccess { m -> days = days.toMutableList().also { it[i] = d to m } }
            }
            ready = true
        }
    }
}
