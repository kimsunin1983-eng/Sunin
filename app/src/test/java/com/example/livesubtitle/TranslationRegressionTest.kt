package com.example.livesubtitle

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class TranslationRegressionTest {
    @Test fun networkTimeoutDoesNotDiscardMusicPrefix() {
        assertFalse(LiveOutputPolicy.shouldDrop("Music", false))
        assertFalse(LiveOutputPolicy.shouldDrop("Music is my life.", true))
        assertTrue(LiveOutputPolicy.shouldDrop("Music", true))
    }

    @Test fun partialBracketIsOnlyDiscardedAtServerCompletion() {
        assertFalse(LiveOutputPolicy.shouldDrop("<no sp", false))
        assertTrue(LiveOutputPolicy.shouldDrop("<no speech>", true))
        assertFalse(LiveOutputPolicy.shouldDrop("No audio equipment is needed.", true))
    }

    @Test fun commandsAndShortSentencesAreNotNames() {
        listOf("I am sick", "I love you", "STOP", "NO", "Help me", "Do it", "Yes", "Go", "STOP!").forEach {
            assertFalse(it, TranslationChecks.permitsUnchanged(it))
        }
    }

    @Test fun explicitInvariantTermsStillWork() {
        listOf("OK", "USB", "iPhone", "Wi-Fi", "5G").forEach {
            assertTrue(it, TranslationChecks.permitsUnchanged(it))
        }
    }

    @Test fun comparisonRetainsMeaningAfterCharacter400() {
        val prefix = "예약 조건입니다. ".repeat(80)
        val independent = prefix + "취소할 수 없습니다."
        val live = prefix + "취소할 수 있습니다."
        val json = JSONObject(TranslationChecks.comparisonText(independent, live)!!)
        assertEquals(independent, json.getString("independent"))
        assertEquals(live, json.getString("live"))
    }

    @Test fun oversizedComparisonSkipsInsteadOfComparingTruncations() {
        assertNull(TranslationChecks.comparisonText("x".repeat(16_001), "short"))
        assertNull(TranslationChecks.comparisonText("short", "x".repeat(16_001)))
    }

    private class Capture {
        val buffer = LiveSpeechBuffer()
        var now = 0L
        fun feed(count: Int, speech: Boolean, marker: Byte = 0) {
            repeat(count) {
                now += 100
                buffer.feed(ByteArray(3200) { marker }, 3200, speech, now)
            }
        }
        fun begin() = buffer.beginTurn(now)
    }

    @Test fun collectsTailAfterTranslationStarts() {
        val c = Capture()
        c.feed(4, true, 1)
        c.begin()
        c.feed(6, true, 2) // user is still speaking after the first output
        c.feed(8, false)
        val pcm = c.buffer.finishTurn()!!
        assertEquals(18 * 3200, pcm.size)
        assertEquals(6 * 3200, pcm.count { it == 2.toByte() })
    }

    @Test fun alreadyCompletedSpeechCanBeMatched() {
        val c = Capture()
        c.feed(5, true, 1)
        c.feed(8, false)
        c.begin()
        assertNotNull(c.buffer.finishTurn())
    }

    @Test fun incompleteSpeechIsNeverUsedToCorrect() {
        val c = Capture()
        c.feed(5, true)
        c.begin()
        assertNull(c.buffer.finishTurn())
    }

    @Test fun multiplePendingUtterancesAreAmbiguous() {
        val c = Capture()
        repeat(2) { c.feed(5, true); c.feed(8, false) }
        c.begin()
        assertNull(c.buffer.finishTurn())
    }

    @Test fun overlappingNextSpeakerInvalidatesCorrection() {
        val c = Capture()
        c.feed(5, true)
        c.feed(8, false)
        c.begin()
        c.feed(5, true)
        c.feed(8, false)
        assertNull(c.buffer.finishTurn())
        c.begin()
        assertNull(c.buffer.finishTurn()) // don't recycle the overlap into the next response
    }

    @Test fun overlongSpeechIsNotSilentlyTruncated() {
        val c = Capture()
        c.feed(310, true)
        c.feed(8, false)
        c.begin()
        assertNull(c.buffer.finishTurn())
    }

    @Test fun canceledAudioDoesNotLeakIntoNextTurn() {
        val c = Capture()
        c.feed(5, true, 1)
        c.begin()
        c.buffer.cancelTurn()
        c.feed(5, true, 2)
        c.feed(8, false)
        c.begin()
        val pcm = c.buffer.finishTurn()!!
        assertFalse(pcm.any { it == 1.toByte() })
        assertTrue(pcm.any { it == 2.toByte() })
    }

    @Test fun silenceAloneNeverCreatesAnUtterance() {
        val c = Capture()
        c.feed(20, false)
        c.begin()
        assertNull(c.buffer.finishTurn())
    }

    @Test fun staleUtteranceIsNotMatchedToNewResponse() {
        val c = Capture()
        c.feed(5, true)
        c.feed(8, false)
        c.now += 31_000
        c.begin()
        assertNull(c.buffer.finishTurn())
    }

    @Test fun queueRemainsBoundedAcrossRestartAndLateOldCompletion() {
        val old = CheckExecutor.create()
        val fresh = CheckExecutor.create()
        val oldStarted = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val oldDone = CountDownLatch(1)
        val freshStarted = CountDownLatch(1)
        val freshRelease = CountDownLatch(1)
        try {
            old.execute {
                oldStarted.countDown()
                // Model an in-flight HTTP request that outlives shutdownNow's interrupt.
                while (oldRelease.count > 0) {
                    try { oldRelease.await() } catch (_: InterruptedException) { }
                }
                oldDone.countDown()
            }
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS))
            old.shutdownNow()
            fresh.execute { freshStarted.countDown(); freshRelease.await() }
            assertTrue(freshStarted.await(2, TimeUnit.SECONDS))
            fresh.execute { }
            fresh.execute { }
            oldRelease.countDown()
            assertTrue(oldDone.await(2, TimeUnit.SECONDS))
            assertEquals(2, fresh.queue.size)
            try {
                fresh.execute { }
                fail("A fourth check must be rejected even after an old session finishes")
            } catch (_: RejectedExecutionException) { }
        } finally {
            oldRelease.countDown()
            freshRelease.countDown()
            old.shutdownNow()
            fresh.shutdownNow()
        }
    }
}
