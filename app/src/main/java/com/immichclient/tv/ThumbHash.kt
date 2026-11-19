package com.immichclient.tv

import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ThumbHash (github.com/evanw/thumbhash, MIT): ~25 bytes per photo supplied by Immich.
 * Decodes into a blurred ~32px preview so fast scrolling never shows empty tiles.
 */
object ThumbHash {
    private val cache = LruCache<String, ImageBitmap>(3000) // 32x32x4 B ~= 4 KB each

    fun bitmap(hash: String): ImageBitmap? {
        cache.get(hash)?.let { return it }
        val b = runCatching { decode(Base64.decode(hash, Base64.DEFAULT)) }.getOrNull() ?: return null
        cache.put(hash, b)
        return b
    }

    fun inCache(hash: String): ImageBitmap? = cache.get(hash)

    fun clear() = cache.evictAll()

    private fun decode(h: ByteArray): ImageBitmap {
        fun u(i: Int) = h[i].toInt() and 0xFF
        val header24 = u(0) or (u(1) shl 8) or (u(2) shl 16)
        val header16 = u(3) or (u(4) shl 8)
        val lDc = (header24 and 63) / 63f
        val pDc = ((header24 shr 6) and 63) / 31.5f - 1
        val qDc = ((header24 shr 12) and 63) / 31.5f - 1
        val lScale = ((header24 shr 18) and 31) / 31f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63) / 63f
        val qScale = ((header16 shr 9) and 63) / 63f
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (u(5) and 15) / 15f else 1f
        val aScale = (u(5) shr 4) / 15f

        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun channel(nx: Int, ny: Int, scale: Float): FloatArray {
            val ac = ArrayList<Float>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    ac.add((((u(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15) / 7.5f - 1) * scale)
                    acIndex++
                    cx++
                }
            }
            return ac.toFloatArray()
        }
        val lAc = channel(lx, ly, lScale)
        val pAc = channel(3, 3, pScale * 1.25f)
        val qAc = channel(3, 3, qScale * 1.25f)
        val aAc = if (hasAlpha) channel(5, 5, aScale) else null

        val ratio = lx.toFloat() / ly
        val w = (if (ratio > 1) 32f else 32 * ratio).roundToInt().coerceAtLeast(1)
        val hh = (if (ratio > 1) 32 / ratio else 32f).roundToInt().coerceAtLeast(1)
        val px = IntArray(w * hh)
        val nx = max(lx, if (hasAlpha) 5 else 3)
        val ny = max(ly, if (hasAlpha) 5 else 3)
        val fxTab = FloatArray(w * nx) { idx ->
            val x = idx / nx
            val cx = idx % nx
            cos(PI / w * (x + 0.5) * cx).toFloat()
        }
        val fyTab = FloatArray(hh * ny) { idx ->
            val y = idx / ny
            val cy = idx % ny
            cos(PI / hh * (y + 0.5) * cy).toFloat()
        }
        for (y in 0 until hh) {
            val yOff = y * ny
            for (x in 0 until w) {
                val xOff = x * nx
                var l = lDc; var p = pDc; var q = qDc; var a = aDc
                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fyTab[yOff + cy] * 2
                    while (cx * ly < lx * (ly - cy)) { l += lAc[j++] * fxTab[xOff + cx] * fy2; cx++ }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fyTab[yOff + cy] * 2
                    while (cx < 3 - cy) { val f = fxTab[xOff + cx] * fy2; p += pAc[j] * f; q += qAc[j] * f; j++; cx++ }
                }
                if (aAc != null) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy > 0) 0 else 1
                        val fy2 = fyTab[yOff + cy] * 2
                        while (cx < 5 - cy) { a += aAc[j++] * fxTab[xOff + cx] * fy2; cx++ }
                    }
                }
                val b = l - 2f / 3f * p
                val r = (3 * l - b + q) / 2
                val g = r - q
                fun clampByte(v: Float) = (255 * min(1f, max(0f, v))).toInt()
                px[y * w + x] = (clampByte(a) shl 24) or (clampByte(r) shl 16) or (clampByte(g) shl 8) or clampByte(b)
            }
        }
        return Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
