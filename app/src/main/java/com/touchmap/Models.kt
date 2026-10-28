package com.touchmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Kind {
    const val TAP = 0
    const val HOLD = 1
    const val TURBO = 2
    const val SWIPE = 3
    const val JOYSTICK = 4
    const val MACRO = 5

    fun title(k: Int): String = when (k) {
        TAP -> "Toque"
        HOLD -> "Mantener"
        TURBO -> "Turbo"
        SWIPE -> "Deslizar"
        JOYSTICK -> "Joystick"
        else -> "Macro"
    }

    fun glyph(k: Int): String = when (k) {
        TAP -> "●"
        HOLD -> "◉"
        TURBO -> "»"
        SWIPE -> "➜"
        JOYSTICK -> "✥"
        else -> "▶"
    }

    fun color(k: Int): Int = when (k) {
        TAP -> 0xFF5B8CFF.toInt()
        HOLD -> 0xFF9B6BFF.toInt()
        TURBO -> 0xFFFF9F43.toInt()
        SWIPE -> 0xFF2ED3A1.toInt()
        JOYSTICK -> 0xFF4FC3F7.toInt()
        else -> 0xFFFF6B8B.toInt()
    }
}

/** Posiciones como fracciones de la pantalla (0..1). size y rad son fracciones del lado corto. */
class Geo(
    var cx: Float, var cy: Float,
    var tx: Float, var ty: Float,
    var ex: Float, var ey: Float,
    var size: Float, var rad: Float
) {
    fun copyGeo() = Geo(cx, cy, tx, ty, ex, ey, size, rad)
}

/** type: 0 = toque, 1 = deslizar, 2 = esperar. Coordenadas como fracciones de la pantalla. */
class Step(var type: Int, var x1: Float, var y1: Float, var x2: Float, var y2: Float, var dur: Long)

class Control(val id: String) {
    var kind = Kind.TAP
    var key = -1
    var kUp = -1
    var kDown = -1
    var kLeft = -1
    var kRight = -1
    var holdMs = 40
    var intervalMs = 70
    var swipeMs = 250
    var speed = 1.0f
    var analogPad = true
    var fixedPress = true // joystick: toque quieto en el punto de la dirección (no arrastre desde el centro)
    var loopMode = 0 // 0 una vez, 1 repetir mientras se mantiene, 2 alternar
    var opacity = 0.85f
    var hidden = false // solo teclado, sin botón en pantalla
    var port = Geo(0.85f, 0.5f, 0.5f, 0.5f, 0.6f, 0.5f, 0.11f, 0.12f)
    var land = Geo(0.85f, 0.5f, 0.5f, 0.5f, 0.6f, 0.5f, 0.11f, 0.12f)
    var steps = ArrayList<Step>()

    fun geo(landscape: Boolean): Geo = if (landscape) land else port
}

class Profile(val id: String, var name: String) {
    val controls = ArrayList<Control>()
}

object Store {
    val profiles = ArrayList<Profile>()
    var activeId = ""
    var showTargets = true
    var keepOn = false
    var haptics = true
    var bubbleP = floatArrayOf(0.0f, 0.35f)
    var bubbleL = floatArrayOf(0.0f, 0.35f)
    private var file: File? = null

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun active(): Profile {
        for (p in profiles) if (p.id == activeId) return p
        return profiles[0]
    }

    fun load(ctx: Context) {
        if (file == null) file = File(ctx.filesDir, "perfiles.json")
        if (profiles.isNotEmpty()) return
        try {
            val f = file!!
            if (f.exists()) {
                val root = JSONObject(f.readText())
                activeId = root.optString("active", "")
                showTargets = root.optBoolean("showTargets", true)
                keepOn = root.optBoolean("keepOn", false)
                haptics = root.optBoolean("haptics", true)
                readPair(root.optJSONArray("bubbleP"), bubbleP)
                readPair(root.optJSONArray("bubbleL"), bubbleL)
                val arr = root.getJSONArray("profiles")
                for (i in 0 until arr.length()) profiles.add(readProfile(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            profiles.clear()
        }
        if (profiles.isEmpty()) {
            val p = Profile(newId(), "Mi perfil")
            profiles.add(p)
            activeId = p.id
            save()
        }
    }

    fun save() {
        try {
            val root = JSONObject()
            root.put("active", activeId)
            root.put("showTargets", showTargets)
            root.put("keepOn", keepOn)
            root.put("haptics", haptics)
            root.put("bubbleP", writePair(bubbleP))
            root.put("bubbleL", writePair(bubbleL))
            val arr = JSONArray()
            for (p in profiles) arr.put(writeProfile(p))
            root.put("profiles", arr)
            file?.writeText(root.toString())
        } catch (e: Exception) {
        }
    }

    private fun readPair(a: JSONArray?, out: FloatArray) {
        if (a != null && a.length() == 2) {
            out[0] = a.getDouble(0).toFloat()
            out[1] = a.getDouble(1).toFloat()
        }
    }

    private fun writePair(f: FloatArray): JSONArray {
        val a = JSONArray()
        a.put(f[0].toDouble())
        a.put(f[1].toDouble())
        return a
    }

    private fun writeGeo(g: Geo): JSONObject {
        val o = JSONObject()
        o.put("cx", g.cx.toDouble()); o.put("cy", g.cy.toDouble())
        o.put("tx", g.tx.toDouble()); o.put("ty", g.ty.toDouble())
        o.put("ex", g.ex.toDouble()); o.put("ey", g.ey.toDouble())
        o.put("size", g.size.toDouble()); o.put("rad", g.rad.toDouble())
        return o
    }

    private fun readGeo(o: JSONObject): Geo = Geo(
        o.optDouble("cx", 0.85).toFloat(), o.optDouble("cy", 0.5).toFloat(),
        o.optDouble("tx", 0.5).toFloat(), o.optDouble("ty", 0.5).toFloat(),
        o.optDouble("ex", 0.6).toFloat(), o.optDouble("ey", 0.5).toFloat(),
        o.optDouble("size", 0.11).toFloat(), o.optDouble("rad", 0.12).toFloat()
    )

    private fun writeProfile(p: Profile): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        val arr = JSONArray()
        for (c in p.controls) {
            val j = JSONObject()
            j.put("id", c.id)
            j.put("kind", c.kind)
            j.put("key", c.key)
            j.put("kUp", c.kUp); j.put("kDown", c.kDown); j.put("kLeft", c.kLeft); j.put("kRight", c.kRight)
            j.put("holdMs", c.holdMs)
            j.put("intervalMs", c.intervalMs)
            j.put("swipeMs", c.swipeMs)
            j.put("speed", c.speed.toDouble())
            j.put("analogPad", c.analogPad)
            j.put("fixedPress", c.fixedPress)
            j.put("loopMode", c.loopMode)
            j.put("opacity", c.opacity.toDouble())
            j.put("hidden", c.hidden)
            j.put("port", writeGeo(c.port))
            j.put("land", writeGeo(c.land))
            val st = JSONArray()
            for (s in c.steps) {
                val so = JSONObject()
                so.put("t", s.type)
                so.put("x1", s.x1.toDouble()); so.put("y1", s.y1.toDouble())
                so.put("x2", s.x2.toDouble()); so.put("y2", s.y2.toDouble())
                so.put("d", s.dur)
                st.put(so)
            }
            j.put("steps", st)
            arr.put(j)
        }
        o.put("controls", arr)
        return o
    }

    private fun readProfile(o: JSONObject): Profile {
        val p = Profile(o.optString("id", newId()), o.optString("name", "Perfil"))
        val arr = o.optJSONArray("controls") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val c = Control(j.optString("id", newId()))
            c.kind = j.optInt("kind", 0)
            c.key = j.optInt("key", -1)
            c.kUp = j.optInt("kUp", -1); c.kDown = j.optInt("kDown", -1)
            c.kLeft = j.optInt("kLeft", -1); c.kRight = j.optInt("kRight", -1)
            c.holdMs = j.optInt("holdMs", 40)
            c.intervalMs = j.optInt("intervalMs", 70)
            c.swipeMs = j.optInt("swipeMs", 250)
            c.speed = j.optDouble("speed", 1.0).toFloat()
            c.analogPad = j.optBoolean("analogPad", true)
            c.fixedPress = j.optBoolean("fixedPress", true)
            c.loopMode = j.optInt("loopMode", 0)
            c.opacity = j.optDouble("opacity", 0.85).toFloat()
            c.hidden = j.optBoolean("hidden", false)
            j.optJSONObject("port")?.let { c.port = readGeo(it) }
            j.optJSONObject("land")?.let { c.land = readGeo(it) }
            val st = j.optJSONArray("steps")
            if (st != null) {
                for (k in 0 until st.length()) {
                    val so = st.getJSONObject(k)
                    c.steps.add(
                        Step(
                            so.optInt("t", 0),
                            so.optDouble("x1", 0.0).toFloat(), so.optDouble("y1", 0.0).toFloat(),
                            so.optDouble("x2", 0.0).toFloat(), so.optDouble("y2", 0.0).toFloat(),
                            so.optLong("d", 40)
                        )
                    )
                }
            }
            p.controls.add(c)
        }
        return p
    }
}

fun Control.deepCopy(newId: String): Control {
    val c = Control(newId)
    c.kind = kind
    c.key = key
    c.kUp = kUp
    c.kDown = kDown
    c.kLeft = kLeft
    c.kRight = kRight
    c.holdMs = holdMs
    c.intervalMs = intervalMs
    c.swipeMs = swipeMs
    c.speed = speed
    c.analogPad = analogPad
    c.fixedPress = fixedPress
    c.loopMode = loopMode
    c.opacity = opacity
    c.hidden = hidden
    c.port = port.copyGeo()
    c.land = land.copyGeo()
    for (s in steps) c.steps.add(Step(s.type, s.x1, s.y1, s.x2, s.y2, s.dur))
    return c
}
