package com.example.sonaruna.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class SpeechFailure {
    PERMISSION_DENIED, UNAVAILABLE, MICROPHONE_UNAVAILABLE, NETWORK,
    NO_SPEECH, LANGUAGE_UNAVAILABLE, UNKNOWN,
}

/** Every start is one user-requested utterance. Stale callbacks cannot affect a new session. */
class SpeechRecognitionManager(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var nextSessionId = 0L
    private var active: Session? = null

    @Volatile
    private var closed = false

    private class Session(
        val id: Long,
        val onResult: (String) -> Unit,
        val onError: (SpeechFailure) -> Unit,
        var attempt: Int = 0,
        var recognizer: SpeechRecognizer? = null,
        var timeout: Runnable? = null,
    )

    fun start(onResult: (String) -> Unit, onError: (SpeechFailure) -> Unit) = onMain {
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

        val session = Session(++nextSessionId, onResult, onError)
        active = session
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)
        beginAttempt(session, onDeviceAvailable)
    }

    fun cancel() = onMain { cancelActive() }

    fun close() {
        closed = true
        onMain { cancelActive() }
    }

    private fun beginAttempt(session: Session, onDevice: Boolean) {
        if (closed || active !== session) return
        val attempt = ++session.attempt
        disposeRecognizer(session)

        if (!onDevice && !runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
                .getOrDefault(false)
        ) {
            finishError(session, SpeechFailure.UNAVAILABLE)
            return
        }

        try {
            val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            session.recognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    if (onDevice && error in onDeviceFallbackErrors) {
                        beginAttempt(session, onDevice = false)
                    } else {
                        finishError(session, classify(error))
                    }
                }

                override fun onResults(results: Bundle?) = onMain {
                    if (!isCurrent(session, attempt)) return@onMain
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()?.replace(Regex("\\s+"), " ")
                    if (text.isNullOrEmpty()) {
                        finishError(session, SpeechFailure.NO_SPEECH)
                    } else {
                        active = null
                        disposeRecognizer(session)
                        session.onResult(text)
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // The system recognizer may ignore this preference; offline use is not guaranteed.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: SecurityException) {
            finishError(session, SpeechFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            if (onDevice && active === session && !closed) {
                beginAttempt(session, onDevice = false)
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

    private companion object {
        val onDeviceFallbackErrors = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
        )
    }
}
