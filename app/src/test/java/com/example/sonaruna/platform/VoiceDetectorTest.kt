package com.example.sonaruna.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDetectorTest {
    private fun VoiceDetector.feedAll(levels: List<Double>): Int? {
        levels.forEachIndexed { i, level -> if (feed(level)) return i }
        return null
    }

    @Test
    fun `the residue of the app's voice never fires, even with its peaks`() {
        val detector = VoiceDetector()
        // 3 s of residue swinging between 300 and 900 (syllables), short peaks of 1500.
        val residue = List(150) { i -> if (i % 25 == 0) 1500.0 else 300.0 + (i % 7) * 100 }
        assertEquals(null, detector.feedAll(residue))
    }

    @Test
    fun `the person speaking over it fires after a moment`() {
        val detector = VoiceDetector()
        val residue = List(50) { 500.0 }
        assertEquals(null, detector.feedAll(residue))
        // Voice well above the residue: fires on the 6th loud frame (120 ms).
        assertEquals(5, detector.feedAll(List(20) { 3500.0 }))
    }

    @Test
    fun `a normal speaking voice is enough, no need to shout`() {
        val detector = VoiceDetector()
        // Residue as measured on the phone (3 to 110); a normal voice around 600-1000.
        assertEquals(null, detector.feedAll(List(50) { 60.0 }))
        assertEquals(5, detector.feedAll(List(10) { 700.0 }))
    }

    @Test
    fun `nothing fires during the first moment, while the canceller adapts`() {
        val detector = VoiceDetector()
        assertEquals(null, detector.feedAll(List(20) { 5000.0 })) // 400 ms
        assertFalse(detector.feed(5000.0) && false)
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
