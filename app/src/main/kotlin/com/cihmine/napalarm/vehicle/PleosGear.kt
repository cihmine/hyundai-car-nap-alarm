package com.cihmine.napalarm.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/**
 * Pleos Vehicle SDK 로 실제 기어 상태를 읽는다. 기어가 P 일 때만 주차로 본다.
 * 값을 받지 못하거나 구독이 끊기면 상태 불명으로 알린다. 공조·시트 제어는 [MockCar] 그대로다.
 * SDK 콜백은 다른 스레드에서 올 수 있어 메인 스레드로 넘긴다.
 */
class PleosGear(context: Context, private val onGear: (VehicleState, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = attempt("Vehicle 준비") { Vehicle(context.applicationContext).also { it.initialize() } }
    private var connected = false
    private var tries = 0

    private val listener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = publish(gear ?: VehicleGear.GEAR_UNKNOWN)
        override fun onFailed(exception: Exception) {
            Log.w(TAG, "기어 상태 오류", exception)
            publish(VehicleGear.GEAR_UNKNOWN)
        }
    }

    init {
        connect()
    }

    /** 서비스 연결이 끝나기 전에는 호출이 실패하므로 값이 올 때까지 몇 번 다시 시도한다. */
    private fun connect() {
        val v = vehicle ?: return
        attempt("기어 읽기") {
            v.getGear().getCurrentGearSelectionState({ gear ->
                main.post {
                    if (!connected) {
                        connected = true
                        attempt("기어 구독") { v.getGear().registerGearSelection(listener) }
                    }
                }
                publish(gear ?: VehicleGear.GEAR_UNKNOWN)
            }, { Log.w(TAG, "기어 읽기 실패: $it") })
        }
        main.postDelayed({
            if (connected) return@postDelayed
            if (++tries < MAX_TRIES) connect() else publish(VehicleGear.GEAR_UNKNOWN)
        }, RETRY_MS)
    }

    private fun publish(gear: VehicleGear) {
        val state = when (gear) {
            VehicleGear.GEAR_PARK -> VehicleState.PARKED
            VehicleGear.GEAR_UNKNOWN -> VehicleState.UNKNOWN
            else -> VehicleState.MOVING
        }
        val label = when (gear) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            VehicleGear.GEAR_UNKNOWN -> "알 수 없음"
            else -> gear.name.removePrefix("GEAR_")
        }
        main.post { onGear(state, label) }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        attempt("Vehicle 해제") {
            if (connected) vehicle?.getGear()?.unregisterGearSelection(listener)
            vehicle?.release()
        }
    }

    private fun <T> attempt(what: String, action: () -> T): T? =
        runCatching(action).onFailure { Log.w(TAG, "$what 실패", it) }.getOrNull()

    private companion object {
        const val TAG = "PleosGear"
        const val RETRY_MS = 1_000L
        const val MAX_TRIES = 10
    }
}
