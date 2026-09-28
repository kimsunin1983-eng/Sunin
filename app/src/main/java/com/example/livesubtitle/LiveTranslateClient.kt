package com.example.livesubtitle

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live 실시간 번역 (gemini-3.5-live-translate-preview) WebSocket 클라이언트.
 *
 * 소리(16kHz PCM)를 100ms 단위로 계속 보내면, 서버가
 *  - inputTranscription: 알아들은 원문
 *  - outputTranscription: 한국어 번역문
 * 을 조금씩 이어서 보내 준다. (번역 음성도 오지만 자막만 쓰므로 버림)
 * 원문 언어는 자동으로 감지된다.
 *
 * 콜백은 OkHttp 스레드에서 호출되므로 받는 쪽에서 메인 스레드로 넘겨야 함.
 */
class LiveTranslateClient(
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onInputText(delta: String)
        fun onOutputText(delta: String)
        fun onTurnComplete()
        /** error == null 이면 정상 종료. gotOutput: 이 연결에서 번역을 한 번이라도 받았는지 */
        fun onClosed(error: String?, gotOutput: Boolean)
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var gotOutput = false
    private val closed = AtomicBoolean(false)

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(if (code == 1000) null else "($code) $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(if (code == 1000) null else "($code) $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code} " }.orEmpty() + (t.message ?: t.javaClass.simpleName)
                finish(detail)
            }
        })
    }

    private fun setupMessage(): String {
        val setup = JSONObject()
            .put("model", "models/$MODEL")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put(
                        "translationConfig",
                        JSONObject()
                            .put("targetLanguageCode", "ko")
                            .put("echoTargetLanguage", true) // 한국어 대사는 그대로
                    )
            )
        return JSONObject().put("setup", setup).toString()
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (msg.has("setupComplete")) {
            ready = true
            listener.onReady()
            return
        }
        if (msg.has("goAway")) {
            // 서버가 곧 연결을 끊겠다고 알림 → 받는 쪽에서 재연결
            Log.i(TAG, "Live goAway")
            close()
            finish("goAway")
            return
        }
        val content = msg.optJSONObject("serverContent") ?: return
        content.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) listener.onInputText(it)
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) {
                gotOutput = true
                listener.onOutputText(it)
            }
        }
        if (content.optBoolean("turnComplete") || content.optBoolean("generationComplete")) {
            listener.onTurnComplete()
        }
    }

    /** 16kHz 16bit mono PCM */
    fun sendAudio(buf: ByteArray, len: Int) {
        if (!ready || closed.get()) return
        val b64 = Base64.encodeToString(buf, 0, len, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun close() {
        ready = false
        runCatching { ws?.close(1000, "bye") }
    }

    private fun finish(error: String?) {
        ready = false
        if (closed.compareAndSet(false, true)) {
            Log.i(TAG, "Live closed: $error (gotOutput=$gotOutput)")
            listener.onClosed(error, gotOutput)
            http.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        private const val TAG = "LiveSubtitle"
    }
}
