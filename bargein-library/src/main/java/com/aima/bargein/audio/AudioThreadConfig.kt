package com.aima.bargein.audio

import android.os.Process
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.asCoroutineDispatcher

object AudioThreadConfig {

    private const val TAG = "BargeInEngine_AudioThreadConfig"

    private class AudioThreadFactory(private val name: String) : ThreadFactory {
        private var counter = 0

        override fun newThread(r: Runnable): Thread {
            return Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "Audio thread started: $name-${counter}")
                r.run()
            }.apply {
                this.name = "$name-${counter++}"
                isDaemon = false
            }
        }
    }

    val audioExecutor = Executors.newFixedThreadPool(
        2,
        AudioThreadFactory("AudioProcessor")
    )

    val audioDispatcher = audioExecutor.asCoroutineDispatcher()

    fun shutdown() {
        audioExecutor.shutdown()
    }
}