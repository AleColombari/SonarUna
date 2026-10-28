# Localização por GPS: GeoJSON e OpenStreetMap

Pesquisa e decisão de 28/10/2026 sobre como guardar pontos de GPS (como a entrada principal da UNAERP de Ribeirão Preto) para o app sugerir onde a pessoa está.

## GeoJSON ou OpenStreetMap?

Os dois não competem; servem para coisas diferentes.

- **GeoJSON** é um **formato de arquivo** (RFC 7946): JSON com pontos, linhas e polígonos em WGS 84, com as coordenadas na ordem **[longitude, latitude]** e um campo `properties` livre para cada feição. É simples de ler no navegador e no Android, sem biblioteca.
- **OpenStreetMap (OSM)** é um **banco de dados colaborativo** do mapa do mundo, com um modelo próprio (nós, vias e relações com etiquetas). Os dados estão sob a licença **ODbL**: quem usa precisa creditar "© colaboradores do OpenStreetMap", e um banco derivado que for distribuído precisa manter a mesma licença.

**Decisão:** o OSM serve de **fonte de referência** e os pontos do app ficam num **arquivo GeoJSON nosso**, [data/unaerp-pontos.geojson](../data/unaerp-pontos.geojson). Cada ponto aponta, pelo campo `ponto`, para um ponto do grafo do piso em [data/unaerp.json](../data/unaerp.json).

Por que não usar só o OSM:

- O OSM tem a UNAERP (contorno do campus, blocos A a S e quatro portões), mas **não tem** as entradas dos prédios, o piso tátil nem os corredores internos. Consulta feita na Overpass API em 28/10/2026.
- O guia do app é o grafo de cruzamentos do piso tátil, que não existe no OSM. O mapeamento interno (*indoor*) do OSM ainda é pouco usado e não tem roteamento pelo piso tátil.
- Um arquivo no repositório funciona sem internet, é versionado com o código e é fácil de revisar.

O que veio do OSM para o GeoJSON, marcado com a fonte e a atribuição:

- o centro do campus (via 445326019), usado para avisar quando a pessoa não está na UNAERP;
- os centros dos blocos A e B;
- os quatro portões, como referência para quem for medir.

**Mais tarde:** dá para devolver ao OSM o que medirmos, como `entrance=main` na entrada principal e `tactile_paving=yes` nos caminhos externos. Isso ajuda outros apps de acessibilidade. É preciso conta no OSM e cuidado com a licença.

## Precisão do GPS

Segundo a documentação do Android:

- a localização **aproximada** tem precisão de cerca de **3 km²**;
- a localização **precisa** costuma ficar em cerca de **50 m**, às vezes poucos metros.

A aproximada não distingue uma entrada da outra. Por isso a confirmação por GPS exige a **precisa**. Os centros dos blocos A e B, por exemplo, estão a uns 45 m um do outro.

Dentro dos prédios o GPS piora muito. Ele serve para sugerir o ponto de partida perto de uma entrada, ao ar livre, e **nunca** para achar a sala.

## Como o app usa

1. Ao tocar no botão, a versão web pede a localização precisa ao navegador e espera até 8 segundos.
2. `suggestOrigin` ([web/localizacao.js](../web/localizacao.js)) decide:
   - **leitura pior que ±60 m** ou sem leitura: não sugere nada e pergunta "Onde você está agora?";
   - **fora do raio do campus**: avisa "você não parece estar no campus da UNAERP" e pergunta;
   - **dentro do raio de um ponto** (raio do ponto + margem de erro da leitura): sugere o mais perto.
3. A sugestão é sempre uma pergunta: "Pela sua localização, parece que você está na entrada principal. Está certo?"
   - **"sim"**: confirma o ponto de partida.
   - **"não"**: o app pergunta onde a pessoa está.
   - **"não, estou no balcão de informações"**: corrige direto.

O GPS nunca decide sozinho.

No celular, o navegador só libera a localização em páginas HTTPS (ou `localhost`). O app Android ainda não usa este arquivo: ele pede só a localização aproximada e não tem o mapa.

## Medir um ponto no campus

1. Abra a versão web com `?teste` no celular, em HTTPS ou por um túnel, e permita a localização **precisa**.
2. Fique no lugar exato, ao ar livre. Para a entrada principal, é o traço grande do começo do esboço.
3. Abra **Medir pontos de GPS** e toque em **Medir minha posição**. O app faz 5 leituras e mostra a melhor, com a precisão.
4. Copie a linha `"geometry": { "type": "Point", "coordinates": [...] }` para a feição certa em [data/unaerp-pontos.geojson](../data/unaerp-pontos.geojson), no lugar de `"geometry": null`.
5. Ajuste o `raio`: 25 m é um bom começo para uma entrada; diminua se dois pontos ficarem perto um do outro.
6. Rode os testes da versão web. O teste que confere que a entrada está pendente vai falhar; atualize-o para a coordenada medida.

Para marcar outro ponto, copie a feição da entrada principal, troque `id`, `ponto` (um id de [data/unaerp.json](../data/unaerp.json)) e `nome`, e meça.

## Fontes

- [RFC 7946, o formato GeoJSON](https://datatracker.ietf.org/doc/html/rfc7946)
- [Direitos autorais e licença do OpenStreetMap (ODbL)](https://www.openstreetmap.org/copyright)
- [Precisão das permissões de localização no Android](https://developer.android.com/training/location/permissions)
- [Localização aproximada no Android 12+](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
- [Etiqueta `entrance` no OSM](https://wiki.openstreetmap.org/wiki/Key:entrance)
- [Etiqueta `tactile_paving` no OSM](https://wiki.openstreetmap.org/wiki/Key:tactile_paving)
- Consultas à Nominatim e à Overpass API (OSM) sobre a UNAERP, em 28/10/2026
