package com.cihmine.napalarm.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 시험용 시계. 단조 시계와 실제 시각을 따로 움직일 수 있다. */
class FakeClock(var mono: Long = 1_000_000, var wall: Long = 1_762_000_000_000, var boot: Int = 1) : Clock {
    override fun bootCount() = boot
    override fun monotonicMs() = mono
    override fun wallMs() = wall
    fun advance(ms: Long) { mono += ms; wall += ms }
}

/** docs/PLAN.md 10절 필수 검증 사례(N-T01~N-T10) 중 순수 로직으로 확인할 수 있는 것 */
class NapEngineTest {
    private val clock = FakeClock()

    private fun resting(seconds: Long = 30 * 60L, demo: Boolean = false) = NapEngine().also { assertNotNull(it.start(seconds, clock, demo)) }

    @Test
    fun `N-T01 20초 타이머 — 목표 시각에 알람 한 번, 다시 불러도 안 울림`() {
        val e = resting(20, demo = true)
        clock.advance(19_000)
        assertEquals(TickEvent.None, e.tick(clock))
        assertEquals(1_000, e.remainingMs(clock))
        clock.advance(1_000)
        assertEquals(TickEvent.Ring(e.session!!.id, 1), e.tick(clock))
        repeat(5) { clock.advance(200); assertEquals(TickEvent.None, e.tick(clock)) }
        assertEquals(NapStatus.RINGING, e.session!!.status)
    }

    @Test
    fun `남은 시간은 시작·목표 시각으로 계산한다(화면 갱신 횟수와 무관)`() {
        val e = resting(60)
        clock.advance(45_500)
        assertEquals(14_500, e.remainingMs(clock))
        assertEquals("0:15", Texts.remaining(e.remainingMs(clock)!!))
    }

    @Test
    fun `N-T02 5분 다시 알림 — 새 목표 시각 저장, 다음 알람은 2번`() {
        val e = resting(60)
        clock.advance(60_000)
        e.tick(clock)
        val s = assertNotNull(e.snooze(clock))
        assertEquals(NapStatus.SNOOZED, s.status)
        assertEquals(clock.wall + 5 * 60_000, s.targetWallMs)
        assertEquals(1, s.snoozeCount)
        assertNull(e.snooze(clock), "울리지 않을 때는 다시 알림 없음")
        clock.advance(5 * 60_000)
        assertEquals(TickEvent.Ring(s.id, 2), e.tick(clock))
    }

    @Test
    fun `N-T03 시간 전에 앱이 꺼졌다 켜지면 남은 시간을 복원한다(처음부터 다시 세지 않음)`() {
        val e = resting(30 * 60L)
        clock.advance(10 * 60_000)
        val restored = NapEngine(NapCodec.decode(NapCodec.encode(e.state)))
        clock.advance(2 * 60_000) // 꺼져 있던 2분
        assertEquals(RestoreOutcome.RESUMED, restored.onAppRestarted(clock))
        assertEquals(18 * 60_000L, restored.remainingMs(clock))
    }

    @Test
    fun `N-T04 시간이 지난 뒤 다시 켜면 알람이 울리지 않았다고 표시하고 그대로 기록한다`() {
        val e = resting(60)
        clock.advance(10 * 60_000)
        val restored = NapEngine(NapCodec.decode(NapCodec.encode(e.state)))
        assertEquals(RestoreOutcome.OVERDUE, restored.onAppRestarted(clock))
        assertEquals(TickEvent.None, restored.tick(clock), "지난 알람을 지금 정상 알람처럼 울리지 않는다")
        assertTrue(restored.wake(clock))
        assertEquals(EndReason.MISSED_WHILE_OFF, restored.finish()!!.endReason)
    }

    @Test
    fun `재부팅 뒤에는 실제 시각으로 남은 시간을 계산하고 일정 확인을 요청한다`() {
        val e = resting(30 * 60L)
        clock.advance(10 * 60_000)
        val saved = NapCodec.encode(e.state)
        clock.mono = 5_000 // 재부팅: 단조 시계가 다시 작은 값
        clock.wall += 60_000
        val restored = NapEngine(NapCodec.decode(saved))
        assertEquals(RestoreOutcome.RESUMED_NEEDS_RECONFIRM, restored.onAppRestarted(clock))
        assertEquals(19 * 60_000L, restored.remainingMs(clock))
        assertTrue(restored.session!!.needsReconfirm)
        clock.advance(19 * 60_000)
        assertIs<TickEvent.Ring>(restored.tick(clock))
    }

    @Test
    fun `같은 부팅에서 시스템 시각이 크게 바뀌면 단조 시계로 계산하되 확인을 요청한다`() {
        val e = resting(30 * 60L)
        clock.advance(5 * 60_000)
        clock.wall += 3 * 3_600_000 // 시각만 3시간 앞으로
        val restored = NapEngine(e.state)
        assertEquals(RestoreOutcome.RESUMED_NEEDS_RECONFIRM, restored.onAppRestarted(clock))
        assertEquals(25 * 60_000L, restored.remainingMs(clock))
    }

    @Test
    fun `N-T05 공조·시트 실패는 타이머와 별개이고 성공으로 기록되지 않는다`() {
        val e = resting()
        val id = e.session!!.id
        e.recordOriginal(VehicleSettings(24.0, SeatPreset.UPRIGHT), clock)
        e.recordAction(id, ControlTarget.CLIMATE, VehicleSettings(temperatureC = 21.0), ControlState.FAILED)
        e.recordAction(id, ControlTarget.SEAT, VehicleSettings(seat = SeatPreset.RECLINE), ControlState.ACKNOWLEDGED)
        assertEquals(NapStatus.RESTING, e.session!!.status)
        assertNull(e.state.appSet.temperatureC, "실패한 공조는 앱이 바꾼 값이 아님")
        assertEquals(SeatPreset.RECLINE, e.state.appSet.seat)
        val plan = e.restorePlan(VehicleSettings(24.0, SeatPreset.RECLINE)).associateBy { it.target }
        assertEquals(RestoreDecision.NOTHING_TO_DO, plan.getValue(ControlTarget.CLIMATE).decision)
        assertEquals(RestoreDecision.RESTORE, plan.getValue(ControlTarget.SEAT).decision)
    }

    @Test
    fun `N-T06 휴식 중 사용자가 직접 바꾼 설정은 덮어쓰지 않고 확인한다`() {
        val e = resting()
        val id = e.session!!.id
        e.recordOriginal(VehicleSettings(24.0, SeatPreset.UPRIGHT), clock)
        e.recordAction(id, ControlTarget.CLIMATE, VehicleSettings(temperatureC = 21.0), ControlState.ACKNOWLEDGED)
        val plan = e.restorePlan(VehicleSettings(23.0, SeatPreset.UPRIGHT)).associateBy { it.target }
        assertEquals(RestoreDecision.CONFIRM_MANUAL_CHANGE, plan.getValue(ControlTarget.CLIMATE).decision)
        assertEquals(RestoreDecision.NOTHING_TO_DO, plan.getValue(ControlTarget.SEAT).decision, "앱이 안 바꾼 항목")
    }

    @Test
    fun `원래 값을 읽지 못한 항목은 복구하지 않는다`() {
        val plan = RestorePlanner.plan(
            OriginalVehicleState("s", VehicleSettings(temperatureC = null, seat = SeatPreset.UPRIGHT), 0),
            VehicleSettings(21.0, SeatPreset.RECLINE), VehicleSettings(21.0, SeatPreset.RECLINE),
        ).associateBy { it.target }
        assertEquals(RestoreDecision.NOTHING_TO_DO, plan.getValue(ControlTarget.CLIMATE).decision)
        assertEquals(RestoreDecision.RESTORE, plan.getValue(ControlTarget.SEAT).decision)
    }

    @Test
    fun `N-T09 끝난 세션의 늦은 콜백 — 다시 울리거나 제어 기록이 붙지 않는다`() {
        val e = resting(60)
        val oldId = e.session!!.id
        clock.advance(60_000)
        e.tick(clock)
        e.wake(clock)
        e.finish()
        assertEquals(TickEvent.None, e.tick(clock))
        assertNull(e.recordAction(oldId, ControlTarget.SEAT, VehicleSettings(seat = SeatPreset.UPRIGHT), ControlState.ACKNOWLEDGED, isRestore = true))
        assertNull(e.snooze(clock))
        // 새 세션에도 이전 세션 응답이 붙지 않는다
        e.start(60, clock)
        assertNull(e.recordAction(oldId, ControlTarget.SEAT, VehicleSettings(seat = SeatPreset.UPRIGHT), ControlState.ACKNOWLEDGED))
    }

    @Test
    fun `중도 종료와 기록 — 수면 시간이 아니라 앱 세션 시간`() {
        val e = resting(30 * 60L)
        clock.advance(7 * 60_000)
        assertTrue(e.stopEarly(clock))
        assertEquals(NapStatus.RESTORE_PENDING, e.session!!.status)
        val r = assertNotNull(e.finish())
        assertEquals(EndReason.STOPPED_EARLY, r.endReason)
        assertEquals(7 * 60_000L, r.endedWallMs - r.startedWallMs)
        assertNull(e.session)
        assertEquals(1, e.state.history.size)
    }

    @Test
    fun `진행 중인 휴식이 있으면 새로 시작하지 않고, 시간 범위는 1~180분`() {
        val e = resting()
        assertNull(e.start(60, clock))
        assertFailsWith<IllegalArgumentException> { NapEngine().start(30, clock) }
        assertFailsWith<IllegalArgumentException> { NapEngine().start(181 * 60L, clock) }
        assertNotNull(NapEngine().start(20, clock, demoShortened = true))
    }

    @Test
    fun `N-T08 음성 시간 해석과 재확인 문구`() {
        assertEquals(Utterance.Duration(30), VoiceParser.parse("30분"))
        assertEquals(Utterance.Duration(30), VoiceParser.parse("삼십 분 뒤에 깨워 줘"))
        assertEquals(Utterance.Duration(3), VoiceParser.parse("3분"))
        assertEquals(Utterance.Duration(15), VoiceParser.parse("십오분"))
        assertEquals(Utterance.Duration(90), VoiceParser.parse("한 시간 반"))
        assertEquals(Utterance.Duration(90), VoiceParser.parse("1시간 30분 후"))
        assertEquals(Utterance.Duration(120), VoiceParser.parse("두 시간"))
        assertEquals(Utterance.Command(NapCommand.SNOOZE), VoiceParser.parse("5분 더"))
        assertEquals(Utterance.Command(NapCommand.WAKE), VoiceParser.parse("일어났어"))
        assertEquals(Utterance.Command(NapCommand.YES), VoiceParser.parse("네"))
        assertEquals(Utterance.Unknown, VoiceParser.parse("오늘 날씨 어때"))
        val zone = ZoneId.of("Asia/Seoul")
        val at = java.time.ZonedDateTime.of(2026, 11, 2, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("30분 뒤, 오후 2시 30분에 알려 드릴까요?", Texts.confirmDuration(30, at, zone))
        assertEquals("1시간 뒤, 오후 3시에 알려 드릴까요?", Texts.confirmDuration(60, at, zone))
    }
}
