package com.touchmap

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF0F1117.toInt())
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = Ui.dpi(this, 16f)
        root.setPadding(p, p, p, p * 2)
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, MapperService::class.java)
        for (part in s.split(':')) {
            val cn = ComponentName.unflattenFromString(part)
            if (cn != null && cn == me) return true
        }
        return false
    }

    private fun batteryOk(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = Ui.dpi(this, 16f)
        c.setPadding(p, p, p, p)
        c.background = Ui.rr(0xFF1B1F29.toInt(), Ui.dp(this, 20f))
        val t = text(title, 16f, Ui.TEXT, true)
        c.addView(t)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Ui.dpi(this, 14f)
        root.addView(c, lp)
        return c
    }

    private fun gap(parent: LinearLayout, dp: Float = 8f) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, Ui.dpi(this, dp)))
    }

    private fun btn(parent: LinearLayout, label: String, bg: Int, fg: Int, onClick: () -> Unit) {
        val b = Ui.pill(this, label, bg, fg, onClick)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Ui.dpi(this, 10f)
        parent.addView(b, lp)
    }

    private fun render() {
        root.removeAllViews()

        val title = text("Mapeador de Toques", 26f, Ui.TEXT, true)
        root.addView(title)
        root.addView(text("Botones en pantalla que simulan tus dedos, con teclado externo", 13f, Ui.MUTED))

        // ---- Estado
        val on = serviceEnabled()
        val st = card("Estado")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dot.background = Ui.rr(if (on) Ui.OK else Ui.DANGER, Ui.dp(this, 8f))
        row.addView(dot, LinearLayout.LayoutParams(Ui.dpi(this, 12f), Ui.dpi(this, 12f)))
        val msg = text(
            if (on) "  Servicio activado: toca la burbuja flotante" else "  Servicio desactivado",
            14f, Ui.TEXT
        )
        row.addView(msg)
        st.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (!on) {
            btn(st, "Abrir ajustes de accesibilidad", Ui.ACCENT, Color.BLACK) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                gap(st)
                st.addView(
                    text(
                        "Si Android no te deja activarlo (opción en gris): abre la información de la app, " +
                                "toca ⋮ y elige «Permitir ajustes restringidos». Luego vuelve a activarlo.",
                        12f, Ui.MUTED
                    )
                )
                btn(st, "Abrir información de la app", Ui.CARD2, Ui.TEXT) {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                }
            }
        }

        // ---- Batería
        val bat = card("Que Android no lo detenga")
        val okB = batteryOk()
        bat.addView(
            text(
                if (okB) "✓ Sin restricciones de batería."
                else "Quita la optimización de batería para que el mapeo no se corte con la pantalla apagada o en segundo plano.",
                13f, if (okB) Ui.OK else Ui.MUTED
            )
        )
        if (!okB) {
            btn(bat, "Quitar optimización de batería", Ui.ACCENT, Color.BLACK) {
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        // ---- Guía
        val g = card("Cómo se usa")
        val steps = arrayOf(
            "1. Activa el servicio y abre tu juego.",
            "2. Toca la burbuja flotante → «Editar botones».",
            "3. «Añadir» un botón (toque, mantener, turbo, deslizar, joystick o macro) y arrástralo.",
            "4. Cada botón tiene dos partes: el botón que tocas (o su tecla) y el objetivo ⊕, donde se simula el toque. Mantenlos separados.",
            "5. «Listo» bloquea la posición. Los botones se pausan solos cuando aparece el teclado de Android o un cuadro de texto.",
            "6. Gira el teléfono para ajustar la distribución horizontal; cada orientación se guarda aparte."
        )
        for (s in steps) {
            val t = text(s, 13f, Ui.MUTED)
            t.setPadding(0, Ui.dpi(this, 6f), 0, 0)
            g.addView(t)
        }

        // ---- Perfiles
        val pc = card("Perfiles")
        for (p in Store.profiles) {
            val active = p.id == Store.activeId
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val pad = Ui.dpi(this, 12f)
            r.setPadding(pad, pad, pad, pad)
            r.background = Ui.rr(if (active) 0xFF2A3A66.toInt() else Ui.CARD2, Ui.dp(this, 14f))
            val n = text((if (active) "● " else "○ ") + p.name, 15f, Ui.TEXT, active)
            r.addView(n, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(text(p.controls.size.toString() + " botones", 12f, Ui.MUTED))
            r.setOnClickListener {
                Store.activeId = p.id
                Store.save()
                MapperService.instance?.reload()
                render()
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dpi(this, 8f)
            pc.addView(r, lp)
        }
        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        val actions = arrayOf("Nuevo", "Renombrar", "Duplicar", "Eliminar")
        for (a in actions) {
            val b = Ui.pill(this, a, Ui.CARD2, if (a == "Eliminar") Ui.DANGER else Ui.TEXT) { profileAction(a) }
            b.textSize = 12f
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(Ui.dpi(this, 2f), Ui.dpi(this, 10f), Ui.dpi(this, 2f), 0)
            br.addView(b, lp)
        }
        pc.addView(br, LinearLayout.LayoutParams(-1, -2))

        // ---- Ajustes
        val sc = card("Ajustes")
        switchRow(sc, "Mostrar objetivos ⊕ sobre el juego", Store.showTargets) { Store.showTargets = it }
        switchRow(sc, "Mantener la pantalla encendida", Store.keepOn) { Store.keepOn = it }
        switchRow(sc, "Vibración al tocar un botón", Store.haptics) { Store.haptics = it }
    }

    private fun switchRow(parent: LinearLayout, label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = label
        s.textSize = 14f
        s.setTextColor(Ui.TEXT)
        s.isChecked = value
        s.setPadding(0, Ui.dpi(this, 8f), 0, Ui.dpi(this, 8f))
        s.setOnCheckedChangeListener { _, checked ->
            onChange(checked)
            Store.save()
            MapperService.instance?.reload()
        }
        parent.addView(s, LinearLayout.LayoutParams(-1, -2))
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSingleLine(true)
        et.setSelection(et.text.length)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("Aceptar") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun profileAction(a: String) {
        val cur = Store.active()
        when (a) {
            "Nuevo" -> askName("Nombre del perfil", "") { n ->
                val p = Profile(Store.newId(), n)
                Store.profiles.add(p)
                Store.activeId = p.id
                done()
            }
            "Renombrar" -> askName("Nuevo nombre", cur.name) { n ->
                cur.name = n
                done()
            }
            "Duplicar" -> {
                val p = Profile(Store.newId(), cur.name + " (copia)")
                for (c in cur.controls) p.controls.add(c.deepCopy(Store.newId()))
                Store.profiles.add(p)
                Store.activeId = p.id
                done()
            }
            "Eliminar" -> {
                if (Store.profiles.size <= 1) {
                    Toast.makeText(this, "Debe existir al menos un perfil", Toast.LENGTH_SHORT).show()
                    return
                }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("¿Eliminar «" + cur.name + "»?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        Store.profiles.remove(cur)
                        Store.activeId = Store.profiles[0].id
                        done()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun done() {
        Store.save()
        MapperService.instance?.reload()
        render()
    }
}
