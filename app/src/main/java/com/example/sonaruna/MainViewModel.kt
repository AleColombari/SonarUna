package com.example.sonaruna

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonaruna.guia.Conversation
import com.example.sonaruna.guia.Echo
import com.example.sonaruna.guia.FloorMap
import com.example.sonaruna.guia.GpsPoints
import com.example.sonaruna.guia.OriginGuess
import com.example.sonaruna.guia.Phase
import com.example.sonaruna.guia.Reply
import com.example.sonaruna.guia.endsMidPhrase
import com.example.sonaruna.guia.forSpeech
import com.example.sonaruna.platform.EchoCancelledAudio
import com.example.sonaruna.platform.HapticFeedback
import com.example.sonaruna.platform.LocationRepository
import com.example.sonaruna.platform.SpeechFailure
import com.example.sonaruna.platform.SpeechOutcome
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
 * One reply being spoken while the microphone already listens (cleaned audio only). [words]: what the app has started
 * to say, in order, so the microphone hearing the loudspeaker (echo) is not taken for the person. [active] while the
 * voice is speaking.
 */
private class SpokenTurn {
    val words = mutableListOf<String>()
    var active = true
    var interrupted = false

    /** The recognition opened during the speech is still running (it then simply keeps listening afterwards). */
    var recognizing = false
}

/**
 * The voice was cut because the detector heard someone over it. [rest]: what the app had not said yet; [peak] and
 * [threshold]: how loud the cut was, to tell a person from a noise when nobody then answers.
 */
private class Cut(val rest: List<String>, val peak: Double, val threshold: Double)

/**
 * Runs the guidance conversation (guia/Conversation.kt) with the platform's voice, microphone and location.
 * After each question the microphone opens by itself (a vibration says it is ready); speaking over the app, or tapping,
 * interrupts it and listens, so the person's answer has priority. The session lives only in memory.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val location = LocationRepository(application)
    private val recognition = SpeechRecognitionManager(application)
    private val voice = TextToSpeechManager(application)
    private val haptics = HapticFeedback(application)
    private val cleanedAudio = EchoCancelledAudio(application).also { recognition.cleanedAudio = it }

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

    /** What the GPS found while the person was speaking or about to: offered only when they stay silent. */
    private var pendingGuess: OriginGuess? = null

    /** When the person was last heard (start of speech, a loud level or words), and whether in this recognition. */
    private var lastVoiceAt = 0L
    private var heardInRecognition = false

    /** The next "ready to hear" vibration is owed: the app just spoke, or the voice was cut. Silent relistens owe none. */
    private var cueOwed = false

    /** Silent relistens since the app last spoke: the first silence does not get "Não ouvi" at once. */
    private var quietRelistens = 0

    /** Network failures in a row. */
    private var networkFailures = 0

    /** "Não entendi" already said since the last answer: another unclear sound is treated as silence. */
    private var noMatchSaid = false

    /** A phrase the recognizer ended in the middle ("estou na ..."), waiting for the rest. */
    private var fragment: List<String>? = null

    private var cut: Cut? = null
    private var detectingVoice = false

    /** The reply being spoken and the sentence it is on, for [Cut.rest]. */
    private var spokenLines: List<String> = emptyList()
    private var spokenPart = 0

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
            startEchoCancellation()
            startOrResume()
        }
    }

    /** Hear the person over the app's voice without hearing the voice itself, when the phone can (see EchoCancelledAudio). */
    private suspend fun startEchoCancellation() {
        if (!permissions.microphone || !foreground) return
        // Google's recognizer takes the cleaned audio only with pt-BR offline. Known missing before the first answer,
        // that answer goes straight to the plain microphone instead of failing first (it had to be said twice).
        if (cleanedAudio.supported && recognition.offlinePortugueseInstalled() == false) cleanedAudio.markUnsupported()
        if (!foreground) return
        if (cleanedAudio.supported) {
            // The recognizer hears our own recording: call path for the whole conversation.
            if (cleanedAudio.start()) cleanedAudio.holdCallPath(true) else cleanedAudio.prepare()
        } else {
            // Only the detector: the recorder is built once now, the call path is used only while the app speaks.
            cleanedAudio.prepare()
        }
    }

    private fun stopEchoCancellation() {
        detectingVoice = false
        cleanedAudio.stop()
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
        stopEchoCancellation()
        listening = false
        speaking = false
        working = false
        fragment = null
        cut = null
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
                startEchoCancellation()
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
            // Already listening: a tap "to talk" must not cut the microphone. A recognizer that is not hearing yet
            // (stuck starting) is opened again; otherwise the vibration says it is hearing.
            if (recognition.ready) haptics.start() else {
                cueOwed = true
                startRecognition()
            }
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
                networkFailures = 0
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
        pendingGuess = null
        if (!permissions.location) return deliver(current.conversation.start())
        val conversation = current.conversation
        // Outside the operation: the person's answers start new operations, and they must not cancel the reading.
        locating = viewModelScope.launch {
            val position = location.currentPosition()
            val guess = current.gps.suggestOrigin(position)
            // The decision and the accuracy only, never the coordinates.
            Log.i(TAG, "GPS: precisao=${position?.precisao?.toInt() ?: -1} m -> " + when (guess) {
                is OriginGuess.Suggestion -> "sugere ${guess.place.id} a ${guess.distancia.toInt()} m"
                else -> guess.javaClass.simpleName
            })
            if (!conversation.locating) return@launch
            pendingGuess = guess
            // Never over the app's voice ("Procurando..."), never while the person speaks or is about to: then the
            // GPS waits for a silence (recognitionFailed), and an answer makes it obsolete (hear() stops locating).
            while (conversation.locating && pendingGuess === guess && (speaking || personMayBeSpeaking())) {
                delay(GPS_WAIT_MILLIS)
            }
            if (pendingGuess !== guess) return@launch
            pendingGuess = null
            val found = conversation.locationFound(guess) ?: return@launch
            Log.i(TAG, "GPS respondeu antes da pessoa")
            // In the background the state is already updated; coming back, resume() asks the GPS question.
            if (foreground) runOperation { deliver(found) }
        }
        deliver(conversation.startLocating())
    }

    /** Listening, and the person is speaking, spoke a moment ago, or the microphone is not even hearing yet. */
    private fun personMayBeSpeaking(): Boolean = listening &&
        (heardInRecognition || fragment != null || !recognition.ready || SystemClock.uptimeMillis() - lastVoiceAt < VOICE_RECENT_MILLIS)

    /**
     * Speaks the reply in order; then listens if the conversation expects an answer. With the cleaned audio the
     * microphone already listens while the voice speaks; otherwise the detector notices the person speaking over the
     * voice. [detect] false: no cutting by voice (after a cut that lost the answer, so it does not cut itself again).
     */
    private suspend fun deliver(reply: Reply, detect: Boolean = true) {
        val canListen = reply.listen && foreground && permissions.microphone
        val lines = reply.say.filter(String::isNotBlank)
        if (lines.isNotEmpty()) {
            // The app spoke: the next ready microphone vibrates, and a silence counts again from zero.
            cueOwed = true
            quietRelistens = 0
        }
        val turn = if (canListen && cleanedAudio.active) SpokenTurn() else null
        turn?.let { startRecognition(duringSpeech = it) }
        spokenLines = lines
        spokenPart = 0
        val detecting = detect && turn == null && canListen && lines.isNotEmpty() && startVoiceDetection()
        // The whole reply queued at once: no gap between its sentences, so the voice flows. Only what the app has
        // started saying can come back as echo through the microphone (also as the voice reads it: "10 bê").
        val spoken = lines.isEmpty() || turn?.interrupted == true ||
            speakMessage(lines, turn) { i ->
                spokenPart = i
                if (i == 0) cleanedAudio.voiceStarted()
                turn?.words?.addAll(Echo.words(lines[i]) + Echo.words(forSpeech(lines[i])))
            }
        turn?.active = false
        // Interrupted: the recognition that started during the speech has the floor and delivers the answer.
        if (turn?.interrupted == true) return
        if (!spoken || !reply.listen || !foreground) {
            // Also when the voice failed: otherwise the listening opened during the speech kept reopening by itself.
            if (!spoken) recognition.cancel()
            endVoiceDetection {}
            if (!detectingVoice) cleanedAudio.leaveForListening()
            refresh()
            return
        }
        when {
            // Still listening since the middle of the speech: keep it. Restarting right away made Google's service
            // refuse the new recognizer (errors 5 and 11) while the old one was closing.
            turn?.recognizing == true -> {
                listening = true
                refresh()
                if (recognition.ready) giveCue()
            }
            detectingVoice -> {
                // The microphone is freed (off the main thread) before the recognizer opens it.
                listening = true
                refresh()
                val currentGeneration = generation
                endVoiceDetection { if (currentGeneration == generation && foreground && listening) startRecognition() }
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

    /** The person's answer has priority: stop speaking and open the microphone, once it is free. */
    private fun interruptAndListen() {
        generation++
        val currentGeneration = generation
        operation?.cancel()
        voice.stop()
        speaking = false
        listening = true
        cueOwed = true
        fragment = null
        refresh()
        endVoiceDetection { if (currentGeneration == generation && foreground) startRecognition() }
    }

    /** Voice on the call path and the cleaned microphone watching for the person (see EchoCancelledAudio). */
    private fun startVoiceDetection(): Boolean {
        if (!cleanedAudio.canDetect) return false
        val currentGeneration = generation
        detectingVoice = cleanedAudio.startDetecting { detector ->
            if (currentGeneration == generation && speaking && foreground) {
                Log.i(TAG, "A pessoa falou por cima; voz interrompida (detector)")
                val pending = interruptedRest()
                cut = Cut(pending, detector.peakLevel, detector.threshold())
                interruptAndListen()
            }
        }
        return detectingVoice
    }

    /** What the app had not finished saying when it was cut: the sentence it was on and the ones after it. */
    private fun interruptedRest(): List<String> = spokenLines.drop(spokenPart)

    /** Ends the detection and frees the microphone for the recognizer, then runs [then] (on the main thread). */
    private fun endVoiceDetection(then: () -> Unit) {
        if (!detectingVoice) {
            then()
            return
        }
        detectingVoice = false
        cleanedAudio.pauseDetecting(then)
    }

    private fun giveCue() {
        if (!cueOwed) return
        cueOwed = false
        haptics.start()
    }

    /**
     * One answer. With [duringSpeech], recognition starts while the app speaks and only takes over the conversation
     * when it hears the person (not the loudspeaker's echo); until then, if it ends, it reopens while the voice goes on.
     */
    private fun startRecognition(duringSpeech: SpokenTurn? = null) {
        recognition.cancel()
        recovery = null
        heardInRecognition = false
        var personSpoke = duringSpeech == null
        if (duringSpeech == null) {
            listening = true
            refresh()
            // In call mode Google's microphone got the call's processing and sometimes heard almost nothing.
            if (!cleanedAudio.active) cleanedAudio.leaveForListening()
        }
        fun takeOver(hypotheses: List<String>, final: Boolean): Boolean {
            if (personSpoke) return true
            if (duringSpeech == null) return false
            val heard = hypotheses.first()
            // Only counts are logged (and the text in test builds): enough to check the echo on a real phone.
            val words = Echo.words(heard).size
            if (!duringSpeech.active) {
                // The voice ended, but this recognition was open during it: the tail of the app's own voice may still
                // arrive. Only what is left beyond that tail is the person; a phrase still open does not decide yet.
                val rest = Echo.answerAfterSpeech(heard, duringSpeech.words)
                if (rest.isEmpty()) {
                    Log.i(TAG, "Ouviu depois da fala: $words palavras, tratado como eco" + if (debugLog) " ($heard)" else "")
                    return false
                }
                if (final) personSpoke = true
                return true
            }
            // While the voice speaks, the person needs words the app did not say: 2 in a phrase still open (a single
            // word may be noise or a misheard echo), 1 when the recognizer closes it.
            if (!Echo.interrupts(heard, duringSpeech.words, needed = if (final) 1 else MIN_PARTIAL_WORDS)) {
                Log.i(TAG, "Ouviu durante a fala: $words palavras, tratado como eco" + if (debugLog) " ($heard)" else "")
                return false
            }
            Log.i(TAG, "Ouviu durante a fala: $words palavras, a pessoa falou por cima; voz interrompida")
            personSpoke = true
            interruptSpeech(duringSpeech)
            return true
        }
        duringSpeech?.recognizing = true
        recognition.start(
            // The vibration says "speak now" only once the recognizer really hears: vibrating before it was ready made
            // people answer too early, the start of the answer was lost, and it had to be said again.
            onReady = { if (foreground && listening) giveCue() },
            onActivity = {
                if (duringSpeech == null || !duringSpeech.active) {
                    lastVoiceAt = SystemClock.uptimeMillis()
                    heardInRecognition = true
                }
            },
            onPartial = { hypotheses ->
                if (duringSpeech == null || !duringSpeech.active) lastVoiceAt = SystemClock.uptimeMillis()
                // Echo of the app's own voice is not written; the person's words appear as they speak.
                if (foreground && takeOver(hypotheses, final = false)) {
                    showHeard(listOfNotNull(fragment?.first(), hypotheses.first()).joinToString(" "))
                }
            },
            onResult = { hypotheses ->
                duringSpeech?.recognizing = false
                if (foreground && takeOver(hypotheses, final = true) && listening) {
                    // Without the stretch of the app's voice the recognizer may have caught before the answer.
                    val answer = duringSpeech?.let { turn ->
                        hypotheses.map { Echo.answerAfterSpeech(it, turn.words) }.filter(String::isNotEmpty).ifEmpty { hypotheses }
                    } ?: hypotheses
                    hearAnswer(answer, hypotheses)
                } else if (foreground && duringSpeech != null && !personSpoke) {
                    // Only echo: while the voice speaks, listen again during it; after it, listen normally.
                    if (duringSpeech.active) reopenDuringSpeech(duringSpeech) else listenAgainAfterEcho()
                }
            },
            onError = { error ->
                duringSpeech?.recognizing = false
                if (!foreground) return@start
                if (error == SpeechFailure.CLEANED_AUDIO_REFUSED) {
                    // Plain microphone from now on. While the app speaks it would hear the voice itself: deliver()
                    // listens after the speech. Otherwise listen again now.
                    if (duringSpeech == null || !duringSpeech.active) startRecognition()
                    return@start
                }
                if ((personSpoke || duringSpeech?.active == false) && listening) {
                    val heardSpeech = heardInRecognition
                    // The rest of a phrase never came: the part that did is the answer.
                    fragment?.let { partial ->
                        fragment = null
                        answerWith(partial, partial)
                        return@start
                    }
                    listening = false
                    runOperation { recognitionFailed(error, heardSpeech) }
                } else if (duringSpeech != null && !personSpoke) {
                    // Silence or echo while the app speaks: listen again; real errors surface after the speech.
                    Log.i(TAG, "Escuta durante a fala terminou ($error); reabrindo")
                    reopenDuringSpeech(duringSpeech)
                }
            },
        )
    }

    /**
     * What the person said. Google ends a phrase at the first short pause, whatever the app asks for: when it stops on
     * a word that cannot end it ("estou na", "quero ir para a"), the app keeps listening, quietly, and joins the rest.
     */
    private fun hearAnswer(answer: List<String>, raw: List<String>) {
        val joined = fragment?.let { first -> answer.map { "${first.first()} $it" } + first } ?: answer
        val wasJoined = fragment != null
        fragment = null
        if (!wasJoined && endsMidPhrase(joined.first())) {
            Log.i(TAG, "Frase cortada no meio; ouvindo o resto")
            fragment = joined
            showHeard(joined.first())
            startRecognition()
            return
        }
        answerWith(joined, raw)
    }

    /** The answer goes to the conversation, which says what comes next. */
    private fun answerWith(joined: List<String>, raw: List<String>) {
        cut = null
        networkFailures = 0
        noMatchSaid = false
        if (debugLog) Log.d(TAG, "Ouviu: ${raw.joinToString(" | ")} -> ${joined.first()}")
        showHeard(joined.first())
        listening = false
        haptics.success()
        runOperation { deliver(guide!!.conversation.hear(joined)) }
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
        cueOwed = true
        giveCue()
    }

    /** The recognition handed over from the speech heard only the app's voice: open a fresh one for the answer. */
    private fun listenAgainAfterEcho() {
        val currentGeneration = generation
        viewModelScope.launch {
            delay(REOPEN_DELAY_MILLIS)
            if (currentGeneration == generation && listening && foreground) startRecognition()
        }
    }

    private fun reopenDuringSpeech(turn: SpokenTurn) {
        val currentGeneration = generation
        viewModelScope.launch {
            delay(REOPEN_DELAY_MILLIS)
            if (currentGeneration == generation && turn.active && !turn.interrupted && foreground) startRecognition(turn)
        }
    }

    /** Listen again without saying anything (and without vibrating: nothing new was asked). */
    private fun listenQuietly() {
        startRecognition()
    }

    private suspend fun recognitionFailed(error: SpeechFailure, heardSpeech: Boolean) {
        val conversation = guide?.conversation ?: return
        val unclear = error == SpeechFailure.NO_MATCH && heardSpeech && !noMatchSaid &&
            conversation.phase != Phase.NAVIGATING && !conversation.locating
        val silent = !unclear && (error == SpeechFailure.NO_SPEECH || error == SpeechFailure.UNKNOWN || error == SpeechFailure.NO_MATCH)

        // After the voice was cut by someone speaking over it.
        cut?.let { last ->
            cut = null
            if (silent && last.rest.isNotEmpty() && last.peak < FALSE_CUT_MARGIN * last.threshold) {
                // Nobody answered and the cut was barely above the threshold: a noise cut the voice. The app says the
                // rest of what it was saying, and the voice is cut less easily from now on.
                cleanedAudio.sensitivity = (cleanedAudio.sensitivity * FALSE_CUT_SENSITIVITY).coerceAtMost(MAX_SENSITIVITY)
                Log.i(TAG, "Corte falso (pico=${last.peak.toInt()} limite=${last.threshold.toInt()}); retomando a fala")
                deliver(Reply(last.rest, listen = true, phase = conversation.phase), detect = false)
                return
            }
            if (silent || unclear) {
                // A real cut whose short answer ended before the microphone opened: ask once, without cutting by voice.
                Log.i(TAG, "Corte real, resposta perdida")
                deliver(Reply(listOf("Pode repetir?"), listen = true, phase = conversation.phase), detect = false)
                return
            }
        }

        if (silent) {
            // What the GPS found while the person might have spoken: now there is a silence to say it in.
            pendingGuess?.let { guess ->
                pendingGuess = null
                conversation.locationFound(guess)?.let { found ->
                    deliver(found)
                    return
                }
            }
            // The first silence after a question: just listen again (Google gives up after a few seconds, often as
            // the person is about to answer). Only then does the conversation ask again.
            if (quietRelistens < QUIET_RELISTENS && conversation.phase != Phase.NAVIGATING) {
                quietRelistens++
                Log.i(TAG, "Silêncio ($error); ouvindo de novo sem falar")
                listenQuietly()
                return
            }
            // On a route (or while waiting for the GPS) silence() asks nothing: listening again owes no vibration.
            deliver(conversation.silence())
            return
        }
        if (unclear) {
            // Something was said, but not understood. Only once: noise must not make the app ask again and again.
            noMatchSaid = true
            deliver(Reply(listOf("Não entendi. Pode repetir?"), listen = true, phase = conversation.phase), detect = false)
            return
        }
        if (error == SpeechFailure.NETWORK && ++networkFailures == 1) {
            deliver(Reply(listOf("Sem conexão com a internet. Fale de novo."), listen = true, phase = conversation.phase))
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
        if (!voiceReady) {
            voiceFailed()
        } else {
            // One kind of voice for the whole run (call path when the phone has an echo canceller), so it sounds the same.
            voice.useCallPath(cleanedAudio.canDetect)
            if (recovery == Recovery.TTS) recovery = null
        }
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

    /** A system message: no answer is heard after it, so the call path is left right away. */
    private suspend fun say(@StringRes resource: Int): Boolean =
        speakMessage(listOf(string(resource))).also { cleanedAudio.leaveForListening() }

    /** With [turn], the microphone stays open while speaking; an interruption by the person is not a voice failure. */
    private suspend fun speakMessage(lines: List<String>, turn: SpokenTurn? = null, onLine: ((Int) -> Unit)? = null): Boolean {
        if (turn == null) {
            recognition.cancel()
            listening = false
        }
        speaking = true
        saidText = lines.joinToString(" ")
        refresh()
        // The voice plays on the call path, which the canceller needs; it waits for the switch, so no word is lost.
        if (cleanedAudio.canDetect) cleanedAudio.enterForSpeech()
        val outcome = voice.speak(lines, onLine)
        speaking = false
        if (outcome == SpeechOutcome.FAILED) voiceFailed()
        refresh()
        return outcome == SpeechOutcome.DONE
    }

    private fun runOperation(block: suspend () -> Unit) {
        val currentGeneration = ++generation
        operation?.cancel()
        endVoiceDetection {}
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
        cleanedAudio.close()
        recognition.close()
        voice.close()
        actionsChannel.close()
        super.onCleared()
    }

    private companion object {
        const val MAP_ASSET = "unaerp.json"
        const val GPS_ASSET = "unaerp-pontos.geojson"

        const val REOPEN_DELAY_MILLIS = 300L

        /** How often the GPS answer checks whether it may speak. */
        const val GPS_WAIT_MILLIS = 200L

        /** The person spoke less than this ago: they may still be talking. */
        const val VOICE_RECENT_MILLIS = 1_500L

        /** Silences heard again quietly before the conversation asks again ("Não ouvi"). */
        const val QUIET_RELISTENS = 1

        /** A cut whose peak was under this many times the threshold, with nobody answering, was a noise. */
        const val FALSE_CUT_MARGIN = 2.0
        const val FALSE_CUT_SENSITIVITY = 1.5
        const val MAX_SENSITIVITY = 2.5

        /** Words a partial hypothesis needs, beyond echo, to interrupt the voice before the phrase ends. */
        const val MIN_PARTIAL_WORDS = 2
        const val TAG = "SonarUna"
    }
}
