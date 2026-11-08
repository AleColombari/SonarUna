package com.example.sonaruna.guia

/**
 * Telling the app's own voice (heard back through the microphone while it speaks, or right after) from the person.
 * The recognizer does not always write the echo exactly as said, so words of 4 letters or
 * more match with one letter swapped ("vire" / "vira"); short words and numbers must match exactly.
 */
object Echo {
    /** Share of a phrase that must be new words (not said by the app) for it to be the person. */
    private const val MIN_NEW_SHARE = 0.4

    fun words(text: String): List<String> = normalize(text).split(' ').filter(String::isNotEmpty)

    fun sameWord(a: String, b: String): Boolean {
        val x = canonical(a)
        val y = canonical(b)
        if (x == y) return true
        if (x.length < 4 || y.length < 4 || x.any(Char::isDigit) || y.any(Char::isDigit)) return false
        return withinOneEdit(x, y)
    }

    /** The words of [heard] that the app did not say. */
    fun newWords(heard: List<String>, spoken: List<String>): List<String> =
        heard.filter { word -> spoken.none { sameWord(word, it) } }

    /**
     * Drops from the start of [heard] the run of two or more words that the app said. The recognizer does not give
     * the echo back word for word: it drops small words ("indo para sala" for "indo para a sala") and writes numbers
     * out ("quatro" for "4"), so the run is any words the app said, in any order. "diga pronto sim" after "... diga
     * pronto" leaves "sim". A single word is kept (it may well be the person's "pronto"), as is everything after.
     */
    fun withoutLeadingEcho(heard: List<String>, spoken: List<String>): List<String> {
        val run = heard.indexOfFirst { word -> spoken.none { sameWord(word, it) } }.let { if (it < 0) heard.size else it }
        return if (run >= 2) heard.drop(run) else heard
    }

    /**
     * While the app is still speaking: the person interrupts only with enough new words (beyond the echo run at the
     * start). [needed] is 2 for a phrase still open (a single word may be noise) and 1 for a closed one.
     */
    fun interrupts(text: String, spoken: List<String>, needed: Int): Boolean {
        val rest = withoutLeadingEcho(words(text), spoken)
        if (rest.isEmpty()) return false
        val fresh = newWords(rest, spoken)
        return fresh.size >= needed && fresh.size >= rest.size * MIN_NEW_SHARE
    }

    /**
     * After the app stopped speaking, from a recognition that was already open: the person's part, without the tail
     * of the app's voice that the recognizer may still deliver. Empty when it was only echo.
     */
    fun answerAfterSpeech(text: String, spoken: List<String>): String =
        withoutLeadingEcho(words(text), spoken).joinToString(" ")

    /** One letter swapped ("vire" / "vira"); a letter more or less is a different word ("para" / "parar"). */
    /** Numbers the voice says as digits come back from the recognizer as words, and the other way around. */
    private fun canonical(word: String): String = NUMBERS[word] ?: word

    private val NUMBERS = mapOf(
        "um" to "1", "uma" to "1", "dois" to "2", "duas" to "2", "tres" to "3", "quatro" to "4", "cinco" to "5",
        "seis" to "6", "sete" to "7", "oito" to "8", "nove" to "9", "dez" to "10", "onze" to "11", "doze" to "12",
        "treze" to "13", "quatorze" to "14", "catorze" to "14", "quinze" to "15", "dezesseis" to "16",
        "dezessete" to "17", "dezoito" to "18", "dezenove" to "19", "vinte" to "20",
    )

    private fun withinOneEdit(a: String, b: String): Boolean =
        a.length == b.length && a.indices.count { a[it] != b[it] } <= 1
}
