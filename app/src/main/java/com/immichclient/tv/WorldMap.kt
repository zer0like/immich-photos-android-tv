package com.immichclient.tv

import android.graphics.Point
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * World map like Google Photos / Immich: all geotagged photos clustered on a vector/satellite map, zooming splits
 * clusters down to individual photos. Base layer = MapLibre using Immich's vector tile style (tiles.immich.cloud via
 * /server/config). Clustering is done on-device (integer-zoom grids + neighbor merging on a background thread) and
 * drawn as Compose overlays above the map so clusters can show thumbnails + counts, accept D-Pad focus, and move
 * smoothly in sync with the camera (position = worldCoord * scale, zero JNI calls per frame).
 *
 * Controls: D-Pad = focus next cluster in that direction (or pan when none); OK = zoom into cluster, or open viewer
 * at the deepest level (or when it no longer splits); FF/RW = zoom in/out; Back = fly back to whole world, Back
 * again = leave map. Hold OK = open all photos of the cluster as a grid (MapPhotos.kt), Play/Pause = slideshow.
 *
 * Memory / ANR notes: zooming out from deep levels used to cluster the whole world at the old fine level, creating
 * thousands of markers and 2-3s GC stalls on 2 GB boxes. Now during camera motion we never cluster finer than the
 * current level, cap markers at [MAX_MARKERS], offload heavy work to Dispatchers.Default, and destroy the MapView
 * whenever the screen goes off-screen (screensaver, Home) because MapLibre holds ~100 MB native memory.
 *
 * Smoothness: renders into a SurfaceView (hardware overlay, no TextureView copy per frame), animates via easeCamera
 * (direct interpolation instead of flyTo which zooms out and loads intermediate tiles), and keeps existing clusters
 * during camera movement—reclustering only once the camera settles to avoid mid-animation marker churn.
 */

/** Map camera state: center in Web Mercator world units (0..1) and scale in px per world unit. */
@Immutable
data class Camera(val cx: Double, val cy: Double, val zoom: Double, val scale: Double)

fun worldX(lon: Double) = (lon + 180) / 360
fun worldY(lat: Double): Double {
    val s = sin(lat.coerceIn(-85.05, 85.05) * PI / 180)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}
fun lonFrom(x: Double) = x * 360 - 180
fun latFrom(y: Double) = (2 * atan(exp((0.5 - y) * 2 * PI)) - PI / 2) * 180 / PI

/**
 * Deepest zoom level (default, ClusterLevel.NORMAL): at z=14, 1 px ~= 1.5 m (at 52° latitude), so a cluster of
 * [RADIUS_DEEP_DP] ~= 400 m. A resort or neighborhood stays one cluster that opens straight into the viewer.
 * The Cluster level setting scales the radius (x0.7 / x1.5) and shifts maxZoom to 16 / 12.
 */
private val maxZoom get() = Settings.cluster.maxZoom
private const val MARKER_DP = 56f
/**
 * Maximum simultaneous markers (largest clusters take priority): each marker is a Compose layer with a thumbnail.
 * Within a single zoom viewport there are normally only a few dozen.
 */
private const val MAX_MARKERS = 90

/** Satellite layer: Sentinel-2 cloudless 2023 by EOX (CC BY-NC-SA 4.0, free non-commercial use with attribution). */
private const val SAT_TILES = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg"
const val SAT_ATTRIBUTION = "EOxCloudless cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2023)"

/** Active MapView while the map section is displayed, so App.onTrimMemory can free its tile cache under memory pressure. */
@Volatile var openMap: MapView? = null
/**
 * Debug/benchmark intent extras (`--ez map_texture true`, `--ei map_lod 0`, `--ei map_fps 30`, `--ez map_all true`):
 * TextureView instead of SurfaceView, tile LOD shift, max FPS, keep all style layers.
 * Measured on NVIDIA Shield: LOD shift -1 (tiles from 1 zoom level lower, 4x fewer tiles) improved ~30 -> ~55 fps
 * and dropped missed frames from 19% to 6%.
 */
@Volatile var mapTexture = false
@Volatile var mapLod = -1
@Volatile var mapFps = 0
@Volatile var mapAllLayers = false
/** MapView height in px when scaled up as a surface (0 = native resolution). */
@Volatile var mapRes = 0
/** Extra LOD shift during the Back flight to whole-world view. */
@Volatile var mapFlyLod = -2
/** In-memory MapLibre tile cache; disabled by default to save ~25 MB PSS while disk cache still serves tiles fast. */
@Volatile var mapCache = false
@Volatile var mapPrefetch = -1

/** Action triggered by OK / hold OK / Play-Pause on a cluster. */
enum class MapAction { VIEW, GRID, SLIDESHOW }

/**
 * Style layers from tiles.immich.cloud (Protomaps dark, 70 layers) that add nothing to a TV photo map while slowing
 * down tile rendering (buildings, POIs, service roads, minor labels at z15+). Every layer adds main-thread evaluation
 * and GPU draw work shared with Compose markers.
 */
private val HIDDEN_LAYERS = listOf(
    "buildings", "pois", "address_label", "roads_oneway", "roads_labels_minor", "water_waterway_label", "water_stream",
    "roads_minor_service", "roads_minor_service_casing", "roads_taxiway", "roads_runway", "landuse_runway", "landuse_aerodrome",
    "landuse_hospital", "landuse_school", "landuse_zoo", "landuse_industrial", "landuse_pedestrian", "landuse_pier", "roads_pier",
    "earth_label_islands",
)

class MapState(private val scope: CoroutineScope) {
    var markers by mutableStateOf<Markers?>(null)
        private set
    /** Cluster tree + zoom levels (MapClusters.kt), built in the background once markers arrive. */
    var index by mutableStateOf<MapIndex?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var styleUrl by mutableStateOf<String?>(null)
    /** Style enum matching [styleUrl]. */
    var styleKind by mutableStateOf<MapStyle?>(null)
    /** Whether the initial start view (Europe / top country) has already been applied once this session. */
    var started = false
    /** Precomputed Web Mercator coordinates for every marker. */
    var wx = DoubleArray(0); var wy = DoubleArray(0)
    /** Current camera, updated every frame during movement. */
    val camera = mutableStateOf(Camera(0.5, 0.5, 1.0, 1024.0))
    var clusters by mutableStateOf<List<Cluster>>(emptyList())
    var focus by mutableStateOf<String?>(null)
    /** Camera saved when leaving the map section, restored on return. */
    var savedCamera: Camera? = null
    /** "Show on map" from the viewer: fly here once the map is ready. */
    var targetLocation by mutableStateOf<Pair<Double, Double>?>(null)
    /** Incremented to re-request D-Pad focus on the map after closing the viewer. */
    var requestFocusTick by mutableIntStateOf(0)
    /** Counter of ongoing background cluster computations (> 0 shows loading pill). */
    var computingCount by mutableIntStateOf(0)
    /** True while the latest rendered map frame reported incomplete tiles. */
    var tilesPending by mutableStateOf(false)

    init {
        scope.launch {
            runCatching { Immich.markers() }
                .onSuccess { m ->
                    val dm = Language.ctx.resources.displayMetrics
                    // Keep a group collapsed while smaller than ~1/3 of the screen's short side
                    val threshold = 0.32 * min(dm.widthPixels, dm.heightPixels)
                    val (x, y, idx) = withContext(Dispatchers.Default) {
                        val x = DoubleArray(m.count) { worldX(m.lon[it]) }; val y = DoubleArray(m.count) { worldY(m.lat[it]) }
                        val t = System.currentTimeMillis()
                        val idx = MapIndex(m, x, y, dm.density, threshold)
                        Log.i("Photos", "map: ${m.count} markers, ${idx.countryCodes.size} countries, tree in ${System.currentTimeMillis() - t} ms")
                        Triple(x, y, idx)
                    }
                    wx = x; wy = y; markers = m; index = idx
                    // Precompute all integer zoom levels once in the background so subsequent interactions are instant
                    launch(Dispatchers.Default) {
                        val t = System.currentTimeMillis()
                        runCatching { idx.warmUp(Settings.cluster.maxZoom.toInt()) }
                        Log.i("Photos", "map: zoom levels ready in ${System.currentTimeMillis() - t} ms")
                    }
                }
                .onFailure { error = it.message; Log.w("Photos", "markers", it) }
        }
    }

    /** Fetch style URL for the selected map style (satellite = dark style with Sentinel-2 underneath). */
    suspend fun loadStyle(s: MapStyle) {
        if (styleKind == s && styleUrl != null) return
        styleUrl = Immich.mapStyleUrl(light = s == MapStyle.LIGHT)
        styleKind = s
    }

    /** Country with the most photos -> bounding box (world units) trimmed to 3rd-97th percentile to drop outliers. */
    fun topCountryBounds(): DoubleArray? {
        val m = markers ?: return null
        val country = m.iso.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val idx = (0 until m.count).filter { m.iso[it] == country }
        if (idx.size < 2) return null
        val xs = idx.map { wx[it] }.sorted(); val ys = idx.map { wy[it] }.sorted()
        val a = (idx.size * 0.03).toInt(); val b = (idx.size * 0.97).toInt().coerceAtMost(idx.size - 1)
        return doubleArrayOf(xs[a], ys[a], xs[b], ys[b])
    }

    fun showAsset(a: Asset) { if (a.lat != null && a.lon != null) { targetLocation = a.lat to a.lon; requestFocusTick++ } }

    /** Display name of a cluster (continent, region, country, or city + country). */
    fun name(c: Cluster): String = index?.name(c) ?: listOfNotNull(markers?.city?.get(c.representative), markers?.country?.get(c.representative)).joinToString(", ")
}

/** ImageSource for opening the viewer from the map: holds IDs only and fetches full Assets lazily. */
class MapSource(private val ids: List<String>, private val scope: CoroutineScope) : ImageSource {
    private val cache = mutableStateMapOf<String, Asset>()
    private val inflight = HashSet<String>()
    override val count get() = ids.size
    override fun at(i: Int): Asset? {
        val id = ids.getOrNull(i) ?: return null
        cache[id]?.let { return it }
        if (inflight.add(id)) scope.launch {
            runCatching { Immich.asset(id) }.onSuccess { cache[id] = it }.onFailure { inflight.remove(id); Log.w("Photos", "asset $id", it) }
        }
        return null
    }
}

/** Drives camera movements, clustering, D-Pad focus, and remote key handling while the map is visible. */
private class MapController(
    val state: MapState, val scope: CoroutineScope, val w: Int, val h: Int, val density: Float, val worldZoom: Double,
    val open: (Cluster, MapAction) -> Unit,
) {
    /** Compose state so style is reapplied when MapView is recreated after returning from background. */
    var map by mutableStateOf<MapLibreMap?>(null)
    private var busy = false
    private var pendingRecluster = false
    /** True if an idle request arrived while a clustering pass was already running. */
    private var pendingIdle = false
    private var prevBounds: DoubleArray? = null
    private var prevZ = -1
    /** Marker index to focus once camera settles (after zoom in/out, focuses the cluster holding that photo). */
    private var focusWanted = -1
    /** After panning off-screen, pick the next cluster in that direction relative to the panned previous focus spot. */
    private var afterPan: Triple<Int, Int, Offset>? = null
    private var lastFocusPoint = Offset(w / 2f, h / 2f)
    /** Target zoom of an active animation so repeated FF/RW presses accumulate cleanly without waiting. */
    private var zoomTarget: Double? = null

    // Frame timing metrics per camera move
    private var perfStart = 0L
    private var perfFrames = 0
    private var perfRenderMs = 0.0
    private var perfMaxMs = 0.0

    fun screenPos(c: Cluster, cam: Camera = state.camera.value) =
        Offset(((c.wx - cam.cx) * cam.scale + w / 2).toFloat(), ((c.wy - cam.cy) * cam.scale + h / 2).toFloat())

    fun focusedCluster() = state.clusters.firstOrNull { it.key == state.focus }

    fun onFrameFinished(complete: Boolean, renderSec: Double) {
        if (state.tilesPending == complete) state.tilesPending = !complete
        if (perfStart == 0L) return
        perfFrames++; val ms = renderSec * 1000; perfRenderMs += ms; if (ms > perfMaxMs) perfMaxMs = ms
    }

    fun readCamera(m: MapLibreMap, idle: Boolean) {
        val p = m.cameraPosition
        val t = p.target ?: return
        state.camera.value = Camera(worldX(t.longitude), worldY(t.latitude), p.zoom, 512.0 * 2.0.pow(p.zoom) * density)
        if (idle) {
            zoomTarget = null
            if (m.tileLodZoomShift != mapLod.toDouble()) m.tileLodZoomShift = mapLod.toDouble()
            if (perfStart != 0L && perfFrames > 0) {
                val dur = System.currentTimeMillis() - perfStart
                Log.i("Photos", "map move: $dur ms, $perfFrames frames (%.0f fps), render avg %.1f ms max %.1f ms, zoom %.2f".format(
                    perfFrames * 1000.0 / dur, perfRenderMs / perfFrames, perfMaxMs, p.zoom))
            }
            perfStart = 0L
        } else if (perfStart == 0L) { perfStart = System.currentTimeMillis(); perfFrames = 0; perfRenderMs = 0.0; perfMaxMs = 0.0 }
        recluster(idle)
    }

    /**
     * Recompute visible clusters when needed. While moving ([idle] = false), keep the previous zoom level's
     * clustering to prevent marker churn mid-animation. If panning/zooming moves outside the cached bbox, re-query,
     * never finer than the previous zoom level (avoiding thousands of fine markers during rapid zoom-out). Once idle,
     * cluster at the true integer zoom level on Dispatchers.Default.
     */
    fun recluster(idle: Boolean, force: Boolean = false) {
        val idx = state.index ?: return
        val cam = state.camera.value
        val trueZ = floor(cam.zoom + 1e-6).toInt().coerceIn(0, 22)
        val hw = w / 2.0 / cam.scale; val hh = h / 2.0 / cam.scale
        val b = prevBounds
        val outOfBounds = b == null || cam.cx - hw < b[0] || cam.cy - hh < b[1] || cam.cx + hw > b[2] || cam.cy + hh > b[3]
        val z = when { idle || prevZ < 0 -> trueZ; outOfBounds -> min(prevZ, trueZ); else -> prevZ }
        val needed = force || z != prevZ || outOfBounds
        if (!needed) { if (idle) chooseFocus(); return }
        if (busy) { pendingRecluster = true; pendingIdle = pendingIdle || idle; return }
        busy = true
        val margin = 2 * radiusDp(z) * density / cam.scale
        val bounds = doubleArrayOf(cam.cx - hw - margin, cam.cy - hh - margin, cam.cx + hw + margin, cam.cy + hh + margin)
        val slow = !idx.isReady(z)
        if (slow) state.computingCount++
        scope.launch {
            val list = try { withContext(Dispatchers.Default) { idx.visible(z, bounds, MAX_MARKERS) } } finally { if (slow) state.computingCount-- }
            prevBounds = bounds; prevZ = z
            state.clusters = list
            busy = false
            if (pendingRecluster) { val s2 = idle || pendingIdle; pendingRecluster = false; pendingIdle = false; recluster(s2) } else if (idle) chooseFocus()
        }
    }

    /** Select focused cluster when camera settles: requested photo -> cluster containing it; else existing; else nearest. */
    private fun chooseFocus() {
        val l = state.clusters
        if (l.isEmpty()) { state.focus = null; return }
        val wanted = focusWanted
        if (wanted >= 0) {
            focusWanted = -1
            l.firstOrNull { it.members.binarySearch(wanted) >= 0 }?.let { setFocus(it); return }
        }
        afterPan?.let { (dx, dy, from) ->
            afterPan = null
            inDirection(l, from, dx, dy)?.let { setFocus(it); return }
        }
        if (l.any { it.key == state.focus }) return
        setFocus(l.minByOrNull { (screenPos(it) - lastFocusPoint).getDistance() }!!)
    }

    private fun setFocus(c: Cluster) { state.focus = c.key; lastFocusPoint = screenPos(c) }

    /** Best cluster in direction (dx, dy) from [from]: strongly prefers clusters aligned along the movement axis. */
    private fun inDirection(l: List<Cluster>, from: Offset, dx: Int, dy: Int): Cluster? {
        var best: Cluster? = null; var bestScore = Float.MAX_VALUE
        for (c in l) {
            val p = screenPos(c)
            if (p.x < 0 || p.y < 0 || p.x > w || p.y > h) continue
            val along = (p.x - from.x) * dx + (p.y - from.y) * dy
            if (along < 6f) continue
            val cross = abs((p.x - from.x) * dy) + abs((p.y - from.y) * dx)
            val score = along + 2.5f * cross
            if (score < bestScore) { bestScore = score; best = c }
        }
        return best
    }

    /** D-Pad arrow: move focus to next cluster in that direction, or pan the map. Returns false to hand off to drawer. */
    fun navigate(dx: Int, dy: Int): Boolean {
        val m = map ?: return false
        val f = focusedCluster()
        val from = f?.let { screenPos(it) } ?: lastFocusPoint
        inDirection(state.clusters, from, dx, dy)?.let { setFocus(it); return true }
        val cam = state.camera.value
        // No more clusters in that direction: pan the map. Only at world zoom does Left exit to the navigation drawer.
        if (dx < 0 && cam.zoom <= worldZoom + 0.2) return false
        val step = 0.45
        val nx = cam.cx + dx * step * w / cam.scale
        val ny = (cam.cy + dy * step * h / cam.scale).coerceIn(0.02, 0.98)
        afterPan = Triple(dx, dy, Offset(from.x - (dx * step * w).toFloat(), from.y - (dy * step * h).toFloat()))
        m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(latFrom(ny), lonFrom(nx))), 380)
        return true
    }

    /** Guard against overlapping OK taps while cluster splitting is computed in background. */
    private var selecting = false

    /**
     * OK: zoom into the focused cluster until it splits; if it's a single photo or won't split even at max zoom,
     * open the viewer directly. Splitting is evaluated on Dispatchers.Default.
     */
    fun select() {
        val m = map ?: return
        val idx = state.index ?: return
        val c = focusedCluster() ?: return
        if (c.count == 1) { open(c, MapAction.VIEW); return }
        if (selecting) return
        val cam = state.camera.value
        val v = idx.bounds(c)
        val spanX = (v[2] - v[0]) * cam.scale; val spanY = (v[3] - v[1]) * cam.scale
        val fit = cam.zoom + log2(min(0.55 * w / max(spanX, 1e-6), 0.55 * h / max(spanY, 1e-6)))
        val targetZoom = min(fit, maxZoom)
        // No meaningful zoom gain left: open viewer
        if (targetZoom - cam.zoom < 0.5) { open(c, MapAction.VIEW); return }
        val dz = floor(targetZoom + 1e-6).toInt()
        selecting = true
        val slow = !idx.isReady(dz)
        if (slow) state.computingCount++
        scope.launch {
            val willSplit = try { withContext(Dispatchers.Default) { idx.splits(c, dz) } } finally { selecting = false; if (slow) state.computingCount-- }
            // Still one cluster at target zoom (e.g. a resort): open viewer
            if (!willSplit) { open(c, MapAction.VIEW); return@launch }
            focusWanted = c.representative
            zoomTarget = targetZoom
            m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latFrom((v[1] + v[3]) / 2), lonFrom((v[0] + v[2]) / 2)), targetZoom), 650)
        }
    }

    /** Hold OK: open all cluster photos as a grid; Play/Pause: start slideshow immediately. */
    fun openPhotos(action: MapAction) { focusedCluster()?.let { open(it, action) } }

    /** FF / RW: zoom in or out around the focused cluster, accumulating smoothly during active animations. */
    fun zoom(delta: Double) {
        val m = map ?: return
        val cam = state.camera.value
        val base = zoomTarget ?: cam.zoom
        val next = (base + delta).coerceIn(worldZoom, maxZoom)
        if (abs(next - cam.zoom) < 0.01) return
        val f = focusedCluster()
        focusWanted = f?.representative ?: -1
        val p = f?.let { screenPos(it) } ?: Offset(w / 2f, h / 2f)
        zoomTarget = next
        m.easeCamera(CameraUpdateFactory.zoomBy(next - cam.zoom, Point(p.x.roundToInt(), p.y.roundToInt())), 400)
    }

    /** Compute a CameraPosition fitting [bounds] (world units) with margin. */
    fun fitBounds(b: DoubleArray, margin: Double = 0.78): CameraPosition {
        val dx = (b[2] - b[0]).coerceAtLeast(1e-6); val dy = (b[3] - b[1]).coerceAtLeast(1e-6)
        val z = log2(min(margin * w / (dx * 512 * density), margin * h / (dy * 512 * density))).coerceIn(worldZoom, maxZoom - 1)
        return CameraPosition.Builder().target(LatLng(latFrom((b[1] + b[3]) / 2), lonFrom((b[0] + b[2]) / 2))).zoom(z).build()
    }

    /** Back: fly out to whole-world view in one move. Returns false if already at world level (lets Back exit map). */
    fun back(): Boolean {
        val m = map ?: return false
        val cam = state.camera.value
        if (cam.zoom <= worldZoom + 0.2) return false
        focusWanted = focusedCluster()?.representative ?: -1
        zoomTarget = worldZoom
        // Use coarser tile LOD during a multi-level flight to keep 60 fps instead of loading every intermediate tile level
        if (mapFlyLod != 0) m.tileLodZoomShift = (mapLod + mapFlyLod).toDouble()
        m.easeCamera(worldCamera(worldZoom), 800)
        return true
    }

    fun flyTo(lat: Double, lon: Double) {
        val m = map ?: return
        focusWanted = -1
        lastFocusPoint = Offset(w / 2f, h / 2f)
        zoomTarget = maxZoom
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), maxZoom), 1200)
    }
}

private const val WORLD_LAT = 20.0
private const val WORLD_LON = 10.0
private fun worldCamera(zoom: Double) = CameraUpdateFactory.newLatLngZoom(LatLng(WORLD_LAT, WORLD_LON), zoom)
/** Europe preset for MapStart.EUROPE: Ireland to Black Sea, Sicily to northern Norway. */
private val EUROPE_BOUNDS = doubleArrayOf(worldX(-12.0), worldY(66.0), worldX(34.0), worldY(35.0))

/** Parse saved `"lat,lon,zoom"` camera position (last viewed location). */
private fun parseLastLocation(): CameraPosition? {
    val p = Settings.mapLast.split(',').mapNotNull { it.toDoubleOrNull() }
    if (p.size != 3) return null
    return CameraPosition.Builder().target(LatLng(p[0], p[1])).zoom(p[2]).build()
}

/**
 * World map section. When the app leaves the foreground (screensaver, Home, another app), the MapView is destroyed
 * completely so MapLibre releases its ~100 MB of native GL buffers and tiles; returning restores the saved camera.
 */
@Composable
fun MapScreen(state: MapState, nav: Nav) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_STOP -> { visible = false; Log.i("Photos", "map: app stopped, tearing down MapView") }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Only MapView is torn down; focus and clusters stay intact so focus doesn't jump to the drawer
    MapContent(state, nav, visible)
}

@Composable
private fun MapContent(state: MapState, nav: Nav, visible: Boolean) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    var hintUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 6000) }
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(hintUntil) { showHint = true; delay((hintUntil - System.currentTimeMillis()).coerceAtLeast(0)); showHint = false }
    var styleError by remember { mutableStateOf(false) }
    var styleReady by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val pr = density.density
        // Whole world across screen width (poles cropped); MapLibre world = 512 * 2^zoom logical px
        val worldZoom = log2(w / (512.0 * pr))
        val controller = remember(w, h) {
            MapController(state, scope, w, h, pr, worldZoom) { c, action ->
                val m = state.markers ?: return@MapController
                val ids = c.members.map { m.id[it] }
                val title = state.name(c).ifEmpty { Language.s(R.string.map) }
                when (action) {
                    MapAction.GRID -> nav.open(Screen.MapPhotos(title, ids, nav.scope))
                    else -> nav.view(MapSource(ids, nav.scope), 0, title, action == MapAction.SLIDESHOW, null) { state.requestFocusTick++ }
                }
            }
        }
        val ctx = LocalContext.current
        val scale = if (mapRes in 1 until h) mapRes.toFloat() / h else 1f
        val mapView: MapView? = if (!visible) null else remember(w, h) {
            MapLibre.getInstance(ctx)
            val saved = state.savedCamera
            // Start view: restore saved camera on return, otherwise follow setting (world / Europe / last viewed;
            // top country is applied below once markers load)
            val camera = when {
                saved != null -> CameraPosition.Builder().target(LatLng(latFrom(saved.cy), lonFrom(saved.cx))).zoom(saved.zoom).build()
                Settings.mapStart == MapStart.EUROPE -> controller.fitBounds(EUROPE_BOUNDS)
                Settings.mapStart == MapStart.LAST -> parseLastLocation() ?: CameraPosition.Builder().target(LatLng(WORLD_LAT, WORLD_LON)).zoom(worldZoom).build()
                else -> CameraPosition.Builder().target(LatLng(WORLD_LAT, WORLD_LON)).zoom(worldZoom).build()
            }
            // SurfaceView (textureMode false): own hardware surface composited by SurfaceFlinger under Compose overlays,
            // avoiding extra per-frame GPU texture copies. Optional [mapRes] scales a lower-res MapView up while
            // adjusting pixelRatio so Camera math (512 * 2^zoom * density px per world) remains exact.
            val options = MapLibreMapOptions.createFromAttributes(ctx).camera(camera)
                .textureMode(mapTexture).pixelRatio(pr * scale)
                .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            MapView(ctx, options).apply { isFocusable = false; isFocusableInTouchMode = false }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        if (mapView != null) DisposableEffect(mapView) {
            mapView.onCreate(null)
            val observer = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> {}
                }
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
            lifecycle.addObserver(observer)
            openMap = mapView
            mapView.addOnDidFailLoadingMapListener { Log.w("Photos", "map style: $it"); styleError = true }
            mapView.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { complete, _, renderSec -> controller.onFrameFinished(complete, renderSec) })
            mapView.getMapAsync { m ->
                m.uiSettings.apply { setAllGesturesEnabled(false); isCompassEnabled = false; isLogoEnabled = false; isAttributionEnabled = false }
                m.setMinZoomPreference(worldZoom)
                m.setMaxZoomPreference(maxZoom)
                if (mapLod != 0) m.tileLodZoomShift = mapLod.toDouble()
                if (mapFps > 0) mapView.setMaximumFps(mapFps)
                if (!mapCache) m.setTileCacheEnabled(false)
                if (mapPrefetch >= 0) m.prefetchZoomDelta = mapPrefetch
                Log.i("Photos", "map: lod $mapLod, flyLod $mapFlyLod, res $mapRes, texture $mapTexture, fps ${mapFps.takeIf { it > 0 } ?: "free"}, allLayers $mapAllLayers, cache $mapCache, prefetch ${m.prefetchZoomDelta}, pixelRatio ${mapView.pixelRatio}")
                m.addOnCameraMoveListener { controller.readCamera(m, idle = false) }
                m.addOnCameraIdleListener { controller.readCamera(m, idle = true) }
                controller.map = m
                controller.readCamera(m, idle = true)
                // Sanity-check projection math against MapLibre once
                val p = m.projection.toScreenLocation(LatLng(52.0, 5.0))
                val cam = state.camera.value
                Log.i("Photos", "map projection: maplibre=(${p.x},${p.y}) ours=(${(worldX(5.0) - cam.cx) * cam.scale + w / 2}, ${(worldY(52.0) - cam.cy) * cam.scale + h / 2}) pr=$pr/${mapView.pixelRatio}")
            }
            onDispose {
                state.savedCamera = state.camera.value
                if (Settings.mapStart == MapStart.LAST) state.camera.value.let { c ->
                    Settings.mapLast = "%.5f,%.5f,%.2f".format(java.util.Locale.ROOT, latFrom(c.cy), lonFrom(c.cx), c.zoom)
                }
                lifecycle.removeObserver(observer)
                if (openMap === mapView) openMap = null
                controller.map = null
                mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            }
        }
        // Fetch and apply map style once both MapLibreMap and style URL are available
        val styleChoice = Settings.mapStyle
        LaunchedEffect(styleChoice) { state.loadStyle(styleChoice) }
        LaunchedEffect(state.styleUrl, state.styleKind, controller.map) {
            val url = state.styleUrl ?: return@LaunchedEffect
            val kind = state.styleKind ?: return@LaunchedEffect
            if (controller.map == null) return@LaunchedEffect
            controller.map?.setStyle(Style.Builder().fromUri(url)) { style ->
                styleReady = true
                if (!mapAllLayers) {
                    val n = HIDDEN_LAYERS.count { style.removeLayer(it) }
                    Log.i("Photos", "map style ${kind.name}: removed $n layers, ${style.layers.size} remaining")
                }
                // Satellite = hybrid: strip fill/background polygons and place Sentinel-2 underneath roads, borders, and labels
                if (kind == MapStyle.SATELLITE) {
                    for (l in style.layers.toList()) if (l is org.maplibre.android.style.layers.FillLayer || l is org.maplibre.android.style.layers.BackgroundLayer) style.removeLayer(l)
                    val tiles = org.maplibre.android.style.sources.TileSet("2.2.0", SAT_TILES).apply { minZoom = 0f; maxZoom = 15f; attribution = SAT_ATTRIBUTION }
                    style.addSource(org.maplibre.android.style.sources.RasterSource("sat", tiles, 256))
                    style.addLayerAt(org.maplibre.android.style.layers.RasterLayer("sat", "sat"), 0)
                }
            }
        }
        // Cluster index ready -> compute initial clusters
        LaunchedEffect(state.index) { if (state.index != null) controller.recluster(idle = true, force = true) }
        // Cluster level changed while map is open -> update maxZoom and recluster
        val clusterChoice = Settings.cluster
        LaunchedEffect(clusterChoice) {
            while (controller.map == null) delay(50)
            controller.map?.setMaxZoomPreference(clusterChoice.maxZoom)
            if (state.index != null) controller.recluster(idle = true, force = true)
        }
        // "Country with the most photos" start view: once markers arrive (first visit only)
        LaunchedEffect(state.markers) {
            if (state.markers == null || state.started || state.savedCamera != null || Settings.mapStart != MapStart.TOP_COUNTRY) return@LaunchedEffect
            state.started = true
            val b = withContext(Dispatchers.Default) { state.topCountryBounds() } ?: return@LaunchedEffect
            while (controller.map == null) delay(50)
            controller.map?.easeCamera(CameraUpdateFactory.newCameraPosition(controller.fitBounds(b)), 900)
        }
        // "Show on map" target from viewer
        LaunchedEffect(state.targetLocation) {
            val (lat, lon) = state.targetLocation ?: return@LaunchedEffect
            while (controller.map == null) delay(50)
            state.targetLocation = null
            controller.flyTo(lat, lon)
        }
        LaunchedEffect(state.requestFocusTick) { delay(60); runCatching { fr.requestFocus() } }
        var okHeld by remember { mutableStateOf(false) }

        // derivedStateOf: camera changes every frame, only recompose when canGoBack flips
        val canGoBack by remember { derivedStateOf { state.camera.value.zoom > worldZoom + 0.2 } }
        DisposableEffect(Unit) { Keys.okRepeatAllowed = true; onDispose { Keys.okRepeatAllowed = false } }
        BackHandler(enabled = canGoBack) { hintUntil = System.currentTimeMillis() + 3000; controller.back() }

        Box(
            Modifier.fillMaxSize().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                // Act on key-up for OK: short press = zoom in / open, hold (repeat ~= 0.4 s) = open cluster grid.
                // Once hold triggers, MainActivity swallows the remaining repeat + key-up events (Keys.okSwallowed).
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    if (e.type == KeyEventType.KeyDown) {
                        val rep = e.nativeKeyEvent.repeatCount
                        if (rep == 0) okHeld = false
                        else if (!okHeld) {
                            okHeld = true; Keys.okSwallowed = true
                            Log.i("Photos", "map: hold-OK opens grid")
                            controller.openPhotos(MapAction.GRID)
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        if (!okHeld) { Log.i("Photos", "map: short OK"); controller.select() }
                        okHeld = false
                    }
                    hintUntil = System.currentTimeMillis() + 3000
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val handled = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> controller.navigate(-1, 0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> controller.navigate(1, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> controller.navigate(0, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> controller.navigate(0, 1)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { controller.zoom(1.0); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { controller.zoom(-1.0); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { controller.openPhotos(MapAction.SLIDESHOW); true }
                    else -> false
                }
                if (handled) hintUntil = System.currentTimeMillis() + 3000
                handled
            },
        ) {
            if (mapView != null) AndroidView(
                factory = { mapView },
                modifier = Modifier.graphicsLayer { scaleX = 1 / scale; scaleY = 1 / scale; transformOrigin = TransformOrigin(0f, 0f) }
                    .layout { m, _ -> val p = m.measure(Constraints.fixed((w * scale).roundToInt(), (h * scale).roundToInt())); layout(w, h) { p.place(0, 0) } },
            )
            // Offline fallback when map tiles are unreachable: country outlines from countries.bin
            if (styleError && !styleReady) CountriesFallbackLayer(state.camera, w, h)

            val m = state.markers
            if (m != null) for (c in state.clusters) key(c.key) {
                MapMarker(c, m, focused = c.key == state.focus, cam = state.camera, w = w, h = h)
            }
            // Focused cluster label + photo count
            controller.focusedCluster()?.let { c ->
                key(c.key) { FocusLabel(c, state.name(c), state.camera, w, h) }
            }

            // Header top-left (backed by dark pill on light/satellite styles so text stays legible)
            val darkMap = styleChoice == MapStyle.DARK
            Column(
                Modifier.padding(start = 24.dp, top = 20.dp)
                    .then(if (darkMap) Modifier else Modifier.background(Color(0xB3000000), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)),
            ) {
                Text(stringResource(R.string.map), style = MaterialTheme.typography.displaySmall)
                val n = m?.count
                Text(
                    when {
                        state.error != null -> stringResource(R.string.map_load_failed, state.error.orEmpty())
                        n == null -> stringResource(R.string.loading)
                        else -> pluralStringResource(R.plurals.photos_with_location, n, Language.number(n))
                    },
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.muted,
                )
            }
            if (styleError && !styleReady) Text(
                stringResource(R.string.map_no_background), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            )
            // Loading indicator: markers/index building, clustering takes >150 ms, or tiles pending >0.7 s
            var computingSlow by remember { mutableStateOf(false) }
            LaunchedEffect(state.computingCount > 0) { if (state.computingCount > 0) { delay(150); computingSlow = true } else computingSlow = false }
            var tilesSlow by remember { mutableStateOf(false) }
            LaunchedEffect(state.tilesPending, styleError) {
                if (state.tilesPending && !styleError) { delay(700); tilesSlow = true; delay(20_000); tilesSlow = false } else tilesSlow = false
            }
            val loading = (state.error == null && (state.markers == null || state.index == null)) || computingSlow || tilesSlow
            LoadingPill(loading, Modifier.align(Alignment.TopEnd).padding(top = if (styleError && !styleReady) 56.dp else 22.dp, end = 24.dp))
            // Bottom controls hint (auto-fades) and attribution
            val hintAlpha by animateFloatAsState(if (showHint) 1f else 0f, tween(400), label = "hint")
            Text(
                stringResource(R.string.map_hint),
                style = MaterialTheme.typography.labelLarge, color = Color(0xDDFFFFFF),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).graphicsLayer { alpha = hintAlpha }
                    .background(Color(0xB3000000), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Text(
                if (styleChoice == MapStyle.SATELLITE) "$SAT_ATTRIBUTION · © OpenStreetMap · Protomaps" else "© OpenStreetMap · Protomaps",
                style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF), maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 3.dp)
                    .background(Color(0x80000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Positions content with its anchor ([anchorX], [anchorY] in 0..1) at a world coordinate; tracks camera without remeasuring. */
private fun Modifier.atWorldPoint(cam: State<Camera>, w: Int, h: Int, wx: Double, wy: Double, anchorX: Float = 0.5f, anchorY: Float = 0.5f) = layout { measurable, c ->
    val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) {
        val k = cam.value
        p.place(((wx - k.cx) * k.scale + w / 2 - p.width * anchorX).roundToInt(), ((wy - k.cy) * k.scale + h / 2 - p.height * anchorY).roundToInt())
    }
}

@Composable
private fun MapMarker(c: Cluster, m: Markers, focused: Boolean, cam: State<Camera>, w: Int, h: Int) {
    // Pops in smoothly; grows when focused
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(260, easing = EaseOutBack)) }
    val fs by animateFloatAsState(if (focused) 1.3f else 1f, tween(160), label = "focus")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.atWorldPoint(cam, w, h, c.wx, c.wy).zIndex(if (focused) 1f else 0f).size(MARKER_DP.dp)
            .graphicsLayer { val s = pop.value * fs; scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f) }
            // Simple dark backing instead of per-marker shadowElevation (HWUI tessellates shadows per moving node per frame)
            .drawBehind { drawRoundRect(Color(0x80000000), Offset(-2f, 0f), Size(size.width + 4f, size.height + 3f), CornerRadius(13.dp.toPx())) },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                this.shape = shape; clip = true; shadowElevation = if (focused) 14.dp.toPx() else 0f
                spotShadowColor = AppColors.primary; ambientShadowColor = AppColors.primary
            }.background(AppColors.surfaceHigh),
        ) {
            AsyncImage(markerImage(m.id[c.representative]), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().border(if (focused) 3.dp else 2.dp, if (focused) AppColors.primary else Color.White, shape))
        }
        if (c.count > 1) Text(
            Language.number(c.count), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)
                .background(if (focused) AppColors.primaryDark else Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Thumbnail decoded at marker size (~146 px): 90 markers x ~85 KB instead of ~330 KB for full thumbnails. */
@Composable
private fun markerImage(id: String): coil3.request.ImageRequest {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { (MARKER_DP * 1.3f).dp.roundToPx() }
    return remember(id) {
        coil3.request.ImageRequest.Builder(ctx).data(Immich.thumb(id)).size(px, px).precision(coil3.size.Precision.INEXACT).build()
    }
}

@Composable
private fun FocusLabel(c: Cluster, name: String, cam: State<Camera>, w: Int, h: Int) {
    Column(
        Modifier.atWorldPoint(cam, w, h, c.wx, c.wy, anchorY = 0f).zIndex(2f).padding(top = (MARKER_DP * 0.65f + 12).dp)
            .background(Color(0xD9000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
        Text(pluralStringResource(R.plurals.photos, c.count, Language.number(c.count)), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
    }
}

/** "Loading..." pill with a spinning arc; fades in and out, animating only while visible. */
@Composable
fun LoadingPill(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 200 else 400), label = "loading")
    if (alpha <= 0f) return
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    val accent = AppColors.primary
    Row(
        modifier.graphicsLayer { this.alpha = alpha }.background(Color(0xCC000000), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = angle }) {
            drawCircle(Color(0x40FFFFFF), style = Stroke(2.5.dp.toPx()))
            drawArc(accent, 0f, 100f, false, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Country outlines from `countries.bin` projected to Web Mercator as an offline fallback when tiles fail. */
@Composable
private fun CountriesFallbackLayer(cam: State<Camera>, w: Int, h: Int) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(Unit) {
        val countries = Countries.list(ctx)
        path = withContext(Dispatchers.Default) {
            Path().apply {
                for (c in countries) for (r in c.rings) {
                    var i = 0
                    while (i < r.size) {
                        val x = worldX(r[i].toDouble()).toFloat(); val y = worldY(r[i + 1].toDouble()).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        i += 2
                    }
                    close()
                }
            }
        }
    }
    val p = path ?: return
    Canvas(Modifier.fillMaxSize()) {
        val k = cam.value
        withTransform({
            translate((w / 2 - k.cx * k.scale).toFloat(), (h / 2 - k.cy * k.scale).toFloat())
            scale(k.scale.toFloat(), k.scale.toFloat(), Offset.Zero)
        }) {
            drawPath(p, Color(0x33FFFFFF))
            drawPath(p, Color(0x99FFFFFF), style = Stroke((1.5.dp.toPx() / k.scale).toFloat()))
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000)))))
}
