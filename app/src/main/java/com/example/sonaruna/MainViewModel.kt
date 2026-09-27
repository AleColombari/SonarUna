package com.example.sonaruna

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonaruna.conversation.ConversationEngine
import com.example.sonaruna.conversation.ConversationPhase
import com.example.sonaruna.conversation.ConversationState
import com.example.sonaruna.conversation.Prompt
import com.example.sonaruna.conversation.PromptKey
import com.example.sonaruna.conversation.Transition
import com.example.sonaruna.platform.HapticFeedback
import com.example.sonaruna.platform.LocationException
import com.example.sonaruna.platform.LocationFailure
import com.example.sonaruna.platform.LocationRepository
import com.example.sonaruna.platform.SpeechFailure
import com.example.sonaruna.platform.SpeechRecognitionManager
import com.example.sonaruna.platform.TextToSpeechManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class PermissionStatus(val location: Boolean, val microphone: Boolean, val permanentlyDenied: Boolean) {
    val granted: Boolean get() = location && microphone
}

data class MainUiState(
    val conversation: ConversationState = ConversationState(),
    val actionDescription: String = "",
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val busy: Boolean = true,
)

sealed interface PlatformAction {
    data object RequestPermissions : PlatformAction
    data object OpenAppSettings : PlatformAction
    data class AnnounceVoiceFailure(val message: String) : PlatformAction
}

private enum class Recovery { PERMISSIONS, SETTINGS, LOCATION, SPEECH, TTS }

/** Owns the session in memory. No saved state, coordinates on disk, or background microphone. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = ConversationEngine()
    private val location = LocationRepository(application)
    private val recognition = SpeechRecognitionManager(application)
    private val voice = TextToSpeechManager(application)
    private val haptics = HapticFeedback(application)
    private val mutableState = MutableStateFlow(MainUiState())
    val state = mutableState.asStateFlow()
    private val actionsChannel = Channel<PlatformAction>(Channel.BUFFERED)
    val actions = actionsChannel.receiveAsFlow()

    private var permissions = PermissionStatus(false, false, false)
    private var foreground = false
    private var voiceReady = false
    private var permissionsRequestedThisSession = false
    private var awaitingPermissions = false
    private var recovery: Recovery? = null
    private var listening = false
    private var speaking = false
    private var working = false
    private var lastMessage: String? = null
    private var operation: Job? = null
    private var generation = 0L

    fun onForeground(status: PermissionStatus) {
        foreground = true
        permissions = status
        if (awaitingPermissions) return
        runOperation {
            if (!ensureVoice()) return@runOperation
            continueSession()
        }
    }

    fun onBackground() {
        foreground = false
        generation++
        operation?.cancel()
        recognition.cancel()
        voice.stop()
        listening = false
        speaking = false
        working = false
        refresh()
    }

    fun onPermissionsResult(status: PermissionStatus) {
        awaitingPermissions = false
        permissions = status
        if (!foreground) return
        runOperation {
            if (!ensureVoice()) return@runOperation
            if (status.granted) {
                recovery = null
                continueSession()
            } else {
                explainDeniedPermissions()
            }
        }
    }

    fun onSettingsUnavailable() {
        if (!foreground) return
        runOperation { say(R.string.settings_unavailable) }
    }

    /** Called only by the visible button, never by a speech or location callback. */
    fun onMicrophoneClick(status: PermissionStatus) {
        permissions = status
        if (!foreground || state.value.busy) return
        if (listening) {
            recognition.cancel()
            listening = false
            runOperation { say(R.string.recognition_cancelled) }
            return
        }
        if (!status.granted && recovery != Recovery.TTS) {
            runOperation {
                if (status.permanentlyDenied) {
                    recovery = Recovery.SETTINGS
                    voice.stop()
                    actionsChannel.send(PlatformAction.OpenAppSettings)
                } else {
                    requestPermissions()
                }
            }
            return
        }
        when (recovery) {
            Recovery.TTS -> runOperation {
                if (ensureVoice()) {
                    recovery = null
                    continueSession()
                }
            }
            Recovery.LOCATION -> runOperation { obtainLocation() }
            Recovery.SETTINGS, Recovery.PERMISSIONS -> runOperation { continueSession() }
            Recovery.SPEECH -> startListening()
            null -> when (engine.state.phase) {
                ConversationPhase.COMPLETE -> runOperation {
                    val transition = engine.restart(System.currentTimeMillis())
                    if (transition.state.phase == ConversationPhase.LOCATING) obtainLocation()
                    else present(transition)
                }
                ConversationPhase.ORIGIN, ConversationPhase.CONFIRM_ORIGIN,
                ConversationPhase.DESTINATION, ConversationPhase.CONFIRM_DESTINATION -> startListening()
                ConversationPhase.INITIALIZING, ConversationPhase.PERMISSIONS -> runOperation { continueSession() }
                ConversationPhase.LOCATING, ConversationPhase.ERROR -> runOperation { obtainLocation() }
            }
        }
    }

    private suspend fun continueSession() {
        if (!permissions.granted) {
            engine.awaitPermissions()
            if (!permissionsRequestedThisSession && !permissions.permanentlyDenied) requestPermissions()
            else explainDeniedPermissions()
            return
        }
        if (recovery == Recovery.PERMISSIONS || recovery == Recovery.SETTINGS) recovery = null
        when (engine.state.phase) {
            ConversationPhase.INITIALIZING, ConversationPhase.PERMISSIONS, ConversationPhase.LOCATING -> obtainLocation()
            else -> {
                // A background interruption never starts the microphone again.
                // Replay the pending instruction so resuming remains understandable without sight.
                val message = lastMessage
                if (message != null && recovery != Recovery.TTS) speakMessage(message)
                else repeatCurrentStep()
            }
        }
    }

    private suspend fun requestPermissions() {
        recovery = null
        engine.awaitPermissions()
        if (!say(R.string.permissions_explanation)) return
        permissionsRequestedThisSession = true
        awaitingPermissions = true
        refresh()
        actionsChannel.send(PlatformAction.RequestPermissions)
    }

    private suspend fun explainDeniedPermissions() {
        recovery = if (permissions.permanentlyDenied) Recovery.SETTINGS else Recovery.PERMISSIONS
        haptics.error()
        say(if (permissions.permanentlyDenied) R.string.permissions_settings else R.string.permissions_denied)
    }

    private suspend fun obtainLocation() {
        recovery = null
        working = true
        engine.locating()
        refresh()
        if (!say(R.string.locating)) return
        try {
            val result = location.currentLocation()
            working = false
            present(engine.locationObtained(result))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LocationException) {
            working = false
            if (error.reason == LocationFailure.PERMISSION_DENIED) {
                recovery = Recovery.PERMISSIONS
                haptics.error()
                say(R.string.permissions_denied)
            } else {
                recovery = Recovery.LOCATION
                haptics.error()
                say(when (error.reason) {
                    LocationFailure.LOCATION_DISABLED -> R.string.location_disabled
                    LocationFailure.SERVICES_UNAVAILABLE -> R.string.location_services_unavailable
                    else -> R.string.location_error
                })
            }
        }
    }

    private fun startListening() {
        // Stop synthesis before acquiring the microphone, including pending utterance callbacks.
        operation?.cancel()
        generation++
        voice.stop()
        speaking = false
        recovery = null
        listening = true
        refresh()
        haptics.start()
        recognition.start(
            onResult = { text ->
                if (foreground && listening) {
                    listening = false
                    if (text.isBlank()) haptics.error() else haptics.success()
                    runOperation { present(engine.recognize(text)) }
                }
            },
            onError = { error ->
                if (foreground && listening) {
                    listening = false
                    runOperation {
                        recovery = if (error == SpeechFailure.PERMISSION_DENIED) Recovery.PERMISSIONS else Recovery.SPEECH
                        haptics.error()
                        say(when (error) {
                            SpeechFailure.PERMISSION_DENIED -> R.string.permissions_denied
                            SpeechFailure.UNAVAILABLE -> R.string.speech_unavailable
                            SpeechFailure.MICROPHONE_UNAVAILABLE -> R.string.microphone_unavailable
                            SpeechFailure.NETWORK -> R.string.speech_network
                            SpeechFailure.LANGUAGE_UNAVAILABLE -> R.string.speech_language
                            SpeechFailure.NO_SPEECH, SpeechFailure.UNKNOWN -> R.string.recognition_empty
                        })
                    }
                }
            },
        )
    }

    private suspend fun ensureVoice(): Boolean {
        if (voiceReady) return true
        working = true
        refresh()
        voiceReady = voice.initialize()
        working = false
        if (!voiceReady) voiceFailed()
        else if (recovery == Recovery.TTS) recovery = null
        return voiceReady
    }

    private suspend fun voiceFailed() {
        recovery = Recovery.TTS
        voiceReady = false
        speaking = false
        working = false
        haptics.error()
        refresh()
        // When the TTS engine itself is missing, TalkBack is the available spoken fallback.
        actionsChannel.send(PlatformAction.AnnounceVoiceFailure(string(R.string.tts_unavailable)))
    }

    private suspend fun present(transition: Transition) {
        refresh()
        transition.prompt?.let { speakMessage(resolve(it)) }
    }

    private suspend fun repeatCurrentStep() {
        when (engine.state.phase) {
            ConversationPhase.ORIGIN -> say(R.string.origin_request)
            ConversationPhase.CONFIRM_ORIGIN -> say(R.string.origin_confirmation, engine.state.origin.orEmpty())
            ConversationPhase.DESTINATION -> say(R.string.destination_request)
            ConversationPhase.CONFIRM_DESTINATION -> say(R.string.destination_confirmation, engine.state.destination.orEmpty())
            ConversationPhase.COMPLETE -> say(R.string.complete, engine.state.origin.orEmpty(), engine.state.destination.orEmpty())
            else -> Unit
        }
    }

    private suspend fun say(@StringRes resource: Int, vararg arguments: String): Boolean =
        speakMessage(string(resource, *arguments))

    private suspend fun speakMessage(message: String): Boolean {
        recognition.cancel()
        listening = false
        lastMessage = message
        speaking = true
        refresh()
        val completed = voice.speak(message)
        speaking = false
        if (!completed) voiceFailed()
        refresh()
        return completed
    }

    private fun runOperation(block: suspend () -> Unit) {
        val currentGeneration = ++generation
        operation?.cancel()
        voice.stop()
        recognition.cancel()
        listening = false
        operation = viewModelScope.launch {
            try {
                block()
            } finally {
                if (currentGeneration == generation) {
                    working = false
                    speaking = false
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        val action = when {
            listening -> R.string.action_listening
            speaking -> R.string.action_speaking
            working && !voiceReady -> R.string.action_initializing
            working -> R.string.action_locating
            recovery == Recovery.TTS -> R.string.tts_unavailable
            recovery == Recovery.SETTINGS -> R.string.action_settings
            recovery == Recovery.PERMISSIONS -> R.string.action_permissions
            recovery == Recovery.LOCATION -> R.string.action_location_retry
            recovery == Recovery.SPEECH -> R.string.action_speech_retry
            else -> when (engine.state.phase) {
                ConversationPhase.INITIALIZING -> R.string.action_initializing
                ConversationPhase.PERMISSIONS -> R.string.action_permissions
                ConversationPhase.LOCATING -> R.string.action_locating
                ConversationPhase.ORIGIN -> R.string.action_origin
                ConversationPhase.CONFIRM_ORIGIN -> R.string.action_confirm_origin
                ConversationPhase.DESTINATION -> R.string.action_destination
                ConversationPhase.CONFIRM_DESTINATION -> R.string.action_confirm_destination
                ConversationPhase.COMPLETE -> R.string.action_restart
                ConversationPhase.ERROR -> R.string.action_location_retry
            }
        }
        mutableState.value = MainUiState(
            conversation = if (recovery != null) engine.state.copy(phase = ConversationPhase.ERROR) else engine.state,
            actionDescription = string(action),
            listening = listening,
            speaking = speaking,
            busy = working || speaking || awaitingPermissions,
        )
    }

    private fun resolve(prompt: Prompt): String = string(
        when (prompt.key) {
            PromptKey.PERMISSIONS_EXPLANATION -> R.string.permissions_explanation
            PromptKey.LOCATING -> R.string.locating
            PromptKey.ORIGIN_REQUEST -> R.string.origin_request
            PromptKey.ORIGIN_CONFIRMATION -> R.string.origin_confirmation
            PromptKey.ORIGIN_RETRY -> R.string.origin_retry
            PromptKey.DESTINATION_REQUEST -> R.string.destination_request
            PromptKey.DESTINATION_CONFIRMATION -> R.string.destination_confirmation
            PromptKey.DESTINATION_RETRY -> R.string.destination_retry
            PromptKey.CONFIRMATION_UNCLEAR -> R.string.confirmation_unclear
            PromptKey.RECOGNITION_EMPTY -> R.string.recognition_empty
            PromptKey.COMPLETE -> R.string.complete
        },
        *prompt.arguments.toTypedArray(),
    )

    private fun string(@StringRes resource: Int, vararg arguments: String): String =
        getApplication<Application>().getString(resource, *arguments)

    override fun onCleared() {
        recognition.close()
        voice.close()
        actionsChannel.close()
        super.onCleared()
    }
}
