package com.example.livesubtitle

import com.example.livesubtitle.EarPolicy.Decision
import com.example.livesubtitle.EarPolicy.Decision.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 번역을 어느 귀로 보낼지: 왼쪽 = 상대, 오른쪽 = 나 */
class EarPolicyTest {
    private fun decide(
        left: String, right: String, out: String, heard: String = "",
        force: Boolean = false, final: Boolean = false,
        leftAuto: Boolean = false, rightAuto: Boolean = false,
        canRescue: Boolean = true, mayWait: Boolean = true, lang: Decision? = null,
    ) = EarPolicy.decide(left, right, leftAuto, rightAuto, out, heard, force, final, canRescue, mayWait, lang)

    // ── 상대 영어(왼쪽) / 나 한국어(오른쪽) ──
    @Test fun koreanTranslationGoesToMe() {
        assertEquals(RIGHT, decide("latin", "ko", "안녕하세요"))
        assertEquals(WAIT, decide("latin", "ko", "네"))
        assertEquals(RIGHT, decide("latin", "ko", "네.", force = true))
    }

    @Test fun englishTranslationGoesToTheOther() {
        assertEquals(LEFT, decide("latin", "ko", "Hello, nice to meet you."))
        assertEquals(LEFT, decide("latin", "ko", "Thank you."))
        assertEquals(WAIT, decide("latin", "ko", "Hello"))
        assertEquals(LEFT, decide("latin", "ko", "Hello", force = true))
    }

    @Test fun oneWordEnglishIsImmediateOnlyWhenKoreanWasHeard() {
        assertEquals(LEFT, decide("latin", "ko", "Yes.", heard = "네"))
        assertEquals(LEFT, decide("latin", "ko", "OK.", heard = "알겠습니다"))
        assertEquals(WAIT, decide("latin", "ko", "Yes."))
        assertEquals(LEFT, decide("latin", "ko", "Yes.", force = true))
        assertEquals(WAIT, decide("latin", "ko", "No", heard = "아니요"))
    }

    @Test fun koreanSentenceStartingWithABrandIsNotSentToTheEnglishEar() {
        assertEquals(WAIT, decide("latin", "ko", "Starbucks"))
        assertEquals(RIGHT, decide("latin", "ko", "Starbucks에서 만나요"))
        // 영어로 말한 문장에 한글 지명이 섞여 받아쓰였어도 '한국어를 들었다'고 보지 않음
        assertEquals(WAIT, decide("latin", "ko", "Starbucks", heard = "Let's meet at Starbucks in 강남"))
        assertEquals(WAIT, decide("latin", "ko", "Dr.", heard = ""))
    }

    @Test fun answeringInsteadOfTranslatingIsHeldThenPlayedIfNothingElse() {
        assertEquals(SUSPECT, decide("latin", "ko", "화장실은 저쪽이에요", heard = "화장실이 어디예요"))
        assertEquals(SUSPECT, decide("latin", "ko", "화장실은 저쪽이에요", heard = "화장실이 어디예요", force = true))
        // 말이 끝났는데 다시 통역할 방법이 없으면 그대로 들려줌
        assertEquals(RIGHT, decide("latin", "ko", "화장실은 저쪽이에요", heard = "화장실이 어디예요", force = true, final = true))
        // 다시 통역할 수단이 없으면 의심하지 않음
        assertEquals(RIGHT, decide("latin", "ko", "화장실은 저쪽이에요", heard = "화장실이 어디예요", canRescue = false))
        // 들은 말에 영어가 섞여 있으면(앞 사람 말이 남은 것일 수 있음) 정상 번역으로 봄
        assertEquals(RIGHT, decide("latin", "ko", "아니요, 괜찮아요", heard = "네 알겠습니다 No, it's fine"))
        assertTrue(EarPolicy.answered("latin", "ko", "화장실은 저쪽이에요", "화장실이 어디예요"))
        assertFalse(EarPolicy.answered("latin", "ko", "Where is the restroom?", "화장실이 어디예요"))
        assertFalse(EarPolicy.answered("latin", "latin", "Hola", "Hola"))
    }

    @Test fun audioWithoutAnyTextWaitsThenFallsBackToBoth() {
        assertEquals(WAIT, decide("latin", "ko", ""))
        assertEquals(WAIT_NO_TEXT, decide("latin", "ko", "", force = true))
        assertEquals(BOTH, decide("latin", "ko", "", force = true, mayWait = false))
        assertEquals(BOTH, decide("latin", "ko", "", force = true, final = true))
        assertEquals(BOTH, decide("latin", "ko", "30", force = true, final = true))
        assertEquals(LEFT, decide("latin", "ko", "30", force = true, final = true, lang = LEFT))
    }

    // ── 같은 글자를 쓰는 언어쌍 ──
    @Test fun sameScriptPairUsesServerLanguageWhenGiven() {
        assertEquals(BOTH, decide("latin", "latin", "Hola amigo"))
        assertEquals(RIGHT, decide("latin", "latin", "Hola amigo", lang = RIGHT))
    }

    // ── 일본어 / 중국어 / 한국어 ──
    @Test fun japaneseAndChinese() {
        assertEquals(LEFT, decide("ja", "han", "こんにちは"))
        assertEquals(WAIT, decide("ja", "han", "你好吗我很好谢谢"))
        assertEquals(RIGHT, decide("ja", "han", "你好吗我很好谢谢", force = true))
        assertEquals(BOTH, decide("ja", "han", "了解", force = true))
        assertEquals(LEFT, decide("ja", "han", "今日は寒いですね"))
    }

    @Test fun kanjiOnlyJapaneseWithKorean() {
        assertEquals(RIGHT, decide("ko", "ja", "了解", force = true))
        assertEquals(LEFT, decide("ko", "ja", "알겠습니다"))
        assertEquals(RIGHT, decide("ko", "ja", "ありがとうございます"))
    }

    // ── 한쪽 자동 감지 ──
    @Test fun autoSide() {
        assertEquals(RIGHT, decide("latin", "ko", "안녕하세요", leftAuto = true))
        assertEquals(LEFT, decide("latin", "ko", "Hello my friend, how are you", leftAuto = true))
        assertEquals(LEFT, decide("latin", "ko", "ありがとうございます", leftAuto = true))
        assertEquals(LEFT, decide("latin", "ko", "مرحبا بك", leftAuto = true))
        assertEquals(BOTH, decide("latin", "latin", "Hello there", leftAuto = true))
    }
}
