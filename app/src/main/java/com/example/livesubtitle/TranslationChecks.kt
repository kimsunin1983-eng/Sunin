package com.example.livesubtitle

/** Explicit invariants, shared by both translation providers. Case alone is not a name. */
internal object TranslationChecks {
    private val unchangedTerms = setOf("ok", "usb", "wi-fi", "wifi", "iphone", "ipad", "5g", "4g", "bluetooth")

    fun permitsUnchanged(text: String): Boolean =
        text.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT) in unchangedTerms

    /** 한두 단어짜리 짧은 알파벳 말. 문장("I am sick", "Do not pay")은 해당하지 않음 */
    fun sharedWord(text: String): Boolean {
        val t = text.trim()
        return t.length <= 24 && t.split(Regex("\\s+")).size <= 2 && t.any { it.isLetter() } &&
            t.all { it.code < 0x250 }
    }

    /** Both complete translations must fit. Never compare one full string with a cut prefix. */
    fun comparisonText(independent: String, live: String): String? {
        if (independent.isBlank() || live.isBlank() || independent.length > 16_000 || live.length > 16_000) return null
        return org.json.JSONObject().put("independent", independent).put("live", live).toString()
    }
}
