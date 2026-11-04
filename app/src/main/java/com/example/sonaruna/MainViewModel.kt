package com.example.sonaruna

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonaruna.guia.Conversation
import com.example.sonaruna.guia.FloorMap
import com.example.sonaruna.guia.GpsPoints
import com.example.sonaruna.guia.Phase
import com.example.sonaruna.guia.Reply
import com.example.sonaruna.platform.HapticFeedback
import com.example.sonaruna.platform.LocationRepository
import com.example.sonaruna.platform.SpeechFailure
import com.example.sonaruna.platform.SpeechRecognitionManager
import com.example.sonaruna.platform.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Location is optional: without it the conversation asks where the person is. The microphone is required. */
data class PermissionStatus(val location: Boolean, val microphone: Boolean, val microphoneBlocked: Boolean)

data class MainUiState(
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

private enum class Recovery { PERMISSIONS, SETTINGS, SPEECH, TTS, MAP }

/** The guide loaded from the assets (data/ in the repository): map, GPS points and the conversation over them. */
private class Guide(val gps: GpsPoints, val conversation: Conversation)

/**
 * Runs the guidance conversation (guia/Conversation.kt) with the platform's voice, microphone and location.
 * After each question the microphone opens by itself; tapping while the app speaks interrupts it and listens, so
 * the person's answer has priority. The session lives only in memory: no saved state or coordinates on disk.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val location = LocationRepository(application)
    private val recognition = SpeechRecognitionManager(application)
    private val voice = TextToSpeechManager(application)
    private val haptics = HapticFeedback(application)
    private val mutableState = MutableStateFlow(MainUiState())
    val state = mutableState.asStateFlow()
    private val actionsChannel = Channel<PlatformAction>(Channel.BUFFERED)
    val actions = actionsChannel.receiveAsFlow()

    private var guide: Guide? = null
    private var permissions = PermissionStatus(location = false, microphone = false, microphoneBlocked = false)
    private var foreground = false
    private var voiceReady = false
    private var welcomed = false
    private var awaitingPermissions = false
    private var recovery: Recovery? = null
    private var listening = false
    private var speaking = false
    private var working = false
    private var operation: Job? = null
    private var generation = 0L

    private val phase: Phase get() = guide?.conversation?.phase ?: Phase.IDLE

    fun onForeground(status: PermissionStatus) {
        foreground = true
        permissions = status
        if (awaitingPermissions) return
        runOperation {
            if (!ensureVoice()) return@runOperation
            if (!ensureGuide()) return@runOperation
            if (!welcomed) {
                welcomed = true
                say(R.string.welcome)
            }
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
            if (status.microphone) {
                recovery = null
                begin()
            } else {
                explainMissingMicrophone()
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
            // A tap while listening pauses: the microphone closes until the next tap.
            recognition.cancel()
            listening = false
            runOperation { say(R.string.listening_paused) }
            return
        }
        if (speaking && phase != Phase.IDLE && recovery == null && status.microphone) {
            interruptAndListen()
            return
        }
        when {
            recovery == Recovery.TTS -> runOperation {
                if (ensureVoice()) {
                    recovery = null
                    say(R.string.welcome)
                }
            }
            recovery == Recovery.MAP -> runOperation { ensureGuide() }
            !status.microphone -> runOperation {
                if (status.microphoneBlocked) {
                    recovery = Recovery.SETTINGS
                    voice.stop()
                    actionsChannel.send(PlatformAction.OpenAppSettings)
                } else {
                    requestPermissions()
                }
            }
            phase == Phase.IDLE -> runOperation { begin() }
            else -> runOperation {
                recovery = null
                deliver(guide!!.conversation.resume())
            }
        }
    }

    /** A new orientation: one GPS reading (if allowed) becomes, at most, a question about where the person is. */
    private suspend fun begin() {
        val current = guide ?: return
        recovery = null
        val guess = if (permissions.location) {
            // Up to 9 seconds of silence would be confusing without sight: say what is happening first.
            if (!say(R.string.locating)) return
            working = true
            refresh()
            current.gps.suggestOrigin(location.currentPosition()).also { working = false }
        } else {
            null
        }
        deliver(current.conversation.start(guess))
    }

    /** Speaks the reply in order; then listens if the conversation expects an answer. */
    private suspend fun deliver(reply: Reply) {
        for (line in reply.say) {
            if (!speakMessage(line)) return
        }
        if (reply.listen && foreground) startRecognition() else refresh()
    }

    private suspend fun requestPermissions() {
        recovery = null
        if (!say(R.string.permissions_explanation)) return
        awaitingPermissions = true
        refresh()
        actionsChannel.send(PlatformAction.RequestPermissions)
    }

    private suspend fun explainMissingMicrophone() {
        recovery = if (permissions.microphoneBlocked) Recovery.SETTINGS else Recovery.PERMISSIONS
        haptics.error()
        say(if (permissions.microphoneBlocked) R.string.permissions_settings else R.string.permissions_denied)
    }

    private suspend fun ensureGuide(): Boolean {
        if (guide != null) return true
        working = true
        refresh()
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                val assets = getApplication<Application>().assets
                val map = FloorMap.parse(assets.open(MAP_ASSET).bufferedReader().use { it.readText() })
                val gps = GpsPoints.parse(assets.open(GPS_ASSET).bufferedReader().use { it.readText() }, map)
                Guide(gps, Conversation(map))
            }
        }.getOrNull()
        working = false
        guide = loaded
        if (loaded == null) {
            recovery = Recovery.MAP
            haptics.error()
            say(R.string.map_error)
            return false
        }
        if (recovery == Recovery.MAP) recovery = null
        refresh()
        return true
    }

    /** The person's answer has priority: stop speaking and open the microphone now. */
    private fun interruptAndListen() {
        generation++
        operation?.cancel()
        voice.stop()
        speaking = false
        startRecognition()
    }

    private fun startRecognition() {
        recognition.cancel()
        recovery = null
        listening = true
        refresh()
        haptics.start()
        recognition.start(
            onResult = { hypotheses ->
                if (foreground && listening) {
                    listening = false
                    haptics.success()
                    runOperation { deliver(guide!!.conversation.hear(hypotheses)) }
                }
            },
            onError = { error ->
                if (foreground && listening) {
                    listening = false
                    runOperation { recognitionFailed(error) }
                }
            },
        )
    }

    private suspend fun recognitionFailed(error: SpeechFailure) {
        if (error == SpeechFailure.NO_SPEECH || error == SpeechFailure.UNKNOWN) {
            // Silence is the conversation's business: it asks again, and stops after a few tries.
            deliver(guide!!.conversation.silence())
            return
        }
        recovery = if (error == SpeechFailure.PERMISSION_DENIED) Recovery.PERMISSIONS else Recovery.SPEECH
        haptics.error()
        say(
            when (error) {
                SpeechFailure.PERMISSION_DENIED -> R.string.permissions_denied
                SpeechFailure.UNAVAILABLE -> R.string.speech_unavailable
                SpeechFailure.MICROPHONE_UNAVAILABLE -> R.string.microphone_unavailable
                SpeechFailure.NETWORK -> R.string.speech_network
                else -> R.string.speech_language
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

    private suspend fun say(@StringRes resource: Int): Boolean = speakMessage(string(resource))

    private suspend fun speakMessage(message: String): Boolean {
        recognition.cancel()
        listening = false
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
            recovery == Recovery.MAP -> R.string.action_map_retry
            recovery == Recovery.SETTINGS -> R.string.action_settings
            recovery == Recovery.PERMISSIONS -> R.string.action_permissions
            recovery == Recovery.SPEECH -> R.string.action_speech_retry
            phase == Phase.IDLE -> R.string.action_start
            else -> R.string.action_continue
        }
        mutableState.value = MainUiState(
            actionDescription = string(action),
            listening = listening,
            speaking = speaking,
            busy = working || awaitingPermissions,
        )
    }

    private fun string(@StringRes resource: Int): String = getApplication<Application>().getString(resource)

    override fun onCleared() {
        recognition.close()
        voice.close()
        actionsChannel.close()
        super.onCleared()
    }

    private companion object {
        const val MAP_ASSET = "unaerp.json"
        const val GPS_ASSET = "unaerp-pontos.geojson"
    }
}
