package com.immichclient.tv

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Language.start(this)
        Immich.selectAccount(this, null)
        Diagnostics.start(this)
    }

    /**
     * Single shared ImageLoader: OkHttp with API key header, 400 MB disk cache (thumbnails ~15 KB, previews ~300 KB;
     * TV boxes often have only a few GB free). Memory cache is kept intentionally small and fixed: 2 GB boxes will
     * aggressively kill foreground apps on memory pressure (observed during video playback). Bitmaps are hardware
     * bitmaps (GPU memory, counted under Graphics in meminfo).
     */
    @OptIn(ExperimentalCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Immich.http })) }
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
        .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
        .memoryCache { MemoryCache.Builder().maxSizeBytes(CACHE_MB * 1024L * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(400L * 1024 * 1024).build() }
        .build()

    // Never clear the entire cache mid-scroll (TRIM_MEMORY_RUNNING_LOW): on TV this caused
    // mass thumbnail reloads and 2-second freezes/GC pauses. Full clear happens only in background.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            Log.i("Photos", "onTrimMemory $level: background, clearing cache")
            SingletonImageLoader.get(this).memoryCache?.clear()
            openMap?.onLowMemory()
            ThumbHash.clear()
        } else if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.i("Photos", "onTrimMemory $level: low memory, halving cache")
            SingletonImageLoader.get(this).memoryCache?.let { mc -> mc.trimToSize(mc.maxSize / 2) }
            openMap?.onLowMemory()
        }
    }

    /** Free all expendable in-memory caches (images, ThumbHashes, MapLibre tiles), e.g. when the screensaver starts. */
    fun releaseMemory(reason: String) {
        Log.i("Photos", "releaseMemory ($reason)")
        SingletonImageLoader.get(this).memoryCache?.clear()
        ThumbHash.clear()
        openMap?.onLowMemory()
    }

    companion object { const val CACHE_MB = 64 }
}

/** A screen with its own preserved state while on the back stack (Back restores scroll position and focus). */
sealed class Screen {
    class Timeline(
        val title: String, val sub: String?, filter: Filter, newestFirst: Boolean, val withMemories: Boolean, scope: CoroutineScope,
    ) : Screen() { val state = TimelineState(filter, newestFirst, scope) }
    class Albums(scope: CoroutineScope) : Screen() { val state = AlbumsState(scope) }
    class Explore(scope: CoroutineScope) : Screen() { val state = ExploreState(scope) }
    class Search(scope: CoroutineScope) : Screen() { val state = SearchState(scope) }
    class Memories(scope: CoroutineScope) : Screen() { val state = MemoriesState(scope) }
    class Map(scope: CoroutineScope) : Screen() { val state = MapState(scope) }
    /** All photos inside a world map cluster (opened via hold-OK on the map). */
    class MapPhotos(val title: String, ids: List<String>, scope: CoroutineScope) : Screen() { val state = MapPhotosState(ids, scope) }
    /** All people (opened from Search), with search and sorting. */
    class People(val list: List<Person>) : Screen() { val state = PeopleState(list) }
    /** A directory inside the folder tree (null node = tree root once loaded). */
    class Folders(tree: FoldersTree, node: FolderNode?, scope: CoroutineScope) : Screen() { val state = FolderState(tree, node, scope) }
    class Shuffle(scope: CoroutineScope) : Screen() { val state = ShuffleState(scope) }
    data object Settings : Screen()
    class ResultList(val title: String, val sub: String?, val byDay: Boolean, scope: CoroutineScope, load: suspend (Int) -> Page) : Screen() {
        val results = SearchResults(scope).also { it.start(load) }
    }
}

class Nav(
    val scope: CoroutineScope,
    val open: (Screen) -> Unit,
    private val showViewer: (source: ImageSource, index: Int, title: String?, slideshow: Boolean, grid: GridState?, onBack: (() -> Unit)?) -> Unit,
) {
    /** Open viewer; [grid] gets focus restored on close, or [onBack] is invoked instead (e.g. map). */
    fun view(source: ImageSource, index: Int, title: String?, slideshow: Boolean, grid: GridState?, onBack: (() -> Unit)? = null) =
        showViewer(source, index, title, slideshow, grid, onBack)
}

/** Activity-level key coordination signals (Back hold, OK hold/swallow, screensaver gear shortcut). */
object Keys {
    var backHoldCount by mutableIntStateOf(0)
    /** Launched via system screensaver gear icon (alias ScreensaverSettings): scroll to Screensaver group. */
    @Volatile var toScreensaver = false
    /** World map uses hold-OK; everywhere else OK repeats never trigger extra actions. */
    @Volatile var okRepeatAllowed = false
    /** A screen opened by hold-OK swallows the rest of that same physical key press (repeats + key-up). */
    @Volatile var okSwallowed = false
}

enum class Section(@StringRes val label: Int, val icon: ImageVector) {
    PHOTOS(R.string.section_photos, Icons.Filled.Photo),
    FOLDERS(R.string.section_folders, Icons.Filled.Folder),
    SHUFFLE(R.string.section_shuffle, Icons.Filled.Shuffle),
    MEMORIES(R.string.section_memories, Icons.Filled.AutoAwesome),
    SEARCH(R.string.section_search, Icons.Filled.Search),
    EXPLORE(R.string.section_explore, Icons.Filled.Explore),
    MAP(R.string.section_map, Icons.Filled.Map),
    ALBUMS(R.string.section_albums, Icons.Filled.PhotoLibrary),
    FAVORITES(R.string.section_favorites, Icons.Filled.Favorite),
    SETTINGS(R.string.section_settings, Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    /** Only allow OK/Enter after our own KeyDown so stray key-up/repeat events from the launcher tile don't click. */
    private var okSeen = false
    private var launchUptime = 0L

    /** Apply the chosen app language independently of system language (see Language). */
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchUptime = SystemClock.uptimeMillis()
        // Custom launchers can pass a profile extra ("viewer") for per-profile Immich accounts
        Immich.selectAccount(this, intent.getStringExtra("viewer"))
        // Apply test extras only on a fresh shell launch (`am start`, referrer com.android.shell). When Android
        // recreates the task after a background kill, it replays the original Intent (`savedInstanceState != null`),
        // which would otherwise re-trigger `--ei test_index` out of nowhere.
        val fromShell = referrer?.host == "com.android.shell"
        val test = savedInstanceState == null && fromShell
        Log.i("Photos", "start: referrer=$referrer restored=${savedInstanceState != null} test=$test extras=${intent.extras?.keySet()?.joinToString()}")
        // Test: `am start ... --ei test_index 91` opens the viewer directly at that timeline photo
        val testIndex = if (test) intent.getIntExtra("test_index", -1) else -1
        // Launched via screensaver gear icon in system settings (alias .ScreensaverSettings, see dream.xml)
        val fromScreensaver = intent.component?.className?.endsWith(".ScreensaverSettings") == true
        if (fromScreensaver) Keys.toScreensaver = true
        val startSection = intent.getStringExtra("section")?.takeIf { test }?.let { s -> Section.entries.firstOrNull { it.name == s } }
            ?: Language.restartSection?.also { Language.restartSection = null } // reopen Settings after language switch
            ?: Section.SETTINGS.takeIf { fromScreensaver }
            ?: Settings.start
        // Test: `--ez setup_test true` shows the pairing screen without clearing the active connection
        val setupTest = test && intent.getBooleanExtra("setup_test", false)
        // Test: `--ez language_test true` shows the first-run language picker (keeps pairing intact)
        val languageTest = test && intent.getBooleanExtra("language_test", false)
        if (test) {
            mapTexture = intent.getBooleanExtra("map_texture", false); mapLod = intent.getIntExtra("map_lod", mapLod)
            mapFps = intent.getIntExtra("map_fps", 0); mapAllLayers = intent.getBooleanExtra("map_all", false)
            mapRes = intent.getIntExtra("map_res", mapRes); mapFlyLod = intent.getIntExtra("map_fly", mapFlyLod)
            noScrollBar = intent.getBooleanExtra("no_scrollbar", false)
            videoNoPlaceholder = intent.getBooleanExtra("video_no_placeholder", false)
            mapCache = intent.getBooleanExtra("map_cache", mapCache); mapPrefetch = intent.getIntExtra("map_prefetch", -1)
            // `--es update_test 2.0`: simulate an older installed version for testing update dialogs
            Update.testVersion = intent.getStringExtra("update_test")
        }
        setContent { PhotosTheme { PhotosApp(finish = ::finish, restart = ::recreate, testIndex = testIndex, startSection = startSection, setupTest = setupTest, languageTest = languageTest) } }
    }

    /** Hold Back = open drawer; swallow the subsequent key-up so it doesn't trigger a normal Back. */
    private var backHeld = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Hold Back opens the navigation drawer everywhere (some boxes like NVIDIA Shield intercept KEYCODE_MENU at system level;
        // MENU still works alongside it for remotes that pass it through).
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!backHeld) { backHeld = true; Keys.backHoldCount++ }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && backHeld) { backHeld = false; return true }
        }
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER) {
            // Ignore OK during the first 500 ms after launch (e.g. accidental double-press on launcher tile)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && SystemClock.uptimeMillis() - launchUptime > 500) okSeen = true
            if (Keys.okSwallowed) {
                Log.i("Photos", "OK swallowed (action ${e.action}, repeat ${e.repeatCount})")
                if (e.action == KeyEvent.ACTION_UP) Keys.okSwallowed = false
                return true
            }
            if (!okSeen) { Log.i("Photos", "OK ignored (action ${e.action}, repeat ${e.repeatCount})"); return true }
            // Holding OK emits key-repeat: suppress extra actions unless enabled (e.g. map)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && !Keys.okRepeatAllowed) return true
        }
        return super.dispatchKeyEvent(e)
    }

    // singleTask: if already running and launched with a different viewer profile, rebuild for that account
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        okSeen = false
        val oldKey = Immich.key
        Immich.selectAccount(this, intent.getStringExtra("viewer"))
        if (Immich.key != oldKey) recreate()
    }
}

@Composable
private fun PhotosApp(finish: () -> Unit, restart: () -> Unit, testIndex: Int = -1, startSection: Section = Section.PHOTOS, setupTest: Boolean = false, languageTest: Boolean = false) {
    // First run: pick language (pre-selected to system language), then recreate Activity in that language
    var pickingLanguage by remember { mutableStateOf(languageTest || !Language.chosen) }
    if (pickingLanguage) { LanguageScreen(finish = finish) { pickingLanguage = false; restart() }; return }
    // Unpaired (first run or after Disconnect) or "Connect again": show QR pairing screen.
    // On success, recreate so every screen rebinds to the new server/account.
    val accountState = Immich.state
    var pairing by remember { mutableStateOf(setupTest) }
    val paired = Immich.key.isNotBlank() && accountState >= 0
    if (!paired || pairing) {
        // Reconnecting via Settings is never a test run (omit "(test)" suffix on key name)
        PairingScreen(test = setupTest && !pairing, back = if (paired) ({ pairing = false }) else null, done = { pairing = false; restart() }, finish = finish)
        return
    }
    // Single scope for all screen states, with a CoroutineExceptionHandler so uncaught coroutine failures are logged
    // to Diagnostics rather than crashing the whole app.
    val scope = remember {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e("Photos", "coroutine", e); Diagnostics.record("coroutine", e)
        })
    }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    LaunchedEffect(Unit) { Immich.warmSearch() }
    val appCtx = LocalContext.current.applicationContext
    // Check API key permissions; notify once if a permission is newly missing
    LaunchedEffect(Immich.state) { delay(1500); Permissions.check(); Permissions.notifyNew(appCtx) }
    // Show "What's new" once after an update, then check GitHub for a newer release if enabled
    LaunchedEffect(Unit) { Update.onStart(appCtx); delay(4000); if (Settings.updateCheck) Update.check(appCtx, notify = true) }
    val roots = remember { HashMap<Section, Screen>() }
    var section by remember { mutableStateOf(startSection) }
    val backStack = remember { mutableStateListOf<Screen>() }
    var viewer by remember { mutableStateOf<Triple<ViewerState, GridState?, (() -> Unit)?>?>(null) }
    /** Shortcut overview overlay (INFO button or Settings -> Keys) */
    var showKeys by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val appTitle = stringResource(Settings.appearance.label)
    // Single app-level radio player: prevents stream overlap and pauses when the app goes to background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var appForeground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> appForeground = true
                Lifecycle.Event.ON_STOP -> appForeground = false
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val musicActive = appForeground && when (Settings.musicMode) {
        MusicMode.OFF -> false
        MusicMode.SLIDESHOW -> viewer?.first?.slideshow == true
        MusicMode.ALWAYS -> true
    }
    if (musicActive) {
        MusicPlayer(url = Settings.musicStation.url, ducked = viewer?.first?.playingVideo == true)
    }
    // Timeline settings affecting existing root timelines (order, partner photos): apply live without restart
    val order = Settings.newestFirst; val partners = Settings.partners
    LaunchedEffect(order, partners) {
        for (s in listOf(Section.PHOTOS, Section.FAVORITES)) (roots[s] as? Screen.Timeline)?.state?.applySettings()
    }

    val nav = remember {
        Nav(
            scope = scope,
            open = { backStack.add(it) },
            showViewer = { source, i, title, slideshow, grid, onBack -> if (i >= 0) viewer = Triple(ViewerState(source, i, title, slideshow), grid, onBack) },
        )
    }
    fun rootOf(s: Section): Screen = roots.getOrPut(s) {
        when (s) {
            Section.PHOTOS -> Screen.Timeline(appTitle, null, Filter(), Settings.newestFirst, true, scope)
            Section.FOLDERS -> Screen.Folders(FoldersTree(scope), null, scope)
            Section.SHUFFLE -> Screen.Shuffle(scope)
            Section.MEMORIES -> Screen.Memories(scope)
            Section.SEARCH -> Screen.Search(scope)
            Section.EXPLORE -> Screen.Explore(scope)
            Section.MAP -> Screen.Map(scope)
            Section.ALBUMS -> Screen.Albums(scope)
            Section.FAVORITES -> Screen.Timeline(ctx.getString(R.string.section_favorites), null, Filter(favorites = true), Settings.newestFirst, false, scope)
            Section.SETTINGS -> Screen.Settings
        }
    }
    val screen = backStack.lastOrNull() ?: rootOf(section)
    if (testIndex >= 0) LaunchedEffect(Unit) {
        val s = (rootOf(Section.PHOTOS) as Screen.Timeline).state
        while (s.count == 0) delay(100)
        delay(600)
        nav.view(s, testIndex, appTitle, false, s.grid)
    }

    // Back: close keys overlay -> pop subscreen -> return to start section -> scroll timeline to top -> exit app
    BackHandler(enabled = viewer == null) {
        val t = (screen as? Screen.Timeline)?.state?.grid?.list
        when {
            showKeys -> showKeys = false
            backStack.isNotEmpty() -> backStack.removeAt(backStack.lastIndex)
            section != startSection -> section = startSection
            t != null && t.firstVisibleItemIndex > 0 -> scope.launch {
                t.scrollToItem(0)
                (screen as Screen.Timeline).state.let { s -> s.months.firstOrNull()?.let { m -> s.loaded[m.key]?.firstOrNull()?.let { s.grid.focusTarget = it.id } } }
            }
            else -> finish()
        }
    }

    val drawerFocus = remember { FocusRequester() }
    /** Content area (right of drawer): receives focus after overlays close so Android doesn't grab the drawer. */
    val contentFocus = remember { FocusRequester() }
    val contentHasFocus = remember { mutableStateOf(false) }
    // Opening the menu = focusing the drawer item of the current section (tv-material's drawer opens on focus)
    fun openDrawer() { showKeys = false; runCatching { drawerFocus.requestFocus() }.onFailure { Log.w("Photos", "drawer", it) } }
    val backHoldCount = Keys.backHoldCount
    LaunchedEffect(backHoldCount) { if (backHoldCount > 0 && viewer == null) openDrawer() }
    Box(
        Modifier.fillMaxSize().background(AppColors.background).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || viewer != null) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // MENU (available on some remotes): open drawer focused on the active section
                KeyEvent.KEYCODE_MENU -> { openDrawer(); true }
                // INFO: toggle remote shortcuts overview (inside the viewer, INFO toggles the info panel)
                KeyEvent.KEYCODE_INFO -> { showKeys = !showKeys; true }
                else -> false
            }
        },
    ) {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val drawerEntryTick = remember { mutableIntStateOf(0) }
        // One frame later: tv-material sets its own focus on entry
        LaunchedEffect(drawerEntryTick.intValue) {
            if (drawerEntryTick.intValue == 0) return@LaunchedEffect
            withFrameNanos { }
            val ok = runCatching { drawerFocus.requestFocus() }.getOrDefault(false)
            Log.i("Photos", "drawer: focus current section ($ok)")
        }
        val insideDrawer = remember { mutableStateOf(false) }
        val drawerFocusedNow = remember { mutableStateOf(false) }
        // Redirect focus to active section only on ENTRY. Moving between drawer items briefly flickers hasFocus
        // false->true, so treat focus as "left drawer" only after 300 ms without focus.
        LaunchedEffect(drawerFocusedNow.value) {
            if (drawerFocusedNow.value) {
                if (!insideDrawer.value) { insideDrawer.value = true; drawerEntryTick.intValue++ }
            } else {
                delay(300)
                insideDrawer.value = false
            }
        }
        // Screen transitions (including viewer/overlays): briefly suppress drawer focusability while the new screen
        // grabs focus, preventing the drawer from flashing open mid-transition.
        val overlayOpen = Update.whatsNewOpen || Update.open || Permissions.open || Overlays.open > 0
        val transitionKey = Triple(screen, viewer != null, overlayOpen)
        val lastKey = remember { mutableStateOf(transitionKey) }
        val transitioning = remember { mutableStateOf(false) }
        val lockDrawer = transitionKey != lastKey.value || transitioning.value
        LaunchedEffect(transitionKey) {
            if (transitionKey == lastKey.value) return@LaunchedEffect
            transitioning.value = true
            val overlayClosed = lastKey.value.third && !overlayOpen && lastKey.value.first == screen
            lastKey.value = transitionKey
            // Overlay itself may already have restored focus to its opener row; only fall back if nothing in content is focused
            if (overlayClosed) { delay(48); if (!contentHasFocus.value) runCatching { contentFocus.requestFocus() } }
            delay(800)
            transitioning.value = false
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            modifier = Modifier.graphicsLayer { alpha = if (viewer != null) 0f else 1f },
            drawerContent = { value ->
                BoxWithConstraints(
                    Modifier.fillMaxHeight().background(if (value == DrawerValue.Open) AppColors.surface else AppColors.background),
                ) {
                    val scrollable = maxHeight < 495.dp
                    val baseMod = Modifier.fillMaxHeight()
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .focusProperties { canFocus = !lockDrawer }
                        .onFocusChanged {
                            drawerFocusedNow.value = it.hasFocus
                            if (it.hasFocus) Log.i("Photos", "drawer focus (locked=$lockDrawer)")
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .selectableGroup()
                    Column(baseMod, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (s in Section.entries) {
                            if (s == Section.SETTINGS) {
                                if (scrollable) Spacer(Modifier.height(10.dp)) else Spacer(Modifier.weight(1f))
                            }
                            NavigationDrawerItem(
                                selected = section == s && backStack.isEmpty(),
                                modifier = Modifier.height(44.dp)
                                    .then(if (section == s) Modifier.focusRequester(drawerFocus) else Modifier)
                                    .onFocusChanged { if (it.isFocused && !insideDrawer.value) Log.i("Photos", "drawer: entered at ${s.name} (current ${section.name})") },
                                onClick = {
                                    backStack.clear(); section = s
                                    drawer.setValue(DrawerValue.Closed)
                                },
                                leadingContent = { Icon(s.icon, null) },
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = AppColors.primary.copy(alpha = 0.18f), selectedContentColor = AppColors.primary,
                                    focusedContainerColor = AppColors.primary, focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Text(if (s == Section.PHOTOS) appTitle else stringResource(s.label), maxLines = 1) }
                        }
                    }
                }
            },
        ) {
            // Smooth entry without flicker: when focus exits the content to the left, route directly to the active
            // section's drawer item instead of letting Android focus the geometrically closest item first.
            @OptIn(ExperimentalComposeUiApi::class)
            Box(
                Modifier.fillMaxSize().padding(start = 80.dp)
                    .focusRequester(contentFocus)
                    .onFocusChanged { contentHasFocus.value = it.hasFocus }
                    .focusProperties {
                        exit = { dir ->
                            if (dir == FocusDirection.Left && !lockDrawer) drawerFocus
                            else FocusRequester.Default
                        }
                    }
                    .focusGroup(),
            ) {
                key(screen) {
                    when (screen) {
                        is Screen.Timeline -> TimelineScreen(screen, nav)
                        is Screen.Albums -> AlbumsScreen(screen.state, nav)
                        is Screen.Explore -> ExploreScreen(screen.state, nav)
                        is Screen.Search -> SearchScreen(screen.state, nav)
                        is Screen.Memories -> MemoriesScreen(screen.state, nav)
                        is Screen.Map -> MapScreen(screen.state, nav)
                        is Screen.MapPhotos -> MapPhotosScreen(screen, nav)
                        is Screen.People -> PeopleScreen(screen.state, nav)
                        is Screen.Folders -> FoldersScreen(screen, nav)
                        is Screen.Shuffle -> ShuffleScreen(screen.state, nav)
                        is Screen.ResultList -> ResultListScreen(screen, nav)
                        is Screen.Settings -> SettingsScreen(reconnect = { pairing = true }, disconnected = { section = Section.PHOTOS }, showKeys = { showKeys = true })
                    }
                }
            }
        }
        if (showKeys && viewer == null) KeysOverview { showKeys = false }
        if (Permissions.open && viewer == null) PermissionsScreen(reconnect = { Permissions.open = false; pairing = true }) { Permissions.open = false }
        if (Update.whatsNewOpen && viewer == null) WhatsNewScreen { Update.whatsNewOpen = false }
        else if (Update.open && viewer == null) UpdateScreen { Update.open = false }
        viewer?.let { (v, grid, onBack) ->
            Viewer(
                v,
                // "Show on map" (OK inside info panel): close viewer, switch to Map section, fly to location
                onMap = { a ->
                    viewer = null; backStack.clear(); section = Section.MAP
                    (rootOf(Section.MAP) as Screen.Map).state.showAsset(a)
                },
            ) { last ->
                viewer = null
                if (grid != null && last != null) grid.returnTo = last.id
                onBack?.invoke()
            }
        }
    }
}
