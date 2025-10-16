import com.aima.bargein.vad.AdaptiveEnergyVAD
import com.aima.bargein.vad.IVoiceActivityDetector


/**
 * Factory para crear detectores VAD
 */
object VoiceActivityDetectorFactory {

    fun create(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode,
        preference: IVoiceActivityDetector.Type
    ): IVoiceActivityDetector {
        return when (preference) {
            IVoiceActivityDetector.Type.ENERGY -> {
                AdaptiveEnergyVAD(sampleRate)
            }
            IVoiceActivityDetector.Type.NONE -> {
                NoOpVAD()
            }
            else -> {
                // Fallback a Energy VAD
                AdaptiveEnergyVAD(sampleRate)
            }
        }
    }
}

/**
 * VAD No-Op (no hace nada, para casos donde no se necesita VAD)
 */
private class NoOpVAD : IVoiceActivityDetector {
    override fun initialize(sampleRate: Int, mode: IVoiceActivityDetector.AggressivenessMode) = true

    override fun processFrame(samples: ShortArray, length: Int) =
        IVoiceActivityDetector.VadResult(
            hasVoice = false,
            confidence = 0f,
            energyDb = -60f,
            timestamp = System.nanoTime()
        )

    override fun setPlaybackActive(active: Boolean) {}
    override fun release() {}
    override fun getType() = IVoiceActivityDetector.Type.NONE
    override fun getMetrics() = IVoiceActivityDetector.VadMetrics(0, 0, 0f, 0)
}