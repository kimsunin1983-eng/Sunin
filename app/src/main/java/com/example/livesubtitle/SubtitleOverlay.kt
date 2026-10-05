package com.example.livesubtitle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 다른 앱 위에 떠 있는 자막 창. 끌어서 이동, 두 번 탭하면 종료.
 *
 *   ───            ← 손잡이
 *   ● 영어 → 한국어        초벌 번역
 *   직전 문장 (완성, 선명)
 *   지금 문장 (초벌은 은은하게 → 완성되면 선명하게)
 */
class SubtitleOverlay(
    private val context: Context,
    /** 예: "자동 감지 → 한국어" */
    private val label: String,
    private val showOriginal: Boolean,
    private val onClose: () -> Unit,
) {
    /** PARTIAL: 말하는 중, DRAFT: 초벌 번역, FINAL: 다듬어진 번역 */
    enum class Tone(val alpha: Float, val label: String) {
        PARTIAL(0.55f, "듣는 중"),
        DRAFT(0.7f, "초벌 번역"),
        FINAL(1f, "완성"),
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val teal = ContextCompat.getColor(context, R.color.teal)
    private val dim = ContextCompat.getColor(context, R.color.textDim)

    private val handle = View(context).apply {
        setBackgroundResource(R.drawable.bg_handle)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
    }

    private val dot = View(context).apply {
        setBackgroundResource(R.drawable.dot_teal)
        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(8) }
    }
    private val labelView = TextView(context).apply {
        text = label
        setTextColor(teal)
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    private val stateView = TextView(context).apply {
        setTextColor(dim)
        textSize = 12f
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot)
        addView(labelView)
        addView(stateView)
    }

    private fun line() = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 3
        setLineSpacing(dp(2).toFloat(), 1f)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private val previous = line().apply { visibility = View.GONE }
    private val current = line()

    private val original = TextView(context).apply {
        setTextColor(dim)
        textSize = 12f
        maxLines = 2
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private val hint = TextView(context).apply {
        text = "드래그로 이동 · 두 번 탭하여 닫기"
        setTextColor(dim)
        textSize = 11f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(14))
        setBackgroundResource(R.drawable.bg_overlay)
        addView(handle)
        addView(header)
        addView(previous)
        addView(original)
        addView(current)
        addView(hint)
    }

    private val params = WindowManager.LayoutParams(
        (context.resources.displayMetrics.widthPixels * 0.94).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(90)
    }

    private var attached = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onClose()
                return true
            }
        })
        var lastX = 0f
        var lastY = 0f
        root.setOnTouchListener { _: View, e: MotionEvent ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x += (e.rawX - lastX).toInt()
                    params.y -= (e.rawY - lastY).toInt() // BOTTOM 기준이라 위로 끌면 y 증가
                    lastX = e.rawX; lastY = e.rawY
                    if (attached) wm.updateViewLayout(root, params)
                }
            }
            true
        }
        wm.addView(root, params)
        attached = true
        // 조작 안내는 잠깐만 보여 줌
        root.postDelayed({ hint.visibility = View.GONE }, 8000)
    }

    /** 안내·오류 문구 한 줄 */
    fun setStatus(text: String) {
        previous.visibility = View.GONE
        original.visibility = View.GONE
        stateView.text = ""
        current.text = text
        current.alpha = 0.85f
    }

    /** 위: 직전 문장 / (원문) / 아래: 지금 문장 */
    fun render(previousText: String?, originalText: String, currentText: String, tone: Tone) {
        if (previousText.isNullOrBlank()) {
            previous.visibility = View.GONE
        } else {
            previous.text = previousText
            previous.visibility = View.VISIBLE
        }
        original.text = originalText
        original.visibility = if (!showOriginal || originalText.isBlank()) View.GONE else View.VISIBLE
        current.text = currentText
        current.alpha = tone.alpha
        stateView.text = tone.label
    }

    fun remove() {
        if (attached) {
            runCatching { wm.removeView(root) }
            attached = false
        }
    }
}
