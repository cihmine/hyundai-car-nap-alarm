package com.cihmine.napalarm

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cihmine.napalarm.core.AlarmReason
import com.cihmine.napalarm.core.Clock
import com.cihmine.napalarm.core.GuardEvent
import com.cihmine.napalarm.core.GuardStatus
import com.cihmine.napalarm.core.Load
import com.cihmine.napalarm.core.ReserveSettings
import com.cihmine.napalarm.core.RuntimeEstimate
import com.cihmine.napalarm.core.VehicleProfile
import com.cihmine.napalarm.vehicle.MockEnergy
import com.cihmine.napalarm.vehicle.MockEnergyState
import com.cihmine.napalarm.core.ControlState
import com.cihmine.napalarm.core.ControlTarget
import com.cihmine.napalarm.core.NapCodec
import com.cihmine.napalarm.core.NapCommand
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
import com.cihmine.napalarm.core.Utterance
import com.cihmine.napalarm.core.VehicleSettings
import com.cihmine.napalarm.core.VoiceParser
import com.cihmine.napalarm.data.JsonFileStore
import com.cihmine.napalarm.speech.AlarmSound
import com.cihmine.napalarm.speech.SpeechError
import com.cihmine.napalarm.speech.SpeechInput
import com.cihmine.napalarm.speech.Voice
import com.cihmine.napalarm.vehicle.MockCar
import com.cihmine.napalarm.vehicle.MockCarState
import com.cihmine.napalarm.vehicle.PleosGear
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
    /** 음성으로 들은 휴식 시간 — 확인 전에는 예약하지 않는다 */
    val pendingMinutes: Int? = null,
    val restorePlan: List<RestoreItem> = emptyList(),
    val message: String? = null,
    val listening: Boolean = false,
    val ttsReady: Boolean = false,
    val sttAvailable: Boolean = false,
    val micGranted: Boolean = false,
    val ttsEngine: String = "",
    val sttEngine: String = "",
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
) {
    val controlAllowed: Boolean get() = vehicle == VehicleState.PARKED
}

/**
 * 낮잠 휴식 흐름. 시간·알람 판단은 [NapEngine], 여기서는 저장 → 화면/소리/모의 제어 순서만 다룬다.
 * 화면이 다시 그려져도 타이머를 새로 시작하지 않는다(시작·목표 시각은 저장 상태에 있다).
 */
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
    private val tts = Voice.output(app) { ok -> _ui.update { it.copy(ttsReady = ok) } }
    private val stt = Voice.input(app) { ok -> _ui.update { it.copy(sttAvailable = ok) } }
    private val gear = PleosGear(app) { state, label ->
        _ui.update { it.copy(gearLabel = label) }
        applyVehicle(state)
    }
    private var ticker: Job? = null
    private var scene: Job? = null
    private var reservationDone = false

    /** 진행 중인 모의 제어 요청. 휴식이 끝나거나 새로 시작하면 취소한다. */
    private val controlJobs = mutableListOf<Job>()

    /** 음성 인식 요청 번호. 상태가 바뀌면 올려서 늦게 온 결과를 버린다. */
    private var listenToken = 0

    init {
        _ui.update { it.copy(ttsEngine = tts.engineName, sttEngine = stt.engineName) }
        val message = when (engine.onAppRestarted(clock)) {
            RestoreOutcome.OVERDUE -> Texts.OVERDUE
            RestoreOutcome.RESUMED -> "이전 휴식을 이어서 진행해요"
            RestoreOutcome.RESUMED_NEEDS_RECONFIRM -> "기기 시각이 바뀌었거나 재부팅됐어요. 남은 시간을 확인해 주세요."
            RestoreOutcome.STILL_RINGING -> "알람이 울리던 중이었어요"
            RestoreOutcome.NOTHING -> null
        }
        commit(message)
        if (engine.session?.status == NapStatus.RINGING) alarm.start()
        if (engine.session?.status == NapStatus.RESTORE_PENDING) refreshRestorePlan()
        startTicker()
    }

    fun setMicGranted(granted: Boolean) = _ui.update { it.copy(micGranted = granted) }

    /** 저장을 끝낸 뒤 화면에 반영한다. */
    private fun commit(message: String? = null) {
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

    /** 상태가 바뀔 때 듣던 음성 인식을 끊고 늦은 결과를 버린다. */
    private fun stopListening() {
        listenToken++
        stt.cancel()
        _ui.update { it.copy(listening = false) }
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
                if (st == NapStatus.RESTING || st == NapStatus.SNOOZED || st == NapStatus.RINGING) energy.drain(now - last)
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
        // 듣는 중이었으면 마이크를 닫고 울린다(알람·안내가 인식과 겹치지 않게).
        stopListening()
        val reason = engine.session?.alarmReason ?: AlarmReason.WAKE_TIME
        commit(if (reason == AlarmReason.WAKE_TIME && e.sequence > 1) "다시 알림 시간이 됐어요" else reason.title)
        alarm.start()
        if (_ui.value.ttsReady) tts.speak(Texts.alarmSpeech(reason), 100) {}
    }

    // ── 전력 보호 ─────────────────────────────────────────

    /** 모의 전원 값으로 남은 시간을 계산하고 보호 판단을 진행한다. 상태가 바뀔 때만 저장한다. */
    private fun energyTick() {
        val before = engine.state.guard
        val t = engine.energyTick(energy.readings(clock.wallMs()), clock)
        _ui.update { it.copy(estimate = t.estimate) }
        if (engine.state.guard != before) commit()
        t.events.forEach { ev ->
            when (ev) {
                is GuardEvent.ProtectionBeforeWake -> say(Texts.protectionBeforeWake(ev.minutes))
                is GuardEvent.DataLost -> say("전원 측정값이 끊겼어요. 남은 시간을 계산할 수 없어요 — 장치를 껐다고 가정하지 않아요.")
                is GuardEvent.RequestShed -> shed(ev.loads)
                is GuardEvent.WarnEarly, GuardEvent.StarterWarning -> Unit // 알람으로 처리
            }
        }
        val ring = t.ring
        if (ring is TickEvent.Ring) onRing(ring)
    }

    private fun say(text: String) {
        commit(text)
        if (_ui.value.ttsReady && engine.session?.status != NapStatus.RINGING) tts.speak(text, 100) {}
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

    fun setTemperature(c: Double) {
        engine.setPreferences(engine.state.prefs.copy(temperatureC = c.coerceIn(17.0, 28.0)))
        commit()
    }

    fun setProfile(p: VehicleProfile) {
        if (engine.state.active) return
        engine.setPreferences(engine.state.prefs.copy(profile = p))
        commit()
    }

    fun setReserve(r: ReserveSettings) {
        if (engine.state.active) return
        engine.setPreferences(engine.state.prefs.copy(reserve = r.copy(
            evReserveSoc = r.evReserveSoc.coerceIn(10.0, 80.0), campingReserveWh = r.campingReserveWh.coerceIn(50.0, 1_000.0),
        )))
        commit()
    }

    fun setSeat(seat: SeatPreset) {
        engine.setPreferences(engine.state.prefs.copy(seatPreset = seat))
        commit()
    }

    /** 휴식 시작. 주차 상태에서만. 공조·시트 준비는 따로 진행하고, 실패해도 타이머는 계속한다. */
    fun startRest(demo20s: Boolean = false) {
        if (!_ui.value.controlAllowed) return commit("주차 상태에서만 휴식을 시작할 수 있어요")
        val seconds = if (demo20s) 20L else engine.state.prefs.lastMinutes * 60L
        cancelControls()
        stopListening()
        val session = engine.start(seconds, clock, demoShortened = demo20s) ?: return
        engine.recordOriginal(car.read(), clock)
        reservationDone = false
        _ui.update { it.copy(pendingMinutes = null, phoneReservation = false) }
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
        stopListening()
        alarm.stop()
        tts.stop()
        commit("${engine.state.prefs.snoozeMinutes}분 뒤 다시 알려 드릴게요")
    }

    fun wake() {
        if (!engine.wake(clock)) return
        endScene()
        cancelControls()
        stopListening()
        alarm.stop()
        tts.stop()
        refreshRestorePlan()
        commit("원래 설정으로 되돌릴지 확인해 주세요")
    }

    fun stopEarly() {
        if (!engine.stopEarly(clock)) return
        endScene()
        cancelControls()
        stopListening()
        alarm.stop()
        refreshRestorePlan()
        commit("휴식을 끝냈어요")
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

    fun finishRestore() {
        engine.finish() ?: return
        endScene()
        cancelControls()
        stopListening()
        _ui.update { it.copy(restorePlan = emptyList()) }
        commit("기록에 남겼어요")
    }

    // ── 음성 ──────────────────────────────────────────────

    fun listen() {
        if (!_ui.value.micGranted || !_ui.value.sttAvailable) return commit("음성 인식을 쓸 수 없어요 — 버튼을 써 주세요")
        alarm.stop()
        tts.stop()
        // 들을 때의 요청 번호·세션·상태를 기억해 두고, 그사이 상태가 바뀌었으면 결과를 버린다.
        val token = ++listenToken
        val sessionId = engine.session?.id
        val status = engine.session?.status
        fun stale() = token != listenToken || engine.session?.id != sessionId || engine.session?.status != status
        _ui.update { it.copy(listening = true) }
        stt.listen(object : SpeechInput.Listener {
            override fun onSpeechStart() = Unit
            override fun onResult(candidates: List<String>) {
                if (stale()) return
                _ui.update { it.copy(listening = false) }
                onUtterance(candidates.firstOrNull().orEmpty())
            }

            override fun onError(error: SpeechError) {
                if (stale()) return
                _ui.update { it.copy(listening = false, message = "잘 못 들었어요(${error.name})") }
                if (engine.session?.status == NapStatus.RINGING) alarm.start()
            }
        })
    }

    /** 시연 패널 글자 입력도 음성과 같은 길로 처리한다. */
    fun onUtterance(text: String) {
        val status = engine.session?.status
        val parsed = VoiceParser.parse(text)
        val pending = _ui.value.pendingMinutes
        when {
            status == NapStatus.RINGING || status == NapStatus.OVERDUE -> when ((parsed as? Utterance.Command)?.command) {
                NapCommand.WAKE, NapCommand.STOP -> wake()
                NapCommand.SNOOZE -> if (status == NapStatus.RINGING) snooze() else wake()
                else -> { commit("'일어났어' 또는 '5분 더'라고 말해 주세요: \"$text\""); if (status == NapStatus.RINGING) alarm.start() }
            }
            status == NapStatus.RESTING || status == NapStatus.SNOOZED ->
                if ((parsed as? Utterance.Command)?.command in setOf(NapCommand.STOP, NapCommand.WAKE)) stopEarly() else commit("들은 말: \"$text\"")
            status == null && pending != null -> when ((parsed as? Utterance.Command)?.command) {
                NapCommand.YES -> { setMinutes(pending); startRest() }
                NapCommand.NO -> { _ui.update { it.copy(pendingMinutes = null) }; commit("다시 말씀해 주세요") }
                else -> askDuration(parsed, text)
            }
            status == null -> askDuration(parsed, text)
            else -> commit("들은 말: \"$text\"")
        }
    }

    /** 음성 시간은 바로 예약하지 않고 "30분 뒤, 오후 2시 30분에 알려 드릴까요?"로 다시 묻는다. */
    private fun askDuration(parsed: Utterance, text: String) {
        val minutes = (parsed as? Utterance.Duration)?.minutes
        if (minutes == null || minutes !in 1..180) return commit("휴식 시간을 1분~180분으로 말해 주세요: \"$text\"")
        val question = Texts.confirmDuration(minutes, clock.wallMs(), zone)
        _ui.update { it.copy(pendingMinutes = minutes) }
        commit(question)
        if (_ui.value.ttsReady) tts.speak(question, 100) {}
    }

    // ── 시연 패널 ─────────────────────────────────────────

    fun toggleDemoPanel() = _ui.update { it.copy(demoPanel = !it.demoPanel) }

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
        caption("장면 1 · 3/4  보존량 근처로 이동(시간 단축) → 기상 시각 전에 먼저 깨워요 · '5분 더'를 눌러도 한도는 그대로")
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

    private fun runScene(profile: VehicleProfile, demo20s: Boolean = false, steps: suspend (String) -> Unit) {
        if (engine.state.active) return commit("진행 중인 휴식을 먼저 끝내 주세요")
        endScene()
        energy.update { MockEnergyState(failShed = failShed, noResponseShed = noResponseShed) }
        // 모의 차량도 처음 값(24℃·기본 자세)으로 — 복구 화면에서 "원래 값"이 장면마다 같게 보이도록
        car.update { copy(settings = MockCarState().settings) }
        engine.setPreferences(engine.state.prefs.copy(profile = profile, reserve = ReserveSettings(), lastMinutes = 30))
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
        gear.release()
        alarm.stop()
        tts.shutdown()
        stt.shutdown()
    }
}
