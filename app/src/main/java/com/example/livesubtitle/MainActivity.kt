package com.example.livesubtitle

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/** 번역(홈) / 설정 두 탭을 가진 첫 화면 */
class MainActivity : AppCompatActivity() {

    /** 영상 언어: (표시 이름, 음성 인식용 코드). "auto" 는 Gemini 방식에서만 가능 */
    private val languages = listOf(
        "자동 감지" to "auto",
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
        "필리핀어 (타갈로그)" to "fil-PH",
    )

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var pages: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navTexts: List<TextView>
    private var currentPage = 0

    // 홈
    private lateinit var spinnerSource: Spinner
    private lateinit var buttonStart: TextView
    private lateinit var segPhone: TextView
    private lateinit var segMic: TextView
    private lateinit var chipText: TextView
    private lateinit var chipDot: View
    private lateinit var chip: View
    private lateinit var homeNote: TextView

    // 설정
    private lateinit var editKey: EditText
    private lateinit var segEngines: List<TextView>

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            refreshSettings()
            if (pendingStart) {
                pendingStart = false
                if (result[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO)) {
                    startCaption()
                } else {
                    toast("소리를 듣기 위해 '마이크' 권한이 필요해요.")
                    // 여러 번 거절하면 허용 창이 더 뜨지 않음 → 앱 설정 화면으로 안내.
                    // (창을 그냥 닫은 첫 번째 경우에는 열지 않도록 거절 횟수를 셈)
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    val denied = prefs.getInt("micDenied", 0) + 1
                    prefs.edit().putInt("micDenied", denied).apply()
                    if (denied >= 2 && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) openAppSettings()
                }
            }
        }
    private var pendingStart = false

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
        migratePrefs()

        pages = listOf(findViewById(R.id.pageHome), findViewById(R.id.pageSettings))
        navIcons = listOf(findViewById(R.id.navHomeIcon), findViewById(R.id.navSettingsIcon))
        navTexts = listOf(findViewById(R.id.navHomeText), findViewById(R.id.navSettingsText))
        listOf(R.id.navHome, R.id.navSettings).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener { showPage(i) }
        }

        setupHome()
        setupSettings()
        showPage((savedInstanceState?.getInt("page") ?: 0).coerceIn(0, 1))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", currentPage)
    }

    override fun onResume() {
        super.onResume()
        refreshHome()
        refreshSettings()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentPage != 0) showPage(0) else super.onBackPressed()
    }

    /** 예전 버전의 설정값(언어 번호)을 새 형식(언어 코드)으로 옮김 */
    private fun migratePrefs() {
        if (!prefs.contains("langCode")) {
            val code = if (prefs.contains("lang")) {
                listOf("en-US", "ja-JP", "zh-CN", "zh-TW", "es-ES", "fr-FR", "de-DE", "vi-VN", "th-TH", "id-ID", "ru-RU")
                    .getOrNull(prefs.getInt("lang", 0))
            } else {
                null
            }
            // Gemini 키가 있으면 자동 감지가 기본
            val hasKey = prefs.getString("geminiKey", "").orEmpty().isNotBlank()
            prefs.edit().putString("langCode", if (hasKey) "auto" else code ?: "auto").apply()
        }
    }

    // ───────────────────────── 탭 ─────────────────────────
    private fun showPage(index: Int) {
        currentPage = index
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        val on = ContextCompat.getColor(this, R.color.teal)
        val off = ContextCompat.getColor(this, R.color.textDim)
        navIcons.forEachIndexed { i, v -> v.setColorFilter(if (i == index) on else off) }
        navTexts.forEachIndexed { i, v -> v.setTextColor(if (i == index) on else off) }
        if (index == 0) refreshHome() else refreshSettings()
    }

    private fun selectSegment(views: List<TextView>, selected: Int) {
        val on = ContextCompat.getColor(this, R.color.teal)
        val off = ContextCompat.getColor(this, R.color.textDim)
        views.forEachIndexed { i, v ->
            if (i == selected) {
                v.setBackgroundResource(R.drawable.bg_segment_on)
                v.setTextColor(on)
            } else {
                v.background = null
                v.setTextColor(off)
            }
        }
    }

    // ───────────────────────── 홈 ─────────────────────────
    private fun setupHome() {
        spinnerSource = findViewById(R.id.spinnerSource)
        buttonStart = findViewById(R.id.buttonStart)
        segPhone = findViewById(R.id.segPhone)
        segMic = findViewById(R.id.segMic)
        chip = findViewById(R.id.chipStatus)
        chipText = findViewById(R.id.chipText)
        chipDot = findViewById(R.id.chipDot)
        homeNote = findViewById(R.id.textHomeNote)

        spinnerSource.adapter = ArrayAdapter(this, R.layout.item_spinner, languages.map { it.first }).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("langCode", languages[position].second).apply()
                refreshHome()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Android 13 미만은 폰 소리를 인식기에 직접 넘길 수 없음
        val systemAudioSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (!systemAudioSupported) prefs.edit().putBoolean("mic", true).apply()
        segPhone.setOnClickListener {
            if (systemAudioSupported) {
                prefs.edit().putBoolean("mic", false).apply()
                refreshHome()
            } else {
                toast("폰 소리 직접 가져오기는 Android 13 이상에서 돼요.")
            }
        }
        segMic.setOnClickListener {
            prefs.edit().putBoolean("mic", true).apply()
            refreshHome()
        }

        chip.setOnClickListener { showPage(1) }
        findViewById<View>(R.id.cardCaption).setOnClickListener { toggleCaption() }
        buttonStart.setOnClickListener { toggleCaption() }
        findViewById<View>(R.id.cardConversation).setOnClickListener {
            if (keyOrEmpty().isEmpty()) {
                toast("대화 통역에는 Gemini API 키가 필요해요. 설정에서 입력해 주세요.")
                showPage(1)
                return@setOnClickListener
            }
            if (CaptionService.isRunning) stopService(Intent(this, CaptionService::class.java))
            startActivity(Intent(this, ConversationActivity::class.java))
        }
    }

    private fun refreshHome() {
        if (!::spinnerSource.isInitialized) return
        val code = prefs.getString("langCode", "auto")
        val idx = languages.indexOfFirst { it.second == code }.coerceAtLeast(0)
        if (spinnerSource.selectedItemPosition != idx) spinnerSource.setSelection(idx)

        selectSegment(listOf(segPhone, segMic), if (prefs.getBoolean("mic", false)) 1 else 0)

        val hasKey = keyOrEmpty().isNotEmpty()
        chip.setBackgroundResource(if (hasKey) R.drawable.bg_chip else R.drawable.bg_chip_warn)
        chipDot.setBackgroundResource(if (hasKey) R.drawable.dot_teal else R.drawable.dot_dim)
        chipText.text = if (hasKey) "API 키 입력됨" else "API 키 필요"
        chipText.setTextColor(ContextCompat.getColor(this, if (hasKey) R.color.teal else R.color.warn))

        val note = when {
            effectiveEngine() == "basic" && code == "auto" ->
                "기본 방식은 언어를 자동으로 알아내지 못해요. 영상의 언어를 골라 주세요."
            else -> null
        }
        homeNote.text = note
        homeNote.visibility = if (note == null) View.GONE else View.VISIBLE

        buttonStart.text = if (CaptionService.isRunning) "■  자막 번역 중지" else "▶  자막 번역 시작"
    }

    private fun keyOrEmpty() = prefs.getString("geminiKey", "").orEmpty().trim()

    /** 키가 없으면 Gemini 방식을 쓸 수 없으므로 기본 방식으로 동작 */
    private fun effectiveEngine(): String =
        if (keyOrEmpty().isEmpty()) "basic" else prefs.getString("engine", "live") ?: "live"

    private fun toggleCaption() {
        if (CaptionService.isRunning) {
            stopService(Intent(this, CaptionService::class.java))
            buttonStart.postDelayed({ refreshHome() }, 300)
        } else {
            startCaption()
        }
    }

    private fun startCaption() {
        val code = prefs.getString("langCode", "auto")
        if (effectiveEngine() == "basic") {
            if (code == "auto") {
                toast("기본 방식은 영상의 언어를 직접 골라야 해요.")
                return
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                toast("이 폰에 음성 인식 서비스가 없어요. Play 스토어에서 'Google' 앱을 설치/업데이트해 주세요.")
                return
            }
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
            pendingStart = true
            permissionLauncher.launch(needed.toTypedArray())
            return
        }

        // 2) 다른 앱 위에 표시 권한
        if (!Settings.canDrawOverlays(this)) {
            toast("'세로말'을 찾아 '다른 앱 위에 표시'를 허용한 뒤 돌아와서 다시 눌러 주세요.")
            openOverlaySettings()
            return
        }

        // 3) 소리 입력 방식별 시작
        val useMic = prefs.getBoolean("mic", false) || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (!useMic) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            launchService(CaptionService.MODE_MIC, 0, null)
        }
    }

    private fun launchService(mode: String, resultCode: Int, data: Intent?) {
        val code = prefs.getString("langCode", "auto")
        val lang = languages.firstOrNull { it.second == code } ?: languages[0]
        val intent = Intent(this, CaptionService::class.java).apply {
            // 자동 감지일 때 기본 방식으로 넘어가게 되면 영어로 인식
            putExtra(CaptionService.EXTRA_LANG, if (lang.second == "auto") "en-US" else lang.second)
            putExtra(CaptionService.EXTRA_LANG_NAME, lang.first)
            putExtra(CaptionService.EXTRA_MODE, mode)
            putExtra(CaptionService.EXTRA_OFFLINE, prefs.getBoolean("offline", true))
            putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(CaptionService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        buttonStart.text = "■  자막 번역 중지"
        toast("자막을 시작했어요. 이제 영상 앱을 열어 재생하세요.")
        moveTaskToBack(true)
    }

    // ───────────────────────── 설정 ─────────────────────────
    private fun setupSettings() {
        editKey = findViewById(R.id.editKey)
        editKey.setText(prefs.getString("geminiKey", ""))
        editKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("geminiKey", s?.toString().orEmpty().trim()).apply()
            }
        })
        val showKey = findViewById<TextView>(R.id.buttonShowKey)
        var keyVisible = false
        showKey.setOnClickListener {
            keyVisible = !keyVisible
            editKey.inputType = InputType.TYPE_CLASS_TEXT or
                (if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            editKey.setSelection(editKey.text.length)
            showKey.text = if (keyVisible) "숨기기" else "보기"
        }
        findViewById<View>(R.id.buttonCheckKey).setOnClickListener { runDiagnosis() }
        findViewById<View>(R.id.rowDiagnose).setOnClickListener { runDiagnosis() }

        segEngines = listOf(findViewById(R.id.segLive), findViewById(R.id.segListen), findViewById(R.id.segBasic))
        val engineCodes = listOf("live", "listen", "basic")
        segEngines.forEachIndexed { i, v ->
            v.setOnClickListener {
                prefs.edit().putString("engine", engineCodes[i]).apply()
                refreshSettings()
            }
        }

        findViewById<View>(R.id.rowInput).setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                toast("폰 소리 직접 가져오기는 Android 13 이상에서 돼요.")
            } else {
                prefs.edit().putBoolean("mic", !prefs.getBoolean("mic", false)).apply()
                refreshSettings()
            }
        }
        findViewById<View>(R.id.rowLanguage).setOnClickListener {
            val current = languages.indexOfFirst { it.second == prefs.getString("langCode", "auto") }
            AlertDialog.Builder(this)
                .setTitle("영상 언어")
                .setSingleChoiceItems(languages.map { it.first }.toTypedArray(), current) { dialog, which ->
                    prefs.edit().putString("langCode", languages[which].second).apply()
                    refreshSettings()
                    dialog.dismiss()
                }
                .show()
        }

        val switchOriginal = findViewById<SwitchCompat>(R.id.switchOriginal)
        switchOriginal.isChecked = prefs.getBoolean("showOriginal", false)
        switchOriginal.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("showOriginal", checked).apply()
        }
        findViewById<View>(R.id.rowOriginal).setOnClickListener { switchOriginal.toggle() }

        // 자막 줄 수: 누를 때마다 2 → 3 → 4 → 2
        findViewById<View>(R.id.rowLines).setOnClickListener {
            val next = when (prefs.getInt("captionLines", 3)) {
                2 -> 3
                3 -> 4
                else -> 2
            }
            prefs.edit().putInt("captionLines", next).apply()
            refreshSettings()
        }

        val switchRefine = findViewById<SwitchCompat>(R.id.switchRefine)
        switchRefine.isChecked = prefs.getBoolean("refine", true)
        switchRefine.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("refine", checked).apply()
        }
        findViewById<View>(R.id.rowRefine).setOnClickListener { switchRefine.toggle() }

        val switchOffline = findViewById<SwitchCompat>(R.id.switchOffline)
        switchOffline.isChecked = prefs.getBoolean("offline", true)
        switchOffline.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("offline", checked).apply()
        }
        findViewById<View>(R.id.rowOffline).setOnClickListener { switchOffline.toggle() }

        findViewById<View>(R.id.rowPermMic).setOnClickListener {
            if (granted(Manifest.permission.RECORD_AUDIO)) openAppSettings()
            else permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        findViewById<View>(R.id.rowPermNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                openAppSettings()
            }
        }
        findViewById<View>(R.id.rowPermOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.rowLastError).setOnClickListener {
            val err = prefs.getString("lastLiveError", null)
            if (err == null) {
                toast("최근 오류가 없어요.")
            } else {
                showCopyDialog("최근 오류", err) {
                    prefs.edit().remove("lastLiveError").apply()
                    refreshSettings()
                }
            }
        }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.textVersion).text =
            "세로말 - 실시간 자막 대화 통역\n" +
                "세상 서로 말의 의미를 담아, 언어가 다른 사람들의 이해를 돕는 이름입니다.\n\n" +
                "버전 ${version ?: "-"}"
    }

    private fun refreshSettings() {
        if (!::segEngines.isInitialized) return
        val engine = prefs.getString("engine", "live")
        val idx = when (engine) {
            "listen" -> 1
            "basic" -> 2
            else -> 0
        }
        selectSegment(segEngines, idx)
        findViewById<TextView>(R.id.valueEngine).text = listOf("실시간 통역", "듣기 번역", "기본")[idx]
        val help = when (idx) {
            0 -> "가장 빠름. Gemini가 소리를 실시간으로 듣고 번역해요. 언어 자동 감지. 데이터를 많이 써요."
            1 -> "소리를 문장 단위로 Gemini에 보내 번역해요. 실시간보다 조금 늦게 나와요. 언어 자동 감지."
            else -> "폰 음성 인식 + Google 번역. 키가 있으면 Gemini가 문장을 다듬어요. 영상 언어를 골라야 해요."
        }
        val noKey = if (keyOrEmpty().isEmpty() && idx != 2) "\n⚠ API 키가 없어서 지금은 기본 방식으로 동작해요." else ""
        findViewById<TextView>(R.id.textEngineHelp).text = help + noKey

        findViewById<TextView>(R.id.valueInput).text = if (prefs.getBoolean("mic", false)) "마이크" else "폰 소리"
        val code = prefs.getString("langCode", "auto")
        findViewById<TextView>(R.id.valueLanguage).text = languages.firstOrNull { it.second == code }?.first ?: "자동 감지"
        findViewById<View>(R.id.rowOffline).visibility = if (idx == 2) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.valueLines).text = "${prefs.getInt("captionLines", 3)}줄"

        setPermission(R.id.valuePermMic, granted(Manifest.permission.RECORD_AUDIO))
        setPermission(
            R.id.valuePermNotif,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        )
        setPermission(R.id.valuePermOverlay, Settings.canDrawOverlays(this))

        val err = prefs.getString("lastLiveError", null)
        findViewById<TextView>(R.id.valueLastError).apply {
            text = if (err == null) "없음" else "있음 · 눌러서 보기"
            setTextColor(ContextCompat.getColor(this@MainActivity, if (err == null) R.color.textDim else R.color.warn))
        }
    }

    private fun setPermission(id: Int, ok: Boolean) {
        findViewById<TextView>(id).apply {
            text = if (ok) "허용됨" else "허용 필요"
            setTextColor(ContextCompat.getColor(this@MainActivity, if (ok) R.color.teal else R.color.warn))
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** 키·모델 연결 상태를 검사해 결과를 보여 줌 (복사해서 보낼 수 있게) */
    private fun runDiagnosis() {
        val key = keyOrEmpty()
        if (key.isEmpty()) {
            toast("먼저 Gemini API 키를 입력하세요.")
            return
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("연결 확인 중…")
            .setMessage("최대 30초 정도 걸려요.")
            .setCancelable(false)
            .show()
        Thread {
            val report = Diagnostics.run(key)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                showCopyDialog("연결 진단 결과", report, null)
            }
        }.start()
    }

    private fun showCopyDialog(title: String, body: String, onClear: (() -> Unit)?) {
        val tv = TextView(this).apply {
            text = body
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 0)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("복사") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(title, body))
                toast("복사했어요.")
            }
            .setNegativeButton("닫기", null)
        if (onClear != null) builder.setNeutralButton("지우기") { _, _ -> onClear() }
        builder.show()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
