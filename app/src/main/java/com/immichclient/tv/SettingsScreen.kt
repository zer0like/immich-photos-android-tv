package com.immichclient.tv

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/*
 * Settings screen (bottom section in the navigation drawer): single grouped list. Each row is a TV Surface:
 * Up/Down picks a row, Left/Right steps values without wrapping, OK advances to the next value (wrapping)
 * or triggers an action. Reads/writes [Settings] directly; the rest of the app updates via Compose state.
 */

@Composable
fun SettingsScreen(reconnect: () -> Unit, disconnected: () -> Unit, showKeys: () -> Unit) {
    val ctx = LocalContext.current
    val me by produceState(Immich.me, Immich.state) { value = Immich.loadMe() }
    val immichVersion by produceState<String?>(null, Immich.state) { value = Immich.serverVersion() }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var greetingInput by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    // Cache size: disk + memory, recalculated when cleared
    var cacheRound by remember { mutableIntStateOf(0) }
    val cacheFmt = stringResource(R.string.cache_size)
    val cacheText by produceState<String?>(null, cacheRound) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val l = SingletonImageLoader.get(ctx)
                val disk = l.diskCache?.size ?: 0L; val mem = l.memoryCache?.size ?: 0L
                cacheFmt.format(Language.locale, Language.number((disk / 1048576.0).roundToLong()), Language.number((mem / 1048576.0).roundToLong()))
            }.getOrDefault("?")
        }
    }
    // Screensaver overlays (setup guide, album/person pickers) and live status
    var helpOpen by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }
    var personPicker by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var dreamRound by remember { mutableIntStateOf(0) }
    val dreamActive = remember(dreamRound, helpOpen) { Screensaver.isActive(ctx) }
    val dreamCanWrite = remember(dreamRound, helpOpen) { Screensaver.canWrite(ctx) }
    val listState = rememberLazyListState()
    val dreamRow = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val languageRow = remember { FocusRequester() }
    val dateRow = remember { FocusRequester() }
    val clockRow = remember { FocusRequester() }
    // Restore focus to the row that opened the album/person picker when it closes
    val albumRow = remember { FocusRequester() }
    val personRow = remember { FocusRequester() }
    var lastPickerRow by remember { mutableStateOf<FocusRequester?>(null) }
    val pickerOpen = albumPicker || personPicker
    var pickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pickerOpen) {
        if (pickerOpen) lastPickerRow = if (albumPicker) albumRow else personRow
        if (pickerWasOpen && !pickerOpen) { delay(16); runCatching { lastPickerRow?.requestFocus() } }
        pickerWasOpen = pickerOpen
    }
    LaunchedEffect(Unit) {
        delay(30)
        // After a language/format change (`recreate`), restore focus onto that row
        Language.focusRow?.let { r ->
            Language.focusRow = null
            val target = when (r) { "date" -> dateRow; "clock" -> clockRow; else -> languageRow }
            repeat(5) { if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) }
        }
        // Launched from system screensaver gear icon: scroll directly to the Screensaver group
        if (Keys.toScreensaver) {
            Keys.toScreensaver = false
            if (SCREENSAVER_ENABLED) {
                listState.scrollToItem(dreamGroupIndex(Settings.allSettings))
                delay(50)
                if (runCatching { dreamRow.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(confirmDisconnect) { if (confirmDisconnect) { delay(6000); confirmDisconnect = false } }
    val offOn = listOf(stringResource(R.string.off), stringResource(R.string.on))
    // Basic = most commonly needed settings; All = full list (persisted per device)
    val showAll = Settings.allSettings

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.settings), stringResource(R.string.settings_sub)) }
        item {
            OptionRow(
                stringResource(R.string.settings_show),
                stringResource(if (showAll) R.string.settings_all_desc else R.string.settings_basic_desc),
                listOf(stringResource(R.string.settings_basic), stringResource(R.string.settings_all)),
                if (showAll) 1 else 0,
                Modifier.focusRequester(first),
            ) { Settings.allSettings = it == 1 }
        }

        // ---- Viewing photos & Slideshow at the top; system Screensaver placed further down ----
        groupHeader(R.string.group_viewing)
        item { OptionRow(stringResource(R.string.country_map), stringResource(R.string.country_map_desc), MapSize.entries.map { stringResource(it.label) }, Settings.viewerMap.ordinal) { Settings.viewerMap = MapSize.entries[it] } }
        toggleRow(R.string.show_date_place, R.string.show_date_place_desc, offOn, Settings.showBar) { Settings.showBar = it }
        if (Settings.showBar) item {
            val always = stringResource(R.string.always_show)
            val labels = Settings.BAR_DURATIONS.map { if (it == 0) always else stringResource(R.string.seconds_decimal, (it / 1000.0).let { s -> if (s % 1 == 0.0) "%.0f".format(Language.locale, s) else "%.1f".format(Language.locale, s) }) }
            OptionRow(stringResource(R.string.date_place_bar), stringResource(R.string.date_place_bar_desc), labels, Settings.BAR_DURATIONS.indexOf(Settings.barMs).coerceAtLeast(0)) { Settings.barMs = Settings.BAR_DURATIONS[it] }
        }
        if (showAll) item { OptionRow(stringResource(R.string.counter), stringResource(R.string.counter_desc, Language.number(12345)), offOn, if (Settings.counter) 1 else 0) { Settings.counter = it == 1 } }
        if (showAll) toggleRow(R.string.album_name, R.string.album_name_desc, offOn, Settings.albumName) { Settings.albumName = it }
        if (showAll) toggleRow(R.string.blurred_background, R.string.blurred_background_desc, offOn, Settings.blurredBackground) { Settings.blurredBackground = it }

        groupHeader(R.string.group_slideshow)
        item {
            OptionRow(
                stringResource(R.string.time_per_photo), stringResource(R.string.time_per_photo_desc),
                Settings.SLIDE_DURATIONS.map { stringResource(R.string.seconds, it) },
                Settings.SLIDE_DURATIONS.indexOf(Settings.slideSeconds).coerceAtLeast(0),
            ) { Settings.slideSeconds = Settings.SLIDE_DURATIONS[it] }
        }
        if (showAll) toggleRow(R.string.ken_burns, R.string.ken_burns_desc, offOn, Settings.kenBurns) { Settings.kenBurns = it }
        item { OptionRow(stringResource(R.string.order), stringResource(R.string.slideshow_order_desc), listOf(stringResource(R.string.by_date), stringResource(R.string.random)), if (Settings.slideShuffle) 1 else 0) { Settings.slideShuffle = it == 1 } }
        item { OptionRow(stringResource(R.string.videos), stringResource(R.string.slideshow_videos_desc), listOf(stringResource(R.string.play), stringResource(R.string.skip)), if (Settings.slideVideos) 0 else 1) { Settings.slideVideos = it == 0 } }
        toggleRow(R.string.keep_awake, R.string.keep_awake_desc, offOn, Settings.slideKeepAwake) { Settings.slideKeepAwake = it }
        if (showAll) item { OptionRow(stringResource(R.string.transition), stringResource(R.string.transition_desc), listOf(stringResource(R.string.fade), stringResource(R.string.slide)), if (Settings.slideTransitionSlide) 1 else 0) { Settings.slideTransitionSlide = it == 1 } }
        if (showAll) item { OptionRow(stringResource(R.string.slideshow_overlay), stringResource(R.string.slideshow_overlay_desc), SlideshowOverlay.entries.map { stringResource(it.label) }, Settings.slideshowOverlay.ordinal) { Settings.slideshowOverlay = SlideshowOverlay.entries[it] } }

        groupHeader(R.string.group_music)
        item {
            OptionRow(stringResource(R.string.music_mode), stringResource(R.string.music_mode_desc), MusicMode.entries.map { stringResource(it.label) }, Settings.musicMode.ordinal) {
                Settings.musicMode = MusicMode.entries[it]
            }
        }
        if (Settings.musicMode != MusicMode.OFF) item {
            OptionRow(stringResource(R.string.music_station), stringResource(R.string.music_station_desc), MusicStation.entries.map { it.title }, Settings.musicStation.ordinal) {
                Settings.musicStation = MusicStation.entries[it]
            }
        }

        groupHeader(R.string.group_timeline)
        item { OptionRow(stringResource(R.string.photo_size), stringResource(R.string.photo_size_desc), RowSize.entries.map { stringResource(it.label) }, Settings.rowSize.ordinal) { Settings.rowSize = RowSize.entries[it] } }
        if (showAll) item { OptionRow(stringResource(R.string.order), stringResource(R.string.timeline_order_desc), listOf(stringResource(R.string.newest_first), stringResource(R.string.oldest_first)), if (Settings.newestFirst) 0 else 1) { Settings.newestFirst = it == 0 } }
        if (showAll) toggleRow(R.string.partner_photos, R.string.partner_photos_desc, offOn, Settings.partners) { Settings.partners = it }
        if (showAll) toggleRow(R.string.memories_at_top, R.string.memories_at_top_desc, offOn, Settings.memories) { Settings.memories = it }
        item {
            val choices = listOf(Section.PHOTOS, Section.FOLDERS, Section.SHUFFLE, Section.MEMORIES, Section.MAP, Section.ALBUMS, Section.FAVORITES)
            OptionRow(
                stringResource(R.string.start_screen), stringResource(R.string.start_screen_desc),
                choices.map { stringResource(if (it == Section.PHOTOS) Settings.appearance.label else it.label) },
                choices.indexOf(Settings.start).coerceAtLeast(0),
            ) { Settings.start = choices[it] }
        }
        if (showAll) item {
            val example = stringResource(R.string.greeting_with_name, stringResource(R.string.greeting_afternoon), me?.name?.substringBefore(' ') ?: "…")
            OptionRow(stringResource(R.string.greeting), stringResource(R.string.greeting_desc, example), Greeting.entries.map { stringResource(it.label) }, Settings.greeting.ordinal) { Settings.greeting = Greeting.entries[it] }
        }
        if (showAll && Settings.greeting == Greeting.CUSTOM) item {
            ActionRow(stringResource(R.string.custom_text), Settings.greetingText.ifBlank { stringResource(R.string.nothing_entered) }) { greetingInput = true }
        }

        groupHeader(R.string.group_map)
        if (showAll) item { OptionRow(stringResource(R.string.map_start_view), stringResource(R.string.map_start_view_desc), MapStart.entries.map { stringResource(it.label) }, Settings.mapStart.ordinal) { Settings.mapStart = MapStart.entries[it] } }
        if (showAll) item { OptionRow(stringResource(R.string.cluster_level), stringResource(R.string.cluster_level_desc), ClusterLevel.entries.map { stringResource(it.label) }, Settings.cluster.ordinal) { Settings.cluster = ClusterLevel.entries[it] } }
        item { OptionRow(stringResource(R.string.map_style), stringResource(R.string.map_style_desc), MapStyle.entries.map { stringResource(it.label) }, Settings.mapStyle.ordinal) { Settings.mapStyle = MapStyle.entries[it] } }

        groupHeader(R.string.group_appearance)
        item {
            // Language: apply immediately by recreating the Activity (all strings, dates, and country names update)
            OptionRow(stringResource(R.string.language), stringResource(R.string.language_desc), Language.LANGUAGES.map { Language.name(it) }, Language.LANGUAGES.indexOf(Language.code).coerceAtLeast(0), Modifier.focusRequester(languageRow)) {
                val next = Language.LANGUAGES[it]
                if (next != Language.code) { Language.choose(ctx, next); Language.restartSection = Section.SETTINGS; Language.focusRow = "language"; (ctx as? Activity)?.recreate() }
            }
        }
        item {
            val options = DateTimeFmt.DateOrder.entries.map { DateTimeFmt.example(d = it) }
            OptionRow(stringResource(R.string.date_format), stringResource(R.string.date_format_desc), options, DateTimeFmt.dateOrder.ordinal, Modifier.focusRequester(dateRow)) {
                val next = DateTimeFmt.DateOrder.entries[it]
                if (next != DateTimeFmt.dateOrder) { DateTimeFmt.choose(ctx, d = next); Language.restartSection = Section.SETTINGS; Language.focusRow = "date"; (ctx as? Activity)?.recreate() }
            }
        }
        item {
            val options = DateTimeFmt.ClockStyle.entries.map { k -> if (k == DateTimeFmt.ClockStyle.DEFAULT) stringResource(R.string.automatic) + " (" + DateTimeFmt.example(k = k) + ")" else DateTimeFmt.example(k = k) }
            OptionRow(stringResource(R.string.time_format), stringResource(R.string.time_format_desc), options, DateTimeFmt.clockStyle.ordinal, Modifier.focusRequester(clockRow)) {
                val next = DateTimeFmt.ClockStyle.entries[it]
                if (next != DateTimeFmt.clockStyle) { DateTimeFmt.choose(ctx, k = next); Language.restartSection = Section.SETTINGS; Language.focusRow = "clock"; (ctx as? Activity)?.recreate() }
            }
        }
        item {
            OptionRow(stringResource(R.string.accent_color), stringResource(R.string.accent_color_desc), Accent.entries.map { stringResource(it.label) }, Settings.accent.ordinal, color = Settings.accent.primary) { Settings.accent = Accent.entries[it] }
        }
        if (showAll) item {
            val u = Settings.appearance
            OptionRow(stringResource(R.string.name_and_icon), stringResource(R.string.name_and_icon_desc, stringResource(u.desc)), AppAppearance.entries.map { stringResource(it.label) }, u.ordinal) {
                val next = AppAppearance.entries[it]; Settings.appearance = next; next.apply(ctx)
            }
        }

        // ---- Screensaver (keep dreamGroupIndex() in sync with rows above) ----
        if (SCREENSAVER_ENABLED) {
            groupHeader(R.string.group_screensaver)
            item {
                if (dreamCanWrite) OptionRow(stringResource(R.string.screensaver), stringResource(if (dreamActive) R.string.dream_status_on else R.string.dream_on_desc), offOn, if (dreamActive) 1 else 0, Modifier.focusRequester(dreamRow)) {
                    Screensaver.set(ctx, it == 1); dreamRound++
                } else SettingRow(stringResource(R.string.screensaver), stringResource(if (dreamActive) R.string.dream_status_on else R.string.dream_status_off_ok), Modifier.focusRequester(dreamRow), onClick = { helpOpen = true }) { focus ->
                    Text(stringResource(if (dreamActive) R.string.on else R.string.off), style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, modifier = Modifier.padding(end = 10.dp))
                }
            }
            item { ActionRow(stringResource(R.string.dream_setup), stringResource(R.string.dream_setup_desc)) { helpOpen = true } }
            item { ActionRow(stringResource(R.string.dream_preview), stringResource(R.string.dream_preview_desc)) { runCatching { ctx.startActivity(Intent(ctx, PreviewActivity::class.java)) } } }
            item { OptionRow(stringResource(R.string.dream_source), stringResource(R.string.dream_source_desc), DreamSource.entries.map { stringResource(it.label) }, Settings.dreamSource.ordinal) { Settings.dreamSource = DreamSource.entries[it] } }
            val src = Settings.dreamSource
            if (src == DreamSource.ALBUM || src == DreamSource.COMBINED) item { ActionRow(stringResource(R.string.dream_albums), Settings.dreamAlbumNames.joinToString(", ").ifBlank { stringResource(R.string.dream_not_chosen) }, Modifier.focusRequester(albumRow)) { albumPicker = true } }
            if (src == DreamSource.PERSON || src == DreamSource.COMBINED) item { ActionRow(stringResource(R.string.dream_people), Settings.dreamPersonNames.joinToString(", ").ifBlank { stringResource(R.string.dream_not_chosen) }, Modifier.focusRequester(personRow)) { personPicker = true } }
            if (src != DreamSource.ALL && src != DreamSource.MEMORIES)
                toggleRow(R.string.dream_other_album, R.string.dream_other_album_desc, offOn, Settings.dreamOtherAlbum) { Settings.dreamOtherAlbum = it }
            item {
                val years = Settings.DREAM_YEARS
                OptionRow(
                    stringResource(R.string.dream_from_year), stringResource(R.string.dream_from_year_desc),
                    years.map { if (it == 0) stringResource(R.string.all_years) else it.toString() },
                    years.indexOf(Settings.dreamFromYear).coerceAtLeast(0),
                ) { Settings.dreamFromYear = years[it] }
            }
            if (src != DreamSource.MEMORIES) item {
                OptionRow(stringResource(R.string.order), stringResource(R.string.dream_order_desc), DreamOrder.entries.map { stringResource(it.label) }, Settings.dreamOrder.ordinal) { Settings.dreamOrder = DreamOrder.entries[it] }
            }
            item {
                val labels = Settings.DREAM_DURATIONS.map { if (it >= 60) stringResource(R.string.minutes, it / 60) else stringResource(R.string.seconds, it) }
                OptionRow(stringResource(R.string.time_per_photo), stringResource(R.string.dream_time_desc), labels, Settings.DREAM_DURATIONS.indexOf(Settings.dreamSeconds).coerceAtLeast(0)) { Settings.dreamSeconds = Settings.DREAM_DURATIONS[it] }
            }
            toggleRow(R.string.dream_clock, R.string.dream_clock_desc, offOn, Settings.dreamClock) { Settings.dreamClock = it }
            item { OptionRow(stringResource(R.string.dream_map), stringResource(R.string.dream_map_desc), MapSize.entries.map { stringResource(it.label) }, Settings.dreamMap.ordinal) { Settings.dreamMap = MapSize.entries[it] } }
            if (showAll) toggleRow(R.string.ken_burns, R.string.dream_ken_burns_desc, offOn, Settings.dreamKenBurns) { Settings.dreamKenBurns = it }
            if (showAll) toggleRow(R.string.dream_info, R.string.dream_info_desc, offOn, Settings.dreamInfo) { Settings.dreamInfo = it }
            if (showAll) toggleRow(R.string.dream_ring, R.string.dream_ring_desc, offOn, Settings.dreamRing) { Settings.dreamRing = it }
        }

        if (showAll) {
            groupHeader(R.string.group_storage)
            item {
                ActionRow(stringResource(R.string.clear_cache), cacheText ?: "…") {
                    val l = SingletonImageLoader.get(ctx)
                    l.memoryCache?.clear(); ThumbHash.clear()
                    Thread { runCatching { l.diskCache?.clear() }; cacheRound++ }.start()
                }
            }
        }

        groupHeader(R.string.group_account)
        item { InfoRow(stringResource(R.string.immich_server), Immich.server.ifBlank { "–" }) }
        item { InfoRow(stringResource(R.string.signed_in_as), me?.let { "${it.name}  ·  ${it.email}" } ?: "…") }
        if (showAll) item {
            val missing = Permissions.missing
            val status = when {
                missing == null -> stringResource(R.string.perms_checking)
                missing.isEmpty() -> stringResource(R.string.perms_ok)
                else -> stringResource(R.string.perms_missing_short, missing.joinToString(", "))
            }
            ActionRow(stringResource(R.string.perms_title), status) { Permissions.open = true; scope.launch { Permissions.check() } }
        }
        item { ActionRow(stringResource(R.string.reconnect), stringResource(R.string.reconnect_desc)) { reconnect() } }
        item {
            ActionRow(stringResource(if (confirmDisconnect) R.string.are_you_sure else R.string.disconnect), stringResource(if (confirmDisconnect) R.string.are_you_sure_desc else R.string.disconnect_desc)) {
                if (!confirmDisconnect) confirmDisconnect = true else { Immich.disconnect(ctx, Immich.viewer); disconnected() }
            }
        }

        groupHeader(R.string.group_about)
        item {
            val n = Update.latest
            val desc = when {
                n != null -> stringResource(R.string.update_available, n.version)
                checkingUpdate -> stringResource(R.string.update_checking)
                Update.checked -> stringResource(R.string.update_up_to_date)
                else -> stringResource(R.string.update_check_prompt)
            }
            ActionRow(stringResource(R.string.updates), desc) {
                if (n != null) Update.open = true
                else if (!checkingUpdate) scope.launch { checkingUpdate = true; Update.check(ctx, notify = false); checkingUpdate = false; if (Update.latest != null) Update.open = true }
            }
        }
        if (showAll) toggleRow(R.string.update_auto, R.string.update_auto_desc, offOn, Settings.updateCheck) { Settings.updateCheck = it }
        item { ActionRow(stringResource(R.string.whats_new_row), stringResource(R.string.whats_new_row_desc)) { Update.whatsNewOpen = true } }
        item { ActionRow(stringResource(R.string.keys), stringResource(R.string.keys_desc)) { showKeys() } }
        item { InfoRow(stringResource(R.string.app_line, stringResource(Settings.appearance.label)), stringResource(R.string.version, version)) }
        item { InfoRow(stringResource(R.string.immich_server), immichVersion?.let { stringResource(R.string.version, it) } ?: "…") }
        if (showAll) item { InfoRow(stringResource(R.string.map), stringResource(R.string.map_attribution, SAT_ATTRIBUTION)) }
        item { Text(stringResource(R.string.unofficial_long), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
        if (showAll) item { ActionRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_desc)) { diagnosticsOpen = true } }
    }
    if (helpOpen) ScreensaverHelpOverlay { helpOpen = false; dreamRound++ }
    if (diagnosticsOpen) DiagnosticsOverlay { diagnosticsOpen = false }
    if (albumPicker) PickerList(stringResource(R.string.dream_albums), albumChoices(), Settings.dreamAlbum, close = { albumPicker = false }, multi = true) { id, name ->
        Settings.toggleDreamAlbum(id, name)
    }
    if (personPicker) PickerList(stringResource(R.string.dream_people), personChoices(), Settings.dreamPerson, close = { personPicker = false }, multi = true) { id, name ->
        Settings.toggleDreamPerson(id, name)
    }
    if (greetingInput) TextInputOverlay(stringResource(R.string.greeting), Settings.greetingText, actionLabel = stringResource(R.string.action_done), close = { greetingInput = false }) {
        Settings.greetingText = it; greetingInput = false
    }
}

/** Item index of the "Screensaver" group header in the LazyColumn (sum of all items above it). */
private fun dreamGroupIndex(all: Boolean): Int {
    val header = 2
    val viewing = 3 + (if (Settings.showBar) 1 else 0) + (if (all) 3 else 0)
    val slideshow = 5 + (if (all) 3 else 0)
    val music = 2 + (if (Settings.musicMode != MusicMode.OFF) 1 else 0)
    val timeline = 3 + (if (all) 4 else 0) + (if (all && Settings.greeting == Greeting.CUSTOM) 1 else 0)
    val map = 2 + (if (all) 2 else 0)
    val appearance = 5 + (if (all) 1 else 0)
    return header + viewing + slideshow + music + timeline + map + appearance
}

/** Show the Screensaver group in Settings (since 2.2; setting false hides the UI while keeping the DreamService). */
const val SCREENSAVER_ENABLED = true

private fun LazyListScope.groupHeader(@StringRes name: Int) = item {
    Text(stringResource(name), style = MaterialTheme.typography.titleLarge, color = AppColors.primary, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp))
}

private fun LazyListScope.toggleRow(@StringRes title: Int, @StringRes desc: Int, offOn: List<String>, value: Boolean, set: (Boolean) -> Unit) = item {
    OptionRow(stringResource(title), stringResource(desc), offOn, if (value) 1 else 0) { set(it == 1) }
}

/** Multi-option setting row: Left/Right = step without wrapping, OK = cycle forward (wrapping). */
@Composable
private fun OptionRow(title: String, desc: String, options: List<String>, index: Int, modifier: Modifier = Modifier, color: Color? = null, onChoose: (Int) -> Unit) {
    SettingRow(
        title, desc, modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // Always consume Left/Right even at boundaries so focus doesn't unexpectedly jump into the drawer
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (index > 0) onChoose(index - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (index < options.lastIndex) onChoose(index + 1); true }
                else -> false
            }
        },
        onClick = { onChoose((index + 1) % options.size) },
    ) { focus ->
        val dim = if (focus) AppColors.onPrimary.copy(alpha = 0.45f) else AppColors.muted.copy(alpha = 0.5f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = MaterialTheme.typography.titleLarge, color = if (index > 0) (if (focus) AppColors.onPrimary else AppColors.text) else dim)
            if (color != null) Box(Modifier.padding(start = 14.dp).size(16.dp).background(color, CircleShape))
            // 240 dp accommodates longest localized values (e.g. "Country with the most photos", "Land mit den meisten Fotos")
            Text(options[index], style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, modifier = Modifier.padding(horizontal = 14.dp).width(240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text("›", style = MaterialTheme.typography.titleLarge, color = if (index < options.lastIndex) (if (focus) AppColors.onPrimary else AppColors.text) else dim)
        }
    }
}

@Composable
private fun ActionRow(title: String, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) = SettingRow(title, desc, modifier, onClick = onClick) { focus ->
    Text("OK", style = MaterialTheme.typography.labelLarge, color = if (focus) AppColors.onPrimary else AppColors.muted, modifier = Modifier.padding(end = 10.dp))
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.width(220.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingRow(title: String, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit, trailing: @Composable (focus: Boolean) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surface, contentColor = AppColors.text, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                // Typography styles define explicit color in Theme.kt, so set text colors explicitly here
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, maxLines = 1)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = if (focus) AppColors.onPrimary.copy(alpha = 0.8f) else AppColors.muted, maxLines = 2)
            }
            trailing(focus)
        }
    }
}

/** Remote control shortcut overview (opened via INFO button or Settings -> Keys). Any key closes it. */
@Composable
fun KeysOverview(close: () -> Unit) {
    Overlays.Open()
    val fr = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(30); runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).trapFocus().focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) { KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> false; else -> { close(); true } }
            }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column {
            Text(stringResource(R.string.keys), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.keys_close_hint), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyGroup(R.string.key_group_everywhere, listOf(
                        R.string.key_back_hold to R.string.key_back_hold_desc,
                        R.string.key_info to R.string.key_info_desc,
                        R.string.key_left_edge to R.string.key_left_edge_desc,
                        R.string.key_back to R.string.key_back_desc,
                    ))
                    KeyGroup(R.string.key_group_timeline, listOf(
                        R.string.key_arrows to R.string.key_arrows_desc,
                        R.string.key_ok to R.string.key_ok_open,
                        R.string.key_play to R.string.key_play_desc,
                        R.string.key_seek to R.string.key_seek_month,
                        R.string.key_scrollbar to R.string.key_scrollbar_desc,
                        R.string.key_back to R.string.key_back_top,
                    ))
                    KeyGroup(R.string.key_group_settings, listOf(
                        R.string.key_left_right to R.string.key_value,
                        R.string.key_ok to R.string.key_ok_value,
                    ))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyGroup(R.string.key_group_viewer, listOf(
                        R.string.key_left_right to R.string.key_prev_next,
                        R.string.key_ok_play to R.string.key_slideshow_desc,
                        R.string.key_info_btn to R.string.key_info_panel,
                        R.string.key_ok_info to R.string.key_ok_info_desc,
                        R.string.key_seek to R.string.key_seek_viewer,
                        R.string.key_back to R.string.key_close,
                    ))
                    KeyGroup(R.string.key_group_map, listOf(
                        R.string.key_arrows to R.string.key_map_arrows,
                        R.string.key_ok to R.string.key_map_ok,
                        R.string.key_ok_hold to R.string.key_map_ok_hold_desc,
                        R.string.key_play to R.string.key_map_play_desc,
                        R.string.key_seek to R.string.key_map_zoom,
                        R.string.key_back to R.string.key_map_back,
                        R.string.key_map_left to R.string.key_left_edge_desc,
                    ))
                }
            }
        }
    }
}

@Composable
private fun KeyGroup(@StringRes name: Int, rows: List<Pair<Int, Int>>) {
    Column {
        Text(stringResource(name), style = MaterialTheme.typography.titleMedium, color = AppColors.primary, modifier = Modifier.padding(bottom = 4.dp))
        for ((keyLabel, action) in rows) Row(Modifier.padding(vertical = 1.dp)) {
            Text(stringResource(keyLabel), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(150.dp).padding(end = 8.dp), maxLines = 2)
            Text(stringResource(action), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}
