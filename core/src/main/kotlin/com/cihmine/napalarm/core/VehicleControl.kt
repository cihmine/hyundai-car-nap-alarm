package com.cihmine.napalarm.core

import kotlinx.serialization.Serializable

@Serializable
enum class ControlTarget(val label: String) {
    CLIMATE("공조 온도"),
    SEAT("시트 자세"),
}

/**
 * 제어 요청 상태. 버튼을 눌렀다고 "완료"로 보지 않는다.
 * 모의 어댑터의 ACKNOWLEDGED 는 "모의 차량이 요청을 받아 값을 바꿨다고 응답함"이며 실제 물리 적용 확인이 아니다.
 */
@Serializable
enum class ControlState(val label: String) {
    REQUESTED("요청함"),
    ACKNOWLEDGED("모의 적용됨"),
    FAILED("실패"),
    TIMED_OUT("응답 없음"),

    /** 휴식이 끝나 요청을 거둠(차량 값은 바꾸지 않음) */
    CANCELLED("취소됨(휴식 끝남)"),

    /** 주행 중·상태 불명이라 요청하지 않음 */
    BLOCKED("차단됨(주차 상태 아님)"),
}

/** 차량 설정 값. null 은 읽지 못함(유효하지 않음). */
@Serializable
data class VehicleSettings(val temperatureC: Double? = null, val seat: SeatPreset? = null) {
    fun valueOf(target: ControlTarget): String? = when (target) {
        ControlTarget.CLIMATE -> temperatureC?.let { "%.1f℃".format(it) }
        ControlTarget.SEAT -> seat?.label
    }

    /** [target] 항목만 [other] 의 값으로 바꾼 설정 */
    fun with(target: ControlTarget, other: VehicleSettings): VehicleSettings = when (target) {
        ControlTarget.CLIMATE -> copy(temperatureC = other.temperatureC)
        ControlTarget.SEAT -> copy(seat = other.seat)
    }
}

@Serializable
data class ControlAction(
    val id: String,
    val sessionId: String,
    val target: ControlTarget,
    /** 요청한 값(표시용) */
    val requestedValue: String,
    val state: ControlState,
    /** 복구 요청인지(휴식 준비가 아니라 원래대로 되돌리기) */
    val isRestore: Boolean = false,
    val note: String? = null,
    /** 요청 직전의 앱이 바꾼 값(실패 시 되돌리기용) */
    val previous: VehicleSettings? = null,
)

/** 휴식 시작 전에 읽은 원래 설정. 읽지 못한 항목은 null 이고 복구 대상이 아니다. */
@Serializable
data class OriginalVehicleState(
    val sessionId: String,
    val settings: VehicleSettings,
    val capturedAtWallMs: Long,
    val source: String = "모의 차량",
)

enum class RestoreDecision(val label: String) {
    /** 앱이 바꾼 항목이고 그 뒤 아무도 바꾸지 않음 → 원래 값으로 되돌릴 후보 */
    RESTORE("원래 값으로 되돌리기"),

    /** 휴식 중 사용자가 직접 바꿈 → 덮어쓰지 않고 확인 */
    CONFIRM_MANUAL_CHANGE("직접 바꾼 값이 있어요 — 확인 필요"),

    /** 이미 원래 값과 같음(복구 완료 또는 사용자가 되돌림) */
    ALREADY_RESTORED("이미 원래 값이에요"),

    /** 앱이 바꾸지 않았거나 원래 값을 읽지 못함 */
    NOTHING_TO_DO("되돌릴 것 없음"),
}

data class RestoreItem(
    val target: ControlTarget,
    val original: String?,
    val appSet: String?,
    val current: String?,
    val decision: RestoreDecision,
)

/**
 * 복구 규칙(계획서 6절): 시작 전에 읽은 유효한 원래 값만, 앱이 바꾼 항목만 복구 후보로 삼는다.
 * 휴식 중 사용자가 바꾼 항목은 덮어쓰지 않고 확인한다.
 */
object RestorePlanner {
    fun plan(original: OriginalVehicleState?, appSet: VehicleSettings, current: VehicleSettings): List<RestoreItem> =
        ControlTarget.entries.map { t ->
            val o = original?.settings?.valueOf(t)
            val a = appSet.valueOf(t)
            val c = current.valueOf(t)
            val decision = when {
                // 앱이 바꾸지 않았음(원래 값을 못 읽었거나, 원래 값과 같은 값을 요청)
                o == null || a == null || o == a -> RestoreDecision.NOTHING_TO_DO
                c == o -> RestoreDecision.ALREADY_RESTORED
                c != a -> RestoreDecision.CONFIRM_MANUAL_CHANGE
                else -> RestoreDecision.RESTORE
            }
            RestoreItem(t, o, a, c, decision)
        }
}
