package com.immichclient.tv

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay

/**
 * Единый фоновый плеер потокового радио на уровне всего приложения:
 * исключает наложение потоков между ручным просмотром и слайд-шоу,
 * плавно затухает и возвращается за 2.5 с при воспроизведении видео.
 */
@OptIn(UnstableApi::class)
@Composable
fun MuziekSpeler(url: String, gedempt: Boolean) {
    val ctx = LocalContext.current
    val speler = remember(url) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("FotosTV/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .build()
    }
    var foutTeller by remember(url) { mutableIntStateOf(0) }
    val fade by animateFloatAsState(
        targetValue = if (gedempt) 0f else 1f,
        animationSpec = tween(durationMillis = 2500, easing = LinearEasing),
        label = "muziekFade",
    )
    // Квадратичная кривая (audio taper) даёт субъективно ровное затухание и нарастание громкости
    LaunchedEffect(fade, speler) { speler.volume = fade * fade }
    LaunchedEffect(foutTeller, url) {
        if (foutTeller in 1..5) {
            delay(5000)
            speler.setMediaItem(MediaItem.fromUri(url))
            speler.prepare()
            speler.playWhenReady = true
        }
    }
    DisposableEffect(speler, url) {
        val luister = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Fotos", "muziek $url: ${error.errorCodeName}", error)
                foutTeller++
            }
        }
        speler.addListener(luister)
        speler.volume = fade * fade
        speler.setMediaItem(MediaItem.fromUri(url))
        speler.prepare()
        speler.playWhenReady = true
        onDispose {
            speler.removeListener(luister)
            speler.stop()
            speler.release()
        }
    }
}
