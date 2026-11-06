package com.example.sonaruna

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonaruna.guia.Conversation
import com.example.sonaruna.guia.FloorMap
import com.example.sonaruna.guia.GpsPoints
import com.example.sonaruna.guia.Phase
import com.example.sonaruna.guia.Reply
import com.example.sonaruna.guia.normalize
import com.example.sonaruna.platform.HapticFeedback
import com.example.sonaruna.platform.LocationRepository
import com.example.sonaruna.platform.SpeechFailure
import com.example.sonaruna.platform.SpeechRecognitionManager
import com.example.sonaruna.platform.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /** What the person is saying (live) and the app's last sentence, written under the button. Memory only. */
    val heard: String = "",
    val said: String = "",
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
 * One reply being spoken while the microphone already listens. [words]: what the app has started to say, so the
 * microphone hearing the loudspeaker (echo) is not taken for the person. [active] while the voice is speaking.
 */
private class SpokenTurn {
    val words = mutableSetOf<String>()
    var active = true
    var interrupted = false

    /** The recognition opened during the speech is still running (it then simply keeps listening afterwards). */
    var recognizing = false
}

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

    // Test builds only (debuggable): logcat shows what the recognizer heard, to check misrecognitions on the phone.
    private val debugLog = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val mutableState = MutableStateFlow(MainUiState())
    val state = mutableState.asStateFlow()
    private val actionsChannel = Channel<PlatformAction>(Channel.BUFFERED)
    val actions = actionsChannel.receiveAsFlow()

    private var guide: Guide? = null
    private var permissions = PermissionStatus(location = false, microphone = false, microphoneBlocked = false)
    private var foreground = false
    private var voiceReady = false
    private var awaitingPermissions = false
    private var recovery: Recovery? = null
    private var listening = false
    private var speaking = false
    private var working = false
    private var operation: Job? = null
    private var generation = 0L

    /** The GPS reading that runs while the app already listens (see [begin]). */
    private var locating: Job? = null

    /** The person has started a phrase on the plain microphone (partial results arrived), not yet finished. */
    private var hearingPhrase = false

    private var heardText = ""
    private var saidText = ""

    private val phase: Phase get() = guide?.conversation?.phase ?: Phase.IDLE

    /**
     * Opening (or coming back to) the app starts by itself, with no tap: it asks for the microphone if needed,
     * then reads the GPS and starts the conversation, or resumes where it was.
     */
    fun onForeground(status: PermissionStatus) {
        foreground = true
        permissions = status
        if (awaitingPermissions) return
        runOperation {
            if (!ensureVoice()) return@runOperation
            if (!ensureGuide()) return@runOperation
            startOrResume()
        }
    }

    private suspend fun startOrResume() {
        when {
            !permissions.microphone && permissions.microphoneBlocked -> explainMissingMicrophone()
            !permissions.microphone -> requestPermissions()
            phase == Phase.IDLE -> begin()
            else -> {
                recovery = null
                deliver(guide!!.conversation.resume())
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
            // Already listening: a tap "to talk" must not cut the microphone. The person just speaks.
            haptics.start()
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
                    startOrResume()
                }
            }
            recovery == Recovery.MAP -> runOperation { if (ensureGuide()) startOrResume() }
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

    /**
     * A new orientation. Without location it just asks where the person is. With location it says only "Procurando
     * sua localização." and already listens while the GPS reads: if the person says where they are (or where they
     * are going) first, that wins and the reading is ignored; otherwise the reading becomes, at most, a question.
     */
    private suspend fun begin() {
        val current = guide ?: return
        recovery = null
        locating?.cancel()
        if (!permissions.location) return deliver(current.conversation.start())
        val conversation = current.conversation
        // Outside the operation: the person's answers start new operations, and they must not cancel the reading.
        locating = viewModelScope.launch {
            val guess = current.gps.suggestOrigin(location.currentPosition())
            // Do not cut a phrase in the middle: if the person is speaking, their answer decides first.
            while (hearingPhrase && conversation.locating) delay(PHRASE_WAIT_MILLIS)
            val found = conversation.locationFound(guess) ?: return@launch
            Log.i(TAG, "GPS respondeu antes da pessoa")
            // In the background the state is already updated; coming back, resume() asks the GPS question.
            if (foreground) runOperation { deliver(found) }
        }
        deliver(conversation.startLocating())
    }

    /**
     * Speaks the reply in order; then listens if the conversation expects an answer. When it expects one, the
     * microphone already listens while the voice speaks, so the person can answer over it (as in the web version).
     */
    private suspend fun deliver(reply: Reply) {
        val turn = if (VOICE_INTERRUPTION && reply.listen && foreground && permissions.microphone) SpokenTurn() else null
        turn?.let { startRecognition(duringSpeech = it) }
        // The whole reply queued at once: no gap between its sentences, so the voice flows.
        val lines = reply.say.filter(String::isNotBlank)
        if (lines.isNotEmpty() && turn?.interrupted != true) {
            // Only what the app has started saying can come back as echo through the microphone.
            if (!speakMessage(lines, turn) { i -> turn?.words?.addAll(normalize(lines[i]).split(' ')) } &&
                turn?.interrupted != true
            ) return
        }
        turn?.active = false
        // Interrupted: the recognition that started during the speech has the floor and delivers the answer.
        if (turn?.interrupted == true) return
        when {
            !reply.listen || !foreground -> refresh()
            // Still listening since the middle of the speech: keep it. Restarting right away made Google's service
            // refuse the new recognizer (errors 5 and 11) while the old one was closing.
            turn?.recognizing == true -> {
                listening = true
                refresh()
                haptics.start()
            }
            else -> startRecognition()
        }
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
        loaded?.let { recognition.biasing = it.conversation.placeNames() }
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

    /**
     * One answer. With [duringSpeech], recognition starts while the app speaks and only takes over the conversation
     * when it hears the person (not the loudspeaker's echo); until then, if it ends, it reopens while the voice goes on.
     */
    private fun startRecognition(duringSpeech: SpokenTurn? = null) {
        recognition.cancel()
        recovery = null
        hearingPhrase = false
        var personSpoke = duringSpeech == null
        if (duringSpeech == null) {
            listening = true
            refresh()
            haptics.start()
        }
        fun takeOver(hypotheses: List<String>, final: Boolean): Boolean {
            if (personSpoke) return true
            if (duringSpeech == null) return false
            // The speech is over: whatever is heard now is the person's answer.
            if (!duringSpeech.active) {
                personSpoke = true
                return true
            }
            // Only counts are logged, never what was said: enough to check the interruption on a real phone.
            val words = normalize(hypotheses.first()).split(' ').count(String::isNotEmpty)
            if (isEcho(hypotheses.first(), duringSpeech)) {
                Log.i(TAG, "Ouviu durante a fala: $words palavras, tratado como eco")
                return false
            }
            // A single word halfway may be noise; it only interrupts once the recognizer closes the phrase.
            if (!final && words < MIN_PARTIAL_WORDS) return false
            Log.i(TAG, "Ouviu durante a fala: $words palavras, a pessoa falou por cima; voz interrompida")
            personSpoke = true
            interruptSpeech(duringSpeech)
            return true
        }
        duringSpeech?.recognizing = true
        recognition.start(
            onPartial = { hypotheses ->
                // Speech over (or never there): this is the person's phrase in progress.
                if (duringSpeech == null || !duringSpeech.active) hearingPhrase = true
                // Echo of the app's own voice is not written; the person's words appear as they speak.
                if (foreground && takeOver(hypotheses, final = false)) showHeard(hypotheses.first())
            },
            onResult = { hypotheses ->
                duringSpeech?.recognizing = false
                hearingPhrase = false
                if (foreground && takeOver(hypotheses, final = true) && listening) {
                    val answer = duringSpeech?.let { turn ->
                        hypotheses.map { withoutEcho(it, turn) }.filter(String::isNotEmpty).ifEmpty { hypotheses }
                    } ?: hypotheses
                    if (debugLog) Log.d(TAG, "Ouviu: ${hypotheses.joinToString(" | ")} -> ${answer.first()}")
                    showHeard(answer.first())
                    listening = false
                    haptics.success()
                    runOperation { deliver(guide!!.conversation.hear(answer)) }
                } else if (foreground && duringSpeech != null && !personSpoke) {
                    reopenDuringSpeech(duringSpeech)
                }
            },
            onError = { error ->
                duringSpeech?.recognizing = false
                hearingPhrase = false
                if (!foreground) return@start
                if ((personSpoke || duringSpeech?.active == false) && listening) {
                    listening = false
                    runOperation { recognitionFailed(error) }
                } else if (duringSpeech != null && !personSpoke) {
                    // Silence or echo while the app speaks: listen again; real errors surface after the speech.
                    Log.i(TAG, "Escuta durante a fala terminou ($error); reabrindo")
                    reopenDuringSpeech(duringSpeech)
                }
            },
        )
    }

    private fun showHeard(text: String) {
        heardText = text
        refresh()
    }

    /** The person started talking over the app: their answer has priority, so the voice stops now. */
    private fun interruptSpeech(turn: SpokenTurn) {
        turn.interrupted = true
        voice.stop()
        speaking = false
        listening = true
        refresh()
        haptics.start()
    }

    private fun reopenDuringSpeech(turn: SpokenTurn) {
        val currentGeneration = generation
        viewModelScope.launch {
            delay(REOPEN_DELAY_MILLIS)
            if (currentGeneration == generation && turn.active && !turn.interrupted && foreground) startRecognition(turn)
        }
    }

    /**
     * The recognition opened during the speech may bring the end of the app's question before the answer ("... para
     * onde você quer ir banheiro"). The leading words the app said are dropped, so the answer is not read with them.
     */
    private fun withoutEcho(text: String, turn: SpokenTurn): String {
        val words = normalize(text).split(' ').filter(String::isNotEmpty)
        val start = words.indexOfFirst { it !in turn.words }.let { if (it < 0) words.size else it }
        return if (start == 0) text else words.drop(start).joinToString(" ")
    }

    /** The microphone heard the app's own voice: most heard words were already said by the app. */
    private fun isEcho(text: String, turn: SpokenTurn): Boolean {
        val words = normalize(text).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return true
        return words.count { it in turn.words } >= words.size * ECHO_RATIO
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

    private suspend fun say(@StringRes resource: Int): Boolean = speakMessage(listOf(string(resource)))

    /** With [turn], the microphone stays open while speaking; an interruption by the person is not a voice failure. */
    private suspend fun speakMessage(lines: List<String>, turn: SpokenTurn? = null, onLine: ((Int) -> Unit)? = null): Boolean {
        if (turn == null) {
            recognition.cancel()
            listening = false
        }
        speaking = true
        saidText = lines.joinToString(" ")
        refresh()
        val completed = voice.speak(lines, onLine)
        speaking = false
        if (!completed && turn?.interrupted != true) voiceFailed()
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
            heard = heardText,
            said = saidText,
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

        /** Listen while speaking, so the person can answer over the app. Set false if a phone cuts its own voice. */
        const val VOICE_INTERRUPTION = true

        /** Share of heard words that the app already said above which it is the loudspeaker's echo (as on the web). */
        const val ECHO_RATIO = 0.6
        const val REOPEN_DELAY_MILLIS = 300L

        /** How often the GPS answer checks whether the person finished the phrase they started. */
        const val PHRASE_WAIT_MILLIS = 200L

        /** Words a partial hypothesis needs, beyond echo, to interrupt the voice before the phrase ends. */
        const val MIN_PARTIAL_WORDS = 2
        const val TAG = "SonarUna"
    }
}
