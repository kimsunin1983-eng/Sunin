package com.example.livesubtitle

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** Local speech boundaries are a conservative check, not authoritative server turn IDs.
 * Never correct a translation using a partial, stale, or ambiguously matched recording.
 * All methods synchronize capture-thread writes with main-thread turn events.
 */
internal class LiveSpeechBuffer {
    private class Clip(val startedAt: Long) {
        val pcm = ByteArrayOutputStream()
        var speechBytes = 0
        var silentBytes = 0
        var complete = false
        var claimed = false
        var usable = true
    }
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0
    private val waiting = ArrayDeque<Clip>()
    private var active: Clip? = null
    private var selected: Clip? = null
    private var turnOpen = false
    private var ambiguous = false

    @Synchronized fun feed(pcm: ByteArray, length: Int, speech: Boolean, now: Long) {
        if (length <= 0) return
        while (waiting.isNotEmpty() && now - waiting.first.startedAt > MAX_AGE_MS) waiting.removeFirst()
        if (active == null && speech) {
            if (turnOpen) ambiguous = true // another utterance began before this response ended
            active = Clip(now).also { clip -> preRoll.forEach { clip.pcm.write(it) } }
            preRoll.clear()
            preRollBytes = 0
        }
        val clip = active
        if (clip == null) {
            preRoll.addLast(pcm.copyOf(length))
            preRollBytes += length
            while (preRollBytes > PRE_ROLL_BYTES && preRoll.isNotEmpty()) {
                preRollBytes -= preRoll.removeFirst().size
            }
            return
        }
        if (clip.pcm.size() + length <= MAX_BYTES) clip.pcm.write(pcm, 0, length)
        else clip.usable = false // never pass a truncated clip to the checker
        if (speech) {
            clip.speechBytes += length
            clip.silentBytes = 0
        } else clip.silentBytes += length
        if (clip.silentBytes >= END_SILENCE_BYTES) {
            clip.complete = true
            if (!clip.claimed) {
                waiting.addLast(clip)
                if (waiting.size > 3) {
                    // Losing an utterance makes subsequent matching unsafe until the next response.
                    waiting.forEach { it.usable = false }
                    waiting.removeFirst()
                }
            }
            active = null
        }
    }

    @Synchronized fun beginTurn(now: Long) {
        if (turnOpen) return
        turnOpen = true
        val candidates = waiting.toList() + listOfNotNull(active?.takeUnless { it.claimed })
        selected = candidates.singleOrNull()?.takeIf { now - it.startedAt <= MAX_AGE_MS }
        ambiguous = selected == null
        candidates.forEach { it.claimed = true }
        waiting.clear()
    }

    /** Only call for server turnComplete. Disconnects and interruptions cancel instead. */
    @Synchronized fun finishTurn(): ByteArray? {
        val clip = selected
        val result = if (!ambiguous && clip != null && clip.complete && clip.usable &&
            clip.speechBytes >= MIN_SPEECH_BYTES) clip.pcm.toByteArray() else null
        // Any overlapping input has uncertain ownership: do not reuse it for a later response.
        if (ambiguous) {
            waiting.clear()
            active?.claimed = true
        }
        selected = null
        turnOpen = false
        ambiguous = false
        return result
    }

    @Synchronized fun cancelTurn() {
        active = null
        selected = null
        waiting.clear()
        preRoll.clear()
        preRollBytes = 0
        turnOpen = false
        ambiguous = false
    }

    companion object {
        private const val PRE_ROLL_BYTES = 32000 * 3 / 10
        private const val END_SILENCE_BYTES = 32000 * 8 / 10
        private const val MIN_SPEECH_BYTES = 32000 / 10
        private const val MAX_BYTES = 32000 * 30
        private const val MAX_AGE_MS = 30_000L
    }
}
