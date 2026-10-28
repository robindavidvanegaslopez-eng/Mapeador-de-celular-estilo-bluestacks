package com.touchmap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Motor de gestos multitoque. Mantiene varios "dedos" a la vez y los envía a Android como
 * trazos encadenados (willContinue), de modo que un joystick sostenido y varios toques
 * simultáneos convivan sin cancelarse entre sí. Todo corre en el hilo principal.
 */
class GestureEngine(private val svc: AccessibilityService) {

    private class Swipe(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val t0: Long, val dur: Long
    )

    private class P(val id: Int, x: Float, y: Float) {
        val sx = x
        val sy = y
        var lx = x
        var ly = y
        var tx = x
        var ty = y
        var stroke: GestureDescription.StrokeDescription? = null
        var ending = false
        var sentEnd = false
        var tapMs = 0L
        var swipe: Swipe? = null
        var jump = false
    }

    private val pts = LinkedHashMap<Int, P>()
    private var seq = 1
    private var inflight = false
    private var generation = 0
    private val h = Handler(Looper.getMainLooper())

    companion object {
        const val SEG = 55L
        const val JUMP = 8L
    }

    fun down(x: Float, y: Float): Int {
        val id = seq++
        pts[id] = P(id, x, y)
        pump()
        return id
    }

    /** jump = true: el dedo salta al punto nuevo en pocos ms (sin arrastre visible). */
    fun move(id: Int, x: Float, y: Float, jump: Boolean = false) {
        val p = pts[id] ?: return
        if (p.ending) return
        if (x != p.tx || y != p.ty) p.jump = jump
        p.tx = x
        p.ty = y
        pump()
    }

    fun up(id: Int) {
        val p = pts[id] ?: return
        p.ending = true
        pump()
    }

    fun tap(x: Float, y: Float, holdMs: Long) {
        val id = seq++
        val p = P(id, x, y)
        p.ending = true
        p.tapMs = holdMs.coerceIn(10L, 1500L)
        pts[id] = p
        pump()
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val id = seq++
        val p = P(id, x1, y1)
        p.swipe = Swipe(x1, y1, x2, y2, SystemClock.uptimeMillis(), durMs.coerceAtLeast(60L))
        pts[id] = p
        pump()
    }

    /** Suelta todos los dedos que estén apoyados y descarta los que aún no empezaron. */
    fun releaseAll() {
        val list = ArrayList(pts.values)
        for (p in list) {
            if (p.stroke == null && !p.sentEnd) {
                pts.remove(p.id)
            } else {
                p.ending = true
            }
        }
        pump()
    }

    private fun pump() {
        if (inflight || pts.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val builder = GestureDescription.Builder()
        val used = ArrayList<P>()
        val bad = ArrayList<Int>()
        val maxN = GestureDescription.getMaxStrokeCount()
        for (p in pts.values) {
            if (used.size >= maxN) break
            val sw = p.swipe
            if (sw != null) {
                val e = ((now - sw.t0 + SEG).toFloat() / sw.dur.toFloat()).coerceIn(0f, 1f)
                p.tx = sw.x1 + (sw.x2 - sw.x1) * e
                p.ty = sw.y1 + (sw.y2 - sw.y1) * e
                if (e >= 1f) p.ending = true
            }
            val path = Path()
            path.moveTo(p.lx, p.ly)
            if (p.tx != p.lx || p.ty != p.ly) path.lineTo(p.tx, p.ty)
            val first = p.stroke == null
            val moving = p.tx != p.lx || p.ty != p.ly
            val dur = if (first && p.ending && p.tapMs > 0L) p.tapMs
            else if (p.jump && moving && !first) JUMP else SEG
            p.jump = false
            val cont = !p.ending
            try {
                val prev = p.stroke
                val sd = if (prev == null) {
                    GestureDescription.StrokeDescription(path, 0L, dur, cont)
                } else {
                    prev.continueStroke(path, 0L, dur, cont)
                }
                builder.addStroke(sd)
                p.stroke = sd
                p.lx = p.tx
                p.ly = p.ty
                if (!cont) p.sentEnd = true
                used.add(p)
            } catch (ex: Exception) {
                bad.add(p.id)
            }
        }
        for (id in bad) pts.remove(id)
        if (used.isEmpty()) return

        val gen = ++generation
        inflight = true
        val ok = svc.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (gen == generation) finish(used, false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (gen == generation) finish(used, true)
            }
        }, h)
        if (!ok) {
            h.postDelayed({ if (gen == generation) finish(used, true) }, 40L)
        }
    }

    private fun finish(list: List<P>, cancelled: Boolean) {
        inflight = false
        for (p in list) {
            if (p.sentEnd) {
                pts.remove(p.id)
            } else if (cancelled) {
                // El sistema canceló el gesto: el dedo se levantó. Se reinicia desde su origen.
                p.stroke = null
                p.lx = p.sx
                p.ly = p.sy
            }
        }
        pump()
    }
}
