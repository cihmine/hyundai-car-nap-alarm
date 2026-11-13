package com.cihmine.napalarm.core

import kotlinx.serialization.Serializable

/** 버튼 하나로 켜는 기본 휴식 모드 */
@Serializable
enum class RestMode(val label: String, val icon: String) {
    CAMPING("캠핑", "🏕"),
    MOVIE("영화관", "🎬"),
    NAP("낮잠", "😴"),
}

/** 모드별로 사용자가 저장해 두는 설정 */
@Serializable
data class ModeSetting(val lightsOff: Boolean, val radioOn: Boolean, val seat: SeatPreset)

@Serializable
data class ModeSettings(
    val camping: ModeSetting = ModeSetting(lightsOff = false, radioOn = false, seat = SeatPreset.FLAT),
    val movie: ModeSetting = ModeSetting(lightsOff = true, radioOn = true, seat = SeatPreset.RECLINE),
    val nap: ModeSetting = ModeSetting(lightsOff = true, radioOn = false, seat = SeatPreset.RECLINE),
) {
    fun of(mode: RestMode): ModeSetting = when (mode) {
        RestMode.CAMPING -> camping
        RestMode.MOVIE -> movie
        RestMode.NAP -> nap
    }

    fun with(mode: RestMode, setting: ModeSetting): ModeSettings = when (mode) {
        RestMode.CAMPING -> copy(camping = setting)
        RestMode.MOVIE -> copy(movie = setting)
        RestMode.NAP -> copy(nap = setting)
    }
}

/** 사용자가 하단바 + 로 추가한 모드. 낮잠처럼 타이머를 켜지는 않고 설정만 적용한다. */
@Serializable
data class CustomMode(val id: String, val name: String, val icon: String, val setting: ModeSetting)

/**
 * 켜져 있는 모드와, 끌 때 되돌릴 처음 값. 다른 모드로 바꿔도 처음 값은 그대로 둔다.
 * 기본 모드면 [mode], 추가한 모드면 [customId] 가 채워진다.
 */
@Serializable
data class ActiveMode(
    val mode: RestMode? = null,
    val originalSeat: SeatPreset,
    val originalLight: String,
    val radioOn: Boolean,
    val customId: String? = null,
) {
    val key: String get() = keyOf(mode, customId)
}

private fun keyOf(mode: RestMode?, customId: String?): String = customId?.let { "custom:$it" } ?: mode?.name.orEmpty()

/** 하단바 한 칸(기본 모드 또는 추가한 모드) */
data class ModeView(val key: String, val icon: String, val name: String, val setting: ModeSetting, val mode: RestMode?, val custom: CustomMode?)

/** 하단바 순서: 기본 모드 3개 다음에 추가한 순서대로 */
fun RestPreferences.modeViews(): List<ModeView> =
    RestMode.entries.map { ModeView(it.name, it.icon, it.label, modes.of(it), it, null) } +
        customModes.map { ModeView(keyOf(null, it.id), it.icon, it.name, it.setting, null, it) }

/** 켜진 모드의 표시 정보. 추가한 모드를 지웠으면 null */
fun RestPreferences.activeView(): ModeView? = activeMode?.let { a -> modeViews().firstOrNull { it.key == a.key } }

/** 모드 추가·삭제 규칙 */
object CustomModes {
    const val MAX = 5
    const val NAME_MAX = 8
    val ICONS = listOf("📖", "🔌", "🐶", "👶", "🎵", "🧘", "☕", "🎮")

    sealed interface AddResult {
        data class Added(val prefs: RestPreferences, val mode: CustomMode) : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    fun add(prefs: RestPreferences, name: String, icon: String, setting: ModeSetting): AddResult {
        val n = name.trim()
        if (n.isEmpty()) return AddResult.Rejected("모드 이름을 적어 주세요")
        if (n.length > NAME_MAX) return AddResult.Rejected("이름은 ${NAME_MAX}글자까지예요")
        if (prefs.customModes.size >= MAX) return AddResult.Rejected("모드는 ${MAX}개까지 추가할 수 있어요")
        if (prefs.modeViews().any { it.name == n }) return AddResult.Rejected("같은 이름의 모드가 있어요")
        val next = (prefs.customModes.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val mode = CustomMode(next.toString(), n, icon, setting)
        return AddResult.Added(prefs.copy(customModes = prefs.customModes + mode), mode)
    }

    /** 켜져 있는 모드는 지우지 않는다(끄고 원래대로 되돌린 뒤 지운다). */
    fun remove(prefs: RestPreferences, id: String): RestPreferences? {
        if (prefs.activeMode?.customId == id) return null
        return prefs.copy(customModes = prefs.customModes.filterNot { it.id == id })
    }

    fun update(prefs: RestPreferences, id: String, setting: ModeSetting): RestPreferences =
        prefs.copy(customModes = prefs.customModes.map { if (it.id == id) it.copy(setting = setting) else it })
}

/** 모드 버튼을 눌렀을 때 차량에 적용할 값 */
data class ModeChange(val next: ActiveMode?, val seat: SeatPreset, val light: String, val radioOn: Boolean)

object ModeRules {
    const val LIGHT_OFF = "OFF"

    fun press(current: ActiveMode?, pressed: RestMode, settings: ModeSettings, seatNow: SeatPreset, lightNow: String): ModeChange =
        press(current, pressed, null, settings.of(pressed), seatNow, lightNow)

    fun pressCustom(current: ActiveMode?, pressed: CustomMode, seatNow: SeatPreset, lightNow: String): ModeChange =
        press(current, null, pressed.id, pressed.setting, seatNow, lightNow)

    /**
     * 같은 모드를 다시 누르면 끄고 처음 값으로 되돌린다. 다른 모드를 누르면 그 모드의 설정으로 바꾼다.
     * 실내등을 끄지 않는 모드는 처음 값으로 둔다(앞 모드가 끈 등이 꺼진 채 남지 않게).
     */
    private fun press(current: ActiveMode?, mode: RestMode?, customId: String?, setting: ModeSetting, seatNow: SeatPreset, lightNow: String): ModeChange {
        if (current != null && current.key == keyOf(mode, customId)) return ModeChange(null, current.originalSeat, current.originalLight, radioOn = false)
        val seat = current?.originalSeat ?: seatNow
        val light = current?.originalLight ?: lightNow
        return ModeChange(
            next = ActiveMode(mode, seat, light, setting.radioOn, customId),
            seat = setting.seat,
            light = if (setting.lightsOff) LIGHT_OFF else light,
            radioOn = setting.radioOn,
        )
    }
}
