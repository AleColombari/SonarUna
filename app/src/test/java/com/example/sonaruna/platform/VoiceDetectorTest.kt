package com.example.sonaruna.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDetectorTest {
    private fun VoiceDetector.feedAll(levels: List<Double>): Int? {
        levels.forEachIndexed { i, level -> if (feed(level)) return i }
        return null
    }

    private fun armed() = VoiceDetector().apply { arm() }

    @Test
    fun `the residue of the app's voice never fires, even with its peaks`() {
        val detector = armed()
        // 3 s of residue swinging between 300 and 900 (syllables), short peaks of 1500.
        val residue = List(150) { i -> if (i % 25 == 0) 1500.0 else 300.0 + (i % 7) * 100 }
        assertEquals(null, detector.feedAll(residue))
    }

    @Test
    fun `the person speaking over it fires after 200 ms`() {
        val detector = armed()
        assertEquals(null, detector.feedAll(List(50) { 500.0 }))
        // Voice well above the residue: fires on the 10th loud frame (200 ms).
        assertEquals(9, detector.feedAll(List(20) { 3500.0 }))
    }

    @Test
    fun `a normal speaking voice is enough, no need to shout`() {
        val detector = armed()
        // Residue as measured on the phone (3 to 110); a normal voice around 600-1000.
        assertEquals(null, detector.feedAll(List(50) { 60.0 }))
        assertEquals(9, detector.feedAll(List(15) { 700.0 }))
    }

    @Test
    fun `a dip in the voice does not reset it`() {
        val detector = armed()
        assertEquals(null, detector.feedAll(List(50) { 60.0 }))
        val syllables = List(15) { i -> if (i == 4) 100.0 else 700.0 }
        assertEquals(10, detector.feedAll(syllables))
    }

    @Test
    fun `a short noise does not cut the voice`() {
        val detector = armed()
        assertEquals(null, detector.feedAll(List(50) { 60.0 }))
        // A door, a cough: 140 ms loud, then quiet again.
        assertEquals(null, detector.feedAll(List(7) { 4000.0 } + List(30) { 60.0 }))
    }

    @Test
    fun `nothing fires before the voice starts or during the lockout`() {
        val detector = VoiceDetector()
        // Before arm(): only the room's noise is learned (up to the 800 ms fallback).
        assertEquals(null, detector.feedAll(List(39) { 5000.0 }))
        val other = armed()
        assertEquals(null, other.feedAll(List(20) { 5000.0 })) // 400 ms
    }

    @Test
    fun `a noisy corridor raises the threshold`() {
        val quiet = armed()
        quiet.feedAll(List(25) { 60.0 })
        val corridor = VoiceDetector()
        corridor.feedAll(List(20) { 300.0 }) // noise before the voice starts
        corridor.arm()
        corridor.feedAll(List(25) { 60.0 })
        assertTrue(corridor.threshold() > quiet.threshold())
        assertTrue(corridor.threshold() <= 1500.0)
    }

    @Test
    fun `answering right away does not teach the detector the person's voice`() {
        val detector = armed()
        // The lockout: mostly residue, but the person already started in its last part.
        detector.feedAll(List(14) { 80.0 } + List(6) { 3000.0 })
        assertTrue(detector.threshold() < 1000.0)
    }

    @Test
    fun `a false cut makes the next ones harder`() {
        val detector = armed()
        detector.feedAll(List(25) { 60.0 })
        val before = detector.threshold()
        detector.sensitivity = 1.5
        assertEquals(before * 1.5, detector.threshold(), 0.001)
    }

    @Test
    fun `rms of 16-bit samples`() {
        val samples = shortArrayOf(1000, -1000, 1000, -1000)
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[2 * i] = (s.toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
        assertEquals(1000.0, VoiceDetector.rms(bytes, bytes.size), 0.001)
        assertTrue(VoiceDetector.rms(ByteArray(0), 0) == 0.0)
    }
}
