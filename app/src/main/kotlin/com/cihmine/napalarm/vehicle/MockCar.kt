package com.cihmine.napalarm.vehicle

import com.cihmine.napalarm.core.ControlState
import com.cihmine.napalarm.core.ControlTarget
import com.cihmine.napalarm.core.SeatPreset
import com.cihmine.napalarm.core.VehicleSettings
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

enum class VehicleState(val label: String) {
    PARKED("주차"),
    MOVING("주행"),

    /** 값 없음·오래된 값. 주행으로 보고 제어를 막는다. */
    UNKNOWN("상태 불명"),
}

/**
 * 공조·시트 제어 경계. 이번 빌드에는 [MockCar] 구현만 있다 — 실제 차량 제어 어댑터는 없고 호출할 수도 없다.
 * Pleos Vehicle Control 이 열리면 지원 차종·권한·전원 조건을 확인한 뒤 별도 구현을 추가한다.
 */
interface VehicleControl {
    val isSimulated: Boolean
    fun read(): VehicleSettings

    /** 요청 결과. ACKNOWLEDGED 는 요청 접수 응답이며 물리 적용 확인이 아니다. */
    suspend fun request(target: ControlTarget, value: VehicleSettings): ControlState
}

/** 시연 패널에서 바꾸는 모의 차량 설정·실패 옵션 */
@Serializable
data class MockCarState(
    val settings: VehicleSettings = VehicleSettings(24.0, SeatPreset.UPRIGHT),
    val failClimate: Boolean = false,
    val failSeat: Boolean = false,
    val noResponse: Boolean = false,
)

class MockCar(initial: MockCarState, private val onChange: (MockCarState) -> Unit) : VehicleControl {
    override val isSimulated = true

    var state: MockCarState = initial
        private set

    fun update(change: MockCarState.() -> MockCarState) {
        state = state.change()
        onChange(state)
    }

    override fun read(): VehicleSettings = state.settings

    override suspend fun request(target: ControlTarget, value: VehicleSettings): ControlState {
        delay(700) // 차량 응답을 기다리는 것처럼
        if (state.noResponse) {
            delay(5_000)
            return ControlState.TIMED_OUT
        }
        val fail = if (target == ControlTarget.CLIMATE) state.failClimate else state.failSeat
        if (fail) return ControlState.FAILED
        update {
            copy(
                settings = when (target) {
                    ControlTarget.CLIMATE -> settings.copy(temperatureC = value.temperatureC ?: settings.temperatureC)
                    ControlTarget.SEAT -> settings.copy(seat = value.seat ?: settings.seat)
                },
            )
        }
        return ControlState.ACKNOWLEDGED
    }
}
