# Mapa da UNAERP

O arquivo [data/unaerp.json](../data/unaerp.json) foi montado em 21/10/2026 a partir do esboço em folha de caderno e das fotos dos pisos táteis. **Não há medidas**: o app guia pelo piso tátil, contando os cruzamentos que a pessoa sente com a bengala. A versão web usa este mapa por padrão; o mapa fictício antigo, com metros, abre em `/web/?mapa=bloco-a`.

As fotos e o esboço não estão no repositório: mostram pessoas e não precisam ser versionados.

## Legenda do esboço

| No esboço | No mapa |
| --- | --- |
| Bola no piso | Cruzamento (`"tipo": "cruzamento"`) com um ramal até a sala; a sala é um ponto próprio |
| Quadrado | Cruzamento que leva a mais caminhos |
| Três pontos (`...`) | Caminho para mapear no futuro; ainda não entra no mapa |
| Zigue-zague entre dois traços | Escada (`"escada"` no trecho) |
| Traço no fim da linha | Fim do piso |
| B F / B M | Banheiro feminino / masculino |
| AV | Sala de audiovisual |
| Dire | Sala do Direito |
| MA | Multiatendimento |
| Prof | Sala dos professores |
| Info | Balcão de informações |

O desenho foi lido com a folha deitada: o corredor do bloco B fica em cima, o do bloco A embaixo, e a passarela os liga pela direita e pela esquerda. Os `rumo` seguem esse desenho (0 = para cima na folha, 90 = direita, 180 = baixo, 270 = esquerda). Não são pontos cardeais: o app só compara um rumo com o seguinte para saber se a curva é à direita ou à esquerda.

## O que o mapa tem

- **Corredor do bloco B** (`b01` a `b21`): 20B, 18B, 17B, banheiro feminino e audiovisual, banheiro masculino, 16B a 8B, 5B, Direito e multiatendimento.
- **Saída do bloco B** (`b21` para baixo): financeiro e cobrança no mesmo cruzamento, depois uma escada.
- **Passarela** (`curva-1` e `curva-2`): ligação entre os blocos, com duas curvas.
- **Chegada ao bloco A**: triângulo, escada, sala dos professores, balcão de informações e protocolo.
- **Corredor do bloco A** (`a01` a `a15`): departamento, 9A, 6A, 7A e sala sem número, 5A, banheiro masculino, 3A, banheiro feminino, 1A, 4A, 2A e uma sala no fim do corredor.

A conversa começa perguntando **onde a pessoa está** (`"origem": "lugar"`), e não por qual entrada ela entrou. Ela sai do lugar pelo único trecho de piso que chega até ele. Pedir só "banheiro" leva ao mais perto; "não, feminino" na confirmação troca para o banheiro feminino mais perto.

## Dúvidas a confirmar no campus

Também estão no campo `duvidas` do JSON.

1. **Nome no fim do corredor A.** O texto escrito acima do último ponto, depois da 2A, não ficou legível. Por enquanto o lugar se chama "sala do fim do corredor A".
2. **Triângulo.** Não sabemos o que ele marca, entre a passarela e a escada que desce para os professores. Ele não muda as instruções: o app diz "até a escada".
3. **Escadas.** Não sabemos se as duas escadas da rota sobem ou descem. Hoje o app só avisa "Atenção, escada. Use o corrimão." Quando souber, troque `"indefinida"` por `"sobe"` ou `"desce"` no sentido do trecho.
4. **Começo do bloco B.** As salas foram lidas como 20B e 18B em cima e 17B embaixo.
5. **Primeira sala de cima do corredor A.** Foi lida como 6A; pela ordem dos números, poderia ser 8A.
6. **Departamento.** Há três cruzamentos logo depois das Informações, com ramais para baixo. O Departamento foi posto no primeiro.
7. **Traço que corta o piso no começo do bloco B**, entre os quadrados e a sala 20B. Pode ser uma porta; hoje não entra no mapa.

## Limites

- Contar cruzamentos longos ("até o 12º cruzamento") é difícil. Vale testar com quem usa bengala e, se for preciso, acrescentar `referencias` nos trechos (bebedouro, porta de vidro, mudança de piso).
- Os ramais sem nome do esboço, como o que sai do quadrado dos professores para a direita, não entram no mapa. Eles não mudam a contagem, porque ficam fora da linha principal.
- O app nunca afirma que um caminho é seguro. Ele não substitui bengala, cão-guia nem ajuda humana.
