package com.example.livesubtitle

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 짧은 소리를 "이 언어로 말한 것"이라고 알려 주고 받아쓰게 하는 도구.
 * 실시간 통역의 자동 받아쓰기가 언어를 잘못 짚었을 때 대화 기록의 원문을 바로잡는 데 씀.
 */
class Transcriber(private val apiKey: String) {

    private val models = java.util.concurrent.CopyOnWriteArrayList(listOf("gemini-flash-lite-latest", "gemini-flash-latest"))
    private val noThinking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** pcm: 16kHz 16bit mono. 실패하거나 말소리가 없으면 null */
    fun transcribe(pcm: ByteArray, languageEnglish: String): String? {
        val wavB64 = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
        val system = "You are a speech-to-text engine. The speaker is speaking $languageEnglish. " +
            "Write exactly what was said, in $languageEnglish, in its normal script. " +
            "Do not translate, do not answer, do not add anything. " +
            "If other languages or voices are also audible, ignore them. " +
            "If there is no intelligible $languageEnglish speech, output an empty string."
        for (model in models.toList()) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val config = JSONObject().put("temperature", 0)
                if (model !in noThinking) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put("role", "user").put(
                                "parts",
                                JSONArray()
                                    .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", wavB64)))
                                    .put(JSONObject().put("text", "Transcribe."))
                            )
                        )
                    )
                    .put("generationConfig", config)
                val (code, resp) = post(model, body)
                when {
                    code == 200 -> return text(resp)
                    code == 400 && model !in noThinking && !resp.contains("API_KEY", ignoreCase = true) -> {
                        noThinking += model
                        continue
                    }
                    else -> {
                        Log.w(TAG, "transcriber $model HTTP $code: ${resp.take(200)}")
                        if (models.size > 1 && models.first() == model) {
                            models.remove(model)
                            models.add(model)
                        }
                        break
                    }
                }
            }
        }
        return null
    }

    private fun post(model: String, body: JSONObject): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun text(resp: String): String? = runCatching {
        val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        sb.toString().trim().trim('"').ifEmpty { null }
    }.getOrNull()

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(16000); int(32000); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "LiveSubtitle"
    }
}
