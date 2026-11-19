package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PORTIE = 120

/**
 * Случайный поток всех фото и видео: порции из /search/random без дат и группировок,
 * с дозагрузкой при скролле или просмотре и кнопкой полного перемешивания.
 */
class WillekeurigStaat(private val scope: CoroutineScope) : BeeldBron {
    var assets by mutableStateOf<List<Asset>>(emptyList())
        private set
    var bezig by mutableStateOf(false)
        private set
    var fout by mutableStateOf<String?>(null)
        private set
    var klaar by mutableStateOf(false)
        private set
    val raster = RasterStaat()

    private val gezien = HashSet<String>()
    private var job: Job? = null
    private var ronde = 0

    init { schud(scrollTop = false) }

    fun schud(scrollTop: Boolean = true) {
        job?.cancel()
        val r = ++ronde
        klaar = false
        fout = null
        bezig = true
        if (scrollTop) {
            raster.laatsteFocus = null
            scope.launch { raster.lijst.scrollToItem(0) }
        }
        job = scope.launch {
            runCatching { Immich.willekeurig(PORTIE, alleenFotos = false) }
                .onSuccess { l ->
                    if (r != ronde) return@onSuccess
                    withContext(Dispatchers.Default) { l.take(40).forEach { it.thumbhash?.let(ThumbHash::bitmap) } }
                    gezien.clear()
                    val uniek = l.filter { gezien.add(it.id) }
                    assets = uniek
                    if (l.size < PORTIE || uniek.isEmpty()) klaar = true
                }
                .onFailure { if (r == ronde) { fout = it.message; Log.w("Fotos", "willekeurig", it) } }
            if (r == ronde) bezig = false
        }
    }

    fun meer() {
        if (klaar || bezig || job?.isActive == true) return
        val r = ronde
        job = scope.launch {
            bezig = true
            runCatching { Immich.willekeurig(PORTIE, alleenFotos = false) }
                .onSuccess { l ->
                    if (r != ronde) return@onSuccess
                    val nieuw = l.filter { gezien.add(it.id) }
                    if (nieuw.isNotEmpty()) {
                        withContext(Dispatchers.Default) { nieuw.take(40).forEach { it.thumbhash?.let(ThumbHash::bitmap) } }
                        assets = assets + nieuw
                    }
                    if (nieuw.isEmpty() || l.size < PORTIE) klaar = true
                }
                .onFailure { if (r == ronde) { fout = it.message; Log.w("Fotos", "willekeurig meer", it) } }
            if (r == ronde) bezig = false
        }
    }

    override val aantal get() = assets.size
    override fun op(i: Int): Asset? {
        if (!klaar && !bezig && job?.isActive != true && i >= assets.size - 15) {
            scope.launch { meer() }
        }
        return assets.getOrNull(i)
    }
}

@Composable
fun WillekeurigScherm(staat: WillekeurigStaat, nav: Nav) {
    val assets = staat.assets
    val titel = stringResource(R.string.sectie_willekeurig)
    fun bekijk(i: Int, dia: Boolean) {
        if (assets.isEmpty()) return
        nav.bekijk(staat, i.coerceIn(0, assets.lastIndex), titel, dia, staat.raster)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        val rijHoogte = Instellingen.rijMaat.hoogte
        val rijen = remember(assets, breedte, rijHoogte) { rijenVoor(assets, breedte, perDag = false, hoogte = rijHoogte) }

        LaunchedEffect(rijen, staat.bezig, staat.klaar) {
            snapshotFlow { staat.raster.lijst.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rijen.isNotEmpty()) staat.meer() }
        }
        LaunchedEffect(assets.isNotEmpty()) {
            if (assets.isNotEmpty() && staat.raster.laatsteFocus == null) {
                staat.raster.focusDoel = assets.first().id
            }
        }

        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val a = staat.raster.laatsteFocus ?: assets.firstOrNull()
                        val i = a?.let { assets.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                        bekijk(i, true)
                        assets.isNotEmpty()
                    }
                    else -> false
                }
            },
        ) {
            FotoRaster(
                rijen = rijen,
                staat = staat.raster,
                onKlik = { a -> val i = assets.indexOf(a); if (i >= 0) bekijk(i, false) },
                bovenAantal = 1,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "kop") {
                    val sub = when {
                        assets.isNotEmpty() -> pluralStringResource(R.plurals.items, assets.size, assets.size) + (if (!staat.klaar) "+" else "")
                        staat.bezig -> stringResource(R.string.laden)
                        else -> null
                    }
                    SchermKop(titel, sub) {
                        Keuze(
                            selected = false, onClick = { staat.schud() },
                            leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.width(18.dp)) },
                            tekst = stringResource(R.string.schudden),
                        )
                        if (assets.isNotEmpty()) Keuze(
                            selected = false, onClick = { bekijk(0, true) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                            tekst = stringResource(R.string.diashow),
                        )
                    }
                    if (staat.fout != null && assets.isEmpty()) {
                        Text(stringResource(R.string.laden_mislukt, staat.fout.orEmpty()), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 12.dp))
                    } else if (!staat.bezig && assets.isEmpty() && staat.fout == null) {
                        Text(stringResource(R.string.willekeurig_leeg), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            LijstBalk(staat.raster, rijen, assets.size, staat.bezig || !staat.klaar, Modifier.align(Alignment.CenterEnd))
        }
    }
}
