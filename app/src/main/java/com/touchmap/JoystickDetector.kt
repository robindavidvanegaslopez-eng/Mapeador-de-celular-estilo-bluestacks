package com.touchmap

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detecta el anillo exterior del joystick del juego en una captura de pantalla.
 * Parte de una zona aproximada (centro y radio puestos por el usuario), busca el círculo cuyo
 * borde tenga el gradiente más alineado con el radio y lo afina por pasos de hasta 0,125 px.
 */
object JoystickDetector {

    class Result(val cx: Float, val cy: Float, val r: Float, val score: Float, val peak: Float)

    private const val TWO_PI = 6.2831855f

    fun detect(bmp: Bitmap, cx0: Float, cy0: Float, r0: Float): Result? {
        val half = (r0 * 2.4f).toInt().coerceAtLeast(60)
        val x0 = (cx0 - half).toInt().coerceAtLeast(0)
        val y0 = (cy0 - half).toInt().coerceAtLeast(0)
        val x1 = (cx0 + half).toInt().coerceAtMost(bmp.width)
        val y1 = (cy0 + half).toInt().coerceAtMost(bmp.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 48 || h < 48) return null

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
        }
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val a = lum[i - w - 1]
                val b = lum[i - w]
                val c = lum[i - w + 1]
                val d = lum[i - 1]
                val f = lum[i + 1]
                val g = lum[i + w - 1]
                val hh = lum[i + w]
                val k = lum[i + w + 1]
                val sx = (c + 2f * f + k) - (a + 2f * d + g)
                val sy = (g + 2f * hh + k) - (a + 2f * b + c)
                gx[i] = sx
                gy[i] = sy
                mag[i] = sqrt(sx * sx + sy * sy)
            }
        }
        val sorted = mag.copyOf()
        sorted.sort()
        val cap = max(sorted[(sorted.size * 0.97f).toInt().coerceAtMost(sorted.size - 1)], 8f)

        val lcx = cx0 - x0
        val lcy = cy0 - y0

        // ---- Búsqueda gruesa ----
        val nCoarse = 48
        val csC = FloatArray(nCoarse) { cos(TWO_PI * it / nCoarse) }
        val snC = FloatArray(nCoarse) { sin(TWO_PI * it / nCoarse) }
        val step = max(2f, r0 / 22f)
        val rMin = r0 * 0.45f
        val rMax = r0 * 1.7f
        val nr = ((rMax - rMin) / step).toInt() + 1
        val bestScore = FloatArray(nr)
        val bestX = FloatArray(nr)
        val bestY = FloatArray(nr)
        var sum = 0f
        var cnt = 0
        var dy = -r0 * 0.8f
        while (dy <= r0 * 0.8f) {
            var dx = -r0 * 0.8f
            while (dx <= r0 * 0.8f) {
                for (ri in 0 until nr) {
                    val r = rMin + ri * step
                    val s = ringScore(gx, gy, w, h, lcx + dx, lcy + dy, r, nCoarse, csC, snC, cap)
                    sum += s
                    cnt++
                    if (s > bestScore[ri]) {
                        bestScore[ri] = s
                        bestX[ri] = lcx + dx
                        bestY[ri] = lcy + dy
                    }
                }
                dx += step
            }
            dy += step
        }
        var gmax = 0f
        for (ri in 0 until nr) gmax = max(gmax, bestScore[ri])
        if (gmax <= 0f) return null
        // Se prefiere el anillo más externo que sea casi tan marcado como el mejor.
        var pick = 0
        for (ri in 0 until nr) {
            if (bestScore[ri] >= gmax * 0.82f) pick = ri
        }
        var cx = bestX[pick]
        var cy = bestY[pick]
        var r = rMin + pick * step
        val mean = sum / max(cnt, 1)

        // ---- Afinado ----
        val nFine = 144
        val csF = FloatArray(nFine) { cos(TWO_PI * it / nFine) }
        val snF = FloatArray(nFine) { sin(TWO_PI * it / nFine) }
        var best = ringScore(gx, gy, w, h, cx, cy, r, nFine, csF, snF, cap)
        var st = max(2f, step)
        while (st >= 0.125f) {
            var improved = true
            var guard = 0
            while (improved && guard < 40) {
                improved = false
                guard++
                var bx = cx
                var by = cy
                var br = r
                for (ix in -1..1) for (iy in -1..1) for (ir in -1..1) {
                    if (ix == 0 && iy == 0 && ir == 0) continue
                    val nx = cx + ix * st
                    val ny = cy + iy * st
                    val nrr = r + ir * st
                    if (nrr < 6f) continue
                    val s = ringScore(gx, gy, w, h, nx, ny, nrr, nFine, csF, snF, cap)
                    if (s > best) {
                        best = s
                        bx = nx
                        by = ny
                        br = nrr
                        improved = true
                    }
                }
                cx = bx
                cy = by
                r = br
            }
            st /= 2f
        }
        return Result(cx + x0, cy + y0, r, best, if (mean > 0f) best / mean else 0f)
    }

    private fun ringScore(
        gx: FloatArray, gy: FloatArray, w: Int, h: Int,
        cx: Float, cy: Float, r: Float,
        n: Int, cs: FloatArray, sn: FloatArray, cap: Float
    ): Float {
        var s = 0f
        for (k in 0 until n) {
            val x = cx + r * cs[k]
            val y = cy + r * sn[k]
            if (x < 1f || y < 1f || x > w - 2f || y > h - 2f) continue
            val ix = x.toInt()
            val iy = y.toInt()
            val fx = x - ix
            val fy = y - iy
            val i = iy * w + ix
            val vx = bil(gx, i, w, fx, fy)
            val vy = bil(gy, i, w, fx, fy)
            val rad = abs(vx * cs[k] + vy * sn[k])
            s += min(rad, cap)
        }
        return s / (n * cap)
    }

    private fun bil(a: FloatArray, i: Int, w: Int, fx: Float, fy: Float): Float {
        val top = a[i] * (1f - fx) + a[i + 1] * fx
        val bot = a[i + w] * (1f - fx) + a[i + w + 1] * fx
        return top * (1f - fy) + bot * fy
    }
}
