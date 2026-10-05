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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
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
 * [실시간 통역 모드] 소리 → Gemini Live 번역(원문 언어 자동 감지) → 자막
 *    연결이 안 되면 → 듣기 번역 모드로 전환
 * [듣기 번역 모드] 소리를 문장/6초 단위로 잘라 Gemini 가 직접 듣고 번역 → 자막
 *    키 오류 등으로 안 되면 → 기본 모드로 전환
 * [기본 모드] 소리 → (폰 내장) 음성 인식 → 초벌 번역(Google, 실패 시 ML Kit) → Gemini 다듬기(키가 있을 때) → 자막
 *
 * MODE_SYSTEM: 다른 앱에서 재생되는 소리를 AudioPlaybackCapture 로 가져와
 *              파이프로 음성 인식기에 직접 넣음 (Android 13+)
 * MODE_MIC:    음성 인식기가 마이크로 직접 들음
 */
class CaptionService : Service() {

    companion object {
        const val EXTRA_LANG = "lang"
        const val EXTRA_LANG_NAME = "langName"
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
        private val SENTENCE_END = setOf('.', '?', '!', '。', '？', '！', '…')

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
    @Volatile private var captureActive = false
    private var geminiKey = ""
    private var showOriginal = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY // 이미 실행 중

        langTag = intent.getStringExtra(EXTRA_LANG) ?: "en-US"
        preferOffline = intent.getBooleanExtra(EXTRA_OFFLINE, true)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val key = prefs.getString("geminiKey", "").orEmpty().trim()
        geminiKey = key
        val engine = prefs.getString("engine", "live")
        val wantLive = key.isNotEmpty() && engine == "live"
        val wantListen = key.isNotEmpty() && engine == "listen"
        if (key.isNotEmpty() && (wantLive || !langTag.startsWith("ko"))) {
            // 실시간 통역은 언어를 자동 감지하므로 언어 이름을 특정하지 않음
            val given = intent.getStringExtra(EXTRA_LANG_NAME) ?: langTag
            val name = if (wantLive || given == "자동 감지") "외국어" else given
            refiner = GeminiRefiner(key, name)
        }
        showOriginal = prefs.getBoolean("showOriginal", false)
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

        // Gemini 방식은 언어를 자동으로 알아내고, 기본 방식은 고른 언어로 인식
        val shownLanguage = if (wantLive || wantListen) "자동 감지" else
            (intent.getStringExtra(EXTRA_LANG_NAME)?.takeIf { it != "자동 감지" } ?: "영어")
        overlay = SubtitleOverlay(this, "$shownLanguage → 한국어", showOriginal) { stopSelf() }.also {
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

        if (wantLive || wantListen) {
            // Gemini 방식은 소리를 직접 보내야 하므로 마이크 모드에서도 직접 녹음
            if (!useSystemAudio) startMicCapture()
            if (wantLive) startLive() else startListen()
        } else {
            startClassic()
        }
        return START_NOT_STICKY
    }

    /** 기본 모드: 폰 음성 인식 → Google 초벌 → Gemini 다듬기 */
    private fun startClassic() {
        setupTranslator()
        createRecognizer()
        startListening()
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
            .setContentTitle("자막 번역 중")
            .setContentText(if (useSystemAudio) "폰 소리를 번역하고 있어요" else "마이크로 들은 소리를 번역하고 있어요")
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
            startCaptureLoop(record)
            true
        } catch (e: Exception) {
            Log.e(TAG, "system capture failed", e)
            false
        }
    }

    /** 마이크 녹음 (실시간 통역 모드 + 마이크로 듣기일 때) */
    @SuppressLint("MissingPermission")
    private fun startMicCapture() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            startCaptureLoop(record)
        } catch (e: Exception) {
            Log.e(TAG, "mic capture failed", e)
        }
    }

    /**
     * 100ms씩 읽어서
     *  - 실시간 통역 중이면 Gemini Live 로 전송
     *  - 아니면 음성 인식기 파이프로 전달
     */
    private fun startCaptureLoop(record: AudioRecord) {
        audioRecord = record
        captureActive = true
        record.startRecording()
        captureThread = thread(name = "audio-capture") {
            val buf = ByteArray(SAMPLE_RATE / 10 * 2) // 100ms
            // 인식기가 재시작하는 짧은 틈의 소리를 최대 1.5초 보관 → 문장 앞부분이 잘리지 않게
            val backlogMax = SAMPLE_RATE * 2 * 3 / 2
            var backlog = ByteArray(0)
            while (!stopped && captureActive) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val live = liveClient
                if (live != null) {
                    live.sendAudio(buf, n)
                    continue
                }
                val listen = audioTranslator
                if (usingListen && listen != null) {
                    listen.feed(buf, n)
                    continue
                }
                if (!usingLive && !usingListen) {
                    val out = pipeOut
                    if (out == null) {
                        backlog = (backlog + buf.copyOf(n)).takeLast(backlogMax)
                        continue
                    }
                    try {
                        if (backlog.isNotEmpty()) {
                            out.write(backlog)
                            backlog = ByteArray(0)
                        }
                        out.write(buf, 0, n)
                    } catch (e: IOException) {
                        // 인식기가 한 문장을 끝내고 파이프를 닫음 → 다음 세션까지 모아 둠
                        if (pipeOut === out) pipeOut = null
                        backlog = (backlog + buf.copyOf(n)).takeLast(backlogMax)
                    }
                }
            }
        }
    }

    private fun stopCapture() {
        captureActive = false
        usingLive = false
        usingListen = false
        runCatching { liveClient?.close() }
        liveClient = null
        runCatching { audioTranslator?.close() }
        audioTranslator = null
        stopCapture()
        captureThread = null
    }

    private fun ByteArray.takeLast(max: Int): ByteArray =
        if (size <= max) this else copyOfRange(size - max, size)

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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
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
            val text = firstResult(partialResults)?.trim() ?: return
            if (text.isEmpty()) return
            partialOriginal = text
            render()
            requestPartialTranslation(text)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val text = firstResult(results)?.trim()
            val carriedDraft = partialDraft
            // 이 문장의 중간 번역은 끝 → 다음 문장부터 새로
            utterance++
            pendingPartial = null
            lastPartialSent = ""
            partialOriginal = null
            partialDraft = null
            if (!text.isNullOrEmpty()) {
                gotAnyResult = true
                addLine(text, carriedDraft)
            } else {
                render()
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
    // 중간 번역·초벌 번역·Gemini 다듬기가 서로 기다리지 않도록 3개 스레드
    private val netExecutor = Executors.newFixedThreadPool(3)
    private var partialInFlight = false
    private var pendingPartial: String? = null
    private var lastPartialSent = ""

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

    // ── 자막 상태: 완성된 문장 목록 + 지금 말하는 중인 문장 ──
    private class Line(val original: String) {
        var draft: String? = null   // Google 초벌 번역
        var refined: String? = null // Gemini 다듬은 번역
        var fromLive = false        // 실시간 통역에서 온 줄
        var logged = false          // 기록 탭에 저장했는지
        val best get() = refined ?: draft
    }

    private val lines = ArrayList<Line>()
    private var utterance = 0
    private var partialOriginal: String? = null
    private var partialDraft: String? = null

    private fun addLine(text: String, carriedDraft: String?) {
        val line = Line(text)
        if (langTag.startsWith("ko")) {
            line.draft = text
        } else {
            line.draft = carriedDraft // 진짜 초벌 번역이 오기 전까지 중간 번역을 잠시 보여 줌
            translateDraft(line)
            enqueueRefine(line)
        }
        lines += line
        if (lines.size > 40) lines.removeAt(0)
        render()
    }

    /** 더 바뀔 일이 없는 줄(마지막 줄 제외)을 기록 탭에 저장 */
    private fun logFinishedLines(includeLast: Boolean = false) {
        val end = if (includeLast) lines.size else lines.size - 1
        for (i in 0 until end) {
            val l = lines[i]
            if (!l.logged) {
                l.logged = true
                l.best?.let { HistoryStore.add(this, "자막", it, l.original) }
            }
        }
    }

    private fun render() {
        logFinishedLines()
        val o = overlay ?: return
        val pOrig = partialOriginal
        if (pOrig != null) {
            o.render(lines.lastOrNull()?.best, pOrig, partialDraft ?: "", SubtitleOverlay.Tone.PARTIAL)
            return
        }
        val last = lines.lastOrNull() ?: return
        val tone = when {
            last.refined != null || refiner == null -> SubtitleOverlay.Tone.FINAL
            else -> SubtitleOverlay.Tone.DRAFT
        }
        o.render(lines.getOrNull(lines.size - 2)?.best, last.original, last.best ?: "…", tone)
    }

    /** 초벌 번역: 온라인 Google 번역, 안 되면 ML Kit */
    private fun translateDraft(line: Line) {
        netExecutor.execute {
            val online = runCatching { translateOnline(line.original) }
                .onFailure { Log.w(TAG, "online translate failed", it) }
                .getOrNull()
            main.post {
                if (online != null) {
                    line.draft = online
                    render()
                } else {
                    val t = translator
                    if (t != null && translatorReady) {
                        t.translate(line.original)
                            .addOnSuccessListener { line.draft = it; render() }
                    } else if (line.draft == null) {
                        line.draft = "(번역 실패: 인터넷 연결을 확인하세요)"
                        render()
                    }
                }
            }
        }
    }

    /**
     * 말하는 도중의 부분 인식 결과도 번역해서 먼저 보여 줌 (흐리게 표시).
     * 동시에 한 건만 요청하고, 그 사이 들어온 것은 가장 최신 것만 이어서 요청.
     */
    private fun requestPartialTranslation(text: String) {
        if (langTag.startsWith("ko")) {
            partialDraft = text
            render()
            return
        }
        if (text.length < 4 || text == lastPartialSent) return
        if (partialInFlight) {
            pendingPartial = text
            return
        }
        partialInFlight = true
        lastPartialSent = text
        val utt = utterance
        netExecutor.execute {
            val r = runCatching { translateOnline(text) }.getOrNull()
            main.post {
                // 그사이 문장이 끝났으면 버림
                if (r != null && utt == utterance && partialOriginal != null) {
                    partialDraft = r
                    render()
                }
                // 번역 서버에 너무 자주 요청하지 않도록 잠깐 쉬었다가 최신 것만 이어서 요청
                main.postDelayed({
                    partialInFlight = false
                    val next = pendingPartial
                    pendingPartial = null
                    if (next != null) requestPartialTranslation(next)
                }, 300)
            }
        }
    }

    // ── Gemini 다듬기: 문장들을 모아 4초에 한 번 이하로 보냄 (무료 한도: 1분 약 15회) ──
    private var refiner: GeminiRefiner? = null
    private val refineQueue = ArrayList<Line>()
    private var refineInFlight = false
    private var lastRefineAt = 0L
    private var warnedKey = false
    private val refineRunnable = Runnable { runRefine() }

    private fun enqueueRefine(line: Line) {
        if (refiner == null) return
        refineQueue += line
        scheduleRefine()
    }

    private fun scheduleRefine() {
        if (stopped || refineInFlight || refineQueue.isEmpty()) return
        main.removeCallbacks(refineRunnable)
        val since = SystemClock.elapsedRealtime() - lastRefineAt
        // 바로 이어지는 문장을 함께 묶도록 최소 1.2초는 모았다가 보냄
        main.postDelayed(refineRunnable, maxOf(1200L, 4000L - since))
    }

    private fun runRefine() {
        val r = refiner ?: return
        if (stopped || refineInFlight || refineQueue.isEmpty()) return
        val batch = refineQueue.take(8)
        refineQueue.subList(0, batch.size).clear()
        val firstIdx = lines.indexOf(batch.first())
        val context = if (firstIdx > 0) {
            lines.subList(maxOf(0, firstIdx - 6), firstIdx).map {
                GeminiRefiner.Context(it.original, it.best.orEmpty())
            }
        } else {
            emptyList()
        }
        val originals = batch.map { it.original }
        // 실시간 통역 줄은 초벌 번역(draft)도 함께 보내서 다듬게 함
        val drafts = if (batch.any { it.fromLive }) batch.map { it.draft } else null
        refineInFlight = true
        lastRefineAt = SystemClock.elapsedRealtime()
        netExecutor.execute {
            val result = runCatching { r.refine(context, originals, drafts) }.getOrNull()
            main.post {
                refineInFlight = false
                if (stopped) return@post
                result?.forEachIndexed { i, t -> if (t != null) batch[i].refined = t }
                if (r.keyInvalid && !warnedKey) {
                    warnedKey = true
                    Toast.makeText(this, "Gemini API 키가 올바르지 않아요. 앱에서 키를 확인해 주세요. (지금은 Google 번역만 사용)", Toast.LENGTH_LONG).show()
                }
                render()
                scheduleRefine()
            }
        }
    }

    private fun translateOnline(text: String): String {
        val src = if (langTag.startsWith("zh")) langTag else langTag.substringBefore('-')
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=" + src + "&tl=ko&q=" + URLEncoder.encode(text, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
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

    // ───────────────────────── Gemini 실시간 통역 ─────────────────────────
    @Volatile private var liveClient: LiveTranslateClient? = null
    @Volatile private var usingLive = false
    private var liveFailures = 0
    private var lastLiveError: String? = null
    private val liveIn = StringBuilder()
    private val liveOut = StringBuilder()
    private val liveSilenceRunnable = Runnable { finalizeLive() }

    private fun startLive() {
        if (stopped) return
        usingLive = true
        if (!gotAnyResult) overlay?.setStatus("Gemini 실시간 통역 연결 중…")
        lateinit var client: LiveTranslateClient
        // 번역 설정 위치를 번갈아 시도 (generationConfig 안 → setup 바로 아래 …), 성공한 방식은 기억
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val remembered = prefs.getInt("liveTranslationPlacement", -1)
        val inGenConfig = if (remembered >= 0 && liveFailures == 0) remembered == 1 else liveFailures % 2 == 0
        client = LiveTranslateClient(geminiKey, false, inGenConfig, object : LiveTranslateClient.Listener {
            override fun onReady() = main.post {
                if (liveClient === client) {
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .remove("lastLiveError")
                        .putInt("liveTranslationPlacement", if (inGenConfig) 1 else 0)
                        .apply()
                }
                if (liveClient === client && !gotAnyResult) {
                    overlay?.setStatus("실시간 통역 연결됨 · 영상을 재생하세요")
                }
            }.let { }

            override fun onInputText(delta: String) = main.post {
                if (liveClient !== client) return@post
                liveIn.append(delta)
                renderLive()
            }.let { }

            override fun onOutputText(delta: String) = main.post {
                if (liveClient !== client) return@post
                liveFailures = 0
                gotAnyResult = true
                liveOut.append(delta)
                // 문장이 충분히 길고 끝맺음 부호로 끝나면 한 줄로 확정
                val t = liveOut.trimEnd()
                if ((t.length > 40 && t.last() in SENTENCE_END) || t.length > 110) {
                    finalizeLive()
                } else {
                    renderLive()
                }
                // 2.5초 동안 번역이 더 안 오면 그 줄을 확정
                main.removeCallbacks(liveSilenceRunnable)
                main.postDelayed(liveSilenceRunnable, 2500)
            }.let { }

            override fun onTurnComplete() = main.post {
                if (liveClient === client) finalizeLive()
            }.let { }

            override fun onClosed(error: String?, gotOutput: Boolean) = main.post {
                onLiveClosed(client, error, gotOutput)
            }.let { }
        })
        liveClient = client
        client.connect()
    }

    private fun onLiveClosed(client: LiveTranslateClient, error: String?, gotOutput: Boolean) {
        if (stopped || liveClient !== client) return
        liveClient = null
        finalizeLive()
        if (gotOutput || error == null || error == "goAway") {
            // 잘 되다가 끊김(서버 세션 시간 제한 등) → 바로 다시 연결
            main.postDelayed({ if (!stopped && usingLive) startLive() }, 300)
            return
        }
        liveFailures++
        lastLiveError = error
        if (liveFailures < 4) {
            main.postDelayed({ if (!stopped && usingLive) startLive() }, 1500L * liveFailures)
            return
        }
        // 계속 실패 → 기본 모드로 전환
        usingLive = false
        Log.w(TAG, "Live failed, fallback: $error")
        // 앱 첫 화면에 사유를 보여 주기 위해 저장
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("lastLiveError", error ?: "알 수 없는 오류")
            .apply()
        Toast.makeText(
            this,
            "Gemini 실시간 통역에 연결하지 못해 '듣기 번역' 방식으로 전환했어요.\n사유: ${error?.take(120)}",
            Toast.LENGTH_LONG
        ).show()
        startListen()
    }

    // ───────────────────────── Gemini 듣기 번역 ─────────────────────────
    @Volatile private var audioTranslator: GeminiAudioTranslator? = null
    @Volatile private var usingListen = false

    private fun startListen() {
        if (stopped) return
        usingListen = true
        if (!gotAnyResult) overlay?.setStatus("Gemini 듣기 번역 중 · 영상을 재생하세요 (2~4초 늦게 나와요)")
        audioTranslator = GeminiAudioTranslator(geminiKey, object : GeminiAudioTranslator.Listener {
            override fun onSubtitles(lines: List<Pair<String, String>>) = main.post {
                if (!usingListen) return@post
                gotAnyResult = true
                lines.forEach { (src, ko) ->
                    this@CaptionService.lines += Line(src).apply { refined = ko }
                }
                while (this@CaptionService.lines.size > 40) this@CaptionService.lines.removeAt(0)
                render()
            }.let { }

            override fun onError(message: String, fatal: Boolean) = main.post {
                if (!usingListen || stopped) return@post
                if (!fatal) {
                    if (!gotAnyResult) overlay?.setStatus(message)
                    return@post
                }
                // 키 오류 등 → 기본 모드로
                usingListen = false
                audioTranslator?.close()
                audioTranslator = null
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("lastLiveError", "[듣기 번역] $message").apply()
                Toast.makeText(
                    this@CaptionService,
                    "Gemini 듣기 번역을 쓸 수 없어 기본 방식으로 전환했어요.\n$message",
                    Toast.LENGTH_LONG
                ).show()
                if (!useSystemAudio) stopCapture() // 마이크는 음성 인식기가 직접 사용
                startClassic()
            }.let { }
        })
    }

    private fun renderLive() {
        val o = overlay ?: return
        if (liveIn.isBlank() && liveOut.isBlank()) return
        o.render(
            lines.lastOrNull()?.best,
            liveIn.toString().trim(),
            liveOut.toString().trim().ifEmpty { "…" },
            SubtitleOverlay.Tone.DRAFT
        )
    }

    private fun finalizeLive() {
        main.removeCallbacks(liveSilenceRunnable)
        val out = liveOut.toString().trim()
        if (out.isNotEmpty()) {
            val line = Line(liveIn.toString().trim()).apply {
                draft = out
                fromLive = true
            }
            lines += line
            if (lines.size > 40) lines.removeAt(0)
            enqueueRefine(line) // 몇 초 뒤 자연스러운 문장으로 교체
        }
        liveIn.setLength(0)
        liveOut.setLength(0)
        if (out.isNotEmpty()) render()
    }

    // ───────────────────────── 정리 ─────────────────────────
    override fun onDestroy() {
        runCatching { logFinishedLines(includeLast = true) }
        stopped = true
        isRunning = false
        main.removeCallbacksAndMessages(null)

        runCatching { recognizer?.destroy() }
        recognizer = null

        runCatching { pipeOut?.close() }
        runCatching { pipeReadEnd?.close() }
        pipeOut = null

        usingLive = false
        runCatching { liveClient?.close() }
        liveClient = null
        stopCapture()

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
