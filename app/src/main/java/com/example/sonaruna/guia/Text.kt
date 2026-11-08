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

/** Text for the voice: room "5B" becomes "5 bê". The text on screen stays "5B". Same as forSpeech in web/engine.js. */
fun forSpeech(text: String): String =
    roomCode.replace(text) { match -> "${match.groupValues[1]} ${letterSounds.getValue(match.groupValues[2][0])}" }

/** Whole-word phrase match on already normalized text, so "sim" never matches "simples". */
internal fun containsPhrase(normalized: String, phrase: String): Boolean = " $normalized ".contains(" $phrase ")

internal fun containsAny(normalized: String, phrases: List<String>): Boolean =
    phrases.any { containsPhrase(normalized, it) }

internal fun plural(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"
