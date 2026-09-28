package com.example.livesubtitle

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * 소리 → (폰 내장) 음성 인식 → 번역(온라인 Google, 실패 시 ML Kit 기기 내) → 자막 오버레이
 *
 * MODE_SYSTEM: 다른 앱에서 재생되는 소리를 AudioPlaybackCapture 로 가져와
 *              파이프로 음성 인식기에 직접 넣음 (Android 13+)
 * MODE_MIC:    음성 인식기가 마이크로 직접 들음
 */
class CaptionService : Service() {

    companion object {
        const val EXTRA_LANG = "lang"
        const val EXTRA_MODE = "mode"
        const val EXTRA_OFFLINE = "offline"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val MODE_SYSTEM = "system"
        const val MODE_MIC = "mic"
        private const val ACTION_STOP = "com.example.livesubtitle.STOP"
        private const val CHANNEL_ID = "caption"
        private const val SAMPLE_RATE = 16000
        private const val TAG = "LiveSubtitle"

        @Volatile
        var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: SubtitleOverlay? = null

    private lateinit var langTag: String
    private var useSystemAudio = false
    private var preferOffline = true
    private var usingOffline = false

    private var recognizer: SpeechRecognizer? = null
    private var restartPending = false
    private var consecutiveErrors = 0
    private var gotAnyResult = false

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var pipeOut: OutputStream? = null
    private var pipeReadEnd: ParcelFileDescriptor? = null

    private var translator: Translator? = null
    private var translatorReady = false

    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY // 이미 실행 중

        langTag = intent.getStringExtra(EXTRA_LANG) ?: "en-US"
        preferOffline = intent.getBooleanExtra(EXTRA_OFFLINE, true)
        useSystemAudio = intent.getStringExtra(EXTRA_MODE) == MODE_SYSTEM &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        // 포그라운드 서비스는 반드시 MediaProjection 을 얻기 전에 시작해야 함 (Android 14)
        try {
            startAsForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true

        overlay = SubtitleOverlay(this) { stopSelf() }.also {
            try {
                it.show()
            } catch (e: Exception) {
                Log.e(TAG, "overlay failed", e)
            }
        }
        overlay?.setStatus("준비 중…")

        if (useSystemAudio) {
            val ok = startSystemCapture(intent)
            if (!ok) {
                overlay?.setStatus("폰 소리를 가져오지 못해 마이크로 전환했어요.")
                useSystemAudio = false
            }
        }

        setupTranslator()
        createRecognizer()
        startListening()
        return START_NOT_STICKY
    }

    // ───────────────────────── 포그라운드 알림 ─────────────────────────
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "실시간 자막", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("실시간 자막 번역 중")
            .setContentText("자막 창을 두 번 탭하거나 '중지'를 누르면 끝나요.")
            .setContentIntent(openIntent)
            .addAction(0, "중지", stopIntent)
            .setOngoing(true)
            .build()

        val type = if (useSystemAudio) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, 1, notification, type)
    }

    // ───────────────────────── 폰 소리 캡처 ─────────────────────────
    @SuppressLint("MissingPermission")
    private fun startSystemCapture(intent: Intent): Boolean {
        return try {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                ?: return false
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data) ?: return false
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
            }, main)

            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }
            audioRecord = record
            record.startRecording()

            captureThread = thread(name = "audio-capture") {
                val buf = ByteArray(SAMPLE_RATE / 10 * 2) // 100ms
                while (!stopped) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val out = pipeOut ?: continue // 인식기 재시작 사이에는 버림
                    try {
                        out.write(buf, 0, n)
                    } catch (e: IOException) {
                        // 인식기가 한 문장을 끝내고 파이프를 닫음 → 다음 세션을 기다림
                        if (pipeOut === out) pipeOut = null
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "system capture failed", e)
            false
        }
    }

    // ───────────────────────── 음성 인식 ─────────────────────────
    private fun createRecognizer() {
        recognizer?.destroy()
        usingOffline = preferOffline &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val r = if (usingOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        r.setRecognitionListener(listener)
        recognizer = r
        Log.i(TAG, "recognizer created, offline=$usingOffline, systemAudio=$useSystemAudio")
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun startListening() {
        if (stopped) return
        restartPending = false
        val r = recognizer ?: return
        val intent = buildIntent()

        if (useSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 매 세션마다 새 파이프: 캡처 스레드가 write 끝에 쓰고, 인식기가 read 끝에서 읽음
            val pipe = ParcelFileDescriptor.createPipe()
            val oldOut = pipeOut
            val oldRead = pipeReadEnd
            pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            pipeReadEnd = pipe[0]
            runCatching { oldOut?.close() }
            runCatching { oldRead?.close() }

            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        }

        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            scheduleRestart(1000, recreate = true)
        }
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean = false) {
        if (stopped || restartPending) return
        restartPending = true
        main.postDelayed({
            if (stopped) return@postDelayed
            if (recreate) createRecognizer()
            startListening()
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!gotAnyResult && translatorReadyOrNotNeeded()) {
                overlay?.setStatus("듣는 중… 영상을 재생하세요")
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            if (text.isNotBlank()) overlay?.setOriginal(text)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val text = firstResult(results)?.trim()
            if (!text.isNullOrEmpty()) {
                gotAnyResult = true
                overlay?.setOriginal(text)
                translate(text)
            }
            scheduleRestart(50)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error $error")
            when (error) {
                // 말소리가 없었음 → 바로 다시 듣기
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(50)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    scheduleRestart(500)
                }

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (usingOffline) {
                        // 오프라인 언어팩이 없음 → 내려받기 요청하고 우선 온라인 인식으로 전환
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runCatching { recognizer?.triggerModelDownload(buildIntent()) }
                        }
                        preferOffline = false
                        overlay?.setStatus("오프라인 언어팩이 없어 온라인 인식으로 전환했어요.")
                        scheduleRestart(300, recreate = true)
                    } else {
                        overlay?.setStatus("이 폰의 음성 인식기가 이 언어를 지원하지 않아요.")
                        scheduleRestart(5000)
                    }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    overlay?.setStatus("마이크 권한이 필요해요. 앱에서 권한을 허용해 주세요.")
                }

                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 5) {
                        overlay?.setStatus("음성 인식 오류($error). 다시 시도하는 중…")
                    }
                    // 오프라인 인식기가 계속 실패하면 온라인으로 전환
                    if (consecutiveErrors == 8 && usingOffline) preferOffline = false
                    val delay = if (consecutiveErrors < 5) 300L else 2000L
                    scheduleRestart(delay, recreate = true)
                }
            }
        }
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ───────────────────────── 번역 ─────────────────────────
    // 1순위: 온라인 Google 번역 (키 불필요, 품질 좋음, 모델 다운로드 없음)
    // 2순위: ML Kit 기기 내 번역 (인터넷이 안 될 때)
    private val netExecutor = Executors.newSingleThreadExecutor()
    private var translateSeq = 0
    private var shownSeq = 0

    private fun sourceLanguage(): String? =
        TranslateLanguage.fromLanguageTag(langTag.substringBefore('-'))

    private fun translatorReadyOrNotNeeded() = true

    private fun setupTranslator() {
        val src = sourceLanguage()
        if (src == null || src == TranslateLanguage.KOREAN) return
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
        translator = t
        // 오프라인 대비용 모델은 뒤에서 조용히 내려받음
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true; Log.i(TAG, "ML Kit model ready") }
            .addOnFailureListener { Log.w(TAG, "ML Kit model download failed", it) }
    }

    private fun translate(text: String) {
        if (langTag.startsWith("ko")) {
            overlay?.setTranslated(text)
            return
        }
        val seq = ++translateSeq
        netExecutor.execute {
            val online = runCatching { translateOnline(text) }
                .onFailure { Log.w(TAG, "online translate failed", it) }
                .getOrNull()
            main.post {
                if (online != null) {
                    show(seq, online)
                } else {
                    translateOffline(seq, text)
                }
            }
        }
    }

    private fun translateOffline(seq: Int, text: String) {
        val t = translator
        if (t == null || !translatorReady) {
            show(seq, "(번역 실패: 인터넷 연결을 확인하세요)")
            return
        }
        t.translate(text)
            .addOnSuccessListener { show(seq, it) }
            .addOnFailureListener { show(seq, "(번역 실패)") }
    }

    /** 늦게 도착한 옛 번역이 새 번역을 덮어쓰지 않도록 순서 확인 */
    private fun show(seq: Int, translated: String) {
        if (stopped || seq < shownSeq) return
        shownSeq = seq
        overlay?.setTranslated(translated)
    }

    private fun translateOnline(text: String): String {
        val src = if (langTag.startsWith("zh")) langTag else langTag.substringBefore('-')
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=" + src + "&tl=ko&q=" + URLEncoder.encode(text, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parts = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val piece = parts.optJSONArray(i)?.optString(0).orEmpty()
                if (piece != "null") sb.append(piece)
            }
            val result = sb.toString().trim()
            if (result.isEmpty()) throw IOException("empty result")
            return result
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────────────── 정리 ─────────────────────────
    override fun onDestroy() {
        stopped = true
        isRunning = false
        main.removeCallbacksAndMessages(null)

        runCatching { recognizer?.destroy() }
        recognizer = null

        runCatching { pipeOut?.close() }
        runCatching { pipeReadEnd?.close() }
        pipeOut = null

        runCatching { audioRecord?.stop() }
        runCatching { captureThread?.join(500) }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { projection?.stop() }
        projection = null

        runCatching { netExecutor.shutdownNow() }
        runCatching { translator?.close() }
        overlay?.remove()
        overlay = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
