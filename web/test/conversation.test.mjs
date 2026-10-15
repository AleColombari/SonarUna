import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadMap } from '../engine.js';
import { MAX_SILENCE_RETRIES, createSession } from '../conversation.js';

const map = loadMap(JSON.parse(readFileSync(new URL('../../data/bloco-a.json', import.meta.url), 'utf8')));
const said = (result) => result.say.join(' ');

test('fluxo completo: entrada principal, sala A12, confirmacao e tres passos', () => {
  const session = createSession(map);
  const start = session.start();
  assert.equal(start.phase, 'entrance');
  assert.ok(start.listen);
  assert.match(said(start), /Por qual entrada você entrou/);

  const entrance = session.hear(['entrei pela entrada principal']);
  assert.equal(entrance.phase, 'destination');
  assert.match(said(entrance), /^Entrada principal\. Para onde você quer ir/);

  const destination = session.hear(['quero ir para a sala A12']);
  assert.equal(destination.phase, 'confirm');
  assert.match(said(destination), /a sala A12\. Está certo/);

  const route = session.hear(['sim']);
  assert.equal(route.phase, 'navigating');
  assert.match(said(route), /Rota até a sala A12: cerca de 55 passos, em 3 etapas/);
  assert.match(said(route), /Etapa 1 de 3\. Siga em frente/);

  assert.match(said(session.hear(['pronto'])), /Etapa 2 de 3\. Vire à direita/);
  assert.match(said(session.hear(['repetir'])), /Etapa 2 de 3/);
  assert.match(said(session.hear(['voltar'])), /Etapa 1 de 3/);
  session.hear(['próximo']);
  assert.match(said(session.hear(['pronto'])), /Etapa 3 de 3/);

  const arrived = session.hear(['pronto']);
  assert.equal(arrived.phase, 'idle');
  assert.equal(arrived.listen, false);
  assert.match(said(arrived), /Você chegou na sala A12/);
});

test('usa as outras hipoteses do reconhecimento quando a primeira nao serve', () => {
  const session = createSession(map);
  session.start();
  assert.equal(session.hear(['entrei pela intrada', 'entrada lateral']).phase, 'destination');
  assert.equal(session.hear(['xis', 'sala a doze']).phase, 'confirm');
});

test('entrada nao reconhecida pergunta de novo sem avancar', () => {
  const session = createSession(map);
  session.start();
  const result = session.hear(['não sei']);
  assert.equal(result.phase, 'entrance');
  assert.ok(result.listen);
  assert.match(said(result), /Não reconheci essa entrada/);
});

test('destino desconhecido, ambiguo ou igual a entrada nao avancam', () => {
  const session = createSession(map);
  session.start();
  session.hear(['principal']);
  assert.match(said(session.hear(['sala A30'])), /Não encontrei a sala A30/);
  assert.match(said(session.hear(['cantina'])), /Não entendi o destino/);
  assert.equal(session.phase, 'destination');
});

test('responder nao na confirmacao volta a pedir o destino', () => {
  const session = createSession(map);
  session.start();
  session.hear(['principal']);
  session.hear(['secretaria']);
  assert.equal(session.hear(['não']).phase, 'destination');
  assert.equal(session.hear(['banheiro']).phase, 'confirm');
  assert.match(said(session.hear(['talvez'])), /Diga apenas sim ou não/);
  assert.equal(session.phase, 'confirm');
});

test('parar, recomecar e ajuda funcionam em qualquer etapa', () => {
  const session = createSession(map);
  session.start();
  session.hear(['principal']);
  assert.match(said(session.hear(['ajuda'])), /sala A12, secretaria ou banheiro/);
  assert.equal(session.phase, 'destination');
  assert.equal(session.hear(['recomeçar']).phase, 'entrance');
  const stopped = session.hear(['parar']);
  assert.equal(stopped.phase, 'idle');
  assert.equal(stopped.listen, false);
  assert.deepEqual(session.hear(['sala A12']).say, []);
});

test('voltar na etapa do destino pergunta a entrada de novo', () => {
  const session = createSession(map);
  session.start();
  session.hear(['principal']);
  const result = session.hear(['voltar']);
  assert.equal(result.phase, 'entrance');
  assert.match(said(result), /Por qual entrada/);
});

test('silencio repetido encerra depois do limite', () => {
  const session = createSession(map);
  session.start();
  for (let i = 1; i < MAX_SILENCE_RETRIES; i++) {
    const retry = session.silence();
    assert.ok(retry.listen);
    assert.match(said(retry), /Não ouvi/);
  }
  const ended = session.silence();
  assert.equal(ended.phase, 'idle');
  assert.equal(ended.listen, false);
});

test('uma resposta valida zera a contagem de silencios', () => {
  const session = createSession(map);
  session.start();
  session.silence();
  session.silence();
  session.hear(['principal']);
  session.silence();
  assert.equal(session.phase, 'destination');
});

test('instrucao nao entendida durante a rota nao muda de passo', () => {
  const session = createSession(map);
  session.start();
  session.hear(['lateral']);
  session.hear(['sala A12']);
  session.hear(['sim']);
  const result = session.hear(['banana']);
  assert.match(said(result), /Diga pronto para a próxima etapa/);
  assert.match(said(session.hear(['repetir'])), /Etapa 1 de 2/);
});
