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
    fun shouldDrop(text: String, serverTurnComplete: Boolean): Boolean =
        serverTurnComplete && (isPlaceholder(text) || mightBecomePlaceholder(text))
}
