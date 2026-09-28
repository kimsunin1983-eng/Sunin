package com.example.livesubtitle

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini(무료 API 키)로 초벌 번역된 자막을 자연스러운 한국어로 다듬는다.
 * 여러 문장을 한 번에 보내고, 앞 대사를 문맥으로 함께 준다.
 *
 * - 기본 모델이 한도(429)에 걸리면 Lite 모델로 한 번 더 시도 (한도가 따로 계산됨)
 * - 둘 다 막히면 잠시 쉬고, 그동안은 초벌 번역이 그대로 표시됨
 */
class GeminiRefiner(private val apiKey: String, private val sourceLanguageName: String) {

    data class Context(val original: String, val translated: String)

    private val models = listOf("gemini-flash-latest", "gemini-flash-lite-latest")

    @Volatile private var sendThinkingConfig = true
    @Volatile private var cooldownUntil = 0L
    @Volatile var keyInvalid = false
        private set

    private val systemPrompt = """
        당신은 영상 자막 전문 번역가입니다. $sourceLanguageName 영상의 음성 인식 결과를 한국 시청자가 보는 자연스러운 한국어 자막으로 옮깁니다.

        규칙:
        - 단어를 하나하나 옮기는 직역을 하지 말고, 한국 드라마·예능 자막처럼 자연스러운 구어체로 옮긴다.
        - 입력은 음성 인식 결과라서 잘못 알아들은 단어, 빠진 조사, 중간에 끊긴 문장이 있다. 문맥으로 원래 뜻을 추측해 바로잡는다.
        - 앞 대사의 흐름, 말투(반말/존댓말), 인물 사이의 호칭을 일관되게 유지한다.
        - 군말(음, 어, えーと, 那个 등)과 불필요한 반복은 뺀다. 자막답게 짧고 한눈에 읽히게 쓴다.
        - 인명·지명·작품명은 한국에서 통용되는 표기를 쓴다.
        - '번역할 대사'의 줄 수와 정확히 같은 개수의 문자열을 JSON 배열로만 출력한다. 줄을 합치거나 나누지 않는다. 설명은 쓰지 않는다. 뜻이 없는 줄은 빈 문자열로 둔다.
    """.trimIndent()

    /** 성공하면 lines 와 같은 길이의 목록(빈 칸은 null), 실패하면 null */
    fun refine(context: List<Context>, lines: List<String>): List<String?>? {
        if (keyInvalid || System.currentTimeMillis() < cooldownUntil) return null
        val prompt = buildPrompt(context, lines)

        for (model in models) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val (code, body) = post(model, prompt)
                when {
                    code == 200 -> return parse(body, lines.size)
                    code == 400 && sendThinkingConfig && body.contains("thinking", ignoreCase = true) -> {
                        // 모델이 thinkingConfig 를 모르면 빼고 다시
                        sendThinkingConfig = false
                        continue
                    }
                    code == 400 && body.contains("API_KEY_INVALID") || code == 401 || code == 403 -> {
                        keyInvalid = true
                        return null
                    }
                    code == 429 || code == 404 || code >= 500 -> break // 다음 모델로
                    else -> {
                        Log.w(TAG, "Gemini $model HTTP $code: ${body.take(300)}")
                        return null
                    }
                }
            }
        }
        // 모든 모델이 한도 초과 → 20초 쉬기 (그동안은 초벌 번역 유지)
        cooldownUntil = System.currentTimeMillis() + 20_000
        return null
    }

    private fun buildPrompt(context: List<Context>, lines: List<String>): String = buildString {
        if (context.isNotEmpty()) {
            append("[앞 대사 — 참고용, 번역하지 말 것]\n")
            context.forEach { append("- ").append(it.original).append("  →  ").append(it.translated).append('\n') }
            append('\n')
        }
        append("[번역할 대사 ${lines.size}줄]\n")
        lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
    }

    private fun post(model: String, prompt: String): Pair<Int, String> {
        val generationConfig = JSONObject()
            .put("temperature", 0.3)
            .put("responseMimeType", "application/json")
            .put(
                "responseSchema",
                JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
            )
        if (sendThinkingConfig) {
            // 빠른 응답을 위해 '생각하기' 끄기
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("generationConfig", generationConfig)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to text
        } catch (e: Exception) {
            Log.w(TAG, "Gemini request failed", e)
            -1 to ""
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, expected: Int): List<String?>? = runCatching {
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        val raw = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(raw)
        if (arr.length() != expected) Log.w(TAG, "Gemini returned ${arr.length()} lines, expected $expected")
        List(expected) { i -> if (i < arr.length()) arr.optString(i).trim().ifEmpty { null } else null }
    }.onFailure { Log.w(TAG, "Gemini parse failed: ${body.take(300)}", it) }.getOrNull()

    companion object {
        private const val TAG = "LiveSubtitle"
    }
}
