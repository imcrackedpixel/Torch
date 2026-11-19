package com.imcrackedpixel.torchcontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Offline coarse minimap memory. Tracks candidate marker positions and explored
 * cells from the upper-left minimap crop; this is not a full collision map.
 */
object MinimapNavigation {
    private const val PREFS = "torch_minimap_memory"
    private const val GRID = 12

    fun observe(context: Context, bitmap: Bitmap): String {
        if (bitmap.width < 200 || bitmap.height < 200) return ""
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val left = (bitmap.width * 0.035f).toInt().coerceIn(0, bitmap.width - 2)
        val right = (bitmap.width * 0.205f).toInt().coerceIn(left + 1, bitmap.width)
        val top = (bitmap.height * 0.105f).toInt().coerceIn(0, bitmap.height - 2)
        val bottom = (bitmap.height * 0.385f).toInt().coerceIn(top + 1, bitmap.height)
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 500)
        var bx = 0.0; var by = 0.0; var bn = 0
        var yx = 0.0; var yy = 0.0; var yn = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (b > 145 && b > r * 1.22 && b > g * 1.03 && r < 190 && g < 225) { bx += x; by += y; bn++ }
                if (r > 185 && g > 125 && g < 225 && b < 115 && r > b * 1.7) { yx += x; yy += y; yn++ }
                x += step
            }
            y += step
        }
        val now = System.currentTimeMillis()
        val edit = p.edit().putLong("last_scan_at", now)
        var note = "Minimap scanned"
        if (bn >= 3) {
            val px = ((bx / bn - left) / (right - left)).toFloat().coerceIn(0f, 1f)
            val py = ((by / bn - top) / (bottom - top)).toFloat().coerceIn(0f, 1f)
            edit.putFloat("player_x", px).putFloat("player_y", py).putLong("player_seen_at", now)
            val key = "${((px * GRID).toInt()).coerceIn(0, GRID - 1)},${((py * GRID).toInt()).coerceIn(0, GRID - 1)}"
            val cells = safeObject(p.getString("visited_cells", "{}"))
            val cell = cells.optJSONObject(key) ?: JSONObject()
            cell.put("visits", cell.optInt("visits", 0) + 1).put("last_seen", now)
            cells.put(key, cell)
            edit.putString("visited_cells", cells.toString())
            note += " · player marker candidate"
        }
        if (yn >= 3) {
            edit.putFloat("target_x", ((yx / yn - left) / (right - left)).toFloat())
                .putFloat("target_y", ((yy / yn - top) / (bottom - top)).toFloat()).putLong("target_seen_at", now)
            note += " · yellow target candidate"
        }
        edit.apply()
        return note
    }

    data class MarkerSnapshot(val x: Float, val y: Float, val seenAt: Long)

    fun snapshot(context: Context): MarkerSnapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("player_seen_at", 0L)
        if (seen == 0L || System.currentTimeMillis() - seen > 5000L) return null
        return MarkerSnapshot(p.getFloat("player_x", 0f), p.getFloat("player_y", 0f), seen)
    }

    /** Null means the minimap marker was not confidently tracked for this test. */
    fun didPlayerMove(context: Context, before: MarkerSnapshot?): Boolean? {
        if (before == null) return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("player_seen_at", 0L)
        if (seen <= before.seenAt || System.currentTimeMillis() - seen > 5000L) return null
        val dx = p.getFloat("player_x", before.x) - before.x
        val dy = p.getFloat("player_y", before.y) - before.y
        return (dx * dx + dy * dy) > 0.00009f
    }

    fun suggestedDirection(context: Context): Int? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("player_seen_at", 0L) > 9000L || now - p.getLong("target_seen_at", 0L) > 9000L) return null
        val dx = p.getFloat("target_x", 0f) - p.getFloat("player_x", 0f)
        val dy = p.getFloat("target_y", 0f) - p.getFloat("player_y", 0f)
        if (abs(dx) + abs(dy) < 0.012f) return null
        val angle = atan2(dy.toDouble(), dx.toDouble())
        return (((angle / (Math.PI / 4.0)).roundToInt() + 2) % 8 + 8) % 8
    }

    fun report(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cells = safeObject(p.getString("visited_cells", "{}"))
        val fresh = System.currentTimeMillis() - p.getLong("player_seen_at", 0L) < 9000L
        val target = System.currentTimeMillis() - p.getLong("target_seen_at", 0L) < 9000L
        return "Minimap memory: ${cells.length()} explored cells/$GRID×$GRID · player marker: " +
            (if (fresh) "candidate tracked" else "not confidently found") + " · target marker: " +
            (if (target) "candidate tracked" else "not confidently found")
    }

    private fun safeObject(value: String?): JSONObject = try { JSONObject(value ?: "{}") } catch (_: Exception) { JSONObject() }
}
