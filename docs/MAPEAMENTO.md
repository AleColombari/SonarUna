# Roteiro de mapeamento do campus

O aplicativo ainda não calcula rotas porque falta o mapa. Este roteiro lista o que coletar no campus, para que o mapa seja montado uma vez, no formato certo.

Para cada trecho que a pessoa percorre, o aplicativo precisa saber a **distância**, a **direção da curva** e **o que ela vai sentir ou ouvir no caminho**. Planta de arquitetura ajuda, mas não substitui caminhar o trajeto.

## 1. Escopo (decidir antes de sair medindo)

- Qual bloco será o piloto? Sugestão: um único bloco, um andar, de dois a cinco destinos (por exemplo: entrada, A15, banheiro, escada, saída).
- Quais salas e locais a pessoa pode pedir? Liste o nome oficial **e como as pessoas falam** (exemplo: "A15", "sala quinze do bloco A", "cantina").

## 2. Pontos (nós do mapa)

Anote um ponto para cada lugar onde algo muda ou onde a pessoa pode ser localizada:

| Campo | Exemplo |
| --- | --- |
| Identificador | `A-entrada` |
| Nome falado | "entrada do bloco A" |
| Tipo | sala, porta, corredor, escada, elevador, banheiro, cruzamento |
| Andar | 1 |
| Como reconhecer ali | "piso tátil", "porta de vidro", "bebedouro à direita" |

Pontos de cruzamento e de mudança de direção também entram, mesmo sem nome de sala.

## 3. Trechos (ligações entre pontos)

Para cada par de pontos vizinhos, caminhando de um ao outro:

| Campo | Exemplo |
| --- | --- |
| De / Para | `A-entrada` → `A-corredor-1` |
| Distância | 18 passos (ou 12 metros). Escolha uma unidade só e use sempre a mesma |
| Direção ao chegar | segue em frente / vira à direita / vira à esquerda |
| Acessível a quem tem deficiência visual? | sim / não (degrau, rampa íngreme, obstáculo) |
| Referências no caminho | "parede à esquerda", "piso tátil", "porta antes do fim" |
| Perigos | degrau, vidro, buraco, obra, porta que abre para o corredor |

Cada trecho vale nos dois sentidos, mas **a direção da curva se inverte** na volta. Anote os dois ou avise que é simétrico.

## 4. Como o app vai saber onde a pessoa está

O GPS não funciona bem dentro de prédios, então este é o ponto mais importante a decidir com a faculdade. Opções, da mais simples para a mais completa:

1. **Confirmação por voz**: a pessoa diz "cheguei na porta" ou o app pergunta "você chegou?". Não exige equipamento, mas depende da pessoa.
2. **Etiquetas NFC ou QR code em pontos fixos**: a pessoa aproxima o celular. Barato, mas exige instalar e manter as etiquetas.
3. **Beacons Bluetooth**: localização contínua, mais cara e mais trabalhosa de instalar.

Pergunte na faculdade: é permitido colar etiquetas ou instalar beacons? Quem autoriza?

## 5. Exemplo do formato final

Este exemplo é uma proposta e pode mudar. Ele é só para mostrar o nível de detalhe:

```json
{
  "pontos": [
    { "id": "A-entrada", "nome": "entrada do bloco A", "tipo": "porta", "andar": 1 },
    { "id": "A15", "nome": "sala A15", "tipo": "sala", "andar": 1 }
  ],
  "trechos": [
    {
      "de": "A-entrada",
      "para": "A15",
      "distancia": 18,
      "unidade": "passos",
      "curva": "direita",
      "acessivel": true,
      "referencias": ["piso tátil", "parede à esquerda"],
      "perigos": ["porta abre para o corredor após 10 passos"]
    }
  ]
}
```

## 6. Cuidados

- Peça a alguém com deficiência visual para **testar o trajeto descrito**. A pessoa percebe o que quem enxerga não nota.
- Revise com a coordenação do campus: obras e mudanças de sala invalidam o mapa.
- O app nunca deve afirmar que um caminho é seguro. Ele orienta, e não substitui bengala, cão-guia nem ajuda humana.
