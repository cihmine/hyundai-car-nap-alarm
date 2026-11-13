package com.cihmine.napalarm.vehicle

import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacMaxAcStateListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import com.cihmine.napalarm.core.CabinState

/**
 * Pleos Vehicle SDK 로 공조(전원·에어컨·최대 냉방·바람 세기)와 실내등 상태를 읽고 바뀔 때마다 알린다.
 * 읽기만 하고 명령은 보내지 않는다. 못 읽은 값은 null 로 남겨 앱이 모의 소비 전력으로 돌아가게 한다.
 */
class PleosCabinState(private val vehicle: Vehicle?, private val onState: (CabinState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var state = CabinState()
    private var area: SeatArea? = null
    /** 바람 세기는 공조 전원과 지원 영역이 다를 수 있어 따로 둔다 */
    private var fanArea: SeatArea? = null
    private var tries = 0

    private fun set(change: CabinState.() -> CabinState) = main.post {
        state = state.change()
        onState(state)
    }

    private val power = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(value: Boolean?) { set { copy(climateOn = value) } }
        override fun onFailed(exception: Exception) { set { copy(climateOn = null) } }
    }
    private val ac = object : HvacAcStateListener {
        override fun onHvacAcStateUpdated(value: Boolean?) { set { copy(acOn = value) } }
        override fun onFailed(exception: Exception) { set { copy(acOn = null) } }
    }
    private val maxAc = object : HvacMaxAcStateListener {
        override fun onHvacMaxAcStateUpdated(value: Boolean?) { set { copy(maxAcOn = value) } }
        override fun onFailed(exception: Exception) { set { copy(maxAcOn = null) } }
    }
    private val fan = object : HvacFanSpeedListener {
        override fun onHvacFanSpeedUpdated(value: Int?) { set { copy(fanLevel = value) } }
        override fun onFailed(exception: Exception) { set { copy(fanLevel = null) } }
    }
    private val light = object : LightStateListener {
        override fun onLightStateUpdated(value: LightState?) { set { copy(cabinLightOn = value?.let { it == LightState.ON }) } }
        override fun onFailed(exception: Exception) { set { copy(cabinLightOn = null) } }
    }

    init {
        connect()
    }

    /** 서비스 연결이 끝나기 전에는 호출이 실패하므로 공조 영역을 받을 때까지 몇 번 다시 시도한다. */
    private fun connect() {
        val v = vehicle ?: return
        attempt("공조 영역 확인") {
            v.getHVAC().checkHvacPowerCapabilities({ areas: List<SeatArea> ->
                Log.i(TAG, "공조 영역 ${areas.map { it.values.toList() }}")
                main.post { areas.firstOrNull()?.let { if (area == null) subscribe(v, it) } }
            }, { Log.w(TAG, "공조 영역 확인 실패: $it") })
        }
        main.postDelayed({ if (area == null && ++tries < MAX_TRIES) connect() }, RETRY_MS)
    }

    private fun subscribe(v: Vehicle, a: SeatArea) {
        area = a
        val hvac = v.getHVAC()
        attempt("공조 전원") {
            hvac.getHvacPowerState(a, { value -> Log.i(TAG, "공조 전원 $value"); set { copy(climateOn = value) } }, { Log.w(TAG, "공조 전원 읽기 실패: $it") })
            hvac.registerHvacPowerState(a, power)
        }
        attempt("에어컨") {
            hvac.getHvacAcState(a, { value -> Log.i(TAG, "에어컨 $value"); set { copy(acOn = value) } }, { Log.w(TAG, "에어컨 읽기 실패: $it") })
            hvac.registerHvacAcState(a, ac)
        }
        attempt("최대 냉방") {
            hvac.getHvacMaxAcState(a, { value -> set { copy(maxAcOn = value) } }, { Log.w(TAG, "최대 냉방 읽기 실패: $it") })
            hvac.registerHvacMaxAcState(a, maxAc)
        }
        attempt("바람 세기") {
            hvac.checkHvacFanSpeedCapabilities({ bounds ->
                val fa = bounds.keys.firstOrNull { it == a } ?: bounds.keys.firstOrNull()
                val max = fa?.let { bounds[it] }?.maxValue
                Log.i(TAG, "바람 세기 영역 ${fa?.values?.toList()} 최대 $max")
                main.post {
                    if (fa == null || fanArea != null) return@post
                    fanArea = fa
                    set { copy(fanMax = max) }
                    attempt("바람 세기 읽기") {
                        hvac.getHvacFanSpeed(fa, { value -> Log.i(TAG, "바람 세기 $value"); set { copy(fanLevel = value) } }, { Log.w(TAG, "바람 세기 읽기 실패: $it") })
                        hvac.registerHvacFanSpeed(fa, fan)
                    }
                }
            }, { Log.w(TAG, "바람 세기 범위 실패: $it") })
        }
        attempt("실내등") {
            v.getLight().getCurrentCabinLightState({ value -> Log.i(TAG, "실내등 $value"); set { copy(cabinLightOn = value?.let { it == LightState.ON }) } }, { Log.w(TAG, "실내등 읽기 실패: $it") })
            v.getLight().registerCabinLightState(light)
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        val v = vehicle ?: return
        val a = area ?: return
        attempt("공조 구독 해제") {
            v.getHVAC().unregisterHvacPowerState(a, power)
            v.getHVAC().unregisterHvacAcState(a, ac)
            v.getHVAC().unregisterHvacMaxAcState(a, maxAc)
            fanArea?.let { v.getHVAC().unregisterHvacFanSpeed(it, fan) }
            v.getLight().unregisterCabinLightState(light)
        }
    }

    private fun <T> attempt(what: String, action: () -> T): T? =
        runCatching(action).onFailure { Log.w(TAG, "$what 실패", it) }.getOrNull()

    private companion object {
        const val TAG = "PleosCabinState"
        const val RETRY_MS = 1_000L
        const val MAX_TRIES = 10
    }
}
