package com.immichclient.tv

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * API key permission checker: verifies which of [Pairing.PERMISSIONS] the key has via `/api-keys/me`
 * (a key is always allowed to read its own permissions; `"all"` means unrestricted). On older servers without that
 * endpoint, falls back to probing each permission's endpoint (403 = missing). On startup, displays [PermissionsScreen]
 * once whenever a newly missing permission is detected; afterwards accessible under Settings -> Account -> Key permissions
 * or from any screen lacking a permission (Folders).
 */
object Permissions {
    /** Describes what each permission is used for; [core] = app cannot function without it. */
    class Permission(val name: String, @StringRes val desc: Int, val core: Boolean = false)

    val ALL = listOf(
        Permission("asset.read", R.string.perm_asset_read, core = true),
        Permission("asset.view", R.string.perm_asset_view, core = true),
        Permission("user.read", R.string.perm_user_read),
        Permission("album.read", R.string.perm_album_read),
        Permission("memory.read", R.string.perm_memory_read),
        Permission("person.read", R.string.perm_person_read),
        Permission("map.read", R.string.perm_map_read),
        Permission("folder.read", R.string.perm_folder_read),
    )

    /** null = not yet checked (or could not be determined); otherwise the list of missing permissions. */
    var missing by mutableStateOf<List<String>?>(null)
        private set
    /** Permissions that could not be verified (no /api-keys/me and inconclusive endpoint probe). */
    var unknown by mutableStateOf<Set<String>>(emptySet())
        private set
    var keyName by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Overlay visibility (rendered by MainActivity). */
    var open by mutableStateOf(false)

    suspend fun check() {
        if (Immich.key.isBlank()) return
        busy = true
        try {
            val own = Immich.keyPermissions()
            if (own != null) {
                keyName = own.first
                val has = own.second
                missing = if ("all" in has) emptyList() else ALL.map { it.name }.filter { it !in has }
                unknown = emptySet()
            } else {
                keyName = null
                val result = ALL.associate { it.name to Immich.testPermission(it.name) }
                missing = result.filterValues { it == false }.keys.toList()
                unknown = result.filterValues { it == null }.keys
            }
            Log.i("Photos", "permissions: missing=$missing unknown=$unknown")
        } finally { busy = false }
    }

    fun isMissing(perm: String) = missing?.contains(perm) == true

    /** On startup: open the overview if a missing permission hasn't been reported yet (per account). */
    fun notifyNew(c: Context) {
        val m = missing ?: return
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val prefKey = "permissions_reported" + (Immich.viewer?.let { "_$it" } ?: "")
        val now = m.sorted().joinToString(",")
        val prev = p.getString(prefKey, "").orEmpty()
        if (now != prev) p.edit().putString(prefKey, now).apply()
        if (m.isNotEmpty() && now != prev) open = true
    }
}

/** Overview overlay listing each permission with check/error icon, plus Reconnect / Check again. */
@Composable
fun PermissionsScreen(reconnect: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
    val missing = Permissions.missing
    val coreMissing = missing.orEmpty().any { m -> Permissions.ALL.any { it.name == m && it.core } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).trapFocus()
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_MENU }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column(Modifier.widthIn(max = 900.dp)) {
            Text(stringResource(R.string.perms_title), style = MaterialTheme.typography.displaySmall)
            val sub = when {
                Permissions.busy || missing == null -> stringResource(R.string.perms_checking)
                missing.isEmpty() && Permissions.unknown.isEmpty() -> stringResource(R.string.perms_ok)
                missing.isEmpty() -> stringResource(R.string.perms_partially_unknown)
                coreMissing -> stringResource(R.string.perms_core_missing)
                else -> stringResource(R.string.perms_missing)
            }
            Text(listOfNotNull(Permissions.keyName?.let { "“$it”" }, sub).joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in Permissions.ALL) {
                    val ok = when { missing == null || r.name in Permissions.unknown -> null; r.name in missing -> false; else -> true }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Error; null -> Icons.AutoMirrored.Filled.HelpOutline },
                            null, Modifier.size(24.dp),
                            tint = when (ok) { true -> Color(0xFF86EFAC); false -> Color(0xFFFCA5A5); null -> AppColors.muted },
                        )
                        Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp).width(150.dp))
                        Text(stringResource(r.desc), style = MaterialTheme.typography.bodyLarge, color = if (ok == false) Color.White else AppColors.muted)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (!missing.isNullOrEmpty()) {
                Text(stringResource(R.string.perms_how_to_fix), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!missing.isNullOrEmpty()) {
                    ActionButton(stringResource(R.string.reconnect), stringResource(R.string.perms_reconnect_sub), Modifier.width(260.dp).focusRequester(first)) { reconnect() }
                }
                ActionButton(stringResource(R.string.perms_recheck), null, Modifier.width(220.dp).then(if (missing.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier)) {
                    scope.launch { Permissions.check() }
                }
                ActionButton(stringResource(R.string.perms_close), null, Modifier.width(160.dp)) { close() }
            }
        }
    }
}
