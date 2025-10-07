package com.aima.bargein.audio

import android.os.Process
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.asCoroutineDispatcher

object AudioThreadConfig {

    private class AudioThreadFactory(private val name: String) : ThreadFactory {
        private var counter = 0

        override fun newThread(r: Runnable): Thread {
            return Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Timber.v("Audio thread started: $name-${counter}")
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