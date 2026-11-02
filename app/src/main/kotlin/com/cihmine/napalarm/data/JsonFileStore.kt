package com.cihmine.napalarm.data

import android.util.AtomicFile
import android.util.Log
import java.io.File

/**
 * 앱 전용 폴더의 JSON 파일. 쓰다가 꺼져도 이전 내용이 남도록 AtomicFile 로 쓴다.
 * 읽기에 실패한 파일은 지우지 않고 옆에 백업해 둔다(빈 상태로 덮어써 기록을 잃지 않게).
 */
class JsonFileStore<T>(dir: File, private val name: String, private val encode: (T) -> String, private val decode: (String) -> T) {
    private val raw = File(dir, name)
    private val file = AtomicFile(raw)

    fun load(): T? {
        if (!raw.exists()) return null
        return runCatching { decode(file.readFully().toString(Charsets.UTF_8)) }.getOrElse {
            Log.e("NapAlarm", "$name 을 읽지 못함 — 백업 후 새로 시작", it)
            runCatching { raw.copyTo(File(raw.parentFile, "$name.broken-${System.currentTimeMillis()}"), overwrite = false) }
            null
        }
    }

    fun save(value: T) {
        val out = file.startWrite()
        try {
            out.write(encode(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
