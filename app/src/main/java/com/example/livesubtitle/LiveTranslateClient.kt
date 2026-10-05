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
    /** false: 키를 x-goog-api-key 헤더로 전송 (새 AQ. 형식 키는 이 방식만 됨), true: 주소 뒤 ?key= */
    private val keyInQuery: Boolean,
    /** translationConfig 를 generationConfig 안에 넣을지(true), setup 바로 아래에 넣을지(false) */
    private val translationInGenerationConfig: Boolean,
    private val listener: Listener,
    /** 번역해서 내보낼 언어 코드 (ko, en, ja …) */
    private val targetLanguage: String = "ko",
    /** true: 이미 그 언어로 말한 것도 그대로 따라 말함, false: 조용히 있음 */
    private val echoTarget: Boolean = true,
    /** 쓸 모델. 기본은 번역 전용 모델 */
    private val model: String = MODEL,
    /** 주어지면 번역 전용 설정 대신 이 지시문을 따르는 일반 실시간 모델로 동작 (대화 통역용) */
    private val systemInstruction: String? = null,
    /** 말하는 도중 다른 소리가 들려도 하던 말을 끊지 않게 함 */
    private val noInterruption: Boolean = false,
    /** 번역을 말할 목소리 이름 (Kore, Aoede, Puck, Charon …). null 이면 모델 기본 */
    private val voiceName: String? = null,
    /** 말하는 사람의 감정·어조에 맞춰 말하기 (지원하는 모델에서만) */
    private val affectiveDialog: Boolean = false,
) {
    interface Listener {
        fun onReady()
        fun onInputText(delta: String)
        fun onOutputText(delta: String)
        fun onTurnComplete()
        /** error == null 이면 정상 종료. gotOutput: 이 연결에서 번역을 한 번이라도 받았는지 */
        fun onClosed(error: String?, gotOutput: Boolean)
        /** 번역된 음성 (24kHz 16bit mono PCM). 자막만 쓸 때는 무시 */
        fun onAudio(pcm: ByteArray) {}
        /** 방금 들은 말의 언어 코드 (서버가 알려 줄 때만) */
        fun onInputLanguage(code: String) {}
        /** 모델이 하던 말을 중간에 끊음 → 재생 중인 소리를 비워야 함 */
        fun onInterrupted() {}
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
        val base = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val request = if (keyInQuery) {
            Request.Builder().url("$base?key=$apiKey").build()
        } else {
            Request.Builder().url(base).header("x-goog-api-key", apiKey).build()
        }
        ws = http.newWebSocket(request, object : WebSocketListener() {
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

    private fun setupMessage(): String =
        buildSetup(
            translationInGenerationConfig, targetLanguage, echoTarget, model, systemInstruction, noInterruption,
            voiceName, affectiveDialog
        ).toString()

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
        if (content.optBoolean("interrupted")) listener.onInterrupted()
        content.optJSONObject("inputTranscription")?.let { t ->
            t.optString("languageCode").let { if (it.isNotEmpty()) listener.onInputLanguage(it) }
            t.optString("text").let { if (it.isNotEmpty()) listener.onInputText(it) }
        }
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                if (data.isNotEmpty()) {
                    runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()?.let { listener.onAudio(it) }
                }
            }
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) {
                gotOutput = true
                listener.onOutputText(it)
            }
        }
        // generationComplete(생성만 끝남)는 끝이 아님. 뒤늦게 오는 원문 받아쓰기가 있으므로 turnComplete 만 끝으로 봄
        if (content.optBoolean("turnComplete")) listener.onTurnComplete()
    }

    /** 16kHz 16bit mono PCM */
    fun sendAudio(buf: ByteArray, len: Int) {
        if (!ready || closed.get()) return
        val b64 = Base64.encodeToString(buf, 0, len, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    /** 설정이 끝나 소리를 받을 준비가 됐는지 */
    val isReady: Boolean get() = ready && !closed.get()

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

        /** 받아쓰기(input/outputAudioTranscription)는 setup 바로 아래 항목 */
        fun buildSetup(
            translationInGenerationConfig: Boolean,
            targetLanguage: String = "ko",
            echoTarget: Boolean = true,
            model: String = MODEL,
            systemInstruction: String? = null,
            noInterruption: Boolean = false,
            voiceName: String? = null,
            affectiveDialog: Boolean = false,
        ): JSONObject {
            val translation = JSONObject()
                .put("targetLanguageCode", targetLanguage)
                .put("echoTargetLanguage", echoTarget)
            val generationConfig = JSONObject()
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
            if (!voiceName.isNullOrBlank()) {
                generationConfig.put(
                    "speechConfig",
                    JSONObject().put(
                        "voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))
                    )
                )
            }
            if (affectiveDialog) generationConfig.put("enableAffectiveDialog", true)
            val setup = JSONObject()
                .put("model", "models/$model")
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
            if (noInterruption) {
                setup.put(
                    "realtimeInputConfig",
                    JSONObject()
                        .put("activityHandling", "NO_INTERRUPTION") // 번역을 말하는 도중 끊지 않음
                        .put(
                            "automaticActivityDetection",
                            JSONObject()
                                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH") // 작은 목소리도 말의 시작으로
                                .put("prefixPaddingMs", 300) // 말의 첫머리가 잘리지 않게 앞부분을 넉넉히
                                // 0.65초 조용하면 말이 끝난 것으로. 더 짧으면 말을 고르며 천천히 하는 사람의 문장이 중간에 끊김
                                .put("silenceDurationMs", 800) // 숨 고르는 사이에 문장이 잘려 엉뚱하게 번역되지 않게
                        )
                )
            }
            if (systemInstruction != null) {
                // 일반 실시간 모델: 지시문으로 역할을 정함
                setup.put(
                    "systemInstruction",
                    JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", systemInstruction)))
                )
            } else if (translationInGenerationConfig) {
                generationConfig.put("translationConfig", translation)
            } else {
                setup.put("translationConfig", translation)
            }
            setup.put("generationConfig", generationConfig)
            return JSONObject().put("setup", setup)
        }
        private const val TAG = "LiveSubtitle"
    }
}
