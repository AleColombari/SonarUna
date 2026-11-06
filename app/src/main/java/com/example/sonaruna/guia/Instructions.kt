package com.example.sonaruna.guia

import kotlin.math.abs

/**
 * One spoken step of a route; [cruzamentos] is how many crossings it passes before the stop. [ponto] and [rumo]
 * say where the step ends and which way the person faces there (after the announced turn), to change destination halfway.
 */
data class Instruction(val texto: String, val cruzamentos: Int, val ponto: String, val rumo: Double)

private const val STRAIGHT = "Siga em frente"
private val NOT_A_CROSSING = setOf("curva", "patamar", "porta", "marco")

private fun turnPhrase(angle: Double): String = when {
    abs(angle) < 30 -> STRAIGHT
    abs(angle) > 150 -> "Dê meia-volta"
    angle > 0 -> "Vire à direita"
    else -> "Vire à esquerda"
}

private class Group(val virar: String, val escada: Stair?, val trechos: MutableList<Segment>)

private class Chunk(val trechos: List<Segment>, val passados: Int, val pausa: Boolean)

/**
 * Tactile-paving guidance without measurements or rooms as references, ported from web/engine.js: the person counts
 * the crossings felt with the cane ("Daqui a 3 cruzamentos, vire à esquerda"). A turn is announced by the step that
 * leads to it; a room is named only as the destination. A long straight becomes steps of at most
 * [FloorMap.cruzamentosPorEtapa] crossings. Every point in the middle of a straight counts as a crossing (except
 * curves, landings, doors and markers). [heading] is the direction the person faces at the start; null means unknown.
 */
fun FloorMap.buildInstructions(heading: Double?, segments: List<Segment>): List<Instruction> {
    val groups = mutableListOf<Group>()
    var facing = heading ?: segments.firstOrNull()?.rumo ?: 0.0
    for (segment in segments) {
        val angle = turnAngle(facing, segment.rumo)
        val last = groups.lastOrNull()
        if (last != null && last.escada == null && segment.escada == null && abs(angle) < 30) {
            last.trechos += segment
        } else {
            groups += Group(turnPhrase(angle), segment.escada, mutableListOf(segment))
        }
        facing = segment.rumo
    }
    val steps = mutableListOf<Instruction>()
    groups.forEachIndexed { index, group ->
        val isLast = index == groups.lastIndex
        val previous = groups.getOrNull(index - 1)
        val next = groups.getOrNull(index + 1)
        if (group.escada != null) {
            val turn = if (group.virar == STRAIGHT) "" else "${group.virar}. "
            val text = when (group.escada) {
                Stair.UP -> "${turn}Suba a escada."
                Stair.DOWN -> "${turn}Desça a escada."
                Stair.UNKNOWN -> "${turn}Atenção, escada. Use o corrimão."
            }
            steps += step(group.trechos, text, arrives = isLast, passados = 0)
            return@forEachIndexed
        }
        // The start turn was already said at the end of the previous step, except at the start or after stairs.
        val startTurn = if ((previous == null || previous.escada != null) && group.virar != STRAIGHT) group.virar else null
        // The end turn (at the crossing where the next straight starts) is said in this step.
        val endTurn = if (next != null && next.escada == null) next.virar else null
        val chunks = splitLongStraight(group.trechos, endsWithoutTurn = isLast || next?.escada != null)
        chunks.forEachIndexed { i, chunk ->
            val lastChunk = i == chunks.lastIndex
            val turnNow = if (i == 0) startTurn else null
            val go = turnNow?.let { "$it e siga o piso tátil" } ?: "${if (i > 0) "Continue" else "Siga"} em frente pelo piso tátil"
            val end = points.getValue(chunk.trechos.last().to)
            val passing = if (chunk.passados > 0) ", passando por ${plural(chunk.passados, "cruzamento", "cruzamentos")}," else ""
            val count = chunk.passados + 1
            val text = when {
                chunk.pausa -> "$go por ${plural(count, "cruzamento", "cruzamentos")}."
                isLast -> "$go$passing até ${if (end.tipo == "porta") "a porta" else "o fim"}."
                next?.escada != null -> "$go$passing até a escada."
                end.tipo == "curva" -> "$go$passing até a curva do piso e ${turnVerb(endTurn!!)}."
                else -> {
                    val whenText = if (count == 1) "No próximo cruzamento" else "Daqui a $count cruzamentos"
                    "${if (turnNow != null) "$go. " else ""}$whenText, ${turnVerb(endTurn!!)}."
                }
            }
            val faces = if (lastChunk && endTurn != null) next!!.trechos.first().rumo else null
            steps += step(chunk.trechos, text, arrives = isLast && !chunk.pausa, passados = chunk.passados, faces = faces)
        }
    }
    return steps
}

private fun turnVerb(virar: String): String = virar.replaceFirstChar { it.lowercase() }

private fun FloorMap.isCrossing(id: String): Boolean = points.getValue(id).tipo !in NOT_A_CROSSING

/**
 * Cuts a straight into pieces of at most [FloorMap.cruzamentosPorEtapa] crossings to count. Middle pieces end at a
 * crossing (pausa); the last keeps the straight's real end. When the straight ends at the crossing where the person
 * turns, that crossing counts too, because the person has to count it to stop.
 */
private fun FloorMap.splitLongStraight(trechos: List<Segment>, endsWithoutTurn: Boolean): List<Chunk> {
    val chunks = mutableListOf<Chunk>()
    var current = mutableListOf<Segment>()
    var counted = 0
    trechos.forEachIndexed { i, segment ->
        current += segment
        if (i == trechos.lastIndex || !isCrossing(segment.to)) return@forEachIndexed
        counted++
        val remaining = trechos.subList(i + 1, trechos.lastIndex).count { isCrossing(it.to) } + if (endsWithoutTurn) 0 else 1
        if (counted == cruzamentosPorEtapa && remaining > 0) {
            chunks += Chunk(current, counted - 1, pausa = true)
            current = mutableListOf()
            counted = 0
        }
    }
    chunks += Chunk(current, counted, pausa = false)
    return chunks
}

private fun FloorMap.step(trechos: List<Segment>, text: String, arrives: Boolean, passados: Int, faces: Double? = null): Instruction {
    val parts = mutableListOf(text)
    val referencias = trechos.flatMap { it.referencias }.distinct()
    if (referencias.isNotEmpty()) parts += "Referência: ${referencias.joinToString(", ")}."
    val end = points.getValue(trechos.last().to)
    parts += if (arrives) "Você chegará ${end.emLugar}. Quando chegar, diga pronto." else "Quando terminar, diga pronto."
    return Instruction(parts.joinToString(" "), passados, end.id, faces ?: trechos.last().rumo)
}
