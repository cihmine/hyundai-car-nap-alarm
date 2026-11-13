package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 차량 공조·실내등 상태로 소비 전력 어림(2026-11-07) */
class CabinLoadTest {
    @Test
    fun `공조 전원을 모르면 어림하지 않고 모의 값으로 돌아간다`() {
        assertNull(CabinLoad.estimate(CabinState()))
        assertNull(CabinLoad.estimate(CabinState(acOn = true, fanLevel = 8, fanMax = 8)))
    }

    @Test
    fun `공조가 꺼져 있으면 기본 소비만`() {
        val load = assertNotNull(CabinLoad.estimate(CabinState(climateOn = false, acOn = true, fanLevel = 3, fanMax = 8)))
        assertEquals(200.0, load.watts)
        assertEquals(listOf("기본 200W", "공조 꺼짐"), load.parts)
    }

    @Test
    fun `에어컨을 켜면 남은 시간이 줄 만큼 소비가 오른다`() {
        val fanOnly = assertNotNull(CabinLoad.estimate(CabinState(climateOn = true, acOn = false, fanLevel = 4, fanMax = 8)))
        val withAc = assertNotNull(CabinLoad.estimate(CabinState(climateOn = true, acOn = true, fanLevel = 4, fanMax = 8)))
        assertEquals(200.0 + 150.0, fanOnly.watts)
        assertEquals(200.0 + 150.0 + 1_200.0, withAc.watts)
        assertEquals(listOf("기본 200W", "송풍 4/8단 150W", "에어컨 1.2kW"), withAc.parts)
    }

    @Test
    fun `바람 세기 단계를 모르면 중간값, 최대 냉방은 에어컨 대신`() {
        val load = assertNotNull(CabinLoad.estimate(CabinState(climateOn = true, acOn = true, maxAcOn = true)))
        assertEquals(200.0 + CabinLoad.FAN_UNKNOWN_W + CabinLoad.MAX_AC_W, load.watts)
    }

    @Test
    fun `실내등을 켜면 더한다`() {
        val load = assertNotNull(CabinLoad.estimate(CabinState(climateOn = false, cabinLightOn = true)))
        assertEquals(210.0, load.watts)
    }

    @Test
    fun `라디오를 켜면 더하고 끄면 기본으로 돌아간다`() {
        val on = assertNotNull(CabinLoad.estimate(CabinState(climateOn = false, radioOn = true)))
        assertEquals(200.0 + CabinLoad.RADIO_W, on.watts)
        assertEquals(listOf("기본 200W", "공조 꺼짐", "라디오(모의) 20W"), on.parts)
        assertEquals(200.0, assertNotNull(CabinLoad.estimate(CabinState(climateOn = false, radioOn = false))).watts)
    }
}
