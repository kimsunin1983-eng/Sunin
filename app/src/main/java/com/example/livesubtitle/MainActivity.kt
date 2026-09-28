package com.example.livesubtitle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val languages = listOf(
        "영어" to "en-US",
        "일본어" to "ja-JP",
        "중국어 (중국 본토)" to "zh-CN",
        "중국어 (대만)" to "zh-TW",
        "스페인어" to "es-ES",
        "프랑스어" to "fr-FR",
        "독일어" to "de-DE",
        "베트남어" to "vi-VN",
        "태국어" to "th-TH",
        "인도네시아어" to "id-ID",
        "러시아어" to "ru-RU",
    )

    private lateinit var spinner: Spinner
    private lateinit var radioSystem: RadioButton
    private lateinit var radioMic: RadioButton
    private lateinit var checkOffline: CheckBox
    private lateinit var button: Button
    private lateinit var info: TextView
    private lateinit var editKey: EditText
    private lateinit var checkLive: CheckBox

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                start()
            } else {
                toast("소리를 듣기 위해 '마이크' 권한이 필요해요.")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                launchService(CaptionService.MODE_SYSTEM, result.resultCode, data)
            } else {
                toast("폰 소리를 가져오려면 화면 공유(녹화)를 허용해야 해요.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinner = findViewById(R.id.spinnerLang)
        radioSystem = findViewById(R.id.radioSystem)
        radioMic = findViewById(R.id.radioMic)
        checkOffline = findViewById(R.id.checkOffline)
        button = findViewById(R.id.buttonStart)
        info = findViewById(R.id.textInfo)
        editKey = findViewById(R.id.editKey)
        editKey.setText(prefs.getString("geminiKey", ""))
        checkLive = findViewById(R.id.checkLive)
        checkLive.isChecked = prefs.getBoolean("live", true)

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first }
        )
        spinner.setSelection(prefs.getInt("lang", 0).coerceIn(0, languages.lastIndex))
        checkOffline.isChecked = prefs.getBoolean("offline", true)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 인식기에 오디오를 직접 넘기는 기능은 Android 13부터 지원
            radioSystem.isEnabled = false
            radioMic.isChecked = true
        } else if (prefs.getBoolean("mic", false)) {
            radioMic.isChecked = true
        }

        info.text = """
            사용 방법
            1. 영상의 언어를 고르고 '자막 시작'을 누르세요.
            2. 처음에는 권한 몇 가지를 허용해야 해요 (마이크, 다른 앱 위에 표시, 화면 공유).
               화면 공유 창이 뜨면 '전체 화면'을 고르세요. 화면은 녹화하지 않고 소리만 사용해요.
            3. 유튜브 등에서 영상을 재생하면 화면 아래에 자막이 나와요.

            자막 창 조작
            • 끌어서 위치 이동
            • 두 번 탭하면 종료 (알림창의 '중지'로도 종료)

            참고
            • 'Gemini 실시간 통역'을 켜면 Gemini가 소리를 직접 듣고 바로 번역해요. 영상 언어는 자동으로 알아내요.
              번역 음성까지 함께 내려받아서 데이터를 많이 써요 (1시간에 약 200MB 이상). Wi-Fi에서 쓰세요.
              연결이 안 되면 자동으로 아래 기본 방식으로 바뀌어요.
            • 기본 방식에서는 자막이 세 단계로 바뀌어요: 흐린 글씨(말하는 중) → 조금 흐린 글씨(빠른 초벌 번역) → 선명한 글씨(Gemini가 다듬은 번역).
            • Gemini 키가 없으면 Google 번역만 써요. 무료 한도를 넘으면 잠시 초벌 번역만 나와요.
            • 넷플릭스처럼 소리 녹음을 막아 둔 앱은 '폰 소리 직접'이 동작하지 않아요. 이때는 '마이크로 듣기'를 쓰세요.
            • 자막이 안 나오면 '오프라인 음성 인식 우선 사용'을 끄고 다시 시도해 보세요.
        """.trimIndent()

        info.setTextIsSelectable(true)

        button.setOnClickListener {
            if (CaptionService.isRunning) {
                stopService(Intent(this, CaptionService::class.java))
                button.postDelayed({ updateButton() }, 300)
            } else {
                start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun updateButton() {
        button.text = if (CaptionService.isRunning) "자막 중지" else "자막 시작"
        val err = prefs.getString("lastLiveError", null)
        val base = info.tag as? String ?: info.text.toString().also { info.tag = it }
        info.text = if (err != null) {
            "⚠ 최근 실시간 통역 연결 실패 사유 (길게 눌러 복사):\n$err\n\n$base"
        } else {
            base
        }
    }

    private fun start() {
        prefs.edit()
            .putInt("lang", spinner.selectedItemPosition)
            .putBoolean("offline", checkOffline.isChecked)
            .putBoolean("mic", radioMic.isChecked)
            .putString("geminiKey", editKey.text.toString().trim())
            .putBoolean("live", checkLive.isChecked)
            .apply()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("이 폰에 음성 인식 서비스가 없어요. Play 스토어에서 'Google' 앱을 설치/업데이트해 주세요.")
            return
        }

        // 1) 마이크(+알림) 권한
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS) &&
            !prefs.getBoolean("askedNotif", false)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            prefs.edit().putBoolean("askedNotif", true).apply()
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return
        }

        // 2) 다른 앱 위에 표시 권한
        if (!Settings.canDrawOverlays(this)) {
            toast("'실시간 자막 번역'을 찾아 '다른 앱 위에 표시'를 허용한 뒤 돌아와서 다시 눌러 주세요.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        // 3) 모드별 시작
        if (radioSystem.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            launchService(CaptionService.MODE_MIC, 0, null)
        }
    }

    private fun launchService(mode: String, resultCode: Int, data: Intent?) {
        val intent = Intent(this, CaptionService::class.java).apply {
            putExtra(CaptionService.EXTRA_LANG, languages[spinner.selectedItemPosition].second)
            putExtra(CaptionService.EXTRA_LANG_NAME, languages[spinner.selectedItemPosition].first)
            putExtra(CaptionService.EXTRA_MODE, mode)
            putExtra(CaptionService.EXTRA_OFFLINE, checkOffline.isChecked)
            putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(CaptionService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        button.text = "자막 중지"
        toast("자막을 시작했어요. 이제 영상 앱을 열어 재생하세요.")
        moveTaskToBack(true)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
