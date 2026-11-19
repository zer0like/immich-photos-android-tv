package com.immichclient.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates: (1) shows "What's new" once after an update (entries live in `whats_new_items` per language),
 * (2) checks GitHub Releases on startup for a newer release, and (3) installs in-app by downloading the APK,
 * verifying its SHA-256 against the GitHub asset digest, and committing a PackageInstaller session (Android prompts
 * the user to confirm; first time requires "install unknown apps" permission for this app).
 * Only a plain anonymous GET to GitHub is sent (no keys or device identifiers).
 */
object Update {
    private const val REPO = "zer0like/immich-photos-android-tv"
    private const val PREFS = "update"
    private const val TAG = "Photos"

    class Release(val version: String, val notes: String, val apk: String, val sha256: String?, val size: Long)

    sealed interface State {
        data object Idle : State
        data class Downloading(val fraction: Float) : State
        data object Installing : State
        /** "Install unknown apps" permission not yet granted for this app */
        data object PermissionRequired : State
        data class Error(val text: String) : State
    }

    /** Newer release on GitHub (null = none or not checked yet). */
    var latest by mutableStateOf<Release?>(null)
        private set
    var checked by mutableStateOf(false)
        private set
    var state by mutableStateOf<State>(State.Idle)
    /** Update prompt dialog open */
    var open by mutableStateOf(false)
    /** "What's new" dialog open */
    var whatsNewOpen by mutableStateOf(false)
    /** Test helper: pretend this version is installed (`--es update_test 2.0`). */
    var testVersion: String? = null

    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    fun version(c: Context): String = testVersion ?: runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "2.10" > "2.9"; missing segments count as 0, suffixes after numbers ("2.8-beta") are ignored. */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(s: String) = s.trimStart('v', 'V').split('.', '-').map { d -> d.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    /** At startup: show "What's new" once after an app update (not on a fresh install). */
    fun onStart(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: return // never testVersion
        val prev = p.getString("seen", null)
        if (prev == now) return
        p.edit().putString("seen", now).apply()
        val info = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        // prev == null: either a fresh install, or an update from a version before this feature (<= 2.7.2)
        val updated = if (prev == null) info != null && info.lastUpdateTime > info.firstInstallTime + 1000 else isNewer(now, prev)
        if (updated) whatsNewOpen = true
    }

    /** Fetch latest GitHub release; notify automatically once per new version (later checks are manual in Settings). */
    suspend fun check(c: Context, notify: Boolean) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "android-tv-immich-client").build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("GitHub ${res.code}")
                    val o = JSONObject(res.body!!.string())
                    val assets = o.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@use null
                    Release(
                        o.getString("tag_name").trimStart('v', 'V'), o.optString("body"), apk.getString("browser_download_url"),
                        apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"), apk.optLong("size"),
                    )
                }
            }.onFailure { Log.w(TAG, "update: check failed: ${it.message}") }.getOrNull()
        }
        checked = true
        latest = r?.takeIf { isNewer(it.version, version(c)) }
        Log.i(TAG, "update: installed ${version(c)}, latest ${r?.version}, newer=${latest != null}")
        val n = latest ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (notify && (testVersion != null || p.getString("reported", null) != n.version) && !whatsNewOpen) {
            p.edit().putString("reported", n.version).apply()
            open = true
        }
    }

    /** Download, verify SHA-256, and start PackageInstaller session. */
    suspend fun install(c: Context) {
        val r = latest ?: return
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) { state = State.PermissionRequired; return }
        state = State.Downloading(0f)
        val file = File(c.cacheDir, "update.apk")
        val err = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(r.apk).header("User-Agent", "android-tv-immich-client").build()).execute().use { res ->
                    if (!res.isSuccessful) error("download ${res.code}")
                    val body = res.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: r.size
                    val md = MessageDigest.getInstance("SHA-256")
                    var read = 0L; var last = 0L
                    body.byteStream().use { inp -> file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); read += n
                            if (total > 0 && read - last > 256 * 1024) { last = read; state = State.Downloading(read.toFloat() / total) }
                        }
                    } }
                    val hash = md.digest().joinToString("") { "%02x".format(it) }
                    if (r.sha256 != null && !hash.equals(r.sha256, ignoreCase = true)) error("sha256 mismatch")
                    Log.i(TAG, "update: ${r.version} downloaded ($read bytes, sha256 $hash)")
                }
                state = State.Installing
                val pi = c.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    file.inputStream().use { inp -> s.openWrite("app.apk", 0, file.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                    val cb = Intent(c, UpdateReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(c, id, cb, flags).intentSender)
                }
            }.exceptionOrNull()
        }
        if (err != null) { Log.w(TAG, "update", err); file.delete(); state = State.Error(err.message ?: err.javaClass.simpleName) }
    }

    /** Open system "Install unknown apps" setting for this app (falls back to Security settings if unavailable). */
    fun openPermissionSettings(c: Context) {
        val per = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(per) }.isSuccess) return
        runCatching { c.startActivity(Intent(AndroidSettings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Condense Markdown release notes to readable lines: (isHeading, text); strips quotes, images, links, max [max] lines. */
    fun summarize(md: String, max: Int = 10): List<Pair<Boolean, String>> = md.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("![") && !it.startsWith("<") && !it.startsWith(">") }
        .map { r -> r.startsWith("#") to r.trimStart('#', '*', '-', ' ').replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1").replace("*", "").replace("`", "") }
        .filter { it.second.isNotBlank() && !it.second.replace("-", "").contains("sha256", ignoreCase = true) }
        .take(max)
}

/**
 * Counts currently open modal overlays (pickers, help dialogs, popups). When an overlay closes, focus is briefly
 * absent and Android would move it to the navigation drawer; MainActivity holds the drawer closed briefly and
 * restores focus to the content area.
 */
object Overlays {
    var open by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Call at the top of an overlay composable to track while it is composed. */
    @Composable
    fun Open() {
        androidx.compose.runtime.DisposableEffect(Unit) { open++; onDispose { open-- } }
    }
}

/** PackageInstaller broadcast receiver: prompts user confirmation or reports failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("Photos", "update: install status $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val prompt = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)
                prompt?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Update.state = Update.State.Idle // app is being replaced
            PackageInstaller.STATUS_FAILURE_ABORTED -> Update.state = Update.State.Idle // user cancelled
            else -> Update.state = Update.State.Error(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status")
        }
    }
}

/** "New version available" overlay: release notes, Update now / Later, download progress, permission prompt, and errors. */
@Composable
fun UpdateScreen(close: () -> Unit) {
    Overlays.Open()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = Update.latest ?: run { close(); return }
    val first = remember { FocusRequester() }
    val busy = Update.state is Update.State.Downloading || Update.state is Update.State.Installing
    BackHandler(enabled = !busy) { Update.state = Update.State.Idle; close() }
    LaunchedEffect(Update.state::class) { delay(30); runCatching { first.requestFocus() } }
    ModalWindow(stringResource(R.string.update_title, r.version), stringResource(R.string.update_sub, Update.version(ctx))) {
        for ((isHeading, line) in Update.summarize(r.notes)) {
            if (isHeading) Text(line, style = MaterialTheme.typography.titleMedium, color = AppColors.primary, modifier = Modifier.padding(top = 6.dp))
            else Text("•  $line", style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        }
        Spacer(Modifier.height(22.dp))
        when (val s = Update.state) {
            is Update.State.Downloading -> Text(stringResource(R.string.update_downloading, (s.fraction * 100).toInt()), style = MaterialTheme.typography.titleMedium, color = AppColors.primary)
            Update.State.Installing -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.titleMedium, color = AppColors.primary)
            Update.State.PermissionRequired -> Text(stringResource(R.string.update_permission), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFCD34D))
            is Update.State.Error -> Text(stringResource(R.string.update_error, s.text), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFCA5A5))
            Update.State.Idle -> {}
        }
        Spacer(Modifier.height(14.dp))
        if (!busy) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (Update.state == Update.State.PermissionRequired) {
                ActionButton(stringResource(R.string.update_permission_btn), null, Modifier.width(300.dp).focusRequester(first)) { Update.state = Update.State.Idle; Update.openPermissionSettings(ctx) }
            } else {
                ActionButton(stringResource(R.string.update_now), null, Modifier.width(240.dp).focusRequester(first)) { scope.launch { Update.install(ctx) } }
            }
            ActionButton(stringResource(R.string.update_later), null, Modifier.width(160.dp)) { Update.state = Update.State.Idle; close() }
        }
    }
}

/** "What's new" overlay shown once after an update. */
@Composable
fun WhatsNewScreen(close: () -> Unit) {
    Overlays.Open()
    val ctx = LocalContext.current
    val button = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(30); runCatching { button.requestFocus() } }
    ModalWindow(stringResource(R.string.whats_new_title, Update.version(ctx)), null) {
        for (line in stringArrayResource(R.array.whats_new_items)) Text("•  $line", style = MaterialTheme.typography.bodyLarge, maxLines = 3)
        Spacer(Modifier.height(24.dp))
        ActionButton(stringResource(R.string.action_done), null, Modifier.width(160.dp).focusRequester(button)) { close() }
    }
}

@Composable
private fun ModalWindow(title: String, sub: String?, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).trapFocus()
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_MENU }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
