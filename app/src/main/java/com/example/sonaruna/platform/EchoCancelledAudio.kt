package com.example.sonaruna.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * Hearing the person while the app speaks, without hearing the app: the same acoustic echo cancellation that voice
 * calls use. The app's voice plays on the call path (still on the loudspeaker) and the microphone records on the call
 * path with the phone's echo canceller.
 *
 * Two uses:
 * - **Cleaned audio for the recognizer** (Android 13+, Google with pt-BR offline): one recording runs while the
 *   conversation is on screen and each recognition gets a fresh pipe fed from it ([start], [newStream]). The call path
 *   is held the whole time ([holdCallPath]).
 * - **Detector** (the usual case: the recognizer refuses that audio): while the app speaks, the same recorder only
 *   measures loudness to notice the person speaking over the voice ([startDetecting]). The recorder is created once
 *   ([prepare]) and only paused between replies ([pauseDetecting]), so the canceller stays attached; the call path is
 *   entered for each speech ([enterForSpeech]) and left before the plain recognizer listens, because in call mode
 *   Google's microphone got the call's processing and sometimes heard almost nothing.
 *
 * Nothing is saved: the audio only goes to the recognizer or to the loudness meter.
 */
class EchoCancelledAudio(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Stopping a recorder blocks; it runs here, never on the main thread. */
    private val micExecutor = Executors.newSingleThreadExecutor { Thread(it, "sonaruna-mic-control") }

    @Volatile
    private var output: FileOutputStream? = null
    private var record: AudioRecord? = null
    private var canceller: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null
    @Volatile
    private var thread: Thread? = null

    /** Recording (the pump thread runs). */
    @Volatile
    private var recording = false

    /** Feeding the recognizer ([start]); otherwise recording only for the detector. */
    @Volatile
    private var feeding = false

    /** Turned off for good once a recognizer refused the cleaned audio; the app then uses the plain microphone. */
    var unsupported = false
        private set

    /** The recognizer may take the cleaned audio (Android 13+, not refused yet). */
    val supported: Boolean
        get() = !unsupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canDetect

    /** The phone has an echo canceller: the cleaned microphone can at least tell when the person speaks. */
    val canDetect: Boolean get() = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

    /** Running and feeding the recognizer. */
    val active: Boolean get() = feeding && recording && !unsupported

    @Volatile
    private var detector: VoiceDetector? = null

    @Volatile
    private var onVoice: ((VoiceDetector) -> Unit)? = null

    /** Extra margin after false cuts, kept for the rest of the conversation. */
    @Volatile
    var sensitivity: Double = 1.0

    private var callPathHeld = false
    private var inCallPath = false

    /** Keeps the call path on for the whole conversation (cleaned audio for the recognizer), or lets it go. */
    @Synchronized
    fun holdCallPath(on: Boolean) {
        if (on == callPathHeld) return
        callPathHeld = on
        if (on) enterCallPath() else leaveCallPath()
    }

    /**
     * Call path for the app's voice, so the canceller knows what plays. Waits a moment when it switches now: the start
     * of a sentence played during the switch came out in a worse voice.
     */
    suspend fun enterForSpeech() {
        val switched = synchronized(this) {
            // A pause still queued from the previous reply must not take the call path away from this one.
            detectSession++
            if (inCallPath) false else {
                enterCallPath()
                true
            }
        }
        if (switched) delay(CALL_PATH_SETTLE_MILLIS)
    }

    /** Back to the normal path before the plain recognizer listens (unless held for the cleaned audio). */
    @Synchronized
    fun leaveForListening() {
        if (!callPathHeld && !feeding) leaveCallPath()
    }

    /** Creates the recorder and its effects once, without recording. False when the phone cannot. */
    @SuppressLint("MissingPermission") // The caller only prepares this after RECORD_AUDIO was granted.
    @Synchronized
    fun prepare(): Boolean {
        if (record != null) return true
        if (!canDetect) return false
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimum * 4,
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            Log.i(TAG, "Cancelamento de eco: gravador indisponível")
            return false
        }
        canceller = runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        suppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
        record = recorder
        Log.i(TAG, "Cancelamento de eco preparado: canceller=${canceller?.enabled == true} ruido=${suppressor?.enabled == true}")
        return true
    }

    /** Starts the recording that feeds the recognizer. False when the phone cannot do it. */
    @Synchronized
    fun start(): Boolean {
        if (!supported || !prepare()) return false
        feeding = true
        if (!startRecording()) {
            feeding = false
            return false
        }
        return true
    }

    /**
     * While the app speaks: measures the cleaned microphone and calls [voice] (on the main thread, once) as soon as the
     * person is heard speaking over it. The detector waits for [voiceStarted]. False when the phone cannot do it.
     */
    @Synchronized
    fun startDetecting(voice: (VoiceDetector) -> Unit): Boolean {
        if (!prepare() || !startRecording()) return false
        detectSession++
        detector = VoiceDetector().also { it.sensitivity = sensitivity }
        onVoice = voice
        return true
    }

    /** The app's voice really started playing: the detector learns its residue from now on. */
    fun voiceStarted() {
        detector?.arm()
    }

    /**
     * Ends the detection and frees the microphone for the plain recognizer, off the main thread, then runs [then] on
     * the main thread. The recorder is only stopped, not released, so the next reply does not build it again.
     */
    fun pauseDetecting(then: () -> Unit) {
        val session = synchronized(this) {
            detector = null
            onVoice = null
            detectSession
        }
        if (feeding) {
            main.post(then)
            return
        }
        micExecutor.execute {
            // A new detection or speech started meanwhile (the next reply): leave its recording and call path alone.
            val stopped = synchronized(this) {
                if (session != detectSession || feeding) null else takeRecording()
            }
            if (stopped != null) {
                stopped()
                synchronized(this) { if (session == detectSession) leaveForListening() }
                // The microphone takes a moment to be free for another app (Google's recognizer).
                Thread.sleep(MIC_RELEASE_SETTLE_MILLIS)
            }
            main.post(then)
        }
    }

    private var detectSession = 0L

    @SuppressLint("MissingPermission")
    private fun startRecording(): Boolean {
        if (recording) return true
        val recorder = record ?: return false
        runCatching { recorder.startRecording() }.onFailure { return false }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
        recording = true
        thread = Thread({ pump(recorder) }, "sonaruna-mic").also { it.start() }
        return true
    }

    /**
     * Marks the recording stopped (under the lock) and returns what stops it for real: AudioRecord.stop and the pump
     * join, which block and so run outside the lock. Null when not recording.
     */
    private fun takeRecording(): (() -> Unit)? {
        if (!recording) return null
        recording = false
        output?.let(::closeOutput)
        output = null
        val recorder = record
        val pump = thread
        thread = null
        return {
            recorder?.let { runCatching { it.stop() } }
            pump?.join(500)
        }
    }

    private fun stopRecording() {
        takeRecording()?.invoke()
    }

    /** A new stream for one recognition; the previous one is closed. Null when not feeding the recognizer. */
    @Synchronized
    fun newStream(): ParcelFileDescriptor? {
        if (!active) return null
        val (read, write) = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val previous = output
        // Closing this stream also closes the write side of the pipe, which ends the recognizer's input.
        output = ParcelFileDescriptor.AutoCloseOutputStream(write)
        previous?.let(::closeOutput)
        return read
    }

    /** The recognizer refused the cleaned audio: back to the plain microphone (and the detector) for this run. */
    @Synchronized
    fun markUnsupported() {
        unsupported = true
        if (feeding) {
            feeding = false
            stopRecording()
            if (callPathHeld) {
                callPathHeld = false
                leaveCallPath()
            }
        }
    }

    /** For good: the screen closed. */
    fun close() {
        stop()
        micExecutor.shutdown()
    }

    /** Everything off and released: the app went to the background or closed. */
    @Synchronized
    fun stop() {
        detector = null
        onVoice = null
        detectSession++
        feeding = false
        stopRecording()
        record?.let(::release)
        record = null
        callPathHeld = false
        leaveCallPath()
    }

    private fun closeOutput(stream: FileOutputStream) {
        runCatching { stream.close() }
    }

    private fun pump(recorder: AudioRecord) {
        val buffer = ByteArray(FRAME_BYTES)
        // A pump that outlived its stop (the join timed out) ends as soon as a newer one takes over.
        while (recording && thread.let { it == null || it === Thread.currentThread() }) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            detector?.let { detect(it, buffer, read) }
            val stream = output ?: continue
            try {
                stream.write(buffer, 0, read)
            } catch (_: IOException) {
                // The recognizer finished and closed its side: drop audio until the next stream.
                if (output === stream) output = null
                closeOutput(stream)
            }
        }
    }

    private fun detect(voiceDetector: VoiceDetector, buffer: ByteArray, length: Int) {
        if (!voiceDetector.feed(VoiceDetector.rms(buffer, length))) return
        // Only the detector of the current reply may fire, and only once.
        val voice = synchronized(this) {
            if (detector !== voiceDetector) return
            onVoice.also {
                detector = null
                onVoice = null
            }
        } ?: return
        Log.i(TAG, "Voz da pessoa detectada durante a fala (${voiceDetector.describe()})")
        main.post { voice(voiceDetector) }
    }

    private fun release(recorder: AudioRecord) {
        canceller?.let { runCatching { it.release() } }
        suppressor?.let { runCatching { it.release() } }
        canceller = null
        suppressor = null
        runCatching { recorder.release() }
    }

    /** Call mode, sound on the loudspeaker unless headphones are plugged in, so the canceller knows what plays. */
    private fun enterCallPath() {
        inCallPath = true
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            (headset ?: speaker)?.let { runCatching { audioManager.setCommunicationDevice(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = true }
        }
    }

    private fun leaveCallPath() {
        if (!inCallPath) return
        inCallPath = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 640 // 20 ms of 16 kHz mono 16-bit audio
        private const val CALL_PATH_SETTLE_MILLIS = 200L
        private const val MIC_RELEASE_SETTLE_MILLIS = 60L
        private const val TAG = "SonarUna"
        private val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
    }
}
