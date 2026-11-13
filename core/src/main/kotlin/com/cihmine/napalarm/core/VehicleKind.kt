package com.cihmine.napalarm.core

/**
 * 차량 SDK 에서 받은 차종 판별 단서. null 은 아직 모름(또는 확인 실패).
 * [evSupported]·[fuelSupported] 는 SDK 지원 정보, [evRead]·[fuelPercent] 는 실제로 읽힌 값이다.
 */
data class PowertrainSignals(
    val evSupported: Boolean? = null,
    val fuelSupported: Boolean? = null,
    val evRead: Boolean = false,
    val fuelPercent: Int? = null,
)

enum class VehicleKind(val label: String) {
    EV("전기차"),
    ICE("내연기관차"),
    HYBRID("하이브리드"),
    UNKNOWN("판별 못 함"),
}

/**
 * 차종 자동 판별. SDK 에 '연료 종류' 값이 없어서 구동 배터리 충전률과 연료량을 읽을 수 있는지로 추정한다.
 * 실제로 값이 읽혔으면 지원 정보보다 그 사실을 우선한다. 단서가 모자라면 UNKNOWN 으로 두고 지금 설정을 바꾸지 않는다.
 */
object VehicleKinds {
    fun detect(s: PowertrainSignals): VehicleKind {
        val ev = s.evRead || s.evSupported == true
        val fuel = s.fuelPercent != null || s.fuelSupported == true
        val evNo = !s.evRead && s.evSupported == false
        val fuelNo = s.fuelPercent == null && s.fuelSupported == false
        return when {
            ev && fuel -> VehicleKind.HYBRID
            ev && fuelNo -> VehicleKind.EV
            fuel && evNo -> VehicleKind.ICE
            else -> VehicleKind.UNKNOWN
        }
    }

    /**
     * 판별 결과에 맞는 전원 설정. 바꿀 필요가 없거나 판단할 수 없으면 null.
     * 내연기관차는 캠핑 배터리를 따로 달았는지 SDK 로 알 수 없으니, 사용자가 '캠핑 배터리'를 골라 두었으면 그대로 둔다.
     * 하이브리드는 시동을 끈 채 구동 배터리로 공조를 오래 쓸 수 있는지 차마다 달라 자동으로 바꾸지 않는다.
     */
    fun profileFor(kind: VehicleKind, current: VehicleProfile): VehicleProfile? = when (kind) {
        VehicleKind.EV -> VehicleProfile.EV.takeIf { current != it }
        VehicleKind.ICE -> if (current == VehicleProfile.EV) VehicleProfile.ICE else null
        VehicleKind.HYBRID, VehicleKind.UNKNOWN -> null
    }
}
