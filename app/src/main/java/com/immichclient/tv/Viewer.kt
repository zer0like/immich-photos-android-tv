package com.immichclient.tv

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Test flag (`--ez video_no_placeholder true`): simulates devices where Media3 forbids placeholder surfaces
 * (e.g. many Fire TV models, `codecNeedsSetOutputSurfaceWorkaround`), delaying decoder init until a real surface exists.
 */
@Volatile var videoNoPlaceholder = false

class ViewerState(val source: ImageSource, start: Int, val title: String?, slideshow: Boolean) {
    var index by mutableIntStateOf(start)
    var direction = 1
    var slideshow by mutableStateOf(slideshow)
    var info by mutableStateOf(false)
    /** Timestamp of last user activity; bottom bar auto-hides after a few seconds. */
    var lastActive by mutableLongStateOf(System.currentTimeMillis())
    var playingVideo by mutableStateOf(false)
    var videoError by mutableStateOf(false)
    var videoHud by mutableStateOf(false)
    var videoPaused by mutableStateOf(false)
    var seekPreviewMs by mutableLongStateOf(-1L)
    var seekDeltaSec by mutableIntStateOf(0)
    var seekRound by mutableIntStateOf(0)
    var player: ExoPlayer? = null
    /** Next slideshow photo, chosen (and prefetched) while the current one is displayed. */
    var nextSlideIndex = -1
    /** Viewport dimensions for deciding whether two portrait photos fit side by side. */
    var widthPx = 1920
    var heightPx = 1080
    var gapPx = 36

    /** Second portrait photo if it fits cleanly beside photo [i] on the screen; otherwise null. */
    fun pairFor(i: Int): Asset? {
        val a = source.at(i) ?: return null
        val b = source.at(i + 1) ?: return null
        if (a.video || b.video || a.ratio >= 1f || b.ratio >= 1f) return null
        val total = heightPx * a.ratio + gapPx + heightPx * b.ratio
        return if (total <= widthPx) b else null
    }

    /** Non-repeating random slideshow order (see [Shuffler]). */
    private val shuffler = Shuffler()

    /** Next photo for the slideshow: sequential or shuffled, skipping videos if configured; -1 = finished. */
    fun slideTarget(): Int {
        val n = source.count
        if (Settings.slideShuffle)
            return shuffler.next(n, index) { Settings.slideVideos || source.at(it)?.video != true }
        val step = if (pairFor(index) != null) 2 else 1
        var i = index + step
        while (i < n && !Settings.slideVideos && source.at(i)?.video == true) i++
        return if (i < n) i else -1
    }

    /** Advance slideshow one step (or stop at the end). */
    fun advanceSlide() {
        val d = nextSlideIndex.takeIf { it >= 0 && it != index && it < source.count } ?: slideTarget()
        nextSlideIndex = -1
        if (d < 0) { slideshow = false; return }
        videoHud = false; videoPaused = false; seekPreviewMs = -1L; seekDeltaSec = 0; seekRound = 0
        direction = if (Settings.slideTransitionSlide) 1 else 0
        index = d
        if (Settings.slideShuffle) shuffler.mark(d)
    }
}

@Composable
fun Viewer(state: ViewerState, onMap: ((Asset) -> Unit)? = null, close: (Asset?) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val current = state.source.at(state.index)
    BackHandler {
        when {
            state.info -> state.info = false
            state.videoHud -> state.videoHud = false
            else -> close(state.source.at(state.index))
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    // Keep screen awake during a slideshow so the system screensaver doesn't interrupt it.
    // Once paused or stopped, normal idle timeout resumes.
    val view = LocalView.current
    DisposableEffect(state.slideshow) {
        view.keepScreenOn = state.slideshow
        onDispose { view.keepScreenOn = false }
    }

    fun step(d: Int) {
        val delta = when {
            d == 1 && state.pairFor(state.index) != null -> 2
            d == -1 && state.index >= 2 && state.pairFor(state.index - 2) != null -> -2
            else -> d
        }
        val n = (state.index + delta).coerceIn(0, (state.source.count - 1).coerceAtLeast(0))
        if (n == state.index) return
        // Stop video immediately (free buffers) rather than after the transition, avoiding memory spikes beside the next preview
        if (current?.video == true) { state.player?.stop(); state.playingVideo = false }
        state.videoHud = false
        state.videoPaused = false
        state.seekPreviewMs = -1L
        state.seekDeltaSec = 0
        state.seekRound = 0
        state.direction = d
        state.index = n
        state.lastActive = System.currentTimeMillis()
    }

    fun seekStepMs(durMs: Long, repeat: Int): Long {
        val base = when {
            durMs in 1..59_999 -> 5_000L
            durMs >= 300_000 -> 15_000L
            else -> 10_000L
        }
        return if (repeat >= 4) base * 2 else base
    }

    fun seekBy(stepMs: Long) {
        val sp = state.player ?: return
        val dur = sp.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val base = if (state.seekPreviewMs >= 0L) state.seekPreviewMs else sp.currentPosition
        val target = (base + stepMs).coerceIn(0L, dur)
        state.seekPreviewMs = target
        state.seekDeltaSec = (if (state.seekRound > 0) state.seekDeltaSec else 0) + (stepMs / 1000L).toInt()
        state.seekRound++
        state.videoHud = true
        state.lastActive = System.currentTimeMillis()
    }

    // Debounced seek so holding D-Pad scrubs smoothly along the timeline without flooding the decoder
    LaunchedEffect(state.seekRound) {
        if (state.seekRound == 0) return@LaunchedEffect
        delay(160)
        val target = state.seekPreviewMs
        if (target >= 0L) state.player?.seekTo(target)
        state.seekPreviewMs = -1L
        delay(900)
        state.seekDeltaSec = 0
        state.seekRound = 0
    }

    // Hide video seek HUD after 4 s of inactivity (stays visible while paused)
    LaunchedEffect(state.videoHud, state.lastActive, state.videoPaused, state.index) {
        if (state.videoHud && !state.videoPaused) {
            delay(4_000)
            state.videoHud = false
        }
    }

    // Light neighbor prefetch: next first, then previous, then a bit further if idle.
    // Skipped while playing video (player needs the memory); previews are ~6 MB each as hardware bitmaps.
    LaunchedEffect(state.index, state.playingVideo, state.slideshow) {
        if (state.playingVideo) return@LaunchedEffect
        // Shuffled slideshow: neighbors aren't next; the slideshow loop prefetches its own target
        if (state.slideshow && Settings.slideShuffle) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        fun load(d: Int) { state.source.at(state.index + d)?.takeIf { !it.video }?.let { loader.enqueue(previewRequest(ctx, it.id)) } }
        load(1); load(2)
        delay(300); load(-1); load(-2)
        delay(700); load(3)
    }
    // Slideshow timer: pick and prefetch next target (even when shuffled or skipping videos), then advance
    LaunchedEffect(state.slideshow, state.index, state.playingVideo) {
        if (!state.slideshow || state.playingVideo) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        val target = state.slideTarget()
        state.nextSlideIndex = target
        if (target >= 0) {
            val loader = SingletonImageLoader.get(ctx)
            // Target month may not be loaded yet (at() triggers loading): retry briefly
            for (attempt in 0 until 3) {
                val a = state.source.at(target)
                if (a != null) { if (!a.video) loader.enqueue(previewRequest(ctx, a.id)); break }
                delay(500)
            }
        }
        delay((Settings.slideMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300))
        state.advanceSlide()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (current?.video == true && state.videoHud && !state.info && !state.slideshow) {
                        val sp = state.player
                        seekBy(-seekStepMs(sp?.duration ?: 0L, e.nativeKeyEvent.repeatCount))
                    } else {
                        state.slideshow = false; step(-1)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (current?.video == true && state.videoHud && !state.info && !state.slideshow) {
                        val sp = state.player
                        seekBy(seekStepMs(sp?.duration ?: 0L, e.nativeKeyEvent.repeatCount))
                    } else {
                        state.slideshow = false; step(1)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (current?.video == true && !state.info && !state.slideshow) {
                        state.videoHud = !state.videoHud
                        state.lastActive = System.currentTimeMillis()
                    } else {
                        state.info = !state.info; state.lastActive = System.currentTimeMillis()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> {
                    if (k == KeyEvent.KEYCODE_DPAD_UP && current?.video == true && state.videoHud && !state.info) {
                        state.videoHud = false
                    } else {
                        state.info = !state.info
                        if (state.info) state.videoHud = false
                    }
                    state.lastActive = System.currentTimeMillis(); true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val sp = state.player
                    // With info panel open on a geotagged photo: OK jumps to the world map
                    if (state.info && onMap != null && current != null && current.lat != null) onMap(current)
                    else if (current?.video == true && sp != null && !state.slideshow) {
                        val playing = !sp.playWhenReady
                        sp.playWhenReady = playing
                        state.videoPaused = !playing
                        state.videoHud = true
                    } else state.slideshow = !state.slideshow
                    state.lastActive = System.currentTimeMillis(); true
                }
                // FF / RW: 10 s in a video, 10 photos otherwise
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (current?.video == true && state.player != null) seekBy(10_000L) else step(10); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (current?.video == true && state.player != null) seekBy(-10_000L) else step(-10); true }
                else -> false
            }
        },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val gapPx = with(density) { 24.dp.roundToPx() }
        state.widthPx = widthPx
        state.heightPx = heightPx
        state.gapPx = gapPx

        // Intentionally no fadeIn/fadeOut here: that allocates a full-screen 8 MB offscreen layer per page cached by HWUI.
        // Crossfading happens inside MediaContent via ModulateAlpha (no offscreen layer); slide/scale only sets lifetime.
        AnimatedContent(
            targetState = state.index,
            transitionSpec = {
                when (state.direction) {
                    // Slideshow: new photo fades in and settles with a gentle zoom
                    0 -> scaleIn(tween(1400), initialScale = 1.03f) togetherWith scaleOut(tween(900), targetScale = 1f)
                    else -> slideInHorizontally(tween(260)) { it / 6 * state.direction.coerceIn(-1, 1) } togetherWith
                        slideOutHorizontally(tween(260)) { -it / 6 * state.direction.coerceIn(-1, 1) }
                }
            },
            label = "photo",
        ) { i ->
            val a = state.source.at(i)
            val b = state.pairFor(i)
            val alpha by transition.animateFloat(transitionSpec = { tween(if (state.direction == 0) 900 else 200) }, label = "alpha") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            if (a != null) MediaContent(a, b, state, active = i == state.index, alpha = { alpha })
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted) }
        }

        // Country outline map (photo frame style): only for geotagged photos, hidden during video/info/"photo only" slideshow.
        val cleanSlideshow = state.slideshow && Settings.slideshowOverlay == SlideshowOverlay.PHOTO_ONLY
        val mapScale = Settings.viewerMap.scale
        CountryMapOverlay(
            target = current?.takeIf { mapScale > 0f && !it.video && it.lat != null && it.lon != null && !state.info && !cleanSlideshow },
            widthPx = widthPx, heightPx = heightPx, sizeScale = mapScale,
        )

        // Bottom bar: date & place, can be disabled or set to auto-hide (0 = always show)
        var showBar by remember { mutableStateOf(Settings.showBar) }
        LaunchedEffect(state.lastActive, state.index, Settings.showBar) {
            if (!Settings.showBar) { showBar = false; return@LaunchedEffect }
            showBar = true; val ms = Settings.barMs; if (ms > 0) { delay(ms.toLong()); showBar = false }
        }
        AnimatedVisibility(
            ((showBar && !cleanSlideshow) || state.info) && current != null && !state.playingVideo && !state.videoHud,
            Modifier.align(Alignment.BottomStart),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (current != null) BottomBar(current, state, hasMap = current.lat != null && !current.video && mapScale > 0f)
        }
        AnimatedVisibility(
            current?.video == true && state.videoHud && !state.info && !state.slideshow,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 },
        ) {
            VideoTransportHud(state)
        }
        AnimatedVisibility(
            state.info && current != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            if (current != null) InfoPanel(current, canJumpToMap = onMap != null && current.lat != null)
        }
        // Top-right slideshow indicator: brief "Slideshow" badge on start and "Slideshow paused" on stop.
        // Progress ring keeps a fixed spot; the badge only fades its alpha so layout stays steady.
        var badge by remember { mutableStateOf<Boolean?>(null) }
        var badgeRound by remember { mutableIntStateOf(0) }
        var firstTime by remember { mutableStateOf(true) }
        LaunchedEffect(state.slideshow) {
            if (firstTime) { firstTime = false; if (!state.slideshow) return@LaunchedEffect }
            // Hide "Slideshow" badge in photo-only mode, but still show "Slideshow paused" on user key press
            badge = state.slideshow.takeIf { !(it && Settings.slideshowOverlay == SlideshowOverlay.PHOTO_ONLY) }; badgeRound++
        }
        LaunchedEffect(badgeRound) { if (badge != null) { delay(if (badge == true) 5000 else 3000); badge = null } }
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = badge
            val badgeAlpha by animateFloatAsState(if (b != null) 1f else 0f, tween(450), label = "badge")
            var lastBadge by remember { mutableStateOf(true) } // keep last label while fading out
            if (b != null) lastBadge = b
            Row(
                Modifier.padding(end = 10.dp).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(start = 9.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (lastBadge) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (lastBadge) R.string.slideshow else R.string.slideshow_paused), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
            AnimatedVisibility(state.slideshow && Settings.slideshowOverlay == SlideshowOverlay.ALL, enter = fadeIn(), exit = fadeOut()) { SlideRing(state) }
        }
    }
}

/** Countdown ring filling over the slideshow interval; pauses while a video is playing. */
@Composable
private fun SlideRing(state: ViewerState) {
    val fraction = remember { Animatable(0f) }
    LaunchedEffect(state.index, state.playingVideo) {
        fraction.snapTo(0f)
        if (!state.playingVideo) fraction.animateTo(1f, tween(Settings.slideMs.toInt(), easing = LinearEasing))
    }
    ProgressRing({ fraction.value })
}

@Composable
private fun MediaContent(a: Asset, b: Asset?, state: ViewerState, active: Boolean, alpha: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        // Slideshow: slow Ken Burns zoom while active; outgoing page keeps its zoom to avoid snapping mid-crossfade,
        // and pausing smoothly eases back to 1.0x.
        val zoom = remember { Animatable(1f) }
        LaunchedEffect(state.slideshow) {
            if (state.slideshow && Settings.kenBurns) { if (active) zoom.animateTo(1.06f, tween((Settings.slideMs + 1500).toInt(), easing = LinearEasing)) }
            else if (zoom.value != 1f) zoom.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
        // 1) ThumbHash 2) grid-cached thumbnail 3) full preview: something is always visible immediately
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null && Settings.blurredBackground) {
            // Stretched darkened 32px ThumbHash backdrop instead of black letterbox bars
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            if (b == null) {
                val frame = Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true)
                Image(hash, null, frame, contentScale = ContentScale.FillBounds)
            }
        }
        if (b != null) {
            Row(
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (item in listOf(a, b)) {
                    Box(
                        Modifier.fillMaxHeight().aspectRatio(item.ratio.coerceIn(0.2f, 1f), matchHeightConstraintsFirst = true),
                        contentAlignment = Alignment.Center,
                    ) {
                        val h = remember(item.thumbhash) { item.thumbhash?.let { ThumbHash.bitmap(it) } }
                        if (h != null) Image(h, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        AsyncImage(imageRequest(Immich.thumb(item.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        AsyncImage(previewImage(item.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
        } else {
            val mod = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }
            if (!a.video || !state.playingVideo) AsyncImage(imageRequest(Immich.thumb(a.id)), null, mod, contentScale = ContentScale.Fit)
            if (!a.video) AsyncImage(previewImage(a.id), null, mod, contentScale = ContentScale.Fit)
            if (a.video && active) VideoPlayer(a, state)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(a: Asset, state: ViewerState) {
    val ctx = LocalContext.current
    val player = remember {
        // Generous buffer for 4K / high-bitrate videos and HLS segments; prevents stuttering on fast TV boxes.
        val buffers = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 3_000)
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(5_000, true)
            .build()
        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                out.add(object : MediaCodecVideoRenderer(
                    context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    true, eventHandler, eventListener, 50,
                ) {
                    override fun codecNeedsSetOutputSurfaceWorkaround(name: String) =
                        videoNoPlaceholder || super.codecNeedsSetOutputSurfaceWorkaround(name)

                    override fun getMediaFormat(
                        format: Format,
                        codecMimeType: String,
                        codecMaxValues: CodecMaxValues,
                        codecOperatingRate: Float,
                        deviceNeedsNoPostProcessWorkaround: Boolean,
                        tunnelingAudioSessionId: Int,
                    ): MediaFormat {
                        val mf = super.getMediaFormat(
                            format, codecMimeType, codecMaxValues, codecOperatingRate,
                            deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
                        )
                        // Amlogic decoders sometimes reject fps > 60 (e.g. 60.000004 or 240fps slow-mo)
                        for (key in arrayOf(MediaFormat.KEY_FRAME_RATE, MediaFormat.KEY_OPERATING_RATE)) {
                            if (mf.containsKey(key)) {
                                val fps = runCatching { mf.getFloat(key) }.getOrNull()
                                if (fps != null && fps > 60f) mf.setFloat(key, 60f)
                            }
                        }
                        return mf
                    }
                })
            }
        }.setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(buffers)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Immich.http)))
            .build()
    }
    // On Android 10+ (API 29+), SurfaceView supports hardware HWC rotation plus direct 10-bit HDR (yuv420p10le HLG)
    // and 4K without GPU copies through TextureView (which drops to ~10 fps on Amlogic).
    var aspectRatio by remember { mutableFloatStateOf(0f) }
    val useTexture = remember(a.id) { Build.VERSION.SDK_INT < 29 && a.ratio < 1f }
    DisposableEffect(a.id) {
        state.videoError = false
        aspectRatio = 0f
        player.setMediaItem(MediaItem.fromUri(Immich.video(a.id)))
        player.prepare()
        player.playWhenReady = true
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.playingVideo = isPlaying }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) aspectRatio = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onPlaybackStateChanged(s: Int) {
                // Slideshow: advance after video finishes
                if (s == Player.STATE_ENDED) { state.playingVideo = false; if (state.slideshow) state.advanceSlide() }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Photos", "video ${a.id}", error)
                state.playingVideo = false; state.videoError = true // slideshow advances normally after slide duration
            }
        }
        player.addListener(listener)
        state.player = player
        // Release when this page exits; on video -> video, the new page may already have replaced state.player
        onDispose {
            player.removeListener(listener); player.stop(); player.release()
            if (state.player === player) { state.player = null; state.playingVideo = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val r = if (aspectRatio > 0f) aspectRatio else a.ratio
        AndroidView(
            factory = { c ->
                if (useTexture) TextureView(c).also { player.setVideoTextureView(it) }
                else SurfaceView(c).also { player.setVideoSurfaceView(it) }
            },
            onRelease = { v ->
                if (v is TextureView) player.clearVideoTextureView(v)
                else if (v is SurfaceView) player.clearVideoSurfaceView(v)
            },
            modifier = Modifier.align(Alignment.Center).aspectRatio(r.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true),
        )
        if (state.videoError) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VideocamOff, null, Modifier.size(44.dp), tint = AppColors.muted)
            Text(stringResource(R.string.video_error), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 8.dp))
        }
        if (!state.videoHud) VideoProgressLine(player, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun VideoProgressLine(player: ExoPlayer, modifier: Modifier = Modifier) {
    val fraction by produceState(0f, player) {
        while (true) { val d = player.duration; value = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f; delay(250) }
    }
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x28FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(AppColors.primary))
    }
}

private fun formatTimeMs(ms: Long): String {
    val tot = (ms.coerceAtLeast(0L) / 1000L)
    val h = tot / 3600L
    val m = (tot % 3600L) / 60L
    val s = tot % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun VideoTransportHud(state: ViewerState) {
    val sp = state.player
    val pos by produceState(Triple(0L, 0L, 0L), sp, state.seekPreviewMs, state.videoHud) {
        while (true) {
            val dur = sp?.duration?.takeIf { it > 0 } ?: 0L
            val cur = if (state.seekPreviewMs >= 0L) state.seekPreviewMs else (sp?.currentPosition ?: 0L)
            val buf = sp?.bufferedPosition ?: 0L
            value = Triple(cur.coerceIn(0L, dur.coerceAtLeast(1L)), dur, buf.coerceIn(0L, dur.coerceAtLeast(1L)))
            delay(150)
        }
    }
    val curMs = pos.first
    val durMs = pos.second
    val bufMs = pos.third
    val fraction = if (durMs > 0L) (curMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val bufFraction = if (durMs > 0L) (bufMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xEB000000))))
            .padding(horizontal = 44.dp, vertical = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.background(Color(0x9918181C), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (state.videoPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, Modifier.size(18.dp), tint = AppColors.primary,
                    )
                    if (state.seekDeltaSec != 0) {
                        val delta = state.seekDeltaSec
                        Icon(
                            if (delta < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                            null, Modifier.size(18.dp), tint = Color.White,
                        )
                        Text(
                            if (delta > 0) "+${delta}s" else "${delta}s",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            "${formatTimeMs(curMs)} / ${if (durMs > 0) formatTimeMs(durMs) else "--:--"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
                Row(
                    Modifier.background(Color(0x8018181C), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.FastRewind, null, Modifier.size(15.dp), tint = Color(0xCCFFFFFF))
                        Text("◀  ▶", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Icon(Icons.Filled.FastForward, null, Modifier.size(15.dp), tint = Color(0xCCFFFFFF))
                    }
                    Text("·", color = Color(0x80FFFFFF))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OK", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Icon(if (state.videoPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(15.dp), tint = Color(0xCCFFFFFF))
                    }
                    Text("·", color = Color(0x80FFFFFF))
                    Text("▲", style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF))
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(formatTimeMs(curMs), style = MaterialTheme.typography.labelLarge, color = Color.White)
                BoxWithConstraints(Modifier.weight(1f).height(20.dp), contentAlignment = Alignment.CenterStart) {
                    val trackWidth = maxWidth
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(bufFraction).background(Color(0x66FFFFFF)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(AppColors.primary))
                    }
                    Box(
                        Modifier.offset(x = (trackWidth * fraction - 8.dp).coerceIn(0.dp, (trackWidth - 16.dp).coerceAtLeast(0.dp)))
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.5.dp, AppColors.primary, CircleShape),
                    )
                }
                Text(if (durMs > 0) formatTimeMs(durMs) else "--:--", style = MaterialTheme.typography.labelLarge, color = Color(0xCCFFFFFF))
            }
        }
    }
}

@Composable
private fun BottomBar(a: Asset, state: ViewerState, hasMap: Boolean) {
    Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp)) {
            // Counter next to title on the left (bottom-right is occupied by the country map)
            Row(verticalAlignment = Alignment.Bottom) {
                state.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = AppColors.primary, modifier = Modifier.padding(end = 12.dp)) }
                if (Settings.counter) Text("${state.index + 1} / ${Language.number(state.source.count)}", style = MaterialTheme.typography.labelMedium, color = Color(0xAAFFFFFF), modifier = Modifier.padding(bottom = 1.dp))
            }
            Text(longDate(a.date), style = MaterialTheme.typography.titleLarge, color = Color.White)
            val place = listOfNotNull(a.city, a.country).joinToString(", ")
            // When country map is visible (geotagged photo), place is already shown beside the map dot
            if (place.isNotEmpty() && !hasMap) Text(place, style = MaterialTheme.typography.bodyLarge, color = Color(0xDDFFFFFF))
            // Album(s) this photo belongs to; omit the currently viewed album title
            if (Settings.albumName) {
                val names = albumNames(a.id).filter { it != state.title }
                if (names.isNotEmpty()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp), tint = Color(0xBBFFFFFF))
                    Text("  " + albumLine(names), style = MaterialTheme.typography.bodyMedium, color = Color(0xBBFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(a: Asset, canJumpToMap: Boolean) {
    val details by produceState<AssetDetails?>(null, a.id) { value = runCatching { Immich.details(a.id) }.getOrNull() }
    Column(Modifier.fillMaxHeight().width(380.dp).background(Color(0xF0121214)).padding(28.dp)) {
        // Footer hint stays pinned at the bottom; scrollable details above handle long descriptions/people lists
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.info), style = MaterialTheme.typography.headlineSmall)
            InfoRow(Icons.Filled.Schedule, longDate(a.date), null)
            val d = details
            if (d != null) {
                d.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                if (d.people.isNotEmpty()) InfoRow(Icons.Filled.People, d.people.joinToString(", "), null, maxLines = 3)
                val albums = albumNames(a.id)
                if (albums.isNotEmpty()) InfoRow(Icons.Filled.PhotoLibrary, albums.joinToString(", "), null, maxLines = 3)
                d.place?.let { InfoRow(Icons.Filled.Place, it, null) }
                if (a.lat != null && a.lon != null) {
                    MiniCountryMap(a.lat, a.lon, Modifier.fillMaxWidth().height(150.dp).background(AppColors.surface, RoundedCornerShape(12.dp)))
                }
                InfoRow(Icons.Filled.Image, d.filename, d.dimensions)
                d.camera?.let { InfoRow(Icons.Filled.CameraAlt, it, listOfNotNull(d.lens, d.settings).joinToString("\n").ifEmpty { null }) }
            } else {
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = AppColors.muted)
            Text("  " + stringResource(if (canJumpToMap) R.string.hint_info_map else R.string.hint_info), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String?, maxLines: Int = Int.MAX_VALUE) {
    Row {
        Icon(icon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = AppColors.muted)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Albums containing this photo (empty while loading or if none). */
@Composable
fun albumNames(id: String): List<String> =
    produceState(emptyList(), id) { delay(150); value = Immich.albumsOf(id) }.value

/** "Holiday 2023", or "Holiday 2023 + 2" when in multiple albums. */
fun albumLine(names: List<String>) = if (names.size <= 1) names.firstOrNull().orEmpty() else names[0] + "  + " + (names.size - 1)
