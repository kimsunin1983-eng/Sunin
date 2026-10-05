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
import android.widget.SeekBar
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
        Lang("자동 감지", AUTO, ""),
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
    private lateinit var textFacing: TextView

    @Volatile private var running = false
    /** 통역을 시작할 때마다 올라가는 번호. 이전 통역에서 늦게 돌아온 결과를 가려내는 데 씀 */
    @Volatile private var session = 0
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
        textFacing = findViewById(R.id.textFacing)
        findViewById<View>(R.id.rowTalkSettings).setOnClickListener { showTalkSettings() }
        left.titleView = findViewById(R.id.textLeftTitle)
        left.stateView = findViewById(R.id.textLeftState)
        right.titleView = findViewById(R.id.textRightTitle)
        right.stateView = findViewById(R.id.textRightState)

        val adapter = ArrayAdapter(this, R.layout.item_spinner, languages.map { it.name }).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerLeft.adapter = adapter
        spinnerRight.adapter = adapter
        // 기본: 상대(왼쪽)는 영어, 나(오른쪽)는 한국어. 목록 순서가 바뀌어도 어긋나지 않게 언어 코드로 기억
        fun indexOf(code: String?, fallback: String) =
            languages.indexOfFirst { it.code == code }.takeIf { it >= 0 }
                ?: languages.indexOfFirst { it.code == fallback }
        spinnerLeft.setSelection(indexOf(prefs.getString("talkLeftLang", "en"), "en"))
        spinnerRight.setSelection(indexOf(prefs.getString("talkRightLang", "ko"), "ko"))
        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateCards()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerLeft.onItemSelectedListener = onPick
        spinnerRight.onItemSelectedListener = onPick

        // 주변 소리 민감도 (통역 중에도 바로 적용)
        val seek = findViewById<SeekBar>(R.id.seekSensitivity)
        val seekValue = findViewById<TextView>(R.id.textSensitivity)
        val seekHelp = findViewById<TextView>(R.id.textSensitivityHelp)
        fun apply(value: Int) {
            setSensitivity(value)
            seekValue.text = value.toString()
            seekHelp.text = when {
                value < 35 -> "낮음: 폰 가까이에서 크게 말한 소리만 번역해요. 시끄러운 곳에 맞아요"
                value < 70 -> "보통: 마주 앉은 거리의 말소리를 번역해요"
                else -> "높음: 작은 목소리와 먼 소리도 번역해요. 조용한 곳에 맞아요"
            }
        }
        seek.progress = prefs.getInt("talkSensitivity", 60).coerceIn(0, 100)
        apply(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = apply(progress)
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                prefs.edit().putInt("talkSensitivity", seek.progress).apply()
            }
        })

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
        loadTalkSettings()
        updateStatus()
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, main)
        volumeControlStream = AudioManager.STREAM_MUSIC // 이 화면에서 음량 버튼은 번역 소리를 조절
    }

    /** 이어폰이 빠지거나 연결되면 바로 반영 (빠진 채로 스피커 소리를 다시 번역하지 않도록) */
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = ui { onDevicesChanged() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = ui { onDevicesChanged() }
    }

    private fun onDevicesChanged() {
        val had = headphones
        updateStatus()
        if (running && had && !headphones) {
            toast("이어폰 연결이 끊겼어요. 번역이 폰 스피커로 나오는 동안에는 마이크를 잠깐 쉬어요.")
        }
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
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback) }
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
                "실시간: 말이 끝나면 곧바로 번역해요"
            } else {
                "문장 단위: 받아쓰기와 번역을 단계별로 확인해요. 실시간보다 몇 초 더 걸려요"
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
        val pickL = languages[spinnerLeft.selectedItemPosition]
        val pickR = languages[spinnerRight.selectedItemPosition]
        if (pickL.code == AUTO && pickR.code == AUTO) {
            toast("한쪽은 언어를 정해 주세요. 양쪽 모두 자동 감지로 둘 수는 없어요.")
            return
        }
        if (pickL.code == pickR.code) {
            toast("왼쪽과 오른쪽 언어를 다르게 골라 주세요.")
            return
        }
        if (speakerMode && !fastMode) {
            toast("'상대는 폰 스피커로 듣기'는 '실시간' 방식에서만 돼요.")
            return
        }
        if ((pickL.code == AUTO || pickR.code == AUTO) && !fastMode) {
            toast("자동 감지는 '실시간' 방식에서만 돼요.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        prefs.edit()
            .putString("talkLeftLang", languages[spinnerLeft.selectedItemPosition].code)
            .putString("talkRightLang", languages[spinnerRight.selectedItemPosition].code)
            .apply()
        left.lang = languages[spinnerLeft.selectedItemPosition]
        right.lang = languages[spinnerRight.selectedItemPosition]

        session++
        playGen++
        speakerRouteChecked = false
        running = true
        setFacing("")
        compact(true)
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

    /** 상대가 읽을 뒤집힌 큰 글자. 보여 줄 말이 있을 때만 자리를 차지함 */
    private fun setFacing(text: String) {
        textFacing.text = text
        textFacing.visibility = if (speakerMode && text.isNotBlank()) View.VISIBLE else View.GONE
    }

    /** 통역 중에는 설정 줄들을 접어 대화 기록을 크게 보여 줌 */
    private fun compact(on: Boolean) {
        val v = if (on) View.GONE else View.VISIBLE
        intArrayOf(R.id.rowLanguages, R.id.rowMode, R.id.rowSensitivity, R.id.textSensitivityHelp, R.id.rowTalkSettings)
            .forEach { findViewById<View>(it).visibility = v }
    }

    private fun stop(message: String) {
        running = false
        compact(false)
        session++
        playGen++
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
        runCatching { rescue?.close() }
        rescue = null
        runCatching { fixExecutor?.shutdownNow() }
        fixExecutor = null
        transcriber = null
        runCatching { speakerPlayer?.shutdownNow() }
        speakerPlayer = null
        speakerBusyUntil = 0L
        speechBuffer.cancelTurn()
        runCatching { speakerTrack?.pause(); speakerTrack?.flush(); speakerTrack?.release() }
        speakerTrack = null

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
                    var lv = level(buf, n)
                    // 민감도보다 작은 소리는 무음으로 바꿔 보냄 (말끝이 잘리지 않게 0.4초는 더 열어 둠)
                    if (lv >= gateLevel) {
                        gateOpenChunks = 4
                    } else if (gateOpenChunks > 0) {
                        gateOpenChunks--
                    } else {
                        java.util.Arrays.fill(buf, 0, n, 0.toByte())
                        lv = 0f
                    }
                    wave.push(lv)
                    // 폰 스피커로 번역이 나오는 동안에는 그 소리를 다시 듣지 않도록 쉼
                    if (speakerPlaying()) continue
                    if (lv > 0.1f) {
                        lastSpeechAt = SystemClock.elapsedRealtime()
                        unansweredSpeechMs += 100
                    }
                    utterance?.feed(buf, n, lv)
                    val client = live
                    if (fastMode && client?.isReady == true) {
                        speechBuffer.feed(buf, n, lv >= maxOf(gateLevel, 0.01f), SystemClock.elapsedRealtime())
                        client.sendAudio(buf, n)
                    }
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
        // 스피커 방식: 스피커로 번역이 나오는 동안(과 직후 0.4초)은 마이크가 그 소리를 다시 듣지 않게 함
        if (speakerMode && SystemClock.elapsedRealtime() < speakerBusyUntil + 400) return true
        if (headphones) return false
        if (tts?.isSpeaking == true) return true
        return SystemClock.elapsedRealtime() - lastPlayAt < 600
    }

    // ── 주변 소리 민감도 (0~100) ──
    /** 작은 목소리를 최대 몇 배까지 키울지 (1~6배) */
    @Volatile private var maxGain = 4f
    /** 이 크기(0~1)보다 작은 소리는 버림 */
    @Volatile private var gateLevel = 0.04f
    private var gateOpenChunks = 0

    private fun setSensitivity(value: Int) {
        val s = value.coerceIn(0, 100) / 100f
        maxGain = 1f + 5f * s                 // 0 → 1배, 60 → 4배, 100 → 6배
        gateLevel = (1f - s) * (1f - s) * 0.25f // 0 → 0.25, 60 → 0.04, 100 → 0
    }

    // 최근 가장 큰 소리 크기 (천천히 줄어듦). 목소리가 작게 들어오면 이 값이 작아져서 더 많이 키움
    private var peakEnvelope = 4000f

    /** 폰에서 떨어져 말해 작게 들어온 목소리를 민감도에 따라 최대 maxGain 배까지 키움 (제자리에서 수정) */
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
        val gain = (12000f / peakEnvelope).coerceIn(1f, maxGain)
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

    // ═════════════════════════ 통역 설정 ═════════════════════════
    /** 상대는 이어폰 대신 폰 스피커로 듣기 (상대=왼쪽, 나=오른쪽) */
    private var speakerMode = false
    private var voice = ""
    private var wantAffective = true
    private var situation = 0
    private var polite = true
    private var notes = ""

    /** 목소리 설정이 바뀌면 통하는 조합도 달라질 수 있어 따로 기억 */
    private fun variantKey() = if (wantAffective) "talkLiveVariant3a" else "talkLiveVariant3"

    private fun loadTalkSettings() {
        speakerMode = prefs.getBoolean("talkSpeaker", false)
        voice = prefs.getString("talkVoice", "").orEmpty()
        wantAffective = prefs.getBoolean("talkAffective", true)
        situation = prefs.getInt("talkSituation", 0)
        polite = prefs.getBoolean("talkPolite", true)
        notes = prefs.getString("talkNotes", "").orEmpty()
        setFacing("")
        findViewById<TextView>(R.id.textTalkSummary).text = listOf(
            if (speakerMode) "상대 스피커" else "이어폰 나눠 끼기",
            SITUATIONS[situation.coerceIn(0, SITUATIONS.lastIndex)].first,
            if (polite) "존댓말" else "편한 말",
            VOICES.firstOrNull { it.second == voice }?.first ?: "기본 목소리",
        ).joinToString(" · ")
        updateCards()
    }

    private fun showTalkSettings() {
        if (running) {
            toast("통역을 종료한 뒤 바꿀 수 있어요.")
            return
        }
        val pad = dp(20)
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, dp(14), 0, dp(4))
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, items.lastIndex))
        }
        val listen = spinner(listOf("이어폰을 한쪽씩 나눠 끼기", "상대는 폰 스피커로 듣기 (나만 이어폰)"), if (speakerMode) 1 else 0)
        val situationPick = spinner(SITUATIONS.map { it.first }, situation)
        val politePick = spinner(listOf("존댓말", "편한 말 (반말)"), if (polite) 0 else 1)
        val voicePick = spinner(VOICES.map { it.first }, VOICES.indexOfFirst { it.second == voice }.coerceAtLeast(0))
        val affective = android.widget.CheckBox(this).apply {
            text = "말하는 사람의 감정·어조에 맞춰 말하기"
            isChecked = wantAffective
        }
        val notesEdit = android.widget.EditText(this).apply {
            setText(notes)
            maxLines = 3
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(label("상대방이 번역을 듣는 방법"))
            addView(listen)
            addView(label("상황"))
            addView(situationPick)
            addView(label("말투"))
            addView(politePick)
            addView(label("번역 목소리"))
            addView(voicePick)
            addView(affective)
            addView(label("이름·자주 나오는 단어 (번역이 정확해져요)"))
            addView(notesEdit)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("통역 설정")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("저장") { _, _ ->
                prefs.edit()
                    .putBoolean("talkSpeaker", listen.selectedItemPosition == 1)
                    .putInt("talkSituation", situationPick.selectedItemPosition)
                    .putBoolean("talkPolite", politePick.selectedItemPosition == 0)
                    .putString("talkVoice", VOICES[voicePick.selectedItemPosition].second)
                    .putBoolean("talkAffective", affective.isChecked)
                    .putString("talkNotes", notesEdit.text.toString().trim())
                    .apply()
                loadTalkSettings()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ═════════════════════════ 안정 방식 (문장 단위) ═════════════════════════
    @Volatile private var utterance: UtteranceTranslator? = null
    @Volatile private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsSeq = 0
    private var warnedTts = false
    private var hearing = false
    private var waiting = 0

    private fun ensureTts() {
        if (tts == null) {
            tts = TextToSpeech(applicationContext) { result ->
                ttsReady = result == TextToSpeech.SUCCESS
                if (!ttsReady) ui { toast("이 폰의 음성 합성(TTS)을 쓸 수 없어요. 번역은 화면 글자로만 보여요.") }
            }
        }
    }

    private fun startSteady() {
        ensureTts()
        // A = 왼쪽 사람의 언어, B = 오른쪽 사람의 언어
        val mine = session
        utterance = UtteranceTranslator(
            apiKey,
            UtteranceTranslator.Language(left.lang.english, left.lang.code),
            UtteranceTranslator.Language(right.lang.english, right.lang.code),
            object : UtteranceTranslator.Listener {
                override fun onSpeaking(speaking: Boolean) = ui {
                    if (mine != session) return@ui
                    hearing = speaking
                    refreshSteadyStatus()
                }

                override fun onPending(count: Int) = ui {
                    if (mine != session) return@ui
                    waiting = count
                    refreshSteadyStatus()
                }

                override fun onResult(speakerIsA: Boolean, src: String, out: String) = ui {
                    if (!running || mine != session) return@ui
                    val speaker = if (speakerIsA) left else right
                    addBubble(speaker, src, out)
                    speak(out, other(speaker))
                }

                override fun onError(message: String, fatal: Boolean) = ui {
                    if (!running || mine != session) return@ui
                    if (fatal) stop(message) else status.text = message
                }
            },
            extraContext = contextPrompt(), // 상황·말투·이름 설정을 문장 단위 방식에도 반영
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
    private var liveTurnOpen = false
    /**
     * 안전장치: 서버의 종료 신호(turnComplete)가 오지 않은 채 4초 동안 아무것도 안 오면 그 말은 끝난 것으로 봄.
     * 이게 없으면 다음 사람의 번역이 앞 말에 이어 붙어 앞 말과 같은 귀(엉뚱한 사람)로 나감.
     * 검산·버리기는 하지 않음 (확인된 종료가 아니므로).
     */
    private val turnIdle = Runnable { if (liveTurnOpen) endLiveTurn(confirmed = false) }
    private var heldAudioBytes = 0

    /** NONE: 번역이 아닌 출력이라 버림 */
    private enum class Ear { LEFT, RIGHT, BOTH, NONE }

    /** 시도할 연결 설정 조합. 서버가 거절하면 다음 것으로, 성공한 것을 기억해 다음에 먼저 씀 */
    private data class Variant(val model: String, val vad: Boolean, val affective: Boolean)

    private val liveVariants = listOf(
        Variant("gemini-3.8-live", vad = true, affective = true),
        Variant("gemini-3.8-live", vad = true, affective = false),
        Variant("gemini-3.8-live", vad = false, affective = false),
        Variant("gemini-3.1-flash-live-preview", vad = true, affective = false),
        Variant("gemini-3.1-flash-live-preview", vad = false, affective = false),
        Variant("gemini-2.5-flash-native-audio-latest", vad = true, affective = true),
        Variant("gemini-2.5-flash-native-audio-latest", vad = true, affective = false),
        Variant("gemini-2.5-flash-native-audio-latest", vad = false, affective = false),
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
                if (liveTurnOpen && now - lastLiveOutputAt > 30_000) {
                    renewLive("응답이 멈춰 다시 연결했어요")
                    main.postDelayed(this, 1000)
                    return
                }
                // 1.5초 넘게 말했는데 말이 끝난 지 6초가 지나도록 번역이 없음 → 놓친 것
                if (unansweredSpeechMs >= 1500 && quietFor > 6000) {
                    unansweredSpeechMs = 0
                    misses++
                    if (misses >= 2) renewLive("번역이 멈춘 것 같아 다시 연결했어요")
                }
                // 서버가 연결을 15분에 끊으므로, 13분이 넘으면 조용한 틈에 미리 새로 맺음.
                // 앞 대화는 recentTurns 로 새 연결에 넘겨주므로 맥락이 이어짐
                else if (now - liveConnectedAt > 13 * 60_000 && quietFor > 3000 && idleOutput) {
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
        speechBuffer.cancelTurn()
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
        liveVariant = prefs.getInt(variantKey(), 0).coerceIn(0, liveVariants.lastIndex)
        track = newTrack().also { it.play() }
        player = Executors.newSingleThreadExecutor()
        // 두 언어가 정해져 있으면, 실시간 모델이 놓친 말을 되살릴 통역기를 준비
        rescue = null
        val mine = session
        if (left.lang.code != AUTO && right.lang.code != AUTO) {
            ensureTts()
            rescue = UtteranceTranslator(
                apiKey,
                UtteranceTranslator.Language(left.lang.english, left.lang.code),
                UtteranceTranslator.Language(right.lang.english, right.lang.code),
                object : UtteranceTranslator.Listener {
                    override fun onSpeaking(speaking: Boolean) {}
                    override fun onPending(count: Int) {}
                    override fun onError(message: String, fatal: Boolean) {}
                    override fun onResult(speakerIsA: Boolean, src: String, out: String) = ui {
                        if (!running || !fastMode || mine != session) return@ui
                        val speaker = if (speakerIsA) left else right
                        addBubble(speaker, src, out)
                        if (speakerMode) {
                            if (!speaker.isLeft) setFacing(out) // 내가 한 말 → 상대가 읽게
                            else speak(out, right)                      // 상대가 한 말 → 내 이어폰
                        } else {
                            speak(out, other(speaker))
                        }
                    }
                },
                extraContext = contextPrompt(),
            )
        }
        transcriber = Transcriber(apiKey)
        fixExecutor = CheckExecutor.create() // running 1 + waiting 2, owned by this session
        speechBuffer = LiveSpeechBuffer()
        speakerBusyUntil = 0L
        recentTurns.clear()
        if (speakerMode) {
            // 상대에게 가는 번역은 폰 스피커로 (이어폰이 연결돼 있어도 이 소리만 스피커로 보냄)
            speakerTrack = newTrack().also { t ->
                val speaker = getSystemService(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) runCatching { t.setPreferredDevice(speaker) }
                t.play()
            }
            speakerPlayer = Executors.newSingleThreadExecutor()
        }
        resetLiveTurn()
        connectLive()
    }

    private fun interpreterPrompt(): String = basePrompt() + "\n\n" + contextPrompt()

    /** 상황·말투·이름 등 사용자가 미리 알려 준 내용 */
    private fun contextPrompt(): String = buildString {
        append("Context for better translations:\n")
        append("- Situation: ").append(SITUATIONS[situation.coerceIn(0, SITUATIONS.lastIndex)].second).append('\n')
        if (polite) {
            append("- Register: polite. When the output is Korean use 존댓말 (해요체). In other languages use a polite, friendly register.\n")
        } else {
            append("- Register: casual, as between friends. When the output is Korean use 반말.\n")
        }
        if (notes.isNotBlank()) {
            append("- Names and terms that may come up; keep them accurate and do not translate proper names: ")
                .append(notes.replace('\n', ' ').take(300)).append('\n')
        }
        if (recentTurns.isNotEmpty()) {
            append("- The conversation so far (oldest first). Use it only to understand what comes next; ")
            append("do not repeat, translate again, or respond to these lines:\n")
            recentTurns.forEach { append("    ").append(it.line.take(200)).append('\n') }
        }
        append("This context only guides word choice. It never changes the rule: translate, never answer.")
    }

    private fun basePrompt(): String {
        // 한쪽이 자동 감지: 정해진 언어(known)와 "그 밖의 어떤 언어" 사이를 통역
        val known = when {
            left.lang.code == AUTO -> right.lang.english
            right.lang.code == AUTO -> left.lang.english
            else -> null
        }
        if (known != null) return """
            You are a live interpreter device placed between two people. You are NOT a participant in the conversation.
            One person speaks $known. The other person speaks a different language that you must detect from their speech.

            - Whenever you hear any language other than $known, say the same thing in $known.
            - Whenever you hear $known, say the same thing in the other person's language: the non-$known language most recently spoken in this conversation. If the other person has not spoken yet, use English.
            - Detect the language of each utterance independently. The language can change on every utterance.
            - Say ONLY the translation, in a natural conversational tone that keeps the speaker's politeness level.
            - NEVER answer questions, never greet back, never add comments, explanations, or filler. If someone asks "How are you?", translate the question; do not reply to it.
            - Translate every utterance, even short ones such as "yes", "okay", a name, or a single word.
            - If part of the speech is unclear, translate the part you understood rather than staying silent.
            - Keep numbers, dates, prices, units, negations and conditions exactly as said. Never change or round them.
            - If you hear only silence, noise, or music, say nothing.
        """.trimIndent()

        val a = left.lang.english
        val b = right.lang.english
        return """
            You are a live interpreter device placed between two people. You are NOT a participant in the conversation.
            One person speaks $a. The other speaks $b.

            - Whenever you hear $a, say the same thing in $b.
            - Whenever you hear $b, say the same thing in $a.
            - ONLY $a and $b are spoken in this conversation. Every utterance is one of these two. Never interpret speech as any third language, however unusual the pronunciation sounds.
            - Detect which of the two it is for each utterance independently. The language can change on every utterance.
            - The speakers may have strong regional or foreign accents, may be non-native speakers, may mumble, speak fast, hesitate, restart sentences, or use dialect and slang. Do not expect textbook pronunciation. Work out what they most plausibly meant from the sounds, the situation, and the previous turns, and translate that intended meaning.
            - If a word could be heard in more than one way, choose the reading that makes sense in this conversation.
            - Keep personal names and place names as heard; do not translate them.
            - Say ONLY the translation, in a natural conversational tone that keeps the speaker's politeness level.
            - NEVER answer questions, never greet back, never add comments, explanations, or filler. If someone asks "How are you?", translate the question; do not reply to it.
            - Translate every utterance, even short ones such as "yes", "okay", a name, or a single word.
            - If part of the speech is unclear, translate the part you understood rather than staying silent.
            - Keep numbers, dates, prices, units, negations and conditions exactly as said. Never change or round them.
            - If you hear only silence, noise, or music, say nothing.
        """.trimIndent()
    }

    private fun connectLive() {
        if (!running) return
        // '감정 따라 말하기'를 껐으면 그 설정이 들어간 조합은 건너뜀
        while (!wantAffective && liveVariants[liveVariant].affective) {
            liveVariant = (liveVariant + 1) % liveVariants.size
        }
        val variant = liveVariants[liveVariant]
        lateinit var client: LiveTranslateClient
        client = LiveTranslateClient(
            apiKey, false, true,
            object : LiveTranslateClient.Listener {
                override fun onReady() = ui {
                    if (live === client) {
                        liveReady = true
                        liveConnectedAt = SystemClock.elapsedRealtime()
                        prefs.edit().putInt(variantKey(), liveVariant).apply()
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
                    if (ear == Ear.NONE) return@ui
                    if (ear == null) chooseEar(force = false) else renderLiveBubble()
                }

                override fun onAudio(pcm: ByteArray) = ui {
                    if (!running || live !== client) return@ui
                    touchLiveTurn()
                    val e = ear
                    if (e == null) {
                        heldAudioBytes += pcm.size
                        if (heldAudioBytes > OUT_RATE * 2 * 30) {
                            renewLive("출력 확인이 지연돼 다시 연결했어요")
                            return@ui
                        }
                        heldAudio += pcm
                    } else play(pcm, e)
                }

                override fun onTurnComplete() = ui {
                    if (live === client) endLiveTurn(confirmed = true)
                }

                override fun onInterrupted() = ui {
                    if (live !== client) return@ui
                    // 모델이 하던 말을 끊음 → 재생 대기 중인 소리를 비움
                    heldAudio.clear()
                    playGen++ // 줄 서 있던 이전 소리도 재생하지 않음
                    runCatching { track?.pause(); track?.flush(); track?.play() }
                    runCatching { speakerTrack?.pause(); speakerTrack?.flush(); speakerTrack?.play() }
                    speakerBusyUntil = 0L
                    speechBuffer.cancelTurn()
                    resetLiveTurn()
                }

                override fun onClosed(error: String?, gotOutput: Boolean) = ui {
                    if (!running || live !== client) return@ui
                    live = null
                    liveReady = false
                    endLiveTurn(confirmed = false)
                    if (gotOutput || error == null || error == "goAway") {
                        main.postDelayed({ connectLive() }, 300) // 세션 시간 제한 등 → 다시 연결
                    } else {
                        liveFailures++
                        // 서버가 설정·모델을 거절한 경우에만 다른 조합으로 넘어감.
                        // 네트워크 끊김 같은 일시 오류에 조합을 바꾸면 잘 되던 설정을 잃어버림
                        val rejected = CONFIG_ERROR.containsMatchIn(error.orEmpty())
                        if (liveFailures < liveVariants.size + 3) {
                            if (rejected) liveVariant = (liveVariant + 1) % liveVariants.size
                            main.postDelayed({ connectLive() }, if (rejected) 500L else 1500L)
                        } else {
                            stop("실시간 방식에 연결하지 못했어요. '문장 단위' 방식을 써 보세요.\n사유: ${error?.take(160)}")
                        }
                    }
                    if (running) updateStatus()
                }
            },
            model = variant.model,
            systemInstruction = interpreterPrompt(),
            noInterruption = variant.vad,
            voiceName = voice.ifBlank { null },
            affectiveDialog = variant.affective,
        )
        speechBuffer = LiveSpeechBuffer()
        live = client
        client.connect()
    }

    /** Network pauses are not utterance boundaries. Only server turnComplete finalizes. */
    private fun touchLiveTurn() {
        misses = 0
        unansweredSpeechMs = 0
        val now = SystemClock.elapsedRealtime()
        if (!liveTurnOpen) {
            liveTurnOpen = true
            speechBuffer.beginTurn(now)
            main.postDelayed(earTimeout, 1200)
        }
        lastLiveOutputAt = now
        main.removeCallbacks(turnIdle)
        main.postDelayed(turnIdle, 4000)
    }

    /** 번역문의 글자 종류로 어느 사람의 언어인지 보고 그 사람 귀에 틂 */
    private fun chooseEar(force: Boolean) {
        if (ear == Ear.NONE) return
        val text = liveOut.toString()
        // 번역이 아닌 표시 문구면 버림. 오는 중일 수 있으면 조금 더 기다림
        // ("Music" 다음에 " is my life." 가 이어질 수 있으므로, 끝까지 기다렸다가 판단)
        val suspicious = isPlaceholder(text) || mightBecomePlaceholder(text)
        if (suspicious) return // timeout may choose an ear, but may never discard a partial sentence

        val script = detectScript(text)
        // 첫 조각의 이름·상표(알파벳) 한두 글자에 속지 않도록, 글자가 4자 이상 모이면 판단.
        // 다만 "네", "Yes" 처럼 짧게 끝난 말은 마지막 판단(force) 때 있는 글자로 정함
        val enough = force || text.count { it.isLetter() } >= 4
        val autoSide = listOf(left, right).firstOrNull { it.lang.code == AUTO }
        val scripts = setOf(left.script, right.script)
        val chosen = when {
            autoSide != null -> {
                // 정해진 언어 글자로 나온 번역은 그 사람 귀에, 그 밖의 글자는 자동 감지 쪽 귀에
                val known = other(autoSide)
                when {
                    known.script == "latin" -> Ear.BOTH // 알파벳 언어는 다른 알파벳 언어와 글자로 구분 불가
                    script == null -> if (force) Ear.BOTH else return
                    !enough -> return
                    script == known.script -> if (known.isLeft) Ear.LEFT else Ear.RIGHT
                    else -> if (autoSide.isLeft) Ear.LEFT else Ear.RIGHT
                }
            }
            left.script == right.script -> Ear.BOTH // 글자로 구분 못 하는 언어쌍은 양쪽에
            script == null -> if (force) Ear.BOTH else return
            !enough -> return
            // 일본어↔중국어: 한자만으로는 어느 쪽인지 알 수 없음 → 가나가 나올 때까지 기다리고, 끝내 없으면 양쪽
            scripts == setOf("ja", "han") && script == "han" -> if (force) Ear.BOTH else return
            matches(script, left) -> Ear.LEFT
            matches(script, right) -> Ear.RIGHT
            force -> Ear.BOTH
            else -> return
        }
        main.removeCallbacks(earTimeout)
        ear = chosen
        heldAudio.forEach { play(it, chosen) }
        heldAudio.clear()
        heldAudioBytes = 0
        renderLiveBubble()
    }

    private fun matches(script: String, side: Side) =
        script == side.script || (script == "han" && side.script == "ja" && other(side).script != "han")

    private fun renderLiveBubble() {
        val text = liveOut.toString().trim()
        if (text.isEmpty()) return
        val e = ear ?: return
        if (e == Ear.NONE) return
        // 번역을 듣는 사람의 반대쪽이 말한 사람
        val speaker = if (e == Ear.LEFT) right else left
        val views = liveBubble ?: addBubble(speaker, "", "").also { liveBubble = it }
        views.second.text = text
        if (speakerMode && e != Ear.RIGHT) {
            setFacing(text)
            facingSeq = turnSeq
        } // 상대가 읽도록 뒤집어 보여 주는 글자
        // 자동 받아쓰기가 말한 사람의 언어와 다른 글자로 나오면(언어를 잘못 짚은 것) 보여 주지 않음
        val original = liveIn.toString().trim()
        views.first.text = original
        views.first.visibility = if (originalLooksRight(original, speaker, e)) View.VISIBLE else View.GONE
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 받아쓴 원문이 말한 사람의 언어 글자로 되어 있는지 */
    private fun originalLooksRight(original: String, speaker: Side, e: Ear): Boolean {
        if (original.isBlank()) return false
        if (e == Ear.BOTH || speaker.lang.code == AUTO) return true // 판단할 근거가 없으면 그대로 둠
        val script = detectScript(original) ?: return false
        return matches(script, speaker)
    }

    // ── 원문 바로잡기: 자동 받아쓰기가 틀렸으면 그 말의 소리를 언어를 알려 주고 다시 받아씀 ──
    @Volatile private var speechBuffer = LiveSpeechBuffer()
    private var transcriber: Transcriber? = null
    private var fixExecutor: ExecutorService? = null

    /** 최근 대화 (말한 언어 → 번역). 연결을 새로 맺을 때 넘겨줘서 맥락이 끊기지 않게 함 */
    private class Turn(var line: String)
    private val recentTurns = java.util.ArrayDeque<Turn>()
    /** 말 한 번마다 올라가는 번호 */
    private var turnSeq = 0
    /** 상대가 읽는 큰 글자가 지금 몇 번째 말의 것인지 */
    private var facingSeq = -1

    // ── 번역이 아닌 출력 걸러내기 + 놓친 말 되살리기 ──
    /**
     * 모델이 번역 대신 내보내는 표시 문구인지. 전체가 그런 문구일 때만 해당.
     * ("Music is my life." 처럼 그 단어로 시작하는 정상 문장을 버리지 않도록 앞부분만 보지 않음)
     */
    private fun isPlaceholder(text: String) = LiveOutputPolicy.isPlaceholder(text)

    private fun mightBecomePlaceholder(text: String) = LiveOutputPolicy.mightBecomePlaceholder(text)

    /** 문장 단위 방식 통역기. 실시간 모델이 말을 놓쳤을 때 그 소리를 한 번 더 통역하는 데 씀 */
    private var rescue: UtteranceTranslator? = null

    /** 이번 턴은 번역이 아님: 소리도 말풍선도 내보내지 않고, 그 말은 문장 단위 방식으로 다시 통역 */
    private fun dropTurn(pcm: ByteArray?) {
        liveBubble?.let { views ->
            val row = views.second.parent?.parent as? View
            if (row != null) bubbles.removeView(row)
        }
        liveBubble = null
        heldAudio.clear()
        main.removeCallbacks(earTimeout)
        ear = Ear.NONE

        if (pcm != null && pcm.size >= 32 * 100) rescue?.submitClip(pcm)

    }

    private fun rememberTurn(): Turn? {
        val e = ear ?: return null
        val out = liveOut.toString().trim()
        if (out.isEmpty() || e == Ear.BOTH || e == Ear.NONE) return null
        val speaker = if (e == Ear.LEFT) right else left
        val listener = other(speaker)
        val original = liveIn.toString().trim().takeIf { originalLooksRight(it, speaker, e) }
        val from = speaker.lang.english.ifBlank { "other language" }
        val to = listener.lang.english.ifBlank { "other language" }
        val turn = Turn(if (original != null) "[$from] $original  →  [$to] $out" else "[$from → $to] $out")
        recentTurns.addLast(turn)
        while (recentTurns.size > 10) recentTurns.removeFirst()
        return turn
    }

    /**
     * 완결된 음성이 한 발화와 대응할 때만 독립적으로 받아쓰기·번역 후 비교.
     * 결과가 늦게 도착해도 해당 기록만 수정하고 현재 화면의 발화 번호를 확인함.
     */
    private fun refineOriginal(context: String, turn: Turn?, pcm: ByteArray?) {
        val views = liveBubble ?: return
        val e = ear ?: return
        if (e == Ear.BOTH || e == Ear.NONE) return
        val speaker = if (e == Ear.LEFT) right else left
        if (speaker.lang.code == AUTO) return

        // Ambiguous, unfinished, or oversized recordings are never used for corrections.
        if (pcm == null) return
        val worker = transcriber ?: return
        val target = views.first
        val translatedView = views.second
        val listener = other(speaker)
        val translation = liveOut.toString().trim()
        val mine = session
        val seq = turnSeq
        runCatching {
            fixExecutor?.execute {
                // 검산: 같은 소리를 따로 받아쓰고 번역해, 실시간 번역과 뜻이 다르면 기록을 고침
                val check = runCatching {
                    worker.check(pcm, speaker.lang.english, listener.lang.english, translation, context)
                }.getOrNull()
                if (check == null) return@execute
                ui {
                    if (mine != session) return@ui
                    val script = detectScript(check.src)
                    val heardOk = check.src.isNotBlank() && script != null && matches(script, speaker)
                    if (heardOk) {
                        target.text = check.src
                        target.visibility = View.VISIBLE
                        turn?.line = "[${speaker.lang.english}] ${check.src}  →  [${listener.lang.english}] $translation"
                    }
                    val outScript = detectScript(check.out)
                    if (heardOk && !check.same && check.out.isNotBlank() &&
                        outScript != null && matches(outScript, listener)
                    ) {
                        translatedView.text = check.out + "  ✎고침"
                        // 큰 글자는 아직 이 말을 보여 주고 있을 때만 바꿈 (다음 말로 넘어갔으면 그대로 둠)
                        if (speakerMode && listener.isLeft && facingSeq == seq) setFacing(check.out)
                        // 다음 번역이 참고하는 대화 맥락도 고친 내용으로
                        turn?.line = "[${speaker.lang.english}] ${check.src}  →  [${listener.lang.english}] ${check.out}"
                    }
                }
            }
        }
    }

    private fun endLiveTurn(confirmed: Boolean) {
        val pcm = if (confirmed) speechBuffer.finishTurn() else {
            speechBuffer.cancelTurn()
            null
        }
        if (LiveOutputPolicy.shouldDrop(liveOut.toString(), confirmed)) {
            dropTurn(pcm)
        } else if (ear == null && (heldAudio.isNotEmpty() || liveOut.isNotEmpty())) chooseEar(force = true)
        if (liveOut.isNotEmpty()) {
            val before = contextPrompt() // 검산에는 이번 말이 들어가기 전의 맥락만 줌
            val turn = rememberTurn()
            if (confirmed) refineOriginal(before, turn, pcm)
        }
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
        liveTurnOpen = false
        heldAudioBytes = 0
        turnSeq++
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

    /** 재생 세대. 중단·중지 때 올려서, 이미 줄 서 있던 이전 소리가 뒤늦게 재생되지 않게 함 */
    @Volatile private var playGen = 0

    private var speakerTrack: AudioTrack? = null
    private var speakerPlayer: ExecutorService? = null
    /** 폰 스피커로 내보낸 소리가 다 나올 것으로 예상되는 시각 */
    @Volatile private var speakerBusyUntil = 0L

    private fun stereo(mono: ByteArray, toLeft: Boolean, toRight: Boolean): ByteArray {
        val out = ByteArray(mono.size * 2)
        var i = 0
        while (i + 1 < mono.size) {
            if (toLeft) {
                out[i * 2] = mono[i]
                out[i * 2 + 1] = mono[i + 1]
            }
            if (toRight) {
                out[i * 2 + 2] = mono[i]
                out[i * 2 + 3] = mono[i + 1]
            }
            i += 2
        }
        return out
    }

    /**
     * 번역 목소리를 키움. 모델이 주는 소리가 작아서 그대로 틀면 폰 음량을 끝까지 올려도 작게 들림.
     * 큰 부분은 찢어지지 않게 부드럽게 눌러 줌.
     */
    private fun louder(mono: ByteArray): ByteArray {
        val out = ByteArray(mono.size)
        var i = 0
        while (i + 1 < mono.size) {
            val v = ((mono[i + 1].toInt() shl 8) or (mono[i].toInt() and 0xFF)) / 32768f * OUT_GAIN
            val a = kotlin.math.abs(v)
            // 0.6 까지는 그대로, 그 위는 1.0 을 넘지 않게 완만하게
            val limited = if (a <= 0.6f) v else Math.copySign(0.6f + 0.4f * kotlin.math.tanh((a - 0.6f) / 0.4f), v)
            val n = (limited * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = (n and 0xFF).toByte()
            out[i + 1] = ((n shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /**
     * 번역 소리 재생.
     * - 이어폰 나눠 끼기: 고른 귀에만
     * - 스피커 방식: 상대(왼쪽)에게 가는 번역은 폰 스피커로, 나(오른쪽)에게 오는 번역은 이어폰 양쪽으로
     */
    private fun play(quiet: ByteArray, e: Ear) {
        if (e == Ear.NONE) return
        val mono = louder(quiet)
        val now = SystemClock.elapsedRealtime()
        lastPlayAt = now
        if (speakerMode) {
            if (e != Ear.RIGHT) { // 상대에게 → 스피커
                val t = speakerTrack
                val out = stereo(mono, toLeft = true, toRight = true)
                val ms = mono.size / 2 * 1000L / OUT_RATE
                speakerBusyUntil = maxOf(now, speakerBusyUntil) + ms
                val gen = playGen
                runCatching { speakerPlayer?.execute { if (gen == playGen) runCatching { t?.write(out, 0, out.size) } } }
                checkSpeakerRoute()
            }
            if (e != Ear.LEFT) { // 나에게 → 이어폰 양쪽
                val t = track ?: return
                val out = stereo(mono, toLeft = true, toRight = true)
                val gen = playGen
                runCatching { player?.execute { if (gen == playGen) runCatching { t.write(out, 0, out.size) } } }
            }
            return
        }
        val t = track ?: return
        val out = stereo(mono, toLeft = e != Ear.RIGHT, toRight = e != Ear.LEFT)
        val gen = playGen
        runCatching { player?.execute { if (gen == playGen) runCatching { t.write(out, 0, out.size) } } }
    }

    // ── H. 스피커 방식: 실제로 폰 스피커로 나가는지 한 번 확인 ──
    private var speakerRouteChecked = false

    private fun checkSpeakerRoute() {
        if (speakerRouteChecked) return
        speakerRouteChecked = true
        main.postDelayed({
            val routed = runCatching { speakerTrack?.routedDevice?.type }.getOrNull() ?: return@postDelayed
            if (running && routed != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                toast("이 폰에서는 번역을 스피커로 따로 보내지 못하고 있어요. 상대에게 가는 번역도 이어폰으로 나와요.")
            }
        }, 800)
    }

    // ───────────────────────── 화면 표시 ─────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 말풍선 추가: 말한 사람 쪽(왼쪽/오른쪽)에 붙이고, 작은 글씨로 원문 + 큰 글씨로 번역.
     * 돌려주는 값은 (원문 글자, 번역 글자) — 실시간 방식에서 내용을 이어서 고칠 때 씀.
     */
    private fun addBubble(speaker: Side, originalText: String, translatedText: String): Pair<TextView, TextView> {
        val badge = TextView(this).apply {
            text = when (speaker.lang.code) {
                "fil" -> "TL"
                AUTO -> "··"
                else -> speaker.lang.code.substringBefore('-').uppercase().take(2)
            }
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
        val l = languages[spinnerLeft.selectedItemPosition.coerceAtLeast(0)].name
        val r = languages[spinnerRight.selectedItemPosition.coerceAtLeast(0)].name
        if (speakerMode) {
            left.titleView.text = "상대 · $l · 스피커"
            right.titleView.text = "나 · $r · 이어폰"
        } else {
            left.titleView.text = "L · $l"
            right.titleView.text = "R · $r"
        }
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
        private const val AUTO = "auto"
        /** 서버가 설정값이나 모델을 받아들이지 않았다는 오류 */
        private val CONFIG_ERROR = Regex(
            "1007|invalid|unknown name|cannot find field|not found|not supported|unsupported|unimplemented",
            RegexOption.IGNORE_CASE
        )
        /** 전체가 <...> 또는 [...] */
        /** 번역 목소리 증폭 배수 */
        private const val OUT_GAIN = 2.8f
        /** (화면 이름, 모델에 알려 줄 설명) */
        private val SITUATIONS = listOf(
            "일상 대화" to "everyday conversation between two people",
            "여행" to "a traveler talking with local people (hotel, airport, directions, tickets)",
            "식당·카페" to "a customer and staff at a restaurant or cafe (ordering, menu, payment)",
            "쇼핑" to "a customer and a seller at a shop or market (prices, sizes, bargaining)",
            "택시·이동" to "a passenger and a driver (destination, route, fare)",
            "업무·회의" to "a business conversation between colleagues or partners",
            "병원·약국" to "a patient and medical or pharmacy staff (symptoms, medicine, instructions); be precise",
        )

        /** (화면 이름, 목소리 이름) */
        private val VOICES = listOf(
            "기본 목소리" to "",
            "여성 · 차분함" to "Kore",
            "여성 · 밝음" to "Aoede",
            "남성 · 밝음" to "Puck",
            "남성 · 차분함" to "Charon",
        )
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
    }
}

