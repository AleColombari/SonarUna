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
  assert.match(said(route), /Rota até a sala A12: 60 passos, em 3 etapas/);
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

// --- Pedido de ajuda ---------------------------------------------------------------------------

const atendente = { id: 'atendente', nome: 'atendente', rotulo: 'o atendente', aliases: ['atendente', 'atendimento'], telefone: '+5516999990001' };
const seguranca = { id: 'seguranca', nome: 'segurança', rotulo: 'a segurança', aliases: ['seguranca', 'vigilante', 'guarda'], telefone: '+5516999990002' };

const sessionWith = (contacts) => createSession(map, () => contacts);
const toNavigation = (session) => {
  session.start();
  session.hear(['principal']);
  session.hear(['sala A12']);
  session.hear(['sim']);
  return session;
};

test('"preciso de ajuda" com dois contatos pergunta com quem falar e liga para quem foi escolhido', () => {
  const session = toNavigation(sessionWith([atendente, seguranca]));
  const ask = session.hear(['preciso de ajuda']);
  assert.equal(ask.phase, 'assist-choose');
  assert.ok(ask.listen);
  assert.match(said(ask), /Com quem você quer falar\? Diga atendente ou segurança/);
  assert.equal(ask.action, undefined);

  const call = session.hear(['a segurança por favor']);
  assert.equal(call.action.type, 'call');
  assert.equal(call.action.contact.id, 'seguranca');
  assert.equal(call.action.contact.telefone, '+5516999990002');
  assert.equal(call.listen, false);
  assert.match(said(call), /Ligando para a segurança agora/);
  assert.doesNotMatch(said(call), /5516/);
  assert.equal(call.phase, 'navigating');
});

test('com um contato so, pede confirmacao e liga ao ouvir sim', () => {
  const session = toNavigation(sessionWith([atendente]));
  const ask = session.hear(['socorro']);
  assert.equal(ask.phase, 'assist-confirm');
  assert.match(said(ask), /Você quer falar com o atendente\? Diga sim ou não/);
  assert.equal(session.hear(['talvez']).action, undefined);
  const call = session.hear(['sim']);
  assert.equal(call.action.contact.id, 'atendente');
});

test('citar o contato no pedido liga direto', () => {
  const session = sessionWith([atendente, seguranca]);
  session.start();
  const call = session.hear(['quero falar com o atendente, preciso de ajuda']);
  assert.equal(call.action.contact.id, 'atendente');
});

test('cancelar ou dizer nao volta para a pergunta em que a conversa estava', () => {
  for (const answer of ['cancelar', 'não', 'voltar']) {
    const session = sessionWith([atendente, seguranca]);
    session.start();
    session.hear(['principal']);
    session.hear(['preciso de ajuda']);
    const back = session.hear([answer]);
    assert.equal(back.phase, 'destination', answer);
    assert.equal(back.action, undefined);
    assert.match(said(back), /Tudo bem\. Vamos continuar\. Para onde você quer ir/);
  }
});

test('resposta que nao se entende mantem a escolha aberta', () => {
  const session = sessionWith([atendente, seguranca]);
  session.start();
  session.hear(['preciso de ajuda']);
  const unclear = session.hear(['banana']);
  assert.equal(unclear.phase, 'assist-choose');
  assert.match(said(unclear), /Não entendi\. Diga atendente ou segurança/);
});

test('sem contatos cadastrados avisa e continua a conversa', () => {
  const session = sessionWith([]);
  session.start();
  const result = session.hear(['preciso de ajuda']);
  assert.equal(result.phase, 'entrance');
  assert.equal(result.action, undefined);
  assert.match(said(result), /Não há nenhum contato de ajuda cadastrado/);
  assert.match(said(result), /Por qual entrada você entrou/);
});

test('depois da ligacao, retomar continua na mesma etapa da rota', () => {
  const session = toNavigation(sessionWith([atendente]));
  session.hear(['pronto']);
  session.hear(['preciso de ajuda']);
  session.hear(['sim']);
  const back = session.resume();
  assert.equal(back.phase, 'navigating');
  assert.ok(back.listen);
  assert.match(said(back), /^Retomando\. Etapa 2 de 3/);
});

test('retomar uma sessao encerrada comeca de novo', () => {
  const session = sessionWith([]);
  assert.equal(session.resume().phase, 'entrance');
});

test('"ajuda" sozinho so explica o app; "socorro" chama alguem', () => {
  const session = sessionWith([atendente]);
  session.start();
  session.hear(['principal']);
  const help = session.hear(['ajuda']);
  assert.equal(help.phase, 'destination');
  assert.match(said(help), /Para falar com alguém, diga preciso de ajuda/);
  assert.equal(session.hear(['socorro']).phase, 'assist-confirm');
});

test('a saudacao so oferece ajuda quando ha contatos', () => {
  const withContacts = sessionWith([atendente]).start();
  const without = sessionWith([]).start();
  assert.match(said(withContacts), /diga preciso de ajuda a qualquer momento/);
  assert.doesNotMatch(said(without), /preciso de ajuda/);
});

test('silencio demais durante o pedido de ajuda encerra sem ligar', () => {
  const session = sessionWith([atendente, seguranca]);
  session.start();
  session.hear(['preciso de ajuda']);
  let last;
  for (let i = 0; i < MAX_SILENCE_RETRIES; i++) last = session.silence();
  assert.equal(last.phase, 'idle');
  assert.equal(last.action, undefined);
});
