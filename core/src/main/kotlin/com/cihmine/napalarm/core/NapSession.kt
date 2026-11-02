package com.cihmine.napalarm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 시계. 화면 숫자를 1초씩 빼지 않고 이 값으로 남은 시간을 계산한다.
 * [monotonicMs] 는 기기 부팅 후 경과 시간(시스템 시각을 바꿔도 영향 없음, 재부팅하면 0부터),
 * [wallMs] 는 실제 시각(재시작·재부팅 뒤 비교용).
 */
interface Clock {
    /** 부팅 횟수(같은 부팅인지 판단). 모르면 -1 */
    fun bootCount(): Int
    fun monotonicMs(): Long
    fun wallMs(): Long
}

@Serializable
enum class NapStatus {
    /** 휴식 중(카운트다운) */
    RESTING,

    /** 알람 울리는 중 */
    RINGING,

    /** 다시 알림 대기 */
    SNOOZED,

    /** 앱이 꺼져 있는 동안 예정 시각이 지남 — 알람이 울리지 않았다는 것을 그대로 보여 준다 */
    OVERDUE,

    /** 기상·종료 후 원래 설정 복구 확인 대기 */
    RESTORE_PENDING,

    FINISHED,
}

@Serializable
enum class EndReason(val label: String) {
    WOKE("알람 후 기상 확인"),
    STOPPED_EARLY("시간 전에 종료"),

    /** 앱이 꺼져 있어 예정 시각에 알람이 울리지 않음 */
    MISSED_WHILE_OFF("앱이 꺼져 알람이 울리지 않음"),
    CANCELLED("시작 취소"),
}

@Serializable
data class NapSession(
    val id: String,
    val requestedSeconds: Long,
    val startedWallMs: Long,
    val startedMonoMs: Long,
    /** 시작할 때의 부팅 횟수(-1 = 모름) */
    val startedBootCount: Int = -1,
    /** 목표 시각(실제 시각). 재시작 뒤 비교에 쓴다. */
    val targetWallMs: Long,
    /** 목표 시각(단조 시계). 같은 부팅 안에서는 이 값을 우선 쓴다. */
    val targetMonoMs: Long,
    val status: NapStatus = NapStatus.RESTING,
    val snoozeCount: Int = 0,
    /** 지금까지 울린 알람 번호(0 = 아직 없음). 같은 번호는 한 번만 울린다. */
    val alarmSequence: Int = 0,
    val endedWallMs: Long? = null,
    /** 끝날 때까지의 실제 경과(ms). 같은 부팅이면 단조 시계로 재서 시스템 시각 변경에 흔들리지 않는다. */
    val elapsedMs: Long? = null,
    val endReason: EndReason? = null,
    /** 시연용 단축 타이머(20초 등)인지 — 화면·기록에 표시한다 */
    val demoShortened: Boolean = false,
    /** 재부팅·시각 변경 등으로 일정을 사용자에게 다시 확인해야 함 */
    val needsReconfirm: Boolean = false,
)

/** 끝난 휴식 기록. 수면 시간이 아니라 앱 세션 시간이다. */
@Serializable
data class NapRecord(
    val sessionId: String,
    val requestedSeconds: Long,
    val startedWallMs: Long,
    val endedWallMs: Long,
    val endReason: EndReason,
    val snoozeCount: Int,
    val demoShortened: Boolean,
    /** 실제 앱 세션 시간(ms). 시스템 시각을 바꿔도 왜곡되지 않게 경과 시간으로 잰다. */
    val actualMs: Long = 0,
)

/** 사용자 휴식 설정 */
@Serializable
data class RestPreferences(
    val temperatureC: Double = 22.0,
    val seatPreset: SeatPreset = SeatPreset.RECLINE,
    val snoozeMinutes: Int = 5,
    val lastMinutes: Int = 30,
)

@Serializable
enum class SeatPreset(val label: String) {
    UPRIGHT("기본 자세"),
    RECLINE("휴식 자세(등받이 젖힘)"),
}

object NapJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
