package com.immichclient.tv

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * First run: choose a language using large tiles (each in its own language), pre-selected to the system language
 * if supported, otherwise English. The title and subtitle follow whichever tile has focus so anyone can read it.
 * OK selects the language -> [done] (recreates the activity in that language).
 */
@Composable
fun LanguageScreen(finish: () -> Unit, done: () -> Unit) {
    val ctx = LocalContext.current
    val defaultLang = remember { Language.systemLanguage(ctx) }
    var focusedLang by remember { mutableStateOf(defaultLang) }
    val first = remember { FocusRequester() }
    BackHandler { finish() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    // Labels rendered in the language of the currently focused tile (app itself is still in the provisional language)
    val res = remember(focusedLang) { Language.resources(focusedLang) }

    Column(
        Modifier.fillMaxSize().background(AppColors.background).padding(horizontal = 40.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.background(AppColors.surface, CircleShape).padding(12.dp)) {
            Icon(Icons.Filled.Language, null, Modifier.size(28.dp), tint = AppColors.primary)
        }
        Spacer(Modifier.height(14.dp))
        Text(res.getString(R.string.language_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 1)
        Text(res.getString(R.string.language_sub), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (row in Language.LANGUAGES.chunked(5)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (code in row) {
                        val isDefault = code == defaultLang
                        Surface(
                            onClick = { Language.choose(ctx, code); done() },
                            modifier = Modifier.width(148.dp).height(94.dp).then(if (isDefault) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { if (it.isFocused) focusedLang = code },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isDefault) AppColors.primary.copy(alpha = 0.22f) else AppColors.surface,
                                contentColor = AppColors.text,
                                focusedContainerColor = AppColors.primary,
                                focusedContentColor = AppColors.onPrimary,
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = if (isDefault) Border(androidx.compose.foundation.BorderStroke(2.dp, AppColors.primary), shape = RoundedCornerShape(16.dp)) else Border.None,
                            ),
                        ) {
                            // Text styles carry their own color (Theme.kt) which overrides Surface contentColor, so set explicitly
                            val color = if (focusedLang == code) AppColors.onPrimary else AppColors.text
                            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(code.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 2.sp), color = color.copy(alpha = 0.7f))
                                Spacer(Modifier.height(2.dp))
                                Text(Language.name(code), style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp), maxLines = 1, color = color)
                            }
                        }
                    }
                }
            }
        }
    }
}
