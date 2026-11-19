package com.immichclient.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Immich-inspired dark theme colors. */
object AppColors {
    val background = Color(0xFF0A0A0A)
    val surface = Color(0xFF1B1B1D)
    val surfaceHigh = Color(0xFF262629)
    // Accent color is Compose state: readers automatically recompose when changed in Settings
    val primary get() = Settings.accent.primary
    val primaryDark get() = Settings.accent.dark
    /** Dark foreground text/icons placed on top of the accent color */
    val onPrimary get() = Settings.accent.onPrimary
    val text = Color(0xFFE5E7EB)
    val muted = Color(0xFFA1A1AA)
}

@OptIn(ExperimentalTextApi::class)
private fun overpass(w: Int) =
    Font(R.font.overpass, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Overpass = FontFamily(overpass(400), overpass(500), overpass(600), overpass(700))

private val baseStyle = TextStyle(fontFamily = Overpass, color = AppColors.text)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PhotosTheme(content: @Composable () -> Unit) {
    // Shared bring-into-view behavior for all lists and grids (timeline, albums, explore, search): see focusAt30
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides focusAt30) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = AppColors.primary, onPrimary = AppColors.onPrimary,
                background = AppColors.background, surface = AppColors.surface, onSurface = AppColors.text,
                surfaceVariant = AppColors.surfaceHigh, onSurfaceVariant = AppColors.muted, border = AppColors.primary,
            ),
            typography = Typography(
                displaySmall = baseStyle.copy(fontSize = 34.sp, fontWeight = FontWeight(700)),
                headlineSmall = baseStyle.copy(fontSize = 24.sp, fontWeight = FontWeight(600)),
                titleLarge = baseStyle.copy(fontSize = 20.sp, fontWeight = FontWeight(600)),
                titleMedium = baseStyle.copy(fontSize = 16.sp, fontWeight = FontWeight(600)),
                titleSmall = baseStyle.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
                bodyLarge = baseStyle.copy(fontSize = 16.sp),
                bodyMedium = baseStyle.copy(fontSize = 14.sp),
                bodySmall = baseStyle.copy(fontSize = 12.sp, color = AppColors.muted),
                labelLarge = baseStyle.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
                labelMedium = baseStyle.copy(fontSize = 12.sp, fontWeight = FontWeight(600)),
                labelSmall = baseStyle.copy(fontSize = 11.sp, fontWeight = FontWeight(500)),
            ),
            content = content,
        )
    }
}

/** Image request with stable cache key; thumbnails stay unscaled since they are already small. */
@Composable
fun imageRequest(url: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(url) { ImageRequest.Builder(ctx).data(url).size(Size.ORIGINAL).crossfade(80).build() }
}

/** Thumbnail request for grids (also used by the prefetcher). */
fun thumbRequest(ctx: android.content.Context, id: String): ImageRequest =
    ImageRequest.Builder(ctx).data(Immich.thumb(id)).size(Size.ORIGINAL).crossfade(70).build()

@Composable
fun thumbImage(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { thumbRequest(ctx, id) }
}

/**
 * Preview (1440 px) for the viewer, always using the same target size so prefetching and display share one cache entry;
 * never decoded larger than the screen.
 */
fun previewRequest(ctx: android.content.Context, id: String): ImageRequest =
    ImageRequest.Builder(ctx).data(Immich.preview(id)).size(1920, 1080).precision(Precision.INEXACT).build()

@Composable
fun previewImage(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { previewRequest(ctx, id) }
}

/** Preview for memory cards: decoded at reduced resolution (~1.5 MB instead of 6 MB per card). */
@Composable
fun cardImage(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { ImageRequest.Builder(ctx).data(Immich.preview(id)).size(400, 500).precision(Precision.INEXACT).crossfade(120).build() }
}

/** Single photo tile in a grid: ThumbHash immediately, with the real thumbnail fading in over it. */
@Composable
fun PhotoTile(
    asset: Asset,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusTarget: Boolean = false,
    onFocusTargetDone: () -> Unit = {},
    onFocus: () -> Unit = {},
    shape: Shape = RoundedCornerShape(5.dp),
) {
    val fr = remember { FocusRequester() }
    if (focusTarget) LaunchedEffect(asset.id) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusTargetDone(); return@LaunchedEffect }; delay(16) }
        onFocusTargetDone()
    }
    // Intentionally avoids tv-Surface and per-tile animateFloatAsState coroutines to keep fast scrolling ANR-free.
    var focus by remember { mutableStateOf(false) }
    Box(
        modifier.size(width, height)
            .zIndex(if (focus) 1f else 0f)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .graphicsLayer {
                if (focus) {
                    scaleX = 1.06f; scaleY = 1.06f
                    this.shape = shape; clip = true
                }
            }
            .drawWithContent {
                drawContent()
                if (focus) {
                    val r = 5.dp.toPx()
                    drawRoundRect(Color.White, cornerRadius = CornerRadius(r, r), style = Stroke(width = 3.dp.toPx()))
                }
            }
            .background(AppColors.surface),
    ) {
        val hashPainter = remember(asset.thumbhash) {
            asset.thumbhash?.let { ThumbHash.bitmap(it) }?.let { BitmapPainter(it) }
        }
        AsyncImage(
            model = thumbImage(asset.id),
            contentDescription = null,
            placeholder = hashPainter,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (asset.video) VideoBadge(asset, Modifier.align(Alignment.TopEnd))
        if (asset.favorite) Icon(
            Icons.Filled.Favorite, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp), tint = Color.White,
        )
    }
}

@Composable
private fun VideoBadge(a: Asset, modifier: Modifier) {
    Row(
        modifier.padding(6.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shortDuration(a.duration)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White) }
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
    }
}

/**
 * Filter/sort chip readable in every state, including selected + focused. Text color is set explicitly because
 * typography styles carry their own color (AppColors.text) which would otherwise override FilterChipDefaults and
 * leave light gray text on the light accent background when focused.
 */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    text: String,
) {
    val darkText = AppColors.onPrimary
    var focus by remember { mutableStateOf(false) }
    val color = when { focus -> darkText; selected -> AppColors.primary; else -> AppColors.text }
    FilterChip(
        selected = selected, onClick = onClick, modifier = modifier.onFocusChanged { focus = it.isFocused }, leadingIcon = leadingIcon,
        colors = FilterChipDefaults.colors(
            containerColor = Color.Transparent, contentColor = AppColors.text,
            focusedContainerColor = AppColors.primary, focusedContentColor = darkText,
            selectedContainerColor = AppColors.primary.copy(alpha = 0.22f), selectedContentColor = AppColors.primary,
            focusedSelectedContainerColor = AppColors.primary, focusedSelectedContentColor = darkText,
            pressedContainerColor = AppColors.primary, pressedContentColor = darkText,
            pressedSelectedContainerColor = AppColors.primary, pressedSelectedContentColor = darkText,
        ),
        content = { Text(text, color = color, maxLines = 1) },
    )
}

/** Circular countdown progress indicator (slideshow, screensaver): [fraction] in 0..1. */
@Composable
fun ProgressRing(fraction: () -> Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(22.dp)) {
        val stroke = 2.2.dp.toPx()
        // Dark outer halo ensures visibility over bright photos
        drawCircle(Color(0x59000000), style = Stroke(stroke * 2.4f))
        drawCircle(Color(0x38FFFFFF), style = Stroke(stroke))
        drawArc(Color(0xD9FFFFFF), -90f, 360f * fraction(), false, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Circular avatar thumbnail of a person. */
@Composable
fun PersonAvatar(p: Person, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(AppColors.surfaceHigh)) {
        AsyncImage(imageRequest(Immich.face(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** For modal overlays (keyboard, date picker): traps focus inside the overlay. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties { exit = { FocusRequester.Cancel } }
    .focusGroup()

/** Up/Down D-Pad scrolling for long text overlays (`verticalScroll` alone does not respond to D-Pad). */
@Composable
fun Modifier.keyScroll(scroll: androidx.compose.foundation.ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return this.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val step = when (e.key.nativeKeyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 220f
            android.view.KeyEvent.KEYCODE_DPAD_UP -> -220f
            else -> return@onPreviewKeyEvent false
        }
        scope.launch { scroll.animateScrollBy(step) }
        true
    }
}

/** Dark gradient scrim beneath text overlaid on a photo. */
val bottomScrim = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xD9000000))
