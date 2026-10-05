package com.example.livesubtitle

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 대화 통역 "안정" 방식.
 *
 * 한 사람이 말을 마칠 때마다(잠깐의 침묵으로 판단) 그 소리를 통째로 Gemini 에 보내서
 *  - 두 언어 중 어느 쪽으로 말했는지
 *  - 원문 받아쓰기
 *  - 상대 언어로 옮긴 번역
 * 을 한 번에 받는다. 문장마다 따로 판단하므로 앞에 말한 언어에 영향받지 않는다.
 */
class UtteranceTranslator(
    private val apiKey: String,
    private val nameA: String,
    private val nameB: String,
    private val listener: Listener,
) {
    interface Listener {
        /** 말소리를 듣는 중인지 */
        fun onSpeaking(speaking: Boolean)
        /** 번역을 기다리는 문장 수 */
        fun onPending(count: Int)
        /** speakerIsA: A 언어로 말했는지. src: 원문, out: 상대 언어 번역 */
        fun onResult(speakerIsA: Boolean, src: String, out: String)
        fun onError(message: String, fatal: Boolean)
    }

    // 빠른 모델 먼저. 응답이 없거나 한도에 걸리면 뒤로 보냄
    private val models = java.util.concurrent.CopyOnWriteArrayList(listOf("gemini-flash-lite-latest", "gemini-flash-latest"))
    private val noThinking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayDeque<String>()
    @Volatile private var closed = false
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    // ── 말소리 구간 자르기 (마이크 스레드) ──
    private val current = ByteArrayOutputStream()
    private val preroll = ArrayDeque<ByteArray>()
    private var inSpeech = false
    private var voicedChunks = 0
    private var silentChunks = 0
    private var loudRun = 0
    private var noise = 0.03f

    /** 16kHz 16bit mono PCM 100ms 조각과 그 소리 크기(0~1) */
    fun feed(buf: ByteArray, len: Int, level: Float) {
        if (closed) return
        val block = buf.copyOf(len)
        val threshold = maxOf(0.08f, noise * 2.5f)
        val loud = level > threshold

        if (!inSpeech) {
            noise = noise * 0.95f + level * 0.05f // 주변 소음 크기를 천천히 따라감
            preroll.addLast(block)
            while (preroll.size > 4) preroll.removeFirst()
            loudRun = if (loud) loudRun + 1 else 0
            if (loudRun >= 2) { // 0.2초 연속으로 소리가 나면 말 시작
                inSpeech = true
                voicedChunks = loudRun
                silentChunks = 0
                current.reset()
                preroll.forEach { current.write(it) }
                preroll.clear()
                listener.onSpeaking(true)
            }
            return
        }

        current.write(block)
        if (loud) {
            voicedChunks++
            silentChunks = 0
        } else {
            silentChunks++
        }
        val tooLong = current.size() >= MAX_BYTES
        if (silentChunks >= END_SILENCE_CHUNKS || tooLong) {
            val pcm = current.toByteArray()
            val voiced = voicedChunks
            current.reset()
            inSpeech = false
            loudRun = 0
            listener.onSpeaking(false)
            if (voiced >= 3) submit(pcm) // 0.3초 미만은 잡음으로 보고 버림
        }
    }

    private fun submit(pcm: ByteArray) {
        listener.onPending(pending.incrementAndGet())
        worker.execute {
            try {
                if (!closed) translate(pcm)
            } finally {
                listener.onPending(pending.decrementAndGet())
            }
        }
    }

    private fun translate(pcm: ByteArray) {
        val wavB64 = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
        for (model in models.toList()) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val (code, body) = post(model, wavB64)
                when {
                    code == 200 -> {
                        parse(body)?.let { (isA, src, out) ->
                            history.addLast("${if (isA) "A" else "B"}: $src  →  $out")
                            while (history.size > 6) history.removeFirst()
                            listener.onResult(isA, src, out)
                        }
                        return
                    }
                    code == 401 || code == 403 || (code == 400 && body.contains("API_KEY", ignoreCase = true)) -> {
                        closed = true
                        listener.onError("API 키 오류 (HTTP $code): ${shortError(body)}", fatal = true)
                        return
                    }
                    code == 400 && model !in noThinking -> {
                        noThinking += model // 이 모델이 thinkingConfig 를 거절 → 빼고 다시
                        continue
                    }
                    code == 429 || code == 404 || code >= 500 || code == -1 -> {
                        Log.w(TAG, "utterance $model HTTP $code: ${body.take(200)}")
                        if (models.size > 1 && models.first() == model) {
                            models.remove(model); models.add(model)
                        }
                        break
                    }
                    else -> {
                        listener.onError("Gemini 오류 (HTTP $code): ${shortError(body)}", fatal = false)
                        return
                    }
                }
            }
        }
        listener.onError("Gemini가 지금 응답하지 않아요 (한도 초과 또는 서버 혼잡). 잠시 뒤 다시 말해 보세요.", fatal = false)
    }

    private fun post(model: String, wavB64: String): Pair<Int, String> {
        val system = """
            You are an interpreter device placed between two people. You are NOT a participant in their conversation.
            Speaker A speaks $nameA. Speaker B speaks $nameB.

            Listen to the audio clip and return JSON:
            - "lang": "A" if the speech is in $nameA, "B" if it is in $nameB, "NONE" if there is no intelligible speech (silence, noise, music) or it is in neither language.
            - "src": the exact transcript of what was said, in the language it was spoken.
            - "out": a natural, conversational translation into the OTHER person's language ($nameB if lang is A, $nameA if lang is B). Keep the speaker's tone and politeness level. Fix obvious speech-recognition slips using context.

            Rules:
            - Decide the language from this clip only. A loanword or a name does not change the language of the whole sentence.
            - Never answer questions or add comments. If the speaker asks "How are you?", translate the question; do not reply to it.
            - Do not add explanations, notes, or romanization. "out" contains only the translation.
        """.trimIndent()
        val context = if (history.isEmpty()) "" else
            "Earlier in this conversation (for context only, do not translate again):\n" + history.joinToString("\n") + "\n\n"

        val generationConfig = JSONObject()
            .put("temperature", 0.2)
            .put("responseMimeType", "application/json")
            .put(
                "responseSchema",
                JSONObject().put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("lang", JSONObject().put("type", "STRING").put("enum", JSONArray().put("A").put("B").put("NONE")))
                            .put("src", JSONObject().put("type", "STRING"))
                            .put("out", JSONObject().put("type", "STRING"))
                    )
                    .put("required", JSONArray().put("lang").put("src").put("out"))
            )
        if (model !in noThinking) generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))

        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", wavB64)))
            .put(JSONObject().put("text", context + "Interpret this clip."))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", generationConfig)

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
            Log.w(TAG, "utterance request failed", e)
            -1 to (e.message ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** (A 언어로 말했는지, 원문, 번역). 말소리가 아니면 null */
    private fun parse(body: String): Triple<Boolean, String, String>? = runCatching {
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        val raw = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = JSONObject(raw)
        val lang = o.optString("lang")
        val out = o.optString("out").trim()
        if ((lang != "A" && lang != "B") || out.isEmpty()) null
        else Triple(lang == "A", o.optString("src").trim(), out)
    }.onFailure { Log.w(TAG, "utterance parse failed: ${body.take(300)}", it) }.getOrNull()

    fun close() {
        closed = true
        worker.shutdownNow()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(16000); int(32000); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun shortError(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            ?: body.take(150)

    companion object {
        private const val TAG = "LiveSubtitle"
        private const val END_SILENCE_CHUNKS = 7        // 0.7초 조용하면 말이 끝난 것으로
        private const val MAX_BYTES = 32 * 15_000       // 한 번에 최대 15초
    }
}
