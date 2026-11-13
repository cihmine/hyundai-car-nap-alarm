package com.cihmine.napalarm.vehicle

import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import com.cihmine.napalarm.core.RealEvBattery

/**
 * Pleos Vehicle SDK 로 전기차 구동 배터리의 충전률·용량과 주행 가능 거리(Odometer.getRangeRemainingDistance, 미터)를 읽는다.
 * 값을 못 읽거나(내연기관차 포함) 구독이 끊기면 null 을 알려 앱이 모의 전원 값으로 돌아간다.
 * 소비 전력은 SDK 에 없어 모의 그대로다.
 */
class PleosEvBattery(private val vehicle: Vehicle?, private val onValue: (RealEvBattery?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var soc: Int? = null
    private var capacityWh: Float? = null
    private var rangeM: Float? = null
    private var subscribed = false
    private var rangeSubscribed = false
    private var tries = 0

    private val rangeListener = object : RangeRemainingListener {
        override fun onRangeRemainingChanged(value: Float?) = main.post { rangeM = value; publish() }.let { }
        override fun onFailed(exception: Exception) {
            Log.w(TAG, "주행 가능 거리 구독 오류", exception)
            main.post { rangeM = null; publish() }
        }
    }

    private val listener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = main.post { update(value) }.let { }
        override fun onFailed(exception: Exception) {
            Log.w(TAG, "충전률 구독 오류", exception)
            main.post { update(null) }
        }
    }

    init {
        connect()
    }

    /** 서비스 연결이 끝나기 전에는 호출이 실패하므로 값이 올 때까지 몇 번 다시 시도한다. */
    private fun connect() {
        val battery = vehicle ?: return
        attempt("배터리 읽기") {
            battery.getEvBattery().getEvBatteryCapacity({ value ->
                Log.i(TAG, "용량 $value")
                main.post { capacityWh = value; publish() }
            }, { Log.w(TAG, "용량 읽기 실패: $it") })
            battery.getEvBattery().getEvBatteryStateOfCharge({ value ->
                Log.i(TAG, "충전률 $value")
                main.post {
                    if (!subscribed) {
                        subscribed = true
                        attempt("충전률 구독") { battery.getEvBattery().registerEvBatteryStateOfCharge(listener) }
                    }
                    update(value)
                }
            }, { Log.w(TAG, "충전률 읽기 실패: $it") })
            battery.getOdometer().getRangeRemainingDistance({ value ->
                Log.i(TAG, "주행 가능 거리 $value")
                main.post {
                    if (!rangeSubscribed) {
                        rangeSubscribed = true
                        attempt("주행 가능 거리 구독") { battery.getOdometer().registerRangeRemainingListener(rangeListener) }
                    }
                    rangeM = value
                    publish()
                }
            }, { Log.w(TAG, "주행 가능 거리 읽기 실패: $it") })
        }
        main.postDelayed({
            if (!subscribed && ++tries < MAX_TRIES) connect()
        }, RETRY_MS)
    }

    private fun update(value: Int?) {
        soc = value
        publish()
    }

    /** 주행 가능 거리는 미터로 온다(에뮬: 500000 = 클러스터 500km, Android VHAL RANGE_REMAINING 과 같은 단위) */
    private fun publish() = onValue(RealEvBattery.of(soc, capacityWh, rangeM?.let { it / 1000f }))

    fun release() {
        main.removeCallbacksAndMessages(null)
        attempt("배터리 구독 해제") {
            if (subscribed) vehicle?.getEvBattery()?.unregisterEvBatteryStateOfCharge(listener)
            if (rangeSubscribed) vehicle?.getOdometer()?.unregisterRangeRemainingListener(rangeListener)
        }
    }

    private fun <T> attempt(what: String, action: () -> T): T? =
        runCatching(action).onFailure { Log.w(TAG, "$what 실패", it) }.getOrNull()

    private companion object {
        const val TAG = "PleosEvBattery"
        const val RETRY_MS = 1_000L
        const val MAX_TRIES = 10
    }
}
