package com.immichclient.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** A single item row in a photo grid (LazyColumn). */
sealed interface GridRow { val key: String }
data class HeaderRow(override val key: String, val title: String, val sub: String?) : GridRow
data class PhotoGridRow(val row: LayoutRow) : GridRow { override val key get() = row.key }
/** Placeholder for a month not yet loaded; focusable so holding D-Pad Down never gets stuck. */
data class PlaceholderRow(override val key: String, val month: String, val height: Float) : GridRow

const val ROW_HEIGHT = 138f
const val GAP = 6f
const val GROUP_GAP = 26f
const val HEADER_HEIGHT = 30f

class GridState {
    val list = LazyListState()
    /** Asset ID that should receive focus as soon as it becomes visible (returning from viewer / screen). */
    var focusTarget by mutableStateOf<String?>(null)
    var lastFocused: Asset? = null
    /** Viewer closed on this photo: scroll to it and restore focus. */
    var returnTo by mutableStateOf<String?>(null)

    suspend fun scrollTo(id: String, rows: List<GridRow>, headerCount: Int = 0) {
        val i = rows.indexOfFirst { r -> r is PhotoGridRow && r.row.chunks.any { c -> c.cells.any { it.asset.id == id } } }
        if (i < 0) return
        val visible = list.layoutInfo.visibleItemsInfo
        val alreadyVisible = visible.any { it.index == i + headerCount && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!alreadyVisible) list.scrollToItem(i + headerCount, -(list.layoutInfo.viewportSize.height * 0.3f).toInt())
        focusTarget = id
    }
}

/** Source backing the viewer: may exceed what is currently loaded (timeline lazily loads month by month). */
interface ImageSource {
    val count: Int
    fun at(i: Int): Asset?
}

class ListSource(val list: List<Asset>) : ImageSource {
    override val count get() = list.size
    override fun at(i: Int) = list.getOrNull(i)
}

/**
 * Keeps focused photos around ~30% of the viewport height (like Android TV rows) with smooth scrolling;
 * Compose's default bring-into-view only scrolls the bare minimum, leaving focus pinned to the bottom edge.
 * Applies to all lists and grids via [PhotosTheme].
 *
 * Uses a spring rather than a tween: Compose re-evaluates bring-into-view on the remaining distance each frame
 * (`UpdatableAnimationState`, playTime = 1 frame). A tween(260, FastOutSlowIn) only advances ~1% per frame
 * (flat initial slope), causing sluggish creeping; a spring integrates velocity per frame cleanly without lag.
 */
@OptIn(ExperimentalFoundationApi::class)
val focusAt30 = object : BringIntoViewSpec {
    @Deprecated("") override val scrollAnimationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = containerSize * 0.3f
        return if (size > containerSize * 0.7f) offset else offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGrid(
    rows: List<GridRow>,
    state: GridState,
    onClick: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    headerCount: Int = 1,
    onPlaceholder: (String) -> Unit = {},
    header: LazyListScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val currentRows by rememberUpdatedState(rows)
    val seen = remember { android.util.LruCache<String, Boolean>(180) }
    LaunchedEffect(state.returnTo, rows) {
        val id = state.returnTo ?: return@LaunchedEffect
        state.scrollTo(id, rows, headerCount)
        state.returnTo = null
    }
    // Direction-aware debounced prefetch (2 rows ahead in scroll direction)
    LaunchedEffect(state) {
        val loader = SingletonImageLoader.get(ctx)
        var prevFirst = -1
        snapshotFlow {
            val info = state.list.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) -1 to -1 else info.first().index to info.last().index
        }.distinctUntilChanged().collectLatest { (first, last) ->
            if (first < 0) return@collectLatest
            val list = currentRows
            if (list.isEmpty()) return@collectLatest
            val scrollingUp = prevFirst in 0 until Int.MAX_VALUE && first < prevFirst
            prevFirst = first
            delay(200)
            val from = if (scrollingUp) (first - headerCount - 2).coerceAtLeast(0) else (last - headerCount + 1).coerceAtLeast(0)
            val to = if (scrollingUp) (first - headerCount - 1).coerceAtMost(list.lastIndex) else (last - headerCount + 2).coerceAtMost(list.lastIndex)
            if (to < from) return@collectLatest
            val hashes = ArrayList<String>()
            for (ri in from..to) {
                val r = list.getOrNull(ri) as? PhotoGridRow ?: continue
                for (c in r.row.chunks) for (cell in c.cells) {
                    if (seen.get(cell.asset.id) == null) {
                        seen.put(cell.asset.id, true)
                        loader.enqueue(thumbRequest(ctx, cell.asset.id))
                        cell.asset.thumbhash?.let { if (ThumbHash.inCache(it) == null) hashes += it }
                    }
                }
            }
            if (hashes.isNotEmpty()) withContext(Dispatchers.Default) {
                for (h in hashes) ThumbHash.bitmap(h)
            }
        }
    }
    val dpadGate = remember { LongArray(1) }
    LazyColumn(
        state = state.list,
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount == 0) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - dpadGate[0] < 95L) true
                    else {
                        dpadGate[0] = now
                        false
                    }
                }
                else -> false
            }
        },
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(GAP.dp),
    ) {
        header()
        items(rows, key = { it.key }, contentType = { it::class }) { r ->
            when (r) {
                is HeaderRow -> MonthHeader(r)
                is PhotoGridRow -> PhotoLayoutRow(r.row, state, onClick)
                is PlaceholderRow -> PlaceholderTile(r, onPlaceholder)
            }
        }
    }
}

@Composable
private fun MonthHeader(r: HeaderRow) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.title, style = MaterialTheme.typography.headlineSmall)
            r.sub?.let { Text("   $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        // Subtle accent line: short accent bar followed by a hairline rule
        Row(Modifier.padding(top = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(34.dp).height(3.dp).background(AppColors.primary, RoundedCornerShape(2.dp)))
            Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(AppColors.text.copy(alpha = 0.08f)))
        }
    }
}

@Composable
private fun PhotoLayoutRow(row: LayoutRow, state: GridState, onClick: (Asset) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    // Draw focused tile above neighboring rows
    Row(
        Modifier.zIndex(if (focus) 1f else 0f).onFocusChanged { focus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
    ) {
        for (chunk in row.chunks) {
            Column {
                if (row.hasHeader) Text(
                    chunk.header.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    modifier = Modifier.height(HEADER_HEIGHT.dp).padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
                    for (cell in chunk.cells) {
                        PhotoTile(
                            cell.asset, cell.width.dp, row.height.dp,
                            onClick = { onClick(cell.asset) },
                            focusTarget = state.focusTarget == cell.asset.id,
                            onFocusTargetDone = { state.focusTarget = null },
                            onFocus = { state.lastFocused = cell.asset },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderTile(r: PlaceholderRow, onPlaceholder: (String) -> Unit) {
    LaunchedEffect(r.month) { onPlaceholder(r.month) }
    // Gently breathing placeholder (layer alpha only: no redraws)
    val breath = rememberInfiniteTransition(label = "breath")
    val a by breath.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(r.height.dp).graphicsLayer { alpha = a }.onFocusChanged { if (it.isFocused) onPlaceholder(r.month) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surface.copy(alpha = 0.5f), focusedContainerColor = AppColors.surfaceHigh),
    ) { Box(Modifier) {} }
}

fun widthFor(total: Float) = total - 48f

/** Flat list (search results, memory, place) turned into grid rows: grouped by day, or one continuous block. */
fun rowsFor(assets: List<Asset>, width: Float, byDay: Boolean, withYear: Boolean = true, height: Float = ROW_HEIGHT): List<GridRow> {
    val groups = if (byDay) PhotoLayout.byDay(assets, withYear) else listOf(DayGroup(null, assets))
    return PhotoLayout.rows("l", groups, width, height, GAP, GROUP_GAP).map { PhotoGridRow(it) }
}
