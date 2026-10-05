package com.example.livesubtitle

/** Explicit invariants, shared by both translation providers. Case alone is not a name. */
internal object TranslationChecks {
    private val unchangedTerms = setOf("ok", "usb", "wi-fi", "wifi", "iphone", "ipad", "5g", "4g", "bluetooth")

    fun permitsUnchanged(text: String): Boolean =
        text.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT) in unchangedTerms

    /** Both complete translations must fit. Never compare one full string with a cut prefix. */
    fun comparisonText(independent: String, live: String): String? {
        if (independent.isBlank() || live.isBlank() || independent.length > 16_000 || live.length > 16_000) return null
        return org.json.JSONObject().put("independent", independent).put("live", live).toString()
    }
}
