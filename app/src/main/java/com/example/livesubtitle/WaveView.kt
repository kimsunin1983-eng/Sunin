package com.example.livesubtitle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** 마이크 소리 크기를 막대 물결로 보여 주는 뷰 (왼쪽 청록 → 오른쪽 보라) */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bars = 44
    private val levels = FloatArray(bars)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val teal = ContextCompat.getColor(context, R.color.teal)
    private val purple = ContextCompat.getColor(context, R.color.purple)

    /** level: 0.0 ~ 1.0. 어느 스레드에서 불러도 됨 */
    fun push(level: Float) {
        synchronized(levels) {
            levels[head] = level.coerceIn(0f, 1f)
            head = (head + 1) % bars
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(levels) { levels.fill(0f) }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val slot = w / bars
        val barW = slot * 0.45f
        val minH = barW
        synchronized(levels) {
            for (i in 0 until bars) {
                // 가장 최근 소리가 가운데 오도록 좌우 대칭으로 배치
                val age = if (i < bars / 2) bars / 2 - 1 - i else i - bars / 2
                val level = levels[((head - 1 - age) % bars + bars) % bars]
                val barH = minH + (h - minH) * level
                val cx = slot * i + slot / 2
                rect.set(cx - barW / 2, (h - barH) / 2, cx + barW / 2, (h + barH) / 2)
                paint.color = if (i < bars / 2) teal else purple
                paint.alpha = (110 + 145 * level).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
            }
        }
    }
}
