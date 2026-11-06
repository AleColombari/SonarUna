package com.example.sonaruna.guia

enum class YesNo { YES, NO }

enum class Command { ASSIST, STOP, RESTART, DESTINATION, REMAINING, OVERVIEW, WHERE, HELP, REPEAT, BACK, NEXT }

// Words and expressions anywhere in the phrase: "acho que sim", "sim sim", "isso aí, pode ir".
private val positive = listOf(
    "sim", "isso", "correto", "correta", "certo", "certa", "confirmo", "confirmado", "confirma", "exato", "exatamente",
    "claro", "ok", "okay", "beleza", "positivo", "afirmativo", "perfeito", "uhum", "aham", "certeza", "pode",
    "quero", "bora", "vamos", "ta bom", "esta bom", "tudo bem", "e esse", "e essa", "e ela", "e ele", "esse mesmo",
    "essa mesma", "yes",
)
private val negative = listOf(
    "nao", "errado", "errada", "incorreto", "incorreta", "negativo", "nunca", "corrigir", "corrige", "trocar",
    "troca", "mudar", "muda", "nem", "enganei",
)

// Stated doubt is not an answer, even with "não" in it.
private val unsure = listOf("nao sei", "sei la", "talvez", "duvida", "nao tenho certeza")

/**
 * Reads yes or no anywhere in the phrase, as web/engine.js does. Negation wins ("não está correto"), "sim" with
 * "não" is a contradiction, and "não está errado" (double negative) stays unanswered.
 */
fun classifyYesNo(text: String): YesNo? {
    val normalized = normalize(text)
    if (normalized.isEmpty() || containsAny(normalized, unsure)) return null
    val saysYes = containsPhrase(normalized, "sim")
    val saysNo = containsPhrase(normalized, "nao")
    if (saysYes && saysNo) return null
    if (saysNo && containsAny(normalized, listOf("errado", "errada", "incorreto", "incorreta"))) return null
    if (containsAny(normalized, negative)) return YesNo.NO
    if (containsAny(normalized, positive)) return YesNo.YES
    return null
}

// "preciso de ajuda" asks for someone; "ajuda" alone only explains the app, so ASSIST comes before HELP.
private val commands = listOf(
    Command.ASSIST to listOf(
        "preciso de ajuda", "preciso de socorro", "preciso de um atendente", "preciso de atendente", "quero ajuda",
        "chamar ajuda", "pedir ajuda", "pedir socorro", "me ajuda", "me ajude", "socorro", "estou perdido",
        "estou perdida", "chamar atendente", "chamar seguranca", "falar com atendente", "falar com um atendente",
        "falar com o atendente", "falar com seguranca", "falar com a seguranca", "falar com um seguranca",
    ),
    // "sair" does not stop the guidance: "quero sair" is a destination (the nearest exit, in the map).
    Command.STOP to listOf("parar", "pare", "cancelar", "cancela", "encerrar", "finalizar"),
    Command.RESTART to listOf("recomecar", "reiniciar", "comecar de novo"),
    // Changing only the destination keeps where the person is (or where the last finished step ended).
    Command.DESTINATION to listOf(
        "novo destino", "outro destino", "nova rota", "outra rota", "mudar o destino", "mudar destino", "trocar o destino", "trocar destino",
    ),
    Command.REMAINING to listOf("quanto falta", "falta muito", "quantas etapas faltam", "quantas faltam"),
    // Before REPEAT, because "repete o caminho" contains "repete".
    Command.OVERVIEW to listOf(
        "repasse o caminho", "repassa o caminho", "repassar o caminho", "repete o caminho", "repita o caminho", "caminho todo",
        "caminho completo", "resumo do caminho", "resumo da rota",
    ),
    Command.WHERE to listOf("onde estou", "onde eu estou", "aonde estou", "aonde eu estou"),
    Command.HELP to listOf("ajuda"),
    Command.REPEAT to listOf("repetir", "repete", "repita", "de novo", "nao ouvi", "nao entendi"),
    Command.BACK to listOf("voltar", "volta", "anterior", "passo anterior"),
    Command.NEXT to listOf("pronto", "proximo", "continuar", "continua", "seguir", "cheguei", "ok", "feito", "ja fui", "ja andei"),
)

fun classifyCommand(text: String): Command? {
    val normalized = normalize(text)
    return commands.firstOrNull { (_, phrases) -> containsAny(normalized, phrases) }?.first
}
