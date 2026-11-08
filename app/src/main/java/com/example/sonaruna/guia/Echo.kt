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
        if (a == b) return true
        if (a.length < 4 || b.length < 4 || a.any(Char::isDigit) || b.any(Char::isDigit)) return false
        return withinOneEdit(a, b)
    }

    /** The words of [heard] that the app did not say. */
    fun newWords(heard: List<String>, spoken: List<String>): List<String> =
        heard.filter { word -> spoken.none { sameWord(word, it) } }

    /**
     * Drops from the start of [heard] the longest run of two or more words that repeats, in order, a stretch of what
     * the app said: "diga pronto sim" after "... quando terminar, diga pronto" leaves "sim". A single word is kept
     * (it may well be the person's "pronto"), as is everything after the run.
     */
    fun withoutLeadingEcho(heard: List<String>, spoken: List<String>): List<String> {
        var longest = 0
        for (start in spoken.indices) {
            var k = 0
            while (k < heard.size && start + k < spoken.size && sameWord(heard[k], spoken[start + k])) k++
            if (k > longest) longest = k
        }
        return if (longest >= 2) heard.drop(longest) else heard
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
    private fun withinOneEdit(a: String, b: String): Boolean =
        a.length == b.length && a.indices.count { a[it] != b[it] } <= 1
}
