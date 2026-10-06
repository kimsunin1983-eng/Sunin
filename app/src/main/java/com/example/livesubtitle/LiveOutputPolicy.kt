package com.example.livesubtitle

internal object LiveOutputPolicy {
    private val bracketed = Regex("^(<[^<>]*>|\\[[^\\[\\]]*\\])[.!]?$")
    private val placeholder = Regex(
        "^\\(?(no speech( detected)?|no audio( detected)?|silence|silent|inaudible|unintelligible|" +
            "noise|background noise|music|background music|\\.\\.\\.)\\)?[.!]?$"
    )
    fun isPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase(java.util.Locale.ROOT)
        return t.isNotEmpty() && (bracketed.matches(t) || placeholder.matches(t))
    }
    fun mightBecomePlaceholder(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("<") || t.startsWith("[")) && !isPlaceholder(t)
    }
    private val phrases = listOf(
        "no speech", "no speech detected", "no audio", "no audio detected", "silence", "silent",
        "inaudible", "unintelligible", "noise", "background noise", "music", "background music",
    )

    /**
     * 표시 문구가 조각으로 나뉘어 오는 중일 수 있는지 ("Background" 다음에 " noise.", "(inaud" 다음에 "ible)").
     * 소리를 잠깐 붙잡아 두는 데만 씀. 말이 끝났을 때 버릴지는 isPlaceholder 로만 판단
     * ("No" 는 "no speech" 의 앞부분이지만, 그대로 끝났다면 정상적인 대답임).
     */
    fun couldGrowIntoPlaceholder(text: String): Boolean {
        val t = text.trim().lowercase(java.util.Locale.ROOT).removePrefix("(")
        return t.isNotEmpty() && phrases.any { it.length > t.length && it.startsWith(t) }
    }

    fun shouldDrop(text: String, serverTurnComplete: Boolean): Boolean =
        serverTurnComplete && (isPlaceholder(text) || mightBecomePlaceholder(text))
}
