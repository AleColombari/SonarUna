package com.example.sonaruna.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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

    private data class PendingSpeech(
        val id: String,
        val continuation: CancellableContinuation<Boolean>,
    )

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

    suspend fun speak(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val generation = stopGeneration
        speechMutex.withLock {
            if (closed || !ready || generation != stopGeneration || text.isBlank()) return@withLock false
            // Respect slow speech rates chosen in accessibility settings. onDone is the
            // normal completion signal; this generous limit only handles a stalled engine.
            val timeoutMillis = (60_000L + text.length * 400L).coerceAtMost(300_000L)
            val completed = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val id = "sonaruna-${++nextUtteranceId}"
                    speech = PendingSpeech(id, continuation)
                    continuation.invokeOnCancellation {
                        onMain {
                            if (speech?.id == id) {
                                speech = null
                                runCatching { engine?.stop() }
                            }
                        }
                    }
                    val status = runCatching {
                        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                    }.getOrNull()
                    if (status != TextToSpeech.SUCCESS) finishSpeech(id, false)
                }
            } ?: false
            // A failed or timed-out engine must be recreated on the next explicit retry.
            // Cancellation propagates above and does not mark a healthy engine as failed.
            if (!completed) releaseEngine()
            completed
        }
    }

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
            tts.voices?.firstOrNull {
                it.locale.language == "pt" && it.locale.country == "BR" && !it.isNetworkConnectionRequired
            }?.let { tts.voice = it }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
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
        if (pending.id != id) return
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
