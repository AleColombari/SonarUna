import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadMap } from '../engine.js';
import { createSession } from '../conversation.js';
import { PRECISAO_MAXIMA, distanciaMetros, loadGpsPoints, suggestOrigin } from '../localizacao.js';

const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const map = loadMap(read('../../data/unaerp.json'));
const geojson = read('../../data/unaerp-pontos.geojson');

const gps = loadGpsPoints(geojson, map);
// Coordenadas marcadas no Google My Maps ("Mapa-guia SonarUna").
const at = (id) => {
  const [lon, lat] = geojson.features.find((f) => f.id === id).geometry.coordinates;
  return { lat, lon };
};
const ENTRADA = at('entrada-principal');

/** Desloca uma posicao para o norte, em metros. */
const north = (p, metros) => ({ ...p, lat: p.lat + metros / 111320 });

test('o GeoJSON do repositorio: tres lugares de partida, todos dentro do campus', () => {
  assert.deepEqual(gps.pontos.map((p) => p.id).sort(), ['entrada-b', 'entrada-principal', 'saida-a']);
  assert.deepEqual(gps.pendentes, []);
  for (const ponto of gps.pontos) assert.ok(distanciaMetros(gps.campus, ponto) < gps.campus.raio, ponto.id);
  // Cruzamentos e escadas do My Maps ficam ligados ao mapa do piso, mas nao viram sugestao.
  const linked = geojson.features.filter((f) => f.properties.nomeMyMaps && f.properties.ponto).map((f) => f.properties.ponto);
  assert.ok(linked.includes('info-x') && linked.includes('q-entrada'));
});

test('cada entrada marcada no My Maps sugere ela mesma', () => {
  for (const id of ['entrada-principal', 'entrada-b', 'saida-a']) {
    assert.equal(suggestOrigin(gps, { ...at(id), precisao: 5 }).place?.id, id, id);
  }
  // Na bifurcacao (entre a entrada principal e a do bloco B) vence a mais perto.
  assert.equal(suggestOrigin(gps, { ...at('mymaps-bifurcacao-bloco-b-h'), precisao: 5 }).place.id, 'entrada-principal');
});

test('distancia de haversine em metros', () => {
  assert.ok(Math.abs(distanciaMetros(ENTRADA, north(ENTRADA, 100)) - 100) < 0.5);
});

test('sugere a entrada perto dela, com leitura precisa', () => {
  const result = suggestOrigin(gps, { ...north(ENTRADA, 10), precisao: 8 });
  assert.equal(result.kind, 'sugestao');
  assert.equal(result.place.id, 'entrada-principal');
});

test('leitura imprecisa, longe de tudo ou fora do campus nao sugere lugar', () => {
  assert.equal(suggestOrigin(gps, { ...ENTRADA, precisao: PRECISAO_MAXIMA + 1 }).kind, 'imprecisa');
  assert.equal(suggestOrigin(gps, null).kind, 'imprecisa');
  assert.equal(suggestOrigin(gps, { ...north(ENTRADA, -120), precisao: 10 }).kind, 'nenhuma');
  assert.equal(suggestOrigin(gps, { lat: -21.1700, lon: -47.8100, precisao: 10 }).kind, 'fora-do-campus');
});

test('GeoJSON invalido falha com mensagem clara', () => {
  assert.throws(() => loadGpsPoints({ type: 'Feature' }, map), /FeatureCollection/);
  const bad = { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [0, 0] }, properties: { ponto: 'nao-existe', raio: 10 } }] };
  assert.throws(() => loadGpsPoints(bad, map), /não existe no mapa/);
});

test('conversa: o GPS sugere, a pessoa confirma com sim', () => {
  const session = createSession(map);
  const hello = session.start(suggestOrigin(gps, { ...ENTRADA, precisao: 5 }));
  assert.equal(hello.phase, 'confirm-origin');
  assert.deepEqual(hello.say, ['Parece que você está na entrada principal. Está certo?']);
  const ok = session.hear(['sim, estou']);
  assert.equal(ok.phase, 'destination');
  assert.match(ok.say[0], /^Você está na entrada principal\./);
});

test('conversa: nao pergunta onde esta; ja dizer outro lugar corrige direto', () => {
  const first = createSession(map);
  first.start(suggestOrigin(gps, { ...ENTRADA, precisao: 5 }));
  const no = first.hear(['não']);
  assert.equal(no.phase, 'entrance');
  assert.match(no.say[0], /^Tudo bem\. Onde você está agora\?/);
  assert.equal(first.hear(['sala 20B']).phase, 'destination');

  const second = createSession(map);
  second.start(suggestOrigin(gps, { ...ENTRADA, precisao: 5 }));
  const fixed = second.hear(['não, estou no balcão de informações']);
  assert.equal(fixed.phase, 'destination');
  assert.match(fixed.say[0], /^Você está no balcão de informações\./);
});

test('conversa: fora do campus avisa e pergunta; sem GPS pergunta direto', () => {
  const outside = createSession(map).start(suggestOrigin(gps, { lat: -21.17, lon: -47.81, precisao: 10 }));
  assert.deepEqual(outside.say, ['Você não parece estar no campus da UNAERP.', 'Onde você está agora?']);
  assert.equal(outside.phase, 'entrance');
  assert.deepEqual(createSession(map).start().say, ['Onde você está agora?']);
});

test('conversa: enquanto o GPS procura, o que a pessoa diz vale e o GPS que chega depois e ignorado', () => {
  const said = createSession(map);
  assert.deepEqual(said.startLocating().say, ['Procurando sua localização.']);
  assert.equal(said.hear(['estou na sala 5A']).phase, 'destination');
  assert.equal(said.locationFound(suggestOrigin(gps, { ...ENTRADA, precisao: 5 })), null);

  const waited = createSession(map);
  waited.startLocating();
  const found = waited.locationFound(suggestOrigin(gps, { ...ENTRADA, precisao: 5 }));
  assert.equal(found.phase, 'confirm-origin');
  assert.equal(waited.locationFound(null), null);

  const nothing = createSession(map);
  nothing.startLocating();
  assert.deepEqual(nothing.locationFound(null).say, ['Onde você está agora?']);
});

test('importar-mymaps: le o KML, cria feicoes novas e preserva a ligacao numa reimportacao', async () => {
  const { mkdtempSync, writeFileSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { execFileSync } = await import('node:child_process');
  const dir = mkdtempSync(join(tmpdir(), 'sonaruna-'));
  const kml = (lon) => `<?xml version="1.0"?><kml><Document><Placemark><name>Portão &amp; rampa</name>
    <description><![CDATA[rampa à <b>direita</b>]]></description><Point><coordinates>${lon},-21.2,0</coordinates></Point></Placemark></Document></kml>`;
  const kmlPath = join(dir, 'mapa.kml');
  const out = join(dir, 'pontos.geojson');
  writeFileSync(out, JSON.stringify({ type: 'FeatureCollection', features: [] }));
  const script = new URL('../../docs/importar-mymaps.mjs', import.meta.url);
  writeFileSync(kmlPath, kml(-47.1));
  execFileSync(process.execPath, [script.pathname.replace(/^\/([A-Za-z]:)/, '$1'), kmlPath, out]);
  const first = JSON.parse(readFileSync(out, 'utf8')).features[0];
  assert.equal(first.id, 'mymaps-portao-rampa');
  assert.equal(first.properties.descricao, 'rampa à direita');
  assert.deepEqual(first.geometry.coordinates, [-47.1, -21.2]);

  const linked = JSON.parse(readFileSync(out, 'utf8'));
  Object.assign(linked.features[0].properties, { ponto: 'entrada-principal', sugerir: true, raio: 15 });
  writeFileSync(out, JSON.stringify(linked));
  writeFileSync(kmlPath, kml(-47.2));
  execFileSync(process.execPath, [script.pathname.replace(/^\/([A-Za-z]:)/, '$1'), kmlPath, out]);
  const again = JSON.parse(readFileSync(out, 'utf8')).features;
  assert.equal(again.length, 1);
  assert.deepEqual(again[0].geometry.coordinates, [-47.2, -21.2]);
  assert.equal(again[0].properties.ponto, 'entrada-principal');
  assert.equal(again[0].properties.raio, 15);
});
