package com.cihmine.napalarm.core

import kotlinx.serialization.Serializable
import kotlin.math.max

/*
 * 전력 관리(계획서 12절) — 한눈에 보기
 *
 *   EnergyReading(전원 하나의 측정값) ──▶ RuntimeEstimator ──▶ RuntimeEstimate(남은 사용 시간 또는 "계산 불가" 이유)
 *                                                                   │
 *                         기상까지 남은 시간 ──────────────────────▶ EnergyGuard ──▶ 이벤트(먼저 깨우기·장치 종료 요청·데이터 끊김)
 *
 * 전원 3종은 절대 합치지 않는다: 전기차 구동 배터리 / 내연기관차 12V 시동 배터리 / 별도 캠핑 배터리.
 * 12V 시동 배터리는 전압 하나로 잔량·재시동 가능 여부를 알 수 없으므로 늘 "계산 불가"이고, 차량이 보낸 경고 신호만 알린다.
 * 이 빌드의 측정값·장치 종료는 전부 모의(시연 패널)다.
 */

/** 전원 종류. 서로 다른 전원으로 따로 계산한다. */
@Serializable
enum class PowerSource(val label: String) {
    EV_TRACTION("전기차 구동 배터리"),
    STARTER_12V("12V 시동 배터리"),
    CAMPING("별도 캠핑 배터리"),
}

/** 종료를 요청할 수 있는 개별 편의 장치(차량 전체 전원·안전 장치는 대상이 아니다) */
@Serializable
enum class Load(val label: String, val source: PowerSource) {
    EV_CLIMATE("공조(구동 배터리)", PowerSource.EV_TRACTION),
    CAMPING_LIGHT("캠핑 조명", PowerSource.CAMPING),
}

/**
 * 차량·전원 구성. [managed] 는 남은 시간을 계산하고 보호할 전원(없으면 알람만), [shedLoads] 는 보호 기준에서 끌 장치.
 * 사용자가 골랐다고 실제 제어 권한이 생기는 것은 아니다 — 이 빌드에선 모의 장치만 끈다.
 */
@Serializable
enum class VehicleProfile(val label: String, val sources: List<PowerSource>, val managed: PowerSource?, val shedLoads: List<Load>) {
    EV("전기차", listOf(PowerSource.EV_TRACTION), PowerSource.EV_TRACTION, listOf(Load.EV_CLIMATE)),
    ICE("내연기관차", listOf(PowerSource.STARTER_12V), null, emptyList()),
    ICE_CAMPING("내연기관차 + 캠핑 배터리", listOf(PowerSource.STARTER_12V, PowerSource.CAMPING), PowerSource.CAMPING, listOf(Load.CAMPING_LIGHT)),
}

/**
 * 차량 SDK 에서 읽은 전기차 구동 배터리 값. 용량을 못 읽으면 [capacityWh] 는 null 이고 모의 용량을 쓴다.
 * 범위를 벗어난 값은 잔량을 지어내지 않도록 버린다.
 */
data class RealEvBattery(val socPercent: Double, val capacityWh: Double?, val rangeKm: Double? = null) {
    companion object {
        fun of(soc: Number?, capacityWh: Number?, rangeKm: Number? = null): RealEvBattery? {
            val percent = soc?.toDouble()?.takeIf { it in 0.0..100.0 } ?: return null
            return RealEvBattery(
                percent, capacityWh?.toDouble()?.takeIf { it > 0.0 && it.isFinite() },
                rangeKm?.toDouble()?.takeIf { it >= 0.0 && it.isFinite() },
            )
        }
    }
}

/** 전원 하나의 측정값. 모르는 값은 null. [simulated] 는 시연 패널 입력인지(화면에 "모의"로 표시). */
@Serializable
data class EnergyReading(
    val source: PowerSource,
    val sampledAtMs: Long,
    /** 사용 가능한 남은 에너지(직접 제공될 때 우선) */
    val remainingWh: Double? = null,
    /** 사용 가능 총용량 — SOC 와 같은 기준일 때만 용량×SOC 로 근사 */
    val usableCapacityWh: Double? = null,
    /** 0~100 */
    val socPercent: Double? = null,
    /** 차량이 알려 준 주행 가능 거리(km). 전기차만 */
    val rangeKm: Double? = null,
    /** 이 전원에서 나가는 총 소비 전력(W). 공조·조명·변환 손실 포함. 0 이하이면 충전·공급 중 또는 소비 없음 */
    val dischargeW: Double? = null,
    val charging: Boolean = false,
    val voltage: Double? = null,
    /** 차량이 보낸 배터리 경고 신호(12V 등) */
    val vehicleWarning: Boolean = false,
    val simulated: Boolean = true,
)

/** 전기차 남길 배터리의 단위. km 는 지금 주행 가능 거리로 %로 바꿔 계산한다. */
@Serializable
enum class ReserveUnit { PERCENT, KM }

/** 남겨 둘 에너지. 바닥까지 쓰지 않는다. 전기차는 퍼센트 또는 주행 가능 km, 캠핑 배터리는 Wh. */
@Serializable
data class ReserveSettings(
    val evReserveSoc: Double = 30.0,
    val campingReserveWh: Double = 200.0,
    /** 보호 기준 몇 분 전에 먼저 깨울지(초기 제안값) */
    val warningLeadMinutes: Int = 10,
    val evUnit: ReserveUnit = ReserveUnit.PERCENT,
    /** [evUnit] 이 KM 일 때 남길 주행 가능 거리 */
    val evReserveKm: Double = 100.0,
) {
    /** 화면에 쓰는 남길 양("30%" 또는 "100km") */
    val evLabel: String get() = if (evUnit == ReserveUnit.KM) "${evReserveKm.toInt()}km" else "${evReserveSoc.toInt()}%"

    /**
     * 남길 양을 충전률(%)로. km 이면 지금 1% 당 주행 거리(주행 가능 거리 ÷ 충전률)로 바꾼다.
     * 주행 가능 거리를 모르거나 충전률이 0 이면 바꿀 수 없어 null(지어내지 않는다).
     */
    fun evSocFor(socPercent: Double?, rangeKm: Double?): Double? = when (evUnit) {
        ReserveUnit.PERCENT -> evReserveSoc
        ReserveUnit.KM -> if (socPercent != null && socPercent > 0.0 && rangeKm != null && rangeKm > 0.0)
            (evReserveKm * socPercent / rangeKm).coerceIn(0.0, 100.0) else null
    }
}

enum class UnknownReason(val label: String) {
    NO_SOURCE("전원 데이터 없음 — 알람만 사용해요"),
    STARTER_NOT_ESTIMATED("12V 시동 배터리는 전압만으로 남은 시간을 알 수 없어요 — 계산 불가"),
    MISSING("측정값이 없어요 — 계산 불가"),
    STALE("측정값이 오래됐어요 — 계산 불가"),
    NO_RESERVE_BASIS("보존량을 같은 기준으로 바꿀 수 없어요 — 계산 불가"),
}

/** 남은 사용 시간 추정 결과. 계산할 수 없으면 숫자를 만들지 않는다. */
sealed interface RuntimeEstimate {
    val source: PowerSource?

    /** (남은 에너지 − 보존량) ÷ 소비 전력. [formula] 는 화면에 그대로 보여 줄 계산식. */
    data class Available(
        override val source: PowerSource,
        val usableWh: Double,
        val reserveWh: Double,
        val loadW: Double,
        val minutes: Double,
        val simulated: Boolean,
    ) : RuntimeEstimate {
        val formula: String
            get() = "(${wh(usableWh)} − ${wh(reserveWh)}) ÷ ${w(loadW)} = ${Texts.durationMinutes(minutes)}"
    }

    /** 충전·전원 공급 중이라 순방전이 없음 — 무한 시간을 표시하지 않는다 */
    data class Charging(override val source: PowerSource) : RuntimeEstimate

    /** 충전도 아닌데 소비가 0 — 0으로 나누지 않는다 */
    data class NoDrain(override val source: PowerSource) : RuntimeEstimate

    data class Unknown(override val source: PowerSource?, val reason: UnknownReason) : RuntimeEstimate

    companion object {
        fun wh(v: Double) = if (v >= 1000) "%.1fkWh".format(v / 1000) else "%.0fWh".format(v)
        fun w(v: Double) = if (v >= 1000) "%.1fkW".format(v / 1000) else "%.0fW".format(v)
    }
}

/** 계획서 12.3 의 식을 그대로 계산한다. 모의 입력이어도 계산은 실제다. */
object RuntimeEstimator {
    /** 이보다 오래된 측정값은 쓰지 않는다 */
    const val STALE_MS = 60_000L

    fun estimate(reading: EnergyReading?, source: PowerSource?, reserve: ReserveSettings, nowMs: Long): RuntimeEstimate {
        if (source == null) return RuntimeEstimate.Unknown(null, UnknownReason.NO_SOURCE)
        // 12V: 전압 하나로 잔량을 만들어 내지 않는다(N-T12)
        if (source == PowerSource.STARTER_12V) return RuntimeEstimate.Unknown(source, UnknownReason.STARTER_NOT_ESTIMATED)
        if (reading == null || reading.source != source) return RuntimeEstimate.Unknown(source, UnknownReason.MISSING)
        if (nowMs - reading.sampledAtMs > STALE_MS) return RuntimeEstimate.Unknown(source, UnknownReason.STALE)
        if (reading.charging) return RuntimeEstimate.Charging(source)
        val load = reading.dischargeW ?: return RuntimeEstimate.Unknown(source, UnknownReason.MISSING)
        if (load <= 0.0) return RuntimeEstimate.NoDrain(source)
        val usable = reading.remainingWh
            ?: if (reading.usableCapacityWh != null && reading.socPercent != null) reading.usableCapacityWh * reading.socPercent / 100 else null
            ?: return RuntimeEstimate.Unknown(source, UnknownReason.MISSING)
        val reserveWh = when (source) {
            PowerSource.EV_TRACTION -> reading.usableCapacityWh?.let { cap -> reserve.evSocFor(reading.socPercent, reading.rangeKm)?.let { cap * it / 100 } }
            PowerSource.CAMPING -> reserve.campingReserveWh
            PowerSource.STARTER_12V -> null
        } ?: return RuntimeEstimate.Unknown(source, UnknownReason.NO_RESERVE_BASIS)
        val minutes = max(usable - reserveWh, 0.0) / load * 60
        return RuntimeEstimate.Available(source, usable, reserveWh, load, minutes, reading.simulated)
    }
}

/**
 * 전력 보호 상태(기상 타이머와 별개). 기상 알람을 꺼도 초기화되지 않고, 한 번 장치를 끄면 이번 휴식 동안 되돌리지 않는다.
 * 화면에는 [simpleLabel] 4가지로 묶어 보여 준다.
 */
@Serializable
enum class GuardStatus(val simpleLabel: String) {
    UNAVAILABLE("계산 불가 · 알람만"),
    MONITORING("감시 중"),
    WARNING("곧 장치 종료"),
    SHED_REQUESTED("곧 장치 종료"),
    SHED_CONFIRMED("장치 종료됨"),
    SHED_FAILED("장치 종료 실패 — 직접 꺼 주세요"),
    ;

    val shed: Boolean get() = this == SHED_REQUESTED || this == SHED_CONFIRMED || this == SHED_FAILED
}

@Serializable
data class EnergyGuardState(
    val status: GuardStatus = GuardStatus.UNAVAILABLE,
    val profile: VehicleProfile = VehicleProfile.EV,
    /** 먼저 깨우기 알림을 이미 보냈는지(한 번만) */
    val warned: Boolean = false,
    /** "보호 시점이 기상보다 빨라요" 안내를 이미 했는지 */
    val beforeWakeNotified: Boolean = false,
    /** 감시하던 중 데이터가 끊겼다고 알렸는지(다시 들어오면 풀림) */
    val dataLostNotified: Boolean = false,
    val starterWarned: Boolean = false,
    /** 종료를 요청한 장치 */
    val shedLoads: List<Load> = emptyList(),
    val lastTransitionAtMs: Long = 0,
)

sealed interface GuardEvent {
    /** 보호 시점이 기상 시각보다 먼저 올 것 같음 — 안내만 */
    data class ProtectionBeforeWake(val minutes: Double) : GuardEvent

    /** 보호 기준 [minutes] 분 전 — 먼저 깨운다(알람) */
    data class WarnEarly(val minutes: Double) : GuardEvent

    /** 보존량에 도달 — 이 장치들을 끄라고 요청 */
    data class RequestShed(val loads: List<Load>) : GuardEvent

    /** 감시 중 측정값이 끊김 — 안전하게 껐다고 가정하지 않고 알린다 */
    data class DataLost(val reason: UnknownReason) : GuardEvent

    /** 차량이 12V 배터리 경고를 보냄 — 알람 */
    data object StarterWarning : GuardEvent
}

/**
 * 충전 대기 모드의 감시 상태(낮잠 타이머·전력 보호와 별개). 모드를 켜면 만들고 끄면 비운다.
 * [armed]: 충전률이 목표보다 낮은 것을 한 번 봤는지(이미 넘은 상태에서 켜도 바로 울리지 않게).
 * [done]: 이번 대기에서 이미 알렸는지(한 번만). [ringing]: 알람이 울리는 중.
 */
@Serializable
data class ChargeWaitState(
    val startedWallMs: Long,
    val armed: Boolean = false,
    val done: Boolean = false,
    val ringing: Boolean = false,
    val reachedSoc: Double? = null,
)

/**
 * 충전 완료 판단. 순수 함수. 충전률이 목표보다 낮은 것을 본 뒤 목표 이상이 되면 한 번 울린다.
 * 충전 중 여부 신호에 기대지 않고 충전률이 아래에서 위로 넘는 것만 본다 — 공개 SDK 로 충전 상태를 못 읽어도 실제 충전률로 동작한다.
 */
object ChargeWatch {
    fun step(s: ChargeWaitState, socPercent: Double?, targetSoc: Int): Pair<ChargeWaitState, Boolean> {
        if (socPercent == null || s.done) return s to false
        return when {
            socPercent < targetSoc -> (if (s.armed) s else s.copy(armed = true)) to false
            s.armed -> s.copy(done = true, ringing = true, reachedSoc = socPercent) to true
            else -> s to false
        }
    }
}

/** 보호 판단. 순수 함수: 상태와 입력을 받아 새 상태와 이벤트를 돌려준다. */
object EnergyGuard {
    fun start(profile: VehicleProfile, nowMs: Long) = EnergyGuardState(profile = profile, lastTransitionAtMs = nowMs)

    fun step(
        g: EnergyGuardState,
        estimate: RuntimeEstimate,
        starterWarning: Boolean,
        wakeRemainingMs: Long?,
        reserve: ReserveSettings,
        nowMs: Long,
    ): Pair<EnergyGuardState, List<GuardEvent>> {
        val events = mutableListOf<GuardEvent>()
        var next = g
        if (starterWarning && !g.starterWarned) {
            events += GuardEvent.StarterWarning
            next = next.copy(starterWarned = true)
        }
        // 이미 장치 종료 단계면 다시 켜거나 다시 요청하지 않는다
        if (next.status.shed) return next to events

        fun move(s: GuardStatus) { if (next.status != s) next = next.copy(status = s, lastTransitionAtMs = nowMs) }

        when (estimate) {
            is RuntimeEstimate.Available -> {
                next = next.copy(dataLostNotified = false)
                when {
                    estimate.minutes <= 0.0 && next.profile.shedLoads.isNotEmpty() -> {
                        move(GuardStatus.SHED_REQUESTED)
                        next = next.copy(shedLoads = next.profile.shedLoads)
                        events += GuardEvent.RequestShed(next.profile.shedLoads)
                    }
                    estimate.minutes <= reserve.warningLeadMinutes -> {
                        move(GuardStatus.WARNING)
                        if (!next.warned) {
                            events += GuardEvent.WarnEarly(estimate.minutes)
                            next = next.copy(warned = true, beforeWakeNotified = true)
                        }
                    }
                    else -> {
                        move(if (next.warned) GuardStatus.WARNING else GuardStatus.MONITORING)
                        if (!next.beforeWakeNotified && wakeRemainingMs != null && estimate.minutes * 60_000 < wakeRemainingMs) {
                            events += GuardEvent.ProtectionBeforeWake(estimate.minutes)
                            next = next.copy(beforeWakeNotified = true)
                        }
                    }
                }
            }
            is RuntimeEstimate.Charging, is RuntimeEstimate.NoDrain -> {
                next = next.copy(dataLostNotified = false)
                move(GuardStatus.MONITORING)
            }
            is RuntimeEstimate.Unknown -> {
                val wasWatching = next.status == GuardStatus.MONITORING || next.status == GuardStatus.WARNING
                if (wasWatching && !next.dataLostNotified &&
                    (estimate.reason == UnknownReason.STALE || estimate.reason == UnknownReason.MISSING)
                ) {
                    events += GuardEvent.DataLost(estimate.reason)
                    next = next.copy(dataLostNotified = true)
                }
                move(GuardStatus.UNAVAILABLE)
            }
        }
        return next to events
    }

    /** 장치 종료 결과. 응답 없음·실패는 "종료됨"으로 쓰지 않는다(N-T16). */
    fun onShedResult(g: EnergyGuardState, result: ControlState, nowMs: Long): EnergyGuardState {
        if (g.status != GuardStatus.SHED_REQUESTED) return g
        val s = if (result == ControlState.ACKNOWLEDGED) GuardStatus.SHED_CONFIRMED else GuardStatus.SHED_FAILED
        return g.copy(status = s, lastTransitionAtMs = nowMs)
    }
}
