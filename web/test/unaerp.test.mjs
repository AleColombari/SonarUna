import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildInstructions, exitHeading, findPlace, loadMap, planRoute } from '../engine.js';
import { createSession } from '../conversation.js';

const map = loadMap(JSON.parse(readFileSync(new URL('../../data/unaerp.json', import.meta.url), 'utf8')));
const places = [...map.points.values()].filter((point) => !['cruzamento', 'curva', 'patamar'].includes(point.tipo));

const route = (fromId, toId) => {
  const origin = { ponto: fromId, rumo: exitHeading(map, fromId) };
  return buildInstructions(map, origin, planRoute(map, fromId, toId)).map((step) => step.texto);
};

test('mapa da UNAERP carrega no modo piso, com partida por lugar', () => {
  assert.equal(map.guia, 'piso');
  assert.equal(map.origem, 'lugar');
  assert.ok(places.length >= 35);
});

test('todo lugar tem uma unica saida e alcanca todos os outros', () => {
  for (const place of places) {
    assert.notEqual(exitHeading(map, place.id), null, `${place.id} precisa de um unico trecho`);
  }
  const [first] = places;
  for (const place of places.slice(1)) {
    assert.ok(planRoute(map, first.id, place.id), `sem caminho de ${first.id} ate ${place.id}`);
  }
});

test('salas e servicos sao achados pelo que a pessoa fala', () => {
  const id = (text) => findPlace(map, text).place?.id;
  assert.equal(id('sala quinze b'), '15B');
  assert.equal(id('vinte b'), '20B');
  assert.equal(id('5A'), '5A');
  assert.equal(id('quero ir no financeiro'), 'financeiro');
  assert.equal(id('cobrança'), 'cobranca');
  assert.equal(id('sala dos professores'), 'professores');
  assert.equal(id('balcão de informações'), 'informacoes');
  assert.equal(id('sala de audiovisual'), 'audiovisual');
  assert.equal(id('direito'), 'direito');
  assert.equal(id('banheiro feminino do bloco a'), 'banheiro-f-a');
  assert.equal(id('estou na entrada'), 'entrada-b');
  assert.equal(id('liape'), 'liape');
  assert.equal(id('sala dos computadores'), 'sala-estudos');
  assert.equal(id('departamento'), 'sala-estudos');
  assert.equal(findPlace(map, 'sala 9').kind, 'ambiguous');
  assert.equal(findPlace(map, 'banheiro').kind, 'several');
});

test('das informacoes ate a 15B: sobe a escada entre os blocos e conta os cruzamentos', () => {
  assert.deepEqual(route('informacoes', '15B'), [
    'Siga em frente pelo piso tátil até o primeiro cruzamento. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil, passando por 1 cruzamento, até a escada. Quando terminar, diga pronto.',
    'Suba a escada. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil até o primeiro cruzamento. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil até o 12º cruzamento. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil até o fim. Você chegará na sala 15B. Quando chegar, diga pronto.',
  ]);
  assert.ok(route('15B', 'protocolo').includes('Desça a escada. Quando terminar, diga pronto.'));
});

test('da entrada do bloco B: porta aberta como referencia, 20B a esquerda e 17B a direita', () => {
  assert.deepEqual(route('entrada-b', '20B'), [
    'Siga em frente pelo piso tátil até o segundo cruzamento. Referência: porta sempre aberta. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil até o fim. Você chegará na sala 20B. Quando chegar, diga pronto.',
  ]);
  assert.match(route('entrada-b', '17B')[1], /^Vire à direita/);
});

test('lado da curva depende do sentido: sair da 20B e sair do protocolo', () => {
  assert.deepEqual(route('20B', 'banheiro-f-b'), [
    'Siga em frente pelo piso tátil até o primeiro cruzamento. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil até o terceiro cruzamento. Quando terminar, diga pronto.',
    'Vire à esquerda e siga o piso tátil até o fim. Você chegará no banheiro feminino do bloco B. Quando chegar, diga pronto.',
  ]);
  assert.match(route('protocolo', '4A')[1], /^Vire à direita/);
  assert.match(route('protocolo', '4A')[2], /^Vire à direita e siga o piso tátil até o 13º cruzamento/);
});

test('conversa: onde voce esta, banheiro mais perto e troca para o feminino', () => {
  const session = createSession(map);
  assert.match(session.start().say.join(' '), /Onde você está agora\? Diga a sala ou o lugar, por exemplo, entrada do bloco B, balcão de informações ou sala 5A\./);
  assert.match(session.hear(['estou na sala 5A']).say[0], /^Você está na sala 5A\./);
  assert.match(session.hear(['banheiro']).say[0], /banheiro masculino do bloco A/);
  assert.match(session.hear(['não, feminino']).say[0], /banheiro feminino do bloco A/);
  const go = session.hear(['isso']);
  assert.equal(go.phase, 'navigating');
  assert.match(go.say[0], /^Rota até o banheiro feminino do bloco A, em 3 etapas/);
});

test('origem com nome repetido pede o nome completo', () => {
  const session = createSession(map);
  session.start();
  assert.match(session.hear(['estou no banheiro']).say[0], /Encontrei mais de um lugar/);
  assert.equal(session.phase, 'entrance');
});
