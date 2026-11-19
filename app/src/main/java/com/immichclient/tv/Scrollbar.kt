package com.immichclient.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Right-side scrollbar: a thin track with a thumb showing position across the entire library, a floating label
 * (month or "1,234 / 47,153"), and optional year markers along the track. Appears on scroll and fades out 1.5 s later.
 *
 * Everything is drawn in a single Canvas and scroll position is read strictly during the draw phase ([fraction], [label],
 * [sub] inside the draw lambda), so each scroll frame only redraws this layer without recomposition or re-layout.
 * An earlier composable-based version recomposed on every scroll frame and caused 12-21% janky frames vs 1-5%;
 * with Canvas + throttled text measurement the overhead matches having no bar (test flag: `--ez no_scrollbar true`).
 *
 * Interactive mode ([steps], 2.5): pressing Right at the right edge focuses the scrollbar (widens with accent border
 * and hint); Up/Down steps through items (timeline: per month), holding accelerates to large jumps (per year) with live
 * label preview. OK or Left jumps there and focuses the first photo; Left without changing step or Back returns focus.
 */
class ScrollSteps(
    val count: Int,
    /** Position 0..1 of step i on the track */
    val fraction: (Int) -> Float,
    val label: (Int) -> String?,
    val sub: (Int) -> String?,
    /** Large jump (while holding key) from step i in direction dir (-1/+1), e.g. to previous/next year */
    val largeStep: (Int, Int) -> Int,
    /** Step currently visible at the top */
    val current: () -> Int,
    /** Jump to step i and focus the first photo there */
    val goTo: (Int) -> Unit,
    /** Cancelled without picking: return focus where it was */
    val back: () -> Unit,
    /** Steps represent months (shows month/year hint), otherwise general hint */
    val perMonth: Boolean = false,
)

/** Large jump every [n] steps (for lists without years). */
fun largeStepBy(n: Int, count: Int): (Int, Int) -> Int = { i, dir -> (i + dir * n).coerceIn(0, (count - 1).coerceAtLeast(0)) }

@Composable
fun ScrollBar(
    /** 0..1 thumb position; read during draw */
    fraction: () -> Float,
    /** Snapshot key changing on scroll (controls bar auto-show/hide) */
    movement: () -> Any?,
    /** Whether the container has anything to scroll */
    scrollable: () -> Boolean,
    label: () -> String?,
    sub: () -> String?,
    /** Year labels (or other ticks) along the track: position 0..1 -> text */
    markers: List<Pair<Float, String>> = emptyList(),
    modifier: Modifier = Modifier,
    /** Remote-controllable navigation steps; null = display-only indicator */
    steps: ScrollSteps? = null,
) {
    if (noScrollBar) return
    var visible by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(false) }
    val selectedStep = remember { mutableIntStateOf(0) }
    var initialStep by remember { mutableIntStateOf(0) }
    // One extra redraw 170 ms after scroll settles so throttled label text catches up to the final item
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { movement() }.distinctUntilChanged().collectLatest {
            if (!scrollable()) { visible = false; return@collectLatest }
            visible = true; delay(170); tick++; delay(1330); visible = false
        }
    }
    // Text layout is the costly part (1-3 ms with variable font): measure at most ~6 times/sec
    val labelCache = remember { LabelCache() }
    val alpha = animateFloatAsState(if (visible || focus) 1f else 0f, tween(if (visible || focus) 160 else 450), label = "scrollbar")
    val tm = rememberTextMeasurer(cacheSize = 16)
    val styleLabel = MaterialTheme.typography.labelLarge.copy(color = Color.White)
    val styleSub = MaterialTheme.typography.labelSmall.copy(color = Color(0xB3FFFFFF))
    val styleYear = MaterialTheme.typography.labelSmall.copy(color = Color(0x99FFFFFF))
    val years = remember(markers, styleYear) { markers.map { (f, t) -> f to tm.measure(t, styleYear) } }
    val accent = AppColors.primary
    val hintText = stringResource(if (steps?.perMonth == true) R.string.scrollbar_hint_month else R.string.scrollbar_hint_general)
    val hint = remember(hintText, styleSub) { tm.measure(hintText, styleSub) }

    Box(modifier.fillMaxHeight().width(300.dp)) {
        Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
            if (alpha.value <= 0f) return@Canvas
            val w = size.width
            val pad = 28.dp.toPx()
            val interactive = focus && steps != null
            val thumbH = (if (interactive) 40 else 30).dp.toPx()
            val travel = (size.height - 2 * pad - thumbH).coerceAtLeast(0f)
            // Track (wider and brighter when focused)
            val trackW = (if (interactive) 5 else 3).dp.toPx()
            drawRoundRect(
                if (interactive) Color(0x66FFFFFF) else Color(0x33FFFFFF),
                Offset(w - 11.5.dp.toPx() - trackW / 2, pad),
                Size(trackW, size.height - 2 * pad),
                CornerRadius(trackW / 2),
            )
            // Year markers left of the track, skipping labels closer than 20 dp
            val minSpacing = 20.dp.toPx()
            var lastY = -1e9f
            for ((f, lay) in years) {
                val y = f * travel
                if (y - lastY < minSpacing) continue
                lastY = y
                drawText(lay, topLeft = Offset(w - 22.dp.toPx() - lay.size.width, pad + y + thumbH / 2 - lay.size.height / 2))
            }
            // Thumb (interactive: positioned at chosen step, larger, with glow)
            val st = steps
            val k = selectedStep.intValue
            val f = if (interactive && st != null && st.count > 0) st.fraction(k.coerceIn(0, st.count - 1)) else fraction()
            val thumbY = pad + f.coerceIn(0f, 1f) * travel
            val thumbW = (if (interactive) 11 else 7).dp.toPx()
            if (interactive) drawRoundRect(
                accent.copy(alpha = 0.35f),
                Offset(w - 11.5.dp.toPx() - thumbW / 2 - 4.dp.toPx(), thumbY - 4.dp.toPx()),
                Size(thumbW + 8.dp.toPx(), thumbH + 8.dp.toPx()),
                CornerRadius(9.dp.toPx()),
            )
            drawRoundRect(accent, Offset(w - 11.5.dp.toPx() - thumbW / 2, thumbY), Size(thumbW, thumbH), CornerRadius(thumbW / 2))
            // Floating label next to the thumb (dark pill so it remains legible over photos)
            @Suppress("UNUSED_EXPRESSION") tick
            val now = android.os.SystemClock.uptimeMillis()
            if (interactive || now - labelCache.time >= 150) {
                labelCache.time = now
                val lt = if (interactive && st != null) st.label(k) else label()
                if (lt != labelCache.labelText) { labelCache.labelText = lt; labelCache.label = lt?.let { tm.measure(it, styleLabel) } }
                val sbt = if (interactive && st != null) st.sub(k) else sub()
                if (sbt != labelCache.subText) { labelCache.subText = sbt; labelCache.sub = sbt?.let { tm.measure(it, styleSub) } }
            }
            val l = labelCache.label
            val s = labelCache.sub
            val hl = if (interactive) hint else null
            if (l == null && s == null) return@Canvas
            val textW = maxOf(l?.size?.width ?: 0, s?.size?.width ?: 0, hl?.size?.width ?: 0).toFloat()
            val textH = ((l?.size?.height ?: 0) + (s?.size?.height ?: 0) + (hl?.size?.height?.plus(4.dp.roundToPx()) ?: 0)).toFloat()
            val px = 14.dp.toPx(); val py = 6.dp.toPx()
            val boxW = textW + 2 * px; val boxH = textH + 2 * py
            val right = w - 26.dp.toPx()
            val top = (thumbY + thumbH / 2 - boxH / 2).coerceIn(0f, (size.height - boxH).coerceAtLeast(0f))
            drawRoundRect(Color(0xE6000000), Offset(right - boxW, top), Size(boxW, boxH), CornerRadius(12.dp.toPx()))
            if (interactive) drawRoundRect(accent, Offset(right - boxW, top), Size(boxW, boxH), CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
            var y = top + py
            if (l != null) { drawText(l, topLeft = Offset(right - px - l.size.width, y)); y += l.size.height }
            if (s != null) { drawText(s, topLeft = Offset(right - px - s.size.width, y)); y += s.size.height }
            if (hl != null) drawText(hl, topLeft = Offset(right - px - hl.size.width, y + 4.dp.toPx()), color = accent)
        }
        // Narrow focus target on the far right edge: D-Pad Right from the grid lands here
        if (steps != null && steps.count > 0) Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp)
                .focusProperties { canFocus = scrollable() }
                .onFocusChanged {
                    if (it.isFocused && !focus) {
                        selectedStep.intValue = steps.current().coerceIn(0, steps.count - 1)
                        initialStep = selectedStep.intValue
                        android.util.Log.i("Photos", "scrollbar: focus (step $initialStep of ${steps.count})")
                    }
                    focus = it.isFocused
                }
                .onPreviewKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    val arrow = n.keyCode == KeyEvent.KEYCODE_DPAD_UP || n.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    val ok = n.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == KeyEvent.KEYCODE_ENTER
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent arrow || ok || n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || n.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    when {
                        arrow -> {
                            val dir = if (n.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                            val rep = n.repeatCount
                            // Single press = 1 step; holding starts at ~10 steps/sec, then after ~1 s switches to large jumps ~6/sec
                            val next = when {
                                rep >= 14 -> if (rep % 3 == 0) steps.largeStep(selectedStep.intValue, dir) else selectedStep.intValue
                                rep > 0 && rep % 2 == 1 -> selectedStep.intValue
                                else -> selectedStep.intValue + dir
                            }
                            selectedStep.intValue = next.coerceIn(0, steps.count - 1)
                            true
                        }
                        ok -> { android.util.Log.i("Photos", "scrollbar: OK -> step ${selectedStep.intValue}"); steps.goTo(selectedStep.intValue); true }
                        n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (selectedStep.intValue != initialStep) steps.goTo(selectedStep.intValue) else steps.back(); true }
                        n.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                        n.keyCode == KeyEvent.KEYCODE_BACK -> { steps.back(); true }
                        else -> false
                    }
                }
                .focusable(),
        )
    }
}

/** Test flag: `--ez no_scrollbar true` disables the scrollbar for scroll performance comparisons. */
@Volatile var noScrollBar = false

private class LabelCache {
    var time = 0L
    var labelText: String? = null
    var subText: String? = null
    var label: TextLayoutResult? = null
    var sub: TextLayoutResult? = null
}

/** Scroll fraction 0..1 of a LazyColumn: first visible item + partial scroll offset vs total scrollable range. */
fun fractionOf(list: LazyListState, headerCount: Int = 0): Float {
    val info = list.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount - headerCount
    if (total <= 0 || visible.isEmpty()) return 0f
    val first = visible.first()
    val part = if (first.size > 0) (-first.offset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
    val i = (first.index - headerCount + part).coerceAtLeast(0f)
    val n = (total - visible.size + 1).coerceAtLeast(1)
    return if (!list.canScrollForward) 1f else (i / n).coerceIn(0f, 1f)
}

fun fractionOf(grid: LazyGridState, headerCount: Int = 0): Float {
    val info = grid.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount - headerCount
    if (total <= 0 || visible.isEmpty()) return 0f
    val i = (visible.first().index - headerCount).coerceAtLeast(0)
    val n = (total - visible.size + 1).coerceAtLeast(1)
    return if (!grid.canScrollForward) 1f else (i.toFloat() / n).coerceIn(0f, 1f)
}

/** Index (0-based) of the first photo in each grid row, used for "N / M" counters in flat lists. */
fun firstPhotoPerRow(rows: List<GridRow>): IntArray {
    val out = IntArray(rows.size)
    var n = 0
    rows.forEachIndexed { i, r -> out[i] = n; if (r is PhotoGridRow) n += r.row.chunks.sumOf { it.cells.size } }
    return out
}
