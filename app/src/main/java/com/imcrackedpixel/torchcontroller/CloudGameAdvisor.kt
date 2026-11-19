package com.imcrackedpixel.torchcontroller

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional cloud vision advisor. It never performs touches; it returns a short
 * suggestion which the local controller treats as untrusted context.
 */
object CloudGameAdvisor {
    private val inFlight = AtomicBoolean(false)
    private const val PREFS = "cloud_ai_settings"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "qwen/qwen3.8-27b"

    fun maybeAnalyze(context: Context, frame: Bitmap, ocr: List<String>, cues: List<String>, objects: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString("groq_api_key", "")?.trim().orEmpty()
        if (key.isBlank() || !inFlight.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_request_at", 0L) < 30000L) {
            inFlight.set(false)
            return
        }
        prefs.edit().putLong("last_request_at", now).apply()
        // Copy before returning: the capture service recycles its frame immediately.
        val scaled = Bitmap.createScaledBitmap(frame, minOf(frame.width, 960), (frame.height * (minOf(frame.width, 960).toFloat() / frame.width)).toInt().coerceAtLeast(1), true)
        val snapshot = scaled.copy(Bitmap.Config.ARGB_8888, false)
        if (scaled !== frame) scaled.recycle()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    snapshot.compress(Bitmap.CompressFormat.JPEG, 68, out)
                    out.toByteArray()
                }
                snapshot.recycle()
                val image = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val prompt = """You are Torch AI's game analyst. Inspect this mobile gameplay screenshot.
Return concise JSON only with keys: scene, likely_goal, suggested_next_step, confidence (0 to 1), evidence.
Do not claim certainty from ambiguous visuals. Prefer safe, reversible actions. Do not issue touch coordinates or pretend you can control the game.
Local OCR: ${ocr.take(20).joinToString(" | ").take(1200)}
Local cues: ${cues.joinToString(" | ").take(900)}
Object labels: ${objects.take(12).joinToString(" | ").take(700)}
Remember this is a screenshot, not a full game-state feed."""
                val body = JSONObject().put("model", MODEL).put("temperature", 0.2)
                    .put("max_tokens", 350)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))))))
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer " + key)
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                if (code !in 200..299) throw IllegalStateException("Groq HTTP $code: " + response.take(240))
                val content = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").trim()
                val clean = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val parsed = try { JSONObject(clean) } catch (_: Exception) { JSONObject().put("suggested_next_step", clean) }
                val advice = buildString {
                    append("Cloud vision: ")
                    append(parsed.optString("scene", "scene unclear"))
                    append(" | goal: ").append(parsed.optString("likely_goal", "uncertain"))
                    append(" | next: ").append(parsed.optString("suggested_next_step", "continue observing"))
                    append(" | confidence: ").append(parsed.opt("confidence")?.toString() ?: "not supplied")
                    append(" | evidence: ").append(parsed.optString("evidence", "not supplied"))
                }.take(1200)
                prefs.edit().putString("latest_advice", advice).putLong("last_success_at", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                prefs.edit().putString("last_error", (e.message ?: "Cloud analysis failed").take(240)).apply()
            } finally {
                if (!snapshot.isRecycled) snapshot.recycle()
                connection?.disconnect()
                inFlight.set(false)
            }
        }.start()
    }
}
