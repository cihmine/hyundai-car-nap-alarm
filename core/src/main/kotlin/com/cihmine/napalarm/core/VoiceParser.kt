package com.cihmine.napalarm.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class NapCommand { WAKE, SNOOZE, STOP, YES, NO }

sealed interface Utterance {
    /** 휴식 시간. 음성으로 들은 시간은 바로 확정하지 않고 다시 묻는다(30분/3분 혼동 방지). */
    data class Duration(val minutes: Int) : Utterance
    data class Command(val command: NapCommand) : Utterance
    data object Unknown : Utterance
}

/** 음성 명령·시간 해석. 허용된 동작 목록으로만 바꾼다. */
object VoiceParser {
    private val COMMANDS: Map<String, NapCommand> = buildMap {
        fun add(c: NapCommand, vararg p: String) = p.forEach { put(norm(it), c) }
        add(NapCommand.WAKE, "일어났어", "일어났다", "일어날게", "깼어", "알람 꺼", "알람 꺼 줘", "알람 끄기", "그만 울려")
        add(NapCommand.SNOOZE, "5분 더", "오 분 더", "조금만 더", "더 잘래", "다시 알려 줘", "다시 알림", "5분만 더")
        add(NapCommand.STOP, "휴식 종료", "그만", "종료", "그만할래", "취소")
        add(NapCommand.YES, "네", "예", "응", "좋아", "그래", "맞아")
        add(NapCommand.NO, "아니", "아니요", "아니오", "싫어", "틀렸어")
    }

    private fun norm(s: String) = s.replace(Regex("[\\s.,!?~]"), "")

    fun parse(text: String): Utterance {
        val t = norm(text)
        if (t.isEmpty()) return Utterance.Unknown
        COMMANDS[t]?.let { return Utterance.Command(it) }
        parseMinutes(t)?.let { return Utterance.Duration(it) }
        return Utterance.Unknown
    }

    /** "30분", "삼십 분 뒤", "1시간 30분", "한 시간 반", "90분 후에 깨워 줘" → 분. 못 알아들으면 null */
    fun parseMinutes(t: String): Int? {
        val m = Regex("^(?:(.+?)시간(반)?)?(?:(.+?)분)?(?:뒤|후)?(?:에)?(?:깨워줘|알려줘|깨워|자고싶어|잘래)?$").matchEntire(t) ?: return null
        val (hText, half, mText) = m.destructured
        if (hText.isEmpty() && mText.isEmpty()) return null
        val hours = if (hText.isEmpty()) 0 else number(hText) ?: return null
        val minutes = if (mText.isEmpty()) 0 else number(mText) ?: return null
        val total = hours * 60 + minutes + if (half.isNotEmpty()) 30 else 0
        return total.takeIf { it > 0 }
    }

    private val NATIVE = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6)
    private val SINO = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)

    /** 아라비아 숫자, 고유어(한~여섯), 한자어(십오, 삼십, 백이십 등) */
    private fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        NATIVE[s]?.let { return it }
        var total = 0
        var digit = 0
        for (c in s) {
            when (c) {
                '백' -> { total += (if (digit == 0) 1 else digit) * 100; digit = 0 }
                '십' -> { total += (if (digit == 0) 1 else digit) * 10; digit = 0 }
                in SINO -> digit = SINO.getValue(c)
                else -> return null
            }
        }
        return (total + digit).takeIf { it > 0 }
    }
}

/** 화면·음성 문구. 수면 측정·기상 보장처럼 말하지 않는다. */
object Texts {
    private val clockFormat = DateTimeFormatter.ofPattern("a h시 m분", Locale.KOREAN)

    fun clock(wallMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(wallMs).atZone(zone).format(clockFormat).replace(" 0분", "")

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}초"
        seconds % 3600 == 0L -> "${seconds / 3600}시간"
        seconds >= 3600 -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
        else -> "${seconds / 60}분"
    }

    /** "30분 뒤, 오후 2시 30분에 알려 드릴까요?" */
    fun confirmDuration(minutes: Int, nowWallMs: Long, zone: ZoneId): String =
        "${duration(minutes * 60L)} 뒤, ${clock(nowWallMs + minutes * 60_000L, zone)}에 알려 드릴까요?"

    fun remaining(ms: Long): String {
        val total = (ms + 999) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    const val ALARM = "설정한 휴식 시간이 끝났어요. 일어나셨으면 '일어났어', 더 쉬려면 '5분 더'라고 말해 주세요."
    const val OVERDUE = "예정된 알람 시각이 지났어요. 앱이 꺼져 있어 알람이 울리지 않았어요."
    const val NOT_GUARANTEE = "이 앱은 기상이나 차 안 체류 안전을 보장하는 장치가 아니에요. 앱이 켜져 있고 차량 시스템이 깨어 있을 때만 알람이 울려요."
}
