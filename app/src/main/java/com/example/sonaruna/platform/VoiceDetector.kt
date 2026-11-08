package com.example.sonaruna.platform

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tells, frame by frame, when someone speaks into the echo-cancelled microphone while the app talks. The canceller
 * leaves a residue of the app's voice; the detector learns its level and only fires on sound well above it, held for
 * a moment, so the residue and short noises do not cut the voice. Pure Kotlin, so it is unit tested on the computer.
 */
class VoiceDetector(
    private val frameMillis: Int = 20,
    /** At the start the canceller is still adapting: only learn the level, never fire. */
    private val lockoutMillis: Int = 400,
    /** How long the sound must stay above the threshold: 120 ms. */
    private val framesNeeded: Int = 6,
    /** How much louder than the residue: about 11 dB. */
    private val factor: Double = 3.5,
    /**
     * Never fire below this level (16-bit samples), whatever the residue. On the phone the canceller left a residue of
     * 3 to 110 and the person speaking reached 2900 to 11700; 500 made people raise their voice, 250 does not.
     */
    private val minimumLevel: Double = 250.0,
) {
    private var elapsed = 0
    private var residue = 0.0
    private var loud = 0
    private var peak = 0.0

    /** One frame's loudness (RMS of its samples); true once the person is speaking. */
    fun feed(level: Double): Boolean {
        elapsed += frameMillis
        if (elapsed <= lockoutMillis) {
            residue = if (residue == 0.0) level else residue * 0.8 + level * 0.2
            return false
        }
        peak = max(peak, level)
        if (level > threshold()) {
            loud++
            return loud >= framesNeeded
        }
        loud = 0
        // The residue follows the app's voice slowly; loud frames (maybe the person) do not raise it.
        residue = residue * 0.98 + level * 0.02
        return false
    }

    fun threshold(): Double = max(residue * factor, minimumLevel)

    /** For the log: levels only, never audio. */
    fun describe(): String = "residuo=${residue.toInt()} limite=${threshold().toInt()} pico=${peak.toInt()}"

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
