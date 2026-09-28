package com.example.livesubtitle

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** '연결 진단' 버튼: 키와 각 Gemini 기능이 실제로 되는지 검사 (백그라운드 스레드에서 호출) */
object Diagnostics {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    fun run(key: String): String = buildString {
        append("키 형식: ").append(if (key.startsWith("AQ.")) "새 형식(AQ.)" else if (key.startsWith("AIza")) "기존 형식(AIza)" else "알 수 없음")
            .append(", 길이 ").append(key.length).append("\n\n")

        // 1) 모델 목록
        val (code, body) = get("$BASE/models?pageSize=1000", key)
        if (code == 200) {
            val models = runCatching { JSONObject(body).getJSONArray("models") }.getOrNull()
            val names = (0 until (models?.length() ?: 0)).map { models!!.getJSONObject(it) }
            append("✅ 키 확인 / 모델 목록: ").append(names.size).append("개\n")
            val live = names.filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                methods.contains("bidiGenerateContent")
            }.map { it.optString("name").removePrefix("models/") }
            append("   실시간(Live) 지원 모델: ").append(if (live.isEmpty()) "없음" else live.joinToString(", ")).append('\n')
            append("   번역 전용 모델(").append(LiveTranslateClient.MODEL).append("): ")
                .append(if (live.contains(LiveTranslateClient.MODEL)) "있음" else "목록에 없음").append("\n")
        } else {
            append("❌ 모델 목록 (HTTP ").append(code).append("): ").append(err(body)).append('\n')
        }

        // 2) 일반 요청 (다듬기·듣기 번역에 쓰는 방식)
        for (model in listOf("gemini-flash-latest", "gemini-flash-lite-latest")) {
            val (c, b) = post("$BASE/models/$model:generateContent", key,
                """{"contents":[{"parts":[{"text":"OK 라고만 답해"}]}]}""")
            if (c == 200) append("✅ ").append(model).append(" 일반 요청 성공\n")
            else append("❌ ").append(model).append(" (HTTP ").append(c).append("): ").append(err(b)).append('\n')
        }

        // 3) 실시간 통역 연결
        append('\n')
        append("실시간 통역 (키를 헤더로): ").append(liveTest(key, false)).append('\n')
        append("실시간 통역 (키를 주소로): ").append(liveTest(key, true)).append('\n')
    }

    private fun liveTest(key: String, inQuery: Boolean): String {
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val base = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val req = if (inQuery) Request.Builder().url("$base?key=$key").build()
        else Request.Builder().url(base).header("x-goog-api-key", key).build()
        val done = CountDownLatch(1)
        var result = "⏱ 10초 동안 응답 없음"
        val setup = JSONObject().put(
            "setup", JSONObject()
                .put("model", "models/${LiveTranslateClient.MODEL}")
                .put(
                    "generationConfig", JSONObject()
                        .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put("translationConfig", JSONObject().put("targetLanguageCode", "ko").put("echoTargetLanguage", true))
                )
        ).toString()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = check(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = check(bytes.utf8())
            private fun check(t: String) {
                result = if (t.contains("setupComplete")) "✅ 연결 성공" else "응답: ${t.take(200)}"
                done.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                result = "❌ 서버가 연결을 닫음 ($code) $reason"
                done.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                result = "❌ 연결 실패 " + (response?.let { "HTTP ${it.code} " } ?: "") +
                    (t.message ?: t.javaClass.simpleName) + (if (body.isNotBlank()) " / ${err(body)}" else "")
                done.countDown()
            }
        })
        done.await(10, TimeUnit.SECONDS)
        runCatching { ws.close(1000, null) }
        http.dispatcher.executorService.shutdown()
        return result
    }

    private fun get(url: String, key: String): Pair<Int, String> = http(url, key, null)
    private fun post(url: String, key: String, json: String): Pair<Int, String> = http(url, key, json)

    private fun http(url: String, key: String, json: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 10000
            conn.setRequestProperty("x-goog-api-key", key)
            if (json != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun err(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            ?: body.take(200)
}
