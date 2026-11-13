package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/PLAN.md 10절 전력 관리 검증 사례 N-T11~N-T18 */
class EnergyTest {
    private val clock = FakeClock()
    private val reserve = ReserveSettings()

    /** 계획서 12.3 데모: 실제 사용 가능 60kWh, SOC 60%, 보존 30% */
    private fun ev(loadW: Double, soc: Double = 60.0, at: Long = clock.wall) =
        EnergyReading(PowerSource.EV_TRACTION, at, usableCapacityWh = 60_000.0, socPercent = soc, dischargeW = loadW)

    @Test
    fun `실제 전기차 값은 범위 안일 때만 쓰고 잔량을 지어내지 않는다`() {
        assertEquals(RealEvBattery(100.0, 77_400.0), RealEvBattery.of(100, 77_400f))
        assertEquals(RealEvBattery(55.0, null), RealEvBattery.of(55, null))
        assertEquals(RealEvBattery(55.0, null), RealEvBattery.of(55, 0f))
        assertEquals(null, RealEvBattery.of(null, 77_400f))
        assertEquals(null, RealEvBattery.of(101, 77_400f))
        assertEquals(null, RealEvBattery.of(-1, 77_400f))
    }

    @Test
    fun `실제 잔량과 용량으로 남은 시간을 계산한다`() {
        val real = assertNotNull(RealEvBattery.of(100, 77_400f))
        val reading = EnergyReading(
            PowerSource.EV_TRACTION, clock.wall, usableCapacityWh = real.capacityWh, socPercent = real.socPercent, dischargeW = 1_500.0,
        )
        val estimate = assertIs<RuntimeEstimate.Available>(RuntimeEstimator.estimate(reading, PowerSource.EV_TRACTION, reserve, clock.wall))
        assertEquals(36.12 * 60, estimate.minutes, 0.01)
    }

    @Test
    fun `남길 배터리를 km 로 정하면 지금 1퍼센트당 거리로 바꿔 계산하고 거리를 모르면 계산 불가`() {
        val km = reserve.copy(evUnit = ReserveUnit.KM, evReserveKm = 100.0)
        // 60% 에 300km → 1%당 5km → 100km = 20%
        assertEquals(20.0, assertNotNull(km.evSocFor(60.0, 300.0)), 0.001)
        val reading = EnergyReading(PowerSource.EV_TRACTION, clock.wall, usableCapacityWh = 60_000.0, socPercent = 60.0, rangeKm = 300.0, dischargeW = 1_500.0)
        val estimate = assertIs<RuntimeEstimate.Available>(RuntimeEstimator.estimate(reading, PowerSource.EV_TRACTION, km, clock.wall))
        assertEquals(12_000.0, estimate.reserveWh, 0.01)
        assertEquals("100km", km.evLabel)
        // 주행 가능 거리 없음·충전률 0 → 지어내지 않는다
        assertEquals(null, km.evSocFor(60.0, null))
        assertEquals(null, km.evSocFor(0.0, 300.0))
        val unknown = assertIs<RuntimeEstimate.Unknown>(RuntimeEstimator.estimate(reading.copy(rangeKm = null), PowerSource.EV_TRACTION, km, clock.wall))
        assertEquals(UnknownReason.NO_RESERVE_BASIS, unknown.reason)
        // 남길 거리가 지금 거리보다 많으면 100% 에서 자른다
        assertEquals(100.0, assertNotNull(km.copy(evReserveKm = 600.0).evSocFor(60.0, 300.0)), 0.001)
        // % 단위는 그대로
        assertEquals(30.0, reserve.evSocFor(60.0, null))
    }

    private fun camping(remainingWh: Double, loadW: Double = 100.0) =
        EnergyReading(PowerSource.CAMPING, clock.wall, remainingWh = remainingWh, dischargeW = loadW)

    private fun starter(warning: Boolean = false) =
        EnergyReading(PowerSource.STARTER_12V, clock.wall, voltage = 12.1, vehicleWarning = warning)

    private fun engineWith(profile: VehicleProfile, minutes: Int = 60): NapEngine = NapEngine().also {
        it.setPreferences(it.state.prefs.copy(profile = profile))
        assertNotNull(it.start(minutes * 60L, clock))
    }

    @Test
    fun `N-T11 전기차 소비 1_5kW에서 3kW로 늘면 남은 시간이 12시간에서 6시간으로 준다`() {
        val a = assertIs<RuntimeEstimate.Available>(RuntimeEstimator.estimate(ev(1500.0), PowerSource.EV_TRACTION, reserve, clock.wall))
        assertEquals(720.0, a.minutes, 0.01)
        assertEquals("(36.0kWh − 18.0kWh) ÷ 1.5kW = 12시간", a.formula)
        assertEquals(PowerSource.EV_TRACTION, a.source)
        assertTrue(a.simulated)
        val b = assertIs<RuntimeEstimate.Available>(RuntimeEstimator.estimate(ev(3000.0), PowerSource.EV_TRACTION, reserve, clock.wall))
        assertEquals(360.0, b.minutes, 0.01)
    }

    @Test
    fun `N-T12 12V 전압만 받으면 남은 시간을 만들지 않는다`() {
        val e = RuntimeEstimator.estimate(starter(), PowerSource.STARTER_12V, reserve, clock.wall)
        assertEquals(UnknownReason.STARTER_NOT_ESTIMATED, assertIs<RuntimeEstimate.Unknown>(e).reason)
        // 내연기관차(12V 만)로 휴식해도 알람은 그대로, 보호 상태는 "계산 불가 · 알람만"
        val engine = engineWith(VehicleProfile.ICE)
        val t = engine.energyTick(mapOf(PowerSource.STARTER_12V to starter()), clock)
        assertIs<RuntimeEstimate.Unknown>(t.estimate)
        assertEquals(GuardStatus.UNAVAILABLE, engine.state.guard!!.status)
        assertTrue(t.events.isEmpty())
        clock.advance(60 * 60_000L)
        assertIs<TickEvent.Ring>(engine.tick(clock))
    }

    @Test
    fun `N-T12 차량이 12V 경고 신호를 보내면 한 번만 알람으로 알린다`() {
        val engine = engineWith(VehicleProfile.ICE)
        val t = engine.energyTick(mapOf(PowerSource.STARTER_12V to starter(warning = true)), clock)
        assertTrue(t.events.contains(GuardEvent.StarterWarning))
        assertIs<TickEvent.Ring>(t.ring)
        assertEquals(AlarmReason.STARTER_WARNING, engine.session!!.alarmReason)
        assertTrue(engine.energyTick(mapOf(PowerSource.STARTER_12V to starter(warning = true)), clock).events.isEmpty())
    }

    @Test
    fun `N-T13 내연기관차 + 캠핑 배터리는 캠핑 배터리에 연결된 장치만 끈다`() {
        assertEquals(listOf(Load.CAMPING_LIGHT), VehicleProfile.ICE_CAMPING.shedLoads)
        assertTrue(VehicleProfile.ICE_CAMPING.shedLoads.all { it.source == PowerSource.CAMPING })
        val engine = engineWith(VehicleProfile.ICE_CAMPING)
        val a = assertIs<RuntimeEstimate.Available>(engine.energyTick(mapOf(PowerSource.CAMPING to camping(800.0)), clock).estimate)
        assertEquals(360.0, a.minutes, 0.01) // (800 − 200) ÷ 100W = 6시간
        val t = engine.energyTick(mapOf(PowerSource.CAMPING to camping(200.0)), clock)
        assertEquals(listOf<GuardEvent>(GuardEvent.RequestShed(listOf(Load.CAMPING_LIGHT))), t.events)
    }

    @Test
    fun `N-T14 보호 기준이 먼저 오면 기상 전에 깨우고, 5분 더로 한도를 늘리지 않는다`() {
        val engine = engineWith(VehicleProfile.EV, minutes = 60)
        // 보존량까지 0.2kWh, 3kW → 4분 남음: 10분 전 기준 안쪽 → 먼저 깨운다
        val t1 = engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 30.0 + 200.0 / 600)), clock)
        assertTrue(t1.events.any { it is GuardEvent.WarnEarly })
        assertIs<TickEvent.Ring>(t1.ring)
        assertEquals(AlarmReason.ENERGY_WARNING, engine.session!!.alarmReason)
        val wakeTarget = engine.session!!.targetMonoMs
        // 5분 더: 소리만 미룬다(원래 기상 시각보다 늦어지지 않음)
        val snoozed = assertNotNull(engine.snooze(clock))
        assertTrue(snoozed.targetMonoMs <= wakeTarget)
        assertEquals(GuardStatus.WARNING, engine.state.guard!!.status)
        // 미룬 사이 보존량 도달 → 장치 종료 요청 + 다시 알람
        clock.advance(4 * 60_000L)
        val t2 = engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 30.0)), clock)
        assertEquals(listOf<GuardEvent>(GuardEvent.RequestShed(listOf(Load.EV_CLIMATE))), t2.events)
        assertIs<TickEvent.Ring>(t2.ring)
        assertEquals(AlarmReason.ENERGY_SHED, engine.session!!.alarmReason)
        assertEquals(GuardStatus.SHED_REQUESTED, engine.state.guard!!.status)
    }

    @Test
    fun `먼저 깨우기 알람이 울리는 중에 보존량에 닿으면 다시 울리지 않고 제목만 바뀐다`() {
        val engine = engineWith(VehicleProfile.EV)
        assertIs<TickEvent.Ring>(engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 30.3)), clock).ring)
        val t = engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 30.0)), clock)
        assertEquals(TickEvent.None, t.ring)
        assertTrue(t.events.single() is GuardEvent.RequestShed)
        assertEquals(AlarmReason.ENERGY_SHED, engine.session!!.alarmReason)
        assertEquals(1, engine.session!!.alarmSequence)
    }

    @Test
    fun `N-T14 기상 시각 알람으로 5분 더는 평소처럼 5분 뒤`() {
        val engine = engineWith(VehicleProfile.EV, minutes = 1)
        clock.advance(60_000)
        assertIs<TickEvent.Ring>(engine.tick(clock))
        val s = assertNotNull(engine.snooze(clock))
        assertEquals(clock.mono + 5 * 60_000L, s.targetMonoMs)
    }

    @Test
    fun `보호 시점이 기상보다 빠르면 시작하자마자 안내한다(알람은 아님)`() {
        val engine = engineWith(VehicleProfile.ICE_CAMPING, minutes = 180)
        // (800-200)/300W = 2시간 < 기상 3시간
        val t = engine.energyTick(mapOf(PowerSource.CAMPING to camping(800.0, loadW = 300.0)), clock)
        assertTrue(t.events.single() is GuardEvent.ProtectionBeforeWake)
        assertEquals(TickEvent.None, t.ring)
        assertTrue(engine.energyTick(mapOf(PowerSource.CAMPING to camping(800.0, loadW = 300.0)), clock).events.isEmpty())
    }

    @Test
    fun `N-T15 오래된 값·충전 중·소비 0은 무한 시간이나 0으로 나누기 없이 상태로 표시`() {
        val old = ev(1500.0, at = clock.wall - RuntimeEstimator.STALE_MS - 1)
        assertEquals(UnknownReason.STALE, assertIs<RuntimeEstimate.Unknown>(RuntimeEstimator.estimate(old, PowerSource.EV_TRACTION, reserve, clock.wall)).reason)
        assertIs<RuntimeEstimate.Charging>(RuntimeEstimator.estimate(ev(1500.0).copy(charging = true), PowerSource.EV_TRACTION, reserve, clock.wall))
        assertIs<RuntimeEstimate.NoDrain>(RuntimeEstimator.estimate(ev(0.0), PowerSource.EV_TRACTION, reserve, clock.wall))
        assertIs<RuntimeEstimate.NoDrain>(RuntimeEstimator.estimate(ev(-500.0), PowerSource.EV_TRACTION, reserve, clock.wall))
        assertEquals(UnknownReason.MISSING, assertIs<RuntimeEstimate.Unknown>(RuntimeEstimator.estimate(null, PowerSource.EV_TRACTION, reserve, clock.wall)).reason)
        // 용량을 모르면 퍼센트 보존량을 Wh 로 바꿀 수 없다
        val noCap = EnergyReading(PowerSource.EV_TRACTION, clock.wall, remainingWh = 30_000.0, dischargeW = 1500.0)
        assertEquals(UnknownReason.NO_RESERVE_BASIS, assertIs<RuntimeEstimate.Unknown>(RuntimeEstimator.estimate(noCap, PowerSource.EV_TRACTION, reserve, clock.wall)).reason)
    }

    @Test
    fun `N-T15 감시 중 데이터가 끊기면 한 번 알리고 장치를 껐다고 처리하지 않는다`() {
        val engine = engineWith(VehicleProfile.EV)
        engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(1500.0)), clock)
        assertEquals(GuardStatus.MONITORING, engine.state.guard!!.status)
        val t = engine.energyTick(emptyMap(), clock)
        assertEquals(listOf<GuardEvent>(GuardEvent.DataLost(UnknownReason.MISSING)), t.events)
        assertEquals(GuardStatus.UNAVAILABLE, engine.state.guard!!.status)
        assertTrue(engine.energyTick(emptyMap(), clock).events.isEmpty())
        // 다시 들어오면 감시 재개
        engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(1500.0)), clock)
        assertEquals(GuardStatus.MONITORING, engine.state.guard!!.status)
    }

    @Test
    fun `N-T16 장치 종료 응답 없음은 종료됨이 아니라 실패`() {
        val engine = engineWith(VehicleProfile.EV)
        engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 30.0)), clock)
        assertEquals(GuardStatus.SHED_FAILED, engine.onShedResult(ControlState.TIMED_OUT, clock))
        assertEquals("장치 종료 실패 — 직접 꺼 주세요", GuardStatus.SHED_FAILED.simpleLabel)
        // 실패 뒤에도 다시 "감시 중"으로 돌아가거나 종료를 다시 요청하지 않는다
        assertTrue(engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 29.0)), clock).events.isEmpty())
        assertEquals(GuardStatus.SHED_FAILED, engine.state.guard!!.status)
    }

    @Test
    fun `N-T17 보호 종료 뒤 복구·재실행해도 공조를 자동으로 다시 켜지 않는다`() {
        val engine = engineWith(VehicleProfile.EV)
        engine.recordOriginal(VehicleSettings(24.0, SeatPreset.UPRIGHT), clock)
        engine.recordAction(engine.session!!.id, ControlTarget.CLIMATE, VehicleSettings(temperatureC = 22.0), ControlState.ACKNOWLEDGED)
        engine.recordAction(engine.session!!.id, ControlTarget.SEAT, VehicleSettings(seat = SeatPreset.RECLINE), ControlState.ACKNOWLEDGED)
        engine.energyTick(mapOf(PowerSource.EV_TRACTION to ev(3000.0, soc = 30.0)), clock)
        engine.onShedResult(ControlState.ACKNOWLEDGED, clock)
        assertTrue(engine.wake(clock))
        // 앱 재시작(저장 → 복원)
        val restarted = NapEngine(NapCodec.decode(NapCodec.encode(engine.state)))
        restarted.onAppRestarted(clock)
        assertEquals(GuardStatus.SHED_CONFIRMED, restarted.state.guard!!.status)
        val plan = restarted.restorePlan(VehicleSettings(22.0, SeatPreset.RECLINE))
        assertEquals(RestoreDecision.BLOCKED_BY_PROTECTION, plan.first { it.target == ControlTarget.CLIMATE }.decision)
        // 시트는 평소대로 복구 후보
        assertEquals(RestoreDecision.RESTORE, plan.first { it.target == ControlTarget.SEAT }.decision)
        val record = assertNotNull(restarted.finish())
        assertEquals(GuardStatus.SHED_CONFIRMED, record.guardOutcome)
        assertEquals(VehicleProfile.EV, record.profile)
    }

    @Test
    fun `N-T18 12V와 캠핑 배터리는 합치거나 서로 대신하지 않는다`() {
        val engine = engineWith(VehicleProfile.ICE_CAMPING)
        // 12V 만 들어오고 캠핑 배터리 값이 없으면, 12V 로 대신 계산하지 않는다
        val t1 = engine.energyTick(mapOf(PowerSource.STARTER_12V to starter()), clock)
        assertEquals(UnknownReason.MISSING, assertIs<RuntimeEstimate.Unknown>(t1.estimate).reason)
        // 둘 다 있으면 캠핑 배터리만으로 계산(12V 에너지를 더하지 않음)
        val a = assertIs<RuntimeEstimate.Available>(
            engine.energyTick(mapOf(PowerSource.STARTER_12V to starter(), PowerSource.CAMPING to camping(800.0)), clock).estimate,
        )
        assertEquals(PowerSource.CAMPING, a.source)
        assertEquals(800.0, a.usableWh)
        assertEquals(200.0, a.reserveWh) // 캠핑 보존량(Wh), 전기차 SOC 보존량과 별개
        // 다른 전원의 측정값을 넣어도 받지 않는다
        assertIs<RuntimeEstimate.Unknown>(RuntimeEstimator.estimate(starter(), PowerSource.CAMPING, reserve, clock.wall))
        assertFalse(Load.entries.any { it.source == PowerSource.STARTER_12V })
    }

    @Test
    fun `남은 시간 글자`() {
        assertEquals("12시간", Texts.durationMinutes(720.0))
        assertEquals("6시간 30분", Texts.durationMinutes(390.4))
        assertEquals("4분", Texts.durationMinutes(4.0))
        assertEquals("1분 미만", Texts.durationMinutes(0.3))
    }
}
