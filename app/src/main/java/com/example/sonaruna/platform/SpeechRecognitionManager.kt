package com.example.sonaruna.platform

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SpeechFailure {
    PERMISSION_DENIED, UNAVAILABLE, MICROPHONE_UNAVAILABLE, NETWORK,
    NO_SPEECH, LANGUAGE_UNAVAILABLE, UNKNOWN,

    /** Speech was heard but could not be written down (error 7 after the person spoke). */
    NO_MATCH,

    /** The recognizer does not take the echo-cancelled audio: from now on it uses the plain microphone. */
    CLEANED_AUDIO_REFUSED,
}

/** Which recognizer an attempt uses. */
private sealed interface Engine {
    /** Google's recognition service, chosen by name: the phone's default may be another one without pt-BR. */
    data class Service(val component: ComponentName) : Engine
    data object OnDevice : Engine
    data object Default : Engine
}

/**
 * Every start is one utterance. Stale callbacks cannot affect a new session. Engines are tried in order (Google's
 * service, on-device, the phone's default) until one accepts pt-BR; the one that worked is remembered.
 */
class SpeechRecognitionManager(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var nextSessionId = 0L
    private var active: Session? = null
    private val engines: List<Engine> by lazy(::findEngines)
    private var workingEngine = 0

    /**
     * One recognizer per engine, reused: creating and destroying one per answer made Google's service report
     * "disconnected" (error 11) when the next one started right away.
     */
    private val recognizers = mutableMapOf<Engine, SpeechRecognizer>()

    /** Names on the map ("sala 10B", "financeiro"): recognizers that support it lean towards them (Android 13+). */
    var biasing: List<String> = emptyList()

    /**
     * Microphone audio with the app's own voice cancelled (Android 13+). While it runs, recognizers listen to it
     * instead of the raw microphone. If a recognizer refuses it before it ever worked, it is turned off for good
     * and the session ends with [SpeechFailure.CLEANED_AUDIO_REFUSED].
     */
    var cleanedAudio: EchoCancelledAudio? = null
    private var cleanedAudioWorked = false

    @Volatile
    private var closed = false

    private class Session(
        val id: Long,
        val onResult: (List<String>) -> Unit,
        val onError: (SpeechFailure) -> Unit,
        val onPartial: ((List<String>) -> Unit)?,
        val onReady: (() -> Unit)?,
        val onActivity: (() -> Unit)?,
        var attempt: Int = 0,
        /** Passing errors (service disconnected, microphone still busy) retried so far. */
        var retries: Int = 0,
        /** The person was heard speaking in this session (start of speech or a loud level). */
        var heardSpeech: Boolean = false,
        var readyWatchdog: Runnable? = null,
        /** The network failed: this answer is tried once more on the phone's offline model. */
        var offlineOnly: Boolean = false,
        var readyAnnounced: Boolean = false,
        var recognizer: SpeechRecognizer? = null,
        var timeout: Runnable? = null,
        var stream: ParcelFileDescriptor? = null,
    )

    /**
     * [onResult] receives the recognizer's hypotheses, best first, never empty. With [onPartial], partial
     * hypotheses arrive while the person is still speaking (used to interrupt the app's own voice). [onReady] is
     * called once, when the recognizer really starts hearing: before that, the start of a phrase would be lost.
     * [onActivity] is called whenever the person is heard speaking, before any text arrives.
     */
    fun start(
        onResult: (List<String>) -> Unit,
        onError: (SpeechFailure) -> Unit,
        onPartial: ((List<String>) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        onActivity: (() -> Unit)? = null,
    ) = onMain {
        cancelActive()
        if (closed) {
            onError(SpeechFailure.UNAVAILABLE)
            return@onMain
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError(SpeechFailure.PERMISSION_DENIED)
            return@onMain
        }

        val session = Session(++nextSessionId, onResult, onError, onPartial, onReady, onActivity)
        active = session
        if (engines.isEmpty()) {
            finishError(session, SpeechFailure.UNAVAILABLE)
            return@onMain
        }
        // Reusing a recognizer right after cancel() made Google's service answer "busy" or "client" (errors 8 and 5)
        // while the old session was still closing: a short wait instead of a failed attempt and a longer retry.
        val wait = START_SETTLE_MILLIS - (SystemClock.uptimeMillis() - lastCancelAt)
        val attempt = session.attempt
        if (wait > 0) {
            handler.postDelayed({ if (active === session && session.attempt == attempt) beginAttempt(session, workingEngine) }, wait)
        } else {
            beginAttempt(session, workingEngine)
        }
    }

    fun cancel() = onMain { cancelActive() }

    /** The recognizer of the current answer is already hearing (onReadyForSpeech arrived). */
    val ready: Boolean get() = active?.readyAnnounced == true

    private var lastCancelAt = 0L

    /** After the first answer the engine is known to work: a "client" error is then not a reason to switch engine. */
    private var engineConfirmed = false

    fun close() {
        closed = true
        onMain {
            cancelActive()
            recognizers.values.forEach { runCatching { it.destroy() } }
            recognizers.clear()
        }
    }

    private fun create(engine: Engine): SpeechRecognizer = when (engine) {
        is Engine.Service -> SpeechRecognizer.createSpeechRecognizer(context, engine.component)
        Engine.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        Engine.Default -> SpeechRecognizer.createSpeechRecognizer(context)
    }

    /** A recognizer whose service disconnected is thrown away; the next attempt creates a fresh one. */
    private fun discard(engine: Engine) {
        recognizers.remove(engine)?.let { runCatching { it.destroy() } }
    }

    private fun findEngines(): List<Engine> = buildList {
        googleRecognitionService()?.let { add(Engine.Service(it)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        ) {
            add(Engine.OnDevice)
        }
        if (runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)) add(Engine.Default)
    }

    private fun googleRecognitionService(): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())
        return GOOGLE_PACKAGES.firstNotNullOfOrNull { name ->
            services.firstOrNull { it.serviceInfo.packageName == name }
                ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }
    }

    private fun beginAttempt(session: Session, engineIndex: Int) {
        if (closed || active !== session) return
        val attempt = ++session.attempt
        disposeRecognizer(session)
        val engine = engines[engineIndex]
        val hasNext = engineIndex + 1 < engines.size

        try {
            val recognizer = recognizers.getOrPut(engine) { create(engine) }
            session.recognizer = recognizer
            // The echo-cancelled audio, when the recognizer may take it. Google's takes it only offline.
            val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cleanedAudio?.newStream() else null
            session.stream = stream
            // Online by default: it gets short answers ("dez bê") better than the offline model, and asking for offline
            // first made the first answer of every run fail when pt-BR was not downloaded.
            val offline = stream != null || session.offlineOnly
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    session.readyWatchdog?.let(handler::removeCallbacks)
                    session.readyWatchdog = null
                    if (session.readyAnnounced) return@onMain
                    session.readyAnnounced = true
                    session.onReady?.invoke()
                }
                override fun onBeginningOfSpeech() = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    session.heardSpeech = true
                    session.onActivity?.invoke()
                }
                // Loudness alone is not speech: footsteps and a busy corridor pass any level.
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .filter { it.isNotBlank() }
                    if (partial.isNotEmpty()) {
                        session.heardSpeech = true
                        session.onPartial?.invoke(partial)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    // Only codes are logged, never what was said: enough to tell why recognition failed on a phone.
                    val cleaned = session.stream != null
                    Log.i(TAG, "Reconhecimento falhou: motor=$engine offline=$offline sem-eco=$cleaned erro=$error")
                    when {
                        cleaned && !cleanedAudioWorked && refusesCleanedAudio(error) -> {
                            // This recognizer does not take audio from the app (Google's, without pt-BR downloaded for
                            // offline use): plain microphone from now on. The caller chooses when to listen again, so
                            // the plain microphone does not open while the app is still speaking (it would hear it).
                            Log.i(TAG, "Reconhecimento recusou o áudio sem eco; usando o microfone comum")
                            // Ask for pt-BR offline, so the echo cancellation works the next time the app opens.
                            if (isLanguageError(error)) requestOfflinePortuguese(engine)
                            cleanedAudio?.markUnsupported()
                            finishError(session, SpeechFailure.CLEANED_AUDIO_REFUSED)
                        }
                        // Offline was only the fallback for a network failure: the problem to report is the network.
                        session.offlineOnly && isLanguageError(error) -> finishError(session, SpeechFailure.NETWORK)
                        !offline && isNetworkError(error) -> {
                            // No connection: the same answer once more on the offline model, if the phone has pt-BR.
                            session.offlineOnly = true
                            beginAttempt(session, engineIndex)
                        }
                        // The service dropped this recognizer, or the microphone was still being released (the app's
                        // own recording, the previous answer): the same engine again, after a pause.
                        isPassing(error) && session.retries < RETRY_DELAYS_MILLIS.size -> retryLater(session, engineIndex, error)
                        hasNext && canTryNextEngine(error) -> beginAttempt(session, engineIndex + 1)
                        // Retries used up on a passing error: the conversation treats it as silence and listens again,
                        // instead of stopping with "microphone unavailable".
                        isPassing(error) && error != SpeechRecognizer.ERROR_AUDIO -> finishError(session, SpeechFailure.UNKNOWN)
                        error == SpeechRecognizer.ERROR_NO_MATCH && !session.heardSpeech -> finishError(session, SpeechFailure.NO_SPEECH)
                        else -> finishError(session, classify(error))
                    }
                }

                override fun onResults(results: Bundle?) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    val hypotheses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .map { it.trim().replace(Regex("\\s+"), " ") }
                        .filter { it.isNotEmpty() }
                    if (hypotheses.isEmpty()) {
                        finishError(session, SpeechFailure.NO_SPEECH)
                    } else {
                        if (workingEngine != engineIndex || session.attempt > 1 || (session.stream != null && !cleanedAudioWorked)) {
                            Log.i(TAG, "Reconhecimento funcionou: motor=$engine offline=$offline sem-eco=${session.stream != null}")
                        }
                        if (session.stream != null) cleanedAudioWorked = true
                        workingEngine = engineIndex
                        engineConfirmed = true
                        active = null
                        disposeRecognizer(session)
                        session.onResult(hypotheses)
                    }
                }
            })

            val timeout = Runnable {
                if (isCurrent(session, attempt)) finishError(session, SpeechFailure.NO_SPEECH)
            }
            session.timeout = timeout
            handler.postDelayed(timeout, SESSION_TIMEOUT_MILLIS)
            // A recognizer that never says it is ready (the service hung): a fresh one, instead of 30 s of nothing.
            val watchdog = Runnable {
                if (!isCurrent(session, attempt)) return@Runnable
                Log.i(TAG, "Reconhecimento não ficou pronto a tempo; recriando")
                discard(engine)
                if (session.retries < RETRY_DELAYS_MILLIS.size) {
                    retryLater(session, engineIndex, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                } else {
                    finishError(session, SpeechFailure.UNKNOWN)
                }
            }
            session.readyWatchdog = watchdog
            handler.postDelayed(watchdog, READY_TIMEOUT_MILLIS)
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, session.onPartial != null)
                // More hypotheses: when the best one misses ("3B" for "dez bê"), another may carry the right room.
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // A short pause in "estou na 3A ... e quero ir para a 10B" should not end the phrase. Google's
                // service ignores these lengths: MainViewModel then joins a phrase that stopped in the middle.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PHRASE_SILENCE_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PHRASE_SILENCE_MILLIS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && biasing.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing))
                }
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                if (stream != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, stream)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, EchoCancelledAudio.SAMPLE_RATE)
                }
            })
        } catch (_: SecurityException) {
            finishError(session, SpeechFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            if (hasNext && active === session && !closed) {
                beginAttempt(session, engineIndex + 1)
            } else {
                finishError(session, SpeechFailure.UNAVAILABLE)
            }
        }
    }

    /**
     * Android 13+: whether the first recognizer has pt-BR installed for offline use, which the echo-cancelled audio
     * needs. When it is missing, the download is asked for right away, not only after a failed answer. Null when the
     * recognizer does not say. Called once per run, before the first answer, so that this answer does not fail.
     */
    suspend fun offlinePortugueseInstalled(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        offlineProbe?.let { return it }
        val engine = withContext(Dispatchers.Main.immediate) { engines.firstOrNull() } ?: return null
        val support = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<RecognitionSupport?> { continuation ->
                onMain {
                    val recognizer = runCatching { recognizers.getOrPut(engine) { create(engine) } }.getOrNull()
                    if (recognizer == null || closed) {
                        if (continuation.isActive) continuation.resume(null)
                        return@onMain
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                    runCatching {
                        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                if (continuation.isActive) continuation.resume(recognitionSupport)
                            }

                            override fun onError(error: Int) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                    }.onFailure { if (continuation.isActive) continuation.resume(null) }
                }
            }
        } ?: return null
        val installed = support.installedOnDeviceLanguages.any(::isPortuguese)
        val pending = support.pendingOnDeviceLanguages.any(::isPortuguese)
        Log.i(TAG, "Português offline do reconhecimento: instalado=$installed baixando=$pending")
        if (!installed && !pending) withContext(Dispatchers.Main.immediate) { requestOfflinePortuguese(engine) }
        offlineProbe = installed
        return installed
    }

    private var offlineProbe: Boolean? = null

    private fun isPortuguese(tag: String): Boolean = Locale.forLanguageTag(tag).let {
        it.language == "pt" && (it.country.isEmpty() || it.country == "BR")
    }

    private var offlineModelRequested = false

    /** Asks the recognizer to download pt-BR for offline use (Android 13+). It may show its own confirmation. */
    private fun requestOfflinePortuguese(engine: Engine) {
        if (offlineModelRequested || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        offlineModelRequested = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        val requester = runCatching {
            if (engine is Engine.Service) SpeechRecognizer.createSpeechRecognizer(context, engine.component)
            else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrNull() ?: return
        runCatching { requester.triggerModelDownload(intent) }
            .onSuccess { Log.i(TAG, "Pedido o download do português offline do reconhecimento de voz") }
            .onFailure { Log.i(TAG, "Download do português offline indisponível: ${it.javaClass.simpleName}") }
        handler.postDelayed({ runCatching { requester.destroy() } }, MODEL_REQUEST_LINGER_MILLIS)
    }

    /** The same engine again after a pause that grows with each try (150, 300, 600 ms). */
    private fun retryLater(session: Session, engineIndex: Int, error: Int) {
        val delayMillis = RETRY_DELAYS_MILLIS[session.retries]
        session.retries++
        // A disconnected service, or "busy" twice: a fresh recognizer.
        if (isDisconnection(error) || (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && session.retries >= 2)) {
            discard(engines[engineIndex])
        }
        disposeRecognizer(session)
        val attempt = ++session.attempt
        handler.postDelayed({ if (isCurrent(session, attempt)) beginAttempt(session, engineIndex) }, delayMillis)
    }

    private fun isCurrent(session: Session, attempt: Int): Boolean =
        !closed && active?.id == session.id && session.attempt == attempt

    private fun finishError(session: Session, failure: SpeechFailure) {
        if (active !== session || closed) return
        active = null
        disposeRecognizer(session)
        session.onError(failure)
    }

    private fun cancelActive() {
        val session = active ?: return
        active = null
        disposeRecognizer(session)
    }

    private fun disposeRecognizer(session: Session) {
        session.timeout?.let(handler::removeCallbacks)
        session.timeout = null
        session.readyWatchdog?.let(handler::removeCallbacks)
        session.readyWatchdog = null
        if (session.recognizer != null) lastCancelAt = SystemClock.uptimeMillis()
        val recognizer = session.recognizer
        session.recognizer = null
        // Cancel only: the recognizer is reused by the next answer.
        runCatching { recognizer?.cancel() }
        session.stream?.let { runCatching { it.close() } }
        session.stream = null
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private fun classify(error: Int): SpeechFailure = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechFailure.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.NO_SPEECH
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.MICROPHONE_UNAVAILABLE
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechFailure.NETWORK
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechFailure.UNAVAILABLE
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechFailure.LANGUAGE_UNAVAILABLE
        else -> SpeechFailure.UNKNOWN
    }

    /**
     * Errors that mean the recognizer cannot work from the app's audio, not "nothing was said". A language error
     * counts too: with an audio source, Google's service may only use its offline model, missing if pt-BR was not
     * downloaded, while the plain microphone works online.
     */
    private fun refusesCleanedAudio(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false
        else -> true
    }

    private fun isDisconnection(error: Int): Boolean = error == SpeechRecognizer.ERROR_CLIENT ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)

    /** Errors that usually go away a moment later: a dropped service, or a microphone still being released. */
    private fun isPassing(error: Int): Boolean = isDisconnection(error) ||
        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_AUDIO

    private fun isNetworkError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT

    private fun isLanguageError(error: Int): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

    /** Errors that mean "this engine cannot do it" (language, service), not "the person said nothing". */
    private fun canTryNextEngine(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_SERVER -> true
        // Mid-conversation, "client" is a passing hiccup of an engine that works, not a reason to switch.
        SpeechRecognizer.ERROR_CLIENT -> !engineConfirmed
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> true
            else -> false
        }
    }

    private companion object {
        /** Google's recognition services, preferred in this order (Google app, then Speech Services by Google). */
        val GOOGLE_PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")
        const val TAG = "SonarUna"
        const val LANGUAGE = "pt-BR"
        val RETRY_DELAYS_MILLIS = longArrayOf(150L, 300L, 600L)
        const val START_SETTLE_MILLIS = 150L
        const val READY_TIMEOUT_MILLIS = 3_000L

        const val PHRASE_SILENCE_MILLIS = 1500L
        const val SESSION_TIMEOUT_MILLIS = 30_000L
        const val PROBE_TIMEOUT_MILLIS = 1_500L
        const val MODEL_REQUEST_LINGER_MILLIS = 10_000L
    }
}
