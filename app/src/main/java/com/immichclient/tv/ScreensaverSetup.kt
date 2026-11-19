package com.immichclient.tv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * Configure the system screensaver to use this app. Android TV usually hides third-party screensaver pickers
 * (especially on Google TV); the underlying setting is `Settings.Secure.screensaver_components`, which requires
 * `WRITE_SECURE_SETTINGS`. Granted once via adb (`pm grant`), the app can toggle itself on and off (restoring the
 * previous screensaver when disabled). Without the permission, shows status + adb instructions or [SelfAdb]
 * (connects to the device's own local network adb port to grant itself the permission).
 */
object Screensaver {
    private const val COMPONENTS_KEY = "screensaver_components"
    private const val ENABLED_KEY = "screensaver_enabled"

    fun component(c: Context): ComponentName = ComponentName(c, PhotoDream::class.java)
    fun componentString(c: Context): String = component(c).flattenToShortString()

    fun current(c: Context): String? = runCatching { AndroidSettings.Secure.getString(c.contentResolver, COMPONENTS_KEY) }.getOrNull()

    /** Is this app listed in `screensaver_components`? */
    fun isSelected(c: Context): Boolean {
        val own = component(c)
        return current(c)?.split(',')?.any { ComponentName.unflattenFromString(it.trim()) == own } == true
    }

    /** Is this app currently the active enabled screensaver? */
    fun isActive(c: Context): Boolean = isSelected(c) && runCatching { AndroidSettings.Secure.getInt(c.contentResolver, ENABLED_KEY, 1) }.getOrDefault(1) != 0

    fun canWrite(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun devOptions(c: Context) = runCatching { AndroidSettings.Global.getInt(c.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) }.getOrDefault(0) == 1
    /** USB debugging (on some boxes network debugging is toggled independently; see [isPortOpen]). */
    fun usbDebugging(c: Context) = runCatching { AndroidSettings.Global.getInt(c.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) }.getOrDefault(0) == 1

    /**
     * Is adb listening on loopback (`127.0.0.1:5555`)? Plain TCP connect/close without an adb handshake so the TV
     * never shows an authorization prompt. Blocks up to 400 ms (call off the main thread).
     */
    fun isPortOpen(): Boolean = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", ADB_PORT), 400) }; true }.getOrDefault(false)
    const val ADB_PORT = 5555

    /** Idle timeout in ms before the screensaver starts (Android TV: `SCREEN_OFF_TIMEOUT`). */
    fun startAfterMs(c: Context): Int? = runCatching { AndroidSettings.System.getInt(c.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.takeIf { it > 0 }

    /** Human-readable label of the currently active screensaver (service label if exported, else component name). */
    fun currentLabel(c: Context): String? {
        val text = current(c)?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cn = ComponentName.unflattenFromString(text) ?: return text
        return runCatching { c.packageManager.getServiceInfo(cn, 0).loadLabel(c.packageManager).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
    }

    /** Save the previous screensaver component before replacing it (unless it's already this app). */
    fun rememberPrevious(c: Context) {
        val now = current(c)
        if (!now.isNullOrBlank() && !isSelected(c)) Settings.dreamPrevious = now
    }

    /** Enable: set this app as screensaver (saving the previous one); disable: restore the previous (or system default). */
    fun set(c: Context, on: Boolean): Boolean = runCatching {
        val cr = c.contentResolver
        if (on) {
            rememberPrevious(c)
            AndroidSettings.Secure.putString(cr, COMPONENTS_KEY, componentString(c))
            AndroidSettings.Secure.putInt(cr, ENABLED_KEY, 1)
        } else {
            val prev = Settings.dreamPrevious.takeIf { it.isNotBlank() && ComponentName.unflattenFromString(it) != component(c) } ?: systemDefault(c)
            AndroidSettings.Secure.putString(cr, COMPONENTS_KEY, prev)
        }
        Log.i("Photos", "screensaver ${if (on) "on" else "off"}: now ${current(c)}")
        true
    }.onFailure { Log.w("Photos", "set screensaver", it) }.getOrDefault(false)

    /** System default screensaver component (e.g. Backdrop on Android TV). */
    private fun systemDefault(c: Context): String? =
        runCatching { AndroidSettings.Secure.getString(c.contentResolver, "screensaver_default_component") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { Resources.getSystem().let { r -> r.getIdentifier("config_dreamsDefaultComponent", "string", "android").takeIf { it != 0 }?.let { r.getString(it) } } }.getOrNull()

    fun canOpen(c: Context, action: String) = runCatching { Intent(action).resolveActivity(c.packageManager) != null }.getOrDefault(false)
    fun open(c: Context, action: String): Boolean = runCatching { c.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    const val ACTION_DREAM = "android.settings.DREAM_SETTINGS"
    const val ACTION_ABOUT = AndroidSettings.ACTION_DEVICE_INFO_SETTINGS
    const val ACTION_DEV = AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS

    /** Manual adb commands shown in the on-screen guide. */
    fun commands(c: Context): List<String> = listOf(
        "adb connect ${Pairing.localIp() ?: "<ip>"}",
        "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS",
        "adb shell settings put secure screensaver_components ${componentString(c)}",
        "adb shell settings put secure screensaver_enabled 1",
    )
}

/**
 * Self-adb helper via local network debugging (`127.0.0.1:5555`): grants this app `WRITE_SECURE_SETTINGS`
 * and sets it as the system screensaver. Requires "Network debugging" (or "ADB over network") in Developer options;
 * the TV asks once to authorize the key stored in `filesDir`.
 */
object SelfAdb {
    var status by mutableStateOf<Int?>(null)
        private set
    var detail by mutableStateOf<String?>(null)
        private set
    @Volatile private var busy = false

    fun setup(c: Context) {
        if (busy) return
        busy = true
        val app = c.applicationContext
        status = R.string.adb_connecting; detail = null
        thread(name = "SelfAdb") {
            try {
                // Closed port = network debugging disabled: fail fast instead of waiting for timeout
                if (!Screensaver.isPortOpen()) { status = R.string.adb_no_connection; return@thread }
                Screensaver.rememberPrevious(app)
                val priv = File(app.filesDir, "adbkey"); val pub = File(app.filesDir, "adbkey.pub")
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
                // Allow generous timeout for the user to accept the TV authorization dialog
                Dadb.create("127.0.0.1", Screensaver.ADB_PORT, AdbKeyPair.read(priv, pub), 4000, 120_000).use { d ->
                    val errors = StringBuilder()
                    fun sh(cmd: String) {
                        val r = d.shell(cmd)
                        if (r.exitCode != 0) errors.append("$cmd -> ${r.allOutput.trim()}\n")
                    }
                    if (!Screensaver.canWrite(app)) sh("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    // Once permitted, let the app set itself (and save previous); otherwise fall back to adb settings commands
                    if (!Screensaver.set(app, true)) {
                        sh("settings put secure screensaver_components ${Screensaver.componentString(app)}")
                        sh("settings put secure screensaver_enabled 1")
                    }
                    Log.i("Photos", "adb: done, write=${Screensaver.canWrite(app)} active=${Screensaver.isActive(app)} ${errors.toString().trim()}")
                    status = if (Screensaver.isActive(app)) R.string.adb_done else R.string.adb_failed
                    detail = errors.toString().trim().ifEmpty { null }
                }
            } catch (e: java.net.ConnectException) {
                Log.w("Photos", "adb: connection failed", e); status = R.string.adb_no_connection; detail = null
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("Photos", "adb: timeout", e); status = R.string.adb_no_permission; detail = null
            } catch (e: Exception) {
                Log.w("Photos", "adb failed", e); status = R.string.adb_failed; detail = e.message?.take(200)
            } finally { busy = false }
        }
    }
}

/** Settings -> Screensaver -> Set up on this device: live status, action buttons, and step-by-step instructions. */
@Composable
fun ScreensaverHelpOverlay(close: () -> Unit) {
    Overlays.Open()
    val ctx = LocalContext.current
    BackHandler { close() }
    var round by remember { mutableIntStateOf(0) }
    // Poll every 2 s so external adb/system changes show up live
    LaunchedEffect(Unit) { while (true) { delay(2000); round++ } }
    val active = remember(round, SelfAdb.status) { Screensaver.isActive(ctx) }
    val canWrite = remember(round, SelfAdb.status) { Screensaver.canWrite(ctx) }
    val dev = remember(round) { Screensaver.devOptions(ctx) }
    val usb = remember(round) { Screensaver.usbDebugging(ctx) }
    val netOpen by produceState(false, round) { value = withContext(Dispatchers.IO) { Screensaver.isPortOpen() } }
    val currentName = remember(round, SelfAdb.status) { Screensaver.currentLabel(ctx) }
    val startAfter = remember(round) { Screensaver.startAfterMs(ctx) }
    val canDream = remember { Screensaver.canOpen(ctx, Screensaver.ACTION_DREAM) }
    val canAbout = remember { Screensaver.canOpen(ctx, Screensaver.ACTION_ABOUT) }
    val canDev = remember { Screensaver.canOpen(ctx, Screensaver.ACTION_DEV) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }

    // Header + status stay pinned at the top; action buttons and instructions scroll below
    Column(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).trapFocus().padding(start = 64.dp, end = 64.dp, top = 30.dp)) {
        Text(stringResource(R.string.dream_setup), style = MaterialTheme.typography.headlineMedium, color = AppColors.text)
        Text(stringResource(R.string.dream_setup_sub), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.fillMaxWidth().background(AppColors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusCheck(active, stringResource(if (active) R.string.dream_status_on else R.string.dream_status_off))
                    StatusCheck(canWrite, stringResource(if (canWrite) R.string.dream_perm_yes else R.string.dream_perm_no))
                }
                Column(Modifier.weight(0.7f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusCheck(dev, stringResource(if (dev) R.string.dream_dev_yes else R.string.dream_dev_no))
                    StatusCheck(usb, stringResource(if (usb) R.string.dream_usb_yes else R.string.dream_usb_no))
                    StatusCheck(netOpen, stringResource(if (netOpen) R.string.dream_net_yes else R.string.dream_net_no))
                }
            }
            val timeStr = startAfter?.let { ms -> if (ms >= 60_000) stringResource(R.string.minutes, ms / 60_000) else stringResource(R.string.seconds, ms / 1000) }
            val lines = listOfNotNull(
                currentName?.takeIf { !active }?.let { stringResource(R.string.dream_current, it) },
                timeStr?.takeIf { startAfter!! < 24 * 3_600_000 }?.let { stringResource(R.string.dream_starts_after, it) },
            )
            if (lines.isNotEmpty()) Text(lines.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = AppColors.muted, modifier = Modifier.padding(start = 32.dp), maxLines = 1)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Direct buttons
            if (canWrite) item {
                ActionButton(stringResource(if (active) R.string.dream_turn_off else R.string.dream_turn_on), stringResource(R.string.dream_toggle_desc), Modifier.fillMaxWidth().focusRequester(first)) {
                    Screensaver.set(ctx, !active); round++
                }
            }
            if (canDream) item {
                ActionButton(stringResource(R.string.dream_open_system), stringResource(R.string.dream_open_system_desc), Modifier.fillMaxWidth().then(if (!canWrite) Modifier.focusRequester(first) else Modifier)) {
                    Screensaver.open(ctx, Screensaver.ACTION_DREAM)
                }
            }
            // Automatic via self-adb
            item {
                Text(stringResource(R.string.dream_auto_heading), style = MaterialTheme.typography.titleLarge, color = AppColors.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                Text(stringResource(R.string.dream_auto_desc), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
            }
            item {
                val s = SelfAdb.status
                ActionButton(
                    stringResource(R.string.dream_auto_btn),
                    s?.let { stringResource(it) + (SelfAdb.detail?.let { d -> "  ·  $d" } ?: "") } ?: stringResource(R.string.dream_auto_btn_desc),
                    Modifier.fillMaxWidth().then(if (!canWrite && !canDream) Modifier.focusRequester(first) else Modifier),
                ) { SelfAdb.setup(ctx) }
            }
            // Manual steps
            item {
                Text(stringResource(R.string.dream_manual_heading), style = MaterialTheme.typography.titleLarge, color = AppColors.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                GuideStep("1", stringResource(R.string.dream_step1))
                GuideStep("2", stringResource(R.string.dream_step2))
                GuideStep("3", stringResource(R.string.dream_step3))
            }
            item {
                Column(Modifier.fillMaxWidth().background(AppColors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    for (cmd in Screensaver.commands(ctx)) Text(cmd, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = AppColors.text)
                }
                Text(stringResource(R.string.dream_step4), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (canAbout) ActionButton(stringResource(R.string.dream_open_about), stringResource(R.string.dream_open_about_desc), Modifier.weight(1f)) { Screensaver.open(ctx, Screensaver.ACTION_ABOUT) }
                    if (canDev) ActionButton(stringResource(R.string.dream_open_dev), stringResource(R.string.dream_open_dev_desc), Modifier.weight(1f)) { Screensaver.open(ctx, Screensaver.ACTION_DEV) }
                }
            }
            item { Text(stringResource(R.string.back_to_close), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun StatusCheck(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = if (ok) Color(0xFF86EFAC) else AppColors.muted)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun GuideStep(n: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(n, style = MaterialTheme.typography.labelLarge, color = AppColors.primary, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Entry in a picker list: name, subtitle, and optional thumbnail (album cover, circular face). */
class PickerItem(val id: String, val name: String, val sub: String?, val image: String?, val round: Boolean = false)

/**
 * Searchable picker list (albums or people for the screensaver), suitable for hundreds of items: search button at the
 * top (filters as you type, accent-insensitive), followed by currently selected items, then the rest. Focus starts on
 * the first item. When [multi] is true, [selectedIds] is comma-separated and OK toggles selection immediately without
 * reordering while the picker stays open.
 */
@Composable
fun PickerList(title: String, items: List<PickerItem>?, selectedIds: String, close: () -> Unit, multi: Boolean = false, onSelect: (String, String) -> Unit) {
    Overlays.Open()
    val selectedSet = remember(selectedIds) { selectedIds.split(',').filter { it.isNotBlank() }.toSet() }
    val initialSelected = remember { selectedSet }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = !searchOpen) { close() }
    val fr = remember { FocusRequester() }
    val searchBtn = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val visible = remember(items, query) {
        val l = items.orEmpty()
        val q = stripAccents(query.trim())
        if (q.isNotEmpty()) l.filter { stripAccents(it.name).contains(q, ignoreCase = true) }
        else l.filter { it.id in initialSelected } + l.filter { it.id !in initialSelected }
    }
    LaunchedEffect(items, query) {
        if (visible.isEmpty()) { if (items != null) { delay(40); runCatching { searchBtn.requestFocus() } }; return@LaunchedEffect }
        listState.scrollToItem(0)
        delay(40)
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) }
    }
    var wasOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).trapFocus().padding(horizontal = 64.dp, vertical = 36.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.displaySmall)
                    if (items != null) Text(
                        Language.number(visible.size) + (if (query.isNotBlank()) "  ·  “$query”" else "") + (if (multi) "  ·  " + stringResource(R.string.dream_albums_hint, selectedSet.size) else ""),
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.muted,
                    )
                }
                if (!items.isNullOrEmpty()) ChoiceChip(
                    selected = query.isNotBlank(), onClick = { searchOpen = true }, modifier = Modifier.focusRequester(searchBtn),
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                    text = query.ifBlank { stringResource(R.string.search) },
                )
            }
            Spacer(Modifier.height(16.dp))
            when {
                items == null -> LoadingPill(true)
                visible.isEmpty() -> Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(visible, key = { it.id }) { item ->
                        PickerRow(item, item.id in selectedSet, if (item === visible.first()) Modifier.focusRequester(fr) else Modifier) { onSelect(item.id, item.name) }
                    }
                }
            }
        }
    }
    LaunchedEffect(searchOpen) { if (wasOpen && !searchOpen && visible.isEmpty()) { delay(16); runCatching { searchBtn.requestFocus() } }; wasOpen = searchOpen }
    if (searchOpen) TextInputOverlay(title, query, live = { query = it }, actionLabel = stringResource(R.string.action_done), close = { searchOpen = false }) { query = it; searchOpen = false }
}

@Composable
private fun PickerRow(item: PickerItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp).onFocusChanged { focus = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surface, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = if (item.round) CircleShape else RoundedCornerShape(8.dp)
            Box(Modifier.size(46.dp).clip(shape).background(AppColors.surfaceHigh)) {
                if (item.image != null) AsyncImage(imageRequest(item.image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, maxLines = 1)
                item.sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (focus) AppColors.onPrimary.copy(alpha = 0.8f) else AppColors.muted, maxLines = 1) }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.padding(end = 8.dp), tint = if (focus) AppColors.onPrimary else AppColors.primary)
        }
    }
}

/** Albums for the picker: cover thumbnail, name + item count/date range, sorted by most recently updated. */
@Composable
fun albumChoices(): List<PickerItem>? {
    val l by produceState<List<PickerItem>?>(null) {
        value = runCatching { Immich.albums().filter { it.count > 0 }.sortedByDescending { it.updatedAt }.map { PickerItem(it.id, it.name, albumSub(it), it.cover?.let(Immich::thumb)) } }.getOrDefault(emptyList())
    }
    return l
}

/** People for the picker: circular face + name, in Immich's order (favorites first, then most photos). */
@Composable
fun personChoices(): List<PickerItem>? {
    val l by produceState<List<PickerItem>?>(null) {
        value = runCatching { Immich.people().map { PickerItem(it.id, it.name, null, Immich.face(it.id), round = true) } }.getOrDefault(emptyList())
    }
    return l
}
