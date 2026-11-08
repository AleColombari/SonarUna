package com.example.sonaruna.guia

enum class YesNo { YES, NO }

enum class Command { ASSIST, STOP, RESTART, DESTINATION, REMAINING, OVERVIEW, WHERE, HELP, REPEAT, BACK, NEXT }

// Words and expressions anywhere in the phrase: "acho que sim", "sim sim", "isso aí, pode ir". The same lists are in
// web/engine.js; both are generated together and the shared cases check them.
private val positive = listOf(
    "sim", "isso", "correto", "correta", "certo", "certa", "confirmo", "confirmado", "confirma", "exato",
    "exatamente", "claro", "ok", "okay", "beleza", "positivo", "afirmativo", "perfeito", "uhum", "aham", "certeza",
    "pode", "quero", "bora", "vamos", "vamo", "ta bom", "esta bom", "tudo bem", "e esse", "e essa", "e ela", "e ele",
    "esse mesmo", "essa mesma", "yes", "isso mesmo", "isso ai", "e isso", "com certeza", "sem duvida", "certissimo",
    "certinho", "confere", "concordo", "de acordo", "fechado", "fechou", "combinado", "show", "otimo", "otima",
    "blz", "firmeza", "demorou", "tranquilo", "suave", "ta certo", "esta certo", "ta ok", "acertou", "sim senhor",
    "sim senhora", "aham sim", "hum hum", "unhum", "pode crer", "manda ver", "manda bala", "positivo e operante",
    "yep", "yeah",
)
private val negative = listOf(
    "nao", "errado", "errada", "incorreto", "incorreta", "negativo", "nunca", "corrigir", "corrige", "trocar",
    "troca", "mudar", "muda", "nem", "enganei", "jamais", "de jeito nenhum", "nada disso", "que nada", "negado",
    "errei", "errou", "nem pensar", "longe disso", "outro lugar", "outro local", "outra sala", "nope", "na na",
    "nananinanao",
)

// Stated doubt is not an answer, even with "não" in it. ("sem dúvida" is yes, so there is no "duvida" alone.)
private val unsure = listOf("nao sei", "sei la", "talvez", "tenho duvida", "em duvida", "na duvida", "nao tenho certeza", "acho que nao sei")

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

private val commands = listOf(
    private val commands = listOf(
    // "preciso de ajuda" asks for someone; "ajuda" alone only explains the app, so ASSIST comes before HELP.
    Command.ASSIST to listOf(
        "preciso de ajuda", "preciso de socorro", "preciso de um atendente", "preciso de atendente", "quero ajuda",
        "chamar ajuda", "pedir ajuda", "pedir socorro", "me ajuda", "me ajude", "socorro", "estou perdido",
        "estou perdida", "chamar atendente", "chamar seguranca", "falar com atendente", "falar com um atendente",
        "falar com o atendente", "falar com seguranca", "falar com a seguranca", "falar com um seguranca",
    ),
    // "sair" does not stop the guidance: "quero sair" is a destination. Nor does "para" alone: it is a preposition.
    Command.STOP to listOf(
        "parar", "pare", "cancelar", "cancela", "encerrar", "encerra", "finalizar", "finaliza", "desligar",
        "desliga", "tchau", "fechar o app", "fechar o aplicativo", "pode parar", "para tudo",
    ),
    Command.RESTART to listOf(
        "recomecar", "reiniciar", "comecar de novo", "do comeco", "desde o comeco",
    ),
    // Changing only the destination keeps where the person is (or where the last finished step ended).
    Command.DESTINATION to listOf(
        "novo destino", "outro destino", "nova rota", "outra rota", "mudar o destino", "mudar destino",
        "trocar o destino", "trocar destino", "mudar de destino", "quero ir para outro lugar", "ir para outro lugar",
    ),
    Command.REMAINING to listOf(
        "quanto falta", "falta muito", "quantas etapas faltam", "quantas faltam", "falta quanto", "falta pouco",
        "ta longe", "esta longe", "ainda falta", "quantas etapas",
    ),
    // Before REPEAT, because "repete o caminho" contains "repete".
    Command.OVERVIEW to listOf(
        "repasse o caminho", "repassa o caminho", "repassar o caminho", "repete o caminho", "repita o caminho",
        "caminho todo", "caminho completo", "resumo do caminho", "resumo da rota", "me fala o caminho",
        "qual e o caminho", "como e o caminho",
    ),
    Command.WHERE to listOf(
        "onde estou", "onde eu estou", "aonde estou", "aonde eu estou", "onde eu to", "onde to",
        "onde e que eu estou", "em que lugar eu estou", "em que lugar estou", "onde estamos",
    ),
    Command.HELP to listOf(
        "ajuda", "o que eu posso falar", "o que eu posso dizer", "como funciona", "quais comandos",
    ),
    Command.REPEAT to listOf(
        "repetir", "repete", "repita", "de novo", "nao ouvi", "nao entendi", "nao escutei", "nao captei",
        "outra vez", "mais uma vez", "hein", "o que voce disse", "o que voce falou", "fala de novo", "como e que e",
    ),
    Command.BACK to listOf(
        "voltar", "volta", "volte", "anterior", "passo anterior", "etapa anterior", "a de antes",
    ),
    // On the way, any "yes" ("beleza", "certo", "isso") also goes to the next step (Conversation.kt).
    Command.NEXT to listOf(
        "pronto", "pronta", "prontinho", "proximo", "proxima", "proxima etapa", "proximo passo", "continuar",
        "continua", "continue", "seguir", "segue", "avancar", "avanca", "cheguei", "ja cheguei", "ok", "feito",
        "fiz", "terminei", "acabei", "consegui", "ja fui", "ja andei", "ja fiz", "ja passei", "e agora", "e depois",
        "e dai", "qual a proxima", "qual e a proxima", "manda a proxima", "pode mandar", "pode continuar",
        "pode seguir", "next", "foi", "fui", "ja", "andei", "passei", "ta feito", "esta feito", "cheguei la",
        "to la", "estou la", "ja era",
    ),
)

fun classifyCommand(text: String): Command? {
    val normalized = normalize(text)
    return commands.firstOrNull { (_, phrases) -> containsAny(normalized, phrases) }?.first
}
