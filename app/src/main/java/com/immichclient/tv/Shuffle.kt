package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BATCH_SIZE = 120

/**
 * Random feed of all photos and videos: batches from `/search/random` without dates or grouping,
 * with incremental loading on scroll or viewing and a full-reshuffle action.
 */
class ShuffleState(private val scope: CoroutineScope) : ImageSource {
    var assets by mutableStateOf<List<Asset>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var exhausted by mutableStateOf(false)
        private set
    val grid = GridState()

    private val seen = HashSet<String>()
    private var job: Job? = null
    private var round = 0

    init { reshuffle(scrollTop = false) }

    fun reshuffle(scrollTop: Boolean = true) {
        job?.cancel()
        val r = ++round
        exhausted = false
        error = null
        loading = true
        if (scrollTop) {
            grid.lastFocused = null
            scope.launch { grid.list.scrollToItem(0) }
        }
        job = scope.launch {
            runCatching { Immich.random(BATCH_SIZE, photosOnly = false) }
                .onSuccess { l ->
                    if (r != round) return@onSuccess
                    withContext(Dispatchers.Default) { l.take(40).forEach { it.thumbhash?.let(ThumbHash::bitmap) } }
                    seen.clear()
                    val unique = l.filter { seen.add(it.id) }
                    assets = unique
                    if (l.size < BATCH_SIZE || unique.isEmpty()) exhausted = true
                }
                .onFailure { if (r == round) { error = it.message; Log.w("Photos", "shuffle", it) } }
            if (r == round) loading = false
        }
    }

    fun loadMore() {
        if (exhausted || loading || job?.isActive == true) return
        val r = round
        job = scope.launch {
            loading = true
            runCatching { Immich.random(BATCH_SIZE, photosOnly = false) }
                .onSuccess { l ->
                    if (r != round) return@onSuccess
                    val fresh = l.filter { seen.add(it.id) }
                    if (fresh.isNotEmpty()) {
                        withContext(Dispatchers.Default) { fresh.take(40).forEach { it.thumbhash?.let(ThumbHash::bitmap) } }
                        assets = assets + fresh
                    }
                    if (fresh.isEmpty() || l.size < BATCH_SIZE) exhausted = true
                }
                .onFailure { if (r == round) { error = it.message; Log.w("Photos", "shuffle more", it) } }
            if (r == round) loading = false
        }
    }

    override val count get() = assets.size
    override fun at(i: Int): Asset? {
        if (!exhausted && !loading && job?.isActive != true && i >= assets.size - 15) {
            scope.launch { loadMore() }
        }
        return assets.getOrNull(i)
    }
}

@Composable
fun ShuffleScreen(state: ShuffleState, nav: Nav) {
    val assets = state.assets
    val title = stringResource(R.string.section_shuffle)
    fun view(i: Int, slideshow: Boolean) {
        if (assets.isEmpty()) return
        nav.view(state, i.coerceIn(0, assets.lastIndex), title, slideshow, state.grid)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        val rowHeight = Settings.rowSize.height
        val rows = remember(assets, width, rowHeight) { rowsFor(assets, width, byDay = false, height = rowHeight) }

        LaunchedEffect(rows, state.loading, state.exhausted) {
            snapshotFlow { state.grid.list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rows.isNotEmpty()) state.loadMore() }
        }
        LaunchedEffect(assets.isNotEmpty()) {
            if (assets.isNotEmpty() && state.grid.lastFocused == null) {
                state.grid.focusTarget = assets.first().id
            }
        }

        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val a = state.grid.lastFocused ?: assets.firstOrNull()
                        val i = a?.let { assets.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                        view(i, true)
                        assets.isNotEmpty()
                    }
                    else -> false
                }
            },
        ) {
            PhotoGrid(
                rows = rows,
                state = state.grid,
                onClick = { a -> val i = assets.indexOf(a); if (i >= 0) view(i, false) },
                headerCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    val sub = when {
                        assets.isNotEmpty() -> pluralStringResource(R.plurals.items, assets.size, assets.size) + (if (!state.exhausted) "+" else "")
                        state.loading -> stringResource(R.string.loading)
                        else -> null
                    }
                    ScreenHeader(title, sub) {
                        ChoiceChip(
                            selected = false, onClick = { state.reshuffle() },
                            leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.width(18.dp)) },
                            text = stringResource(R.string.shuffle),
                        )
                        if (assets.isNotEmpty()) ChoiceChip(
                            selected = false, onClick = { view(0, true) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                            text = stringResource(R.string.slideshow),
                        )
                    }
                    if (state.error != null && assets.isEmpty()) {
                        Text(stringResource(R.string.loading_failed, state.error.orEmpty()), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 12.dp))
                    } else if (!state.loading && assets.isEmpty() && state.error == null) {
                        Text(stringResource(R.string.shuffle_empty), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            ListScrollBar(state.grid, rows, assets.size, state.loading || !state.exhausted, Modifier.align(Alignment.CenterEnd))
        }
    }
}
