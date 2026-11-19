package com.immichclient.tv

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import kotlin.reflect.KProperty

/**
 * All app settings as a single state object: every setting is Compose state (readers recompose on change)
 * and is immediately persisted to SharedPreferences. Stored per account, matching server/key (`key` or
 * `key_<VIEWER>`, see [load], called from Immich.selectAccount). Only launcher appearance (app name/icon) is
 * device-wide because it is controlled via manifest activity aliases rather than per account.
 *
 * Defaults preserve the 1.1 behavior so updating existing installs introduces no surprises.
 */
object Settings {
    private const val PREFS = "settings"
    private var prefs: SharedPreferences? = null
    private var suffix = ""
    private val all = ArrayList<Setting<*>>()

    class Setting<T>(
        private val baseKey: String, val default: T, private val perDevice: Boolean,
        private val read: (SharedPreferences, String, T) -> T, private val write: (SharedPreferences.Editor, String, T) -> Unit,
    ) {
        private val state = mutableStateOf(default)
        private val key get() = if (perDevice) baseKey else baseKey + suffix
        var value: T
            get() = state.value
            set(v) { state.value = v; prefs?.edit()?.also { write(it, key, v) }?.apply() }
        fun load(p: SharedPreferences) { state.value = runCatching { read(p, key, default) }.getOrDefault(default) }
        operator fun getValue(t: Any?, p: KProperty<*>): T = value
        operator fun setValue(t: Any?, p: KProperty<*>, v: T) { value = v }
    }

    private fun bool(k: String, d: Boolean, perDevice: Boolean = false) =
        Setting(k, d, perDevice, { p, s, x -> p.getBoolean(s, x) }, { e, s, v -> e.putBoolean(s, v) }).also { all += it }
    private fun integer(k: String, d: Int) =
        Setting(k, d, false, { p, s, x -> p.getInt(s, x) }, { e, s, v -> e.putInt(s, v) }).also { all += it }
    private fun text(k: String, d: String, perDevice: Boolean = false) =
        Setting(k, d, perDevice, { p, s, x -> p.getString(s, x) ?: x }, { e, s, v -> e.putString(s, v) }).also { all += it }
    private inline fun <reified E : Enum<E>> choice(k: String, d: E, perDevice: Boolean = false) = Setting(
        k, d, perDevice,
        { p, s, x -> p.getString(s, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: x },
        { e, s, v -> e.putString(s, v.name) },
    ).also { all += it }

    // ---- Slideshow ----
    /** Slowly zoom in during the slideshow. */
    var kenBurns by bool("ken_burns", true)
    var slideSeconds by integer("slide_s", 6)
    val SLIDE_DURATIONS = listOf(3, 5, 6, 8, 10, 15, 20)
    var slideShuffle by bool("slide_shuffle", false)
    /** Play videos during the slideshow (otherwise skip them). */
    var slideVideos by bool("slide_videos", true)
    var slideTransitionSlide by bool("slide_transition_slide", false)
    /** What is shown over the photo during a running slideshow (ring, badge, date/place, country map). */
    var slideshowOverlay by choice("slideshow_overlay", SlideshowOverlay.ALL)
    /** Prevent screen sleep and system screensaver while a slideshow is playing. */
    var slideKeepAwake by bool("slide_keep_awake", true)

    // ---- Music ----
    /** Background music: off / slideshow only / always in the app. */
    var musicMode by choice("music_mode", MusicMode.OFF)
    var musicStation by choice("music_station", MusicStation.DEEP_SPACE_ONE)

    // ---- Viewer ----
    var viewerMap by choice("viewer_map", MapSize.LARGE)
    /** Show the bottom date and place bar while viewing photos. */
    var showBar by bool("show_bar", true)
    /** Auto-hide the bottom date/place bar after ... ms (0 = always show). */
    var barMs by integer("bar_ms", 3500)
    val BAR_DURATIONS = listOf(2000, 3500, 6000, 10000, 0)
    var counter by bool("counter", true)
    /** Show the photo's album(s) in the bar and info panel (helpful when browsing a person or timeline). */
    var albumName by bool("album_name", true)
    /** Blurred ThumbHash backdrop around photos/videos that don't fill the screen (viewer + screensaver); off = black. */
    var blurredBackground by bool("blurred_background", true)

    // ---- Timeline ----
    var rowSize by choice("row_size", RowSize.NORMAL)
    var newestFirst by bool("newest_first", true)
    var partners by bool("partners", true)
    var memories by bool("memories", true)
    var start by choice("start", Section.PHOTOS)
    var greeting by choice("greeting", Greeting.OFF)
    var greetingText by text("greeting_text", "")

    // ---- Map ----
    var mapStart by choice("map_start", MapStart.WORLD)
    var cluster by choice("cluster", ClusterLevel.NORMAL)
    var mapStyle by choice("map_style", MapStyle.DARK)
    /** Last viewed location: "lat,lon,zoom" (for MapStart.LAST). */
    var mapLast by text("map_last", "")

    // ---- Settings screen ----
    /** Show all settings (otherwise basic only); per device. */
    var allSettings by bool("all_settings", false, perDevice = true)
    /** Check GitHub on startup for a newer release; per device. */
    var updateCheck by bool("update_check", true, perDevice = true)

    // ---- Appearance ----
    var accent by choice("accent", Accent.BLUE)
    var appearance by choice("appearance", AppAppearance.PHOTOS, perDevice = true)

    // ---- Screensaver (DreamService, see Dream.kt) ----
    var dreamSource by choice("dream_source", DreamSource.ALL)
    /** Selected album IDs, comma-separated (until 2.7.2 always a single ID). */
    var dreamAlbum by text("dream_album", "")
    /** Display names matching [dreamAlbum], newline-separated (same order). */
    var dreamAlbumName by text("dream_album_name", "")
    /** For album/favorites sources: if the photo is also in another album, show that album's name instead of the source. */
    var dreamOtherAlbum by bool("dream_other_album", true)
    /** Selected people (comma-separated) and their names (newline-separated), matching albums. */
    var dreamPerson by text("dream_person", "")
    var dreamPersonName by text("dream_person_name", "")
    /** Only photos taken in or after this year (0 = all years). */
    var dreamFromYear by integer("dream_from_year", 0)
    var dreamOrder by choice("dream_order", DreamOrder.RANDOM)
    /** For sequential order: resume cursor ("source-key<TAB>date") so the screensaver picks up where it left off. */
    var dreamPosition by text("dream_position", "")
    var dreamSeconds by integer("dream_s", 15)
    val DREAM_DURATIONS = listOf(5, 8, 10, 15, 20, 30, 45, 60, 120)
    var dreamClock by bool("dream_clock", true)
    /** Country map bottom-right: off / small / large. */
    var dreamMap by choice("dream_map_size", MapSize.LARGE)
    var dreamKenBurns by bool("dream_ken_burns", true)
    /** Date/place (and album name) bottom-left. */
    var dreamInfo by bool("dream_info", true)
    /** Countdown progress ring bottom-left. */
    var dreamRing by bool("dream_ring", true)
    /** System screensaver that was active before this app was selected (restored when turned off); per device. */
    var dreamPrevious by text("dream_previous", "", perDevice = true)

    /** Load prefs for this account; [viewer] is a profile with its own account, or null for the default account. */
    fun load(c: Context, viewer: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        suffix = if (viewer.isNullOrBlank()) "" else "_$viewer"
        for (item in all) item.load(p)
        // Until 1.2 the "country with the most photos" start view had a different enum name; prevent falling back silently
        p.getString("map_start$suffix", null)?.let { old -> if (MapStart.entries.none { it.name == old }) mapStart = MapStart.TOP_COUNTRY }
        // Until 2.7.2 the screensaver map was a boolean toggle
        if (!p.contains("dream_map_size$suffix") && !p.getBoolean("dream_map$suffix", true)) dreamMap = MapSize.OFF
        // Migrate older slideshow music toggle -> musicMode
        if (!p.contains("music_mode$suffix") && p.getBoolean("slide_music$suffix", false)) musicMode = MusicMode.SLIDESHOW
        // The manifest is the source of truth for launcher name/icon (e.g. after reinstalling over old prefs)
        AppAppearance.entries.firstOrNull { it.isEnabled(c) }?.let { if (it != appearance) appearance = it }
    }

    val dreamAlbums get() = splitComma(dreamAlbum)
    val dreamAlbumNames get() = splitLines(dreamAlbumName)
    val dreamPeople get() = splitComma(dreamPerson)
    val dreamPersonNames get() = splitLines(dreamPersonName)
    /** Choices for "From": 0 = all years, then 2000 .. current year. */
    val DREAM_YEARS get() = listOf(0) + (2000..java.time.LocalDate.now().year)
    private fun splitComma(s: String) = s.split(',').filter { it.isNotBlank() }
    private fun splitLines(s: String) = s.split('\n').filter { it.isNotBlank() }
    /** (id, name) pairs for a multi-select preference, padding missing names with "". */
    fun pairs(ids: List<String>, names: List<String>) = ids.mapIndexed { i, id -> id to names.getOrElse(i) { "" } }
    private fun toggle(ids: List<String>, names: List<String>, id: String, name: String): Pair<String, String> {
        val p = pairs(ids, names).toMutableList()
        if (!p.removeAll { it.first == id }) p += id to name
        return p.joinToString(",") { it.first } to p.joinToString("\n") { it.second }
    }
    /** Toggle an album in the screensaver selection. */
    fun toggleDreamAlbum(id: String, name: String) { toggle(dreamAlbums, dreamAlbumNames, id, name).let { dreamAlbum = it.first; dreamAlbumName = it.second } }
    /** Toggle a person in the screensaver selection. */
    fun toggleDreamPerson(id: String, name: String) { toggle(dreamPeople, dreamPersonNames, id, name).let { dreamPerson = it.first; dreamPersonName = it.second } }

    /** Slideshow interval in ms. */
    val slideMs get() = slideSeconds * 1000L
}

// Labels are localized string resources; the UI resolves them via stringResource(x.label).
enum class MapSize(@StringRes val label: Int, val scale: Float) { OFF(R.string.off, 0f), SMALL(R.string.small, 0.62f), LARGE(R.string.large, 1f) }
enum class RowSize(@StringRes val label: Int, val height: Float) { SMALL(R.string.small, 104f), NORMAL(R.string.normal, 138f), LARGE(R.string.large, 186f) }
enum class Greeting(@StringRes val label: Int) { OFF(R.string.off), AUTO(R.string.automatic), CUSTOM(R.string.custom_text) }
enum class MapStart(@StringRes val label: Int) { WORLD(R.string.whole_world), EUROPE(R.string.europe), TOP_COUNTRY(R.string.top_country), LAST(R.string.last_viewed) }
/** Scale factor for cluster radius and deepest zoom level (see WorldMap.kt: 14 ~= 400 m per cluster at 52° latitude). */
enum class ClusterLevel(@StringRes val label: Int, val radius: Float, val maxZoom: Double) { FINE(R.string.fine, 0.7f, 16.0), NORMAL(R.string.normal_cluster, 1f, 14.0), COARSE(R.string.coarse, 1.5f, 12.0) }
enum class MapStyle(@StringRes val label: Int) { DARK(R.string.dark), LIGHT(R.string.light), SATELLITE(R.string.satellite) }
/** What is drawn over the photo while a slideshow is running; pausing (or pressing a key) brings the info back. */
enum class SlideshowOverlay(@StringRes val label: Int) { ALL(R.string.slideshow_overlay_all), NO_RING(R.string.slideshow_overlay_no_ring), PHOTO_ONLY(R.string.slideshow_overlay_photo_only) }
enum class MusicMode(@StringRes val label: Int) { OFF(R.string.off), SLIDESHOW(R.string.music_slideshow), ALWAYS(R.string.music_always) }
/** Ad-free ambient/space radio streams (SomaFM). */
enum class MusicStation(val title: String, val url: String) {
    DEEP_SPACE_ONE("Deep Space One", "https://ice2.somafm.com/deepspaceone-128-aac"),
    DRONE_ZONE("Drone Zone", "https://ice2.somafm.com/dronezone-128-aac"),
    SPACE_STATION("Space Station Soma", "https://ice2.somafm.com/spacestation-128-aac"),
    GROOVE_SALAD("Groove Salad", "https://ice2.somafm.com/groovesalad-128-aac"),
}
/** Where the screensaver pulls its photos from. */
enum class DreamSource(@StringRes val label: Int) {
    ALL(R.string.dream_all_photos), ALBUM(R.string.dream_albums), FAVORITES(R.string.section_favorites),
    MEMORIES(R.string.dream_memories), PERSON(R.string.dream_people),
    /** Selected albums and selected people combined */
    COMBINED(R.string.dream_combined),
}
/** Order of screensaver photos; sequential modes resume from [Settings.dreamPosition]. */
enum class DreamOrder(@StringRes val label: Int) { RANDOM(R.string.random), OLDEST(R.string.oldest_first), NEWEST(R.string.newest_first) }

/** App-wide accent color (AppColors.primary etc. read from here). */
enum class Accent(@StringRes val label: Int, val primary: Color, val dark: Color, val onPrimary: Color) {
    BLUE(R.string.color_blue, Color(0xFFACCBFA), Color(0xFF4250AF), Color(0xFF0B1A3A)),
    GREEN(R.string.color_green, Color(0xFFA6E3B9), Color(0xFF2F7D4F), Color(0xFF07261A)),
    ORANGE(R.string.color_orange, Color(0xFFFFC89A), Color(0xFFC2520C), Color(0xFF3A1A05)),
    PINK(R.string.color_pink, Color(0xFFF9B8D4), Color(0xFFBE185D), Color(0xFF3B0A1F)),
    PURPLE(R.string.color_purple, Color(0xFFD4BCFA), Color(0xFF6D3FC9), Color(0xFF22103F)),
    GRAY(R.string.color_gray, Color(0xFFD9D9DE), Color(0xFF5B5B66), Color(0xFF1A1A1E)),
}

/**
 * Launcher name + icon + banner: one manifest activity-alias per variant, with exactly one enabled at a time.
 * Android cannot change a launcher label/icon dynamically at runtime, but toggling aliases works (the launcher updates
 * within a few seconds, and launchers querying by package find the enabled alias). Alias labels and banners follow the
 * launcher's system language (manifest resources), while the in-app title follows the app language.
 */
enum class AppAppearance(@StringRes val label: Int, val alias: String, @StringRes val desc: Int) {
    PHOTOS(R.string.alias_photos, "Photos", R.string.appearance_photos),
    ALBUM(R.string.alias_album, "PhotoAlbum", R.string.appearance_album),
    MEMORIES(R.string.alias_memories, "Memories", R.string.appearance_memories),
    FAMILY(R.string.alias_family, "Family", R.string.appearance_family);

    private fun component(c: Context) = ComponentName(c.packageName, "com.immichclient.tv.$alias")
    fun isEnabled(c: Context) = c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
        (c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && this == PHOTOS)

    /** Enable this variant (enable first, then disable the rest so at least one entry point always remains active). */
    fun apply(c: Context) {
        val pm = c.packageManager
        runCatching {
            pm.setComponentEnabledSetting(component(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (u in entries) if (u != this) pm.setComponentEnabledSetting(u.component(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Log.i("Photos", "appearance: $name ($alias)")
        }.onFailure { Log.w("Photos", "appearance", it) }
    }
}
