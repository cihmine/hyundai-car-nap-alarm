package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 충전 대기 모드: 타이머 없이 충전률을 감시하다 목표에 닿으면 알린다(N-T19) */
class ChargeWakeTest {
    private val clock = FakeClock()

    private fun ev(soc: Double, at: Long = clock.wall) =
        mapOf(PowerSource.EV_TRACTION to EnergyReading(PowerSource.EV_TRACTION, at, usableCapacityWh = 60_000.0, socPercent = soc, dischargeW = 200.0, charging = true))

    private fun waiting(target: Int = 80, profile: VehicleProfile = VehicleProfile.EV) = NapEngine().also {
        it.setPreferences(it.state.prefs.copy(profile = profile))
        it.setChargeTarget(target)
        it.syncChargeWait(true, clock)
    }

    @Test
    fun `N-T19 충전률이 목표 아래에서 목표 이상으로 오르면 타이머 없이 한 번 알린다`() {
        val e = waiting(80)
        assertNull(e.session) // 낮잠 타이머는 돌지 않는다
        assertFalse(e.chargeTick(ev(72.0), clock))
        assertFalse(e.chargeTick(ev(79.9), clock))
        assertTrue(e.chargeTick(ev(80.0), clock))
        val w = assertNotNull(e.state.chargeWait)
        assertTrue(w.ringing)
        assertEquals(80.0, w.reachedSoc)
        // 알람을 꺼도 같은 대기에서는 다시 울리지 않는다
        e.ackChargeAlarm()
        assertFalse(e.state.chargeWait!!.ringing)
        assertFalse(e.chargeTick(ev(85.0), clock))
    }

    @Test
    fun `이미 목표를 넘은 상태에서 켜면 바로 울리지 않는다`() {
        val e = waiting(80)
        assertFalse(e.chargeTick(ev(90.0), clock))
        assertFalse(e.chargeTick(ev(95.0), clock))
    }

    @Test
    fun `모드가 꺼졌거나 내연기관차거나 값이 오래되면 울리지 않는다`() {
        val off = NapEngine()
        assertFalse(off.chargeTick(ev(70.0), clock))
        assertFalse(off.chargeTick(ev(100.0), clock))
        assertNull(off.state.chargeWait)

        val ice = waiting(80, VehicleProfile.ICE)
        ice.chargeTick(ev(70.0), clock)
        assertFalse(ice.chargeTick(ev(90.0), clock))

        val stale = waiting(80)
        stale.chargeTick(ev(70.0), clock)
        clock.advance(RuntimeEstimator.STALE_MS + 1)
        assertFalse(stale.chargeTick(ev(90.0, at = clock.wall - RuntimeEstimator.STALE_MS - 1), clock))

        // 모드를 끄면 감시 상태를 비운다
        stale.syncChargeWait(false, clock)
        assertNull(stale.state.chargeWait)
    }

    @Test
    fun `대기 중 목표를 바꾸면 처음부터 다시 판단하고 범위를 벗어난 값은 자른다`() {
        val e = waiting(80)
        e.chargeTick(ev(75.0), clock)
        e.setChargeTarget(70) // 이미 넘은 목표 → 바로 울리지 않음
        assertFalse(e.chargeTick(ev(76.0), clock))
        e.setChargeTarget(90)
        e.chargeTick(ev(80.0), clock)
        assertTrue(e.chargeTick(ev(90.0), clock))

        val n = NapEngine()
        n.setChargeTarget(30)
        assertEquals(ChargeWake.MIN, n.state.prefs.chargeTargetSoc)
        n.setChargeTarget(120)
        assertEquals(ChargeWake.MAX, n.state.prefs.chargeTargetSoc)
    }

    @Test
    fun `낮잠 전력 보호와 섞이지 않고 예전 저장 파일도 읽힌다`() {
        val e = waiting(80)
        assertNotNull(e.start(30 * 60L, clock))
        // 낮잠 전력 감시에는 충전 알람이 끼지 않는다
        e.chargeTick(ev(70.0), clock)
        assertTrue(e.energyTick(ev(90.0), clock).events.isEmpty())
        assertTrue(e.chargeTick(ev(90.0), clock))
        val old = NapCodec.decode("""{"prefs":{}}""")
        assertNull(old.chargeWait)
        assertEquals(ChargeWake.DEFAULT, old.prefs.chargeTargetSoc)
    }
}
