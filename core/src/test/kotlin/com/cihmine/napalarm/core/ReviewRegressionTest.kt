package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 2026-11-02 codex 점검 지적 재현 */
class ReviewRegressionTest {
    @Test
    fun `재부팅 뒤 가동 시간이 시작값보다 커도 재부팅으로 알아챈다`() {
        val clock = FakeClock(mono = 60_000)
        val e = NapEngine().also { it.start(10 * 60L, clock) }
        val saved = NapCodec.encode(e.state)
        clock.wall += 20 * 60_000
        clock.mono = 120_000
        clock.boot += 1
        val restored = NapEngine(NapCodec.decode(saved))
        assertEquals(RestoreOutcome.OVERDUE, restored.onAppRestarted(clock))
    }

    @Test
    fun `시각이 바뀐 뒤에는 표시하는 알람 시각도 실제로 울릴 시각으로 바꾼다`() {
        val clock = FakeClock()
        val e = NapEngine().also { it.start(30 * 60L, clock) }
        clock.advance(5 * 60_000)
        clock.wall += 3 * 3_600_000
        val restored = NapEngine(e.state)
        restored.onAppRestarted(clock)
        assertEquals(clock.wall + 25 * 60_000, restored.session!!.targetWallMs)
    }

    @Test
    fun `앱이 원래 값과 같은 값을 요청했으면 사용자가 바꿔도 복구 대상이 아니다`() {
        val plan = RestorePlanner.plan(
            OriginalVehicleState("s", VehicleSettings(24.0, null), 0), VehicleSettings(24.0, null), VehicleSettings(23.0, null),
        ).first { it.target == ControlTarget.CLIMATE }
        assertEquals(RestoreDecision.NOTHING_TO_DO, plan.decision)
    }

    @Test
    fun `이미 원래 값으로 돌아갔으면 직접 바꾼 값으로 보지 않는다`() {
        val plan = RestorePlanner.plan(
            OriginalVehicleState("s", VehicleSettings(24.0, null), 0), VehicleSettings(22.0, null), VehicleSettings(24.0, null),
        ).first { it.target == ControlTarget.CLIMATE }
        assertEquals(RestoreDecision.ALREADY_RESTORED, plan.decision)
    }

    @Test
    fun `소수점 시간은 다른 정수로 바꾸지 않고 거절한다`() {
        assertEquals(Utterance.Unknown, VoiceParser.parse("1.5분"))
        assertEquals(Utterance.Duration(30), VoiceParser.parse("30분."))
    }

    @Test
    fun `휴식 중 시스템 시각을 바꿔도 기록의 실제 시간은 경과 시간이다`() {
        val clock = FakeClock()
        val e = NapEngine().also { it.start(30 * 60L, clock) }
        clock.advance(5 * 60_000)
        clock.wall -= 3_600_000
        e.stopEarly(clock)
        val r = assertNotNull(e.finish())
        assertEquals(5 * 60_000L, r.actualMs)
    }

    @Test
    fun `요청 중 앱이 꺼져도 앱이 바꾸려던 값을 기억한다(요청 전 기록)`() {
        val clock = FakeClock()
        val e = NapEngine().also { it.start(30 * 60L, clock) }
        e.recordOriginal(VehicleSettings(24.0, SeatPreset.UPRIGHT), clock)
        val a = assertNotNull(e.requestAction(e.session!!.id, ControlTarget.CLIMATE, VehicleSettings(temperatureC = 22.0)))
        val restored = NapEngine(NapCodec.decode(NapCodec.encode(e.state)))
        assertEquals(22.0, restored.state.appSet.temperatureC)
        // 응답이 실패로 오면 앱이 바꾼 값에서 뺀다
        restored.completeAction(a.id, ControlState.FAILED)
        assertEquals(null, restored.state.appSet.temperatureC)
        assertTrue(restored.state.actions.single().state == ControlState.FAILED)
    }
}
