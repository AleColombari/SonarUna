package com.example.sonaruna.conversation

import java.text.Normalizer
import java.util.Locale

/** A synchronous state machine. Android services and their failures stay outside it. */
class ConversationEngine(initialState: ConversationState = ConversationState()) {
    var state: ConversationState = initialState
        private set

    fun awaitPermissions(): Transition = transition(
        state.copy(phase = ConversationPhase.PERMISSIONS),
        Prompt(PromptKey.PERMISSIONS_EXPLANATION),
    )

    fun locating(): Transition = transition(
        ConversationState(phase = ConversationPhase.LOCATING),
        Prompt(PromptKey.LOCATING),
    )

    fun locationObtained(location: ApproximateLocation): Transition {
        // A cancelled request may still deliver a result after the session has moved on.
        if (state.phase != ConversationPhase.LOCATING) return unchanged()
        return transition(
            ConversationState(phase = ConversationPhase.ORIGIN, location = location),
            Prompt(PromptKey.ORIGIN_REQUEST),
        )
    }

    fun recognize(text: String): Transition {
        if (state.phase !in listeningPhases) return unchanged()
        val answer = compactSpaces(text)
        if (answer.isEmpty()) return transition(state, Prompt(PromptKey.RECOGNITION_EMPTY))

        return when (state.phase) {
            ConversationPhase.ORIGIN -> transition(
                state.copy(phase = ConversationPhase.CONFIRM_ORIGIN, origin = answer),
                Prompt(PromptKey.ORIGIN_CONFIRMATION, listOf(answer)),
            )
            ConversationPhase.CONFIRM_ORIGIN -> confirmOrigin(answer)
            ConversationPhase.DESTINATION -> transition(
                state.copy(phase = ConversationPhase.CONFIRM_DESTINATION, destination = answer),
                Prompt(PromptKey.DESTINATION_CONFIRMATION, listOf(answer)),
            )
            ConversationPhase.CONFIRM_DESTINATION -> confirmDestination(answer)
            else -> unchanged()
        }
    }

    /** A reading in the future is not reusable, including after the clock is adjusted. */
    fun restart(nowMillis: Long): Transition {
        val location = state.location?.takeIf {
            val ageMillis = nowMillis - it.timestampMillis
            it.timestampMillis <= nowMillis &&
                ageMillis in 0..LOCATION_MAX_AGE_MILLIS
        }
        return if (location != null) {
            transition(
                ConversationState(phase = ConversationPhase.ORIGIN, location = location),
                Prompt(PromptKey.ORIGIN_REQUEST),
            )
        } else {
            locating()
        }
    }

    private fun confirmOrigin(answer: String): Transition = when (confirmation(answer)) {
        Confirmation.YES -> transition(
            state.copy(phase = ConversationPhase.DESTINATION),
            Prompt(PromptKey.DESTINATION_REQUEST),
        )
        Confirmation.NO -> transition(
            state.copy(phase = ConversationPhase.ORIGIN, origin = null, destination = null),
            Prompt(PromptKey.ORIGIN_RETRY),
        )
        Confirmation.UNKNOWN -> transition(state, Prompt(PromptKey.CONFIRMATION_UNCLEAR))
    }

    private fun confirmDestination(answer: String): Transition = when (confirmation(answer)) {
        Confirmation.YES -> transition(
            state.copy(phase = ConversationPhase.COMPLETE),
            Prompt(PromptKey.COMPLETE, listOfNotNull(state.origin, state.destination)),
        )
        Confirmation.NO -> transition(
            state.copy(phase = ConversationPhase.DESTINATION, destination = null),
            Prompt(PromptKey.DESTINATION_RETRY),
        )
        Confirmation.UNKNOWN -> transition(state, Prompt(PromptKey.CONFIRMATION_UNCLEAR))
    }

    private fun transition(next: ConversationState, prompt: Prompt): Transition {
        state = next
        return Transition(next, prompt)
    }

    private fun unchanged() = Transition(state)

    private enum class Confirmation { YES, NO, UNKNOWN }

    companion object {
        const val LOCATION_MAX_AGE_MILLIS = 120_000L

        private val listeningPhases = setOf(
            ConversationPhase.ORIGIN,
            ConversationPhase.CONFIRM_ORIGIN,
            ConversationPhase.DESTINATION,
            ConversationPhase.CONFIRM_DESTINATION,
        )
        private val whitespace = Regex("[\\s\\p{Z}]+")
        private val accents = Regex("\\p{M}+")
        private val punctuation = Regex("[\\p{P}\\p{S}]+")

        private val positiveAnswers = setOf(
            "sim", "correto", "isso", "isso mesmo", "esta certo", "esta correto",
            "certo", "confirmo", "confirmado", "pode confirmar", "sim correto",
            "sim esta correto", "sim esta certo", "sim isso", "sim isso mesmo",
            "sim confirmo", "sim pode confirmar",
        )
        private val negativeAnswers = setOf(
            "nao", "errado", "incorreto", "nao esta correto", "nao esta certo",
            "esta errado", "nao esta certo nao", "nao esta correto nao", "nao e isso",
            "nao confirmo", "corrigir", "quero corrigir",
            "nao correto",
        )

        private fun compactSpaces(text: String): String = whitespace.replace(text, " ").trim()

        private fun confirmation(text: String): Confirmation {
            val normalized = compactSpaces(
                punctuation.replace(
                    accents.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
                        .lowercase(Locale.ROOT),
                    " ",
                ),
            )
            // Whole phrases avoid interpreting "não está correto" as "correto", or
            // accepting contradictory answers such as "sim, não".
            return when (normalized) {
                in positiveAnswers -> Confirmation.YES
                in negativeAnswers -> Confirmation.NO
                else -> Confirmation.UNKNOWN
            }
        }
    }
}
