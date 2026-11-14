package com.cihmine.napalarm.core

import kotlinx.serialization.Serializable

/** 앱 전체 저장 상태. 바뀔 때마다 통째로 저장한다. */
@Serializable
data class NapState(
    val session: NapSession? = null,
    val prefs: RestPreferences = RestPreferences(),
    val original: OriginalVehicleState? = null,
    /** 이번 휴식에서 앱이 실제로(모의 응답 기준) 바꾼 값 */
    val appSet: VehicleSettings = VehicleSettings(),
    val actions: List<ControlAction> = emptyList(),
    val history: List<NapRecord> = emptyList(),
    val nextId: Long = 1,
    /** 이번 휴식의 전력 보호 상태(기상 타이머와 별개). 휴식이 끝나면 비운다. */
    val guard: EnergyGuardState? = null,
    /** 충전 대기 모드 감시(타이머 없음). 모드가 꺼져 있으면 null */
    val chargeWait: ChargeWaitState? = null,
) {
    val active: Boolean get() = session != null && session.status != NapStatus.FINISHED
}

sealed interface TickEvent {
    data object None : TickEvent

    /** 이 세션의 [sequence] 번째 알람을 울린다. 같은 세션·번호는 한 번만 나온다. */
    data class Ring(val sessionId: String, val sequence: Int) : TickEvent
}

/** 전력 감시 한 번의 결과: 화면에 보일 추정치, 보호 이벤트, (필요하면) 알람 */
data class EnergyTick(
    val estimate: RuntimeEstimate,
    val events: List<GuardEvent> = emptyList(),
    val ring: TickEvent = TickEvent.None,
)

/** 재시작 뒤 복원 결과 */
enum class RestoreOutcome {
    NOTHING,

    /** 남은 시간이 있어 이어서 진행 */
    RESUMED,

    /** 재부팅·시각 변경으로 남은 시간을 실제 시각 기준으로 다시 계산 — 사용자 확인 필요 */
    RESUMED_NEEDS_RECONFIRM,

    /** 꺼져 있는 동안 예정 시각이 지남(알람 미실행) */
    OVERDUE,

    /** 알람이 울리던 중 재시작 */
    STILL_RINGING,
}

/**
 * 낮잠 타이머 상태 전이(계획서 5절). 시각은 [Clock] 으로 받는다.
 * 휴식 중 → 알람 → (다시 알림 → 알람)* → 복구 확인 → 끝. 중도 종료는 휴식 중 → 복구 확인.
 */
class NapEngine(state: NapState = NapState()) {
    var state: NapState = state
        private set

    val session: NapSession? get() = state.session

    // ── 시작 ──────────────────────────────────────────────

    /** 휴식 시작. 이미 진행 중인 휴식이 있으면 null. [seconds] 는 1분~180분(시연 단축은 예외). */
    fun start(seconds: Long, clock: Clock, demoShortened: Boolean = false): NapSession? {
        if (state.active) return null
        require(demoShortened || seconds in MIN_SECONDS..MAX_SECONDS) { "휴식 시간은 1~180분" }
        val wall = clock.wallMs()
        val mono = clock.monotonicMs()
        val session = NapSession(
            id = "nap-${state.nextId}", requestedSeconds = seconds,
            startedWallMs = wall, startedMonoMs = mono, startedBootCount = clock.bootCount(),
            targetWallMs = wall + seconds * 1000, targetMonoMs = mono + seconds * 1000,
            demoShortened = demoShortened,
        )
        state = state.copy(
            session = session, nextId = state.nextId + 1, original = null, appSet = VehicleSettings(), actions = emptyList(),
            guard = EnergyGuard.start(state.prefs.profile, wall),
        )
        return session
    }

    fun setPreferences(prefs: RestPreferences) {
        state = state.copy(prefs = prefs)
    }

    // ── 충전 대기 모드(타이머 없음) ────────────────────────

    /** 충전 대기 감시를 켜거나 끈다(모드 켜짐 여부에 맞춘다). 이미 같은 상태면 그대로 둔다. */
    fun syncChargeWait(on: Boolean, clock: Clock) {
        val now = state.chargeWait
        if (on && now == null) state = state.copy(chargeWait = ChargeWaitState(clock.wallMs()))
        if (!on && now != null) state = state.copy(chargeWait = null)
    }

    /** 목표 충전률(%)을 바꾼다. 감시 중이면 판단을 처음부터 다시 한다(지금 충전률이 새 목표보다 낮은 것을 본 뒤에 울린다). */
    fun setChargeTarget(soc: Int) {
        val v = soc.coerceIn(ChargeWake.MIN, ChargeWake.MAX)
        state = state.copy(prefs = state.prefs.copy(chargeTargetSoc = v), chargeWait = state.chargeWait?.copy(armed = false, done = false, ringing = false, reachedSoc = null))
    }

    /**
     * 충전 대기 감시 한 단계. 전기차이고 최근(오래되지 않은) 충전률일 때만 본다. 목표에 닿아 알람을 시작하면 true.
     */
    fun chargeTick(readings: Map<PowerSource, EnergyReading>, clock: Clock): Boolean {
        val w = state.chargeWait ?: return false
        if (state.prefs.profile.managed != PowerSource.EV_TRACTION) return false
        val soc = readings[PowerSource.EV_TRACTION]?.takeIf { clock.wallMs() - it.sampledAtMs <= RuntimeEstimator.STALE_MS }?.socPercent
        val (next, rang) = ChargeWatch.step(w, soc, state.prefs.chargeTargetSoc)
        if (next != w) state = state.copy(chargeWait = next)
        return rang
    }

    /** 충전 완료 알람 끄기(감시 상태는 남김 — 모드를 끄면 비운다) */
    fun ackChargeAlarm() {
        state.chargeWait?.let { state = state.copy(chargeWait = it.copy(ringing = false)) }
    }

    // ── 시간 ──────────────────────────────────────────────

    /** 남은 시간(ms). 같은 부팅이면 단조 시계, 아니면 실제 시각으로 계산한다. */
    fun remainingMs(clock: Clock): Long? {
        val s = state.session ?: return null
        if (s.status != NapStatus.RESTING && s.status != NapStatus.SNOOZED) return null
        val left = if (sameBoot(s, clock)) s.targetMonoMs - clock.monotonicMs() else s.targetWallMs - clock.wallMs()
        return left.coerceAtLeast(0)
    }

    /**
     * 세션을 시작한 부팅과 같은 부팅인지. 부팅 횟수가 다르거나(알 수 있을 때) 단조 시계가 시작값보다 작으면 재부팅이다.
     * 가동 시간만 보면 재부팅 뒤 오래 켜 둔 경우를 같은 부팅으로 오판한다.
     */
    private fun sameBoot(s: NapSession, clock: Clock): Boolean {
        val boot = clock.bootCount()
        val bootKnown = boot >= 0 && s.startedBootCount >= 0
        return clock.monotonicMs() >= s.startedMonoMs && (!bootKnown || boot == s.startedBootCount)
    }

    /** 지금까지의 실제 경과(ms) */
    private fun elapsed(s: NapSession, clock: Clock): Long =
        (if (sameBoot(s, clock)) clock.monotonicMs() - s.startedMonoMs else clock.wallMs() - s.startedWallMs).coerceAtLeast(0)

    /** 주기적으로 불러 목표 시각 도달을 확인한다. 끝난 세션·이미 울린 번호는 다시 울리지 않는다. */
    fun tick(clock: Clock): TickEvent {
        val s = state.session ?: return TickEvent.None
        if (s.status != NapStatus.RESTING && s.status != NapStatus.SNOOZED) return TickEvent.None
        if ((remainingMs(clock) ?: return TickEvent.None) > 0) return TickEvent.None
        return ring(s, AlarmReason.WAKE_TIME)
    }

    private fun ring(s: NapSession, reason: AlarmReason): TickEvent.Ring {
        val next = s.copy(status = NapStatus.RINGING, alarmSequence = s.alarmSequence + 1, alarmReason = reason)
        state = state.copy(session = next)
        return TickEvent.Ring(next.id, next.alarmSequence)
    }

    // ── 전력 보호 ─────────────────────────────────────────

    /**
     * 전원 측정값으로 남은 사용 시간을 계산하고 보호 판단을 한 단계 진행한다(주기적으로 부른다).
     * 먼저 깨우기·장치 종료·12V 경고는 휴식 중이면 알람으로 울린다. 장치 종료 요청은 [EnergyTick.events] 로 앱이 보낸다.
     */
    fun energyTick(readings: Map<PowerSource, EnergyReading>, clock: Clock): EnergyTick {
        val profile = state.guard?.profile ?: state.prefs.profile
        val reserve = state.prefs.reserve
        // 관리 전원이 없으면(12V 만 있는 차) 12V 로 "계산 불가" 이유를 보여 준다
        val source = profile.managed ?: profile.sources.firstOrNull()
        val estimate = RuntimeEstimator.estimate(source?.let { readings[it] }, source, reserve, clock.wallMs())
        val s = state.session
        val g = state.guard
        if (s == null || s.status == NapStatus.FINISHED || g == null) return EnergyTick(estimate)
        val starterWarning = PowerSource.STARTER_12V in profile.sources && readings[PowerSource.STARTER_12V]?.vehicleWarning == true
        val (next, events) = EnergyGuard.step(g, estimate, starterWarning, remainingMs(clock), reserve, clock.wallMs())
        state = state.copy(guard = next)
        val canRing = s.status == NapStatus.RESTING || s.status == NapStatus.SNOOZED
        val reason = when {
            events.any { it is GuardEvent.RequestShed } -> AlarmReason.ENERGY_SHED
            events.any { it is GuardEvent.WarnEarly } -> AlarmReason.ENERGY_WARNING
            events.any { it is GuardEvent.StarterWarning } -> AlarmReason.STARTER_WARNING
            else -> null
        }
        val ringEvent = if (canRing && reason != null) ring(s, reason) else TickEvent.None
        // 이미 울리는 중에 장치를 끄게 되면 새로 울리지 않고 알람 제목만 바꾼다
        if (s.status == NapStatus.RINGING && reason == AlarmReason.ENERGY_SHED) state = state.copy(session = s.copy(alarmReason = reason))
        return EnergyTick(estimate, events, ringEvent)
    }

    /** 장치 종료 요청 결과(모의 장치 응답). 끝난 휴식이면 버린다. */
    fun onShedResult(result: ControlState, clock: Clock): GuardStatus? {
        val g = state.guard ?: return null
        val next = EnergyGuard.onShedResult(g, result, clock.wallMs())
        state = state.copy(guard = next)
        return next.status
    }

    /** 전력 보호로 끈 장치가 걸린 복구 항목 — 자동으로 다시 켜지 않는다 */
    private fun protectedTargets(): Set<ControlTarget> {
        val g = state.guard ?: return emptySet()
        return if (g.status.shed && Load.EV_CLIMATE in g.shedLoads) setOf(ControlTarget.CLIMATE) else emptySet()
    }

    /**
     * 다시 알림. 알람이 울리는 중일 때만. 새 목표 시각을 저장하고 지금 알람은 멈춘다(앱이 소리를 끈다).
     * 전력 알림을 미룬 경우엔 소리만 미룬다: 원래 기상 시각보다 늦추지 않고, 전력 보호 한도도 그대로다(EnergyGuard 는 따로 돈다).
     */
    fun snooze(clock: Clock): NapSession? {
        val s = state.session?.takeIf { it.status == NapStatus.RINGING } ?: return null
        val ms = state.prefs.snoozeMinutes * 60_000L
        val energyAlarm = s.alarmReason != AlarmReason.WAKE_TIME
        val wall = clock.wallMs() + ms
        val mono = clock.monotonicMs() + ms
        val next = s.copy(
            status = NapStatus.SNOOZED, snoozeCount = s.snoozeCount + 1,
            targetWallMs = if (energyAlarm) minOf(wall, s.targetWallMs) else wall,
            targetMonoMs = if (energyAlarm) minOf(mono, s.targetMonoMs) else mono,
        )
        state = state.copy(session = next)
        return next
    }

    /** "일어났어". 알람 중이면 기상 확인, 꺼진 사이 시각이 지난 경우면 그 사실대로 기록한다. */
    fun wake(clock: Clock): Boolean {
        val s = state.session ?: return false
        val reason = when (s.status) {
            NapStatus.RINGING -> EndReason.WOKE
            NapStatus.OVERDUE -> EndReason.MISSED_WHILE_OFF
            else -> return false
        }
        state = state.copy(session = s.copy(status = NapStatus.RESTORE_PENDING, endReason = reason, endedWallMs = clock.wallMs(), elapsedMs = elapsed(s, clock)))
        return true
    }

    /**
     * 휴식 중에 전체 휴식 시간을 [totalSeconds] 로 바꾼다(시작 시각 기준). 다시 알림 대기·시연 단축 중에는 바꾸지 않는다.
     * 바꾼 뒤 남은 시간이 1분보다 짧아지면 거절한다(이미 지난 시각으로 옮기지 않음). 새 목표 시각은 저장된다.
     */
    fun setDuration(totalSeconds: Long, clock: Clock): NapSession? {
        val s = state.session?.takeIf { it.status == NapStatus.RESTING && !it.demoShortened } ?: return null
        if (totalSeconds !in MIN_SECONDS..MAX_SECONDS) return null
        val deltaMs = (totalSeconds - s.requestedSeconds) * 1000
        val next = s.copy(requestedSeconds = totalSeconds, targetWallMs = s.targetWallMs + deltaMs, targetMonoMs = s.targetMonoMs + deltaMs)
        val left = if (sameBoot(next, clock)) next.targetMonoMs - clock.monotonicMs() else next.targetWallMs - clock.wallMs()
        if (left < MIN_SECONDS * 1000) return null
        state = state.copy(session = next, prefs = state.prefs.copy(lastMinutes = (totalSeconds / 60).toInt()))
        return next
    }

    /** 시간 전에 종료 */
    fun stopEarly(clock: Clock): Boolean {
        val s = state.session?.takeIf { it.status == NapStatus.RESTING || it.status == NapStatus.SNOOZED } ?: return false
        state = state.copy(session = s.copy(status = NapStatus.RESTORE_PENDING, endReason = EndReason.STOPPED_EARLY, endedWallMs = clock.wallMs(), elapsedMs = elapsed(s, clock)))
        return true
    }

    /** 복구 확인(또는 건너뛰기) 뒤 끝. 기록을 남기고 다음 휴식을 받을 수 있게 비운다. */
    fun finish(): NapRecord? {
        val s = state.session?.takeIf { it.status == NapStatus.RESTORE_PENDING } ?: return null
        val record = NapRecord(
            s.id, s.requestedSeconds, s.startedWallMs, s.endedWallMs ?: s.startedWallMs, s.endReason!!, s.snoozeCount, s.demoShortened,
            actualMs = s.elapsedMs ?: ((s.endedWallMs ?: s.startedWallMs) - s.startedWallMs).coerceAtLeast(0),
            profile = state.guard?.profile, guardOutcome = state.guard?.status,
        )
        state = state.copy(session = null, original = null, appSet = VehicleSettings(), guard = null, history = (state.history + record).takeLast(MAX_HISTORY))
        return record
    }

    /** 재부팅·시각 변경 뒤 남은 일정을 사용자가 확인함 */
    fun confirmSchedule() {
        state.session?.let { state = state.copy(session = it.copy(needsReconfirm = false)) }
    }

    // ── 재시작 복원 ───────────────────────────────────────

    /**
     * 앱이 다시 켜졌을 때 한 번 부른다. 꺼진 동안 알람이 울리지 않았는데 정상 기상으로 기록하지 않는다.
     * 같은 부팅이면 단조 시계로, 재부팅(단조 시계가 시작값보다 작음)이면 실제 시각으로 남은 시간을 계산하고 확인을 요청한다.
     */
    fun onAppRestarted(clock: Clock): RestoreOutcome {
        val s = state.session ?: return RestoreOutcome.NOTHING
        when (s.status) {
            NapStatus.RINGING -> return RestoreOutcome.STILL_RINGING
            NapStatus.RESTING, NapStatus.SNOOZED -> Unit
            else -> return RestoreOutcome.NOTHING
        }
        val wall = clock.wallMs()
        val mono = clock.monotonicMs()
        val sameBoot = sameBoot(s, clock)
        // 같은 부팅인데 실제 시각과 단조 시계의 차이가 크게 달라졌으면 시각이 바뀐 것
        val clockJumped = sameBoot && kotlin.math.abs((wall - mono) - (s.startedWallMs - s.startedMonoMs)) > CLOCK_JUMP_MS
        var next = s
        if (!sameBoot) {
            // 재부팅: 실제 시각 기준으로 단조 시계 값을 다시 잡는다
            next = next.copy(
                startedMonoMs = mono - (wall - s.startedWallMs), targetMonoMs = mono + (s.targetWallMs - wall),
                startedBootCount = clock.bootCount(),
            )
        } else if (clockJumped) {
            // 시각만 바뀜: 타이머는 단조 시계 그대로, 표시·비교용 실제 시각을 지금 시계에 맞춘다
            next = next.copy(startedWallMs = wall - (mono - s.startedMonoMs), targetWallMs = wall + (s.targetMonoMs - mono))
        }
        val left = if (sameBoot) next.targetMonoMs - mono else next.targetWallMs - wall
        if (left <= 0) {
            state = state.copy(session = next.copy(status = NapStatus.OVERDUE))
            return RestoreOutcome.OVERDUE
        }
        val reconfirm = !sameBoot || clockJumped
        state = state.copy(session = next.copy(needsReconfirm = reconfirm || next.needsReconfirm))
        return if (reconfirm) RestoreOutcome.RESUMED_NEEDS_RECONFIRM else RestoreOutcome.RESUMED
    }

    // ── 차량 설정(모의) 기록 ──────────────────────────────

    fun recordOriginal(settings: VehicleSettings, clock: Clock) {
        val s = state.session ?: return
        state = state.copy(original = OriginalVehicleState(s.id, settings, clock.wallMs()))
    }

    /** 이 세션에 아직 제어 요청을 보내도 되는지(끝난 휴식의 준비·복구를 차량에 보내지 않게) */
    fun acceptsControl(sessionId: String, isRestore: Boolean): Boolean {
        val s = state.session ?: return false
        if (s.id != sessionId) return false
        return if (isRestore) s.status == NapStatus.RESTORE_PENDING else s.status in setOf(NapStatus.RESTING, NapStatus.SNOOZED, NapStatus.RINGING)
    }

    /**
     * 제어 요청을 차량에 보내기 **전에** 기록한다. 준비 요청이면 바꾸려는 값을 앱이 바꾼 값으로 미리 적어 둔다 —
     * 요청 도중 앱이 꺼져도 복구 후보를 잃지 않게(차량 값과 기록이 어긋나지 않게). 실패하면 [completeAction] 이 되돌린다.
     */
    fun requestAction(sessionId: String, target: ControlTarget, requested: VehicleSettings, isRestore: Boolean = false, note: String? = null): ControlAction? {
        val s = state.session?.takeIf { it.id == sessionId && it.status != NapStatus.FINISHED } ?: return null
        val action = ControlAction(
            "act-${state.nextId}", s.id, target, requested.valueOf(target) ?: "-", ControlState.REQUESTED, isRestore, note,
            previous = state.appSet,
        )
        val appSet = if (isRestore) state.appSet else state.appSet.with(target, requested)
        state = state.copy(actions = state.actions + action, appSet = appSet, nextId = state.nextId + 1)
        return action
    }

    /** 요청 결과. 끝난 휴식의 늦은 응답은 버린다. 준비 요청이 실패하면 앱이 바꾼 값에서 뺀다. */
    fun completeAction(actionId: String, result: ControlState): ControlAction? {
        val action = state.actions.firstOrNull { it.id == actionId && it.state == ControlState.REQUESTED } ?: return null
        if (state.session?.id != action.sessionId) return null
        val done = action.copy(state = result)
        val appSet = if (!action.isRestore && result != ControlState.ACKNOWLEDGED) {
            state.appSet.with(action.target, action.previous ?: VehicleSettings())
        } else {
            state.appSet
        }
        state = state.copy(actions = state.actions.map { if (it.id == actionId) done else it }, appSet = appSet)
        return done
    }

    /** 요청과 결과를 한 번에 기록(시험·동기 처리용) */
    fun recordAction(sessionId: String, target: ControlTarget, requested: VehicleSettings, result: ControlState, isRestore: Boolean = false, note: String? = null): ControlAction? {
        val a = requestAction(sessionId, target, requested, isRestore, note) ?: return null
        return completeAction(a.id, result)
    }

    fun restorePlan(current: VehicleSettings): List<RestoreItem> = RestorePlanner.plan(state.original, state.appSet, current, protectedTargets())

    companion object {
        const val MIN_SECONDS = 60L
        const val MAX_SECONDS = 180 * 60L
        const val CLOCK_JUMP_MS = 60_000L
        const val MAX_HISTORY = 50
    }
}

object NapCodec {
    fun encode(state: NapState): String = NapJson.json.encodeToString(NapState.serializer(), state)
    fun decode(text: String): NapState = NapJson.json.decodeFromString(NapState.serializer(), text)
}
