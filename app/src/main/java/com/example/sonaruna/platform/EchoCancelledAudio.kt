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

/**
 * Hearing the person while the app speaks, without hearing the app: the same acoustic echo cancellation that voice
 * calls use. The app's voice plays on the call path (still on the loudspeaker), the microphone records on the call
 * path with the phone's echo canceller, and the recognizer gets that cleaned audio (Android 13+, through
 * RecognizerIntent.EXTRA_AUDIO_SOURCE) instead of opening the raw microphone itself.
 *
 * One recording runs while the conversation is on screen, so the canceller stays adapted to the room; each recognition
 * gets a fresh pipe fed from it. Nothing is saved: the audio only goes to the recognizer.
 *
 * When the recognizer refuses that audio (Google's only takes it offline, with pt-BR downloaded), the same cleaned
 * microphone still serves to notice the person speaking over the voice ([startDetecting]): the voice stops and the
 * plain recognizer listens. That needs no recognizer at all, only the loudness of the cleaned audio.
 */
class EchoCancelledAudio(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    @Volatile
    private var output: FileOutputStream? = null
    private var record: AudioRecord? = null
    private var canceller: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Turned off for good once a recognizer refused the cleaned audio; the app then uses the plain microphone. */
    var unsupported = false
        private set

    /** The recognizer may take the cleaned audio (Android 13+, not refused yet). */
    val supported: Boolean
        get() = !unsupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canDetect

    /** The phone has an echo canceller: the cleaned microphone can at least tell when the person speaks. */
    val canDetect: Boolean get() = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

    /** Running and feeding the recognizer. */
    val active: Boolean get() = running && !unsupported

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var detector: VoiceDetector? = null

    @Volatile
    private var onVoice: (() -> Unit)? = null

    /**
     * Call mode kept on for the whole conversation (see [holdCallPath]): switching it at every reply made the start
     * of each reply play during the switch, in a worse voice.
     */
    private var callPathHeld = false

    /** Keeps the call path on (or lets it go) whether or not the microphone records. */
    @Synchronized
    fun holdCallPath(on: Boolean) {
        if (on == callPathHeld) return
        callPathHeld = on
        if (on) enterCallPath() else if (!running) leaveCallPath()
    }

    /** Starts the call path and the recording that feeds the recognizer. False when the phone cannot do it. */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (!supported) return false
        return open()
    }

    /**
     * While the app speaks: records the cleaned microphone and calls [voice] (on the main thread, once) as soon as the
     * person is heard speaking over it. False when the phone cannot do it. [stop] ends it.
     */
    @Synchronized
    fun startDetecting(voice: () -> Unit): Boolean {
        if (!running && (!canDetect || !open())) return false
        onVoice = voice
        detector = VoiceDetector()
        return true
    }

    /** Starts the call path and the recording. False when the phone cannot do it; then nothing changed. */
    @SuppressLint("MissingPermission") // The caller only starts this after RECORD_AUDIO was granted.
    private fun open(): Boolean {
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
        if (!callPathHeld) enterCallPath()
        runCatching { recorder.startRecording() }.onFailure {
            release(recorder)
            if (!callPathHeld) leaveCallPath()
            return false
        }
        record = recorder
        running = true
        thread = Thread({ pump(recorder) }, "sonaruna-mic").apply { start() }
        Log.i(TAG, "Cancelamento de eco ligado: canceller=${canceller?.enabled == true} ruido=${suppressor?.enabled == true}")
        return true
    }

    /** A new stream for one recognition; the previous one is closed. Null when not running. */
    @Synchronized
    fun newStream(): ParcelFileDescriptor? {
        if (!running) return null
        val (read, write) = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val previous = output
        // Closing this stream also closes the write side of the pipe, which ends the recognizer's input.
        output = ParcelFileDescriptor.AutoCloseOutputStream(write)
        previous?.let(::closeOutput)
        return read
    }

    /** The recognizer refused the cleaned audio: back to the plain microphone for the rest of this run. */
    fun markUnsupported() {
        unsupported = true
        stop()
    }

    @Synchronized
    fun stop() {
        detector = null
        onVoice = null
        if (!running) return
        running = false
        output?.let(::closeOutput)
        output = null
        record?.let { runCatching { it.stop() } }
        thread?.join(500)
        thread = null
        record?.let(::release)
        record = null
        if (!callPathHeld) leaveCallPath()
        Log.i(TAG, "Cancelamento de eco desligado")
    }

    private fun closeOutput(stream: FileOutputStream) {
        runCatching { stream.close() }
    }

    private fun pump(recorder: AudioRecord) {
        val buffer = ByteArray(FRAME_BYTES)
        while (running) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            detector?.let { detect(it, buffer, read) }
            val stream = output ?: continue
            try {
                stream.write(buffer, 0, read)
            } catch (_: IOException) {
                // The recognizer finished and closed its side: drop audio until the next stream.
                synchronized(this) { if (output === stream) output = null }
                closeOutput(stream)
            }
        }
    }

    private fun detect(voiceDetector: VoiceDetector, buffer: ByteArray, length: Int) {
        if (!voiceDetector.feed(VoiceDetector.rms(buffer, length))) return
        val voice = onVoice ?: return
        detector = null
        onVoice = null
        Log.i(TAG, "Voz da pessoa detectada durante a fala (${voiceDetector.describe()})")
        main.post(voice)
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
        private const val TAG = "SonarUna"
        private val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
    }
}
