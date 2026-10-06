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

    @Test fun placeholderArrivingInPiecesIsHeldButNeverDroppedByPrefixAlone() {
        listOf("Background", "(inaud", "No sp", "Mus", "No").forEach {
            assertTrue(it, LiveOutputPolicy.couldGrowIntoPlaceholder(it))
        }
        listOf("Background noise", "No, thank you", "Musical", "Hello", "", "안녕하세요").forEach {
            assertFalse(it, LiveOutputPolicy.couldGrowIntoPlaceholder(it))
        }
        // 앞부분만 같은 정상 대답은 말이 끝났을 때 버리지 않음
        assertFalse(LiveOutputPolicy.shouldDrop("No", true))
        assertFalse(LiveOutputPolicy.shouldDrop("Background", true))
        assertTrue(LiveOutputPolicy.shouldDrop("Background noise.", true))
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
        c.feed(8, false) // 취소된 말이 끝남
        c.feed(5, true, 2)
        c.feed(8, false)
        c.begin()
        val pcm = c.buffer.finishTurn()!!
        assertFalse(pcm.any { it == 1.toByte() })
        assertTrue(pcm.any { it == 2.toByte() })
    }

    @Test fun speechContinuingAcrossCancelIsTruncatedAndNeverUsed() {
        val c = Capture()
        c.feed(5, true, 1)
        c.begin()
        c.buffer.cancelTurn()
        c.feed(5, true, 2) // 쉬지 않고 이어 말함 → 앞부분이 없는 소리
        c.feed(8, false)
        c.begin()
        assertNull(c.buffer.finishTurn())
    }

    @Test fun shortNoiseBeforeSpeechDoesNotBlockCorrection() {
        val c = Capture()
        c.feed(1, true); c.feed(8, false) // 0.1초 잡음
        c.feed(5, true, 1); c.feed(8, false)
        c.begin()
        assertNotNull(c.buffer.finishTurn())
    }

    @Test fun shortNoiseDuringResponseDoesNotBlockCorrection() {
        val c = Capture()
        c.feed(5, true, 1); c.feed(8, false)
        c.begin()
        c.feed(2, true); c.feed(8, false) // 번역이 나오는 동안의 짧은 잡음
        assertNotNull(c.buffer.finishTurn())
    }

    @Test fun namesAndSharedWordsSurviveOnlyAsLastResort() {
        listOf("Marriott", "Taxi", "No", "Clark Marriott").forEach { assertTrue(it, TranslationChecks.sharedWord(it)) }
        listOf("I am sick", "Do not pay", "I love you", "아니요").forEach { assertFalse(it, TranslationChecks.sharedWord(it)) }
    }

    @Test fun brandNamesDoNotTurnASentenceIntoLatin() {
        mapOf(
            "Google Calendar 확인해 주세요" to "ko",
            "iPhone 있어요?" to "ko",
            "Marriott 호텔" to "ko",
            "我想去Starbucks" to "han",
            "Я люблю YouTube" to "cyr",
            "ไป Starbucks กัน" to "thai",
            "スターバックスに行きましょう" to "ja",
            "Let's meet at Starbucks" to "latin",
            "I'm at 강남 station" to "latin",
            "Starbucks 가요" to "ko",
            "Samsung Galaxy 샀어" to "ko",
            "去Starbucks吧" to "han",
            "Starbucksです" to "ja",
            "Xin chào, tôi là Minh" to "latin",
        ).forEach { (text, script) -> assertEquals(text, script, Scripts.detect(text)) }
        assertNull(Scripts.detect("123"))
        assertNull(Scripts.detect("..."))
        assertNull(Scripts.detect(""))
    }

    @Test fun mixedScriptIsRecognised() {
        assertTrue(Scripts.mixed("How do you say 안녕하세요?"))
        assertFalse(Scripts.mixed("안녕하세요"))
        assertFalse(Scripts.mixed("Hello there"))
    }

    @Test fun unansweredSpeechIsHandedOverOnceAndOnlyAfterWaiting() {
        val c = Capture()
        c.feed(12, true, 1); c.feed(8, false)
        assertNull(c.buffer.pollUnanswered(c.now + 1000, 3500))
        assertNotNull(c.buffer.pollUnanswered(c.now + 4000, 3500))
        assertNull(c.buffer.pollUnanswered(c.now + 9000, 3500))
        // 꺼낸 뒤에는 다음 말의 검산을 방해하지 않음
        c.now += 5000
        c.feed(5, true, 2); c.feed(8, false)
        c.begin()
        assertNotNull(c.buffer.finishTurn())
    }

    @Test fun speechStillGoingIsNotHandedOver() {
        val c = Capture()
        c.feed(12, true, 1); c.feed(8, false) // 첫 마디 뒤 잠깐 쉼
        c.feed(40, true, 2)                   // 이어서 계속 말하는 중
        assertNull(c.buffer.pollUnanswered(c.now, 3500))
    }

    @Test fun shortNoiseIsPrunedSoItDoesNotBlockTheNextCheck() {
        val c = Capture()
        c.feed(4, true); c.feed(8, false) // 0.4초짜리 소리 (기침 등)
        c.now += 4000
        c.buffer.pruneStale(c.now, 3500)
        c.feed(12, true, 1); c.feed(8, false)
        c.begin()
        assertNotNull(c.buffer.finishTurn())
        assertEquals(1200, c.buffer.lastSpeechMs)
    }

    @Test fun lateAnswerToHandedOverSpeechIsRecognisedDespiteStrayNoise() {
        val c = Capture()
        c.feed(12, true, 1); c.feed(8, false)
        c.feed(4, true); c.feed(8, false)          // 넘기기 전부터 남아 있던 짧은 소리
        c.now += 3000
        val sentAt = c.now
        assertNotNull(c.buffer.pollUnanswered(sentAt, 3500))
        c.now += 500
        // 새로 한 말 없이 번역이 시작됨 → 넘긴 말에 대한 늦은 번역
        assertFalse(c.buffer.beginTurn(c.now, sentAt))
        c.buffer.finishTurn()
        // 넘긴 뒤에 새로 말한 것이 있으면 새 말의 번역
        c.feed(8, true, 2); c.feed(8, false)
        assertTrue(c.buffer.beginTurn(c.now, sentAt))
    }

    @Test fun answeredSpeechIsNotHandedOver() {
        val c = Capture()
        c.feed(12, true, 1); c.feed(8, false)
        c.begin()
        assertNull(c.buffer.pollUnanswered(c.now + 9000, 3500))
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
            fresh.execute {
                freshStarted.countDown()
                try { freshRelease.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
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
