package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VehicleKindTest {
    @Test
    fun `구동 배터리만 읽히면 전기차`() {
        assertEquals(VehicleKind.EV, VehicleKinds.detect(PowertrainSignals(evSupported = true, fuelSupported = false)))
        assertEquals(VehicleKind.EV, VehicleKinds.detect(PowertrainSignals(evRead = true, fuelSupported = false)))
    }

    @Test
    fun `연료량만 읽히면 내연기관차`() {
        assertEquals(VehicleKind.ICE, VehicleKinds.detect(PowertrainSignals(evSupported = false, fuelPercent = 60)))
        assertEquals(VehicleKind.ICE, VehicleKinds.detect(PowertrainSignals(evSupported = false, fuelSupported = true)))
    }

    @Test
    fun `둘 다 읽히면 하이브리드`() {
        assertEquals(VehicleKind.HYBRID, VehicleKinds.detect(PowertrainSignals(evRead = true, fuelPercent = 40)))
    }

    @Test
    fun `단서가 모자라면 판별하지 않는다`() {
        assertEquals(VehicleKind.UNKNOWN, VehicleKinds.detect(PowertrainSignals()))
        // 전기차 지원만 알고 연료는 아직 모름
        assertEquals(VehicleKind.UNKNOWN, VehicleKinds.detect(PowertrainSignals(evSupported = true)))
        assertEquals(VehicleKind.UNKNOWN, VehicleKinds.detect(PowertrainSignals(evSupported = false, fuelSupported = false)))
    }

    @Test
    fun `실제로 읽힌 값이 지원 정보보다 우선`() {
        // 지원 안 한다고 했지만 값이 읽혔다
        assertEquals(VehicleKind.HYBRID, VehicleKinds.detect(PowertrainSignals(evSupported = false, evRead = true, fuelPercent = 50)))
    }

    @Test
    fun `판별 결과로 전원 설정을 고른다`() {
        assertEquals(VehicleProfile.EV, VehicleKinds.profileFor(VehicleKind.EV, VehicleProfile.ICE))
        assertNull(VehicleKinds.profileFor(VehicleKind.EV, VehicleProfile.EV))
        assertEquals(VehicleProfile.ICE, VehicleKinds.profileFor(VehicleKind.ICE, VehicleProfile.EV))
        // 캠핑 배터리는 SDK 로 알 수 없으니 사용자가 고른 값 유지
        assertNull(VehicleKinds.profileFor(VehicleKind.ICE, VehicleProfile.ICE_CAMPING))
        assertNull(VehicleKinds.profileFor(VehicleKind.HYBRID, VehicleProfile.ICE))
        assertNull(VehicleKinds.profileFor(VehicleKind.UNKNOWN, VehicleProfile.EV))
    }
}
