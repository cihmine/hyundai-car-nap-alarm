package com.cihmine.napalarm.speech

/** 음성 인식. 결과는 후보 목록(첫 번째가 가장 유력, Pleos STT 는 1개) */
interface SpeechInput {
    fun listen(listener: Listener)
    fun cancel()

    interface Listener {
        fun onSpeechStart()
        fun onResult(candidates: List<String>)
        fun onError(error: SpeechError)
    }
}

enum class SpeechError { NO_MATCH, TIMEOUT, NETWORK, AUDIO_FOCUS_LOST, PERMISSION, OTHER }

/** 음성 안내. [ratePercent] 는 100 이 기본 속도 */
interface SpeechOutput {
    fun speak(text: String, ratePercent: Int, onDone: () -> Unit)
    fun stop()
}
