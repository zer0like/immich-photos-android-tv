package com.immichclient.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Local-only diagnostics: records why the app exited. Everything stays on the device (`filesDir/diagnostics.json`,
 * last [MAX] entries):
 *  - a custom UncaughtExceptionHandler persists the stack trace and delegates to the default handler (the app still
 *    exits normally, and the next launch shows what happened);
 *  - at startup, [start] reads the system process-exit log (`ApplicationExitInfo`, Android 11+): crash, ANR,
 *    low memory, signal, user-stopped, etc.;
 *  - caught coroutine errors ([record], via the app's CoroutineExceptionHandler) are also logged as non-fatal.
 * Viewable under Settings -> About -> Diagnostics.
 */
object Diagnostics {
    private const val FILE_NAME = "diagnostics.json"
    private const val MAX = 12
    private const val TAG = "Photos"

    class Entry(val time: Long, val kind: Kind, val summary: String?, val detail: String?, val foreground: Boolean)

    enum class Kind(@StringRes val label: Int) {
        CRASH(R.string.diag_crash), CRASH_NATIVE(R.string.diag_crash_native), ANR(R.string.diag_anr), MEMORY(R.string.diag_memory),
        SIGNAL(R.string.diag_signal), USER(R.string.diag_user), SYSTEM(R.string.diag_system), CAUGHT(R.string.diag_caught),
        OTHER(R.string.diag_other),
    }

    private lateinit var app: Context
    @Volatile private var installed = false

    fun start(c: Context) {
        if (installed) return
        installed = true
        app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { append(Entry(System.currentTimeMillis(), Kind.CRASH, "${t.name}: ${e::class.java.simpleName}", stackTrace(e), true)) }
            previous?.uncaughtException(t, e)
        }
        Thread({ runCatching { readExitReasons() }.onFailure { Log.w(TAG, "diagnostics", it) } }, "diagnostics").start()
    }

    /** Non-fatal caught error (the app kept running), e.g. from a coroutine. */
    fun record(where: String, e: Throwable) {
        if (!installed) return
        Thread { runCatching { append(Entry(System.currentTimeMillis(), Kind.CAUGHT, "$where: ${e::class.java.simpleName}: ${e.message?.take(160)}", stackTrace(e), true)) } }.start()
    }

    private fun stackTrace(e: Throwable): String {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        // Keep only the top frames where the error occurred; deeper lines are mostly framework boilerplate
        return sw.toString().lineSequence().filter { it.isNotBlank() }.take(14).joinToString("\n") { it.trim().take(200) }
    }

    /** System process-exit reasons, only those newer than the last recorded timestamp to avoid duplicates. */
    private fun readExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val last = prefs.getLong("last", 0L)
        var newLast = last
        val list = am.getHistoricalProcessExitReasons(app.packageName, 0, 10).filter { it.timestamp > last }.sortedBy { it.timestamp }
        for (e in list) {
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH -> Kind.CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> Kind.CRASH_NATIVE
                ApplicationExitInfo.REASON_ANR -> Kind.ANR
                ApplicationExitInfo.REASON_LOW_MEMORY -> Kind.MEMORY
                ApplicationExitInfo.REASON_SIGNALED -> Kind.SIGNAL
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> Kind.USER
                ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Kind.SYSTEM
                else -> Kind.OTHER
            }
            val foreground = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            val summary = listOfNotNull(e.description?.takeIf { it.isNotBlank() }, "pss ${e.pss / 1024} MB", if (foreground) null else "importance ${e.importance}").joinToString(" · ")
            // If our UncaughtExceptionHandler already logged this crash around the same time, skip duplicating it
            if (kind == Kind.CRASH && read().any { it.kind == Kind.CRASH && kotlin.math.abs(it.time - e.timestamp) < 15_000 }) {
                newLast = maxOf(newLast, e.timestamp)
                continue
            }
            append(Entry(e.timestamp, kind, summary, null, foreground))
            newLast = maxOf(newLast, e.timestamp)
        }
        if (newLast != last) prefs.edit().putLong("last", newLast).apply()
    }

    private val lock = Any()

    @Synchronized private fun append(r: Entry) = synchronized(lock) {
        val l = (read() + r).sortedBy { it.time }.takeLast(MAX)
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("t", x.time).put("s", x.kind.name).put("o", x.summary ?: JSONObject.NULL).put("d", x.detail ?: JSONObject.NULL).put("v", x.foreground))
        File(app.filesDir, FILE_NAME).writeText(a.toString())
    }

    fun read(): List<Entry> = runCatching {
        val f = File(app.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Entry(o.getLong("t"), runCatching { Kind.valueOf(o.getString("s")) }.getOrDefault(Kind.OTHER), o.optString("o").takeIf { it.isNotEmpty() && it != "null" }, o.optString("d").takeIf { it.isNotEmpty() && it != "null" }, o.optBoolean("v"))
        }
    }.getOrDefault(emptyList())

    fun clear() { runCatching { File(app.filesDir, FILE_NAME).delete() } }
}

/** Settings -> About -> Diagnostics: recent exits with timestamp, reason, and top of the stack trace. */
@Composable
fun DiagnosticsOverlay(close: () -> Unit) {
    Overlays.Open()
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(30); runCatching { fr.requestFocus() } }
    val entries by produceState<List<Diagnostics.Entry>?>(null) { value = withContext(Dispatchers.IO) { Diagnostics.read().sortedByDescending { it.time } } }
    val scroll = rememberScrollState()
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val ram = remember {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        "${mi.totalMem / 1048576} MB"
    }
    Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)).trapFocus().focusRequester(fr).focusable().keyScroll(scroll).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.diagnostics_sub), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
            Text(
                "${stringResource(R.string.app_line, stringResource(Settings.appearance.label))} $version  ·  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  ·  ${Build.MANUFACTURER} ${Build.MODEL}  ·  RAM $ram",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            val l = entries
            when {
                l == null -> Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                l.isEmpty() -> Text(stringResource(R.string.diagnostics_empty), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in l) {
                        Column(Modifier.fillMaxWidth().background(AppColors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            val t = Instant.ofEpochMilli(r.time).atZone(ZoneId.systemDefault()).toLocalDateTime()
                            Row {
                                Text(longDate(t), style = MaterialTheme.typography.labelLarge, color = AppColors.primary)
                                Text("   " + stringResource(r.kind.label) + if (r.foreground && r.kind != Diagnostics.Kind.CAUGHT) "  ·  " + stringResource(R.string.diag_foreground) else "", style = MaterialTheme.typography.labelLarge)
                            }
                            r.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                            r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.diagnostics_footer), style = MaterialTheme.typography.bodySmall)
        }
    }
}
