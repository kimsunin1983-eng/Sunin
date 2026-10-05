package com.example.livesubtitle

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 대화 통역: 무선 이어폰을 한쪽씩 나눠 끼고 서로 다른 언어로 대화.
 * 폰 마이크로 두 사람 말을 듣고, 번역을 "듣는 사람" 쪽 이어폰에만 들려준다.
 *
 * 방식 두 가지:
 *  - 안정(문장 단위): 말이 끝날 때마다 그 소리를 Gemini 에 보내 언어 판별+번역 → 폰 음성 합성(TTS)으로 한쪽 귀에 읽어 줌.
 *    문장마다 따로 판단하므로 언어가 번갈아 나와도 흔들리지 않음.
 *  - 빠름(실시간): 대화용 실시간 모델 하나에 통역사 역할을 지시. 모델이 말한 번역 음성을 한쪽 귀에 재생.
 *
 * (번역 전용 모델 gemini-3.5-live-translate 는 한 방향 전용이고 연결 후 처음 들은 언어에 묶여서 대화에는 쓰지 않음)
 */
class ConversationActivity : AppCompatActivity() {

    /** (표시 이름, 언어 코드, 모델에 알려 줄 영어 이름) */
    private data class Lang(val name: String, val code: String, val english: String)

    private val languages = listOf(
        Lang("한국어", "ko", "Korean"),
        Lang("영어", "en", "English"),
        Lang("일본어", "ja", "Japanese"),
        Lang("중국어 (간체)", "zh-CN", "Mandarin Chinese (Simplified)"),
        Lang("중국어 (번체 · 대만)", "zh-TW", "Mandarin Chinese (Traditional, Taiwan)"),
        Lang("스페인어", "es", "Spanish"),
        Lang("프랑스어", "fr", "French"),
        Lang("독일어", "de", "German"),
        Lang("베트남어", "vi", "Vietnamese"),
        Lang("태국어", "th", "Thai"),
        Lang("인도네시아어", "id", "Indonesian"),
        Lang("러시아어", "ru", "Russian"),
        Lang("필리핀어 (타갈로그)", "fil", "Filipino (Tagalog)"),
    )

    /** 이어폰 한쪽을 낀 사람 */
    private inner class Side(val isLeft: Boolean) {
        var lang = languages[0]
        lateinit var titleView: TextView
        lateinit var stateView: TextView
        val script get() = scriptOfLanguage(lang.code)
    }

    private val left = Side(true)
    private val right = Side(false)
    private fun other(side: Side) = if (side.isLeft) right else left

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var spinnerLeft: Spinner
    private lateinit var spinnerRight: Spinner
    private lateinit var buttonTalk: View
    private lateinit var iconTalk: ImageView
    private lateinit var status: TextView
    private lateinit var bubbles: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var wave: WaveView
    private lateinit var chipWarn: View
    private lateinit var segSteady: TextView
    private lateinit var segFast: TextView

    @Volatile private var running = false
    @Volatile private var micActive = false
    private var fastMode = false
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var apiKey = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else toast("대화를 듣기 위해 '마이크' 권한이 필요해요.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)

        spinnerLeft = findViewById(R.id.spinnerLeft)
        spinnerRight = findViewById(R.id.spinnerRight)
        buttonTalk = findViewById(R.id.buttonTalk)
        iconTalk = findViewById(R.id.iconTalk)
        status = findViewById(R.id.textStatus)
        bubbles = findViewById(R.id.bubbles)
        scroll = findViewById(R.id.scrollLog)
        wave = findViewById(R.id.wave)
        chipWarn = findViewById(R.id.chipWarn)
        segSteady = findViewById(R.id.segSteady)
        segFast = findViewById(R.id.segFast)
        left.titleView = findViewById(R.id.textLeftTitle)
        left.stateView = findViewById(R.id.textLeftState)
        right.titleView = findViewById(R.id.textRightTitle)
        right.stateView = findViewById(R.id.textRightState)

        val adapter = ArrayAdapter(this, R.layout.item_spinner, languages.map { it.name }).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerLeft.adapter = adapter
        spinnerRight.adapter = adapter
        spinnerLeft.setSelection(prefs.getInt("talkLeft", 1).coerceIn(0, languages.lastIndex))   // 영어
        spinnerRight.setSelection(prefs.getInt("talkRight", 0).coerceIn(0, languages.lastIndex)) // 한국어
        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateCards()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerLeft.onItemSelectedListener = onPick
        spinnerRight.onItemSelectedListener = onPick

        fastMode = prefs.getString("talkMode2", "fast") == "fast" // 기본은 실시간
        segSteady.setOnClickListener { setMode(false) }
        segFast.setOnClickListener { setMode(true) }
        renderMode()

        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<View>(R.id.buttonSwap).setOnClickListener {
            if (running) {
                toast("통역을 종료한 뒤 바꿀 수 있어요.")
            } else {
                val l = spinnerLeft.selectedItemPosition
                spinnerLeft.setSelection(spinnerRight.selectedItemPosition)
                spinnerRight.setSelection(l)
            }
        }
        buttonTalk.setOnClickListener {
            if (running) stop("통역을 멈췄어요. 마이크 버튼을 누르면 다시 시작해요.") else start()
        }
        findViewById<View>(R.id.buttonEnd).setOnClickListener {
            if (running) stop("통역을 종료했어요.")
            finish()
        }
        updateCards()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        if (running) stop("화면을 벗어나 통역을 멈췄어요.")
    }

    override fun onDestroy() {
        runCatching { tts?.shutdown() }
        tts = null
        super.onDestroy()
    }

    private fun setMode(fast: Boolean) {
        if (running) {
            toast("통역을 종료한 뒤 바꿀 수 있어요.")
            return
        }
        fastMode = fast
        prefs.edit().putString("talkMode2", if (fast) "fast" else "steady").apply()
        renderMode()
    }

    private fun renderMode() {
        val on = ContextCompat.getColor(this, R.color.teal)
        val off = ContextCompat.getColor(this, R.color.textDim)
        listOf(segSteady to !fastMode, segFast to fastMode).forEach { (v, selected) ->
            if (selected) v.setBackgroundResource(R.drawable.bg_segment_on) else v.background = null
            v.setTextColor(if (selected) on else off)
        }
        if (!running) {
            status.text = if (fastMode) {
                "실시간: 말이 끝나면 바로 번역이 들려요"
            } else {
                "문장 단위: 단계별로 확인해 정확하지만 3~5초 걸려요"
            }
        }
    }

    // ───────────────────────── 시작 / 중지 ─────────────────────────
    private fun start() {
        apiKey = prefs.getString("geminiKey", "").orEmpty().trim()
        if (apiKey.isEmpty()) {
            toast("먼저 설정에서 Gemini API 키를 입력해 주세요.")
            return
        }
        if (spinnerLeft.selectedItemPosition == spinnerRight.selectedItemPosition) {
            toast("왼쪽과 오른쪽 언어를 다르게 골라 주세요.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        prefs.edit()
            .putInt("talkLeft", spinnerLeft.selectedItemPosition)
            .putInt("talkRight", spinnerRight.selectedItemPosition)
            .apply()
        left.lang = languages[spinnerLeft.selectedItemPosition]
        right.lang = languages[spinnerRight.selectedItemPosition]

        running = true
        if (fastMode) startFast() else startSteady()
        if (!startMic()) {
            stop("마이크를 열지 못했어요.")
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spinnerLeft.isEnabled = false
        spinnerRight.isEnabled = false
        setTalkButton(active = true)
        updateStatus()
    }

    private fun stop(message: String) {
        running = false
        micActive = false
        main.removeCallbacksAndMessages(null)
        runCatching { record?.stop() }
        runCatching { captureThread?.join(500) }
        runCatching { record?.release() }
        record = null

        runCatching { utterance?.close() }
        utterance = null
        runCatching { tts?.stop() }
        hearing = false
        waiting = 0

        runCatching { live?.close() }
        live = null
        liveReady = false
        runCatching { player?.shutdownNow() }
        player = null
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spinnerLeft.isEnabled = true
        spinnerRight.isEnabled = true
        setTalkButton(active = false)
        wave.clear()
        updateStatus()
        status.text = message
    }

    private fun setTalkButton(active: Boolean) {
        buttonTalk.setBackgroundResource(if (active) R.drawable.bg_circle_teal else R.drawable.bg_circle_ring)
        iconTalk.setColorFilter(ContextCompat.getColor(this, if (active) R.color.onTeal else R.color.teal))
    }

    // ───────────────────────── 마이크 (폰 본체 마이크) ─────────────────────────
    @SuppressLint("MissingPermission")
    private fun startMic(): Boolean = try {
        val minBuf = AudioRecord.getMinBufferSize(
            IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, IN_RATE, // 떨어진 거리의 말소리 인식용
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, IN_RATE * 2)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            false
        } else {
            // 이어폰 마이크가 아니라 폰 본체 마이크로 듣기 (이어폰 마이크를 쓰면 좌우 분리가 안 됨)
            val am = getSystemService(AudioManager::class.java)
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { r.preferredDevice = it }
            record = r
            micActive = true
            r.startRecording()
            captureThread = thread(name = "talk-mic") {
                val buf = ByteArray(IN_RATE / 10 * 2) // 100ms
                while (micActive) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0 || !running) continue
                    boost(buf, n)
                    val lv = level(buf, n)
                    wave.push(lv)
                    if (lv > 0.1f) {
                        lastSpeechAt = SystemClock.elapsedRealtime()
                        unansweredSpeechMs += 100
                    }
                    // 이어폰 없이 폰 스피커로 번역이 나오는 동안에는 그 소리를 다시 듣지 않도록 쉼
                    if (speakerPlaying()) continue
                    utterance?.feed(buf, n, lv)
                    live?.sendAudio(buf, n)
                }
            }
            true
        }
    } catch (e: Exception) {
        false
    }

    @Volatile private var headphones = true
    @Volatile private var lastPlayAt = 0L

    /** 이어폰이 없어서 번역 소리가 폰 스피커로 나오고 있는 중인지 */
    private fun speakerPlaying(): Boolean {
        if (headphones) return false
        if (tts?.isSpeaking == true) return true
        return SystemClock.elapsedRealtime() - lastPlayAt < 600
    }

    // 최근 가장 큰 소리 크기 (천천히 줄어듦). 목소리가 작게 들어오면 이 값이 작아져서 더 많이 키움
    private var peakEnvelope = 4000f

    /** 폰에서 떨어져 말해 작게 들어온 목소리를 최대 4배까지 키움 (제자리에서 수정) */
    private fun boost(buf: ByteArray, n: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 2
        }
        peakEnvelope = maxOf(peak.toFloat(), peakEnvelope * 0.97f, 600f)
        val gain = (12000f / peakEnvelope).coerceIn(1f, 4f)
        if (gain <= 1.05f) return
        i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            val g = (v * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = g.toByte()
            buf[i + 1] = (g shr 8).toByte()
            i += 2
        }
    }

    /** 소리 크기를 0~1 로 */
    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v
            i += 2
        }
        val rms = Math.sqrt(sum / maxOf(1, n / 2))
        return (rms / 3500.0).toFloat().coerceIn(0f, 1f)
    }

    // ═════════════════════════ 안정 방식 (문장 단위) ═════════════════════════
    @Volatile private var utterance: UtteranceTranslator? = null
    @Volatile private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsSeq = 0
    private var warnedTts = false
    private var hearing = false
    private var waiting = 0

    private fun startSteady() {
        if (tts == null) {
            tts = TextToSpeech(applicationContext) { result ->
                ttsReady = result == TextToSpeech.SUCCESS
                if (!ttsReady) ui { toast("이 폰의 음성 합성(TTS)을 쓸 수 없어요. 번역은 화면 글자로만 보여요.") }
            }
        }
        // A = 왼쪽 사람의 언어, B = 오른쪽 사람의 언어
        utterance = UtteranceTranslator(
            apiKey,
            UtteranceTranslator.Language(left.lang.english, left.lang.code),
            UtteranceTranslator.Language(right.lang.english, right.lang.code),
            object : UtteranceTranslator.Listener {
                override fun onSpeaking(speaking: Boolean) = ui {
                    hearing = speaking
                    refreshSteadyStatus()
                }

                override fun onPending(count: Int) = ui {
                    waiting = count
                    refreshSteadyStatus()
                }

                override fun onResult(speakerIsA: Boolean, src: String, out: String) = ui {
                    if (!running) return@ui
                    val speaker = if (speakerIsA) left else right
                    addBubble(speaker, src, out)
                    speak(out, other(speaker))
                }

                override fun onError(message: String, fatal: Boolean) = ui {
                    if (!running) return@ui
                    if (fatal) stop(message) else status.text = message
                }
            }
        )
    }

    private fun refreshSteadyStatus() {
        if (!running || fastMode) return
        status.text = when {
            hearing -> "말을 듣고 있어요…"
            waiting > 0 -> "번역하고 있어요…"
            else -> "말씀하세요. 말을 마치면 번역해 드려요"
        }
    }

    /** 듣는 사람 쪽 이어폰에만 읽어 줌 */
    private fun speak(text: String, listener: Side) {
        val engine = tts ?: return
        if (!ttsReady) return
        val locale = Locale.forLanguageTag(listener.lang.code)
        if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            if (!warnedTts) {
                warnedTts = true
                toast("이 폰에 ${listener.lang.name} 음성이 없어요. 폰 설정의 '글자 읽어주기(TTS)'에서 음성 데이터를 설치해 주세요.")
            }
            return
        }
        engine.language = locale
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, if (listener.isLeft) -1f else 1f)
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, "talk-${ttsSeq++}")
    }

    // ═════════════════════════ 빠름 방식 (실시간 모델 하나) ═════════════════════════
    @Volatile private var live: LiveTranslateClient? = null
    @Volatile private var liveReady = false
    private var liveFailures = 0
    private var track: AudioTrack? = null
    private var player: ExecutorService? = null

    // 한 번의 번역(턴) 상태 — 메인 스레드에서만 접근
    private val liveIn = StringBuilder()
    private val liveOut = StringBuilder()
    private val heldAudio = ArrayList<ByteArray>()
    /** 번역을 들려줄 쪽. null = 아직 모름(글자가 오면 정함) */
    private var ear: Ear? = null
    private var liveBubble: Pair<TextView, TextView>? = null
    private var lastLiveOutputAt = 0L
    private val earTimeout = Runnable { if (ear == null) chooseEar(force = true) }
    private val turnIdle = Runnable { endLiveTurn() }

    private enum class Ear { LEFT, RIGHT, BOTH }

    /** 시도할 (모델, 끼어들기 방지 설정 사용) 조합. 성공한 것을 기억해 다음에 먼저 씀 */
    private val liveVariants = listOf(
        "gemini-3.8-live" to true,
        "gemini-3.8-live" to false,
        "gemini-3.1-flash-live-preview" to true,
        "gemini-3.1-flash-live-preview" to false,
        "gemini-2.5-flash-native-audio-latest" to true,
        "gemini-2.5-flash-native-audio-latest" to false,
    )
    private var liveVariant = 0

    // ── 멈춤 감지 ──
    @Volatile private var lastSpeechAt = 0L
    /** 마지막 번역 이후에 들어온 말소리 길이(ms). 번역이 나오면 0 으로 */
    @Volatile private var unansweredSpeechMs = 0
    private var misses = 0
    private var liveConnectedAt = 0L

    private val liveWatchdog = object : Runnable {
        override fun run() {
            if (!running || !fastMode) return
            val now = SystemClock.elapsedRealtime()
            val quietFor = now - lastSpeechAt
            val idleOutput = now - lastLiveOutputAt > 3000 && ear == null
            if (liveReady) {
                // 1.5초 넘게 말했는데 말이 끝난 지 6초가 지나도록 번역이 없음 → 놓친 것
                if (unansweredSpeechMs >= 1500 && quietFor > 6000) {
                    unansweredSpeechMs = 0
                    misses++
                    if (misses >= 2) renewLive("번역이 멈춘 것 같아 다시 연결했어요")
                }
                // 연결이 8분 넘었으면 조용한 틈에 미리 새로 맺음 (서버 시간 제한·누적 대화로 인한 느려짐 예방)
                else if (now - liveConnectedAt > 8 * 60_000 && quietFor > 3000 && idleOutput) {
                    renewLive(null)
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun renewLive(message: String?) {
        misses = 0
        unansweredSpeechMs = 0
        val old = live
        live = null
        liveReady = false
        runCatching { old?.close() }
        resetLiveTurn()
        if (message != null) status.text = message
        connectLive()
    }

    private fun startFast() {
        liveFailures = 0
        misses = 0
        unansweredSpeechMs = 0
        lastSpeechAt = SystemClock.elapsedRealtime()
        main.postDelayed(liveWatchdog, 1000)
        liveVariant = prefs.getInt("talkLiveVariant", 0).coerceIn(0, liveVariants.lastIndex)
        track = newTrack().also { it.play() }
        player = Executors.newSingleThreadExecutor()
        resetLiveTurn()
        connectLive()
    }

    private fun interpreterPrompt(): String {
        val a = left.lang.english
        val b = right.lang.english
        return """
            You are a live interpreter device placed between two people. You are NOT a participant in the conversation.
            One person speaks $a. The other speaks $b.

            - Whenever you hear $a, say the same thing in $b.
            - Whenever you hear $b, say the same thing in $a.
            - Detect the language of each utterance independently. The language can change on every utterance.
            - Say ONLY the translation, in a natural conversational tone that keeps the speaker's politeness level.
            - NEVER answer questions, never greet back, never add comments, explanations, or filler. If someone asks "How are you?", translate the question; do not reply to it.
            - Translate every utterance, even short ones such as "yes", "okay", a name, or a single word.
            - If part of the speech is unclear, translate the part you understood rather than staying silent.
            - If you hear only silence, noise, or music, say nothing.
        """.trimIndent()
    }

    private fun connectLive() {
        if (!running) return
        val (model, noInterrupt) = liveVariants[liveVariant]
        lateinit var client: LiveTranslateClient
        client = LiveTranslateClient(
            apiKey, false, true,
            object : LiveTranslateClient.Listener {
                override fun onReady() = ui {
                    if (live === client) {
                        liveReady = true
                        liveConnectedAt = SystemClock.elapsedRealtime()
                        prefs.edit().putInt("talkLiveVariant", liveVariant).apply()
                        updateStatus()
                    }
                }

                override fun onInputText(delta: String) = ui {
                    if (live === client) liveIn.append(delta)
                }

                override fun onOutputText(delta: String) = ui {
                    if (live !== client) return@ui
                    liveFailures = 0
                    touchLiveTurn()
                    liveOut.append(delta)
                    if (ear == null) chooseEar(force = false) else renderLiveBubble()
                }

                override fun onAudio(pcm: ByteArray) = ui {
                    if (!running || live !== client) return@ui
                    touchLiveTurn()
                    val e = ear
                    if (e == null) heldAudio += pcm else play(pcm, e)
                }

                override fun onTurnComplete() = ui {
                    if (live === client) endLiveTurn()
                }

                override fun onInterrupted() = ui {
                    if (live !== client) return@ui
                    // 모델이 하던 말을 끊음 → 재생 대기 중인 소리를 비움
                    heldAudio.clear()
                    runCatching { track?.pause(); track?.flush(); track?.play() }
                }

                override fun onClosed(error: String?, gotOutput: Boolean) = ui {
                    if (!running || live !== client) return@ui
                    live = null
                    liveReady = false
                    endLiveTurn()
                    if (gotOutput || error == null || error == "goAway") {
                        main.postDelayed({ connectLive() }, 300) // 세션 시간 제한 등 → 다시 연결
                    } else {
                        // 연결 실패 → 다른 모델/설정 조합으로
                        liveFailures++
                        if (liveFailures < liveVariants.size) {
                            liveVariant = (liveVariant + 1) % liveVariants.size
                            main.postDelayed({ connectLive() }, 500)
                        } else {
                            stop("실시간 방식에 연결하지 못했어요. '안정' 방식을 써 보세요.\n사유: ${error?.take(160)}")
                        }
                    }
                    if (running) updateStatus()
                }
            },
            model = model,
            systemInstruction = interpreterPrompt(),
            noInterruption = noInterrupt,
        )
        live = client
        client.connect()
    }

    /** 번역 소리/글자가 올 때마다: 새 턴이면 귀 선택 대기 시작, 1.5초 조용하면 턴 종료 */
    private fun touchLiveTurn() {
        misses = 0
        unansweredSpeechMs = 0
        val now = SystemClock.elapsedRealtime()
        val active = liveOut.isNotEmpty() || heldAudio.isNotEmpty() || ear != null
        if (active && now - lastLiveOutputAt > 1500) endLiveTurn()
        if (ear == null && heldAudio.isEmpty() && liveOut.isEmpty()) {
            main.removeCallbacks(earTimeout)
            main.postDelayed(earTimeout, 1200) // 글자가 끝내 안 오면 양쪽 귀에 틂
        }
        lastLiveOutputAt = now
        main.removeCallbacks(turnIdle)
        main.postDelayed(turnIdle, 1500)
    }

    /** 번역문의 글자 종류로 어느 사람의 언어인지 보고 그 사람 귀에 틂 */
    private fun chooseEar(force: Boolean) {
        val script = detectScript(liveOut.toString())
        val chosen = when {
            left.script == right.script -> Ear.BOTH // 글자로 구분 못 하는 언어쌍은 양쪽에
            script != null && matches(script, left) -> Ear.LEFT
            script != null && matches(script, right) -> Ear.RIGHT
            force -> Ear.BOTH
            else -> return
        }
        main.removeCallbacks(earTimeout)
        ear = chosen
        heldAudio.forEach { play(it, chosen) }
        heldAudio.clear()
        renderLiveBubble()
    }

    private fun matches(script: String, side: Side) =
        script == side.script || (script == "han" && side.script == "ja" && other(side).script != "han")

    private fun renderLiveBubble() {
        val text = liveOut.toString().trim()
        if (text.isEmpty()) return
        val e = ear ?: return
        // 번역을 듣는 사람의 반대쪽이 말한 사람
        val speaker = if (e == Ear.LEFT) right else left
        val views = liveBubble ?: addBubble(speaker, "", "").also { liveBubble = it }
        views.second.text = text
        val original = liveIn.toString().trim()
        views.first.text = original
        views.first.visibility = if (original.isBlank()) View.GONE else View.VISIBLE
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun endLiveTurn() {
        if (ear == null && (heldAudio.isNotEmpty() || liveOut.isNotEmpty())) chooseEar(force = true)
        if (ear != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // 구형 기기는 버퍼가 차야 재생되므로 무음으로 밀어 줌
            val t = track
            val silence = ByteArray(OUT_RATE * 4)
            runCatching { player?.execute { runCatching { t?.write(silence, 0, silence.size) } } }
        }
        resetLiveTurn()
    }

    private fun resetLiveTurn() {
        main.removeCallbacks(earTimeout)
        main.removeCallbacks(turnIdle)
        liveIn.setLength(0)
        liveOut.setLength(0)
        heldAudio.clear()
        ear = null
        liveBubble = null
        lastLiveOutputAt = 0L
    }

    private fun newTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, OUT_RATE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                // 기본값은 버퍼(약 1초)가 다 차야 재생을 시작해서 짧은 번역은 소리가 안 났음 → 조금만 쌓여도 바로 재생
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { it.setStartThresholdInFrames(OUT_RATE / 20) } // 50ms
                }
            }
    }

    /** mono PCM 을 고른 귀에만(또는 양쪽에) 재생 */
    private fun play(mono: ByteArray, e: Ear) {
        val t = track ?: return
        val out = ByteArray(mono.size * 2)
        var i = 0
        while (i + 1 < mono.size) {
            if (e != Ear.RIGHT) {
                out[i * 2] = mono[i]
                out[i * 2 + 1] = mono[i + 1]
            }
            if (e != Ear.LEFT) {
                out[i * 2 + 2] = mono[i]
                out[i * 2 + 3] = mono[i + 1]
            }
            i += 2
        }
        lastPlayAt = SystemClock.elapsedRealtime()
        runCatching { player?.execute { runCatching { t.write(out, 0, out.size) } } }
    }

    // ───────────────────────── 화면 표시 ─────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 말풍선 추가: 말한 사람 쪽(왼쪽/오른쪽)에 붙이고, 작은 글씨로 원문 + 큰 글씨로 번역.
     * 돌려주는 값은 (원문 글자, 번역 글자) — 실시간 방식에서 내용을 이어서 고칠 때 씀.
     */
    private fun addBubble(speaker: Side, originalText: String, translatedText: String): Pair<TextView, TextView> {
        val badge = TextView(this).apply {
            text = if (speaker.lang.code == "fil") "TL" else speaker.lang.code.substringBefore('-').uppercase().take(2)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, if (speaker.isLeft) R.color.teal else R.color.purple))
            setBackgroundResource(if (speaker.isLeft) R.drawable.bg_badge_teal else R.drawable.bg_badge_purple)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        val original = TextView(this).apply {
            text = originalText
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.textDim))
            visibility = if (originalText.isBlank()) View.GONE else View.VISIBLE
        }
        val translated = TextView(this).apply {
            text = translatedText
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bubble)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(original)
            addView(translated)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (speaker.isLeft) marginStart = dp(8) else marginEnd = dp(8)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                if (speaker.isLeft) marginEnd = dp(40) else marginStart = dp(40)
            }
            if (speaker.isLeft) {
                addView(badge)
                addView(bubble)
            } else {
                addView(bubble)
                addView(badge)
            }
        }
        bubbles.addView(row)
        while (bubbles.childCount > 80) bubbles.removeViewAt(0)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return original to translated
    }

    /** 고른 언어를 카드 제목에 반영 */
    private fun updateCards() {
        left.titleView.text = "L · " + languages[spinnerLeft.selectedItemPosition.coerceAtLeast(0)].name
        right.titleView.text = "R · " + languages[spinnerRight.selectedItemPosition.coerceAtLeast(0)].name
    }

    private fun updateStatus() {
        val teal = ContextCompat.getColor(this, R.color.teal)
        val dim = ContextCompat.getColor(this, R.color.textDim)
        val ok = running && (!fastMode || liveReady)
        val label = when {
            !running -> "● 대기 중"
            ok -> if (fastMode) "● 연결됨" else "● 준비됨"
            else -> "● 연결 중…"
        }
        listOf(left, right).forEach { s ->
            s.stateView.text = label
            s.stateView.setTextColor(if (ok) teal else dim)
        }
        headphones = headphonesConnected()
        chipWarn.visibility = if (headphones) View.GONE else View.VISIBLE
        if (running) {
            if (fastMode) {
                status.text = if (liveReady) "두 사람의 말을 듣고 있어요" else "연결하고 있어요…"
            } else {
                refreshSteadyStatus()
            }
        }
    }

    private fun headphonesConnected(): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) types += AudioDeviceInfo.TYPE_BLE_HEADSET
        return getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in types }
    }

    private fun scriptOfLanguage(code: String) = when (code.lowercase().substringBefore('-')) {
        "ko" -> "ko"
        "ja" -> "ja"
        "zh" -> "han"
        "ru" -> "cyr"
        "th" -> "thai"
        else -> "latin"
    }

    private fun detectScript(text: String): String? {
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

    private fun ui(block: () -> Unit) {
        main.post { if (!isFinishing && !isDestroyed) block() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
    }
}
