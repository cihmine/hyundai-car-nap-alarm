package com.cihmine.napalarm.vehicle

import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import com.cihmine.napalarm.core.PowertrainSignals

/**
 * 차종을 판별하려고 Pleos Vehicle SDK 에서 두 가지를 확인한다.
 * - 전기차 구동 배터리 충전률을 읽을 수 있는지(지원 정보)
 * - 연료량(%)을 읽을 수 있는지(지원 정보 + 실제 값). 연료량은 구독해서 바뀔 때마다 알린다.
 * SDK 에 '연료 종류' 값은 없어서 이 둘로 추정한다. 못 읽으면 null(모름)로 두고, 지어내지 않는다.
 */
class PleosPowertrain(private val vehicle: Vehicle?, private val onSignals: (PowertrainSignals) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var signals = PowertrainSignals()
    private var subscribed = false
    private var tries = 0

    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) = main.post { update(signals.copy(fuelPercent = value?.takeIf { it in 0..100 })) }.let { }
        override fun onFailed(exception: Exception) {
            Log.w(TAG, "연료량 구독 오류", exception)
        }
    }

    init {
        connect()
    }

    /** 차에 그 값이 아예 없다는 응답(일시적인 연결 실패와 구분한다) */
    private fun Exception.notSupported(): Boolean {
        val text = generateSequence<Throwable>(this) { it.cause }.joinToString(" ") { "${it.message} $it" }
        return "NOT_SUPPORTED" in text || "not supported" in text
    }

    private fun AccessType.readable() = this == AccessType.READ || this == AccessType.READ_WRITE

    /** 서비스 연결이 끝나기 전에는 호출이 실패하므로 몇 번 다시 시도한다. */
    private fun connect() {
        val v = vehicle ?: return
        attempt("전기차 배터리 지원 확인") {
            v.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ cap ->
                Log.i(TAG, "전기차 충전률 지원: ${cap.accessType}")
                main.post { update(signals.copy(evSupported = cap.accessType.readable())) }
            }, { e ->
                Log.w(TAG, "전기차 충전률 지원 확인 실패: $e")
                if (e.notSupported()) main.post { update(signals.copy(evSupported = false)) }
            })
        }
        attempt("연료량 지원 확인") {
            v.getFuel().checkFuelPercentCapabilities({ cap ->
                Log.i(TAG, "연료량 지원: ${cap.accessType}")
                main.post { update(signals.copy(fuelSupported = cap.accessType.readable())) }
            }, { e ->
                Log.w(TAG, "연료량 지원 확인 실패: $e")
                if (e.notSupported()) main.post { update(signals.copy(fuelSupported = false)) }
            })
        }
        attempt("연료량 읽기") {
            v.getFuel().getFuelPercent({ value ->
                Log.i(TAG, "연료량 $value")
                main.post {
                    if (!subscribed) {
                        subscribed = true
                        attempt("연료량 구독") { v.getFuel().registerFuelPercent(fuelListener) }
                    }
                    update(signals.copy(fuelPercent = value?.takeIf { it in 0..100 }))
                }
            }, { e ->
                Log.w(TAG, "연료량 읽기 실패: $e")
                if (e.notSupported()) main.post { update(signals.copy(fuelSupported = false)) }
            })
        }
        main.postDelayed({
            if ((signals.evSupported == null || signals.fuelSupported == null) && ++tries < MAX_TRIES) connect()
        }, RETRY_MS)
    }

    private fun update(next: PowertrainSignals) {
        signals = next
        onSignals(next)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        attempt("연료량 구독 해제") { if (subscribed) vehicle?.getFuel()?.unregisterFuelPercent(fuelListener) }
    }

    private fun <T> attempt(what: String, action: () -> T): T? =
        runCatching(action).onFailure { Log.w(TAG, "$what 실패", it) }.getOrNull()

    private companion object {
        const val TAG = "PleosPowertrain"
        const val RETRY_MS = 1_000L
        const val MAX_TRIES = 10
    }
}
