package com.cihmine.napalarm.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면 문구. 수면 측정·기상 보장처럼 말하지 않는다. (음성 기능은 앱에서 뺐다) */
object Texts {
    private val clockFormat = DateTimeFormatter.ofPattern("a h시 m분", Locale.KOREAN)

    fun clock(wallMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(wallMs).atZone(zone).format(clockFormat).replace(" 0분", "")

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}초"
        seconds % 3600 == 0L -> "${seconds / 3600}시간"
        seconds >= 3600 -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
        else -> "${seconds / 60}분"
    }

    /** 분(소수) → "12시간", "6시간 30분", "3분", "1분 미만" */
    fun durationMinutes(minutes: Double): String {
        if (minutes < 1) return "1분 미만"
        val m = Math.round(minutes)
        return when {
            m < 60 -> "${m}분"
            m % 60 == 0L -> "${m / 60}시간"
            else -> "${m / 60}시간 ${m % 60}분"
        }
    }

    /** 첫 화면용 예상 시간: 하루 넘으면 시간 단위로 반올림해 분 단위 정확도처럼 보이지 않게 한다 */
    fun roughMinutes(minutes: Double): String = when {
        minutes >= 24 * 60 -> "약 ${Math.round(minutes / 60)}시간"
        minutes >= 60 -> "약 ${durationMinutes(minutes)}"
        else -> durationMinutes(minutes)
    }

    fun remaining(ms: Long): String {
        val total = (ms + 999) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun protectionBeforeWake(minutes: Double): String =
        "현재 소비 기준으로 약 ${durationMinutes(minutes)} 뒤 전력 보존량에 닿아요 — 기상 시각보다 빨라요. 그 전에 먼저 깨워 드릴게요."

    const val OVERDUE = "예정된 알람 시각이 지났어요. 앱이 꺼져 있어 알람이 울리지 않았어요."
    const val NOT_GUARANTEE = "이 앱은 기상이나 차 안 체류 안전을 보장하는 장치가 아니에요. 앱이 켜져 있고 차량 시스템이 깨어 있을 때만 알람이 울려요."
}
