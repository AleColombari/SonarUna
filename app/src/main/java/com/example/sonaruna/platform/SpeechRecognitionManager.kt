package com.example.sonaruna.platform

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class SpeechFailure {
    PERMISSION_DENIED, UNAVAILABLE, MICROPHONE_UNAVAILABLE, NETWORK,
    NO_SPEECH, LANGUAGE_UNAVAILABLE, UNKNOWN,
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

    @Volatile
    private var closed = false

    private class Session(
        val id: Long,
        val onResult: (List<String>) -> Unit,
        val onError: (SpeechFailure) -> Unit,
        val onPartial: ((List<String>) -> Unit)?,
        var attempt: Int = 0,
        var recognizer: SpeechRecognizer? = null,
        var timeout: Runnable? = null,
    )

    /**
     * [onResult] receives the recognizer's hypotheses, best first, never empty. With [onPartial], partial
     * hypotheses arrive while the person is still speaking (used to interrupt the app's own voice).
     */
    fun start(
        onResult: (List<String>) -> Unit,
        onError: (SpeechFailure) -> Unit,
        onPartial: ((List<String>) -> Unit)? = null,
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

        val session = Session(++nextSessionId, onResult, onError, onPartial)
        active = session
        if (engines.isEmpty()) {
            finishError(session, SpeechFailure.UNAVAILABLE)
            return@onMain
        }
        beginAttempt(session, workingEngine)
    }

    fun cancel() = onMain { cancelActive() }

    fun close() {
        closed = true
        onMain { cancelActive() }
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
            val recognizer = when (engine) {
                is Engine.Service -> SpeechRecognizer.createSpeechRecognizer(context, engine.component)
                Engine.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                Engine.Default -> SpeechRecognizer.createSpeechRecognizer(context)
            }
            session.recognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .filter { it.isNotBlank() }
                    if (partial.isNotEmpty()) session.onPartial?.invoke(partial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    if (hasNext && canTryNextEngine(error)) {
                        beginAttempt(session, engineIndex + 1)
                    } else {
                        finishError(session, classify(error))
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
                        workingEngine = engineIndex
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
            handler.postDelayed(timeout, 30_000L)
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, session.onPartial != null)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // The system recognizer may ignore this preference; offline use is not guaranteed.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        val recognizer = session.recognizer
        session.recognizer = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private fun classify(error: Int): SpeechFailure = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.NO_SPEECH
        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechFailure.MICROPHONE_UNAVAILABLE
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechFailure.NETWORK
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechFailure.UNAVAILABLE
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechFailure.LANGUAGE_UNAVAILABLE
        else -> SpeechFailure.UNKNOWN
    }

    /** Errors that mean "this engine cannot do it" (language, service), not "the person said nothing". */
    private fun canTryNextEngine(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT -> true
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
    }
}
