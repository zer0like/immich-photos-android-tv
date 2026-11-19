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
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Test (`--ez video_zonder_placeholder true`): doet alsof dit apparaat geen placeholder-surface mag gebruiken, zoals
 * Media3 dat voor o.a. veel Fire TV-modellen beslist (codecNeedsSetOutputSurfaceWorkaround). Dan start de decoder pas
 * als er een echt oppervlak is.
 */
@Volatile var videoZonderPlaceholder = false

class KijkerStaat(val bron: BeeldBron, start: Int, val titel: String?, diashow: Boolean) {
    var index by mutableIntStateOf(start)
    var richting = 1
    var diashow by mutableStateOf(diashow)
    var info by mutableStateOf(false)
    /** laatste toetsdruk; de balk onderin verdwijnt na een paar seconden */
    var actief by mutableLongStateOf(System.currentTimeMillis())
    var speeltVideo by mutableStateOf(false)
    var videoFout by mutableStateOf(false)
    var videoHud by mutableStateOf(false)
    var videoGepauzeerd by mutableStateOf(false)
    var seekPreviewMs by mutableLongStateOf(-1L)
    var seekDeltaSec by mutableIntStateOf(0)
    var seekRonde by mutableIntStateOf(0)
    var speler: ExoPlayer? = null
    /** volgende foto van de diashow, alvast gekozen (en vooraf geladen) zodra de huidige in beeld staat */
    var diaVolgende = -1
    /** schermmaten voor het bepalen of twee staande foto's naast elkaar passen */
    var breedtePx = 1920
    var hoogtePx = 1080
    var tussenPx = 36

    /** Tweede staande foto als die naast [i] op het scherm past; anders null. */
    fun paarVoor(i: Int): Asset? {
        val a = bron.op(i) ?: return null
        val b = bron.op(i + 1) ?: return null
        if (a.video || b.video || a.ratio >= 1f || b.ratio >= 1f) return null
        val totaal = hoogtePx * a.ratio + tussenPx + hoogtePx * b.ratio
        return if (totaal <= breedtePx) b else null
    }

    /** willekeurige diashow zonder herhaling (zie [Schudder]) */
    private val schudder = Schudder()

    /** Volgende foto voor de diashow: op volgorde of willekeurig, video's zo nodig overgeslagen; -1 = klaar. */
    fun diaDoel(): Int {
        val n = bron.aantal
        if (Instellingen.diaWillekeurig)
            return schudder.volgende(n, index) { Instellingen.diaVideos || bron.op(it)?.video != true }
        val stap = if (paarVoor(index) != null) 2 else 1
        var i = index + stap
        while (i < n && !Instellingen.diaVideos && bron.op(i)?.video == true) i++
        return if (i < n) i else -1
    }

    /** Diashow een stap verder (of stoppen aan het eind). */
    fun diaVerder() {
        val d = diaVolgende.takeIf { it >= 0 && it != index && it < bron.aantal } ?: diaDoel()
        diaVolgende = -1
        if (d < 0) { diashow = false; return }
        videoHud = false; videoGepauzeerd = false; seekPreviewMs = -1L; seekDeltaSec = 0; seekRonde = 0
        richting = if (Instellingen.diaSchuiven) 1 else 0
        index = d
        if (Instellingen.diaWillekeurig) schudder.markeer(d)
    }
}

@Composable
fun Kijker(staat: KijkerStaat, opKaart: ((Asset) -> Unit)? = null, sluit: (Asset?) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val huidig = staat.bron.op(staat.index)
    BackHandler {
        when {
            staat.info -> staat.info = false
            staat.videoHud -> staat.videoHud = false
            else -> sluit(staat.bron.op(staat.index))
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    // Tijdens een diashow het scherm aan houden: anders start de systeem-screensaver (op o.a. de Google TV
    // Streamer niet uit te zetten) midden in de diashow. Gepauzeerd of gestopt geldt weer de gewone timeout.
    val view = LocalView.current
    DisposableEffect(staat.diashow) {
        view.keepScreenOn = staat.diashow
        onDispose { view.keepScreenOn = false }
    }

    fun ga(d: Int) {
        val stap = when {
            d == 1 && staat.paarVoor(staat.index) != null -> 2
            d == -1 && staat.index >= 2 && staat.paarVoor(staat.index - 2) != null -> -2
            else -> d
        }
        val n = (staat.index + stap).coerceIn(0, (staat.bron.aantal - 1).coerceAtLeast(0))
        if (n == staat.index) return
        // video meteen stoppen (buffers vrij) in plaats van pas na de overgang: minder piek naast de nieuwe preview
        if (huidig?.video == true) { staat.speler?.stop(); staat.speeltVideo = false }
        staat.videoHud = false
        staat.videoGepauzeerd = false
        staat.seekPreviewMs = -1L
        staat.seekDeltaSec = 0
        staat.seekRonde = 0
        staat.richting = d
        staat.index = n
        staat.actief = System.currentTimeMillis()
    }

    fun spoelStapMs(durMs: Long, herhaling: Int): Long {
        val basis = when {
            durMs in 1..59_999 -> 5_000L
            durMs >= 300_000 -> 15_000L
            else -> 10_000L
        }
        return if (herhaling >= 4) basis * 2 else basis
    }

    fun spoel(stapMs: Long) {
        val sp = staat.speler ?: return
        val dur = sp.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val basis = if (staat.seekPreviewMs >= 0L) staat.seekPreviewMs else sp.currentPosition
        val doel = (basis + stapMs).coerceIn(0L, dur)
        staat.seekPreviewMs = doel
        staat.seekDeltaSec = (if (staat.seekRonde > 0) staat.seekDeltaSec else 0) + (stapMs / 1000L).toInt()
        staat.seekRonde++
        staat.videoHud = true
        staat.actief = System.currentTimeMillis()
    }

    // Debounced seek zodat ingedrukt houden vloeiend over de tijdlijn glijdt zonder de decoder te overspoelen
    LaunchedEffect(staat.seekRonde) {
        if (staat.seekRonde == 0) return@LaunchedEffect
        delay(160)
        val doel = staat.seekPreviewMs
        if (doel >= 0L) staat.speler?.seekTo(doel)
        staat.seekPreviewMs = -1L
        delay(900)
        staat.seekDeltaSec = 0
        staat.seekRonde = 0
    }

    // Video-seekbalk verbergen na 4 s inactiviteit (blijft zichtbaar zolang video gepauzeerd is)
    LaunchedEffect(staat.videoHud, staat.actief, staat.videoGepauzeerd, staat.index) {
        if (staat.videoHud && !staat.videoGepauzeerd) {
            delay(4_000)
            staat.videoHud = false
        }
    }

    // Buren vooraf laden, zuinig: eerst de volgende, dan de vorige, en pas als je blijft nog eentje verder.
    // Niet tijdens een video (de speler heeft het geheugen nodig); previews ≈ 6 MB per stuk als hardware-bitmap.
    LaunchedEffect(staat.index, staat.speeltVideo, staat.diashow) {
        if (staat.speeltVideo) return@LaunchedEffect
        // willekeurige diashow: de buren zijn dan niet de volgende; die laadt de diashow-lus zelf vooraf
        if (staat.diashow && Instellingen.diaWillekeurig) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        fun laad(d: Int) { staat.bron.op(staat.index + d)?.takeIf { !it.video }?.let { loader.enqueue(previewVerzoek(ctx, it.id)) } }
        laad(1); laad(2)
        delay(300); laad(-1); laad(-2)
        delay(700); laad(3)
    }
    // diashow: volgende alvast kiezen en vooraf laden (ook bij willekeurig of overgeslagen video's), dan verder
    LaunchedEffect(staat.diashow, staat.index, staat.speeltVideo) {
        if (!staat.diashow || staat.speeltVideo) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        val doel = staat.diaDoel()
        staat.diaVolgende = doel
        if (doel >= 0) {
            val loader = SingletonImageLoader.get(ctx)
            // de maand van een willekeurige foto is misschien nog niet geladen (op() start dat): even later nog eens
            for (poging in 0 until 3) {
                val a = staat.bron.op(doel)
                if (a != null) { if (!a.video) loader.enqueue(previewVerzoek(ctx, a.id)); break }
                delay(500)
            }
        }
        delay((Instellingen.diaMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300))
        staat.diaVerder()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (huidig?.video == true && staat.videoHud && !staat.info && !staat.diashow) {
                        val sp = staat.speler
                        spoel(-spoelStapMs(sp?.duration ?: 0L, e.nativeKeyEvent.repeatCount))
                    } else {
                        staat.diashow = false; ga(-1)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (huidig?.video == true && staat.videoHud && !staat.info && !staat.diashow) {
                        val sp = staat.speler
                        spoel(spoelStapMs(sp?.duration ?: 0L, e.nativeKeyEvent.repeatCount))
                    } else {
                        staat.diashow = false; ga(1)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (huidig?.video == true && !staat.info && !staat.diashow) {
                        staat.videoHud = !staat.videoHud
                        staat.actief = System.currentTimeMillis()
                    } else {
                        staat.info = !staat.info; staat.actief = System.currentTimeMillis()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> {
                    if (k == KeyEvent.KEYCODE_DPAD_UP && huidig?.video == true && staat.videoHud && !staat.info) {
                        staat.videoHud = false
                    } else {
                        staat.info = !staat.info
                        if (staat.info) staat.videoHud = false
                    }
                    staat.actief = System.currentTimeMillis(); true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val sp = staat.speler
                    // infopaneel open bij een foto met plek: OK springt naar de wereldkaart
                    if (staat.info && opKaart != null && huidig != null && huidig.lat != null) opKaart(huidig)
                    else if (huidig?.video == true && sp != null && !staat.diashow) {
                        val nieuw = !sp.playWhenReady
                        sp.playWhenReady = nieuw
                        staat.videoGepauzeerd = !nieuw
                        staat.videoHud = true
                    } else staat.diashow = !staat.diashow
                    staat.actief = System.currentTimeMillis(); true
                }
                // ⏩ / ⏪: in een video 10 s, anders 10 foto's
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (huidig?.video == true && staat.speler != null) spoel(10_000L) else ga(10); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (huidig?.video == true && staat.speler != null) spoel(-10_000L) else ga(-10); true }
                else -> false
            }
        },
    ) {
        val dicht = LocalDensity.current
        val breedtePx = with(dicht) { maxWidth.roundToPx() }
        val hoogtePx = with(dicht) { maxHeight.roundToPx() }
        val tussenPx = with(dicht) { 24.dp.roundToPx() }
        staat.breedtePx = breedtePx
        staat.hoogtePx = hoogtePx
        staat.tussenPx = tussenPx

        // Bewust geen fadeIn/fadeOut hier: die zetten een schermvullende offscreen-laag (8 MB) per pagina op en HWUI houdt
        // die lagen in zijn cache. De crossfade zit in Beeld (ModulateAlpha = alpha per tekenopdracht, zonder laag);
        // de slide/zoom bepaalt alleen hoe lang de oude pagina nog bestaat.
        AnimatedContent(
            targetState = staat.index,
            transitionSpec = {
                when (staat.richting) {
                    // diashow: de nieuwe foto vloeit erin en 'landt' met een kleine zoom
                    0 -> scaleIn(tween(1400), initialScale = 1.03f) togetherWith scaleOut(tween(900), targetScale = 1f)
                    else -> slideInHorizontally(tween(260)) { it / 6 * staat.richting.coerceIn(-1, 1) } togetherWith
                        slideOutHorizontally(tween(260)) { -it / 6 * staat.richting.coerceIn(-1, 1) }
                }
            },
            label = "foto",
        ) { i ->
            val a = staat.bron.op(i)
            val b = staat.paarVoor(i)
            val zicht by transition.animateFloat(transitionSpec = { tween(if (staat.richting == 0) 900 else 200) }, label = "zicht") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            if (a != null) Beeld(a, b, staat, actief = i == staat.index, zicht = { zicht })
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht) }
        }

        // Landkaart (zoals in een fotolijst-screensaver): alleen bij foto's met GPS, niet bij video of info. De laag zelf wacht even
        // bij een nieuw land en laat bij hetzelfde land alleen de stip glijden. Instelling: uit / klein / groot.
        // "alleen de foto" tijdens een lopende diashow: geen kaart, balk, badge of ringetje (gepauzeerd komt alles terug)
        val schoon = staat.diashow && Instellingen.diaBeeld == DiaBeeld.ALLEEN_FOTO
        val kaartMaat = Instellingen.viewerKaart.schaal
        KaartLaag(
            doel = huidig?.takeIf { kaartMaat > 0f && !it.video && it.lat != null && it.lon != null && !staat.info && !schoon },
            breedtePx = breedtePx, hoogtePx = hoogtePx, maat = kaartMaat,
        )

        // balk onderin: datum en plaats, uit te zetten of verdwijnt vanzelf (instelbaar, 0 = altijd)
        var balk by remember { mutableStateOf(Instellingen.toonBalk) }
        LaunchedEffect(staat.actief, staat.index, Instellingen.toonBalk) {
            if (!Instellingen.toonBalk) { balk = false; return@LaunchedEffect }
            balk = true; val ms = Instellingen.balkMs; if (ms > 0) { delay(ms.toLong()); balk = false }
        }
        AnimatedVisibility(
            ((balk && !schoon) || staat.info) && huidig != null && !staat.speeltVideo && !staat.videoHud, Modifier.align(Alignment.BottomStart),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (huidig != null) OnderBalk(huidig, staat, metKaart = huidig.lat != null && !huidig.video && kaartMaat > 0f)
        }
        AnimatedVisibility(
            huidig?.video == true && staat.videoHud && !staat.info && !staat.diashow,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 },
        ) {
            VideoTransportHud(staat)
        }
        AnimatedVisibility(
            staat.info && huidig != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            if (huidig != null) InfoPaneel(huidig, opKaart != null && huidig.lat != null)
        }
        // Diashow rechtsboven: een badge (play-icoon + "Diashow") bij het starten, en kort "Diashow gepauzeerd" bij het
        // stoppen (OK, of handmatig bladeren); niet bij elke automatische wissel. Hij vervaagt na een paar seconden.
        // Het voortgangsringetje houdt zijn vaste plek: de badge vervaagt alleen (alpha) en houdt zijn ruimte.
        var badge by remember { mutableStateOf<Boolean?>(null) }
        var badgeRonde by remember { mutableIntStateOf(0) }
        var eersteKeer by remember { mutableStateOf(true) }
        LaunchedEffect(staat.diashow) {
            // viewer geopend zonder diashow: geen "gepauzeerd"
            if (eersteKeer) { eersteKeer = false; if (!staat.diashow) return@LaunchedEffect }
            // geen "▶ Diashow" bij alleen de foto; "gepauzeerd" wel (dat is een reactie op je eigen toets)
            badge = staat.diashow.takeIf { !(it && Instellingen.diaBeeld == DiaBeeld.ALLEEN_FOTO) }; badgeRonde++
        }
        LaunchedEffect(badgeRonde) { if (badge != null) { delay(if (badge == true) 5000 else 3000); badge = null } }
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = badge
            val badgeAlpha by animateFloatAsState(if (b != null) 1f else 0f, tween(450), label = "badge")
            var laatste by remember { mutableStateOf(true) } // laatste inhoud, zodat hij met die tekst uitfadet
            if (b != null) laatste = b
            Row(
                Modifier.padding(end = 10.dp).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(start = 9.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (laatste) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (laatste) R.string.diashow else R.string.diashow_pauze), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
            AnimatedVisibility(staat.diashow && Instellingen.diaBeeld == DiaBeeld.ALLES, enter = fadeIn(), exit = fadeOut()) { DiaRing(staat) }
        }
    }
}

/** Ringetje dat in de diashow-tijd volloopt en bij elke foto opnieuw begint; staat stil zolang een video speelt. */
@Composable
private fun DiaRing(staat: KijkerStaat) {
    val deel = remember { Animatable(0f) }
    LaunchedEffect(staat.index, staat.speeltVideo) {
        deel.snapTo(0f)
        if (!staat.speeltVideo) deel.animateTo(1f, tween(Instellingen.diaMs.toInt(), easing = LinearEasing))
    }
    VoortgangRing({ deel.value })
}

@Composable
private fun Beeld(a: Asset, b: Asset?, staat: KijkerStaat, actief: Boolean, zicht: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = zicht(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        // diashow: heel langzaam inzoomen (Ken Burns, instelbaar), anders stil
        // Alleen op de diashow-schakelaar: de vertrekkende pagina houdt zijn zoom (anders springt hij terug tijdens de
        // crossfade), en na het stoppen glijdt de foto terug naar 1,0 in plaats van te springen.
        val zoom = remember { Animatable(1f) }
        LaunchedEffect(staat.diashow) {
            if (staat.diashow && Instellingen.kenBurns) { if (actief) zoom.animateTo(1.06f, tween((Instellingen.diaMs + 1500).toInt(), easing = LinearEasing)) }
            else if (zoom.value != 1f) zoom.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
        // 1) thumbhash 2) thumbnail (al in de cache van het raster) 3) preview: er staat dus altijd meteen iets
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null && Instellingen.wazig) {
            // achtergrond i.p.v. zwarte balken: de thumbhash (32 px) schermvullend opgerekt = gratis 'blur', verdonkerd
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            if (b == null) {
                val vorm = Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true)
                Image(hash, null, vorm, contentScale = ContentScale.FillBounds)
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
                        AsyncImage(beeld(Immich.thumb(item.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        AsyncImage(preview(item.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
        } else {
            val mod = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }
            if (!a.video || !staat.speeltVideo) AsyncImage(beeld(Immich.thumb(a.id)), null, mod, contentScale = ContentScale.Fit)
            if (!a.video) AsyncImage(preview(a.id), null, mod, contentScale = ContentScale.Fit)
            if (a.video && actief) Video(a, staat)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(a: Asset, staat: KijkerStaat) {
    val ctx = LocalContext.current
    val speler = remember {
        // Ruimere buffer voor 4K/hoge-bitrate video's en HLS-segmenten; voorkomt stotteren op snelle TV-boxen.
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
                out.add(object : MediaCodecVideoRenderer(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    true, eventHandler, eventListener, 50) {
                    override fun codecNeedsSetOutputSurfaceWorkaround(name: String) =
                        videoZonderPlaceholder || super.codecNeedsSetOutputSurfaceWorkaround(name)

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
                        // Amlogic-decoders weigeren soms fps > 60 (bijv. 60.000004 of 240fps slow-mo)
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
    // Op Android 10+ (API 29+) ondersteunt SurfaceView rotatie in de hardware-overlay (HWC), én biedt hij directe
    // 10-bit HDR (yuv420p10le HLG) en 4K zonder GPU-kopie via TextureView (die op Amlogic terugvalt naar ~10 fps).
    var verhouding by remember { mutableFloatStateOf(0f) }
    val gebruikTexture = remember(a.id) { Build.VERSION.SDK_INT < 29 && a.ratio < 1f }
    DisposableEffect(a.id) {
        staat.videoFout = false
        verhouding = 0f
        speler.setMediaItem(MediaItem.fromUri(Immich.video(a.id)))
        speler.prepare()
        speler.playWhenReady = true
        val luister = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { staat.speeltVideo = isPlaying }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) verhouding = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onPlaybackStateChanged(s: Int) {
                // diashow: na de video door naar de volgende
                if (s == Player.STATE_ENDED) { staat.speeltVideo = false; if (staat.diashow) staat.diaVerder() }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Fotos", "video ${a.id}", error)
                staat.speeltVideo = false; staat.videoFout = true // diashow loopt dan na de diashow-tijd gewoon door
            }
        }
        speler.addListener(luister)
        staat.speler = speler
        // vrijgeven zodra deze pagina weg is; bij video→video heeft de nieuwe pagina staat.speler dan al overgenomen
        onDispose {
            speler.removeListener(luister); speler.stop(); speler.release()
            if (staat.speler === speler) { staat.speler = null; staat.speeltVideo = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val r = if (verhouding > 0f) verhouding else a.ratio
        AndroidView(
            factory = { c ->
                if (gebruikTexture) TextureView(c).also { speler.setVideoTextureView(it) }
                else SurfaceView(c).also { speler.setVideoSurfaceView(it) }
            },
            onRelease = { v ->
                if (v is TextureView) speler.clearVideoTextureView(v)
                else if (v is SurfaceView) speler.clearVideoSurfaceView(v)
            },
            // past binnen het scherm, staand of liggend (aspectRatio probeert eerst de hoogte, anders de breedte)
            modifier = Modifier.align(Alignment.Center).aspectRatio(r.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true),
        )
        if (staat.videoFout) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VideocamOff, null, Modifier.size(44.dp), tint = Kleur.zacht)
            Text(stringResource(R.string.video_fout), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 8.dp))
        }
        if (!staat.videoHud) VideoVoortgang(speler, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun VideoVoortgang(speler: ExoPlayer, modifier: Modifier = Modifier) {
    val deel by produceState(0f, speler) {
        while (true) { val d = speler.duration; value = if (d > 0) (speler.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f; delay(250) }
    }
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x28FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(deel).background(Kleur.primair))
    }
}

private fun formatTijdMs(ms: Long): String {
    val tot = (ms.coerceAtLeast(0L) / 1000L)
    val u = tot / 3600L
    val m = (tot % 3600L) / 60L
    val s = tot % 60L
    return if (u > 0) "%d:%02d:%02d".format(u, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun VideoTransportHud(staat: KijkerStaat) {
    val sp = staat.speler
    val pos by produceState(Triple(0L, 0L, 0L), sp, staat.seekPreviewMs, staat.videoHud) {
        while (true) {
            val dur = sp?.duration?.takeIf { it > 0 } ?: 0L
            val cur = if (staat.seekPreviewMs >= 0L) staat.seekPreviewMs else (sp?.currentPosition ?: 0L)
            val buf = sp?.bufferedPosition ?: 0L
            value = Triple(cur.coerceIn(0L, dur.coerceAtLeast(1L)), dur, buf.coerceIn(0L, dur.coerceAtLeast(1L)))
            delay(150)
        }
    }
    val curMs = pos.first
    val durMs = pos.second
    val bufMs = pos.third
    val deel = if (durMs > 0L) (curMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val bufDeel = if (durMs > 0L) (bufMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

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
                        if (staat.videoGepauzeerd) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, Modifier.size(18.dp), tint = Kleur.primair,
                    )
                    if (staat.seekDeltaSec != 0) {
                        val delta = staat.seekDeltaSec
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
                            "${formatTijdMs(curMs)} / ${if (durMs > 0) formatTijdMs(durMs) else "--:--"}",
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
                        Icon(if (staat.videoGepauzeerd) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(15.dp), tint = Color(0xCCFFFFFF))
                    }
                    Text("·", color = Color(0x80FFFFFF))
                    Text("▲", style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF))
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(formatTijdMs(curMs), style = MaterialTheme.typography.labelLarge, color = Color.White)
                BoxWithConstraints(Modifier.weight(1f).height(20.dp), contentAlignment = Alignment.CenterStart) {
                    val spoorBreedte = maxWidth
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(bufDeel).background(Color(0x66FFFFFF)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth(deel).background(Kleur.primair))
                    }
                    Box(
                        Modifier.offset(x = (spoorBreedte * deel - 8.dp).coerceIn(0.dp, (spoorBreedte - 16.dp).coerceAtLeast(0.dp)))
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.5.dp, Kleur.primair, CircleShape),
                    )
                }
                Text(if (durMs > 0) formatTijdMs(durMs) else "--:--", style = MaterialTheme.typography.labelLarge, color = Color(0xCCFFFFFF))
            }
        }
    }
}

@Composable
private fun OnderBalk(a: Asset, staat: KijkerStaat, metKaart: Boolean) {
    Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp)) {
            // teller bij de titel, links: rechtsonder staat de landkaart (die liep over de teller heen)
            Row(verticalAlignment = Alignment.Bottom) {
                staat.titel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Kleur.primair, modifier = Modifier.padding(end = 12.dp)) }
                if (Instellingen.teller) Text("${staat.index + 1} / ${Taal.getal(staat.bron.aantal)}", style = MaterialTheme.typography.labelMedium, color = Color(0xAAFFFFFF), modifier = Modifier.padding(bottom = 1.dp))
            }
            Text(datumLang(a.datum), style = MaterialTheme.typography.titleLarge, color = Color.White)
            val plaats = listOfNotNull(a.stad, a.land).joinToString(", ")
            // met kaart (foto met GPS) staat de plaats zo bij de stip
            if (plaats.isNotEmpty() && !metKaart) Text(plaats, style = MaterialTheme.typography.bodyLarge, color = Color(0xDDFFFFFF))
            // album(s) van de foto; het album dat je nu bekijkt niet nog eens
            if (Instellingen.albumNaam) {
                val namen = albumNamen(a.id).filter { it != staat.titel }
                if (namen.isNotEmpty()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp), tint = Color(0xBBFFFFFF))
                    Text("  " + albumRegel(namen), style = MaterialTheme.typography.bodyMedium, color = Color(0xBBFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InfoPaneel(a: Asset, metKaartSprong: Boolean) {
    val details by produceState<Details?>(null, a.id) { value = runCatching { Immich.details(a.id) }.getOrNull() }
    Column(Modifier.fillMaxHeight().width(380.dp).background(Color(0xF0121214)).padding(28.dp)) {
        // De hintregel staat vast onderaan; de inhoud erboven mag langer zijn dan het scherm (veel personen, lange
        // omschrijving) en scrolt dan weg, in plaats van de hint uit beeld te duwen. Lange lijsten worden afgekapt.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.info), style = MaterialTheme.typography.headlineSmall)
            InfoRegel(Icons.Filled.Schedule, datumLang(a.datum), null)
            val d = details
            if (d != null) {
                d.omschrijving?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                if (d.mensen.isNotEmpty()) InfoRegel(Icons.Filled.People, d.mensen.joinToString(", "), null, maxRegels = 3)
                val albums = albumNamen(a.id)
                if (albums.isNotEmpty()) InfoRegel(Icons.Filled.PhotoLibrary, albums.joinToString(", "), null, maxRegels = 3)
                d.plaats?.let { InfoRegel(Icons.Filled.Place, it, null) }
                if (a.lat != null && a.lon != null) {
                    KaartKlein(a.lat, a.lon, Modifier.fillMaxWidth().height(150.dp).background(Kleur.vlak, RoundedCornerShape(12.dp)))
                }
                InfoRegel(Icons.Filled.Image, d.bestand, d.afmeting)
                d.camera?.let { InfoRegel(Icons.Filled.CameraAlt, it, listOfNotNull(d.lens, d.instellingen).joinToString("\n").ifEmpty { null }) }
            } else {
                Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = Kleur.zacht)
            Text("  " + stringResource(if (metKaartSprong) R.string.hint_info_kaart else R.string.hint_info), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRegel(icoon: ImageVector, titel: String, sub: String?, maxRegels: Int = Int.MAX_VALUE) {
    Row {
        Icon(icoon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = Kleur.zacht)
        Column(Modifier.padding(start = 14.dp)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge, maxLines = maxRegels, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Albums waar deze foto in zit (leeg zolang ze laden of als er geen zijn). */
@Composable
fun albumNamen(id: String): List<String> =
    produceState(emptyList(), id) { delay(150); value = Immich.albumsVan(id) }.value

/** "Vakantie 2023", of "Vakantie 2023 + 2" bij meer albums. */
fun albumRegel(namen: List<String>) = if (namen.size <= 1) namen.firstOrNull().orEmpty() else namen[0] + "  + " + (namen.size - 1)
