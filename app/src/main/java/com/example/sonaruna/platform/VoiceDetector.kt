package com.example.sonaruna.platform

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tells, frame by frame, when someone speaks into the echo-cancelled microphone while the app talks. The canceller
 * leaves a residue of the app's voice; the detector learns its level and only fires on sound well above it, held for
 * a moment, so the residue, corridor noise and short sounds do not cut the voice. Pure Kotlin, unit tested.
 *
 * Phases: until [arm] (the app's voice really started playing) it only learns the room's noise; then, during the
 * lockout, it learns the residue of the app's voice; only after that can it fire.
 */
class VoiceDetector(
    private val frameMillis: Int = 20,
    /** After the voice starts, the canceller is still adapting: only learn the residue, never fire. */
    private val lockoutMillis: Int = 400,
    /** Fire when [framesNeeded] of the last [window] frames are loud: 200 ms of voice, a dip or two allowed. */
    private val framesNeeded: Int = 10,
    private val window: Int = 12,
    /** How much louder than the residue: about 11 dB. */
    private val factor: Double = 3.5,
    /** How much louder than the room's noise (a busy corridor): about 10 dB. */
    private val noiseFactor: Double = 3.0,
    /**
     * Never fire below this level (16-bit samples). On the phone the canceller left a residue of 3 to 110 and the
     * person speaking reached 2900 to 11700; 500 made people raise their voice, 250 does not.
     */
    private val minimumLevel: Double = 250.0,
    /** Never ask for more than this, so a person speaking normally can always cut the voice. */
    private val maximumThreshold: Double = 1500.0,
    /** When the voice start is never reported, arm anyway after this long. */
    private val armFallbackMillis: Int = 800,
) {
    private var elapsed = 0
    private var armedAt: Int? = null
    private var noise = 0.0
    private var residue = 0.0
    private val lockoutLevels = mutableListOf<Double>()
    private val recent = ArrayDeque<Boolean>()
    private var peak = 0.0

    /** Extra margin after a cut that turned out to be false (nobody answered): the voice is cut less easily. */
    var sensitivity: Double = 1.0

    /** The app's voice started playing now: from here the residue is learned, then the detector may fire. */
    fun arm() {
        if (armedAt == null) armedAt = elapsed
    }

    /** One frame's loudness (RMS of its samples); true once the person is speaking. */
    fun feed(level: Double): Boolean {
        elapsed += frameMillis
        if (armedAt == null && elapsed >= armFallbackMillis) armedAt = elapsed
        val armed = armedAt ?: run {
            noise = if (noise == 0.0) level else noise * 0.9 + level * 0.1
            return false
        }
        if (elapsed - armed <= lockoutMillis) {
            lockoutLevels += level
            // The 25th percentile, not the average: a person who answers right away does not raise it.
            residue = lockoutLevels.sorted()[lockoutLevels.size / 4]
            return false
        }
        peak = max(peak, level)
        val loud = level > threshold()
        recent.addLast(loud)
        if (recent.size > window) recent.removeFirst()
        if (recent.count { it } >= framesNeeded) return true
        // The residue follows the app's voice slowly; loud frames (maybe the person) do not raise it.
        if (!loud) residue = residue * 0.98 + level * 0.02
        return false
    }

    fun threshold(): Double =
        min(max(max(residue * factor, noise * noiseFactor), minimumLevel) * sensitivity, maximumThreshold * sensitivity)

    /** For the log: levels only, never audio. */
    fun describe(): String =
        "ruido=${noise.toInt()} residuo=${residue.toInt()} limite=${threshold().toInt()} pico=${peak.toInt()} " +
            "ms_desde_voz=${armedAt?.let { elapsed - it } ?: -1}"

    /** The loudest frame after the lockout, against the threshold: a cut barely above it may well be noise. */
    val peakLevel: Double get() = peak

    companion object {
        /** RMS of 16-bit little-endian PCM. */
        fun rms(buffer: ByteArray, length: Int): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            for (i in 0 until samples) {
                val sample = (buffer[2 * i].toInt() and 0xFF) or (buffer[2 * i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            return sqrt(sum / samples)
        }
    }
}
