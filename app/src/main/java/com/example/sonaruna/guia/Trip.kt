package com.example.sonaruna.guia

/** Where the person is and where they want to go, both normalized, from one phrase. Same as splitTrip in web/engine.js. */
data class Trip(val origem: String?, val destino: String?)

// "estou na 3A e quero ir para a 10B", "quero ir pra 10B, estou na 3A".
private val destinationMarkers = listOf(
    "quero ir para", "quero ir pra", "quero ir pro", "quero ir ate", "quero ir na", "quero ir no", "quero ir",
    "preciso ir para", "preciso ir pra", "preciso ir pro", "preciso ir ate", "preciso ir",
    "vou para", "vou pra", "vou pro", "vou ate", "ir para", "ir pra", "ir pro", "ir ate",
    "me leva para", "me leva pra", "me leve para", "me leve pra", "me levar para",
)
private val originMarkers = listOf("estou", "to", "tou", "estamos", "aqui")

// Leftovers at the edges of a piece ("estou na 3A e ..."), which get in the way of reading the room code.
private val linkingWords = setOf("e", "eu", "ai", "entao", "mas", "agora")

private fun trimLinking(text: String): String {
    val words = text.split(' ').filter(String::isNotEmpty).toMutableList()
    while (words.isNotEmpty() && words.last() in linkingWords) words.removeAt(words.lastIndex)
    while (words.isNotEmpty() && words.first() in linkingWords) words.removeAt(0)
    return words.joinToString(" ")
}

private data class Marker(val at: Int, val marker: String)

private fun firstMarker(padded: String, markers: List<String>): Marker? {
    var best: Marker? = null
    for (marker in markers) {
        val at = padded.indexOf(" $marker ")
        if (at < 0) continue
        val current = best
        if (current == null || at < current.at || (at == current.at && marker.length > current.marker.length)) {
            best = Marker(at, marker)
        }
    }
    return best
}

/** Splits the part that says where the person is from the part that says where they want to go. */
fun splitTrip(text: String): Trip {
    val padded = " ${normalize(text)} "
    val destination = firstMarker(padded, destinationMarkers)
        ?: return Trip(trimLinking(padded.trim()).ifEmpty { null }, null)
    val before = trimLinking(padded.substring(0, destination.at))
    val after = " ${padded.substring(destination.at + destination.marker.length + 1).trim()} "
    val origin = firstMarker(after, originMarkers)
    if (origin != null) {
        return Trip(
            trimLinking(after.substring(origin.at)).ifEmpty { null },
            trimLinking(after.substring(0, origin.at)).ifEmpty { null },
        )
    }
    return Trip(before.ifEmpty { null }, trimLinking(after).ifEmpty { null })
}
