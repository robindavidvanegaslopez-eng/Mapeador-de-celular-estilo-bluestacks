package com.touchmap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MapperService : AccessibilityService() {

    companion object {
        @JvmStatic
        var instance: MapperService? = null
    }

    private class Active {
        var held = false
        var ptr = -1
        var turbo: Runnable? = null
        var macroOn = false
        var macroTok = 0
        var joyId = -1
        var bits = 0
    }

    private class Bind(val c: Control, val role: Int) // 0 principal, 1 arriba, 2 abajo, 3 izquierda, 4 derecha

    private lateinit var wm: WindowManager
    private lateinit var engine: GestureEngine
    private val h = Handler(Looper.getMainLooper())
    private val ui: Context by lazy { ContextThemeWrapper(this, android.R.style.Theme_Material_NoActionBar) }

    var screenW = 0
    var screenH = 0
    var isLand = false
    var selectedId: String? = null

    private var editing = false
    private var userPaused = false
    private var textPaused = false

    private val allViews = ArrayList<View>()
    private val ctlViews = HashMap<String, ControlView>()
    private val ctlLps = HashMap<String, WindowManager.LayoutParams>()
    private var targetsView: TargetsView? = null
    private var editView: EditView? = null
    private var editLp: WindowManager.LayoutParams? = null
    private var toolbar: View? = null
    private var toolbarLp: WindowManager.LayoutParams? = null
    private var toolbarTop = true
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var recorder: RecorderView? = null

    private val act = HashMap<String, Active>()
    private val keyMap = HashMap<Int, Bind>()
    private var keyCapture: ((Int) -> Unit)? = null

    // ------------------------------------------------------------------ ciclo de vida

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Store.load(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = GestureEngine(this)
        rebuild()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        instance = null
        if (::engine.isInitialized) stopAll()
        if (::wm.isInitialized) removeAllWindows()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        h.removeCallbacks(rebuildRunnable)
        h.postDelayed(rebuildRunnable, 220L)
    }

    private val rebuildRunnable = Runnable { if (::wm.isInitialized) rebuild() }

    /** Lo llama la pantalla principal cuando cambian perfiles o ajustes. */
    fun reload() {
        selectedId = null
        if (::wm.isInitialized) rebuild()
    }

    fun controls(): List<Control> = Store.active().controls

    // ------------------------------------------------------------------ ventanas

    private fun measure() {
        var w = 0
        var hh = 0
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                w = b.width()
                hh = b.height()
            }
        } catch (e: Exception) {
        }
        if (w <= 0 || hh <= 0) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            hh = dm.heightPixels
        }
        screenW = w
        screenH = hh
        isLand = w > hh
    }

    private fun mk(w: Int, hh: Int, x: Int, y: Int, touchable: Boolean, outside: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (outside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (Store.keepOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val p = WindowManager.LayoutParams(
            w, hh, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun addWin(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(v, lp)
            allViews.add(v)
        } catch (e: Exception) {
        }
    }

    private fun removeWin(v: View?) {
        if (v == null) return
        try {
            wm.removeViewImmediate(v)
        } catch (e: Exception) {
        }
        allViews.remove(v)
    }

    private fun removeAllWindows() {
        for (v in ArrayList(allViews)) removeWin(v)
        allViews.clear()
        ctlViews.clear()
        ctlLps.clear()
        targetsView = null
        editView = null
        editLp = null
        toolbar = null
        toolbarLp = null
        panel = null
        panelLp = null
        menu = null
        bubble = null
        bubbleLp = null
        recorder = null
    }

    private fun rebuild() {
        stopAll()
        removeAllWindows()
        measure()
        buildKeyMap()
        if (editing) buildEdit() else buildPlay()
    }

    private fun buildPlay() {
        if (Store.showTargets && controls().isNotEmpty()) {
            val t = TargetsView(ui, this)
            targetsView = t
            addWin(t, mk(screenW, screenH, 0, 0, false))
        }
        val short = min(screenW, screenH).toFloat()
        for (c in controls()) {
            if (c.hidden) continue
            val g = c.geo(isLand)
            val d = max(Ui.dp(ui, 44f), g.size * short)
            val v = ControlView(ui, c, d, this)
            val s = d.toInt() + 2
            val lp = mk(
                s, s,
                (g.cx * screenW - s / 2f).toInt().coerceIn(0, max(0, screenW - s)),
                (g.cy * screenH - s / 2f).toInt().coerceIn(0, max(0, screenH - s)),
                true
            )
            ctlViews[c.id] = v
            ctlLps[c.id] = lp
            addWin(v, lp)
        }
        val b = BubbleView(ui, this)
        val size = b.size()
        val pos = if (isLand) Store.bubbleL else Store.bubbleP
        val lp = mk(
            size, size,
            (pos[0] * screenW).toInt().coerceIn(0, max(0, screenW - size)),
            (pos[1] * screenH).toInt().coerceIn(0, max(0, screenH - size)),
            true
        )
        b.alpha = 0.8f
        bubble = b
        bubbleLp = lp
        addWin(b, lp)
        applyPauseVisual()
    }

    private fun buildEdit() {
        val ev = EditView(ui, this)
        editView = ev
        val elp = mk(screenW, screenH, 0, 0, true)
        editLp = elp
        addWin(ev, elp)
        buildToolbar()
        if (selectedId != null) showProps()
    }

    // ------------------------------------------------------------------ pausa

    private fun blocked(): Boolean = editing || userPaused || textPaused

    private fun applyPauseVisual() {
        val p = userPaused || textPaused
        for ((id, v) in ctlViews) {
            v.alpha = if (p) 0.25f else 1f
            val lp = ctlLps[id] ?: continue
            lp.flags = if (p) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                wm.updateViewLayout(v, lp)
            } catch (e: Exception) {
            }
        }
        targetsView?.alpha = if (p) 0.3f else 1f
        bubble?.let {
            it.status = if (userPaused) 1 else if (textPaused) 2 else 0
            it.invalidate()
        }
    }

    private fun setPaused(user: Boolean, text: Boolean) {
        val changed = (user != userPaused) || (text != textPaused)
        userPaused = user
        textPaused = text
        if (changed) {
            stopAll()
            applyPauseVisual()
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || editing) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                h.removeCallbacks(textCheck)
                h.postDelayed(textCheck, 120L)
            }
        }
    }

    private val textCheck = Runnable { evalText() }

    private fun evalText() {
        if (!::wm.isInitialized) return
        var typing = false
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    typing = true
                    break
                }
            }
            if (!typing) {
                val cfg = resources.configuration
                val hw = cfg.keyboard != Configuration.KEYBOARD_NOKEYS &&
                        cfg.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
                if (hw) {
                    val root = rootInActiveWindow
                    val f = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (f != null && f.isEditable) typing = true
                }
            }
        } catch (e: Exception) {
        }
        if (typing != textPaused) setPaused(userPaused, typing)
    }

    // ------------------------------------------------------------------ ejecución de botones

    private fun stopAll() {
        for ((_, a) in act) releaseState(a)
        if (::engine.isInitialized) engine.releaseAll()
    }

    private fun releaseState(a: Active) {
        if (a.ptr >= 0) engine.up(a.ptr)
        a.ptr = -1
        a.turbo?.let { h.removeCallbacks(it) }
        a.turbo = null
        a.macroOn = false
        a.macroTok++
        if (a.joyId >= 0) engine.up(a.joyId)
        a.joyId = -1
        a.bits = 0
        a.held = false
    }

    fun press(c: Control) {
        if (blocked()) return
        val a = act.getOrPut(c.id) { Active() }
        if (a.held) return
        a.held = true
        val g = c.geo(isLand)
        val tx = g.tx * screenW
        val ty = g.ty * screenH
        when (c.kind) {
            Kind.TAP -> engine.tap(tx, ty, c.holdMs.toLong())
            Kind.HOLD -> a.ptr = engine.down(tx, ty)
            Kind.TURBO -> {
                val hold = min(c.holdMs, max(15, c.intervalMs - 15)).toLong()
                val r = object : Runnable {
                    override fun run() {
                        if (!a.held) return
                        engine.tap(tx, ty, hold)
                        h.postDelayed(this, c.intervalMs.toLong())
                    }
                }
                a.turbo = r
                r.run()
            }
            Kind.SWIPE -> engine.swipe(tx, ty, g.ex * screenW, g.ey * screenH, c.swipeMs.toLong())
            Kind.MACRO -> macroPress(c, a)
        }
    }

    fun release(c: Control) {
        val a = act[c.id] ?: return
        if (!a.held) return
        a.held = false
        when (c.kind) {
            Kind.HOLD -> {
                if (a.ptr >= 0) engine.up(a.ptr)
                a.ptr = -1
            }
            Kind.TURBO -> {
                a.turbo?.let { h.removeCallbacks(it) }
                a.turbo = null
            }
            Kind.MACRO -> if (c.loopMode == 1) {
                a.macroOn = false
                a.macroTok++
            }
        }
    }

    /** Dirección del joystick: (ux, uy) unitario y mag de 0 a 1. */
    fun joy(c: Control, ux: Float, uy: Float, mag: Float) {
        val a = act.getOrPut(c.id) { Active() }
        if (mag < 0.05f || blocked()) {
            if (a.joyId >= 0) engine.up(a.joyId)
            a.joyId = -1
            return
        }
        val g = c.geo(isLand)
        val cx = g.tx * screenW
        val cy = g.ty * screenH
        val r = g.rad * min(screenW, screenH) * c.speed
        if (a.joyId < 0) a.joyId = engine.down(cx, cy)
        engine.move(a.joyId, cx + ux * mag * r, cy + uy * mag * r)
    }

    private fun joyKey(c: Control, bit: Int, down: Boolean) {
        val a = act.getOrPut(c.id) { Active() }
        a.bits = if (down) (a.bits or bit) else (a.bits and bit.inv())
        var dx = 0f
        var dy = 0f
        if ((a.bits and 1) != 0) dy -= 1f
        if ((a.bits and 2) != 0) dy += 1f
        if ((a.bits and 4) != 0) dx -= 1f
        if ((a.bits and 8) != 0) dx += 1f
        val len = hypot(dx, dy)
        if (len < 0.01f) joy(c, 0f, 0f, 0f) else joy(c, dx / len, dy / len, 1f)
    }

    // ------------------------------------------------------------------ macros

    private fun macroPress(c: Control, a: Active) {
        if (c.steps.isEmpty()) return
        when (c.loopMode) {
            2 -> if (a.macroOn) {
                a.macroOn = false
                a.macroTok++
            } else {
                macroStart(c, a, true, false)
            }
            1 -> macroStart(c, a, true, false)
            else -> if (!a.macroOn) macroStart(c, a, false, false)
        }
    }

    private fun macroStart(c: Control, a: Active, loop: Boolean, force: Boolean) {
        a.macroOn = true
        a.macroTok++
        runStep(c, a, a.macroTok, 0, loop, force)
    }

    private fun runStep(c: Control, a: Active, tok: Int, i: Int, loop: Boolean, force: Boolean) {
        if (!a.macroOn || a.macroTok != tok) return
        if (!force && blocked()) {
            a.macroOn = false
            return
        }
        if (i >= c.steps.size) {
            if (loop) {
                h.postDelayed({ runStep(c, a, tok, 0, true, force) }, 40L)
            } else {
                a.macroOn = false
            }
            return
        }
        val s = c.steps[i]
        var wait = 20L
        when (s.type) {
            0 -> {
                val d = s.dur.coerceAtLeast(30L)
                engine.tap(s.x1 * screenW, s.y1 * screenH, d)
                wait = d + 25L
            }
            1 -> {
                engine.swipe(s.x1 * screenW, s.y1 * screenH, s.x2 * screenW, s.y2 * screenH, s.dur)
                wait = s.dur + GestureEngine.SEG + 25L
            }
            else -> wait = s.dur.coerceAtLeast(20L)
        }
        h.postDelayed({ runStep(c, a, tok, i + 1, loop, force) }, wait)
    }

    private fun macroDuration(c: Control): Long {
        var t = 0L
        for (s in c.steps) t += if (s.type == 2) s.dur else s.dur + 80L
        return t
    }

    private fun testMacro(c: Control) {
        if (c.steps.isEmpty()) {
            toast("Primero graba la macro")
            return
        }
        setEditInteractive(false)
        val a = act.getOrPut(c.id) { Active() }
        macroStart(c, a, false, true)
        h.postDelayed({
            a.macroOn = false
            setEditInteractive(true)
        }, macroDuration(c) + 400L)
    }

    // ------------------------------------------------------------------ teclado externo

    private fun buildKeyMap() {
        keyMap.clear()
        for (c in controls()) {
            if (c.kind == Kind.JOYSTICK) {
                if (c.kUp >= 0) keyMap[c.kUp] = Bind(c, 1)
                if (c.kDown >= 0) keyMap[c.kDown] = Bind(c, 2)
                if (c.kLeft >= 0) keyMap[c.kLeft] = Bind(c, 3)
                if (c.kRight >= 0) keyMap[c.kRight] = Bind(c, 4)
            } else if (c.key >= 0) {
                keyMap[c.key] = Bind(c, 0)
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val cap = keyCapture
        if (cap != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                keyCapture = null
                cap(event.keyCode)
            }
            return true
        }
        if (editing || userPaused || textPaused) return false
        val b = keyMap[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                if (b.role == 0) press(b.c) else joyKey(b.c, 1 shl (b.role - 1), true)
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (b.role == 0) release(b.c) else joyKey(b.c, 1 shl (b.role - 1), false)
        }
        return true
    }

    private fun captureKey(cb: (Int) -> Unit) {
        keyCapture = { code ->
            if (code != KeyEvent.KEYCODE_ESCAPE) cb(code) else showProps()
        }
    }

    // ------------------------------------------------------------------ burbuja y menú

    fun moveBubble(dx: Float, dy: Float) {
        val lp = bubbleLp ?: return
        val b = bubble ?: return
        closeMenu()
        lp.x = (lp.x + dx.toInt()).coerceIn(0, max(0, screenW - b.size()))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, max(0, screenH - b.size()))
        try {
            wm.updateViewLayout(b, lp)
        } catch (e: Exception) {
        }
    }

    fun dropBubble() {
        val lp = bubbleLp ?: return
        val b = bubble ?: return
        val m = Ui.dpi(ui, 2f)
        lp.x = if (lp.x + b.size() / 2 < screenW / 2) m else screenW - b.size() - m
        try {
            wm.updateViewLayout(b, lp)
        } catch (e: Exception) {
        }
        val pos = if (isLand) Store.bubbleL else Store.bubbleP
        pos[0] = lp.x.toFloat() / screenW
        pos[1] = lp.y.toFloat() / screenH
        Store.save()
    }

    fun bubbleTapped() {
        if (menu != null) closeMenu() else showMenu()
    }

    private fun closeMenu() {
        removeWin(menu)
        menu = null
    }

    private fun showMenu() {
        val b = bubble ?: return
        val blp = bubbleLp ?: return
        val col = LinearLayout(ui)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dpi(ui, 6f)
        col.setPadding(p, p, p, p)
        col.background = Ui.rr(Ui.CARD, Ui.dp(ui, 18f), 0x33FFFFFF, 1)

        fun item(text: String, color: Int = Ui.TEXT, onClick: () -> Unit) {
            val t = TextView(ui)
            t.text = text
            t.setTextColor(color)
            t.textSize = 14f
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(Ui.dpi(ui, 12f), 0, Ui.dpi(ui, 12f), 0)
            t.setOnClickListener { closeMenu(); onClick() }
            col.addView(t, LinearLayout.LayoutParams(-1, Ui.dpi(ui, 44f)))
        }
        item("✎   Editar botones") { enterEdit() }
        item(if (userPaused) "▶   Reanudar mapeo" else "⏸   Pausar mapeo", if (userPaused) Ui.OK else Ui.TEXT) {
            setPaused(!userPaused, textPaused)
        }
        item("◎   Objetivos: " + (if (Store.showTargets) "visibles" else "ocultos")) {
            Store.showTargets = !Store.showTargets
            Store.save()
            rebuild()
        }
        item("⇄   Perfil: " + Store.active().name) {
            val i = Store.profiles.indexOf(Store.active())
            Store.activeId = Store.profiles[(i + 1) % Store.profiles.size].id
            Store.save()
            rebuild()
            toast("Perfil: " + Store.active().name)
        }
        item("✕   Detener mapeador", Ui.DANGER) { disableSelf() }

        val w = Ui.dpi(ui, 230f)
        val estH = Ui.dpi(ui, 5 * 44f + 14f)
        val x = if (blp.x + b.size() / 2 < screenW / 2) blp.x + b.size() + Ui.dpi(ui, 6f)
        else blp.x - w - Ui.dpi(ui, 6f)
        val y = blp.y.coerceIn(0, max(0, screenH - estH))
        val lp = mk(w, WindowManager.LayoutParams.WRAP_CONTENT, x.coerceIn(0, max(0, screenW - w)), y, true, true)
        col.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) closeMenu()
            false
        }
        menu = col
        addWin(col, lp)
        // la burbuja debe quedar por encima del menú
        removeWin(b)
        addWin(b, blp)
    }

    // ------------------------------------------------------------------ modo edición

    private fun enterEdit() {
        editing = true
        stopAll()
        rebuild()
    }

    private fun exitEdit() {
        editing = false
        selectedId = null
        keyCapture = null
        Store.save()
        rebuild()
    }

    fun select(id: String?) {
        if (id == selectedId && panelKind == 2) return
        selectedId = id
        if (id == null) closePanel() else showProps()
    }

    fun selectionMoved() {
        if (selectedId != null && panelKind == 2) showProps()
    }

    private fun setEditInteractive(on: Boolean) {
        val pairs = ArrayList<Pair<View?, WindowManager.LayoutParams?>>()
        pairs.add(Pair(editView, editLp))
        pairs.add(Pair(toolbar, toolbarLp))
        pairs.add(Pair(panel, panelLp))
        for ((v, lp) in pairs) {
            if (v == null || lp == null) continue
            v.visibility = if (on) View.VISIBLE else View.INVISIBLE
            lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                wm.updateViewLayout(v, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun buildToolbar() {
        val row = LinearLayout(ui)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dpi(ui, 6f)
        row.setPadding(p, p, p, p)
        fun add(text: String, bg: Int, fg: Int = Ui.TEXT, onClick: () -> Unit) {
            val t = Ui.pill(ui, text, bg, fg, onClick)
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(Ui.dpi(ui, 3f), 0, Ui.dpi(ui, 3f), 0)
            row.addView(t, lp)
        }
        add("＋ Añadir", Ui.ACCENT, Color.BLACK) { showAddMenu() }
        add("⚙ Ajustes", Ui.CARD2) {
            if (selectedId != null) showProps() else toast("Toca un botón para editarlo")
        }
        add("⧉ Duplicar", Ui.CARD2) { duplicateSelected() }
        add("🗑 Borrar", Ui.CARD2, Ui.DANGER) { deleteSelected() }
        add("⇅", Ui.CARD2) { moveToolbar() }
        add("✓ Listo", Ui.OK, Color.BLACK) { exitEdit() }
        val sv = HorizontalScrollView(ui)
        sv.isHorizontalScrollBarEnabled = false
        sv.addView(row)
        sv.background = Ui.rr(Ui.CARD, Ui.dp(ui, 26f), 0x33FFFFFF, 1)
        val w = min(screenW - Ui.dpi(ui, 16f), Ui.dpi(ui, 470f))
        val lp = mk(w, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, true)
        placeToolbar(lp)
        toolbar = sv
        toolbarLp = lp
        addWin(sv, lp)
    }

    private fun placeToolbar(lp: WindowManager.LayoutParams) {
        if (toolbarTop) {
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = statusBarHeight() + Ui.dpi(ui, 6f)
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.y = Ui.dpi(ui, 10f)
        }
        lp.x = 0
    }

    private fun statusBarHeight(): Int {
        if (isLand) return 0
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else Ui.dpi(ui, 24f)
    }

    private fun moveToolbar() {
        val v = toolbar ?: return
        val lp = toolbarLp ?: return
        toolbarTop = !toolbarTop
        placeToolbar(lp)
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
        }
    }

    private fun addControl(kind: Int) {
        val c = Control(Store.newId())
        c.kind = kind
        val n = controls().size
        val g = if (kind == Kind.JOYSTICK) {
            Geo(0.82f, 0.66f, 0.16f, 0.68f, 0.3f, 0.5f, 0.28f, 0.11f)
        } else {
            val y = 0.22f + 0.1f * (n % 6)
            if (kind == Kind.SWIPE) Geo(0.88f, y, 0.4f, 0.5f, 0.6f, 0.5f, 0.11f, 0.12f)
            else Geo(0.88f, y, 0.5f, 0.5f, 0.6f, 0.5f, 0.11f, 0.12f)
        }
        c.port = g
        c.land = g.copyGeo()
        Store.active().controls.add(c)
        Store.save()
        selectedId = c.id
        buildKeyMap()
        editView?.invalidate()
        showProps()
    }

    private fun duplicateSelected() {
        val s = controls().firstOrNull { it.id == selectedId }
        if (s == null) {
            toast("Selecciona un botón primero")
            return
        }
        val c = Control(Store.newId())
        c.kind = s.kind
        c.holdMs = s.holdMs
        c.intervalMs = s.intervalMs
        c.swipeMs = s.swipeMs
        c.speed = s.speed
        c.analogPad = s.analogPad
        c.loopMode = s.loopMode
        c.opacity = s.opacity
        c.hidden = s.hidden
        c.port = s.port.copyGeo()
        c.land = s.land.copyGeo()
        for (g in listOf(c.port, c.land)) {
            g.cx = (g.cx + 0.05f).coerceAtMost(0.97f)
            g.cy = (g.cy + 0.05f).coerceAtMost(0.97f)
        }
        for (st in s.steps) c.steps.add(Step(st.type, st.x1, st.y1, st.x2, st.y2, st.dur))
        Store.active().controls.add(c)
        Store.save()
        selectedId = c.id
        editView?.invalidate()
        showProps()
    }

    private fun deleteSelected() {
        val s = controls().firstOrNull { it.id == selectedId }
        if (s == null) {
            toast("Selecciona un botón primero")
            return
        }
        Store.active().controls.remove(s)
        Store.save()
        selectedId = null
        buildKeyMap()
        closePanel()
        editView?.invalidate()
    }

    // ------------------------------------------------------------------ paneles

    private var panelKind = 0 // 0 ninguno, 1 añadir, 2 ajustes

    private fun closePanel() {
        removeWin(panel)
        panel = null
        panelLp = null
        panelKind = 0
    }

    private fun card(): LinearLayout {
        val col = LinearLayout(ui)
        col.orientation = LinearLayout.VERTICAL
        val p = Ui.dpi(ui, 12f)
        col.setPadding(p, p, p, p)
        col.background = Ui.rr(Ui.CARD, Ui.dp(ui, 20f), 0x33FFFFFF, 1)
        return col
    }

    private fun header(root: LinearLayout, title: String) {
        val row = LinearLayout(ui)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(ui)
        t.text = title
        t.setTextColor(Ui.TEXT)
        t.textSize = 17f
        t.typeface = Typeface.DEFAULT_BOLD
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.pill(ui, "✕", Ui.CARD2) { closePanel() })
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = Ui.dpi(ui, 8f)
        root.addView(row, lp)
    }

    private fun placePanel(root: View, fixed: Boolean) {
        val w: Int
        val hh: Int
        val lp: WindowManager.LayoutParams
        if (isLand) {
            w = Ui.dpi(ui, 310f)
            hh = if (fixed) screenH - Ui.dpi(ui, 20f) else WindowManager.LayoutParams.WRAP_CONTENT
            lp = mk(w, hh, 0, 0, true)
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            lp.x = Ui.dpi(ui, 8f)
        } else {
            w = screenW - Ui.dpi(ui, 16f)
            hh = if (fixed) (screenH * 0.47f).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
            lp = mk(w, hh, 0, 0, true)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.y = Ui.dpi(ui, 8f)
        }
        panel = root
        panelLp = lp
        addWin(root, lp)
    }

    private fun showAddMenu() {
        closePanel()
        panelKind = 1
        val root = card()
        header(root, "Añadir botón")
        val kinds = intArrayOf(Kind.TAP, Kind.HOLD, Kind.TURBO, Kind.SWIPE, Kind.JOYSTICK, Kind.MACRO)
        val desc = arrayOf(
            "Un toque", "Mantiene el dedo", "Clics continuos", "Desliza de A a B",
            "Mover con WASD o pad", "Secuencia grabada"
        )
        var i = 0
        while (i < kinds.size) {
            val row = LinearLayout(ui)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0..1) {
                val k = kinds[i + j]
                val cell = LinearLayout(ui)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                val pp = Ui.dpi(ui, 8f)
                cell.setPadding(pp, pp, pp, pp)
                cell.background = Ui.rr(Ui.CARD2, Ui.dp(ui, 14f))
                val g = TextView(ui)
                g.text = Kind.glyph(k)
                g.textSize = 24f
                g.gravity = Gravity.CENTER
                g.setTextColor(Kind.color(k))
                val t = TextView(ui)
                t.text = Kind.title(k)
                t.textSize = 14f
                t.typeface = Typeface.DEFAULT_BOLD
                t.gravity = Gravity.CENTER
                t.setTextColor(Ui.TEXT)
                val d = TextView(ui)
                d.text = desc[i + j]
                d.textSize = 11f
                d.gravity = Gravity.CENTER
                d.setTextColor(Ui.MUTED)
                cell.addView(g)
                cell.addView(t)
                cell.addView(d)
                cell.setOnClickListener { closePanel(); addControl(k) }
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.setMargins(Ui.dpi(ui, 3f), Ui.dpi(ui, 3f), Ui.dpi(ui, 3f), Ui.dpi(ui, 3f))
                row.addView(cell, lp)
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
            i += 2
        }
        placePanel(root, false)
    }

    private fun label(parent: LinearLayout, text: String, muted: Boolean = false) {
        val t = TextView(ui)
        t.text = text
        t.textSize = if (muted) 12f else 13f
        t.setTextColor(if (muted) Ui.MUTED else Ui.TEXT)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Ui.dpi(ui, 8f)
        parent.addView(t, lp)
    }

    private fun slider(
        parent: LinearLayout, title: String, lo: Int, hi: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val t = TextView(ui)
        t.text = title + ": " + fmt(value)
        t.textSize = 13f
        t.setTextColor(Ui.TEXT)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Ui.dpi(ui, 10f)
        parent.addView(t, lp)
        val sb = SeekBar(ui)
        sb.max = hi - lo
        sb.progress = (value - lo).coerceIn(0, hi - lo)
        val tint = ColorStateList.valueOf(Ui.ACCENT)
        sb.progressTintList = tint
        sb.thumbTintList = tint
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = lo + p
                t.text = title + ": " + fmt(v)
                if (fromUser) {
                    onChange(v)
                    editView?.invalidate()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                Store.save()
            }
        })
        parent.addView(sb, LinearLayout.LayoutParams(-1, -2))
    }

    private fun toggle(parent: LinearLayout, text: String, on: Boolean, onClick: () -> Unit) {
        val t = Ui.pill(ui, text, if (on) Ui.ACCENT else Ui.CARD2, if (on) Color.BLACK else Ui.TEXT, onClick)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Ui.dpi(ui, 8f)
        parent.addView(t, lp)
    }

    private fun keyRow(parent: LinearLayout, title: String, get: () -> Int, set: (Int) -> Unit) {
        val row = LinearLayout(ui)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val btn = Ui.pill(ui, "$title:  " + Ui.keyName(get()), Ui.CARD2) {}
        btn.setOnClickListener {
            btn.text = "Presiona una tecla…  (Esc cancela)"
            btn.background = Ui.rr(Ui.ACCENT, Ui.dp(ui, 18f))
            btn.setTextColor(Color.BLACK)
            captureKey { code ->
                set(code)
                Store.save()
                buildKeyMap()
                editView?.invalidate()
                showProps()
            }
        }
        row.addView(btn, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = Ui.pill(ui, "✕", Ui.CARD2, Ui.MUTED) {
            set(-1)
            Store.save()
            buildKeyMap()
            editView?.invalidate()
            showProps()
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.leftMargin = Ui.dpi(ui, 6f)
        row.addView(clear, lp)
        val rlp = LinearLayout.LayoutParams(-1, -2)
        rlp.topMargin = Ui.dpi(ui, 8f)
        parent.addView(row, rlp)
    }

    private fun showProps() {
        val c = controls().firstOrNull { it.id == selectedId }
        if (c == null) {
            closePanel()
            return
        }
        val g = c.geo(isLand)
        removeWin(panel)
        panel = null
        panelLp = null
        panelKind = 2
        val root = card()
        header(root, Kind.glyph(c.kind) + "  " + Kind.title(c.kind))
        val sv = ScrollView(ui)
        sv.isVerticalScrollBarEnabled = false
        val body = LinearLayout(ui)
        body.orientation = LinearLayout.VERTICAL
        sv.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- teclas
        if (c.kind == Kind.JOYSTICK) {
            label(body, "Teclas del teclado externo")
            keyRow(body, "▲ Arriba", { c.kUp }, { c.kUp = it })
            keyRow(body, "▼ Abajo", { c.kDown }, { c.kDown = it })
            keyRow(body, "◀ Izquierda", { c.kLeft }, { c.kLeft = it })
            keyRow(body, "▶ Derecha", { c.kRight }, { c.kRight = it })
        } else {
            label(body, "Tecla del teclado externo")
            keyRow(body, "Tecla", { c.key }, { c.key = it })
        }

        // --- específicos del tipo
        when (c.kind) {
            Kind.TAP -> slider(body, "Duración del toque", 10, 400, c.holdMs, { "$it ms" }) { c.holdMs = it }
            Kind.TURBO -> {
                slider(body, "Intervalo entre clics", 30, 500, c.intervalMs, { "$it ms (${1000 / max(it, 1)}/s)" }) {
                    c.intervalMs = it
                }
                slider(body, "Duración de cada clic", 10, 200, c.holdMs, { "$it ms" }) { c.holdMs = it }
            }
            Kind.SWIPE -> {
                slider(body, "Duración del deslizamiento", 60, 1500, c.swipeMs, { "$it ms" }) { c.swipeMs = it }
                label(body, "Arrastra el punto ➜ para fijar dónde termina el deslizamiento.", true)
            }
            Kind.JOYSTICK -> {
                slider(body, "Velocidad (desplazamiento)", 30, 100, (c.speed * 100).toInt(), { "$it %" }) {
                    c.speed = it / 100f
                }
                slider(body, "Radio del joystick del juego", 3, 40, (g.rad * 100).toInt(), { "$it %" }) {
                    g.rad = it / 100f
                }
                toggle(body, if (c.analogPad) "Pad táctil: proporcional" else "Pad táctil: siempre al máximo", c.analogPad) {
                    c.analogPad = !c.analogPad
                    Store.save()
                    showProps()
                }
                val det = Ui.pill(ui, "◎  Detectar joystick automáticamente", Ui.OK, Color.BLACK) { autoDetect(c) }
                val dl = LinearLayout.LayoutParams(-1, -2)
                dl.topMargin = Ui.dpi(ui, 12f)
                body.addView(det, dl)
                label(
                    body,
                    "Coloca el círculo punteado sobre el joystick del juego (más o menos) y pulsa detectar: " +
                            "se ajusta solo al centro y radio exactos.",
                    true
                )
            }
            Kind.MACRO -> {
                val info = c.steps.size.toString() + " pasos · " + (macroDuration(c) / 100L) / 10.0 + " s"
                label(body, info, true)
                val r = LinearLayout(ui)
                r.orientation = LinearLayout.HORIZONTAL
                val rec = Ui.pill(ui, "● Grabar", Ui.DANGER, Color.WHITE) { startRecord(c) }
                val tst = Ui.pill(ui, "▶ Probar", Ui.CARD2) { testMacro(c) }
                val clr = Ui.pill(ui, "Vaciar", Ui.CARD2, Ui.MUTED) {
                    c.steps.clear()
                    Store.save()
                    showProps()
                }
                for (v in listOf(rec, tst, clr)) {
                    val lp = LinearLayout.LayoutParams(0, -2, 1f)
                    lp.setMargins(Ui.dpi(ui, 3f), Ui.dpi(ui, 6f), Ui.dpi(ui, 3f), 0)
                    r.addView(v, lp)
                }
                body.addView(r, LinearLayout.LayoutParams(-1, -2))
                toggle(body, "Ejecutar una vez al pulsar", c.loopMode == 0) { c.loopMode = 0; Store.save(); showProps() }
                toggle(body, "Repetir mientras se mantiene pulsado", c.loopMode == 1) { c.loopMode = 1; Store.save(); showProps() }
                toggle(body, "Alternar: pulsar para iniciar / detener", c.loopMode == 2) { c.loopMode = 2; Store.save(); showProps() }
            }
            Kind.HOLD -> label(body, "Mantiene el dedo apoyado mientras sostengas el botón o la tecla.", true)
        }

        // --- apariencia
        val lo = if (c.kind == Kind.JOYSTICK) 14 else 6
        val hi = if (c.kind == Kind.JOYSTICK) 45 else 24
        slider(body, "Tamaño del botón", lo, hi, (g.size * 100).toInt(), { "$it %" }) {
            g.size = it / 100f
        }
        slider(body, "Opacidad", 20, 100, (c.opacity * 100).toInt(), { "$it %" }) { c.opacity = it / 100f }
        toggle(body, if (c.hidden) "Botón en pantalla: oculto (solo teclado)" else "Botón en pantalla: visible", !c.hidden) {
            c.hidden = !c.hidden
            Store.save()
            editView?.invalidate()
            showProps()
        }
        label(
            body,
            "Estás editando la distribución de " + (if (isLand) "horizontal" else "vertical") +
                    ". Gira el teléfono para ajustar la otra.",
            true
        )
        val del = Ui.pill(ui, "🗑  Eliminar botón", Ui.CARD2, Ui.DANGER) { deleteSelected() }
        val dl = LinearLayout.LayoutParams(-1, -2)
        dl.topMargin = Ui.dpi(ui, 12f)
        dl.bottomMargin = Ui.dpi(ui, 6f)
        body.addView(del, dl)

        placePanel(root, true)
    }

    // ------------------------------------------------------------------ grabación y detección

    private fun startRecord(c: Control) {
        setEditInteractive(false)
        val rv = RecorderView(ui, screenW, screenH) { steps ->
            removeWin(recorder)
            recorder = null
            if (steps != null) {
                c.steps = steps
                Store.save()
                toast(steps.size.toString() + " pasos guardados")
            }
            setEditInteractive(true)
            showProps()
        }
        recorder = rv
        addWin(rv, mk(screenW, screenH, 0, 0, true))
    }

    private fun autoDetect(c: Control) {
        if (Build.VERSION.SDK_INT < 30) {
            toast("La detección automática requiere Android 11 o superior")
            return
        }
        toast("Detectando… no toques la pantalla")
        setEditInteractive(false)
        h.postDelayed({ capture(c) }, 320L)
    }

    private fun capture(c: Control) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        Thread {
                            var out: JoystickDetector.Result? = null
                            var bw = 1
                            var bh = 1
                            try {
                                val hb = result.hardwareBuffer
                                val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                hb.close()
                                if (bmp != null) {
                                    bw = bmp.width
                                    bh = bmp.height
                                    val g = c.geo(isLand)
                                    out = JoystickDetector.detect(
                                        bmp, g.tx * bw, g.ty * bh, g.rad * min(bw, bh)
                                    )
                                }
                            } catch (t: Throwable) {
                            }
                            val res = out
                            h.post { onDetected(c, res, bw, bh) }
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        h.post {
                            setEditInteractive(true)
                            toast("No se pudo capturar la pantalla (código $errorCode). Inténtalo de nuevo.")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            setEditInteractive(true)
            toast("No se pudo capturar la pantalla")
        }
    }

    private fun onDetected(c: Control, r: JoystickDetector.Result?, bw: Int, bh: Int) {
        setEditInteractive(true)
        if (r == null || r.score < 0.05f || r.peak < 1.5f) {
            toast("No se detectó el joystick. Acércale más el círculo punteado y ajusta su radio.")
            return
        }
        val g = c.geo(isLand)
        g.tx = (r.cx / bw).coerceIn(0f, 1f)
        g.ty = (r.cy / bh).coerceIn(0f, 1f)
        g.rad = (r.r / min(bw, bh)).coerceIn(0.03f, 0.5f)
        Store.save()
        editView?.invalidate()
        showProps()
        toast("Joystick ajustado: centro y radio detectados (" + (r.score * 100).toInt() + " % de coincidencia)")
    }

    private fun toast(s: String) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }
}
