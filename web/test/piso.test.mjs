import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildInstructions, loadMap, planRoute } from '../engine.js';
import { createSession } from '../conversation.js';

// Corredor reto entrada -> c1 -> c2 -> c3 (para o norte), ramal de c2 para a sala A1 (leste) e
// escada de c3 subindo ate o topo, de onde um ramal leva a sala A2 (oeste). Sem metros.
const raw = {
  guia: 'piso',
  entradas: [{ id: 'entrada', nome: 'entrada principal', aliases: ['principal'], ponto: 'entrada', rumo: 0 }],
  pontos: [
    { id: 'entrada', nome: 'entrada principal', tipo: 'entrada' },
    { id: 'c1', nome: 'cruzamento 1', tipo: 'cruzamento' },
    { id: 'c2', nome: 'cruzamento 2', tipo: 'cruzamento' },
    { id: 'c3', nome: 'cruzamento 3', tipo: 'cruzamento' },
    { id: 'topo', nome: 'topo da escada', tipo: 'cruzamento' },
    { id: 'A1', nome: 'sala A1', tipo: 'sala', codigo: 'A1' },
    { id: 'A2', nome: 'sala A2', tipo: 'sala', codigo: 'A2' },
  ],
  trechos: [
    { de: 'entrada', para: 'c1', rumo: 0 },
    { de: 'c1', para: 'c2', rumo: 0 },
    { de: 'c2', para: 'c3', rumo: 0 },
    { de: 'c2', para: 'A1', rumo: 90 },
    { de: 'c3', para: 'topo', rumo: 0, escada: 'sobe' },
    { de: 'topo', para: 'A2', rumo: 270 },
  ],
};
const map = loadMap(raw);
const entrance = map.entrances[0];
const texts = (to) => buildInstructions(map, entrance, planRoute(map, 'entrada', to)).map((step) => step.texto);

test('modo piso carrega trechos sem metros; o modo passos continua exigindo metros', () => {
  assert.equal(map.guia, 'piso');
  assert.throws(() => loadMap({ ...raw, guia: undefined }), /metros/);
  assert.throws(() => loadMap({ ...raw, trechos: [{ de: 'entrada', para: 'c1' }] }), /rumo/);
  assert.throws(() => loadMap({ ...raw, trechos: [{ de: 'c3', para: 'topo', rumo: 0, escada: 'cima' }] }), /sobe/);
});

test('guia pelo piso conta os cruzamentos ate a curva e termina no fim do ramal', () => {
  assert.deepEqual(texts('A1'), [
    'Siga em frente pelo piso tátil até o segundo cruzamento. Quando terminar, diga pronto.',
    'Vire à direita e siga o piso tátil até o fim. Você chegará na sala A1. Quando chegar, diga pronto.',
  ]);
});

test('escada vira uma etapa propria, e o sentido inverte na volta', () => {
  assert.deepEqual(texts('A2'), [
    'Siga em frente pelo piso tátil, passando por 2 cruzamentos, até a escada. Quando terminar, diga pronto.',
    'Suba a escada. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil até o fim. Você chegará na sala A2. Quando chegar, diga pronto.',
  ]);
  const back = planRoute(map, 'A2', 'entrada');
  assert.equal(back.find((segment) => segment.escada).escada, 'desce');
});

test('peso de um trecho desvia a rota para outro caminho', () => {
  const ring = loadMap({
    guia: 'piso',
    pontos: ['a', 'b', 'c', 'd'].map((id) => ({ id, nome: id })),
    trechos: [
      { de: 'a', para: 'b', rumo: 0, peso: 5 },
      { de: 'a', para: 'c', rumo: 90 },
      { de: 'c', para: 'd', rumo: 0 },
      { de: 'd', para: 'b', rumo: 270 },
    ],
  });
  assert.deepEqual(planRoute(ring, 'a', 'b').map((segment) => segment.para), ['c', 'd', 'b']);
});

test('conversa no modo piso fala etapas, sem passos', () => {
  const session = createSession(map);
  session.start();
  session.hear(['principal']);
  session.hear(['sala A1']);
  const route = session.hear(['sim']);
  assert.equal(route.phase, 'navigating');
  assert.match(route.say[0], /^Rota até a sala A1, em 2 etapas, seguindo o piso tátil\.$/);
  assert.match(route.say[1], /^Etapa 1 de 2\. Siga em frente pelo piso tátil até o segundo cruzamento/);
  assert.doesNotMatch(route.say.join(' '), /passo/);
});

// Reta para o norte com `n` cruzamentos e, no fim, a sala Z (em frente) ou uma curva para a direita ate Z.
function corridor(n, { turn = false, limit } = {}) {
  const ids = Array.from({ length: n }, (_, i) => `c${i + 1}`);
  const pontos = [{ id: 'inicio', nome: 'início', tipo: 'sala' }, ...ids.map((id) => ({ id, nome: id, tipo: 'cruzamento' })),
    { id: 'Z', nome: 'sala Z', tipo: 'sala' }];
  const chain = ['inicio', ...ids];
  const trechos = chain.slice(1).map((id, i) => ({ de: chain[i], para: id, rumo: 0 }));
  trechos.push({ de: ids[n - 1], para: 'Z', rumo: turn ? 90 : 0 });
  const m = loadMap({ guia: 'piso', cruzamentosPorEtapa: limit, pontos, trechos });
  return buildInstructions(m, { ponto: 'inicio', rumo: 0 }, planRoute(m, 'inicio', 'Z')).map((step) => step.texto.split('. ')[0]);
}

test('reta longa ate o fim vira etapas de ate 5 cruzamentos', () => {
  assert.deepEqual(corridor(11), [
    'Siga em frente pelo piso tátil até o quinto cruzamento',
    'Continue em frente pelo piso tátil até o quinto cruzamento',
    'Continue em frente pelo piso tátil, passando por 1 cruzamento, até o fim',
  ]);
  assert.deepEqual(corridor(5), ['Siga em frente pelo piso tátil, passando por 5 cruzamentos, até o fim']);
});

test('o cruzamento onde se vira tambem conta para o limite', () => {
  assert.deepEqual(corridor(5, { turn: true }), [
    'Siga em frente pelo piso tátil até o quinto cruzamento',
    'Vire à direita e siga o piso tátil até o fim',
  ]);
  assert.deepEqual(corridor(6, { turn: true }), [
    'Siga em frente pelo piso tátil até o quinto cruzamento',
    'Continue em frente pelo piso tátil até o primeiro cruzamento',
    'Vire à direita e siga o piso tátil até o fim',
  ]);
});

test('cruzamentosPorEtapa no mapa muda o limite', () => {
  assert.deepEqual(corridor(7, { limit: 3 }), [
    'Siga em frente pelo piso tátil até o terceiro cruzamento',
    'Continue em frente pelo piso tátil até o terceiro cruzamento',
    'Continue em frente pelo piso tátil, passando por 1 cruzamento, até o fim',
  ]);
});
