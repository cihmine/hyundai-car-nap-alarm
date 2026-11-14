package com.cihmine.napalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestModesTest {
    private val settings = ModeSettings()

    private fun press(current: ActiveMode?, mode: RestMode) =
        ModeRules.press(current, mode, settings, seatNow = SeatPreset.UPRIGHT, lightNow = "AUTOMATIC")

    @Test
    fun `영화관 모드는 실내등을 끄고 라디오를 켠다`() {
        val c = press(null, RestMode.MOVIE)
        assertEquals(ModeRules.LIGHT_OFF, c.light)
        assertTrue(c.radioOn)
        assertEquals(RestMode.MOVIE, c.next?.mode)
    }

    @Test
    fun `캠핑 모드는 시트를 차박 자세로 바꾸고 실내등은 그대로 둔다`() {
        val c = press(null, RestMode.CAMPING)
        assertEquals(SeatPreset.FLAT, c.seat)
        assertEquals("AUTOMATIC", c.light)
        assertFalse(c.radioOn)
    }

    @Test
    fun `같은 모드를 다시 누르면 끄고 처음 값으로 되돌린다`() {
        val on = press(null, RestMode.MOVIE).next
        val off = ModeRules.press(on, RestMode.MOVIE, settings, seatNow = SeatPreset.RECLINE, lightNow = ModeRules.LIGHT_OFF)
        assertNull(off.next)
        assertEquals(SeatPreset.UPRIGHT, off.seat)
        assertEquals("AUTOMATIC", off.light)
        assertFalse(off.radioOn)
    }

    @Test
    fun `다른 모드로 바꿔도 처음 값을 잃지 않고 앞 모드가 끈 등을 되돌린다`() {
        val movie = press(null, RestMode.MOVIE).next
        val camping = ModeRules.press(movie, RestMode.CAMPING, settings, seatNow = SeatPreset.RECLINE, lightNow = ModeRules.LIGHT_OFF)
        assertEquals("AUTOMATIC", camping.light)
        assertEquals(SeatPreset.UPRIGHT, camping.next?.originalSeat)
        assertEquals("AUTOMATIC", camping.next?.originalLight)
    }

    @Test
    fun `저장한 모드 설정이 그대로 적용되고 저장 왕복 뒤에도 남는다`() {
        val mine = settings.with(RestMode.CAMPING, ModeSetting(lightsOff = true, radioOn = true, seat = SeatPreset.RECLINE))
        val c = ModeRules.press(null, RestMode.CAMPING, mine, SeatPreset.UPRIGHT, "ON")
        assertEquals(ModeRules.LIGHT_OFF, c.light)
        assertTrue(c.radioOn)
        assertEquals(SeatPreset.RECLINE, c.seat)
        val state = NapState(prefs = RestPreferences(modes = mine, activeMode = c.next))
        val back = NapCodec.decode(NapCodec.encode(state))
        assertEquals(mine, back.prefs.modes)
        assertEquals(c.next, back.prefs.activeMode)
    }

    @Test
    fun `모드 설정이 없던 예전 저장 파일도 기본값으로 읽는다`() {
        val old = NapCodec.encode(NapState()).replace(Regex(""","modes":\{.*?\}\}"""), "")
        assertFalse("\"modes\"" in old)
        assertEquals(ModeSettings(), NapCodec.decode(old).prefs.modes)
    }

    // ── 하단바 + 로 추가한 모드(2026-11-11) ──
    private val reading = ModeSetting(lightsOff = false, radioOn = false, seat = SeatPreset.UPRIGHT)

    private fun added(prefs: RestPreferences, name: String, setting: ModeSetting = reading): Pair<RestPreferences, CustomMode> {
        val r = CustomModes.add(prefs, name, "📖", setting)
        assertTrue(r is CustomModes.AddResult.Added, r.toString())
        return r.prefs to r.mode
    }

    @Test
    fun `모드를 추가하면 하단바 끝에 붙고 저장 왕복 뒤에도 남는다`() {
        val (prefs, mode) = added(RestPreferences(), "  독서 ")
        assertEquals("독서", mode.name)
        assertEquals(listOf("캠핑", "영화관", "낮잠", "충전 대기", "독서"), prefs.modeViews().map { it.name })
        // 충전 대기는 전기차에서만 보인다
        assertEquals(listOf("캠핑", "영화관", "낮잠", "독서"), prefs.copy(profile = VehicleProfile.ICE).modeViews().map { it.name })
        val back = NapCodec.decode(NapCodec.encode(NapState(prefs = prefs)))
        assertEquals(prefs.customModes, back.prefs.customModes)
    }

    @Test
    fun `빈 이름·긴 이름·같은 이름·개수 초과는 거절한다`() {
        assertTrue(CustomModes.add(RestPreferences(), " ", "📖", reading) is CustomModes.AddResult.Rejected)
        assertTrue(CustomModes.add(RestPreferences(), "아주아주긴모드이름", "📖", reading) is CustomModes.AddResult.Rejected)
        assertTrue(CustomModes.add(RestPreferences(), "영화관", "📖", reading) is CustomModes.AddResult.Rejected)
        var prefs = RestPreferences()
        repeat(CustomModes.MAX) { prefs = added(prefs, "모드$it").first }
        assertTrue(CustomModes.add(prefs, "하나더", "📖", reading) is CustomModes.AddResult.Rejected)
    }

    @Test
    fun `추가한 모드도 누르면 켜지고 다시 누르면 처음 값으로 돌아간다`() {
        val (prefs, mode) = added(RestPreferences(), "독서", ModeSetting(lightsOff = false, radioOn = true, seat = SeatPreset.UPRIGHT))
        val on = ModeRules.pressCustom(null, mode, SeatPreset.RECLINE, "AUTOMATIC")
        assertEquals(mode.id, on.next?.customId)
        assertNull(on.next?.mode)
        assertTrue(on.radioOn)
        assertEquals("독서", prefs.copy(activeMode = on.next).activeView()?.name)
        val off = ModeRules.pressCustom(on.next, mode, SeatPreset.UPRIGHT, "AUTOMATIC")
        assertNull(off.next)
        assertEquals(SeatPreset.RECLINE, off.seat)
    }

    @Test
    fun `영화관에서 추가한 모드로 바꾸면 처음 값을 잃지 않는다`() {
        val (_, mode) = added(RestPreferences(), "독서")
        val movie = press(null, RestMode.MOVIE).next
        val c = ModeRules.pressCustom(movie, mode, SeatPreset.RECLINE, ModeRules.LIGHT_OFF)
        assertEquals("AUTOMATIC", c.light)
        assertEquals(SeatPreset.UPRIGHT, c.next?.originalSeat)
    }

    @Test
    fun `켜져 있는 모드는 지우지 않고 꺼진 모드는 지운다`() {
        val (prefs, mode) = added(RestPreferences(), "독서")
        val on = prefs.copy(activeMode = ModeRules.pressCustom(null, mode, SeatPreset.UPRIGHT, "ON").next)
        assertNull(CustomModes.remove(on, mode.id))
        assertEquals(emptyList(), CustomModes.remove(prefs, mode.id)?.customModes)
    }
}
