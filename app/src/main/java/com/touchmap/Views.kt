package com.touchmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object Ui {
    const val CARD = 0xF21B1F29.toInt()
    const val CARD2 = 0xFF283040.toInt()
    const val ACCENT = 0xFF6C8CFF.toInt()
    const val TEXT = 0xFFEAEFFF.toInt()
    const val MUTED = 0xFF9AA3B8.toInt()
    const val OK = 0xFF2ED3A1.toInt()
    const val WARN = 0xFFFFB84D.toInt()
    const val DANGER = 0xFFFF5C7A.toInt()

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rr(color: Int, radius: Float, strokeColor: Int = 0, strokeW: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.setColor(color)
        g.cornerRadius = radius
        if (strokeW > 0) g.setStroke(strokeW, strokeColor)
        return g
    }

    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    fun keyName(code: Int): String {
        if (code < 0) return "—"
        return when (code) {
            KeyEvent.KEYCODE_SPACE -> "ESP"
            KeyEvent.KEYCODE_DPAD_UP -> "↑"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            KeyEvent.KEYCODE_DPAD_LEFT -> "←"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            KeyEvent.KEYCODE_ENTER -> "ENT"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "SHF"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "CTL"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "ALT"
            KeyEvent.KEYCODE_TAB -> "TAB"
            else -> {
                val n = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
                if (n.length > 5) n.substring(0, 5) else n
            }
        }
    }

    fun pill(ctx: Context, text: String, bg: Int, fg: Int = TEXT, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(fg)
        t.textSize = 13f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        val ph = dpi(ctx, 12f)
        val pv = dpi(ctx, 8f)
        t.setPadding(ph, pv, ph, pv)
        t.background = rr(bg, dp(ctx, 18f))
        t.setOnClickListener { onClick() }
        return t
    }
}

/** Pinceles y funciones de dibujo compartidas por todas las vistas (hilo principal). */
object Draw {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun label(c: Canvas, s: String, x: Float, y: Float) {
        val yy = y - (text.descent() + text.ascent()) / 2f
        c.drawText(s, x, yy, text)
    }

    fun control(
        c: Canvas, ctl: Control, cx: Float, cy: Float, d: Float,
        pressed: Boolean, selected: Boolean, kx: Float, ky: Float, dens: Float
    ) {
        val r = d / 2f * (if (pressed) 0.93f else 1f)
        val col = Kind.color(ctl.kind)
        val op = ctl.opacity
        stroke.pathEffect = null
        if (ctl.kind == Kind.JOYSTICK) {
            fill.color = Ui.alpha(0xFF0E1118.toInt(), op * 0.75f)
            c.drawCircle(cx, cy, r, fill)
            stroke.strokeWidth = 2.5f * dens
            stroke.color = Ui.alpha(col, op)
            c.drawCircle(cx, cy, r - 1.25f * dens, stroke)
            stroke.strokeWidth = 1f * dens
            stroke.color = Ui.alpha(Color.WHITE, 0.22f * op)
            c.drawCircle(cx, cy, r * 0.55f, stroke)
            text.textSize = max(10f * dens, d * 0.1f)
            text.color = Ui.alpha(Color.WHITE, 0.9f)
            val off = r * 0.78f
            Draw.label(c, if (ctl.kUp >= 0) Ui.keyName(ctl.kUp) else "▲", cx, cy - off)
            Draw.label(c, if (ctl.kDown >= 0) Ui.keyName(ctl.kDown) else "▼", cx, cy + off)
            Draw.label(c, if (ctl.kLeft >= 0) Ui.keyName(ctl.kLeft) else "◀", cx - off, cy)
            Draw.label(c, if (ctl.kRight >= 0) Ui.keyName(ctl.kRight) else "▶", cx + off, cy)
            fill.color = Ui.alpha(col, 0.5f + 0.45f * op)
            c.drawCircle(cx + kx, cy + ky, r * 0.3f, fill)
            stroke.strokeWidth = 1.5f * dens
            stroke.color = Ui.alpha(Color.WHITE, 0.7f)
            c.drawCircle(cx + kx, cy + ky, r * 0.3f, stroke)
        } else {
            fill.color = Ui.alpha(col, if (pressed) 0.95f else op * 0.6f)
            c.drawCircle(cx, cy, r, fill)
            stroke.strokeWidth = 2f * dens
            stroke.color = Ui.alpha(Color.WHITE, 0.55f * op + 0.2f)
            c.drawCircle(cx, cy, r - dens, stroke)
            val s = if (ctl.key >= 0) Ui.keyName(ctl.key) else Kind.glyph(ctl.kind)
            text.color = Color.WHITE
            text.textSize = if (s.length > 2) d * 0.27f else d * 0.4f
            Draw.label(c, s, cx, cy)
            if (ctl.key >= 0) {
                text.textSize = d * 0.2f
                text.color = Ui.alpha(Color.WHITE, 0.8f)
                Draw.label(c, Kind.glyph(ctl.kind), cx, cy + r * 0.62f)
            }
        }
        if (selected) {
            stroke.strokeWidth = 2.5f * dens
            stroke.color = Color.WHITE
            stroke.pathEffect = DashPathEffect(floatArrayOf(9f * dens, 6f * dens), 0f)
            c.drawCircle(cx, cy, d / 2f + 5f * dens, stroke)
            stroke.pathEffect = null
        }
    }

    fun target(c: Canvas, ctl: Control, tx: Float, ty: Float, shortSide: Float, rad: Float, dens: Float, strong: Boolean) {
        val col = Kind.color(ctl.kind)
        val a = if (strong) 1f else 0.55f
        stroke.pathEffect = null
        stroke.strokeWidth = 2f * dens
        stroke.color = Ui.alpha(col, a)
        val rr = 15f * dens
        c.drawCircle(tx, ty, rr, stroke)
        c.drawLine(tx - rr - 5f * dens, ty, tx + rr + 5f * dens, ty, stroke)
        c.drawLine(tx, ty - rr - 5f * dens, tx, ty + rr + 5f * dens, stroke)
        fill.color = Ui.alpha(col, a)
        c.drawCircle(tx, ty, 3f * dens, fill)
        if (ctl.kind == Kind.JOYSTICK) {
            stroke.strokeWidth = 1.8f * dens
            stroke.pathEffect = DashPathEffect(floatArrayOf(10f * dens, 7f * dens), 0f)
            c.drawCircle(tx, ty, rad * shortSide, stroke)
            stroke.pathEffect = null
        }
    }
}

class ControlView(
    ctx: Context,
    private val ctl: Control,
    private val diameter: Float,
    private val svc: MapperService
) : View(ctx) {

    private var pressed = false
    private var kx = 0f
    private var ky = 0f
    private val dens = ctx.resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = diameter.toInt() + 2
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val m = width / 2f
        Draw.control(canvas, ctl, m, m, diameter, pressed, false, kx, ky, dens)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = width / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN && Store.haptics) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                if (ctl.kind == Kind.JOYSTICK) {
                    val dx = e.x - m
                    val dy = e.y - m
                    val dist = hypot(dx, dy)
                    val maxD = diameter / 2f * 0.8f
                    pressed = true
                    if (dist < maxD * 0.12f) {
                        kx = 0f; ky = 0f
                        svc.joy(ctl, 0f, 0f, 0f)
                    } else {
                        val ux = dx / dist
                        val uy = dy / dist
                        val k = min(dist, maxD)
                        kx = ux * k
                        ky = uy * k
                        val mag = if (ctl.analogPad) min(dist / maxD, 1f) else 1f
                        svc.joy(ctl, ux, uy, mag)
                    }
                } else if (!pressed) {
                    pressed = true
                    svc.press(ctl)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                kx = 0f
                ky = 0f
                if (ctl.kind == Kind.JOYSTICK) svc.joy(ctl, 0f, 0f, 0f) else svc.release(ctl)
                invalidate()
            }
        }
        return true
    }
}

/** Capa de solo dibujo (no recibe toques): muestra dónde caerá cada toque. */
class TargetsView(ctx: Context, private val svc: MapperService) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val short = min(w, h)
        for (ctl in svc.controls()) {
            val g = ctl.geo(svc.isLand)
            Draw.target(canvas, ctl, g.tx * w, g.ty * h, short, g.rad, dens, false)
            if (ctl.kind == Kind.SWIPE) {
                Draw.stroke.color = Ui.alpha(Kind.color(ctl.kind), 0.55f)
                Draw.stroke.strokeWidth = 2f * dens
                canvas.drawLine(g.tx * w, g.ty * h, g.ex * w, g.ey * h, Draw.stroke)
                Draw.fill.color = Ui.alpha(Kind.color(ctl.kind), 0.55f)
                canvas.drawCircle(g.ex * w, g.ey * h, 6f * dens, Draw.fill)
            }
        }
    }
}

/** Capa del modo edición: arrastrar botones, objetivos, extremos de deslizamiento y radio del joystick. */
class EditView(ctx: Context, private val svc: MapperService) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density
    private var dragCtl: Control? = null
    private var part = 0 // 1 botón, 2 objetivo, 3 extremo, 4 radio
    private var grabDx = 0f
    private var grabDy = 0f
    private val gridPaint = Paint().apply {
        color = Ui.alpha(Color.WHITE, 0.07f)
        strokeWidth = 1f
    }

    fun diameter(ctl: Control, g: Geo, short: Float): Float = max(Ui.dp(context, 44f), g.size * short)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val short = min(w, h)
        canvas.drawColor(Ui.alpha(0xFF05060A.toInt(), 0.45f))
        val step = Ui.dp(context, 36f)
        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, gridPaint); x += step }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, gridPaint); y += step }

        val list = svc.controls()
        for (ctl in list) {
            val g = ctl.geo(svc.isLand)
            val sel = ctl.id == svc.selectedId
            val cx = g.cx * w
            val cy = g.cy * h
            val tx = g.tx * w
            val ty = g.ty * h
            val d = diameter(ctl, g, short)
            // línea botón -> objetivo
            Draw.stroke.pathEffect = DashPathEffect(floatArrayOf(8f * dens, 8f * dens), 0f)
            Draw.stroke.strokeWidth = 1.5f * dens
            Draw.stroke.color = Ui.alpha(Kind.color(ctl.kind), if (sel) 0.9f else 0.4f)
            canvas.drawLine(cx, cy, tx, ty, Draw.stroke)
            Draw.stroke.pathEffect = null
            if (ctl.kind == Kind.SWIPE) {
                Draw.stroke.strokeWidth = 3f * dens
                Draw.stroke.color = Ui.alpha(Kind.color(ctl.kind), 0.9f)
                canvas.drawLine(tx, ty, g.ex * w, g.ey * h, Draw.stroke)
                Draw.fill.color = Ui.alpha(Kind.color(ctl.kind), 1f)
                canvas.drawCircle(g.ex * w, g.ey * h, 9f * dens, Draw.fill)
                Draw.text.color = Color.WHITE
                Draw.text.textSize = 11f * dens
                Draw.label(canvas, "➜", g.ex * w, g.ey * h)
            }
            Draw.target(canvas, ctl, tx, ty, short, g.rad, dens, sel)
            if (ctl.kind == Kind.JOYSTICK && sel) {
                val hx = tx + g.rad * short
                Draw.fill.color = Color.WHITE
                canvas.drawCircle(hx, ty, 8f * dens, Draw.fill)
                Draw.fill.color = Kind.color(ctl.kind)
                canvas.drawCircle(hx, ty, 5f * dens, Draw.fill)
            }
            if (ctl.hidden) {
                Draw.stroke.pathEffect = DashPathEffect(floatArrayOf(6f * dens, 6f * dens), 0f)
                Draw.stroke.strokeWidth = 2f * dens
                Draw.stroke.color = Ui.alpha(Kind.color(ctl.kind), 0.9f)
                canvas.drawCircle(cx, cy, d / 2f, stroke())
                Draw.stroke.pathEffect = null
                Draw.text.color = Color.WHITE
                Draw.text.textSize = d * 0.3f
                Draw.label(canvas, "⌨", cx, cy)
                if (sel) {
                    Draw.stroke.color = Color.WHITE
                    canvas.drawCircle(cx, cy, d / 2f + 5f * dens, stroke())
                }
            } else {
                Draw.control(canvas, ctl, cx, cy, d, false, sel, 0f, 0f, dens)
            }
            // aviso si el botón tapa algún objetivo (el toque caería sobre el propio botón)
            if (!ctl.hidden) {
                for (o in list) {
                    val og = o.geo(svc.isLand)
                    if (hypot(og.tx * w - cx, og.ty * h - cy) < d / 2f + 14f * dens) {
                        Draw.stroke.strokeWidth = 3f * dens
                        Draw.stroke.color = Ui.DANGER
                        canvas.drawCircle(cx, cy, d / 2f + 9f * dens, stroke())
                        break
                    }
                }
            }
        }
    }

    private fun stroke(): Paint = Draw.stroke

    private fun hit(x: Float, y: Float): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val short = min(w, h)
        val list = svc.controls()
        for (i in list.indices.reversed()) {
            val ctl = list[i]
            val g = ctl.geo(svc.isLand)
            val tx = g.tx * w
            val ty = g.ty * h
            if (ctl.kind == Kind.JOYSTICK && ctl.id == svc.selectedId) {
                if (hypot(x - (tx + g.rad * short), y - ty) < Ui.dp(context, 22f)) {
                    dragCtl = ctl; part = 4; return true
                }
            }
            if (ctl.kind == Kind.SWIPE) {
                if (hypot(x - g.ex * w, y - g.ey * h) < Ui.dp(context, 24f)) {
                    dragCtl = ctl; part = 3; return true
                }
            }
            if (hypot(x - tx, y - ty) < Ui.dp(context, 24f)) {
                dragCtl = ctl; part = 2; return true
            }
            val d = diameter(ctl, g, short)
            if (hypot(x - g.cx * w, y - g.cy * h) < d / 2f + Ui.dp(context, 6f)) {
                dragCtl = ctl; part = 1
                grabDx = x - g.cx * w
                grabDy = y - g.cy * h
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val short = min(w, h)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hit(e.x, e.y)) {
                    svc.select(dragCtl!!.id)
                } else {
                    dragCtl = null
                    part = 0
                    svc.select(null)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val c = dragCtl ?: return true
                val g = c.geo(svc.isLand)
                when (part) {
                    1 -> {
                        g.cx = ((e.x - grabDx) / w).coerceIn(0.01f, 0.99f)
                        g.cy = ((e.y - grabDy) / h).coerceIn(0.01f, 0.99f)
                    }
                    2 -> {
                        g.tx = (e.x / w).coerceIn(0.0f, 1f)
                        g.ty = (e.y / h).coerceIn(0.0f, 1f)
                    }
                    3 -> {
                        g.ex = (e.x / w).coerceIn(0.0f, 1f)
                        g.ey = (e.y / h).coerceIn(0.0f, 1f)
                    }
                    4 -> {
                        g.rad = (hypot(e.x - g.tx * w, e.y - g.ty * h) / short).coerceIn(0.03f, 0.5f)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragCtl != null) Store.save()
                dragCtl = null
                part = 0
                svc.selectionMoved()
            }
        }
        return true
    }
}

/** Burbuja flotante: toque = abrir menú, arrastre = mover. */
class BubbleView(ctx: Context, private val svc: MapperService) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var t0 = 0L
    var status = 0 // 0 activo, 1 pausado por el usuario, 2 pausado por cuadro de texto

    fun size(): Int = (46f * dens).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size(), size())
    }

    override fun onDraw(c: Canvas) {
        val m = width / 2f
        Draw.fill.color = Ui.alpha(0xFF10131B.toInt(), 0.82f)
        c.drawCircle(m, m, m - dens, Draw.fill)
        val col = when (status) { 0 -> Ui.ACCENT; 1 -> Ui.WARN; else -> Ui.MUTED }
        Draw.stroke.pathEffect = null
        Draw.stroke.strokeWidth = 2.5f * dens
        Draw.stroke.color = col
        c.drawCircle(m, m, m - 3f * dens, Draw.stroke)
        Draw.stroke.strokeWidth = 2f * dens
        Draw.stroke.color = Ui.alpha(Color.WHITE, 0.9f)
        c.drawCircle(m, m, m * 0.42f, Draw.stroke)
        Draw.fill.color = Color.WHITE
        c.drawCircle(m, m, m * 0.14f, Draw.fill)
        Draw.fill.color = col
        c.drawCircle(m, m * 0.34f, 2.6f * dens, Draw.fill)
        c.drawCircle(m, m * 1.66f, 2.6f * dens, Draw.fill)
        c.drawCircle(m * 0.34f, m, 2.6f * dens, Draw.fill)
        c.drawCircle(m * 1.66f, m, 2.6f * dens, Draw.fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                lastX = e.rawX; lastY = e.rawY
                moved = false
                t0 = SystemClock.uptimeMillis()
                alpha = 1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.rawX - downX, e.rawY - downY) > 10f * dens) moved = true
                if (moved) {
                    svc.moveBubble(e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX
                    lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                alpha = 0.8f
                if (moved) svc.dropBubble() else svc.bubbleTapped()
            }
            MotionEvent.ACTION_CANCEL -> alpha = 0.8f
        }
        return true
    }
}

/** Grabador de macros: captura toques y deslizamientos con sus tiempos. */
class RecorderView(
    ctx: Context,
    private val sw: Int,
    private val sh: Int,
    private val onDone: (ArrayList<Step>?) -> Unit
) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density
    private val steps = ArrayList<Step>()
    private var lastEnd = 0L
    private var downT = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var curX = 0f
    private var curY = 0f
    private var down = false
    private val ripples = ArrayList<FloatArray>() // x, y, t0
    private val saveRect = RectF()
    private val cancelRect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        c.drawColor(Ui.alpha(0xFF05060A.toInt(), 0.35f))
        Draw.text.color = Color.WHITE
        Draw.text.textSize = 14f * dens
        Draw.label(c, "Grabando macro · toca o desliza como en el juego", w / 2f, 34f * dens)
        Draw.text.textSize = 12f * dens
        Draw.text.color = Ui.alpha(Color.WHITE, 0.75f)
        Draw.label(c, "${steps.size} pasos grabados", w / 2f, 54f * dens)
        val bw = 130f * dens
        val bh = 40f * dens
        val top = 68f * dens
        saveRect.set(w / 2f - bw - 6f * dens, top, w / 2f - 6f * dens, top + bh)
        cancelRect.set(w / 2f + 6f * dens, top, w / 2f + bw + 6f * dens, top + bh)
        Draw.fill.color = Ui.OK
        c.drawRoundRect(saveRect, 20f * dens, 20f * dens, Draw.fill)
        Draw.fill.color = Ui.DANGER
        c.drawRoundRect(cancelRect, 20f * dens, 20f * dens, Draw.fill)
        Draw.text.color = Color.BLACK
        Draw.text.textSize = 14f * dens
        Draw.label(c, "■ Guardar", saveRect.centerX(), saveRect.centerY())
        Draw.text.color = Color.WHITE
        Draw.label(c, "✕ Cancelar", cancelRect.centerX(), cancelRect.centerY())

        val now = SystemClock.uptimeMillis()
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val k = (now - r[2].toLong()) / 450f
            if (k >= 1f) { it.remove(); continue }
            Draw.stroke.pathEffect = null
            Draw.stroke.strokeWidth = 3f * dens
            Draw.stroke.color = Ui.alpha(Color.WHITE, 1f - k)
            c.drawCircle(r[0], r[1], (14f + 34f * k) * dens, Draw.stroke)
        }
        if (down) {
            Draw.stroke.color = Ui.alpha(Ui.ACCENT, 0.9f)
            Draw.stroke.strokeWidth = 3f * dens
            c.drawLine(x0, y0, curX, curY, Draw.stroke)
            Draw.fill.color = Ui.alpha(Ui.ACCENT, 0.9f)
            c.drawCircle(curX, curY, 12f * dens, Draw.fill)
        }
        if (ripples.isNotEmpty() || down) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (saveRect.contains(e.x, e.y)) { onDone(steps); return true }
                if (cancelRect.contains(e.x, e.y)) { onDone(null); return true }
                if (lastEnd != 0L && now - lastEnd > 40L) {
                    steps.add(Step(2, 0f, 0f, 0f, 0f, now - lastEnd))
                }
                down = true
                downT = now
                x0 = e.x; y0 = e.y; curX = e.x; curY = e.y
                ripples.add(floatArrayOf(e.x, e.y, now.toFloat()))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (down) { curX = e.x; curY = e.y; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (down) {
                    down = false
                    val dur = now - downT
                    val dist = hypot(e.x - x0, e.y - y0)
                    if (dist < 12f * dens) {
                        steps.add(Step(0, x0 / sw, y0 / sh, x0 / sw, y0 / sh, dur.coerceIn(30L, 1500L)))
                    } else {
                        steps.add(Step(1, x0 / sw, y0 / sh, e.x / sw, e.y / sh, dur.coerceIn(60L, 3000L)))
                    }
                    lastEnd = now
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}
