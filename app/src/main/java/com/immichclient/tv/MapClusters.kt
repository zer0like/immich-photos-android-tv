package com.immichclient.tv

import androidx.annotation.StringRes
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/*
 * Hierarchical world map clustering: continent -> UN subregion -> country -> place (distance clusters).
 *
 * Built once per marker set on a background thread ([MapIndex]): per marker we resolve its country ISO code
 * (from Immich's country name), build the tree storing each group's members, bounding box, and marker anchor
 * (the densest cell of the group rather than the mean, which for Europe or Asia falls in the ocean), and lazily
 * compute & cache per-integer-zoom distance clusters inside each country. Determining what to display at a zoom level
 * ([MapIndex.visible]) is then a fast tree traversal: a group stays a single marker while its on-screen spread is
 * below [MapIndex.thresholdPx] and zoom is below its ceiling; otherwise we descend into its children. This shows
 * continents at world level, regions and countries as you zoom in, and places underneath—taking milliseconds instead
 * of re-clustering tens of thousands of photos on every move.
 */

enum class Continent(@StringRes val label: Int) {
    EUROPE(R.string.continent_europe), ASIA(R.string.continent_asia), AFRICA(R.string.continent_africa),
    NORTH_AMERICA(R.string.continent_north_america), SOUTH_AMERICA(R.string.continent_south_america),
    OCEANIA(R.string.continent_oceania), ANTARCTICA(R.string.continent_antarctica), OTHER(R.string.continent_other),
}

/** UN M49 subregions mapped to ISO 3166 country codes. */
enum class Region(val continent: Continent, @StringRes val label: Int, countries: String) {
    NORTHERN_EUROPE(Continent.EUROPE, R.string.region_northern_europe, "AX DK EE FO FI GG IS IE IM JE LV LT NO SJ SE GB"),
    WESTERN_EUROPE(Continent.EUROPE, R.string.region_western_europe, "AT BE FR DE LI LU MC NL CH"),
    SOUTHERN_EUROPE(Continent.EUROPE, R.string.region_southern_europe, "AL AD BA HR GI GR VA IT MT ME MK PT SM RS SI ES XK"),
    EASTERN_EUROPE(Continent.EUROPE, R.string.region_eastern_europe, "BY BG CZ HU PL MD RO RU SK UA"),
    NORTH_AFRICA(Continent.AFRICA, R.string.region_north_africa, "DZ EG LY MA SD TN EH"),
    WEST_AFRICA(Continent.AFRICA, R.string.region_west_africa, "BJ BF CV CI GM GH GN GW LR ML MR NE NG SH SN SL TG"),
    CENTRAL_AFRICA(Continent.AFRICA, R.string.region_central_africa, "AO CM CF TD CG CD GQ GA ST"),
    EAST_AFRICA(Continent.AFRICA, R.string.region_east_africa, "BI KM DJ ER ET KE MG MW MU YT MZ RE RW SC SO SS TZ UG ZM ZW IO TF"),
    SOUTHERN_AFRICA(Continent.AFRICA, R.string.region_southern_africa, "BW SZ LS NA ZA"),
    NORTHERN_AMERICA(Continent.NORTH_AMERICA, R.string.region_northern_america, "BM CA GL PM US"),
    CENTRAL_AMERICA(Continent.NORTH_AMERICA, R.string.region_central_america, "BZ CR SV GT HN MX NI PA"),
    CARIBBEAN(Continent.NORTH_AMERICA, R.string.region_caribbean, "AI AG AW BS BB BQ VG KY CU CW DM DO GD GP HT JM MQ MS PR BL KN LC MF VC SX TT TC VI"),
    SOUTH_AMERICA(Continent.SOUTH_AMERICA, R.string.continent_south_america, "AR BO BV BR CL CO EC FK GF GY PY PE GS SR UY VE"),
    CENTRAL_ASIA(Continent.ASIA, R.string.region_central_asia, "KZ KG TJ TM UZ"),
    EAST_ASIA(Continent.ASIA, R.string.region_east_asia, "CN HK MO KP JP MN KR TW"),
    SOUTHEAST_ASIA(Continent.ASIA, R.string.region_southeast_asia, "BN KH ID LA MY MM PH SG TH TL VN"),
    SOUTH_ASIA(Continent.ASIA, R.string.region_south_asia, "AF BD BT IN IR MV NP PK LK"),
    WEST_ASIA(Continent.ASIA, R.string.region_west_asia, "AM AZ BH CY GE IQ IL JO KW LB OM QA SA PS SY TR AE YE"),
    AUSTRALIA(Continent.OCEANIA, R.string.region_australia, "AU CX CC HM NZ NF"),
    MELANESIA(Continent.OCEANIA, R.string.region_melanesia, "FJ NC PG SB VU"),
    MICRONESIA(Continent.OCEANIA, R.string.region_micronesia, "GU KI MH FM NR MP PW UM"),
    POLYNESIA(Continent.OCEANIA, R.string.region_polynesia, "AS CK PF NU PN WS TK TO TV WF"),
    ANTARCTICA(Continent.ANTARCTICA, R.string.continent_antarctica, "AQ"),
    ;

    val codes: List<String> = countries.split(' ')

    companion object {
        private val byIso: Map<String, Region> by lazy { HashMap<String, Region>().also { m -> for (r in entries) for (c in r.codes) m[c] = r } }
        fun from(iso: String): Region? = byIso[iso]
    }
}

enum class MapLevel { CONTINENT, REGION, COUNTRY, PLACE }

/**
 * A cluster of photo markers at one location. [key] is stable across recalculations for the same group,
 * [members] holds sorted marker indices (ascending = newest first). [name] is set for continents/regions/countries;
 * [country] is an index into [MapIndex.countryCodes] (-1 for continents/regions).
 */
class Cluster(
    val key: String, val wx: Double, val wy: Double, val members: IntArray,
    val level: MapLevel = MapLevel.PLACE, val name: String? = null, val country: Int = -1,
    /** Merged because markers overlapped at this zoom level (e.g. Italy + Croatia): always splits on zoom-in. */
    val merged: Boolean = false,
) {
    val count get() = members.size
    /** Representative photo for the thumbnail: pick the middle one (the newest is often dark/accidental). */
    val representative get() = members[members.size / 2]
}

/** Continent, region, or country node: its marker ([cluster]), bounding box of all members, and child groups. */
class MapGroup(val cluster: Cluster, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val children: List<MapGroup>, val zMax: Int)

/** Maximum zoom level (exclusive) at which a group may stay collapsed into one marker even if small on screen. */
private const val Z_CONTINENT = 3
private const val Z_REGION = 5
private const val Z_COUNTRY = 7

/** Marker size and merge radius (dp): compact at world/country levels, wider at street level. */
const val RADIUS_DP = 66f
const val RADIUS_DEEP_DP = 130f
/** Cluster radius (dp) per integer zoom level: 66 dp up to zoom 9, scaling linearly to 130 dp at zoom 14 (x setting). */
fun radiusDp(z: Int) = (RADIUS_DP + (RADIUS_DEEP_DP - RADIUS_DP) * ((z - 9) / 5f).coerceIn(0f, 1f)) * Settings.cluster.radius

private class CellAccumulator(val country: Int, val kx: Long, val ky: Long) {
    var n = 0; var sx = 0.0; var sy = 0.0; var members = IntArray(8); var absorbed = false
    fun add(i: Int, x: Double, y: Double) { if (n == members.size) members = members.copyOf(n * 2); members[n++] = i; sx += x; sy += y }
    fun absorb(b: CellAccumulator) {
        if (n + b.n > members.size) members = members.copyOf(max(n + b.n, 2 * members.size))
        System.arraycopy(b.members, 0, members, n, b.n); n += b.n; sx += b.sx; sy += b.sy
    }
}

/** Pack grid cell + country into one Long: country (14 bits) | kx (25 bits) | ky (25 bits). */
private fun cellKey(country: Int, kx: Long, ky: Long) = ((country + 1).toLong() shl 50) or ((kx and 0x1FFFFFF) shl 25) or (ky and 0x1FFFFFF)

/**
 * Spatial index of all map markers, built once on a background thread per marker set.
 * [density] = px per dp, [thresholdPx] = screen spread below which a group stays a single marker.
 */
class MapIndex(
    val markers: Markers, private val wx: DoubleArray, private val wy: DoubleArray, private val density: Float, private val thresholdPx: Double,
    /** Markers closer than this (px) are merged so they don't stack on top of one another. */
    private val overlapPx: Double = 0.85 * 56 * density,
) {
    private val n = wx.size
    /** Country ISO codes present; "??" = photos whose country could not be determined. */
    val countryCodes: List<String>
    private val countryOf: IntArray
    val roots: List<MapGroup>
    private val groups = HashMap<String, MapGroup>()
    private val parent = HashMap<String, MapGroup>()
    private val countryNames: Array<String>

    init {
        val codes = ArrayList<String>()
        val indexByCode = HashMap<String, Int>()
        countryOf = IntArray(n) { i -> markers.iso[i]?.let { c -> indexByCode.getOrPut(c) { codes.add(c); codes.size - 1 } } ?: -1 }
        fillUnknown(codes)
        countryCodes = codes
        countryNames = Array(codes.size) { i -> if (codes[i] == UNKNOWN) Language.s(R.string.continent_other) else CountryName.fromIso(codes[i]) }
        roots = build()
    }

    /** Photos without a recognized country: adopt the country of the nearest known photo (~1° grid), else "unknown". */
    private fun fillUnknown(codes: ArrayList<String>) {
        val unassigned = (0 until n).filter { countryOf[it] < 0 }
        if (unassigned.isEmpty()) return
        val cell = 1.0 / 360
        val grid = HashMap<Long, Int>()
        for (i in 0 until n) if (countryOf[i] >= 0) grid.putIfAbsent(cellKey(0, floor(wx[i] / cell).toLong(), floor(wy[i] / cell).toLong()), i)
        var unknownIdx = -1
        for (i in unassigned) {
            val kx = floor(wx[i] / cell).toLong(); val ky = floor(wy[i] / cell).toLong()
            var best = -1; var bestD = Double.MAX_VALUE
            for (r in 0..3) {
                for (dx in -r..r) for (dy in -r..r) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    val j = grid[cellKey(0, kx + dx, ky + dy)] ?: continue
                    val d = hypot(wx[j] - wx[i], wy[j] - wy[i])
                    if (d < bestD) { bestD = d; best = j }
                }
                if (best >= 0) break
            }
            countryOf[i] = if (best >= 0) countryOf[best] else {
                if (unknownIdx < 0) { codes.add(UNKNOWN); unknownIdx = codes.size - 1 }
                unknownIdx
            }
        }
    }

    private fun build(): List<MapGroup> {
        // Partition members per country (ascending order preserved because i increases)
        val counts = IntArray(countryCodes.size)
        for (c in countryOf) counts[c]++
        val perCountry = Array(countryCodes.size) { IntArray(counts[it]) }
        val cursor = IntArray(countryCodes.size)
        for (i in 0 until n) { val c = countryOf[i]; perCountry[c][cursor[c]++] = i }
        val countries = countryCodes.indices.map { c -> makeGroup("l:${countryCodes[c]}", countryNames[c], MapLevel.COUNTRY, perCountry[c], emptyList(), Z_COUNTRY, c) }

        val rootList = ArrayList<MapGroup>()
        for (cont in Continent.entries) {
            val inCont = countries.filter { g -> continentOf(countryCodes[g.cluster.country]) == cont }
            if (inCont.isEmpty()) continue
            // Regions with photos; a single-country region collapses straight to that country (avoids duplicate zoom stops)
            val regions = inCont.groupBy { Region.from(countryCodes[it.cluster.country]) }.map { (r, cs) ->
                if (r == null || cs.size == 1) cs else listOf(makeGroup("r:${r.name}", Language.s(r.label), MapLevel.REGION, mergeMembers(cs), cs, Z_REGION, -1))
            }.flatten()
            // Continent with only one region (e.g. South America, US+Canada): expose countries directly underneath
            val children = if (regions.size == 1 && regions[0].cluster.level == MapLevel.REGION) regions[0].children else regions
            rootList += makeGroup("w:${cont.name}", Language.s(cont.label), MapLevel.CONTINENT, mergeMembers(inCont), children, Z_CONTINENT, -1)
        }
        return rootList
    }

    private fun continentOf(iso: String) = Region.from(iso)?.continent ?: Continent.OTHER

    private fun mergeMembers(groupsList: List<MapGroup>): IntArray {
        val out = IntArray(groupsList.sumOf { it.cluster.count })
        var p = 0
        for (g in groupsList) { System.arraycopy(g.cluster.members, 0, out, p, g.cluster.count); p += g.cluster.count }
        out.sort()
        return out
    }

    /** Create a MapGroup with bbox and anchor positioned at the centroid of its densest ~40 km cell. */
    private fun makeGroup(key: String, name: String, level: MapLevel, members: IntArray, children: List<MapGroup>, zMax: Int, country: Int): MapGroup {
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        val cell = 1.0 / 1024
        val cells = HashMap<Long, DoubleArray>()
        var best: DoubleArray? = null
        for (i in members) {
            val x = wx[i]; val y = wy[i]
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            val v = cells.getOrPut(cellKey(0, floor(x / cell).toLong(), floor(y / cell).toLong())) { DoubleArray(3) }
            v[0]++; v[1] += x; v[2] += y
            if (best == null || v[0] > best[0]) best = v
        }
        val b = best!!
        val g = MapGroup(Cluster(key, b[1] / b[0], b[2] / b[0], members, level, name, country), x0, y0, x1, y1, children, zMax)
        groups[key] = g
        for (k in children) parent[k.cluster.key] = g
        return g
    }

    // ---- Distance clusters per integer zoom level, per country ----

    private class ZoomLevel(val perCountry: Array<Array<Cluster>>)
    private val levels = HashMap<Int, ZoomLevel>()

    /** Distance clusters at integer zoom [z] (lazily computed and cached per cluster-density setting). */
    private fun level(z: Int): ZoomLevel {
        val key = z * 16 + Settings.cluster.ordinal
        synchronized(levels) { levels[key]?.let { return it } }
        val computed = computeLevel(z)
        synchronized(levels) { levels[key] = computed }
        return computed
    }

    /** Whether integer zoom [z] is already cached (instant lookup, no loading pill needed). */
    fun isReady(z: Int) = synchronized(levels) { levels.containsKey(z * 16 + Settings.cluster.ordinal) }

    /**
     * Grid cells of [radiusDp] px at integer zoom [z], grouped per country; adjacent cells (same country)
     * whose centroids lie closer than that radius are merged (largest first). Prevents clusters from jumping
     * during continuous pan/zoom and ensures places never merge across country borders.
     */
    private fun computeLevel(z: Int): ZoomLevel {
        val scaleZ = 512.0 * (1L shl z) * density
        val radius = radiusDp(z) * density
        val cell = radius / scaleZ
        val cells = HashMap<Long, CellAccumulator>()
        for (i in 0 until n) {
            val x = wx[i]; val y = wy[i]; val c = countryOf[i]
            val kx = floor(x / cell).toLong(); val ky = floor(y / cell).toLong()
            cells.getOrPut(cellKey(c, kx, ky)) { CellAccumulator(c, kx, ky) }.add(i, x, y)
        }
        val list = cells.values.sortedByDescending { it.n }
        for (a in list) {
            if (a.absorbed) continue
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val b = cells[cellKey(a.country, a.kx + dx, a.ky + dy)] ?: continue
                if (b.absorbed || b.n > a.n) continue
                val d = hypot((a.sx / a.n - b.sx / b.n) * scaleZ, (a.sy / a.n - b.sy / b.n) * scaleZ)
                if (d < radius) { a.absorb(b); b.absorbed = true }
            }
        }
        val perCountry = Array(countryCodes.size) { ArrayList<Cluster>() }
        for (v in list) if (!v.absorbed) perCountry[v.country] += Cluster("p$z:${v.country}:${v.kx}:${v.ky}", v.sx / v.n, v.sy / v.n, v.members.copyOf(v.n).also { it.sort() }, country = v.country)
        return ZoomLevel(Array(perCountry.size) { perCountry[it].toTypedArray() })
    }

    // ---- Visibility queries ----

    private fun scaleAt(z: Int) = 512.0 * 2.0.pow(z) * density

    /** Keep [g] as one marker at zoom [z]? Small enough on screen and still below its max zoom ceiling. */
    private fun staysCollapsed(g: MapGroup, z: Int) = z < g.zMax && max(g.x1 - g.x0, g.y1 - g.y0) * scaleAt(z) <= thresholdPx * Settings.cluster.radius

    /**
     * Clusters at integer zoom [z] inside bounds [b] (x0, y0, x1, y1 in world units). If more than [max], keep the
     * largest (bounded marker count = bounded Compose work & thumbnail loads).
     */
    fun visible(z: Int, b: DoubleArray, max: Int): List<Cluster> {
        val out = ArrayList<Cluster>()
        for (g in roots) collect(g, z, b, out)
        val merged = mergeOverlapping(out, z)
        return if (merged.size <= max) merged else merged.sortedByDescending { it.count }.take(max)
    }

    /**
     * Merge markers that nearly overlap on screen: the largest absorbs the others. Name becomes their smallest
     * common ancestor (Italy + Croatia + Slovenia -> Southern Europe), or the city name if all are places in one
     * country. Merged markers always break apart again on zoom-in.
     */
    private fun mergeOverlapping(list: List<Cluster>, z: Int): List<Cluster> {
        if (list.size < 2) return list
        val s = scaleAt(z)
        val limit = overlapPx / s
        val ordered = list.sortedByDescending { it.count }
        val out = ArrayList<Cluster>()
        val buckets = ArrayList<ArrayList<Cluster>>()
        for (c in ordered) {
            val i = out.indexOfFirst { hypot(it.wx - c.wx, it.wy - c.wy) < limit }
            if (i < 0) { out += c; buckets += arrayListOf(c) } else buckets[i] += c
        }
        if (buckets.all { it.size == 1 }) return list
        return buckets.map { group ->
            if (group.size == 1) return@map group[0]
            val head = group[0]
            val members = IntArray(group.sumOf { it.count }).also { a -> var p = 0; for (c in group) { System.arraycopy(c.members, 0, a, p, c.count); p += c.count }; a.sort() }
            val allPlaces = group.all { it.level == MapLevel.PLACE }
            val common = if (allPlaces && group.all { it.country == head.country }) null else commonAncestor(group)
            Cluster(
                "m:" + group.joinToString("+") { it.key }, head.wx, head.wy, members,
                level = common?.cluster?.level ?: MapLevel.PLACE, name = common?.cluster?.name,
                country = if (common == null) head.country else -1, merged = true,
            )
        }
    }

    /** Smallest ancestor group (country, region, continent) containing all clusters, or null. */
    private fun commonAncestor(list: List<Cluster>): MapGroup? {
        fun chain(c: Cluster): List<MapGroup> {
            var g: MapGroup? = groups[c.key] ?: if (c.country >= 0) groups["l:${countryCodes[c.country]}"] else null
            val out = ArrayList<MapGroup>()
            while (g != null) { out += g; g = parent[g.cluster.key] }
            return out
        }
        val first = chain(list[0])
        val rest = list.drop(1).map { chain(it).toSet() }
        return first.firstOrNull { g -> rest.all { g in it } }
    }

    /** Collect visible markers for group [g] at zoom [z] within [b] (or all if [b] is null). */
    private fun collect(g: MapGroup, z: Int, b: DoubleArray?, out: ArrayList<Cluster>) {
        fun inside(c: Cluster) = b == null || (c.wx >= b[0] && c.wx <= b[2] && c.wy >= b[1] && c.wy <= b[3])
        if (b != null && (g.x1 < b[0] || g.x0 > b[2] || g.y1 < b[1] || g.y0 > b[3])) return
        if (staysCollapsed(g, z)) { if (inside(g.cluster)) out += g.cluster; return }
        if (g.children.isNotEmpty()) { for (k in g.children) collect(k, z, b, out); return }
        for (c in level(z).perCountry[g.cluster.country]) if (inside(c)) out += c
    }

    /** Does cluster [c] split into multiple markers at integer zoom [z]? */
    fun splits(c: Cluster, z: Int): Boolean {
        if (c.merged) return true
        if (c.level != MapLevel.PLACE) {
            val g = groups[c.key] ?: return false
            return ArrayList<Cluster>().also { collect(g, z, null, it) }.size > 1
        }
        if (c.country < 0) return false
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.members) { val x = wx[i]; val y = wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        val margin = 3 * radiusDp(z) * density / scaleAt(z)
        var found = 0
        for (k in level(z).perCountry[c.country]) {
            if (k.wx < x0 - margin || k.wx > x1 + margin || k.wy < y0 - margin || k.wy > y1 + margin) continue
            if (intersectsSorted(k.members, c.members) && ++found > 1) return true
        }
        return false
    }

    /** Do two ascending sorted arrays share at least one element? */
    private fun intersectsSorted(a: IntArray, b: IntArray): Boolean {
        var i = 0; var j = 0
        while (i < a.size && j < b.size) { val x = a[i]; val y = b[j]; if (x == y) return true; if (x < y) i++ else j++ }
        return false
    }

    private val placeNames = HashMap<String, String>()

    /** Display name under a marker: continent/region/country, or for a place the most frequent city + country. */
    fun name(c: Cluster): String {
        c.name?.let { return it }
        synchronized(placeNames) { placeNames[c.key]?.let { return it } }
        val counts = HashMap<String, Int>()
        val step = max(1, c.count / 2000)
        var i = 0
        while (i < c.count) { markers.city[c.members[i]]?.let { counts[it] = (counts[it] ?: 0) + 1 }; i += step }
        val city = counts.maxByOrNull { it.value }?.key
        val country = if (c.country >= 0) countryNames[c.country] else markers.country[c.representative]
        val out = listOfNotNull(city, country?.takeIf { countryCodes.getOrNull(c.country) != UNKNOWN }).joinToString(", ")
        synchronized(placeNames) { placeNames[c.key] = out }
        return out
    }

    /** Member bounding box (world units) of a cluster, used for zoom-to-fit. */
    fun bounds(c: Cluster): DoubleArray {
        groups[c.key]?.let { return doubleArrayOf(it.x0, it.y0, it.x1, it.y1) }
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.members) { val x = wx[i]; val y = wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Precompute all zoom levels up to [upTo] in the background after building the tree. */
    fun warmUp(upTo: Int) { for (z in 0..upTo) level(z) }

    companion object {
        const val UNKNOWN = "??"
    }
}
