package com.cihmine.napalarm.core

import kotlin.math.roundToInt

/**
 * 차량에서 읽은 공조·실내등 상태. 모르는 값은 null.
 * [fanLevel] 은 차량이 알려 준 바람 세기, [fanMax] 는 그 차량의 최대 단계다.
 */
data class CabinState(
    val climateOn: Boolean? = null,
    val acOn: Boolean? = null,
    val maxAcOn: Boolean? = null,
    val fanLevel: Int? = null,
    val fanMax: Int? = null,
    val cabinLightOn: Boolean? = null,
    /** 라디오(모드가 켠 값, 공개 SDK 에 없어 모의) */
    val radioOn: Boolean? = null,
)

/** 어림한 소비 전력과 그 구성(화면에 그대로 보여 준다). */
data class EstimatedLoad(val watts: Double, val parts: List<String>)

/**
 * 전기차의 공조·실내등 상태로 소비 전력을 어림한다. 차량 SDK 에 소비 전력 값이 없어서 쓰는 추정이다.
 * 숫자는 차종·외기 온도에 따라 크게 달라지는 초기 제안값이고 docs/DECISIONS.md 에 근거를 남긴다.
 * 공조 전원을 모르면 null 을 돌려 모의 소비 전력으로 돌아가게 한다(값을 지어내지 않음).
 */
object CabinLoad {
    /** 공조 외 기본 소비(전장품·화면·변환 손실) */
    const val BASE_W = 200.0
    /** 바람 세기 최대일 때 송풍기 */
    const val FAN_MAX_W = 300.0
    /** 바람 세기 단계를 모를 때 송풍기 */
    const val FAN_UNKNOWN_W = 150.0
    /** 에어컨(전동 압축기) */
    const val AC_W = 1_200.0
    /** 최대 냉방 */
    const val MAX_AC_W = 1_800.0
    /** 실내등(LED) */
    const val LIGHT_W = 10.0
    /** 라디오·오디오(보통 음량) — 모의 */
    const val RADIO_W = 20.0

    fun estimate(s: CabinState): EstimatedLoad? {
        val climateOn = s.climateOn ?: return null
        var watts = BASE_W
        val parts = mutableListOf("기본 ${w(BASE_W)}")
        if (climateOn) {
            val level = s.fanLevel
            val max = s.fanMax
            val fanW = if (level != null && max != null && max > 0) FAN_MAX_W * level.coerceIn(0, max) / max else FAN_UNKNOWN_W
            watts += fanW
            parts += if (level != null && max != null && max > 0) "송풍 $level/${max}단 ${w(fanW)}" else "송풍 ${w(fanW)}"
            when {
                s.maxAcOn == true -> { watts += MAX_AC_W; parts += "최대 냉방 ${w(MAX_AC_W)}" }
                s.acOn == true -> { watts += AC_W; parts += "에어컨 ${w(AC_W)}" }
            }
        } else {
            parts += "공조 꺼짐"
        }
        if (s.cabinLightOn == true) {
            watts += LIGHT_W
            parts += "실내등 ${w(LIGHT_W)}"
        }
        if (s.radioOn == true) {
            watts += RADIO_W
            parts += "라디오(모의) ${w(RADIO_W)}"
        }
        return EstimatedLoad(watts, parts)
    }

    private fun w(watts: Double): String = if (watts >= 1_000) "${(watts / 100).roundToInt() / 10.0}kW" else "${watts.roundToInt()}W"
}
