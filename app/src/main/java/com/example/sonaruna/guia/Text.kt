package com.example.sonaruna.guia

import java.text.Normalizer
import java.util.Locale

private val marks = Regex("\\p{M}+")
private val separators = Regex("[^a-z0-9]+")
private val letterDigitBoundary = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")
private val spaces = Regex("\\s+")

/** Lowercase, no accents or punctuation, letters and digits split apart ("Á-15," -> "a 15"). Same as web/engine.js. */
fun normalize(text: String): String {
    val plain = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    return spaces.replace(letterDigitBoundary.replace(separators.replace(plain, " "), " ").trim(), " ")
}

// Letter names of the blocks, so the voice reads "5B" as "5 bê": some voices read "5B" as "5 billion".
private val letterSounds = mapOf('A' to "á", 'B' to "bê", 'C' to "cê", 'D' to "dê", 'E' to "é", 'F' to "éfe", 'G' to "gê", 'H' to "agá", 'I' to "i", 'J' to "jota", 'K' to "cá", 'L' to "éle", 'M' to "ême", 'N' to "êne", 'O' to "ó", 'P' to "pê", 'Q' to "quê", 'R' to "érre", 'S' to "ésse")
private val roomCode = Regex("""\b(\d+)([A-S])\b""")

// Acronyms said as words at the university ("UNAERP", "LIAPE"): in capitals, some voices spell them letter by letter.
private val acronym = Regex("""\b([A-Z])([A-Z]{2,})\b""")

/**
 * Text for the voice: room "5B" becomes "5 bê", "UNAERP" becomes "Unaerp". The text on screen stays as it is. Same as
 * forSpeech in web/engine.js.
 */
fun forSpeech(text: String): String = roomCode
    .replace(text) { match -> "${match.groupValues[1]} ${letterSounds.getValue(match.groupValues[2][0])}" }
    .let { spoken -> acronym.replace(spoken) { it.groupValues[1] + it.groupValues[2].lowercase() } }

// Words a phrase cannot end on: the person paused in the middle ("estou na ... 3A", "quero ir para a ... 10B").
private val midPhraseWords = setOf(
    "na", "no", "nas", "nos", "para", "pra", "pro", "a", "o", "e", "em", "de", "do", "da", "ao", "ate", "perto",
    "sala", "bloco", "estou", "to", "tou", "quero", "ir", "vou",
)

/**
 * The recognizer ended the phrase at a pause in the middle of it: the last word cannot end it, and the phrase is not
 * already an answer (a command, a yes or a no: "onde estou", "sim, estou").
 */
fun endsMidPhrase(text: String): Boolean {
    val normalized = normalize(text)
    val words = normalized.split(' ')
    val last = words.last()
    if (last.isEmpty() || last !in midPhraseWords) return false
    // "estou na 3A" ends in "3 a" and "bloco e" in a block letter: the letter closes the room, the phrase is whole.
    val before = words.getOrNull(words.size - 2)
    if (last.length == 1 && before != null && (before.all(Char::isDigit) || before == "bloco")) return false
    // Only an explicit "sim"/"não" makes it an answer: "quero" counts as yes, but "quero ir para a" is unfinished.
    val explicitAnswer = containsPhrase(normalized, "sim") || containsPhrase(normalized, "nao")
    return classifyCommand(text) == null && !explicitAnswer
}

/** Whole-word phrase match on already normalized text, so "sim" never matches "simples". */
internal fun containsPhrase(normalized: String, phrase: String): Boolean = " $normalized ".contains(" $phrase ")

internal fun containsAny(normalized: String, phrases: List<String>): Boolean =
    phrases.any { containsPhrase(normalized, it) }

internal fun plural(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"
