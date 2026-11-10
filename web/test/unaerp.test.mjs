import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildInstructions, exitHeading, findPlace, loadMap, planRoute } from '../engine.js';
import { createSession } from '../conversation.js';

const map = loadMap(JSON.parse(readFileSync(new URL('../../data/unaerp.json', import.meta.url), 'utf8')));
// Lugares com saida unica; portas ficam no meio do piso e tem duas.
const places = [...map.points.values()].filter((point) => !['cruzamento', 'curva', 'patamar', 'porta'].includes(point.tipo));

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
  assert.equal(id('estou na entrada'), 'entrada-principal');
  assert.equal(id('entrada do bloco b'), 'entrada-b');
  assert.equal(id('liape'), 'liape');
  assert.equal(id('sala dos computadores'), 'sala-estudos');
  assert.equal(id('departamento'), 'sala-estudos');
  assert.equal(findPlace(map, 'sala 9').kind, 'ambiguous');
  assert.equal(findPlace(map, 'banheiro').kind, 'several');
});

test('das informacoes ate a 15B: sobe a escada e conta os cruzamentos ate cada virada', () => {
  assert.deepEqual(route('informacoes', '15B'), [
    'No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil, passando por 1 cruzamento, até a escada. Quando terminar, diga pronto.',
    'Suba a escada. Quando terminar, diga pronto.',
    'No próximo cruzamento, vire à esquerda. Referência: trecho curto, de poucos passos. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.',
    'Continue em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.',
    'No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil até o fim. Você chegará na sala 15B. Quando chegar, diga pronto.',
  ]);
  assert.ok(route('15B', 'protocolo').includes('Desça a escada. Quando terminar, diga pronto.'));
});

test('da entrada principal: a porta do bloco B e referencia e nao conta; 20B a esquerda, 17B a direita', () => {
  assert.deepEqual(route('entrada-principal', '20B'), [
    'No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.',
    'Daqui a 2 cruzamentos, vire à esquerda. Referência: porta da entrada do bloco B, sempre aberta. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil até o fim. Você chegará na sala 20B. Quando chegar, diga pronto.',
  ]);
  assert.match(route('entrada-principal', '17B')[1], /^Daqui a 3 cruzamentos, vire à direita/);
  const fromDoor = buildInstructions(map, { ponto: 'entrada-b', rumo: exitHeading(map, 'entrada-b') }, planRoute(map, 'entrada-b', '18B'));
  assert.match(fromDoor[0].texto, /^Daqui a 3 cruzamentos, vire à esquerda/);
  assert.match(route('20B', 'entrada-b').at(-1), /até a porta\. Você chegará na entrada do bloco B/);
});

test('lado da virada depende do sentido, e salas nao sao referencia no caminho', () => {
  assert.deepEqual(route('20B', 'banheiro-f-b'), [
    'No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.',
    'Daqui a 3 cruzamentos, vire à esquerda. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil até o fim. Você chegará no banheiro feminino do bloco B. Quando chegar, diga pronto.',
  ]);
  assert.deepEqual(route('protocolo', '4A'), [
    'No próximo cruzamento, vire à direita. Quando terminar, diga pronto.',
    'No próximo cruzamento, vire à direita. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.',
    'Continue em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.',
    'Daqui a 3 cruzamentos, vire à esquerda. Quando terminar, diga pronto.',
    'Siga em frente pelo piso tátil até o fim. Você chegará na sala 4A. Quando chegar, diga pronto.',
  ]);
  for (const from of places) {
    for (const to of places) {
      if (from === to) continue;
      const steps = route(from.id, to.id);
      for (const text of steps.slice(0, -1)) assert.doesNotMatch(text, /sala|na altura/, `${from.id} -> ${to.id}: ${text}`);
    }
  }
});

test('conversa: onde voce esta, banheiro mais perto e troca para o feminino', () => {
  const session = createSession(map);
  assert.deepEqual(session.start().say, ['Onde você está agora?']);
  assert.match(session.hear(['ajuda']).say.join(' '), /por exemplo, entrada principal, balcão de informações ou sala 5A/);
  assert.match(session.hear(['estou na sala 5A']).say[0], /^Você está na sala 5A\./);
  assert.match(session.hear(['banheiro']).say[0], /^Indo para o banheiro masculino do bloco A/);
  const go = session.hear(['não, feminino']);
  assert.equal(go.phase, 'navigating');
  assert.match(go.say[0], /^Indo para o banheiro feminino do bloco A, em 3 etapas/);
});

test('nenhuma etapa do mapa pede para contar mais de 5 cruzamentos', () => {
  for (const from of places) {
    for (const to of places) {
      if (from === to) continue;
      for (const text of route(from.id, to.id)) {
        assert.doesNotMatch(text, /(Daqui a|por) ([6-9]|\d\d) cruzamentos/, `${from.id} -> ${to.id}: ${text}`);
      }
    }
  }
});

test('origem com nome repetido pede o nome completo', () => {
  const session = createSession(map);
  session.start();
  assert.match(session.hear(['estou no banheiro']).say[0], /Encontrei mais de um lugar/);
  assert.equal(session.phase, 'entrance');
});

test('na rota, silencio e a pessoa andando: o microfone segue ouvindo calado', () => {
  const session = createSession(map);
  session.start();
  session.hear(['informações']);
  session.hear(['sala 15B']);
  session.hear(['pronto']);
  for (let i = 0; i < 5; i++) {
    const quiet = session.silence();
    assert.equal(quiet.phase, 'navigating');
    assert.equal(quiet.listen, true);
    assert.deepEqual(quiet.say, []);
  }
  assert.match(session.resume().say[0], /^Retomando a etapa 2 de 8\. Diga pronto para a próxima/);
  assert.match(session.hear(['pronto']).say[0], /^Etapa 3\. Suba a escada/);
});

test('outro destino mantem onde a pessoa esta; no meio da rota, parte do fim da ultima etapa feita', () => {
  const confirm = createSession(map);
  confirm.start();
  confirm.hear(['informações']);
  confirm.hear(['sala 15B']);
  const other = confirm.hear(['outro destino']);
  assert.equal(other.phase, 'destination');
  assert.equal(other.say[0], 'Certo. Para onde você quer ir?');

  const midway = createSession(map);
  midway.start();
  midway.hear(['informações']);
  midway.hear(['sala 15B']);
  for (let i = 0; i < 3; i++) midway.hear(['pronto']); // etapas 1 a 3 feitas: a pessoa esta no alto da escada
  assert.equal(midway.hear(['novo destino']).phase, 'destination');
  const route = midway.hear(['sala 20B']);
  assert.match(route.say[0], /^Indo para a sala 20B, em 6 etapas/);
  assert.doesNotMatch(route.say.join(' '), /escada/);
});

test('dizer um lugar no meio da rota troca o destino', () => {
  const session = createSession(map);
  session.start();
  session.hear(['informações']);
  session.hear(['sala 15B']);
  const changed = session.hear(['quero ir para o protocolo']);
  assert.equal(changed.phase, 'navigating');
  assert.match(changed.say[0], /protocolo/);
});

test('saida e quero sair levam a saida mais perto, sem encerrar a orientacao', () => {
  const destino = (origem, texto) => {
    const session = createSession(map);
    session.start();
    session.hear([origem]);
    return session.hear([texto]).say[0];
  };
  assert.match(destino('sala 20B', 'saída'), /entrada do bloco B/);
  assert.match(destino('sala 5A', 'saída'), /entrada do bloco A/);
  assert.match(destino('sala 5A', 'quero sair'), /entrada do bloco A/);
  assert.match(destino('informações', 'saída principal'), /entrada principal/);
});

test('quanto falta, repasse o caminho e onde estou', () => {
  const session = createSession(map);
  session.start();
  assert.equal(session.hear(['onde estou']).say[0], 'Ainda não sei onde você está.');
  session.hear(['informações']);
  assert.equal(session.hear(['quanto falta']).say[0], 'Ainda não há uma rota.');
  session.hear(['sala 15B']);
  session.hear(['pronto']);
  session.hear(['pronto']);
  assert.equal(session.hear(['quanto falta?']).say[0], 'Você está na etapa 3 de 8. Depois desta, faltam 5 etapas até a sala 15B.');
  const where = session.hear(['onde eu estou']).say;
  assert.equal(where[0], 'Se você terminou a etapa 2, está no pé da escada entre os blocos.');
  assert.match(where[1], /^Etapa 3\. Suba a escada/);
  const overview = session.hear(['repasse o caminho']).say;
  assert.equal(overview[0], 'Caminho até a sala 15B.');
  assert.equal(overview[1], 'Etapa 3: Suba a escada.');
  assert.equal(overview.length, 8);
  assert.equal(overview.at(-1), 'Agora você está na etapa 3. Quando terminar, diga pronto.');
  for (let i = 0; i < 5; i++) session.hear(['pronto']);
  assert.equal(session.hear(['quanto falta']).say[0], 'Esta é a última etapa até a sala 15B.');
});

test('"para a 10": o "a" e o artigo, nao o bloco A (so existe a 10B)', () => {
  assert.equal(findPlace(map, 'para a 10').place?.id, '10B');
  assert.equal(findPlace(map, 'quero ir para a dez').place?.id, '10B');
  // A 9 existe nos dois blocos: "a 9" continua sendo a 9A.
  assert.equal(findPlace(map, 'para a 9').place?.id, '9A');
});

test('comandos so valem pela melhor hipotese: "pare" numa alternativa baixa nao encerra o guia', () => {
  const session = createSession(map);
  session.start();
  session.hear(['estou na sala 5A']);
  assert.equal(session.hear(['para a 10B', 'pare a 10B']).phase, 'navigating');
  assert.equal(session.hear(['repete', 'pronto']).say[0].startsWith('Etapa 1'), true);
});
