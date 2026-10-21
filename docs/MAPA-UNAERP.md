# Mapa da UNAERP

O arquivo [data/unaerp.json](../data/unaerp.json) foi montado em 21/10/2026 a partir do esboço em folha de caderno, das fotos dos pisos táteis e das respostas do Alexandre sobre o esboço. **Não há medidas**: o app guia pelo piso tátil, contando os cruzamentos que a pessoa sente com a bengala. A versão web usa este mapa por padrão; o mapa fictício antigo, com metros, abre em `/web/?mapa=bloco-a`.

As fotos e o esboço não estão no repositório: mostram pessoas e não precisam ser versionados.

## Legenda do esboço

| No esboço | No mapa |
| --- | --- |
| Bola no piso | Cruzamento (`"tipo": "cruzamento"`) com um ramal até a sala; a sala é um ponto próprio |
| Quadrado | Cruzamento que leva a mais caminhos |
| Três pontos (`...`) | Caminho para mapear no futuro; ainda não entra no mapa |
| Zigue-zague entre dois traços | Escada (`"escada"` no trecho) |
| Seta (triângulo) | "Continua aqui": liga dois pedaços do desenho que são o mesmo lugar |
| Traço cortando o piso | Porta; a do começo do bloco B fica sempre aberta |
| Traço no fim da linha | Fim do piso |
| B F / B M | Banheiro feminino / masculino |
| AV | Sala de audiovisual |
| Dire | Sala do Direito |
| MA | Multiatendimento |
| Prof | Sala dos professores |
| Info | Balcão de informações |
| LIAPE | Assistência de informática, no fim do corredor A |

O desenho foi lido com a folha deitada: o corredor do bloco B fica em cima e o do bloco A embaixo. A linha comprida que parece ligar os dois pela passarela só abria espaço no papel. A seta indica que a escada do fim do bloco B e a do começo do bloco A são **a mesma escada**.

Os `rumo` seguem o desenho (0 = para cima na folha, 90 = direita, 180 = baixo, 270 = esquerda). Não são pontos cardeais: o app só compara um rumo com o seguinte para saber se a curva é à direita ou à esquerda.

## O que o mapa tem

- **Entrada do bloco B** (`entrada-b`), no começo do corredor, seguida de uma porta que fica sempre aberta. O app cita a porta como referência.
- **Corredor do bloco B** (`b02` a `b21`): 20B e 18B à esquerda e 17B à direita de quem vem da entrada; banheiro feminino e audiovisual; banheiro masculino; 16B a 8B; 5B; Direito; multiatendimento.
- **Escada entre os blocos**: começa no cruzamento do financeiro e da cobrança e **desce** do bloco B para o bloco A. Na volta, o app diz "Suba a escada".
- **Chegada ao bloco A**: sala dos professores, balcão de informações e protocolo.
- **Corredor do bloco A** (`a01` a `a15`): sala de estudos (computadores, portas de vidro), 9A, 6A, 7A e sala sem número, 5A, banheiro masculino, 3A, banheiro feminino, 1A, 4A, 2A e LIAPE.
- **Escadas dos quadrados** `b07` (bloco B) e `a07` (bloco A): sobem para caminhos ainda não mapeados. Estão anotadas no campo `nota` desses pontos.

A conversa começa perguntando **onde a pessoa está** (`"origem": "lugar"`), e não por qual entrada ela entrou. Ela sai do lugar pelo único trecho de piso que chega até ele. Pedir só "banheiro" leva ao mais perto; "não, feminino" na confirmação troca para o banheiro feminino mais perto.

## Ainda a confirmar

Também estão no campo `duvidas` do JSON.

1. **Sentido da escada entre os blocos.** O mapa considera que ela desce indo do bloco B para o bloco A. Se for o contrário, troque `"desce"` por `"sobe"` no trecho `fin` → `pe-escada`.
2. **Portas da sala de estudos.** A sala tem várias portas de vidro, ligadas aos três cruzamentos logo depois das Informações. O mapa usa só a primeira. Os outros dois cruzamentos (`a02`, `a03`) continuam contando.

## Limites

- Contar cruzamentos longos ("até o 12º cruzamento") é difícil. Vale testar com quem usa bengala e, se for preciso, acrescentar `referencias` nos trechos (bebedouro, porta de vidro, mudança de piso).
- Os ramais sem nome do esboço, como o que sai do quadrado dos professores para a direita, não entram no mapa. Eles não mudam a contagem, porque ficam fora da linha principal.
- O app nunca afirma que um caminho é seguro. Ele não substitui bengala, cão-guia nem ajuda humana.
