package com.example.livesubtitle

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 번역된 문장을 기기 안 파일에 시간순으로 쌓아 두는 간단한 기록 */
object HistoryStore {
    private const val FILE = "history.txt"
    private const val MAX_BYTES = 200_000L
    private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
    private var lastHeader = ""

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** kind: "자막" 또는 "대화", text: 번역문, original: 원문(없어도 됨) */
    @Synchronized
    fun add(context: Context, kind: String, text: String, original: String? = null) {
        if (text.isBlank()) return
        runCatching {
            val f = file(context.applicationContext)
            if (f.exists() && f.length() > MAX_BYTES) {
                // 너무 커지면 앞쪽 절반을 버림
                val all = f.readText()
                f.writeText(all.substring(all.length / 2).substringAfter('\n'))
            }
            val header = "── $kind · ${timeFormat.format(Date())} ──"
            val sb = StringBuilder()
            if (header != lastHeader) {
                sb.append('\n').append(header).append('\n')
                lastHeader = header
            }
            sb.append(text.trim()).append('\n')
            if (!original.isNullOrBlank()) sb.append("  ").append(original.trim()).append('\n')
            f.appendText(sb.toString())
        }
    }

    @Synchronized
    fun read(context: Context): String =
        runCatching { file(context.applicationContext).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context.applicationContext).delete() }
        lastHeader = ""
    }
}
