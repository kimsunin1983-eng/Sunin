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
        /** 앞 말의 번역이 나오는 도중에 시작된 소리인지 */
        var duringTurn = false
        var endedAt = 0L
    }
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0
    private val waiting = ArrayDeque<Clip>()
    private var active: Clip? = null
    private var selected: Clip? = null
    private var turnOpen = false
    private var ambiguous = false
    /** 말하는 도중에 취소됨 → 그 말이 끝날 때까지(무음 0.8초)는 모으지 않음. 앞이 잘린 소리로 검산하지 않기 위함 */
    private var skipUntilSilence = false
    private var skipSilentBytes = 0

    @Synchronized fun feed(pcm: ByteArray, length: Int, speech: Boolean, now: Long) {
        if (length <= 0) return
        if (skipUntilSilence) {
            if (speech) skipSilentBytes = 0 else skipSilentBytes += length
            if (skipSilentBytes >= END_SILENCE_BYTES) skipUntilSilence = false
            return
        }
        while (waiting.isNotEmpty() && now - waiting.first.startedAt > MAX_AGE_MS) waiting.removeFirst()
        if (active == null && speech) {
            active = Clip(now).also { clip ->
                clip.duringTurn = turnOpen
                preRoll.forEach { clip.pcm.write(it) }
            }
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
        // 번역 도중에 다른 말이 시작됨 → 소리의 주인이 불분명. 단, 0.3초도 안 되는 잡음은 말로 치지 않음
        if (clip.duringTurn && turnOpen && clip.speechBytes >= NOISE_BYTES) ambiguous = true
        if (clip.silentBytes >= END_SILENCE_BYTES) {
            clip.complete = true
            clip.endedAt = now
            if (!clip.claimed && clip.speechBytes >= NOISE_BYTES) { // 짧은 잡음은 발화로 세지 않음
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

    /**
     * 말이 끝난 지 waitMs 가 지나도록 번역이 시작되지 않은 발화(모델이 놓친 말)를 꺼냄.
     * 꺼낸 발화는 대기열에서 빠지므로 다음 말의 검산을 방해하지 않음. 너무 짧거나 잘린 소리는 버림(null).
     */
    @Synchronized fun pollUnanswered(now: Long, waitMs: Long): ByteArray? {
        // 아직 말하는 중이면(잠깐 쉬었다 이어 말하는 경우) 꺼내지 않음. 앞부분만 따로 통역하면 같은 말이 두 번 나옴
        if (turnOpen || active != null) return null
        val clip = waiting.firstOrNull() ?: return null
        if (now - clip.endedAt < waitMs) return null
        waiting.removeFirst()
        return if (clip.usable && clip.speechBytes >= RESCUE_SPEECH_BYTES) clip.pcm.toByteArray() else null
    }

    /** 직전에 끝난 번역에 대응하는 말소리 길이(ms). 대응하는 소리가 없었으면 0 */
    @Volatile var lastSpeechMs = 0
        private set

    /** 오래된 짧은 소리(잡음)를 치움. 남아 있으면 다음 말이 '발화 둘'로 보여 검산·되살리기가 건너뛰어짐 */
    @Synchronized fun pruneStale(now: Long, olderThanMs: Long) {
        waiting.removeAll { now - it.endedAt >= olderThanMs && (!it.usable || it.speechBytes < RESCUE_SPEECH_BYTES) }
    }

    /**
     * @param since 이 시각 뒤에 시작된 말소리만 '새로 한 말'로 침 (놓친 말을 다른 곳으로 넘긴 시각)
     * @return 이 번역에 대응할 새 말소리가 있었는지. 없으면 이미 넘긴 말에 대한 늦은 번역일 수 있음.
     *         (그 전부터 남아 있던 짧은 소리나 잡음은 새 말로 치지 않음)
     */
    @Synchronized fun beginTurn(now: Long, since: Long = 0L): Boolean {
        if (turnOpen) return true
        turnOpen = true
        val candidates = waiting.toList() + listOfNotNull(active?.takeUnless { it.claimed })
        selected = candidates.singleOrNull()?.takeIf { now - it.startedAt <= MAX_AGE_MS }
        ambiguous = selected == null
        selected?.duringTurn = false // 이 번역의 주인으로 정해진 소리는 겹친 말로 세지 않음
        candidates.forEach { it.claimed = true }
        waiting.clear()
        return candidates.any { it.startedAt > since && it.speechBytes >= NOISE_BYTES }
    }

    /** Only call for server turnComplete. Disconnects and interruptions cancel instead. */
    @Synchronized fun finishTurn(): ByteArray? {
        val clip = selected
        val result = if (!ambiguous && clip != null && clip.complete && clip.usable &&
            clip.speechBytes >= MIN_SPEECH_BYTES) clip.pcm.toByteArray() else null
        lastSpeechMs = if (result != null && clip != null) clip.speechBytes / 32 else 0
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
        skipUntilSilence = active != null
        skipSilentBytes = 0
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
        private const val NOISE_BYTES = 32000 * 3 / 10
        private const val RESCUE_SPEECH_BYTES = 32000 // 말소리 1초 이상일 때만 (주변 소음으로 요청이 늘지 않게)
        private const val MAX_BYTES = 32000 * 30
        private const val MAX_AGE_MS = 30_000L
    }
}
