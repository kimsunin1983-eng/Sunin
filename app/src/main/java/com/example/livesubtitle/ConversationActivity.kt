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
 * 각 연결은 자기 언어로 들어온 말은 그대로 따라 말하는데(echoTargetLanguage=true),
 * 앱이 받아쓰기 글자로 말한 언어를 판단해 그 언어 쪽 귀의 소리를 막는다.
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

        // ── 한 번의 말(턴) 단위 판단: 모든 접근은 메인 스레드 ──
        /** 이 연결이 받아쓴 원문 조각 (도착 시각, 글자) */
        val heard = ArrayList<Pair<Long, String>>()
        var turn = Turn.NONE
        var turnStartAt = 0L
        var prevTurnStartAt = 0L
        var lastOutputAt = 0L
        val pendingAudio = ArrayList<ByteArray>()
        val pendingText = StringBuilder()
        val decideTimeout = Runnable { decide(this, force = true) }
        val script get() = scriptOfLanguage(langCode)
    }

    /** NONE: 말 없음, UNDECIDED: 누구 말인지 확인 중(소리를 붙잡아 둠), PLAY: 틀어 줌, MUTE: 막음 */
    private enum class Turn { NONE, UNDECIDED, PLAY, MUTE }

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
        recentPlayed.clear()
        history.clear()
        sides.forEach { side ->
            side.failures = 0
            side.ready = false
            side.lastInputLang = ""
            side.heard.clear()
            side.turn = Turn.NONE
            side.turnStartAt = 0L
            side.prevTurnStartAt = 0L
            side.lastOutputAt = 0L
            side.pendingAudio.clear()
            side.pendingText.setLength(0)
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

                override fun onInputText(delta: String) = ui {
                    if (side.client !== client) return@ui
                    side.heard += android.os.SystemClock.elapsedRealtime() to delta
                    while (side.heard.size > 200) side.heard.removeAt(0)
                    if (side.turn == Turn.UNDECIDED) decide(side, force = false)
                }

                override fun onInputLanguage(code: String) {
                    side.lastInputLang = code
                }

                override fun onAudio(pcm: ByteArray) = ui {
                    if (!running || side.client !== client) return@ui
                    onOutput(side)
                    when (side.turn) {
                        Turn.PLAY -> play(side, pcm)
                        Turn.UNDECIDED -> side.pendingAudio += pcm
                        else -> {}
                    }
                }

                override fun onOutputText(delta: String) = ui {
                    if (side.client !== client) return@ui
                    side.failures = 0
                    onOutput(side)
                    when (side.turn) {
                        Turn.PLAY -> showText(side, delta)
                        Turn.UNDECIDED -> {
                            side.pendingText.append(delta)
                            decide(side, force = false)
                        }
                        else -> {}
                    }
                }

                override fun onTurnComplete() = ui {
                    if (side.client === client) {
                        finalizeLine(side)
                        endTurn(side)
                    }
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
            // false 로 두면 모델이 번역 대신 대답을 지어내는 일이 있어 true(따라 말하기)로 두고,
            // 따라 말한 소리는 앱이 누구 말인지 판단해서 막는다 (judge)
            echoTarget = true,
        )
        side.client = client
        client.connect()
    }

    // ───────────────────────── 누구 말인지 판단 ─────────────────────────
    // 번역 모델은 같은 언어를 들으면 그대로 따라 말한다. 그래서 소리가 오면 바로 틀지 않고
    // 받아쓰기가 올 때까지 잠깐 붙잡아 둔 뒤, 이쪽 사람이 한 말이면 버리고 상대 말이면 튼다.

    /** 방금 이어폰으로 틀어 준 문장들 (시각, 정규화한 글자) — 마이크가 그 소리를 다시 들었는지 확인용 */
    private val recentPlayed = ArrayList<Pair<Long, String>>()

    /** 번역 소리/글자가 도착할 때마다 호출: 새 말의 시작이면 판단 대기 상태로 */
    private fun onOutput(side: Side) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (side.turn == Turn.NONE || now - side.lastOutputAt > 1200) {
            if (side.turn != Turn.NONE) endTurn(side)
            side.turn = Turn.UNDECIDED
            side.prevTurnStartAt = side.turnStartAt
            side.turnStartAt = now
            main.postDelayed(side.decideTimeout, 1500) // 받아쓰기가 끝내 안 오면 그냥 틂
            side.lastOutputAt = now
            decide(side, force = false)
            return
        }
        side.lastOutputAt = now
    }

    private fun endTurn(side: Side) {
        main.removeCallbacks(side.decideTimeout)
        side.turn = Turn.NONE
        side.pendingAudio.clear()
        side.pendingText.setLength(0)
    }

    private fun decide(side: Side, force: Boolean) {
        if (side.turn != Turn.UNDECIDED) return
        val verdict = judge(side) ?: if (force) Turn.PLAY else return
        main.removeCallbacks(side.decideTimeout)
        side.turn = verdict
        if (verdict == Turn.PLAY) {
            side.pendingAudio.forEach { play(side, it) }
            if (side.pendingText.isNotEmpty()) showText(side, side.pendingText.toString())
        }
        side.pendingAudio.clear()
        side.pendingText.setLength(0)
    }

    /** PLAY: 상대 말 → 번역을 틀어 줌, MUTE: 본인 말이거나 메아리 → 버림, null: 아직 모름 */
    private fun judge(side: Side): Turn? {
        val other = if (side.isLeft) right else left
        // 이번 말의 원문 = 직전 말이 시작된 뒤에 받아쓴 것
        val heardNow = side.heard.filter { it.first > side.prevTurnStartAt }
        val heardText = heardNow.joinToString("") { it.second }
        val heardNorm = normalize(heardText)
        if (heardNorm.isEmpty()) return null

        // (1) 메아리: 이어폰으로 틀고 있는(또는 방금 다 튼) 문장을 마이크가 다시 들음.
        //     사람이 같은 말로 대답하는 경우와 구분하려고, 재생이 끝난 지 1초 안에 들린 것만 메아리로 봄.
        val now = android.os.SystemClock.elapsedRealtime()
        val heardAt = heardNow.first().first
        recentPlayed.removeAll { now - it.first > 8000 }
        val playing = sides.map { normalize(it.current.toString()) }
        val justPlayed = recentPlayed.filter { heardAt < it.first + 1000 }.map { it.second }
        if ((playing + justPlayed).any { sameWords(it, heardNorm) }) return Turn.MUTE

        // (2) 글자 종류로 말한 언어 판단 (한글/가나/한자/라틴…)
        val heardScript = detectScript(heardText) ?: return null
        val mine = heardScript == side.script ||
            (heardScript == "han" && side.script == "ja" && other.script != "han")
        if (!mine) return Turn.PLAY
        if (side.script != other.script) return Turn.MUTE

        // (3) 두 언어의 글자 종류가 같을 때(영어↔스페인어 등): 번역문이 원문과 같으면 따라 말한 것
        val outNorm = normalize(side.pendingText.toString())
        if (outNorm.length < 6) return null
        return if (heardNorm.contains(outNorm) || outNorm.contains(heardNorm)) Turn.MUTE else Turn.PLAY
    }

    private fun play(side: Side, pcm: ByteArray) {
        val track = side.track ?: return
        val stereo = toOneEar(pcm, side.isLeft)
        runCatching { side.player?.execute { runCatching { track.write(stereo, 0, stereo.size) } } }
    }

    private fun showText(side: Side, delta: String) {
        side.current.append(delta)
        renderLog()
        main.removeCallbacks(side.finalizeRunnable)
        main.postDelayed(side.finalizeRunnable, 2500)
    }

    private fun normalize(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    private fun sameWords(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))
    }

    private fun detectScript(text: String): String? {
        var ko = 0; var kana = 0; var han = 0; var latin = 0; var cyr = 0; var thai = 0
        for (c in text) {
            when (c) {
                in '\uAC00'..'\uD7A3', in '\u3131'..'\u318E', in '\u1100'..'\u11FF' -> ko++
                in '\u3040'..'\u30FF' -> kana++
                in '\u4E00'..'\u9FFF' -> han++
                in '\u0400'..'\u04FF' -> cyr++
                in '\u0E00'..'\u0E7F' -> thai++
                in 'a'..'z', in 'A'..'Z', in '\u00C0'..'\u024F', in '\u1E00'..'\u1EFF' -> latin++
            }
        }
        val best = listOf("ko" to ko, "ja" to kana * 3, "han" to han, "cyr" to cyr, "thai" to thai, "latin" to latin)
            .maxByOrNull { it.second } ?: return null
        return if (best.second == 0) null else best.first
    }

    // ───────────────────────── 화면 표시 ─────────────────────────
    private fun finalizeLine(side: Side) {
        main.removeCallbacks(side.finalizeRunnable)
        val text = side.current.toString().trim()
        side.current.setLength(0)
        if (text.isNotEmpty()) {
            recentPlayed += side.lastOutputAt to normalize(text) // 마지막 소리가 온 시각 기준
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

    private fun scriptOfLanguage(code: String) = when (code.lowercase().substringBefore('-')) {
        "ko" -> "ko"
        "ja" -> "ja"
        "zh" -> "han"
        "ru" -> "cyr"
        "th" -> "thai"
        else -> "latin"
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
