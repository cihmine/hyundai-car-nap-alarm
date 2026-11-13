package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 휴식 화면에서 시간 고치기(2026-11-07) */
class SetDurationTest {
    private val clock = FakeClock()

    private fun resting(seconds: Long = 30 * 60L, demo: Boolean = false) = NapEngine().also { assertNotNull(it.start(seconds, clock, demo)) }

    @Test
    fun `휴식 중 시간을 늘리고 줄이면 목표 시각이 시작 기준으로 바뀌고 저장된다`() {
        val e = resting()
        clock.advance(10 * 60_000L)
        assertNotNull(e.setDuration(60 * 60L, clock))
        assertEquals(50 * 60_000L, e.remainingMs(clock))
        assertEquals(60, e.state.prefs.lastMinutes)
        assertNotNull(e.setDuration(15 * 60L, clock))
        assertEquals(5 * 60_000L, e.remainingMs(clock))
        // 저장 왕복 뒤에도 같은 목표
        val restored = NapEngine(NapCodec.decode(NapCodec.encode(e.state)))
        assertEquals(5 * 60_000L, restored.remainingMs(clock))
    }

    @Test
    fun `이미 지난 시각이나 1분 미만으로는 바꾸지 않는다`() {
        val e = resting()
        clock.advance(20 * 60_000L)
        assertNull(e.setDuration(15 * 60L, clock))
        assertNull(e.setDuration(20 * 60L + 30, clock))
        assertEquals(10 * 60_000L, e.remainingMs(clock))
    }

    @Test
    fun `시연 단축·다시 알림 대기·범위 밖은 바꾸지 않는다`() {
        assertNull(resting(20, demo = true).setDuration(30 * 60L, clock))
        assertNull(resting().setDuration(181 * 60L, clock))
        val e = resting(60)
        clock.advance(60_000)
        e.tick(clock)
        assertNotNull(e.snooze(clock))
        assertNull(e.setDuration(30 * 60L, clock))
    }
}
