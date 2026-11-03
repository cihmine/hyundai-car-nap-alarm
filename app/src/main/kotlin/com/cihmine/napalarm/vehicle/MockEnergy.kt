package com.cihmine.napalarm.vehicle

import com.cihmine.napalarm.core.ControlState
import com.cihmine.napalarm.core.EnergyReading
import com.cihmine.napalarm.core.Load
import com.cihmine.napalarm.core.PowerSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * 시연 패널에서 움직이는 모의 전원 3종과 모의 편의 장치. 실제 배터리·BMS 값이 아니다(화면에 "모의"로 표시).
 * 숫자는 계획서 12.3 데모 예시 그대로: 전기차 60kWh·SOC 60%·1.5kW / 캠핑 배터리 800Wh·조명 100W.
 */
@Serializable
data class MockEnergyState(
    val evCapacityWh: Double = 60_000.0,
    val evSoc: Double = 60.0,
    /** 공조 외 기본 소비(전장품·변환 손실) */
    val evBaseW: Double = 200.0,
    val evClimateW: Double = 1_300.0,
    val evClimateOn: Boolean = true,
    val campingWh: Double = 800.0,
    val campingLightW: Double = 100.0,
    val campingLightOn: Boolean = true,
    val starterVoltage: Double = 12.3,
    val starterWarning: Boolean = false,
    /** 측정값이 들어오지 않음 */
    val dataCut: Boolean = false,
    val charging: Boolean = false,
    /** 배터리 소모 배속(시연 시간 단축). 1 = 실제 시간 */
    val drainSpeed: Int = 1,
    val failShed: Boolean = false,
    val noResponseShed: Boolean = false,
) {
    val evLoadW: Double get() = evBaseW + if (evClimateOn) evClimateW else 0.0
    val campingLoadW: Double get() = if (campingLightOn) campingLightW else 0.0
}

class MockEnergy(initial: MockEnergyState, private val onChange: (MockEnergyState) -> Unit) {
    var state: MockEnergyState = initial
        private set

    fun update(change: MockEnergyState.() -> MockEnergyState) {
        state = state.change()
        onChange(state)
    }

    /** 지금 측정값. 데이터가 끊기면 아무것도 주지 않는다. */
    fun readings(nowMs: Long): Map<PowerSource, EnergyReading> {
        val s = state
        if (s.dataCut) return emptyMap()
        return mapOf(
            PowerSource.EV_TRACTION to EnergyReading(
                PowerSource.EV_TRACTION, nowMs, usableCapacityWh = s.evCapacityWh, socPercent = s.evSoc,
                dischargeW = s.evLoadW, charging = s.charging,
            ),
            PowerSource.CAMPING to EnergyReading(PowerSource.CAMPING, nowMs, remainingWh = s.campingWh, dischargeW = s.campingLoadW, charging = s.charging),
            PowerSource.STARTER_12V to EnergyReading(PowerSource.STARTER_12V, nowMs, voltage = s.starterVoltage, vehicleWarning = s.starterWarning),
        )
    }

    /** [dtMs] 만큼 소비(배속 반영). 화면 갱신마다 저장하지 않도록 onChange 는 부르지 않는다. */
    fun drain(dtMs: Long) {
        val s = state
        if (s.charging || dtMs <= 0) return
        val hours = dtMs / 3_600_000.0 * s.drainSpeed
        state = s.copy(
            evSoc = (s.evSoc - s.evLoadW * hours / s.evCapacityWh * 100).coerceAtLeast(0.0),
            campingWh = (s.campingWh - s.campingLoadW * hours).coerceAtLeast(0.0),
        )
    }

    fun save() = onChange(state)

    /** 모의 편의 장치 종료. ACKNOWLEDGED = 모의 장치가 꺼졌다고 응답함(물리 확인 아님). */
    suspend fun shed(loads: List<Load>): ControlState {
        delay(700)
        if (state.noResponseShed) {
            delay(5_000)
            return ControlState.TIMED_OUT
        }
        if (state.failShed) return ControlState.FAILED
        update {
            copy(
                evClimateOn = evClimateOn && Load.EV_CLIMATE !in loads,
                campingLightOn = campingLightOn && Load.CAMPING_LIGHT !in loads,
            )
        }
        return ControlState.ACKNOWLEDGED
    }
}
