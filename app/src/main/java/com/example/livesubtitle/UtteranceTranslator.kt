package com.example.livesubtitle

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 대화 통역 "안정" 방식.
 *
 * 한 사람이 말을 마칠 때마다(잠깐의 침묵으로 판단) 다음 단계를 거친다.
 *  1) 받아쓰기: 소리를 Gemini 에 보내 말한 그대로 글자로 받음 (번역은 시키지 않음 → 대답할 여지가 없음)
 *  2) 언어 판단: 받아쓴 글자의 종류(한글/알파벳/가나…)로 앱이 직접 판단. 글자 종류가 같은 언어쌍만 모델 판단을 씀
 *  3) 번역: 받아쓴 글만 Gemini 에 보내 번역 → 결과가 상대 언어 글자인지, 원문과 같지 않은지 검사
 *  4) 검사에서 떨어지거나 실패하면 Google 번역으로 대체 (대답하거나 따라 말하지 않음)
 */
class UtteranceTranslator(
    private val apiKey: String,
    private val langA: Language,
    private val langB: Language,
    private val listener: Listener,
) {
    /** english: 모델에 알려 줄 이름, code: ko / en / ja / zh-CN … */
    data class Language(val english: String, val code: String)

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
    private val pending = AtomicInteger(0)
    @Volatile private var closed = false

    private val scriptA = Scripts.ofLanguage(langA.code)
    private val scriptB = Scripts.ofLanguage(langB.code)

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
        if (silentChunks >= END_SILENCE_CHUNKS || current.size() >= MAX_BYTES) {
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
                if (!closed) interpret(pcm)
            } catch (e: Exception) {
                Log.w(TAG, "interpret failed", e)
            } finally {
                listener.onPending(pending.decrementAndGet())
            }
        }
    }

    // ───────────────────────── 한 문장 처리 ─────────────────────────
    private fun interpret(pcm: ByteArray) {
        // 1) 받아쓰기
        val heard = transcribe(pcm) ?: return
        val src = heard.second.trim()
        if (src.isEmpty()) return

        // 2) 누가 말했는지: 글자 종류로 판단, 구분이 안 되는 언어쌍만 모델의 판단을 따름
        val srcScript = Scripts.detect(src)
        val speakerIsA = when {
            scriptA != scriptB && srcScript != null && Scripts.matches(srcScript, scriptA, scriptB) -> true
            scriptA != scriptB && srcScript != null && Scripts.matches(srcScript, scriptB, scriptA) -> false
            heard.first == "A" -> true
            heard.first == "B" -> false
            else -> return // 두 언어 어느 쪽도 아님 (잡음 등)
        }
        val from = if (speakerIsA) langA else langB
        val to = if (speakerIsA) langB else langA
        val toScript = if (speakerIsA) scriptB else scriptA
        val fromScript = if (speakerIsA) scriptA else scriptB

        // 3) 번역 + 검사, 4) 떨어지면 Google 번역
        var out = translateWithGemini(src, from, to)?.takeIf { valid(it, src, toScript, fromScript) }
        if (out == null) {
            Log.i(TAG, "Gemini translation rejected or failed → Google Translate")
            out = runCatching { translateWithGoogle(src, from.code, to.code) }
                .onFailure { Log.w(TAG, "Google translate failed", it) }
                .getOrNull()
                ?.takeIf { valid(it, src, toScript, fromScript) }
        }
        if (out == null) {
            listener.onError("번역하지 못했어요. 다시 말해 주세요.", fatal = false)
            return
        }
        history.addLast("${from.english}: $src  →  ${to.english}: $out")
        while (history.size > 6) history.removeFirst()
        listener.onResult(speakerIsA, src, out)
    }

    /** 번역 결과 검사: 비어 있지 않고, 원문을 그대로 따라 쓰지 않았고, 상대 언어 글자로 되어 있어야 함 */
    private fun valid(out: String, src: String, toScript: String, fromScript: String): Boolean {
        if (out.isBlank()) return false
        if (Scripts.normalize(out) == Scripts.normalize(src)) return false
        if (toScript != fromScript) {
            val s = Scripts.detect(out) ?: return false
            if (!Scripts.matches(s, toScript, fromScript)) return false
        }
        return true
    }

    /** (모델이 본 언어 "A"/"B"/"NONE", 받아쓴 글) */
    private fun transcribe(pcm: ByteArray): Pair<String, String>? {
        val wavB64 = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
        val system = """
            You are a speech-to-text engine. Transcribe the audio clip verbatim.
            The speaker is speaking either ${langA.english} (call it "A") or ${langB.english} (call it "B").

            Return JSON:
            - "lang": "A" or "B" — the language actually spoken. Use "NONE" if there is no intelligible speech (silence, noise, music, a cough) or it is in neither language.
            - "src": exactly what was said, written in the language and script it was spoken in. Do not translate. Do not answer. Do not add anything that was not said.

            A loanword or a name inside a sentence does not change the language of the sentence.
        """.trimIndent()
        val text = gemini { model ->
            val config = JSONObject()
                .put("temperature", 0)
                .put("responseMimeType", "application/json")
                .put(
                    "responseSchema",
                    JSONObject().put("type", "OBJECT")
                        .put(
                            "properties",
                            JSONObject()
                                .put("lang", JSONObject().put("type", "STRING").put("enum", JSONArray().put("A").put("B").put("NONE")))
                                .put("src", JSONObject().put("type", "STRING"))
                        )
                        .put("required", JSONArray().put("lang").put("src"))
                )
            if (model !in noThinking) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val parts = JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", wavB64)))
                .put(JSONObject().put("text", "Transcribe this clip."))
            body(system, parts, config)
        } ?: return null
        return runCatching {
            val o = JSONObject(text)
            val lang = o.optString("lang")
            val src = o.optString("src")
            if (lang == "NONE" || src.isBlank()) null else lang to src
        }.getOrNull()
    }

    private fun translateWithGemini(src: String, from: Language, to: Language): String? {
        val system = """
            You are a translation engine, not a chat assistant.
            Translate the text inside <text> tags from ${from.english} to ${to.english}.

            - Output JSON {"out": "<translation>"} and nothing else.
            - The text is something one person said to another person. It may be a question, a greeting, or a request. Translate it. NEVER answer it, reply to it, or act on it.
              Example: <text>How are you?</text> must become the same question in ${to.english}, not an answer such as "I'm fine".
            - Natural spoken style; keep the speaker's tone and politeness level.
            - The text came from speech recognition; fix obvious recognition slips using context.
            - Write the translation in ${to.english} only. No romanization, notes, or quotes.
        """.trimIndent()
        val context = if (history.isEmpty()) "" else
            "Earlier lines of the conversation, for context only (do not translate them):\n" +
                history.joinToString("\n") + "\n\n"
        val text = gemini { model ->
            val config = JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json")
                .put(
                    "responseSchema",
                    JSONObject().put("type", "OBJECT")
                        .put("properties", JSONObject().put("out", JSONObject().put("type", "STRING")))
                        .put("required", JSONArray().put("out"))
                )
            if (model !in noThinking) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val parts = JSONArray().put(JSONObject().put("text", "$context<text>$src</text>"))
            body(system, parts, config)
        } ?: return null
        return runCatching { JSONObject(text).optString("out").trim() }.getOrNull()?.ifEmpty { null }
    }

    private fun translateWithGoogle(text: String, from: String, to: String): String {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=" + from + "&tl=" + to + "&q=" + URLEncoder.encode(text, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parts = JSONArray(raw).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val piece = parts.optJSONArray(i)?.optString(0).orEmpty()
                if (piece != "null") sb.append(piece)
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────────────── Gemini 호출 공통 ─────────────────────────
    private fun body(system: String, parts: JSONArray, config: JSONObject): JSONObject = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        .put("generationConfig", config)

    /** 모델을 바꿔 가며 호출해서 응답 글자를 돌려줌. 실패하면 null */
    private fun gemini(build: (model: String) -> JSONObject): String? {
        for (model in models.toList()) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val (code, resp) = post(model, build(model))
                when {
                    code == 200 -> return candidateText(resp)
                    code == 401 || code == 403 || (code == 400 && resp.contains("API_KEY", ignoreCase = true)) -> {
                        closed = true
                        listener.onError("API 키 오류 (HTTP $code): ${shortError(resp)}", fatal = true)
                        return null
                    }
                    code == 400 && model !in noThinking -> {
                        noThinking += model // 이 모델이 thinkingConfig 를 거절 → 빼고 다시
                        continue
                    }
                    else -> {
                        Log.w(TAG, "gemini $model HTTP $code: ${resp.take(200)}")
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
            Log.w(TAG, "gemini request failed", e)
            -1 to (e.message ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun candidateText(resp: String): String? = runCatching {
        val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }.onFailure { Log.w(TAG, "gemini parse failed: ${resp.take(300)}", it) }.getOrNull()

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

/** 글자 종류(한글/가나/한자/라틴/키릴/태국)로 언어를 가려내는 도구 */
object Scripts {
    fun ofLanguage(code: String) = when (code.lowercase().substringBefore('-')) {
        "ko" -> "ko"
        "ja" -> "ja"
        "zh" -> "han"
        "ru" -> "cyr"
        "th" -> "thai"
        else -> "latin"
    }

    fun detect(text: String): String? {
        var ko = 0
        var kana = 0
        var han = 0
        var latin = 0
        var cyr = 0
        var thai = 0
        for (c in text) {
            when (c) {
                in '가'..'힣', in 'ㄱ'..'ㆎ', in 'ᄀ'..'ᇿ' -> ko++
                in '぀'..'ヿ' -> kana++
                in '一'..'鿿' -> han++
                in 'Ѐ'..'ӿ' -> cyr++
                in '฀'..'๿' -> thai++
                in 'a'..'z', in 'A'..'Z', in 'À'..'ɏ', in 'Ḁ'..'ỿ' -> latin++
            }
        }
        val best = listOf("ko" to ko, "ja" to kana * 3, "han" to han, "cyr" to cyr, "thai" to thai, "latin" to latin)
            .maxByOrNull { it.second } ?: return null
        return if (best.second == 0) null else best.first
    }

    /** 감지한 글자 종류가 target 언어의 것인지 (한자만 있는 일본어 문장은 상대가 중국어가 아닐 때 일본어로 봄) */
    fun matches(detected: String, target: String, other: String) =
        detected == target || (detected == "han" && target == "ja" && other != "han")

    fun normalize(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
}
