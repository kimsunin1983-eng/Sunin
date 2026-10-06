package com.example.livesubtitle

/**
 * 실시간 통역의 번역을 누구 귀에 들려줄지 정하는 규칙. 화면·소리와 분리해 두어 자동 시험으로 확인할 수 있게 함.
 *
 * 번역문의 글자 종류(한글/가나/한자/알파벳…)가 듣는 사람의 언어이므로 그 사람 귀로 보냄.
 * 글자가 아직 부족하면 기다리고(WAIT), 기다림이 끝나면(force) 있는 글자로 정함.
 */
internal object EarPolicy {
    enum class Decision {
        LEFT, RIGHT, BOTH,
        /** 글자가 더 올 때까지 기다림 */
        WAIT,
        /** 소리만 오고 글자가 하나도 없음: 타이머를 다시 걸고 조금 더 기다림 */
        WAIT_NO_TEXT,
        /** 통역 대신 같은 언어로 대답한 것으로 보임: 말이 끝날 때까지 소리를 붙잡아 둠 */
        SUSPECT,
    }

    private fun ascii(t: String) = t.count { it in 'a'..'z' || it in 'A'..'Z' }
    private fun letters(t: String) = t.count { it.isLetter() }

    /**
     * 통역 대신 같은 언어로 대답하거나 따라 말했는지: 들은 말과 나온 말이 같은 글자(한글·가나·키릴·태국).
     * 들은 말에 알파벳이 섞여 있으면(앞 사람 말이 섞였을 수 있음) 단정하지 않음.
     */
    fun answered(leftScript: String, rightScript: String, out: String, heard: String): Boolean {
        if (leftScript == rightScript) return false
        val s = Scripts.detect(out) ?: return false
        return s in setOf("ko", "ja", "cyr", "thai") && s == Scripts.detect(heard) &&
            letters(out) >= 4 && letters(heard) >= 4 && ascii(heard) < 4
    }

    /**
     * @param out 지금까지 받은 번역문
     * @param heard 서버가 받아쓴 원문 (없거나 틀릴 수 있어 보조로만 씀)
     * @param force 기다릴 만큼 기다림 (1.2초 타이머 또는 말이 끝남)
     * @param final 말이 끝남. 더 올 글자가 없음
     * @param canRescue 버린 말을 문장 단위 방식으로 다시 통역할 수 있는지
     * @param mayWaitForText 글자 없이 소리만 왔을 때 더 기다릴 횟수가 남았는지
     * @param languageEar 서버가 알려 준 언어로 정한 귀 (없으면 null). 글자로 구분할 수 없을 때만 씀
     */
    fun decide(
        leftScript: String,
        rightScript: String,
        leftAuto: Boolean,
        rightAuto: Boolean,
        out: String,
        heard: String,
        force: Boolean,
        final: Boolean,
        canRescue: Boolean,
        mayWaitForText: Boolean,
        languageEar: Decision?,
    ): Decision {
        val script = Scripts.detect(out)
        val n = letters(out)
        val heardScript = Scripts.detect(heard)

        if (canRescue && !final && answered(leftScript, rightScript, out, heard)) return Decision.SUSPECT
        if (n == 0 && force && !final && mayWaitForText) return Decision.WAIT_NO_TEXT

        val pair = setOf(leftScript, rightScript)
        // 들은 말이 온전히 상대 글자(한글 등)였음 → 알파벳 번역이 맞다는 뒷받침
        val heardOther = heardScript != null && heardScript != "latin" && heardScript in pair && ascii(heard) < 4
        val sentenceDone = n >= 2 && out.trimEnd().lastOrNull() in setOf('.', '?', '!')
        // 한글·일본어 번역이 "Starbucks" 같은 알파벳 단어로 시작할 수 있음.
        // 알파벳만 보일 때는 15자 넘게 이어지거나, 문장이 끝났거나, 들은 말이 상대 글자였을 때만 알파벳 언어로 판단
        if (script == "latin" && !force && pair.size == 2 && "latin" in pair && n < 15 && !heardOther && !sentenceDone) {
            return Decision.WAIT
        }
        // 첫 조각의 한두 글자에 속지 않도록 4자 이상 모이면 판단. "Yes." 처럼 짧게 끝난 말은 뒷받침이 있을 때만 바로
        val enough = force || n >= 4 || (script == "latin" && sentenceDone && heardOther)

        val chosen = when {
            leftAuto || rightAuto -> {
                // 정해진 언어 글자로 나온 번역은 그 사람 귀에, 그 밖의 글자는 자동 감지 쪽 귀에
                val knownScript = if (leftAuto) rightScript else leftScript
                val autoScript = if (leftAuto) leftScript else rightScript
                val autoEar = if (leftAuto) Decision.LEFT else Decision.RIGHT
                val knownEar = if (leftAuto) Decision.RIGHT else Decision.LEFT
                when {
                    knownScript == "latin" -> Decision.BOTH // 알파벳 언어는 다른 알파벳 언어와 글자로 구분 불가
                    // 다루지 않는 글자(아랍어·힌디어 등)는 정해진 언어가 아니므로 자동 감지 쪽 귀에
                    script == null && n >= 4 -> autoEar
                    script == null -> if (force) Decision.BOTH else return Decision.WAIT
                    !enough -> return Decision.WAIT
                    Scripts.matches(script, knownScript, autoScript) -> knownEar
                    else -> autoEar
                }
            }
            leftScript == rightScript -> Decision.BOTH // 글자로 구분 못 하는 언어쌍은 양쪽에
            script == null -> if (force) Decision.BOTH else return Decision.WAIT
            !enough -> return Decision.WAIT
            // 일본어↔중국어: 한자만으로는 어느 쪽인지 알 수 없음 → 가나가 나올 때까지 기다림
            pair == setOf("ja", "han") && script == "han" -> when {
                !force -> return Decision.WAIT
                // 가나가 하나도 없는 긴 문장은 중국어로 봄. 짧은 한자어("了解")는 양쪽에
                out.count { it in '一'..'鿿' } >= 6 -> if (leftScript == "han") Decision.LEFT else Decision.RIGHT
                else -> Decision.BOTH
            }
            Scripts.matches(script, leftScript, rightScript) -> Decision.LEFT
            Scripts.matches(script, rightScript, leftScript) -> Decision.RIGHT
            force -> Decision.BOTH
            else -> return Decision.WAIT
        }
        return if (chosen == Decision.BOTH && languageEar != null) languageEar else chosen
    }
}
