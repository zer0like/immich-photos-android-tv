package com.immichclient.tv

import androidx.compose.runtime.Immutable

/**
 * Immich-style justified photo layout: photos keep their aspect ratio and rows fill the exact available width.
 * Small days (fitting in a single row) share a row side-by-side with their own date headers; larger days span
 * multiple rows. All dimensions are computed in dp/px ahead of image loading so nothing jumps during scroll.
 */
@Immutable data class Cell(val asset: Asset, val width: Float)
@Immutable data class Chunk(val header: String?, val cells: List<Cell>)
@Immutable data class LayoutRow(val key: String, val chunks: List<Chunk>, val height: Float, val hasHeader: Boolean)

@Immutable data class DayGroup(val header: String?, val assets: List<Asset>)

object PhotoLayout {
    /** Clamped aspect ratio bounds for layout (panoramas crop gently rather than shrinking to a 3px strip). */
    private fun ratioOf(a: Asset) = a.ratio.coerceIn(0.5f, 2.6f)

    fun rows(prefix: String, groups: List<DayGroup>, width: Float, rowHeight: Float, gap: Float, groupGap: Float): List<LayoutRow> {
        val out = ArrayList<LayoutRow>()
        var pending = ArrayList<Chunk>()
        var pendingWidth = 0f
        var n = 0
        fun flushPending() {
            if (pending.isEmpty()) return
            out.add(LayoutRow("$prefix-${n++}", pending, rowHeight, pending.any { it.header != null }))
            pending = ArrayList(); pendingWidth = 0f
        }
        for (g in groups) {
            if (g.assets.isEmpty()) continue
            val natural = g.assets.sumOf { (ratioOf(it) * rowHeight).toDouble() }.toFloat() + gap * (g.assets.size - 1)
            if (natural <= width) {
                // Fits in one row: pack next to previous small day(s) if space allows
                val needed = natural + if (pending.isEmpty()) 0f else groupGap
                if (pendingWidth + needed > width) flushPending()
                pendingWidth += natural + if (pending.isEmpty()) 0f else groupGap
                pending.add(Chunk(g.header, g.assets.map { Cell(it, ratioOf(it) * rowHeight) }))
                continue
            }
            flushPending()
            // Large day: fill rows up to full width, then scale height so the row justifies edge-to-edge
            var row = ArrayList<Asset>()
            var sum = 0f
            var first = true
            for (a in g.assets) {
                row.add(a); sum += ratioOf(a)
                if (sum * rowHeight + gap * (row.size - 1) >= width) {
                    val h = (width - gap * (row.size - 1)) / sum
                    out.add(LayoutRow("$prefix-${n++}", listOf(Chunk(if (first) g.header else null, row.map { Cell(it, ratioOf(it) * h) })), h, first && g.header != null))
                    first = false; row = ArrayList(); sum = 0f
                }
            }
            if (row.isNotEmpty()) {
                out.add(LayoutRow("$prefix-${n++}", listOf(Chunk(if (first) g.header else null, row.map { Cell(it, ratioOf(it) * rowHeight) })), rowHeight, first && g.header != null))
            }
        }
        flushPending()
        return out
    }

    /** Rough height estimate for an unloaded month (used only for the placeholder and scrollbar). */
    fun estimateHeight(count: Int, width: Float, rowHeight: Float, headerHeight: Float): Float {
        val rows = kotlin.math.ceil(count * 1.25f * rowHeight / width).coerceAtLeast(1f)
        // Capped because Compose rejects single items taller than ~262,000 px (months with thousands of photos)
        return (rows * rowHeight + rows * 0.5f * headerHeight).coerceAtMost(2400f)
    }

    fun byDay(assets: List<Asset>, withYear: Boolean): List<DayGroup> {
        val out = ArrayList<DayGroup>()
        var day: java.time.LocalDate? = null
        var list = ArrayList<Asset>()
        for (a in assets) {
            val d = a.date.toLocalDate()
            if (d != day) {
                if (list.isNotEmpty()) out.add(DayGroup(dayTitle(day!!, withYear) + placeSuffix(list), list))
                day = d; list = ArrayList()
            }
            list.add(a)
        }
        if (list.isNotEmpty()) out.add(DayGroup(dayTitle(day!!, withYear) + placeSuffix(list), list))
        return out
    }

    /** Most common city for that day appended to the date header, like Immich ("Sat 17 Oct · Lisbon"). */
    private fun placeSuffix(l: List<Asset>): String {
        val city = l.mapNotNull { it.city }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return ""
        return " ·  $city"
    }
}
