package com.example.sonaruna.conversation

import java.text.Normalizer
import java.util.Locale

/** A room named by an optional block letter and a number: block A, room 15 is "A15". */
data class RoomCode(val block: Char?, val number: Int) {
    val canonical: String get() = "${block ?: ""}$number"
}

/**
 * Extracts a room code from a spoken answer. Recognizers return "A15", "a 15" or "a quinze"
 * for the same room, so all of them are reduced to one code. The block may also come after the
 * number ("30A", "trinta a"); the canonical form is always letter first ("A30").
 * Pure Kotlin, testable without Android.
 */
object RoomCodeParser {
    private const val UNIT = 1
    private const val TENS = 2
    private const val HUNDREDS = 3

    private class NumberWord(val value: Int, val rank: Int, val teen: Boolean = false)

    private sealed interface Term {
        data class Word(val text: String) : Term
        data class Num(val value: Int) : Term
    }

    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")
    private val letterDigitBoundary = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")

    private val numberWords: Map<String, NumberWord> = buildMap {
        listOf("zero", "um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove")
            .forEachIndexed { value, word -> put(word, NumberWord(value, UNIT)) }
        put("uma", NumberWord(1, UNIT))
        put("duas", NumberWord(2, UNIT))
        listOf(
            "dez", "onze", "doze", "treze", "catorze", "quinze",
            "dezesseis", "dezessete", "dezoito", "dezenove",
        ).forEachIndexed { index, word -> put(word, NumberWord(10 + index, TENS, teen = true)) }
        put("quatorze", NumberWord(14, TENS, teen = true))
        listOf("vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
            .forEachIndexed { index, word -> put(word, NumberWord(20 + 10 * index, TENS)) }
        put("cem", NumberWord(100, HUNDREDS))
        put("cento", NumberWord(100, HUNDREDS))
        listOf(
            "duzentos", "trezentos", "quatrocentos", "quinhentos",
            "seiscentos", "setecentos", "oitocentos", "novecentos",
        ).forEachIndexed { index, word -> put(word, NumberWord(200 + 100 * index, HUNDREDS)) }
    }

    /** Courtesy words at the end of a phrase, skipped when looking for the letter that closes "30 A". */
    private val trailingCourtesy = listOf(listOf("por", "favor"), listOf("obrigado"), listOf("obrigada"))

    fun parse(text: String): RoomCode? {
        val terms = toTerms(withoutTrailingCourtesy(tokenize(text)))
        return blockAndRoom(terms) ?: letterAndNumber(terms) ?: numberAndLetter(terms) ?: roomOnly(terms)
    }

    private fun withoutTrailingCourtesy(tokens: List<String>): List<String> {
        for (phrase in trailingCourtesy) {
            if (tokens.size > phrase.size && tokens.takeLast(phrase.size) == phrase) {
                return tokens.dropLast(phrase.size)
            }
        }
        return tokens
    }

    private fun isBlockLetter(term: Term): Boolean =
        term is Term.Word && term.text.length == 1 && term.text[0] in 'a'..'z' && term.text !in "eo"

    /** "bloco B sala 3" and "sala 3 do bloco B", in any order. */
    private fun blockAndRoom(terms: List<Term>): RoomCode? {
        val block = letterAfter(terms, "bloco") ?: return null
        val room = numberAfter(terms, "sala") ?: return null
        return RoomCode(block.uppercaseChar(), room)
    }

    /**
     * "A15", "a quinze", "sala a vinte e dois". The letters "e" and "o" are skipped because
     * they are far more likely to be the conjunction or the article, as in "sala 12 e 15".
     */
    private fun letterAndNumber(terms: List<Term>): RoomCode? {
        for (index in 0 until terms.size - 1) {
            val letter = terms[index]
            val number = terms[index + 1]
            if (isBlockLetter(letter) && letter is Term.Word && number is Term.Num) {
                return RoomCode(letter.text[0].uppercaseChar(), number.value)
            }
        }
        return null
    }

    /**
     * "30A", "trinta a": the block after the number. Only when the letter closes the phrase,
     * because in "sala 20 a esquerda" the "a" is the preposition.
     */
    private fun numberAndLetter(terms: List<Term>): RoomCode? {
        if (terms.size < 2) return null
        val number = terms[terms.size - 2]
        val letter = terms.last()
        if (isBlockLetter(letter) && letter is Term.Word && number is Term.Num) {
            return RoomCode(letter.text[0].uppercaseChar(), number.value)
        }
        return null
    }

    private fun roomOnly(terms: List<Term>): RoomCode? =
        numberAfter(terms, "sala")?.let { RoomCode(null, it) }

    private fun letterAfter(terms: List<Term>, keyword: String): Char? {
        for (index in 0 until terms.size - 1) {
            val current = terms[index]
            val next = terms[index + 1]
            if (current is Term.Word && current.text == keyword && next is Term.Word && next.text.length == 1) {
                return next.text[0]
            }
        }
        return null
    }

    private fun numberAfter(terms: List<Term>, keyword: String): Int? {
        for (index in 0 until terms.size - 1) {
            val current = terms[index]
            val next = terms[index + 1]
            if (current is Term.Word && current.text == keyword && next is Term.Num) return next.value
        }
        return null
    }

    private fun tokenize(text: String): List<String> {
        val plain = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
        val spaced = letterDigitBoundary.replace(separators.replace(plain, " "), " ")
        return spaced.split(' ').filter { it.isNotEmpty() }
    }

    private fun toTerms(tokens: List<String>): List<Term> {
        val terms = mutableListOf<Term>()
        var index = 0
        while (index < tokens.size) {
            val parsed = parseNumber(tokens, index)
            if (parsed != null) {
                terms += Term.Num(parsed.first)
                index += parsed.second
            } else {
                terms += Term.Word(tokens[index])
                index++
            }
        }
        return terms
    }

    /** Returns the value and how many tokens were used, or null when no number starts here. */
    private fun parseNumber(tokens: List<String>, start: Int): Pair<Int, Int>? {
        val first = tokens[start]
        if (first.all { it.isDigit() }) {
            return if (first.length <= 3) first.toInt() to 1 else null
        }
        var index = start
        var total = 0
        var previousRank = Int.MAX_VALUE
        var previousTeen = false
        var matched = false
        while (index < tokens.size) {
            val joined = matched && tokens[index] == "e"
            val wordIndex = if (joined) index + 1 else index
            val entry = tokens.getOrNull(wordIndex)?.let { numberWords[it] } ?: break
            if (entry.rank >= previousRank || previousTeen || (matched && entry.value == 0)) break
            total += entry.value
            previousRank = entry.rank
            previousTeen = entry.teen
            matched = true
            index = wordIndex + 1
        }
        if (!matched) return null
        // Digits spoken one by one: "um cinco" is 15.
        if (index == start + 1 && previousRank == UNIT) {
            var digits = total.toString()
            while (digits.length < 3) {
                val next = tokens.getOrNull(index)?.let { numberWords[it] } ?: break
                if (next.rank != UNIT) break
                digits += next.value
                index++
            }
            return digits.toInt() to (index - start)
        }
        return total to (index - start)
    }
}
