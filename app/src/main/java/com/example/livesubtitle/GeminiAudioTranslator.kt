package com.example.livesubtitle

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * "Gemini 듣기 번역" 모드.
 * 소리를 말 끊김(또는 최대 6초) 단위로 잘라 Gemini 에 직접 보내면,
 * Gemini 가 듣고 받아쓴 원문과 자연스러운 한국어 자막을 함께 돌려준다.
 *
 * - 무료 한도(1분 약 15회)를 넘지 않도록 요청은 4초에 한 번 이하, 그 사이 쌓인 소리는 합쳐서 보냄
 * - 조용한 구간은 보내지 않음
 * - 한도 초과 시 Lite 모델로 재시도, 둘 다 막히면 잠시 쉼
 */
class GeminiAudioTranslator(
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        /** (원문, 한국어) 목록. 메인 스레드가 아님 */
        fun onSubtitles(lines: List<Pair<String, String>>)
        /** fatal=true 면 이 방식으로는 계속할 수 없음(키 오류 등) */
        fun onError(message: String, fatal: Boolean)
    }

    // 응답이 없거나 한도에 걸린 모델은 뒤로 보내서 다음부터 다른 모델을 먼저 씀
    private val models = java.util.concurrent.CopyOnWriteArrayList(listOf("gemini-flash-latest", "gemini-flash-lite-latest"))

    private fun demote(model: String) {
        if (models.size > 1 && models.first() == model) {
            models.remove(model)
            models.add(model)
        }
    }
    private val worker = Executors.newSingleThreadScheduledExecutor()

    // ── 소리 자르기 (캡처 스레드에서 호출) ──
    private val current = ByteArrayOutputStream()
    private var preroll = ByteArray(0)
    private var hasSpeech = false
    private var silenceMs = 0
    private var lengthMs = 0

    // ── 보내기 대기열 ──
    // 소리 조각은 캡처 스레드가 pending 에 바로 넣고(20초 넘으면 오래된 것부터 버림),
    // 보내는 일은 worker 한 곳에서만 한다. 서버 응답을 기다리는 동안에도 제한이 계속 적용된다.
    private val pending = ArrayList<ByteArray>()
    private val pumping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastSendAt = 0L
    @Volatile private var cooldownUntil = 0L
    private val history = ArrayDeque<Pair<String, String>>()

    // "빠르게 답하기(thinkingBudget 0)" 설정을 거절한 모델 목록 → 이 모델엔 설정 없이 보냄
    private val noThinking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var closed = false

    /** 16kHz 16bit mono PCM, 100ms 단위 */
    fun feed(buf: ByteArray, len: Int) {
        if (closed) return
        val block = buf.copyOf(len)
        val speech = rms(block) > SPEECH_RMS
        val blockMs = len / 32 // 16000Hz * 2byte = 32 byte/ms

        if (!hasSpeech) {
            if (!speech) {
                preroll = (preroll + block).let { if (it.size > PREROLL_BYTES) it.copyOfRange(it.size - PREROLL_BYTES, it.size) else it }
                return
            }
            hasSpeech = true
            current.write(preroll)
            lengthMs = preroll.size / 32
            preroll = ByteArray(0)
        }
        current.write(block)
        lengthMs += blockMs
        silenceMs = if (speech) 0 else silenceMs + blockMs

        val sentenceEnded = silenceMs >= 500 && lengthMs >= 1000
        if (sentenceEnded || lengthMs >= MAX_CHUNK_MS) {
            val chunk = current.toByteArray()
            current.reset()
            hasSpeech = false
            silenceMs = 0
            lengthMs = 0
            synchronized(pending) {
                pending += chunk
                // 너무 쌓이면 오래된 소리부터 버림 (지연 방지)
                while (pending.sumOf { it.size } > MAX_PENDING_BYTES && pending.size > 1) pending.removeAt(0)
            }
            if (pumping.compareAndSet(false, true)) {
                runCatching { worker.execute { pump() } }.onFailure { pumping.set(false) }
            }
        }
    }

    /** 쌓인 소리를 모아 4초에 한 번 이하로 보냄. worker 스레드에서만 실행 */
    private fun pump() {
        try {
            while (!closed) {
                val wait = maxOf(lastSendAt + MIN_INTERVAL_MS, cooldownUntil) - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                val pcm = synchronized(pending) {
                    if (pending.isEmpty()) return
                    val out = ByteArrayOutputStream()
                    pending.forEach { out.write(it) }
                    pending.clear()
                    out.toByteArray()
                }
                lastSendAt = System.currentTimeMillis()
                val result = request(pcm)
                if (closed) return
                if (!result.isNullOrEmpty()) {
                    result.forEach {
                        history.addLast(it)
                        while (history.size > 6) history.removeFirst()
                    }
                    listener.onSubtitles(result)
                }
            }
        } catch (e: InterruptedException) {
            // 종료
        } finally {
            pumping.set(false)
            // 마지막 확인과 플래그 해제 사이에 들어온 조각이 있으면 다시 시작
            val more = synchronized(pending) { pending.isNotEmpty() }
            if (more && !closed && pumping.compareAndSet(false, true)) {
                runCatching { worker.execute { pump() } }.onFailure { pumping.set(false) }
            }
        }
    }

    /** 성공 시 자막 목록(말이 없으면 빈 목록), 실패 시 null */
    private fun request(pcm: ByteArray): List<Pair<String, String>>? {
        val wavB64 = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
        for (model in models.toList()) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val (code, body) = post(model, wavB64)
                when {
                    code == 200 -> return parse(body)
                    code == 400 && model !in noThinking && !body.contains("API_KEY", ignoreCase = true) -> {
                        // 이 모델이 thinkingConfig 를 거절 → 빼고 다시
                        noThinking += model
                        continue
                    }
                    code == 401 || code == 403 || (code == 400 && body.contains("API_KEY", ignoreCase = true)) -> {
                        listener.onError("키 오류 (HTTP $code): ${shortError(body)}", fatal = true)
                        closed = true
                        return null
                    }
                    code == 429 || code == 404 || code >= 500 || code == -1 -> {
                        Log.w(TAG, "Gemini audio $model HTTP $code: ${body.take(200)}")
                        demote(model)
                        break
                    }
                    else -> {
                        listener.onError("Gemini 오류 (HTTP $code): ${shortError(body)}", fatal = false)
                        return null
                    }
                }
            }
        }
        cooldownUntil = System.currentTimeMillis() + 15_000
        listener.onError("Gemini 무료 한도 초과 또는 연결 문제 — 15초 뒤 다시 시도", fatal = false)
        return null
    }

    private fun post(model: String, wavB64: String): Pair<Int, String> {
        val contextText = if (history.isEmpty()) "" else buildString {
            append("앞 자막 (참고용, 다시 출력하지 말 것):\n")
            history.forEach { append("- ").append(it.first).append("  →  ").append(it.second).append('\n') }
        }
        val generationConfig = JSONObject()
            .put("temperature", 0.3)
            .put("responseMimeType", "application/json")
            .put(
                "responseSchema",
                JSONObject().put("type", "ARRAY").put(
                    "items",
                    JSONObject().put("type", "OBJECT")
                        .put(
                            "properties",
                            JSONObject()
                                .put("src", JSONObject().put("type", "STRING"))
                                .put("ko", JSONObject().put("type", "STRING"))
                        )
                        .put("required", JSONArray().put("src").put("ko"))
                )
            )
        if (model !in noThinking) generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))

        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", wavB64)))
            .put(JSONObject().put("text", contextText + "위 오디오의 대사를 받아쓰고 한국어 자막으로 옮겨 주세요."))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
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
            Log.w(TAG, "Gemini audio request failed", e)
            -1 to (e.message ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Pair<String, String>>? = runCatching {
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        val raw = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ko = o.optString("ko").trim()
            if (ko.isEmpty()) null else o.optString("src").trim() to ko
        }
    }.onFailure { Log.w(TAG, "Gemini audio parse failed: ${body.take(300)}", it) }.getOrNull()

    fun close() {
        closed = true
        worker.shutdownNow()
    }

    private fun rms(b: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < b.size) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / maxOf(1, b.size / 2))
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
        private const val SPEECH_RMS = 400.0
        private const val PREROLL_BYTES = 32 * 300      // 0.3초
        private const val MAX_CHUNK_MS = 6000
        private const val MIN_INTERVAL_MS = 4000L
        private const val MAX_PENDING_BYTES = 32 * 20_000 // 20초

        private val SYSTEM_PROMPT = """
            당신은 영상 자막 전문 번역가입니다. 영상에서 잘라 낸 짧은 오디오를 듣고, 말하는 내용을 받아쓴 뒤 한국 시청자가 보는 자연스러운 한국어 자막으로 옮깁니다.

            규칙:
            - 언어는 스스로 알아낸다 (일본어, 중국어, 영어 등). 한국어 대사는 그대로 적는다.
            - 직역하지 말고 한국 드라마·예능 자막처럼 자연스러운 구어체로 옮긴다.
            - 앞 자막의 흐름, 말투(반말/존댓말), 호칭을 이어 간다.
            - 오디오는 문장 중간에서 시작하거나 끝날 수 있다. 앞 자막과 이어지는 부분은 자연스럽게 이어 번역한다.
            - 군말과 불필요한 반복은 빼고, 자막답게 짧고 한눈에 읽히게 쓴다. 한 항목은 한 문장 정도.
            - 숫자·날짜·금액·단위, 부정 표현(아니다/없다/못 한다), 고유명사는 들은 그대로 옮기고 바꾸지 않는다.
            - 잘 안 들린 부분을 그럴듯하게 지어내지 않는다. 확실히 들은 부분만 옮긴다.
            - 배경음악, 노래 가사, 효과음, 알아들을 수 없는 소리는 무시한다.
            - 말소리가 없으면 빈 배열 []을 출력한다.
            - 출력: [{"src": 원문, "ko": 한국어 자막}, ...] JSON 배열만. 설명은 쓰지 않는다.
        """.trimIndent()
    }
}
