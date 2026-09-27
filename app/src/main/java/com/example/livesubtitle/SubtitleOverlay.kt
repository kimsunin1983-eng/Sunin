package com.example.livesubtitle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 다른 앱 위에 떠 있는 자막 창. 끌어서 이동, 두 번 탭하면 종료. */
class SubtitleOverlay(private val context: Context, private val onClose: () -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val original = TextView(context).apply {
        setTextColor(Color.parseColor("#B8B8B8"))
        textSize = 13f
        maxLines = 2
    }

    private val translated = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 4
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#C8000000"))
            cornerRadius = dp(12).toFloat()
        }
        addView(original)
        addView(translated)
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
    }

    fun setStatus(text: String) {
        original.text = ""
        original.visibility = View.GONE
        translated.text = text
    }

    fun setOriginal(text: String) {
        original.visibility = View.VISIBLE
        original.text = text
    }

    fun setTranslated(text: String) {
        translated.text = text
    }

    fun remove() {
        if (attached) {
            runCatching { wm.removeView(root) }
            attached = false
        }
    }
}
