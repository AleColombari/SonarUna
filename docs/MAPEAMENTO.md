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
| De / Para | `entrada-principal` → `hall` |
| Passos | 18, **contados caminhando o trecho**. O app fala em passos, então este é o número mais importante |
| Metros | 12, da planta ou de uma trena. Obrigatório no arquivo; serve de reserva quando faltam os passos |
| Rumo | Direção da caminhada pela bússola do celular: 0 = norte, 90 = leste, 180 = sul, 270 = oeste |
| Acessível a quem tem deficiência visual? | sim / não (degrau, rampa íngreme, obstáculo) |
| Referências no caminho | "parede à esquerda", "piso tátil", "porta antes do fim" |
| Perigos | degrau, vidro, buraco, obra, porta que abre para o corredor |

Cada trecho vale nos dois sentidos. O app inverte o rumo (soma 180°) e descobre sozinho se a curva é à direita ou à esquerda na volta, então **não precisa anotar os dois sentidos**.

**Sobre os passos:** o tamanho do passo muda de pessoa para pessoa, então o app diz "cerca de" e arredonda de 5 em 5 acima de 10 passos. Conte os passos em ritmo normal, sem pisar com cuidado demais. Confira o trecho com outra pessoa: se a contagem diferir muito, anote a média. Uma referência que a pessoa sente (parede, piso tátil, porta) vale mais que o número.

## 4. Como o app vai saber onde a pessoa está

O GPS não funciona bem dentro de prédios, então este é o ponto mais importante a decidir com a faculdade. Opções, da mais simples para a mais completa:

1. **Confirmação por voz**: a pessoa diz "cheguei na porta" ou o app pergunta "você chegou?". Não exige equipamento, mas depende da pessoa.
2. **Etiquetas NFC ou QR code em pontos fixos**: a pessoa aproxima o celular. Barato, mas exige instalar e manter as etiquetas.
3. **Beacons Bluetooth**: localização contínua, mais cara e mais trabalhosa de instalar.

Pergunte na faculdade: é permitido colar etiquetas ou instalar beacons? Quem autoriza?

## 5. Formato do arquivo

O app lê [data/bloco-a.json](../data/bloco-a.json). Hoje esse arquivo tem **dados fictícios**: troque pelas medidas reais. Um trecho se escreve assim:

```json
{
  "de": "entrada-principal",
  "para": "hall",
  "metros": 6,
  "passos": 9,
  "rumo": 0,
  "referencias": ["piso tátil no centro"]
}
```

Só `de`, `para`, `metros` e `rumo` são obrigatórios; `passos` e `referencias` são opcionais. Se o arquivo tiver erro, o app diz qual (ponto que não existe, trecho com 0 metros).

## 6. Cuidados

- Peça a alguém com deficiência visual para **testar o trajeto descrito**. A pessoa percebe o que quem enxerga não nota.
- Revise com a coordenação do campus: obras e mudanças de sala invalidam o mapa.
- O app nunca deve afirmar que um caminho é seguro. Ele orienta, e não substitui bengala, cão-guia nem ajuda humana.
