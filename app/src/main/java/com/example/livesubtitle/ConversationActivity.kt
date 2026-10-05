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
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 대화 통역: 무선 이어폰을 한쪽씩 나눠 끼고 서로 다른 언어로 대화.
 *
 *  폰 마이크 ──┬─▶ Gemini 실시간 번역 (→ 왼쪽 사람 언어) ─▶ 왼쪽 이어폰에만 재생
 *              └─▶ Gemini 실시간 번역 (→ 오른쪽 사람 언어) ─▶ 오른쪽 이어폰에만 재생
 *
 * 각 연결은 "이미 그 언어로 말한 것"은 따라 말하지 않도록 설정(echoTargetLanguage=false)하고,
 * 서버가 알려 준 입력 언어가 목표 언어와 같으면 앱에서도 한 번 더 막는다.
 */
class ConversationActivity : AppCompatActivity() {

    private val languages = listOf(
        "한국어" to "ko",
        "영어" to "en",
        "일본어" to "ja",
        "중국어 (간체)" to "zh-Hans",
        "중국어 (번체 · 대만)" to "zh-Hant",
        "스페인어" to "es",
        "프랑스어" to "fr",
        "독일어" to "de",
        "베트남어" to "vi",
        "태국어" to "th",
        "인도네시아어" to "id",
        "러시아어" to "ru",
    )

    /** 이어폰 한쪽 = 그 사람이 듣는 언어로 번역하는 연결 하나 */
    private inner class Side(val label: String, val isLeft: Boolean) {
        var langName = ""
        var langCode = ""
        @Volatile var client: LiveTranslateClient? = null
        var track: AudioTrack? = null
        var player: ExecutorService? = null
        @Volatile var lastInputLang = ""
        val current = StringBuilder()
        var failures = 0
        var ready = false
        val finalizeRunnable = Runnable { finalizeLine(this) }
    }

    private val left = Side("왼쪽", true)
    private val right = Side("오른쪽", false)
    private val sides get() = listOf(left, right)

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var spinnerLeft: Spinner
    private lateinit var spinnerRight: Spinner
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    @Volatile private var running = false
    @Volatile private var micActive = false
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val history = ArrayList<String>()
    private var apiKey = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else toast("대화를 듣기 위해 '마이크' 권한이 필요해요.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)
        title = "대화 통역"

        spinnerLeft = findViewById(R.id.spinnerLeft)
        spinnerRight = findViewById(R.id.spinnerRight)
        button = findViewById(R.id.buttonTalk)
        status = findViewById(R.id.textStatus)
        log = findViewById(R.id.textLog)
        scroll = findViewById(R.id.scrollLog)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
        spinnerLeft.adapter = adapter
        spinnerRight.adapter = adapter
        spinnerLeft.setSelection(prefs.getInt("talkLeft", 1).coerceIn(0, languages.lastIndex))   // 영어
        spinnerRight.setSelection(prefs.getInt("talkRight", 0).coerceIn(0, languages.lastIndex)) // 한국어

        status.text = "이어폰을 한쪽씩 나눠 끼고, 폰은 두 사람 사이에 두세요."
        button.setOnClickListener { if (running) stop("중지했어요.") else start() }
    }

    override fun onStop() {
        super.onStop()
        if (running) stop("화면을 벗어나 중지했어요.")
    }

    // ───────────────────────── 시작 / 중지 ─────────────────────────
    private fun start() {
        apiKey = prefs.getString("geminiKey", "").orEmpty().trim()
        if (apiKey.isEmpty()) {
            toast("먼저 첫 화면에서 Gemini API 키를 입력해 주세요.")
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

        left.langName = languages[spinnerLeft.selectedItemPosition].first
        left.langCode = languages[spinnerLeft.selectedItemPosition].second
        right.langName = languages[spinnerRight.selectedItemPosition].first
        right.langCode = languages[spinnerRight.selectedItemPosition].second

        if (!startMic()) {
            toast("마이크를 열지 못했어요.")
            return
        }
        running = true
        history.clear()
        sides.forEach { side ->
            side.failures = 0
            side.ready = false
            side.lastInputLang = ""
            side.current.setLength(0)
            side.track = newTrack().also { it.play() }
            side.player = Executors.newSingleThreadExecutor()
            connect(side)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spinnerLeft.isEnabled = false
        spinnerRight.isEnabled = false
        button.text = "대화 통역 중지"
        updateStatus()
        renderLog()
    }

    private fun stop(message: String) {
        running = false
        micActive = false
        main.removeCallbacksAndMessages(null)
        runCatching { record?.stop() }
        runCatching { captureThread?.join(500) }
        runCatching { record?.release() }
        record = null
        sides.forEach { side ->
            runCatching { side.client?.close() }
            side.client = null
            runCatching { side.player?.shutdownNow() }
            runCatching { side.track?.pause(); side.track?.flush(); side.track?.release() }
            side.track = null
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spinnerLeft.isEnabled = true
        spinnerRight.isEnabled = true
        button.text = "대화 통역 시작"
        status.text = message
    }

    // ───────────────────────── 마이크 (폰 본체 마이크) ─────────────────────────
    @SuppressLint("MissingPermission")
    private fun startMic(): Boolean = try {
        val minBuf = AudioRecord.getMinBufferSize(
            IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, IN_RATE,
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
                    left.client?.sendAudio(buf, n)
                    right.client?.sendAudio(buf, n)
                }
            }
            true
        }
    } catch (e: Exception) {
        false
    }

    // ───────────────────────── 재생 (한쪽 귀에만) ─────────────────────────
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
    }

    /** mono PCM → 한쪽 채널에만 소리가 있는 stereo PCM */
    private fun toOneEar(mono: ByteArray, leftEar: Boolean): ByteArray {
        val out = ByteArray(mono.size * 2)
        var i = 0
        val offset = if (leftEar) 0 else 2
        while (i + 1 < mono.size) {
            out[i * 2 + offset] = mono[i]
            out[i * 2 + offset + 1] = mono[i + 1]
            i += 2
        }
        return out
    }

    // ───────────────────────── Gemini 연결 ─────────────────────────
    private fun connect(side: Side) {
        if (!running) return
        lateinit var client: LiveTranslateClient
        client = LiveTranslateClient(
            apiKey, false, true,
            object : LiveTranslateClient.Listener {
                override fun onReady() = ui {
                    if (side.client === client) {
                        side.ready = true
                        updateStatus()
                    }
                }

                override fun onInputText(delta: String) {}

                override fun onInputLanguage(code: String) {
                    side.lastInputLang = code
                }

                override fun onAudio(pcm: ByteArray) {
                    if (!running || side.client !== client || sameLanguage(side)) return
                    val stereo = toOneEar(pcm, side.isLeft)
                    val track = side.track ?: return
                    runCatching { side.player?.execute { runCatching { track.write(stereo, 0, stereo.size) } } }
                }

                override fun onOutputText(delta: String) = ui {
                    if (side.client !== client || sameLanguage(side)) return@ui
                    side.failures = 0
                    side.current.append(delta)
                    renderLog()
                    main.removeCallbacks(side.finalizeRunnable)
                    main.postDelayed(side.finalizeRunnable, 2500)
                }

                override fun onTurnComplete() = ui {
                    if (side.client === client) finalizeLine(side)
                }

                override fun onClosed(error: String?, gotOutput: Boolean) = ui {
                    if (!running || side.client !== client) return@ui
                    side.client = null
                    side.ready = false
                    finalizeLine(side)
                    if (gotOutput || error == null || error == "goAway") {
                        // 서버 세션 시간 제한 등 → 바로 다시 연결
                        main.postDelayed({ connect(side) }, 300)
                    } else {
                        side.failures++
                        if (side.failures < 3) {
                            main.postDelayed({ connect(side) }, 1500L * side.failures)
                        } else {
                            stop("${side.label}(${side.langName}) 통역에 연결하지 못했어요.\n사유: ${error?.take(160)}")
                        }
                    }
                    if (running) updateStatus()
                }
            },
            targetLanguage = side.langCode,
            echoTarget = false,
        )
        side.client = client
        client.connect()
    }

    /** 방금 들은 말이 이미 이쪽 사람의 언어면 번역해 줄 필요가 없음 */
    private fun sameLanguage(side: Side): Boolean {
        val heard = side.lastInputLang.lowercase().substringBefore('-')
        return heard.isNotEmpty() && heard == side.langCode.lowercase().substringBefore('-')
    }

    // ───────────────────────── 화면 표시 ─────────────────────────
    private fun finalizeLine(side: Side) {
        main.removeCallbacks(side.finalizeRunnable)
        val text = side.current.toString().trim()
        side.current.setLength(0)
        if (text.isNotEmpty()) {
            history += lineOf(side, text)
            while (history.size > 60) history.removeAt(0)
        }
        renderLog()
    }

    private fun lineOf(side: Side, text: String) =
        (if (side.isLeft) "◀ " else "▶ ") + "${side.label} · ${side.langName}\n$text"

    private fun renderLog() {
        val sb = StringBuilder()
        history.forEach { sb.append(it).append("\n\n") }
        sides.forEach { s ->
            val cur = s.current.toString().trim()
            if (cur.isNotEmpty()) sb.append(lineOf(s, cur)).append(" …\n\n")
        }
        log.text = sb
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateStatus() {
        val conn = sides.joinToString("   ") { "${it.label}(${it.langName}) " + if (it.ready) "✅" else "연결 중…" }
        val warn = if (headphonesConnected()) "" else
            "\n⚠ 이어폰이 연결되지 않았어요. 폰 스피커로 나오면 마이크가 그 소리를 다시 들어요."
        status.text = conn + warn
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

    private fun ui(block: () -> Unit) {
        main.post { if (!isFinishing && !isDestroyed) block() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
    }
}
