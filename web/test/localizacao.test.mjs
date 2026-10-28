import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadMap } from '../engine.js';
import { createSession } from '../conversation.js';
import { PRECISAO_MAXIMA, distanciaMetros, loadGpsPoints, suggestOrigin } from '../localizacao.js';

const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const map = loadMap(read('../../data/unaerp.json'));
const geojson = read('../../data/unaerp-pontos.geojson');

// Entrada principal "medida" num ponto de teste perto do bloco B, como ficara depois da medicao no campus.
const ENTRADA = { lat: -21.20245, lon: -47.78035 };
const measured = structuredClone(geojson);
measured.features.find((f) => f.id === 'entrada-principal').geometry = { type: 'Point', coordinates: [ENTRADA.lon, ENTRADA.lat] };
const gps = loadGpsPoints(measured, map);

/** Desloca uma posicao para o norte, em metros. */
const north = (p, metros) => ({ ...p, lat: p.lat + metros / 111320 });

test('o GeoJSON do repositorio e valido e a entrada principal esta pendente de medicao', () => {
  const real = loadGpsPoints(geojson, map);
  assert.ok(real.campus);
  assert.deepEqual(real.pontos, []);
  assert.deepEqual(real.pendentes, ['entrada-principal']);
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
  assert.equal(suggestOrigin(gps, { ...north(ENTRADA, 120), precisao: 10 }).kind, 'nenhuma');
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
  assert.match(hello.say.at(-1), /^Pela sua localização, parece que você está na entrada principal\. Está certo\? Diga sim ou não\.$/);
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
  assert.match(outside.say.join(' '), /você não parece estar no campus da UNAERP\. Onde você está agora\?/);
  assert.equal(outside.phase, 'entrance');
  assert.match(createSession(map).start().say.at(-1), /^Onde você está agora\?/);
});
