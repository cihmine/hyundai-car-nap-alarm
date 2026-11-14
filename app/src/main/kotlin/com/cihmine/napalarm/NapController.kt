package com.cihmine.napalarm

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cihmine.napalarm.core.AlarmReason
import com.cihmine.napalarm.core.ChargeWake
import com.cihmine.napalarm.core.Clock
import com.cihmine.napalarm.core.GuardEvent
import com.cihmine.napalarm.core.GuardStatus
import com.cihmine.napalarm.core.Load
import com.cihmine.napalarm.core.CabinLoad
import com.cihmine.napalarm.core.CabinState
import com.cihmine.napalarm.core.EstimatedLoad
import com.cihmine.napalarm.core.PowerSource
import com.cihmine.napalarm.core.RealEvBattery
import com.cihmine.napalarm.core.ReserveSettings
import com.cihmine.napalarm.core.RuntimeEstimate
import com.cihmine.napalarm.core.VehicleProfile
import com.cihmine.napalarm.vehicle.MockEnergy
import com.cihmine.napalarm.vehicle.MockEnergyState
import com.cihmine.napalarm.core.ControlState
import com.cihmine.napalarm.core.ControlTarget
import com.cihmine.napalarm.core.NapCodec
import com.cihmine.napalarm.core.NapEngine
import com.cihmine.napalarm.core.NapJson
import com.cihmine.napalarm.core.NapState
import com.cihmine.napalarm.core.NapStatus
import com.cihmine.napalarm.core.RestoreDecision
import com.cihmine.napalarm.core.RestoreItem
import com.cihmine.napalarm.core.RestoreOutcome
import com.cihmine.napalarm.core.SeatPreset
import com.cihmine.napalarm.core.TickEvent
import com.cihmine.napalarm.core.Texts
import com.cihmine.napalarm.core.VehicleSettings
import com.cihmine.napalarm.data.JsonFileStore
import com.cihmine.napalarm.speech.AlarmSound
import com.cihmine.napalarm.vehicle.MockCar
import com.cihmine.napalarm.vehicle.MockCarState
import com.cihmine.napalarm.core.ActiveMode
import com.cihmine.napalarm.core.CustomModes
import com.cihmine.napalarm.core.activeView
import com.cihmine.napalarm.core.modeViews
import com.cihmine.napalarm.core.ModeChange
import com.cihmine.napalarm.core.ModeRules
import com.cihmine.napalarm.core.ModeSetting
import com.cihmine.napalarm.core.RestMode
import com.cihmine.napalarm.vehicle.LightOutcome
import com.cihmine.napalarm.vehicle.PleosCabinLight
import com.cihmine.napalarm.vehicle.PleosCabinState
import com.cihmine.napalarm.vehicle.PleosEvBattery
import com.cihmine.napalarm.vehicle.PleosGear
import com.cihmine.napalarm.vehicle.PleosPowertrain
import com.cihmine.napalarm.core.PowertrainSignals
import com.cihmine.napalarm.core.VehicleKind
import com.cihmine.napalarm.core.VehicleKinds
import com.cihmine.napalarm.vehicle.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** 실제 시계: 경과는 부팅 후 단조 시계, 비교용 실제 시각, 같은 부팅 판단용 부팅 횟수 */
class SystemNapClock(private val app: Application) : Clock {
    override fun bootCount(): Int =
        runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    override fun monotonicMs() = SystemClock.elapsedRealtime()
    override fun wallMs() = System.currentTimeMillis()
}

data class NapUi(
    val nap: NapState = NapState(),
    val car: MockCarState = MockCarState(),
    val vehicle: VehicleState = VehicleState.PARKED,
    val remainingMs: Long? = null,
    val restorePlan: List<RestoreItem> = emptyList(),
    val message: String? = null,
    val phoneReservation: Boolean = false,
    val energy: MockEnergyState = MockEnergyState(),
    /** 지금 남은 장치 사용 시간 추정(계산 불가면 이유) */
    val estimate: RuntimeEstimate? = null,
    /** 시연 장면 안내("장면 1 · 2/4 …"). 장면이 끝나면 null */
    val sceneCaption: String? = null,
    /** 시연 패널 열림. 기본은 닫힘 — 앱을 켜면 실제 사용자 화면이 나온다. */
    val demoPanel: Boolean = false,
    /** 실제 기어 값을 쓰는 중이면 그 표시(P·D 등). null 이면 시연 패널의 모의 상태를 쓰는 중이다. */
    val gearLabel: String? = null,
    /** 실제 전기차 배터리 값을 쓰는 중이면 그 값. null 이면 모의 전원 값을 쓰는 중이다(시연 장면·30배속 때도 모의). */
    val realEv: RealEvBattery? = null,
    /** 차량 공조·실내등 상태로 어림한 전기차 소비 전력. null 이면 모의 소비 전력을 쓰는 중이다. */
    val realLoad: EstimatedLoad? = null,
    /**
     * 시연 패널의 '공조 켬(시연 입력)'. 에뮬레이터의 공조 화면·Extended Controls 로는 SDK 공조 값이 안 바뀌어서
     * 실제 공조 상태 대신 "공조 켬 + 에어컨 켬"으로 계산해 화면 변화를 보여 준다. 실제 바람 세기는 그대로 쓴다.
     */
    val demoClimateOn: Boolean = false,
    /** 마지막 모드 적용 결과(실내등·시트·라디오). 무엇이 실제이고 모의인지 글자로 보여 준다. */
    val modeResult: List<String> = emptyList(),
    val modeBusy: Boolean = false,
    /** 차량 SDK 로 자동 판별한 차종 */
    val vehicleKind: VehicleKind = VehicleKind.UNKNOWN,
    /** 실제 연료량(%). 읽지 못하면 null */
    val fuelPercent: Int? = null,
    /** 실제 전기차 충전률이 마지막으로 들어온 시각(실시간 갱신 표시용) */
    val evUpdatedAtMs: Long? = null,
) {
    val controlAllowed: Boolean get() = vehicle == VehicleState.PARKED
    /** 지금 화면의 전기차 잔량이 실제 차량 값인지 */
    val realEvInUse: Boolean get() = realEv != null && nap.prefs.profile.managed == PowerSource.EV_TRACTION
    /** 지금 화면의 전기차 소비 전력이 차량 상태로 어림한 값인지 */
    val realLoadInUse: Boolean get() = realLoad != null && nap.prefs.profile.managed == PowerSource.EV_TRACTION
}

/**
 * 낮잠 휴식 흐름. 시간·알람 판단은 [NapEngine], 여기서는 저장 → 화면/소리/모의 제어 순서만 다룬다.
 * 화면이 다시 그려져도 타이머를 새로 시작하지 않는다(시작·목표 시각은 저장 상태에 있다).
 */
/** 주행·상태 불명일 때 모드 편집·추가를 막으며 보여 주는 문구 */
const val PARK_FIRST = "주차 후 이용할 수 있어요"

class NapController(app: Application) : AndroidViewModel(app) {
    private val clock: Clock = SystemNapClock(app)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val store = JsonFileStore(app.filesDir, "nap_state.json", NapCodec::encode, NapCodec::decode)
    private val carStore = JsonFileStore(app.filesDir, "mock_car.json",
        { NapJson.json.encodeToString(MockCarState.serializer(), it) }, { NapJson.json.decodeFromString(MockCarState.serializer(), it) })

    private val engine = NapEngine(store.load() ?: NapState())
    private val car = MockCar(carStore.load() ?: MockCarState()) { s ->
        runCatching { carStore.save(s) }
        _ui.update { it.copy(car = s) }
    }

    private val energyStore = JsonFileStore(app.filesDir, "mock_energy.json",
        { NapJson.json.encodeToString(MockEnergyState.serializer(), it) }, { NapJson.json.decodeFromString(MockEnergyState.serializer(), it) })
    private val energy = MockEnergy(energyStore.load() ?: MockEnergyState()) { s ->
        runCatching { energyStore.save(s) }
        _ui.update { it.copy(energy = s) }
    }

    private val _ui: MutableStateFlow<NapUi> = MutableStateFlow(NapUi(nap = engine.state, car = car.state, energy = energy.state))
    val ui: StateFlow<NapUi> = _ui

    private val alarm = AlarmSound(viewModelScope)
    private val gear = PleosGear(app) { state, label ->
        _ui.update { it.copy(gearLabel = label) }
        applyVehicle(state)
    }
    private val light = PleosCabinLight(gear.vehicle)
    private var realEv: RealEvBattery? = null
    private val createdElapsed = SystemClock.elapsedRealtime()
    /** 시연 장면 전의 차종·남길 배터리(장면이 끝나면 되돌림). 앱이 꺼지면 잊는다 */
    private var beforeScene: Pair<VehicleProfile, ReserveSettings>? = null
    /** 장면 4(충전 대기) 전의 차종·목표 충전률. 앱이 꺼지면 잊는다 */
    private var chargeScene: Pair<VehicleProfile, Int>? = null
    private val evBattery = PleosEvBattery(gear.vehicle) { value ->
        realEv = value
        if (value != null) {
            signals = signals.copy(evRead = true)
            // 충전률이 바뀌면 다음 1초 틱을 기다리지 않고 바로 화면·남은 시간을 갱신한다
            _ui.update { it.copy(evUpdatedAtMs = clock.wallMs()) }
            energyTick()
        }
        onSignals()
    }
    private var signals = PowertrainSignals()
    private var appliedKind = VehicleKind.UNKNOWN
    private val powertrain = PleosPowertrain(gear.vehicle) { s ->
        signals = s.copy(evRead = signals.evRead)
        onSignals()
    }
    private var cabinState = CabinState()
    private val cabin = PleosCabinState(gear.vehicle) { cabinState = it }
    private var mockLight = "ON"
    private var modeJob: Job? = null
    private var ticker: Job? = null
    private var scene: Job? = null
    private var reservationDone = false

    /** 진행 중인 모의 제어 요청. 휴식이 끝나거나 새로 시작하면 취소한다. */
    private val controlJobs = mutableListOf<Job>()

    init {
        val message = when (engine.onAppRestarted(clock)) {
            RestoreOutcome.OVERDUE -> Texts.OVERDUE
            RestoreOutcome.RESUMED -> "이전 휴식을 이어서 진행해요"
            RestoreOutcome.RESUMED_NEEDS_RECONFIRM -> "기기 시각이 바뀌었거나 재부팅됐어요. 남은 시간을 확인해 주세요."
            RestoreOutcome.STILL_RINGING -> "알람이 울리던 중이었어요"
            RestoreOutcome.NOTHING -> null
        }
        commit(message)
        if (engine.session?.status == NapStatus.RINGING || engine.state.chargeWait?.ringing == true) alarm.start()
        if (engine.session?.status == NapStatus.RESTORE_PENDING) refreshRestorePlan()
        startTicker()
    }

    /** 저장을 끝낸 뒤 화면에 반영한다. */
    private fun commit(message: String? = null) {
        endNapModeIfDone()
        // 충전 대기 감시는 '충전 대기' 모드가 켜져 있는 동안만 돈다
        val wait = engine.state.chargeWait
        engine.syncChargeWait(engine.state.prefs.activeMode?.mode == RestMode.CHARGE, clock)
        if (wait != null && engine.state.chargeWait == null) onChargeWaitStopped(wait.ringing)
        val saved = runCatching { store.save(engine.state) }.isSuccess
        // 저장에 실패하면 성공 안내로 덮지 않는다.
        val shown = if (saved) message else "저장 실패 — 앱을 다시 켜면 휴식이 이어지지 않을 수 있어요"
        _ui.update { it.copy(nap = engine.state, remainingMs = engine.remainingMs(clock), message = shown ?: it.message) }
    }

    private fun launchControl(block: suspend () -> Unit) {
        controlJobs.removeAll { !it.isActive }
        controlJobs += viewModelScope.launch { block() }
    }

    private fun cancelControls() {
        controlJobs.forEach { it.cancel() }
        controlJobs.clear()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var saveCounter = 0
            while (isActive) {
                when (val e = engine.tick(clock)) {
                    is TickEvent.Ring -> onRing(e)
                    TickEvent.None -> Unit
                }
                val now = SystemClock.elapsedRealtime()
                // 휴식 중에만 모의 배터리를 소모한다(설정 화면에서는 값이 그대로)
                val st = engine.session?.status
                if (st == NapStatus.RESTING || st == NapStatus.SNOOZED || st == NapStatus.RINGING || engine.state.chargeWait != null) energy.drain(now - last)
                last = now
                energyTick()
                checkReservation()
                _ui.update { it.copy(remainingMs = engine.remainingMs(clock), energy = energy.state) }
                if (++saveCounter % 20 == 0) energy.save() // 5초마다 모의 전원 값 저장
                delay(250)
            }
        }
    }

    private fun onRing(e: TickEvent.Ring) {
        val reason = engine.session?.alarmReason ?: AlarmReason.WAKE_TIME
        commit(if (reason == AlarmReason.WAKE_TIME && e.sequence > 1) "다시 알림 시간이 됐어요" else reason.title)
        alarm.start()
    }

    // ── 전력 보호 ─────────────────────────────────────────

    /** 모의 전원 값으로 남은 시간을 계산하고 보호 판단을 진행한다. 상태가 바뀔 때만 저장한다. */
    private fun energyTick() {
        val before = engine.state.guard
        // 시연 장면과 30배속은 값을 일부러 옮기므로 모의 값으로 돌린다
        val useReal = scene == null && energy.state.drainSpeed == 1
        val ev = realEv.takeIf { useReal }
        // 앱을 막 켰을 때 실제 배터리 값이 오기 전 모의 값(60%·180km)으로 보호 판단을 하지 않는다 — 첫 값을 잠깐 기다린다(기상 알람은 engine.tick 이 따로 처리)
        if (useReal && ev == null && gear.vehicle != null && engine.state.prefs.profile.managed == PowerSource.EV_TRACTION &&
            SystemClock.elapsedRealtime() - createdElapsed < FIRST_REAL_WAIT_MS) return
        // 공조(시연 입력)·라디오(모드, 모의)·실내등을 켜고 끄면 남은 시간이 바로 바뀐다
        val withClimate = if (_ui.value.demoClimateOn) cabinState.copy(climateOn = true, acOn = true) else cabinState
        val cs = withClimate.copy(
            radioOn = engine.state.prefs.activeMode?.radioOn == true,
            cabinLightOn = withClimate.cabinLightOn ?: (mockLight != ModeRules.LIGHT_OFF),
        )
        val load = if (useReal) CabinLoad.estimate(cs) else null
        val readings = energy.readings(clock.wallMs(), ev, load?.watts)
        val t = engine.energyTick(readings, clock)
        // 충전 대기: 목표 충전률에 닿으면 알람(낮잠 타이머와 별개)
        val waitBefore = engine.state.chargeWait
        if (engine.chargeTick(readings, clock)) {
            commit("충전이 ${engine.state.prefs.chargeTargetSoc}%까지 찼어요")
            alarm.start()
        } else if (engine.state.chargeWait != waitBefore) commit()
        _ui.update { it.copy(estimate = t.estimate, realEv = ev, realLoad = load) }
        if (engine.state.guard != before) commit()
        t.events.forEach { ev ->
            when (ev) {
                is GuardEvent.ProtectionBeforeWake -> say(Texts.protectionBeforeWake(ev.minutes))
                is GuardEvent.DataLost -> say("전원 측정값이 끊겨 보호 상태를 확인할 수 없어요 · 장치를 직접 확인해 주세요(장치를 껐다고 가정하지 않아요)")
                is GuardEvent.RequestShed -> shed(ev.loads)
                is GuardEvent.WarnEarly, GuardEvent.StarterWarning -> Unit // 알람으로 처리
            }
        }
        val ring = t.ring
        if (ring is TickEvent.Ring) onRing(ring)
    }

    private fun say(text: String) {
        commit(text)
    }

    /** 보호 기준 도달: 미리 정한 모의 장치에 종료 요청 → 응답으로 결과 확인(응답 없음·실패는 종료됨으로 쓰지 않음) */
    private fun shed(loads: List<Load>) {
        val sessionId = engine.session?.id ?: return
        val names = loads.joinToString { it.label }
        commit("전력 보호 기준 도달 — 모의 $names 종료 요청")
        viewModelScope.launch {
            val result = withTimeoutOrNull(3_000) { energy.shed(loads) } ?: ControlState.TIMED_OUT
            if (engine.session?.id != sessionId) return@launch
            when (engine.onShedResult(result, clock)) {
                GuardStatus.SHED_CONFIRMED -> say("모의 $names 꺼짐(모의 장치 응답). 보호 종료 뒤에는 자동으로 다시 켜지 않아요.")
                GuardStatus.SHED_FAILED -> {
                    say("모의 $names 를 끄지 못했어요(${result.label}). 직접 꺼 주세요.")
                    if (engine.session?.status != NapStatus.RINGING) alarm.start()
                }
                else -> commit()
            }
        }
    }

    // ── 시작 ──────────────────────────────────────────────

    fun setMinutes(minutes: Int) {
        engine.setPreferences(engine.state.prefs.copy(lastMinutes = minutes.coerceIn(1, 180)))
        commit()
    }

    /** 휴식 화면에서 전체 휴식 시간을 바꾼다. 남은 시간이 1분 미만이 되면 거절한다. */
    fun adjustRest(minutes: Int) {
        val session = engine.setDuration(minutes.coerceIn(1, 180) * 60L, clock)
            ?: return commit("이미 지난 시각으로는 바꿀 수 없어요")
        commit("${Texts.clock(session.targetWallMs, zone)}에 알려 드릴게요")
    }

    fun setTemperature(c: Double) {
        engine.setPreferences(engine.state.prefs.copy(temperatureC = c.coerceIn(17.0, 28.0)))
        commit()
    }

    /**
     * 차종 단서가 바뀔 때마다 판별한다. 판별 결과가 새로 정해졌을 때 한 번만 전원 설정을 맞춘다
     * (그 뒤 시연 패널에서 직접 고른 값은 덮어쓰지 않는다). 휴식·시연 장면 중에는 바꾸지 않는다.
     */
    private fun onSignals() {
        val kind = VehicleKinds.detect(signals)
        _ui.update { it.copy(vehicleKind = kind, fuelPercent = signals.fuelPercent) }
        if (kind == appliedKind || engine.state.active || scene != null) return
        appliedKind = kind
        VehicleKinds.profileFor(kind, engine.state.prefs.profile)?.let { p ->
            setProfile(p)
            _ui.update { it.copy(message = "차종을 ${kind.label}로 자동 인식했어요") }
        }
    }

    fun setProfile(p: VehicleProfile) {
        if (engine.state.active) return
        engine.setPreferences(engine.state.prefs.copy(profile = p))
        commit()
    }

    fun setReserve(r: ReserveSettings) {
        if (engine.state.active) return
        engine.setPreferences(engine.state.prefs.copy(reserve = r.copy(
            evReserveSoc = r.evReserveSoc.coerceIn(10.0, 80.0), evReserveKm = r.evReserveKm.coerceIn(20.0, 400.0), campingReserveWh = r.campingReserveWh.coerceIn(50.0, 1_000.0),
        )))
        commit()
    }

    /** 충전 완료 알람 목표(%). null = 끔. 휴식 중에도 바꿀 수 있다(바꾸면 지금 충전률부터 다시 판단) */
    fun setChargeTarget(soc: Int) {
        engine.setChargeTarget(soc)
        commit("충전이 ${engine.state.prefs.chargeTargetSoc}%까지 차면 알려 드릴게요")
    }

    /** 충전 완료 알람 확인. [stopMode] 면 충전 대기 모드도 끄고 원래대로 되돌린다. */
    fun ackCharge(stopMode: Boolean) {
        alarm.stop()
        engine.ackChargeAlarm()
        commit("충전 완료 알람을 껐어요")
        if (stopMode && engine.state.prefs.activeMode?.mode == RestMode.CHARGE) pressModeKey(RestMode.CHARGE.name)
    }

    /** 모드 설정 저장. 낮잠 모드의 시트는 휴식 시작 때 쓰는 값이기도 하다. */
    fun setModeSetting(mode: RestMode, setting: ModeSetting) {
        if (!_ui.value.controlAllowed) return commit(PARK_FIRST)
        val prefs = engine.state.prefs
        engine.setPreferences(prefs.copy(
            modes = prefs.modes.with(mode, setting),
            seatPreset = if (mode == RestMode.NAP) setting.seat else prefs.seatPreset,
        ))
        commit()
    }

    // ── 휴식 모드 ─────────────────────────────────────────
    // 무엇을 바꿀지는 ModeRules 가 정한다. 실내등은 실제 기어 P 이고 차량이 지원할 때만 실제 명령을 보내고,
    // 시트 자세와 라디오는 공개 SDK 에 기능이 없어 모의다.

    fun pressMode(mode: RestMode) = pressModeKey(mode.name)

    /** 하단바 한 칸을 눌렀을 때. 기본 모드는 이름, 추가한 모드는 ModeView.key 로 찾는다. */
    fun pressModeKey(key: String) {
        if (!_ui.value.controlAllowed) return commit("주차 상태에서만 모드를 쓸 수 있어요")
        if (modeJob?.isActive == true) return
        modeJob = viewModelScope.launch {
            _ui.update { it.copy(modeBusy = true) }
            try {
                val current = engine.state.prefs.activeMode
                if (key == RestMode.NAP.name) {
                    // 켜진 모드를 먼저 끄고(같은 키를 다시 누른 것으로) 낮잠을 시작한다
                    if (current != null) modeChange(current.key)?.let { applyMode(it) }
                    startNapMode()
                } else {
                    modeChange(key)?.let { applyMode(it) }
                }
            } finally {
                _ui.update { it.copy(modeBusy = false) }
            }
        }
    }

    private suspend fun modeChange(key: String): ModeChange? {
        val prefs = engine.state.prefs
        val view = prefs.modeViews().firstOrNull { it.key == key }
        val seatNow = car.read().seat ?: SeatPreset.UPRIGHT
        val light = readLight()
        val active = prefs.activeMode
        val mode = view?.mode
        val custom = view?.custom
        return when {
            mode != null -> ModeRules.press(active, mode, prefs.modes, seatNow, light)
            custom != null -> ModeRules.pressCustom(active, custom, seatNow, light)
            // 켜진 모드를 그사이 지웠다면 처음 값으로만 되돌린다
            active != null && active.key == key -> ModeChange(null, active.originalSeat, active.originalLight, radioOn = false)
            else -> null
        }
    }

    /** 하단바 + 로 모드 추가. 성공하면 true(화면이 입력 창을 닫는다). */
    fun addCustomMode(name: String, icon: String, setting: ModeSetting): Boolean =
        if (!_ui.value.controlAllowed) { commit(PARK_FIRST); false }
        else when (val r = CustomModes.add(engine.state.prefs, name, icon, setting)) {
            is CustomModes.AddResult.Added -> { engine.setPreferences(r.prefs); commit("${r.mode.name} 모드를 추가했어요"); true }
            is CustomModes.AddResult.Rejected -> { commit(r.reason); false }
        }

    fun removeCustomMode(id: String) {
        val next = CustomModes.remove(engine.state.prefs, id) ?: return commit("켜져 있는 모드는 끈 뒤에 지울 수 있어요")
        engine.setPreferences(next)
        commit("모드를 지웠어요")
    }

    /**
     * 창 제목 옆 🗑 로 지우기. 켜져 있으면 먼저 끄고 원래대로 되돌린 뒤 지운다(되돌릴 원래 값을 잃지 않게).
     * 끄기가 실패해 아직 켜져 있으면 [removeCustomMode] 가 지우지 않고 이유를 알린다.
     */
    fun deleteCustomMode(id: String) {
        val active = engine.state.prefs.activeMode
        if (active?.customId != id) return removeCustomMode(id)
        if (!_ui.value.controlAllowed) return commit("주차 상태에서만 끄고 지울 수 있어요")
        if (modeJob?.isActive == true) return
        modeJob = viewModelScope.launch {
            _ui.update { it.copy(modeBusy = true) }
            try {
                modeChange(active.key)?.let { applyMode(it) }
            } finally {
                _ui.update { it.copy(modeBusy = false) }
            }
            removeCustomMode(id)
        }
    }

    fun setCustomSetting(id: String, setting: ModeSetting) {
        if (!_ui.value.controlAllowed) return commit(PARK_FIRST)
        engine.setPreferences(CustomModes.update(engine.state.prefs, id, setting))
        commit()
    }

    private suspend fun applyMode(change: ModeChange) {
        val lights = setLight(change.light)
        val seat = withTimeoutOrNull(3_000) { car.request(ControlTarget.SEAT, VehicleSettings(seat = change.seat)) { _ui.value.controlAllowed } }
        engine.setPreferences(engine.state.prefs.copy(activeMode = change.next))
        val seatText = if (seat == ControlState.ACKNOWLEDGED) "시트: ${change.seat.label}(모의)" else "시트 조절 실패 — 직접 조절해 주세요"
        _ui.update { it.copy(modeResult = listOf(lightText(lights, change.light), seatText, radioText(change.radioOn))) }
        // 끄기 성공(모드 꺼짐)과 차량 설정 복구 성공을 섞지 않는다 — 실패·미확인 항목은 이름을 짚어 알린다
        val unsure = buildList {
            if (lights == LightOutcome.FAILED || lights == LightOutcome.UNCONFIRMED) add("실내등")
            if (seat != ControlState.ACKNOWLEDGED) add("시트")
        }
        val name = engine.state.prefs.activeView()?.name ?: ""
        commit(when {
            change.next != null && unsure.isEmpty() -> "$name 모드를 켰어요"
            change.next != null -> "$name 모드를 켰어요 · ${unsure.joinToString("·")}는 확인이 필요해요"
            unsure.isEmpty() -> "모드를 끄고 원래대로 되돌렸어요"
            else -> "모드는 껐어요 · ${unsure.joinToString("·")} 복구는 확인이 필요해요"
        })
    }

    /** 낮잠 모드: 저장한 설정으로 바로 휴식을 시작한다. 시트는 휴식의 복구 흐름이 맡고, 등과 라디오는 휴식이 끝나면 되돌린다. */
    private suspend fun startNapMode() {
        val prefs = engine.state.prefs
        val setting = prefs.modes.nap
        val original = readLight()
        val seatNow = car.read().seat ?: SeatPreset.UPRIGHT
        engine.setPreferences(prefs.copy(seatPreset = setting.seat))
        startRest()
        if (engine.state.session?.status != NapStatus.RESTING) return
        val target = if (setting.lightsOff) ModeRules.LIGHT_OFF else original
        val lights = setLight(target)
        engine.setPreferences(engine.state.prefs.copy(activeMode = ActiveMode(RestMode.NAP, seatNow, original, setting.radioOn)))
        _ui.update { it.copy(modeResult = listOf(lightText(lights, target), radioText(setting.radioOn))) }
        commit()
    }

    private fun endNapModeIfDone() {
        val active = engine.state.prefs.activeMode ?: return
        val status = engine.state.session?.status
        if (active.mode != RestMode.NAP || (status != null && status != NapStatus.FINISHED)) return
        engine.setPreferences(engine.state.prefs.copy(activeMode = null))
        viewModelScope.launch {
            val lights = setLight(active.originalLight)
            _ui.update { it.copy(modeResult = listOf(lightText(lights, active.originalLight), radioText(false))) }
        }
    }

    private val realLight: Boolean get() = light.supported && _ui.value.gearLabel == "P"

    private suspend fun readLight(): String = (if (realLight) light.read() else null) ?: mockLight

    private suspend fun setLight(target: String): LightOutcome {
        if (realLight) return light.set(target)
        mockLight = target
        return LightOutcome.MOCK
    }

    private fun lightText(outcome: LightOutcome, target: String): String {
        val state = if (target == ModeRules.LIGHT_OFF) "꺼짐" else "원래대로"
        return when (outcome) {
            LightOutcome.MOCK -> "실내등: $state(모의)"
            LightOutcome.ALREADY -> "실내등: 이미 $state"
            LightOutcome.CONFIRMED -> "실내등: $state"
            LightOutcome.UNCONFIRMED -> "실내등: 명령 전송 — 변화 미확인, 직접 확인해 주세요"
            LightOutcome.FAILED -> "실내등: 실패 — 직접 조작해 주세요"
        }
    }

    private fun radioText(on: Boolean) = "라디오: ${if (on) "켜짐" else "꺼짐"}(모의)"

    /** 휴식 시작. 주차 상태에서만. 공조·시트 준비는 따로 진행하고, 실패해도 타이머는 계속한다. */
    fun startRest(demo20s: Boolean = false) {
        if (!_ui.value.controlAllowed) return commit("주차 상태에서만 휴식을 시작할 수 있어요")
        val seconds = if (demo20s) 20L else engine.state.prefs.lastMinutes * 60L
        cancelControls()
        val session = engine.start(seconds, clock, demoShortened = demo20s) ?: return
        engine.recordOriginal(car.read(), clock)
        reservationDone = false
        _ui.update { it.copy(phoneReservation = false) }
        commit(if (demo20s) "시연용 20초 타이머(시간 단축)" else "${Texts.clock(session.targetWallMs, zone)}에 알려 드릴게요")
        val prefs = engine.state.prefs
        launchControl {
            control(session.id, ControlTarget.CLIMATE, VehicleSettings(temperatureC = prefs.temperatureC))
            control(session.id, ControlTarget.SEAT, VehicleSettings(seat = prefs.seatPreset))
        }
    }

    /**
     * 모의 제어 요청 하나. 끝난 휴식이면 보내지 않고, 주차가 아니면 막는다. 요청을 보내기 전에 기록·저장하고
     * (도중에 꺼져도 앱이 바꾸려던 값을 잃지 않게), 차량이 값을 바꾸기 직전에도 주차 상태를 다시 확인한다.
     */
    private suspend fun control(sessionId: String, target: ControlTarget, value: VehicleSettings, isRestore: Boolean = false, note: String? = null) {
        if (!engine.acceptsControl(sessionId, isRestore)) return
        val action = engine.requestAction(sessionId, target, value, isRestore, note) ?: return
        commit()
        val result = try {
            if (!_ui.value.controlAllowed) ControlState.BLOCKED
            else withTimeoutOrNull(3_000) { car.request(target, value) { _ui.value.controlAllowed && engine.acceptsControl(sessionId, isRestore) } }
                ?: ControlState.TIMED_OUT
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 휴식이 끝나 취소됨: 차량 값은 바뀌지 않았으므로 앱이 바꾼 값에서도 뺀다
            if (engine.completeAction(action.id, ControlState.CANCELLED) != null) commit()
            throw e
        }
        if (engine.completeAction(action.id, result) != null) commit()
    }

    // ── 알람·종료 ─────────────────────────────────────────

    fun snooze() {
        if (engine.snooze(clock) == null) return
        alarm.stop()
        commit("${engine.state.prefs.snoozeMinutes}분 뒤 다시 알려 드릴게요")
    }

    fun wake() {
        if (!engine.wake(clock)) return
        endScene()
        cancelControls()
        alarm.stop()
        restoreAndFinish()
    }

    fun stopEarly() {
        if (!engine.stopEarly(clock)) return
        endScene()
        cancelControls()
        alarm.stop()
        restoreAndFinish()
    }

    /**
     * 낮잠 끝: 영화관 모드의 '끄고 원래대로'처럼 되돌리기 단계를 따로 두지 않고 바로 되돌린 뒤 끝낸다(2026-11-13 사용자 요청).
     * 버튼을 누른 것이 깨어난 사용자의 확인이다. 사용자가 직접 바꾼 값과 전력 보호로 끈 장치는 덮어쓰거나 다시 켜지 않는다.
     * 주차 상태가 아니면 차를 움직이지 않고 지금 값 그대로 끝낸다.
     */
    private fun restoreAndFinish() {
        val s = engine.session?.takeIf { it.status == NapStatus.RESTORE_PENDING } ?: return
        val shed = engine.state.guard?.status?.shed == true
        val plan = engine.restorePlan(car.read())
        _ui.update { it.copy(restorePlan = plan) }
        val original = engine.state.original?.settings
        val todo = plan.filter { it.decision == RestoreDecision.RESTORE }
        val kept = plan.filter { it.decision == RestoreDecision.CONFIRM_MANUAL_CHANGE }.map { it.target.label }
        val parked = _ui.value.controlAllowed
        commit(if (todo.isEmpty()) null else "원래대로 되돌리는 중…")
        launchControl {
            if (parked && original != null) todo.forEach { control(s.id, it.target, original, isRestore = true) }
            val notes = buildList {
                if (!parked && todo.isNotEmpty()) add("주차 상태가 아니라 ${todo.joinToString("·") { it.target.label }}은(는) 지금 값 그대로예요")
                if (kept.isNotEmpty()) add("직접 바꾼 ${kept.joinToString("·")}은(는) 그대로 뒀어요")
                // 공조뿐 아니라 캠핑 조명처럼 복구 목록에 없는 장치도 보호로 껐으면 알린다
                if (shed || plan.any { it.decision == RestoreDecision.BLOCKED_BY_PROTECTION }) add("전력 보호로 끈 장치는 다시 켜지 않았어요")
            }
            finishRestore((listOf("낮잠을 끝내고 원래대로 되돌렸어요") + notes).joinToString(" · "))
        }
    }

    fun confirmSchedule() {
        engine.confirmSchedule()
        commit("남은 시간대로 계속해요")
    }

    // ── 복구 ──────────────────────────────────────────────

    private fun refreshRestorePlan() {
        _ui.update { it.copy(restorePlan = engine.restorePlan(car.read())) }
    }

    /** 항목 하나를 원래 값으로 되돌린다(사용자가 누른 것만). 직접 바꾼 항목도 사용자가 고르면 되돌린다. */
    fun restore(item: RestoreItem) {
        val s = engine.session?.takeIf { it.status == NapStatus.RESTORE_PENDING } ?: return
        val original = engine.state.original?.settings ?: return
        if (item.decision != RestoreDecision.RESTORE && item.decision != RestoreDecision.CONFIRM_MANUAL_CHANGE) return
        launchControl {
            control(s.id, item.target, original, isRestore = true)
            refreshRestorePlan()
        }
    }

    fun finishRestore() = finishRestore("기록에 남겼어요")

    private fun finishRestore(message: String) {
        engine.finish() ?: return
        endScene()
        cancelControls()
        beforeScene?.let { (profile, reserve) -> engine.setPreferences(engine.state.prefs.copy(profile = profile, reserve = reserve)) }
        beforeScene = null
        _ui.update { it.copy(restorePlan = emptyList()) }
        commit(message)
    }

    // ── 시연 패널 ─────────────────────────────────────────

    fun toggleDemoPanel() = _ui.update { it.copy(demoPanel = !it.demoPanel) }
    fun setDemoClimate(on: Boolean) = _ui.update { it.copy(demoClimateOn = on) }

    /** 시연 패널의 모의 상태. 다음 실제 기어 값이 오면 그 값으로 바뀐다. */
    fun setVehicle(v: VehicleState) {
        _ui.update { it.copy(gearLabel = null) }
        applyVehicle(v)
    }

    private fun applyVehicle(v: VehicleState) {
        _ui.update { it.copy(vehicle = v) }
        if (v != VehicleState.PARKED) commit("주차 상태가 아니어서 공조·시트 요청을 막아요")
    }

    fun setFailClimate(on: Boolean) = car.update { copy(failClimate = on) }
    fun setFailSeat(on: Boolean) = car.update { copy(failSeat = on) }
    fun setNoResponse(on: Boolean) = car.update { copy(noResponse = on) }

    /** 휴식 중 사용자가 직접 온도를 바꾼 상황 */
    fun manualTemperature(c: Double) {
        car.update { copy(settings = settings.copy(temperatureC = c)) }
        if (engine.session?.status == NapStatus.RESTORE_PENDING) refreshRestorePlan()
        commit("모의 차량 온도를 직접 ${c}℃로 바꿨어요")
    }

    /** 휴대폰 예약(모의): 기상 10분 전에 공조를 원래 온도로 미리 돌려 둔다 */
    fun phoneReservation() {
        if (engine.session == null) return commit("휴식 중에만 예약할 수 있어요")
        _ui.update { it.copy(phoneReservation = true) }
        commit("휴대폰에서 '10분 전 준비' 예약이 들어왔어요(모의 이벤트)")
    }

    private fun checkReservation() {
        if (!_ui.value.phoneReservation || reservationDone) return
        val s = engine.session ?: return
        val left = engine.remainingMs(clock) ?: return
        if (left > 10 * 60_000L) return
        reservationDone = true
        val original = engine.state.original?.settings?.temperatureC ?: return
        // 복구 규칙과 같게: 앱이 바꾼 공조이고 그 뒤 아무도 안 바꿨을 때만 원래 온도로 돌린다(수동 변경은 덮어쓰지 않음).
        val climate = engine.restorePlan(car.read()).first { it.target == ControlTarget.CLIMATE }
        if (climate.decision != RestoreDecision.RESTORE) {
            return commit("휴대폰 예약: ${climate.decision.label} — 공조를 바꾸지 않았어요")
        }
        // 앱이 바꾼 값으로 기록해야 복구 화면에서 "직접 바꾼 값"으로 오인하지 않는다.
        launchControl { control(s.id, ControlTarget.CLIMATE, VehicleSettings(temperatureC = original), note = "휴대폰 예약(모의)") }
    }

    // ── 시연 패널: 모의 전원 ───────────────────────────────

    fun energy(change: MockEnergyState.() -> MockEnergyState) = energy.update(change)

    /** 지금 차량·전원 구성에서 소비 전력을 바꾼다(전기차 공조 / 캠핑 조명) */
    fun changeLoad(deltaW: Double) = energy.update {
        when (engine.state.guard?.profile ?: engine.state.prefs.profile) {
            VehicleProfile.EV -> copy(evClimateW = (evClimateW + deltaW).coerceIn(0.0, 6_000.0))
            VehicleProfile.ICE_CAMPING -> copy(campingLightW = (campingLightW + deltaW / 10).coerceIn(0.0, 600.0))
            VehicleProfile.ICE -> this
        }
    }

    // ── 시연 장면(버튼 하나로 처음부터 끝까지) ────────────

    private fun endScene() {
        scene?.cancel()
        scene = null
        if (energy.state.drainSpeed != 1) energy.update { copy(drainSpeed = 1) }
        _ui.update { it.copy(sceneCaption = null) }
    }

    private fun caption(text: String) = _ui.update { it.copy(sceneCaption = text) }

    /** 장면 진행 중 휴식이 끝났으면 멈춘다 */
    private fun sceneAlive(id: String) = engine.session?.id == id && engine.session?.status != NapStatus.RESTORE_PENDING

    /**
     * 장면 1 · 전기차: 1.5kW → 12시간, 공조 세게 3kW → 6시간, 보존량 근처(시간 단축) → 기상 전에 먼저 깨움,
     * 소모 30배속 → 보존량 도달 → 모의 공조 종료.
     */
    fun sceneEv() = runScene(VehicleProfile.EV) { id ->
        caption("장면 1 · 1/4  전기차 60kWh(가정)·SOC 60%·보존 30% · 소비 1.5kW → 남은 시간 12시간")
        delay(6_000); if (!sceneAlive(id)) return@runScene
        energy.update { copy(evClimateW = 2_800.0) }
        caption("장면 1 · 2/4  공조를 세게(총 3kW) → 남은 시간이 6시간으로 줄어요")
        delay(6_000); if (!sceneAlive(id)) return@runScene
        energy.update { copy(evSoc = 30.6) }
        caption("장면 1 · 3/4  보존량 근처로 이동(시간 단축) → 기상 시각 전에 먼저 깨워요 · 보호 종료까지 보려면 '소리만 5분 미루기'를 눌러 주세요(한도는 그대로)")
        delay(8_000); if (!sceneAlive(id)) return@runScene
        energy.update { copy(drainSpeed = 30) }
        caption("장면 1 · 4/4  배터리 소모 30배속 → 보존량 30%에 닿으면 모의 공조를 끄고 결과를 확인해요")
    }

    /** 장면 2 · 내연기관차 + 캠핑 배터리: 800Wh·조명 100W → 6시간, 보존량 근처 → 먼저 깨움 → 모의 조명 종료 */
    fun sceneCamping() = runScene(VehicleProfile.ICE_CAMPING) { id ->
        caption("장면 2 · 1/3  내연기관차 + 캠핑 배터리 800Wh·보존 200Wh · 조명 100W → 6시간 (순정 공조가 아니라 캠핑 배터리에 연결된 장치만)")
        delay(7_000); if (!sceneAlive(id)) return@runScene
        energy.update { copy(campingWh = 215.0) }
        caption("장면 2 · 2/3  보존량 근처로 이동(시간 단축) → 먼저 깨워요")
        delay(8_000); if (!sceneAlive(id)) return@runScene
        energy.update { copy(drainSpeed = 30) }
        caption("장면 2 · 3/3  소모 30배속 → 보존량 200Wh 에 닿으면 모의 캠핑 조명을 꺼요")
    }

    /** 장면 3 · 12V 만 있는 내연기관차: 남은 시간 "계산 불가", 그래도 알람은 울린다(20초 단축 타이머) */
    fun sceneStarter() = runScene(VehicleProfile.ICE, demo20s = true) { id ->
        caption("장면 3 · 1/2  12V 시동 배터리만 있는 차 → 전압만으로는 남은 시간을 알 수 없어 '계산 불가' · 알람만 써요")
        delay(10_000); if (!sceneAlive(id)) return@runScene
        caption("장면 3 · 2/2  20초 뒤 평소처럼 알람이 울려요(시간 단축). 시연 패널의 '12V 경고 신호'로 차량 경고 알람도 볼 수 있어요")
    }

    /**
     * 장면 4 · 충전 대기 모드: 전기차·목표 80%로 맞추고 '충전 대기'를 켠 뒤 모의 72% → 50kW 충전(30배속) → 80% 알람.
     * 알람을 확인하고 모드를 끄면 장면 전의 차종·목표·모의 충전으로 돌려놓는다([onChargeWaitStopped]).
     */
    fun sceneCharge() {
        if (engine.state.active || engine.state.prefs.activeMode != null) return commit("켜져 있는 모드·휴식을 먼저 꺼 주세요")
        endScene()
        _ui.update { it.copy(demoPanel = false) }
        energy.update { MockEnergyState(failShed = failShed, noResponseShed = noResponseShed, evSoc = 72.0, charging = true) }
        val prefs = engine.state.prefs
        chargeScene = prefs.profile to prefs.chargeTargetSoc
        engine.setPreferences(prefs.copy(profile = VehicleProfile.EV))
        engine.setChargeTarget(ChargeWake.DEFAULT)
        pressModeKey(RestMode.CHARGE.name)
        scene = viewModelScope.launch {
            caption("장면 4 · 1/2  휴게소 급속충전 중 차에서 기다리기 · '충전 대기' 켬 · 지금 72% · 목표 ${ChargeWake.DEFAULT}% (모의 50kW 충전, 타이머 없음)")
            delay(6_000); if (engine.state.chargeWait == null) return@launch
            energy.update { copy(drainSpeed = 30) }
            caption("장면 4 · 2/2  충전 30배속(시간 단축) → ${ChargeWake.DEFAULT}%에 닿으면 알람이 울려요")
        }
    }

    /** 충전 대기가 꺼졌을 때(모드 끄기·다른 모드 켜기). 장면 4 였으면 장면 전 값으로 돌려놓는다. */
    private fun onChargeWaitStopped(wasRinging: Boolean) {
        if (wasRinging) alarm.stop()
        val before = chargeScene ?: return
        chargeScene = null
        endScene()
        engine.setPreferences(engine.state.prefs.copy(profile = before.first))
        engine.setChargeTarget(before.second)
        if (energy.state.charging) energy.update { copy(charging = false) }
    }

    private fun runScene(profile: VehicleProfile, demo20s: Boolean = false, steps: suspend (String) -> Unit) {
        if (engine.state.active) return commit("진행 중인 휴식을 먼저 끝내 주세요")
        endScene()
        // 장면이 시작되면 시연 패널을 접어 낮잠 창과 결과가 좁아지지 않게 한다
        _ui.update { it.copy(demoPanel = false) }
        energy.update { MockEnergyState(failShed = failShed, noResponseShed = noResponseShed) }
        // 모의 차량도 처음 값(24℃·기본 자세)으로 — 복구 화면에서 "원래 값"이 장면마다 같게 보이도록
        car.update { copy(settings = MockCarState().settings) }
        // 장면이 끝나면(finishRestore) 장면 전의 차종·남길 배터리로 돌려놓는다 — 시연 뒤 전기차가 12V 화면으로 남지 않게
        val prefs = engine.state.prefs
        beforeScene = prefs.profile to prefs.reserve
        engine.setPreferences(prefs.copy(profile = profile, reserve = ReserveSettings(), lastMinutes = 30))
        startRest(demo20s)
        val id = engine.session?.id ?: return
        scene = viewModelScope.launch { steps(id) }
    }

    fun time(wallMs: Long) = Texts.clock(wallMs, zone)

    fun onAppStopped() {
        runCatching { store.save(engine.state) }
        energy.save()
    }

    override fun onCleared() {
        light.release()
        evBattery.release()
        powertrain.release()
        cabin.release()
        gear.release()
        alarm.stop()
    }
}

/** 앱을 켠 뒤 첫 실제 전기차 배터리 값을 기다리는 최대 시간. 이 안에는 모의 값으로 전력 보호를 판단하지 않는다 */
private const val FIRST_REAL_WAIT_MS = 5_000L
