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
) {
    val active: Boolean get() = session != null && session.status != NapStatus.FINISHED
}

sealed interface TickEvent {
    data object None : TickEvent

    /** 이 세션의 [sequence] 번째 알람을 울린다. 같은 세션·번호는 한 번만 나온다. */
    data class Ring(val sessionId: String, val sequence: Int) : TickEvent
}

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
            startedWallMs = wall, startedMonoMs = mono,
            targetWallMs = wall + seconds * 1000, targetMonoMs = mono + seconds * 1000,
            demoShortened = demoShortened,
        )
        state = state.copy(session = session, nextId = state.nextId + 1, original = null, appSet = VehicleSettings(), actions = emptyList())
        return session
    }

    fun setPreferences(prefs: RestPreferences) {
        state = state.copy(prefs = prefs)
    }

    // ── 시간 ──────────────────────────────────────────────

    /** 남은 시간(ms). 같은 부팅이면 단조 시계, 아니면 실제 시각으로 계산한다. */
    fun remainingMs(clock: Clock): Long? {
        val s = state.session ?: return null
        if (s.status != NapStatus.RESTING && s.status != NapStatus.SNOOZED) return null
        val mono = clock.monotonicMs()
        val left = if (mono >= s.startedMonoMs) s.targetMonoMs - mono else s.targetWallMs - clock.wallMs()
        return left.coerceAtLeast(0)
    }

    /** 주기적으로 불러 목표 시각 도달을 확인한다. 끝난 세션·이미 울린 번호는 다시 울리지 않는다. */
    fun tick(clock: Clock): TickEvent {
        val s = state.session ?: return TickEvent.None
        if (s.status != NapStatus.RESTING && s.status != NapStatus.SNOOZED) return TickEvent.None
        if ((remainingMs(clock) ?: return TickEvent.None) > 0) return TickEvent.None
        val next = s.copy(status = NapStatus.RINGING, alarmSequence = s.alarmSequence + 1)
        state = state.copy(session = next)
        return TickEvent.Ring(next.id, next.alarmSequence)
    }

    /** 다시 알림. 알람이 울리는 중일 때만. 새 목표 시각을 저장하고 지금 알람은 멈춘다(앱이 소리를 끈다). */
    fun snooze(clock: Clock): NapSession? {
        val s = state.session?.takeIf { it.status == NapStatus.RINGING } ?: return null
        val ms = state.prefs.snoozeMinutes * 60_000L
        val next = s.copy(
            status = NapStatus.SNOOZED, snoozeCount = s.snoozeCount + 1,
            targetWallMs = clock.wallMs() + ms, targetMonoMs = clock.monotonicMs() + ms,
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
        state = state.copy(session = s.copy(status = NapStatus.RESTORE_PENDING, endReason = reason, endedWallMs = clock.wallMs()))
        return true
    }

    /** 시간 전에 종료 */
    fun stopEarly(clock: Clock): Boolean {
        val s = state.session?.takeIf { it.status == NapStatus.RESTING || it.status == NapStatus.SNOOZED } ?: return false
        state = state.copy(session = s.copy(status = NapStatus.RESTORE_PENDING, endReason = EndReason.STOPPED_EARLY, endedWallMs = clock.wallMs()))
        return true
    }

    /** 복구 확인(또는 건너뛰기) 뒤 끝. 기록을 남기고 다음 휴식을 받을 수 있게 비운다. */
    fun finish(): NapRecord? {
        val s = state.session?.takeIf { it.status == NapStatus.RESTORE_PENDING } ?: return null
        val record = NapRecord(s.id, s.requestedSeconds, s.startedWallMs, s.endedWallMs ?: s.startedWallMs, s.endReason!!, s.snoozeCount, s.demoShortened)
        state = state.copy(session = null, original = null, appSet = VehicleSettings(), history = (state.history + record).takeLast(MAX_HISTORY))
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
        val sameBoot = mono >= s.startedMonoMs
        // 같은 부팅인데 실제 시각과 단조 시계의 차이가 크게 달라졌으면 시각이 바뀐 것
        val clockJumped = sameBoot && kotlin.math.abs((wall - mono) - (s.startedWallMs - s.startedMonoMs)) > CLOCK_JUMP_MS
        var next = s
        if (!sameBoot) {
            // 재부팅: 실제 시각 기준으로 단조 시계 값을 다시 잡는다
            next = next.copy(startedMonoMs = mono - (wall - s.startedWallMs), targetMonoMs = mono + (s.targetWallMs - wall))
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

    /**
     * 제어 요청 결과를 남긴다. 다른 세션(이미 끝난 휴식)의 늦은 응답은 버린다.
     * 휴식 준비 요청이 모의 적용되면 [NapState.appSet] 에 반영한다(복구 후보 판단용).
     */
    fun recordAction(sessionId: String, target: ControlTarget, requested: VehicleSettings, result: ControlState, isRestore: Boolean = false, note: String? = null): ControlAction? {
        val s = state.session?.takeIf { it.id == sessionId && it.status != NapStatus.FINISHED } ?: return null
        val action = ControlAction("act-${state.nextId}", s.id, target, requested.valueOf(target) ?: "-", result, isRestore, note)
        var appSet = state.appSet
        if (result == ControlState.ACKNOWLEDGED && !isRestore) {
            appSet = when (target) {
                ControlTarget.CLIMATE -> appSet.copy(temperatureC = requested.temperatureC)
                ControlTarget.SEAT -> appSet.copy(seat = requested.seat)
            }
        }
        state = state.copy(actions = state.actions + action, appSet = appSet, nextId = state.nextId + 1)
        return action
    }

    fun restorePlan(current: VehicleSettings): List<RestoreItem> = RestorePlanner.plan(state.original, state.appSet, current)

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
