package com.example.sonaruna.guia

import com.example.sonaruna.guia.TestData.gps
import com.example.sonaruna.guia.TestData.map
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared reference conversations in casos/conversas.json: the web version generates the expected answers
 * (node casos/atualizar.mjs) and the app must say exactly the same, phase by phase.
 */
class CasesTest {
    private val phases = mapOf(
        "idle" to Phase.IDLE,
        "entrance" to Phase.ORIGIN,
        "confirm-origin" to Phase.CONFIRM_ORIGIN,
        "destination" to Phase.DESTINATION,
        "confirm" to Phase.CONFIRM,
        "navigating" to Phase.NAVIGATING,
    )

    private fun guess(position: JSONObject?) = position?.let {
        gps.suggestOrigin(Position(it.getDouble("lat"), it.getDouble("lon"), it.getDouble("precisao")))
    }

    @Test
    fun `the app answers every shared case exactly like the web version`() {
        val cases = JSONObject(TestData.repoFile("casos/conversas.json")).getJSONArray("casos").objects()
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val name = case.getString("nome")
            val session = Conversation(map)
            val start = if (case.optBoolean("procurando")) session.startLocating() else session.start(guess(case.optJSONObject("gps")))
            val replies = mutableListOf<Reply?>(start)
            val steps = case.getJSONArray("passos")
            for (i in 0 until steps.length()) {
                replies += when (val step = steps.get(i)) {
                    "silencio" -> session.silence()
                    "retomar" -> session.resume()
                    is JSONArray -> session.hear(step.strings())
                    // The GPS answer arriving; null when the person already answered first.
                    is JSONObject -> session.locationFound(guess(step.optJSONObject("gps")))
                    else -> error("$name: passo desconhecido $step")
                }
            }
            val expected = case.getJSONArray("esperado").let { list -> (0 until list.length()).map { list.optJSONObject(it) } }
            assertEquals("$name: número de respostas", expected.size, replies.size)
            expected.zip(replies).forEachIndexed { i, (want, got) ->
                val where = "$name, resposta $i"
                if (want == null) {
                    assertEquals(where, null, got)
                    return@forEachIndexed
                }
                got!!
                assertEquals(where, phases.getValue(want.getString("fase")), got.phase)
                assertEquals(where, want.getBoolean("ouvir"), got.listen)
                assertEquals(where, want.getJSONArray("dizer").strings(), got.say)
            }
        }
    }
}
