package com.example.sonaruna.guia

enum class Phase { IDLE, ORIGIN, CONFIRM_ORIGIN, DESTINATION, CONFIRM, NAVIGATING }

/** What to say, in order; when [listen] is true the app opens the microphone after the last sentence. */
data class Reply(val say: List<String>, val listen: Boolean, val phase: Phase)

/**
 * The SonarUna conversation, ported from web/conversation.js: where are you (or confirm the GPS suggestion) ->
 * destination -> confirmation -> step-by-step route along the tactile paving. It neither speaks nor listens: it
 * receives what was heard and returns what to say. The session lives only in memory.
 */
class Conversation(private val map: FloorMap) {
    var phase = Phase.IDLE
        private set
    var steps: List<Instruction> = emptyList()
        private set

    private var origin: Place? = null
    private var originHeading: Double? = null
    private var destination: Place? = null
    private var candidate: Place? = null
    private var suggested: Place? = null
    private var index = 0
    private var silences = 0

    private fun reply(say: List<String>, listen: Boolean = true) = Reply(say, listen, phase)
    private fun reply(vararg say: String) = reply(say.toList())

    private fun originQuestion() = "Onde você está agora? Diga a sala ou o lugar, por exemplo, ${map.exemploOrigem}."
    private fun destinationQuestion() = "Para onde você quer ir? Diga, por exemplo, ${map.exemplo}."
    private fun suggestionQuestion() = "Pela sua localização, parece que você está ${suggested!!.emLugar}. Está certo? Diga sim ou não."
    private fun confirmQuestion() = "Você quer ir ${candidate!!.paraLugar}. Está certo? Diga sim ou não."
    private fun stepLine(i: Int) = "Etapa ${i + 1} de ${steps.size}. ${steps[i].texto}"

    /** [guess] comes from the GPS, when there is a reading. A suggestion becomes a question; the GPS never decides. */
    fun start(guess: OriginGuess? = null): Reply {
        phase = Phase.ORIGIN
        origin = null
        originHeading = null
        destination = null
        candidate = null
        suggested = null
        steps = emptyList()
        index = 0
        silences = 0
        val question = when (guess) {
            is OriginGuess.Suggestion -> {
                suggested = guess.place
                phase = Phase.CONFIRM_ORIGIN
                listOf(suggestionQuestion())
            }
            OriginGuess.OutsideCampus -> listOf(
                "Pela sua localização, você não parece estar ${map.nome?.let { "no $it" } ?: "no mapa"}.",
                originQuestion(),
            )
            else -> listOf(originQuestion())
        }
        val hello = if (map.nome != null) "Olá. Eu sou o SonarUna, no ${map.nome}." else "Olá. Eu sou o SonarUna."
        return reply(listOf(hello) + question)
    }

    fun stop(): Reply {
        phase = Phase.IDLE
        return reply(listOf("Orientação encerrada. Toque no botão para começar de novo."), listen = false)
    }

    /** Back from a pause: repeats where the conversation was, without losing the route. */
    fun resume(): Reply {
        if (phase == Phase.IDLE) return start()
        silences = 0
        return reply(listOf("Retomando.") + currentQuestion().say)
    }

    /** [alternatives]: what speech recognition understood, best hypothesis first. */
    fun hear(alternatives: List<String>): Reply {
        if (phase == Phase.IDLE) return reply(emptyList(), listen = false)
        val heard = alternatives.map(String::trim).filter(String::isNotEmpty)
        if (heard.isEmpty()) return silence()
        silences = 0

        if (heard.any { classifyCommand(it) == Command.ASSIST }) {
            return reply(listOf("O pedido de ajuda por ligação ainda não está neste aplicativo. Se precisar, peça ajuda a alguém por perto.") + currentQuestion().say)
        }
        for (text in heard) {
            when (classifyCommand(text)) {
                Command.STOP -> return stop()
                Command.RESTART -> return start()
                Command.HELP -> return help()
                else -> Unit
            }
        }
        if (phase != Phase.NAVIGATING) {
            for (text in heard) {
                val command = classifyCommand(text)
                if (command == Command.REPEAT) return currentQuestion()
                if (command == Command.BACK && phase != Phase.ORIGIN) {
                    phase = if (phase == Phase.CONFIRM) Phase.DESTINATION else Phase.ORIGIN
                    candidate = null
                    return currentQuestion()
                }
            }
        }
        return when (phase) {
            Phase.ORIGIN -> hearOrigin(heard)
            Phase.CONFIRM_ORIGIN -> hearOriginConfirmation(heard)
            Phase.DESTINATION -> hearDestination(heard)
            Phase.CONFIRM -> hearConfirmation(heard)
            Phase.NAVIGATING -> hearNavigation(heard)
            Phase.IDLE -> reply(emptyList(), listen = false)
        }
    }

    /** Nothing was heard (silence, noise or a passing microphone error). */
    fun silence(): Reply {
        if (phase == Phase.IDLE) return reply(emptyList(), listen = false)
        silences++
        if (silences >= MAX_SILENCE_RETRIES) {
            phase = Phase.IDLE
            silences = 0
            return reply(listOf("Não ouvi nenhuma resposta. Toque no botão quando quiser continuar."), listen = false)
        }
        return reply("Não ouvi. Pode repetir?")
    }

    private fun help(): Reply {
        val common = "Você pode dizer a qualquer momento: repetir, voltar, recomeçar ou parar."
        return when (phase) {
            Phase.ORIGIN -> reply(originQuestion(), common)
            Phase.CONFIRM_ORIGIN -> reply("Diga sim se você está nesse lugar, ou diga onde você está.", common)
            Phase.DESTINATION -> reply(destinationQuestion(), common)
            Phase.CONFIRM -> reply("Diga sim para confirmar o destino ou não para escolher outro.", common)
            Phase.NAVIGATING -> reply("Diga pronto quando terminar a etapa e quiser a próxima.", common)
            Phase.IDLE -> reply(listOf("Toque no botão para começar."), listen = false)
        }
    }

    private fun currentQuestion(): Reply = when (phase) {
        Phase.ORIGIN -> reply(originQuestion())
        Phase.CONFIRM_ORIGIN -> reply(suggestionQuestion())
        Phase.DESTINATION -> reply(destinationQuestion())
        Phase.CONFIRM -> reply(confirmQuestion())
        Phase.NAVIGATING -> reply(stepLine(index))
        Phase.IDLE -> reply(emptyList(), listen = false)
    }

    private fun hearOrigin(heard: List<String>): Reply {
        var several: List<Place>? = null
        for (text in heard) {
            when (val match = map.findPlace(text)) {
                is PlaceMatch.Found -> return setOrigin(match.place)
                is PlaceMatch.Several -> several = several ?: match.places
                is PlaceMatch.Ambiguous -> several = several ?: match.places
                else -> Unit
            }
        }
        if (several != null) return reply("Encontrei mais de um lugar: ${several.joinToString(", ") { it.nome }}. Diga o nome completo.")
        return reply("Não reconheci esse lugar. ${originQuestion()}")
    }

    private fun setOrigin(place: Place): Reply {
        origin = place
        originHeading = map.exitHeading(place.id)
        suggested = null
        phase = Phase.DESTINATION
        return reply("Você está ${place.emLugar}. ${destinationQuestion()}")
    }

    /** Answer to "it looks like you are at X": yes confirms, no asks, naming another place corrects directly. */
    private fun hearOriginConfirmation(heard: List<String>): Reply {
        if (heard.any { namesOtherPlace(it, suggested!!) }) return hearOrigin(heard)
        for (text in heard) {
            when (classifyYesNo(text)) {
                YesNo.YES -> return setOrigin(suggested!!)
                YesNo.NO -> {
                    suggested = null
                    phase = Phase.ORIGIN
                    return reply("Tudo bem. ${originQuestion()}")
                }
                null -> Unit
            }
        }
        return reply("Não entendi. Diga sim ou não, ou diga onde você está.")
    }

    private fun namesOtherPlace(text: String, current: Place): Boolean {
        val match = map.findPlace(text)
        return match !is PlaceMatch.None && !(match is PlaceMatch.Found && match.place.id == current.id)
    }

    private fun hearDestination(heard: List<String>): Reply {
        val from = origin!!.id
        var unknown: PlaceMatch.UnknownRoom? = null
        var ambiguous: PlaceMatch.Ambiguous? = null
        for (text in heard) {
            var match = map.findPlace(text)
            // Several places with the same name ("banheiro"): go to the nearest one.
            if (match is PlaceMatch.Several) {
                val nearest = match.places.minBy { map.routeWeight(from, it.id) }
                if (map.routeWeight(from, nearest.id).isFinite()) match = PlaceMatch.Found(nearest)
            }
            when (match) {
                is PlaceMatch.Found -> {
                    if (match.place.id == from) return reply("Você já está aí. Diga outro destino.")
                    candidate = match.place
                    phase = Phase.CONFIRM
                    return reply(confirmQuestion())
                }
                is PlaceMatch.UnknownRoom -> unknown = unknown ?: match
                is PlaceMatch.Ambiguous -> ambiguous = ambiguous ?: match
                else -> Unit
            }
        }
        ambiguous?.let { return reply("Encontrei mais de uma sala: ${it.places.joinToString(", ") { place -> place.nome }}. Diga o nome completo da sala.") }
        unknown?.let { return reply("Não encontrei a sala ${it.code} neste mapa. ${destinationQuestion()}") }
        return reply("Não entendi o destino. ${destinationQuestion()}")
    }

    private fun hearConfirmation(heard: List<String>): Reply {
        // Naming another place changes the destination ("não, a secretaria") instead of "quero" counting as yes.
        if (heard.any { namesOtherPlace(it, candidate!!) }) return hearDestination(heard)
        for (text in heard) {
            when (classifyYesNo(text)) {
                YesNo.YES -> return beginRoute()
                YesNo.NO -> {
                    candidate = null
                    phase = Phase.DESTINATION
                    return reply("Certo. Para onde você quer ir?")
                }
                null -> Unit
            }
        }
        return reply("Não entendi. Diga apenas sim ou não.")
    }

    private fun beginRoute(): Reply {
        val from = origin!!
        val to = candidate!!
        val segments = map.planRoute(from.id, to.id)
        if (segments.isNullOrEmpty()) {
            phase = Phase.DESTINATION
            return reply("Não encontrei um caminho ${from.deLugar} ${to.ateLugar}. Escolha outro destino.")
        }
        destination = to
        steps = map.buildInstructions(originHeading, segments)
        index = 0
        phase = Phase.NAVIGATING
        return reply("Rota ${to.ateLugar}, em ${plural(steps.size, "etapa", "etapas")}, seguindo o piso tátil.", stepLine(0))
    }

    private fun hearNavigation(heard: List<String>): Reply {
        for (text in heard) {
            when (classifyCommand(text)) {
                Command.NEXT -> {
                    if (index + 1 >= steps.size) {
                        phase = Phase.IDLE
                        return reply(listOf("Você chegou ${destination!!.emLugar}. Toque no botão para uma nova orientação."), listen = false)
                    }
                    index++
                    return reply(stepLine(index))
                }
                Command.REPEAT -> return reply(stepLine(index))
                Command.BACK -> {
                    index = maxOf(0, index - 1)
                    return reply(stepLine(index))
                }
                else -> Unit
            }
        }
        return reply("Não entendi. Diga pronto para a próxima etapa, repetir, voltar ou parar.")
    }

    companion object {
        const val MAX_SILENCE_RETRIES = 3
    }
}
