package com.cihmine.napalarm.vehicle

import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 실내등 명령 결과. 화면에 사실대로 쓰기 위해 구분한다. */
enum class LightOutcome {
    /** 차량이 지원하지 않거나 실제 기어 P 가 아니어서 모의로 처리함 */
    MOCK,

    /** 이미 요청한 값이라 명령을 보내지 않음 */
    ALREADY,

    /** 명령 뒤 값이 요청한 값으로 바뀐 것을 확인함 */
    CONFIRMED,

    /** 명령은 접수됐지만 값이 바뀌지 않음(에뮬레이터는 성공을 답해도 값이 그대로다) */
    UNCONFIRMED,
    FAILED,
}

/** Pleos Vehicle SDK 의 실내등. 끄기를 지원한다고 답한 차량에서만 [supported] 가 true 가 된다. */
class PleosCabinLight(private val vehicle: Vehicle?) {
    private val main = Handler(Looper.getMainLooper())
    private var tries = 0

    @Volatile
    var supported = false
        private set

    init {
        check()
    }

    /** 서비스 연결이 끝나기 전에는 호출이 실패하므로 몇 번 다시 시도한다. */
    private fun check() {
        val v = vehicle ?: return
        runCatching {
            v.getLight().checkCabinLightSwitchCapabilities({ values: List<LightSwitch> ->
                supported = LightSwitch.OFF in values
                Log.i(TAG, "실내등 지원: $values")
            }, { Log.w(TAG, "실내등 확인 실패: $it") })
        }.onFailure { Log.w(TAG, "실내등 확인 실패", it) }
        main.postDelayed({ if (!supported && ++tries < MAX_TRIES) check() }, RETRY_MS)
    }

    suspend fun read(): String? = withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { c ->
            val v = vehicle
            if (v == null) c.resume(null) else runCatching {
                v.getLight().getCabinLightSwitch({ value -> if (c.isActive) c.resume(value?.name) }, { if (c.isActive) c.resume(null) })
            }.onFailure { if (c.isActive) c.resume(null) }
        }
    }

    suspend fun set(target: String): LightOutcome {
        val v = vehicle ?: return LightOutcome.FAILED
        val value = LightSwitch.entries.firstOrNull { it.name == target } ?: return LightOutcome.FAILED
        if (read() == target) return LightOutcome.ALREADY
        val sent = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { c ->
                runCatching {
                    v.getLight().setCabinLightSwitch(value, { if (c.isActive) c.resume(true) }, {
                        Log.w(TAG, "실내등 변경 실패: $it")
                        if (c.isActive) c.resume(false)
                    })
                }.onFailure { if (c.isActive) c.resume(false) }
            }
        } ?: false
        if (!sent) return LightOutcome.FAILED
        return if (read() == target) LightOutcome.CONFIRMED else LightOutcome.UNCONFIRMED
    }

    fun release() = main.removeCallbacksAndMessages(null)

    private companion object {
        const val TAG = "PleosCabinLight"
        const val RETRY_MS = 1_000L
        const val MAX_TRIES = 10
        const val TIMEOUT_MS = 3_000L
    }
}
