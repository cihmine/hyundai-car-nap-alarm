package com.cihmine.napalarm

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cihmine.napalarm.core.Clock
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

    private val _ui: MutableStateFlow<NapUi> = MutableStateFlow(NapUi(nap = engine.state, car = car.state))
    val ui: StateFlow<NapUi> = _ui

    private val alarm = AlarmSound(viewModelScope)
    private val tts = Voice.output(app) { ok -> _ui.update { it.copy(ttsReady = ok) } }
    private val stt = Voice.input(app) { ok -> _ui.update { it.copy(sttAvailable = ok) } }
    private var ticker: Job? = null
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
            while (isActive) {
                when (val e = engine.tick(clock)) {
                    is TickEvent.Ring -> onRing(e)
                    TickEvent.None -> Unit
                }
                checkReservation()
                _ui.update { it.copy(remainingMs = engine.remainingMs(clock)) }
                delay(250)
            }
        }
    }

    private fun onRing(e: TickEvent.Ring) {
        // 듣는 중이었으면 마이크를 닫고 울린다(알람·안내가 인식과 겹치지 않게).
        stopListening()
        commit(if (e.sequence == 1) "휴식 시간이 끝났어요" else "다시 알림 시간이 됐어요")
        alarm.start()
        if (_ui.value.ttsReady) tts.speak(Texts.ALARM, 100) {}
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
        cancelControls()
        stopListening()
        alarm.stop()
        tts.stop()
        refreshRestorePlan()
        commit("원래 설정으로 되돌릴지 확인해 주세요")
    }

    fun stopEarly() {
        if (!engine.stopEarly(clock)) return
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

    fun setVehicle(v: VehicleState) {
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

    fun time(wallMs: Long) = Texts.clock(wallMs, zone)

    fun onAppStopped() {
        runCatching { store.save(engine.state) }
    }

    override fun onCleared() {
        alarm.stop()
        tts.shutdown()
        stt.shutdown()
    }
}
