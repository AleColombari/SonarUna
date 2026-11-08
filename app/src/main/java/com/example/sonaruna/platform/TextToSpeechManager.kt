package com.example.sonaruna.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.sonaruna.guia.forSpeech
import java.util.Locale
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How much faster than the person's own rate the app speaks (the web version uses 1.2 too). */
private const val SPEECH_RATE = 1.2f

/** Owns one TTS engine and completes speech only after its utterance callback. */
class TextToSpeechManager(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val initializationMutex = Mutex()
    private val speechMutex = Mutex()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initializationId = 0L
    private var initialization: CancellableContinuation<Boolean>? = null
    private var nextUtteranceId = 0L
    private var stopGeneration = 0L
    private var speech: PendingSpeech? = null

    @Volatile
    private var closed = false

    /** One reply, queued as [parts] utterances "<prefix>-0".."<prefix>-(parts-1)" that the engine plays back to back. */
    private class PendingSpeech(
        val prefix: String,
        val parts: Int,
        val continuation: CancellableContinuation<Boolean>,
        val onPart: ((Int) -> Unit)?,
    ) {
        val lastId: String get() = "$prefix-${parts - 1}"
        fun part(utteranceId: String?): Int? =
            utteranceId?.takeIf { it.startsWith("$prefix-") }?.substringAfterLast('-')?.toIntOrNull()
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.Main.immediate) {
        initializationMutex.withLock {
            if (closed) return@withLock false
            if (ready) return@withLock true
            releaseEngine()
            withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine { continuation ->
                    val id = ++initializationId
                    initialization = continuation
                    continuation.invokeOnCancellation {
                        onMain {
                            if (initializationId == id) {
                                initialization = null
                                releaseEngine()
                                initializationId++
                            }
                        }
                    }
                    try {
                        engine = TextToSpeech(context) { status ->
                            // Posting also handles engines that initialize before the constructor returns.
                            handler.post { finishInitialization(id, status) }
                        }
                    } catch (_: RuntimeException) {
                        finishInitialization(id, TextToSpeech.ERROR)
                    }
                }
            } ?: false
        }
    }

    suspend fun speak(text: String): Boolean = speak(listOf(text))

    /**
     * Speaks [sentences] queued together, so the engine goes from one to the next without a pause. [onPart] is called
     * as each sentence starts playing (for the echo check: only what was already said can come back).
     */
    suspend fun speak(sentences: List<String>, onPart: ((Int) -> Unit)? = null): Boolean = withContext(Dispatchers.Main.immediate) {
        val generation = stopGeneration
        // "5B" read as "5 bê", not "5 billion".
        val parts = sentences.filter(String::isNotBlank).map(::forSpeech)
        val text = parts.joinToString(" ")
        speechMutex.withLock {
            if (closed || !ready || generation != stopGeneration || parts.isEmpty()) return@withLock false
            // Respect slow speech rates chosen in accessibility settings. onDone is the
            // normal completion signal; this generous limit only handles a stalled engine.
            val timeoutMillis = (60_000L + text.length * 400L).coerceAtMost(300_000L)
            val completed = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val pending = PendingSpeech("sonaruna-${++nextUtteranceId}", parts.size, continuation, onPart)
                    speech = pending
                    continuation.invokeOnCancellation {
                        onMain {
                            if (speech === pending) {
                                speech = null
                                runCatching { engine?.stop() }
                            }
                        }
                    }
                    val queued = parts.withIndex().all { (i, sentence) ->
                        val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        runCatching { engine?.speak(sentence, mode, null, "${pending.prefix}-$i") }.getOrNull() == TextToSpeech.SUCCESS
                    }
                    if (!queued) finishSpeech(pending.lastId, false)
                }
            } ?: false
            // A failed or timed-out engine must be recreated on the next explicit retry.
            // Cancellation propagates above and does not mark a healthy engine as failed.
            if (!completed) releaseEngine()
            completed
        }
    }

    /**
     * With the echo canceller on, the voice plays as a call (still on the loudspeaker), so the canceller knows what
     * is playing and removes it from the microphone. Otherwise it plays as accessibility speech.
     */
    fun useCallPath(on: Boolean) = onMain {
        callPath = on
        runCatching { engine?.setAudioAttributes(attributes()) }
    }

    private var callPath = false

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (callPath) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun stop() = onMain {
        stopGeneration++
        val pending = speech
        speech = null
        runCatching { engine?.stop() }
        if (pending?.continuation?.isActive == true) pending.continuation.resume(false)
    }

    fun close() {
        closed = true
        onMain {
            stop()
            initializationId++
            val pending = initialization
            initialization = null
            releaseEngine()
            if (pending?.isActive == true) pending.resume(false)
        }
    }

    private fun finishInitialization(id: Long, status: Int) {
        if (closed || id != initializationId) return
        val pending = initialization ?: return
        initialization = null
        val tts = engine
        val configured = status == TextToSpeech.SUCCESS && tts != null && runCatching {
            val languageStatus = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
            if (languageStatus < TextToSpeech.LANG_AVAILABLE) return@runCatching false
            // The most natural pt-BR voice installed (no network needed), the quickest to start among equals.
            tts.voices
                ?.filter { it.locale.language == "pt" && it.locale.country == "BR" && !it.isNetworkConnectionRequired }
                ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
                ?.maxWithOrNull(compareBy<Voice>({ it.quality }, { -it.latency }))
                ?.let { tts.voice = it }
            // A bit faster than the person's own speech rate (system text-to-speech setting), like the web version.
            val ownRate = Settings.Secure.getInt(context.contentResolver, Settings.Secure.TTS_DEFAULT_RATE, 100) / 100f
            tts.setSpeechRate(ownRate * SPEECH_RATE)
            tts.setAudioAttributes(attributes())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = onMain {
                    val pending = speech ?: return@onMain
                    pending.part(utteranceId)?.let { pending.onPart?.invoke(it) }
                }
                override fun onDone(utteranceId: String?) = onMain { finishSpeech(utteranceId, true) }
                @Deprecated("Required by the platform listener")
                override fun onError(utteranceId: String?) = onMain { finishSpeech(utteranceId, false) }
                override fun onError(utteranceId: String?, errorCode: Int) =
                    onMain { finishSpeech(utteranceId, false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    onMain { finishSpeech(utteranceId, false) }
            })
            true
        }.getOrDefault(false)
        ready = configured
        if (!configured) releaseEngine()
        if (pending.isActive) pending.resume(configured)
    }

    private fun finishSpeech(id: String?, success: Boolean) {
        val pending = speech ?: return
        if (pending.part(id) == null) return
        // A sentence before the last one finished: the next one is already queued.
        if (success && id != pending.lastId) return
        speech = null
        if (pending.continuation.isActive) pending.continuation.resume(success && !closed)
    }

    private fun releaseEngine() {
        ready = false
        val tts = engine
        engine = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }
}
