package com.cihmine.napalarm.speech

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 앱 안 알람 소리. 갑자기 크게 울리지 않도록 작은 소리에서 시작해 조금씩 키우고, [stop] 으로 바로 멈춘다.
 * 앱이 실행 중이고 차량 시스템이 깨어 있을 때만 울린다(전원 OFF·강제 종료 시 보장하지 않음).
 */
class AlarmSound(private val scope: CoroutineScope) {
    private var job: Job? = null

    val isPlaying: Boolean get() = job?.isActive == true

    fun start() {
        if (isPlaying) return
        job = scope.launch {
            var volume = 30
            while (isActive) {
                val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, volume) }.getOrNull()
                try {
                    repeat(3) {
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                        delay(450)
                    }
                } finally {
                    tone?.release()
                }
                delay(1_200)
                volume = (volume + 8).coerceAtMost(85)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
