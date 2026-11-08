package com.example.sonaruna.guia

enum class Phase { IDLE, ORIGIN, CONFIRM_ORIGIN, DESTINATION, NAVIGATING }

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

    /** The GPS is still searching (after [startLocating]); whatever the person says first wins. */
    var locating = false
        private set

    /** A destination said before the origin ("quero ir para a 10B"): it applies as soon as the origin is known. */
    private var pendingDestination: String? = null

    /** What a person may say: the names of rooms and services, for the recognizer to lean towards. */
    fun placeNames(): List<String> = map.points.values
        .filter { it.tipo == "sala" || it.tipo == "servico" || it.tipo == "acesso" }
        .flatMap { listOf(it.nome) + it.aliases }
        .distinct()

    private fun reply(say: List<String>, listen: Boolean = true) = Reply(say, listen, phase)
    private fun reply(vararg say: String) = reply(say.toList())

    // Short questions; the examples are in the help.
    private fun originQuestion() = "Onde você está agora?"
    private fun destinationQuestion() = "Para onde você quer ir?"
    private fun suggestionQuestion() = "Parece que você está ${suggested!!.emLugar}. Está certo?"

    /** The first step says how many there are and teaches "pronto"; the others go straight to what to do. */
    private fun stepLine(i: Int): String {
        if (i == 0) return "Etapa 1 de ${steps.size}. ${steps[0].texto}"
        val label = if (i == steps.size - 1) "Última etapa." else "Etapa ${i + 1}."
        return "$label ${steps[i].texto.replace(promptSuffix, "")}"
    }

    // At UNAERP rooms are named by number, then block ("10B"); the internal code has the block first.
    private fun roomName(code: String) = code.replace(blockFirst, "$2$1")

    private fun reset() {
        phase = Phase.ORIGIN
        origin = null
        originHeading = null
        destination = null
        candidate = null
        suggested = null
        steps = emptyList()
        index = 0
        silences = 0
        locating = false
        pendingDestination = null
    }

    /** What the GPS suggests becomes a question; the GPS never decides on its own where the person is. */
    private fun originFromGps(guess: OriginGuess?): List<String> = when (guess) {
        is OriginGuess.Suggestion -> {
            suggested = guess.place
            phase = Phase.CONFIRM_ORIGIN
            listOf(suggestionQuestion())
        }
        OriginGuess.OutsideCampus -> listOf(
            "Você não parece estar ${map.nome?.let { "no $it" } ?: "no mapa"}.",
            originQuestion(),
        )
        else -> listOf(originQuestion())
    }

    /** Start with the GPS already read (or without GPS): just the question, no introduction. */
    fun start(guess: OriginGuess? = null): Reply {
        reset()
        return reply(originFromGps(guess))
    }

    /**
     * Start while the GPS is still searching: the app already listens. If the person says where they are before the
     * GPS answers, that wins; otherwise [locationFound] asks with what the GPS found.
     */
    fun startLocating(): Reply {
        reset()
        locating = true
        return reply("Procurando sua localização.")
    }

    /** The GPS answer after [startLocating]. Null when the person already answered first. */
    fun locationFound(guess: OriginGuess?): Reply? {
        if (!locating || phase != Phase.ORIGIN || origin != null) return null
        locating = false
        return reply(originFromGps(guess))
    }

    fun stop(): Reply {
        phase = Phase.IDLE
        return reply(listOf("Orientação encerrada. Toque no botão para começar de novo."), listen = false)
    }

    /** Back from a pause: repeats where the conversation was, without losing the route. */
    fun resume(): Reply {
        if (phase == Phase.IDLE) return start()
        silences = 0
        if (phase == Phase.NAVIGATING) {
            return reply("Retomando a etapa ${index + 1} de ${steps.size}. Diga pronto para a próxima, ou repetir para ouvir de novo.")
        }
        return reply(listOf("Retomando.") + currentQuestion().say)
    }

    /** [alternatives]: what speech recognition understood, best hypothesis first. */
    fun hear(alternatives: List<String>): Reply {
        if (phase == Phase.IDLE) return reply(emptyList(), listen = false)
        val heard = alternatives.map(String::trim).filter(String::isNotEmpty)
        if (heard.isEmpty()) return silence()
        silences = 0
        // The person spoke: whatever the GPS finds later no longer changes the conversation.
        locating = false

        if (heard.any { classifyCommand(it) == Command.ASSIST }) {
            return reply(listOf("O pedido de ajuda por ligação ainda não está neste aplicativo. Se precisar, peça ajuda a alguém por perto.") + currentQuestion().say)
        }
        for (text in heard) {
            when (classifyCommand(text)) {
                Command.STOP -> return stop()
                Command.RESTART -> return start()
                Command.DESTINATION -> return if (origin != null) changeDestination() else currentQuestion()
                Command.REMAINING -> return remaining()
                Command.OVERVIEW -> return overview()
                Command.WHERE -> return whereAmI()
                Command.HELP -> return help()
                else -> Unit
            }
        }
        if (phase != Phase.NAVIGATING) {
            for (text in heard) {
                val command = classifyCommand(text)
                if (command == Command.REPEAT) return currentQuestion()
                if (command == Command.BACK && phase != Phase.ORIGIN) {
                    phase = Phase.ORIGIN
                    candidate = null
                    return currentQuestion()
                }
            }
        }
        if (phase in setOf(Phase.ORIGIN, Phase.CONFIRM_ORIGIN, Phase.DESTINATION)) {
            hearTrip(heard)?.let { return it }
        }
        return when (phase) {
            Phase.ORIGIN -> hearOrigin(heard)
            Phase.CONFIRM_ORIGIN -> hearOriginConfirmation(heard)
            Phase.DESTINATION -> hearDestination(heard)
            Phase.NAVIGATING -> hearNavigation(heard)
            Phase.IDLE -> reply(emptyList(), listen = false)
        }
    }

    /**
     * Nothing was heard (silence, noise or a passing microphone error). After the first tap the person never has to
     * tap again: the microphone keeps listening until they say "parar". On questions the app asks again a few
     * times and then just listens; on a route it just listens.
     */
    fun silence(): Reply {
        if (phase == Phase.IDLE) return reply(emptyList(), listen = false)
        // On a route, silence is the person walking: the microphone keeps listening, quietly, until "pronto".
        // While the GPS searches, silence is just waiting: the GPS speaks next (locationFound).
        if (phase == Phase.NAVIGATING || locating) return reply(emptyList())
        silences++
        // After asking again a few times, it just listens, so the app does not talk on and on.
        if (silences >= MAX_SILENCE_RETRIES) return reply(emptyList())
        return reply("Não ouvi. Pode repetir?")
    }

    private fun noRouteYet(): Reply = reply(listOf("Ainda não há uma rota.") + currentQuestion().say)

    /** "Quanto falta?": how many steps are left after the current one. */
    private fun remaining(): Reply {
        if (phase != Phase.NAVIGATING) return noRouteYet()
        val rest = steps.size - index - 1
        val to = destination!!.ateLugar
        return reply(
            if (rest == 0) "Esta é a última etapa $to."
            else "Você está na etapa ${index + 1} de ${steps.size}. Depois desta, ${if (rest == 1) "falta 1 etapa" else "faltam $rest etapas"} $to.",
        )
    }

    /** "Repasse o caminho": the steps left, from the current one to the destination, and where the person is in them. */
    private fun overview(): Reply {
        if (phase != Phase.NAVIGATING) return noRouteYet()
        val rest = steps.drop(index).mapIndexed { i, step ->
            "Etapa ${index + i + 1}: ${step.texto.replace(promptSuffix, "")}"
        }
        return reply(listOf("Caminho ${destination!!.ateLugar}.") + rest + "Agora você está na etapa ${index + 1}. Quando terminar, diga pronto.")
    }

    /** "Onde estou?": the starting place, or where the last finished step ended. */
    private fun whereAmI(): Reply {
        if (phase == Phase.NAVIGATING) {
            if (index == 0) return reply("Você está ${origin!!.emLugar}, no começo da rota.", stepLine(index))
            val done = map.points.getValue(steps[index - 1].ponto)
            return reply("Se você terminou a etapa $index, está ${done.emLugar}.", stepLine(index))
        }
        val here = origin?.let { "Você está ${it.emLugar}." } ?: "Ainda não sei onde você está."
        return reply(listOf(here) + currentQuestion().say)
    }

    /** A new destination without losing where the person is: halfway, where the last finished step ended. */
    private fun changeDestination(): Reply {
        if (phase == Phase.NAVIGATING && index > 0) {
            val done = steps[index - 1]
            origin = map.points.getValue(done.ponto)
            originHeading = done.rumo
        }
        candidate = null
        destination = null
        steps = emptyList()
        index = 0
        phase = Phase.DESTINATION
        return reply("Certo. Para onde você quer ir?")
    }

    private fun help(): Reply {
        val common = "Você pode dizer a qualquer momento: repetir, voltar, recomeçar ou parar."
        return when (phase) {
            Phase.ORIGIN -> reply("Diga onde você está, por exemplo, ${map.exemploOrigem}. Pode dizer também para onde quer ir.", common)
            Phase.CONFIRM_ORIGIN -> reply("Diga sim se você está nesse lugar, ou diga onde você está.", common)
            Phase.DESTINATION -> reply("Diga para onde quer ir, por exemplo, ${map.exemplo}.", common)
            Phase.NAVIGATING -> reply("Diga pronto quando terminar a etapa e quiser a próxima. Para trocar o destino, diga o novo lugar.", common)
            Phase.IDLE -> reply(listOf("Toque no botão para começar."), listen = false)
        }
    }

    private fun currentQuestion(): Reply = when (phase) {
        Phase.ORIGIN -> reply(originQuestion())
        Phase.CONFIRM_ORIGIN -> reply(suggestionQuestion())
        Phase.DESTINATION -> reply(destinationQuestion())
        Phase.NAVIGATING -> reply(stepLine(index))
        Phase.IDLE -> reply(emptyList(), listen = false)
    }

    private fun hearOrigin(heard: List<String>): Reply {
        var several: List<Place>? = null
        var unknown: PlaceMatch.UnknownRoom? = null
        for (text in heard) {
            when (val match = map.findPlace(text)) {
                is PlaceMatch.Found -> return setOrigin(match.place)
                is PlaceMatch.Several -> several = several ?: match.places
                is PlaceMatch.Ambiguous -> several = several ?: match.places
                is PlaceMatch.UnknownRoom -> unknown = unknown ?: match
                else -> Unit
            }
        }
        if (several != null) return reply("Encontrei mais de um lugar: ${several.joinToString(", ") { it.nome }}. Diga o nome completo.")
        unknown?.let { return reply("Não encontrei a sala ${roomName(it.code)} neste mapa. $ROOM_TIP") }
        return reply("Não reconheci esse lugar. ${originQuestion()}")
    }

    private fun setOrigin(place: Place): Reply {
        origin = place
        originHeading = map.exitHeading(place.id)
        suggested = null
        phase = Phase.DESTINATION
        pendingDestination?.let { text ->
            pendingDestination = null
            routeTo(text)?.let { return it }
        }
        return reply("Você está ${place.emLugar}. ${destinationQuestion()}")
    }

    /** Origin and destination said together: goes straight, saying from where to where. Null if not found. */
    private fun routeTo(text: String): Reply? {
        val from = origin!!.id
        var match = map.findPlace(text)
        if (match is PlaceMatch.Several) match = PlaceMatch.Found(nearestOf(match.places))
        if (match !is PlaceMatch.Found || match.place.id == from) return null
        candidate = match.place
        return beginRoute(fromOrigin = true)
    }

    /** Among several places with the same name ("banheiro"), the nearest to where the person is. */
    private fun nearestOf(places: List<Place>): Place = places.minBy { map.routeWeight(origin!!.id, it.id) }

    /**
     * "Estou na 3A e quero ir para a 10B": with origin and destination in one phrase, the app goes straight to the
     * route. Only the destination, before the origin is known ("quero ir para a 10B"): the app keeps the destination
     * and asks where the person is. Null when the phrase says nothing about a destination.
     */
    private fun hearTrip(heard: List<String>): Reply? {
        val originKnown = phase != Phase.ORIGIN && phase != Phase.CONFIRM_ORIGIN
        for (text in heard) {
            val trip = splitTrip(text)
            val wanted = trip.destino ?: continue
            var from = trip.origem?.let { map.findPlace(it) } ?: PlaceMatch.None
            val answer = trip.origem?.let { classifyYesNo(it) }
            // "Sim, quero ir para a 15B" after "it looks like you are at X": confirms X and goes.
            if (from !is PlaceMatch.Found && phase == Phase.CONFIRM_ORIGIN && answer == YesNo.YES) from = PlaceMatch.Found(suggested!!)
            if (from is PlaceMatch.Found) {
                // setOrigin goes straight to the destination; if it is not recognized, it asks again.
                pendingDestination = wanted
                return setOrigin(from.place)
            }
            if (!originKnown && from !is PlaceMatch.None) {
                // The origin said is not on the map ("3B") or is ambiguous: keep the destination, ask only the origin.
                pendingDestination = wanted
                suggested = null
                phase = Phase.ORIGIN
                return hearOrigin(listOf(trip.origem!!))
            }
            if (originKnown || map.findPlace(wanted) is PlaceMatch.None) continue
            pendingDestination = wanted
            if (phase == Phase.CONFIRM_ORIGIN && answer != YesNo.NO) return reply(suggestionQuestion())
            suggested = null
            phase = Phase.ORIGIN
            return reply(originQuestion())
        }
        return null
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

    /**
     * The destination becomes a route right away, with no confirmation question: the summary says the place, and
     * naming another place on the way changes it. [avoid]: the destination the person just changed. If the recognizer
     * hears the same place again as the best hypothesis ("3B" when they said "10B"), the next hypothesis with another
     * place wins.
     */
    private fun hearDestination(heard: List<String>, avoid: String? = null): Reply {
        val from = origin!!.id
        var unknown: PlaceMatch.UnknownRoom? = null
        var ambiguous: PlaceMatch.Ambiguous? = null
        var same: Place? = null
        for (text in heard) {
            var match = map.findPlace(text)
            if (match is PlaceMatch.Several) {
                val nearest = nearestOf(match.places)
                if (map.routeWeight(from, nearest.id).isFinite()) match = PlaceMatch.Found(nearest)
            }
            when (match) {
                is PlaceMatch.Found -> {
                    if (match.place.id == avoid) {
                        same = same ?: match.place
                        continue
                    }
                    if (match.place.id == from) return reply("Você já está aí. Diga outro destino.")
                    candidate = match.place
                    return beginRoute()
                }
                is PlaceMatch.UnknownRoom -> unknown = unknown ?: match
                is PlaceMatch.Ambiguous -> ambiguous = ambiguous ?: match
                else -> Unit
            }
        }
        same?.let {
            candidate = it
            return beginRoute()
        }
        ambiguous?.let { return reply("Encontrei mais de uma sala: ${it.places.joinToString(", ") { place -> place.nome }}. Diga o nome completo da sala.") }
        unknown?.let { return reply("Não encontrei a sala ${roomName(it.code)} neste mapa. $ROOM_TIP") }
        return reply("Não entendi o destino. ${destinationQuestion()}")
    }

    /** [fromOrigin]: the person just said where they are; the summary says from where to where. */
    private fun beginRoute(fromOrigin: Boolean = false): Reply {
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
        val going = if (fromOrigin) "${from.deLugar.replaceFirstChar(Char::uppercase)} ${to.paraLugar}" else "Indo ${to.paraLugar}"
        return reply("$going, em ${plural(steps.size, "etapa", "etapas")}.", stepLine(0))
    }

    private fun hearNavigation(heard: List<String>): Reply {
        // "E agora quero ir para o banheiro": a destination said with "quero ir", "para"... wins before "e agora"
        // counts as "pronto".
        if (heard.any { text -> splitTrip(text).destino?.let { map.findPlace(it) !is PlaceMatch.None } == true }) {
            val previous = destination!!.id
            changeDestination()
            return hearDestination(heard, avoid = previous)
        }
        for (text in heard) {
            // "Pronto", "foi" or any yes ("beleza", "isso", "certo"): the step is done.
            when (classifyCommand(text) ?: if (classifyYesNo(text) == YesNo.YES) Command.NEXT else null) {
                Command.NEXT -> {
                    if (index + 1 >= steps.size) {
                        // Arrived: the destination becomes the new starting place and the app already listens for the next.
                        val arrived = destination!!
                        setOrigin(arrived)
                        candidate = null
                        destination = null
                        steps = emptyList()
                        index = 0
                        return reply("Você chegou ${arrived.emLugar}. Diga outro destino, ou parar para encerrar.")
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
        // Naming a place halfway ("quero ir para o banheiro") changes the destination from where the person is.
        if (heard.any { map.findPlace(it) !is PlaceMatch.None }) {
            val previous = destination!!.id
            changeDestination()
            return hearDestination(heard, avoid = previous)
        }
        // "Não" on the way: the destination heard was wrong; ask again without losing where the person is.
        if (heard.any { classifyYesNo(it) == YesNo.NO && normalize(it).split(' ').size <= 4 }) return changeDestination()
        return reply("Não entendi. Diga pronto para a próxima etapa, repetir, voltar ou parar.")
    }

    companion object {
        const val MAX_SILENCE_RETRIES = 3
        private val promptSuffix = Regex(" Quando (terminar|chegar), diga pronto\\.$")
        private val blockFirst = Regex("^([A-Z]+)(\\d+)$")

        // The recognizer mixes up short numbers ("dez bê" turning into "3B"); with the block first it usually gets it.
        private const val ROOM_TIP = "Tente dizer o bloco e o número, por exemplo: bloco B, sala 10."
    }
}
