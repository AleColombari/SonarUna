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

/** Whole-word phrase match on already normalized text, so "sim" never matches "simples". */
internal fun containsPhrase(normalized: String, phrase: String): Boolean = " $normalized ".contains(" $phrase ")

internal fun containsAny(normalized: String, phrases: List<String>): Boolean =
    phrases.any { containsPhrase(normalized, it) }

internal fun plural(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"
