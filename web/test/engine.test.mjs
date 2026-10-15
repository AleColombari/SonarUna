import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  ateLugar, buildInstructions, classifyCommand, emLugar, paraLugar, classifyYesNo, findEntrance, findPlace, loadMap, normalize, parseRoomCode, planRoute,
} from '../engine.js';

const map = loadMap(JSON.parse(readFileSync(new URL('../../data/bloco-a.json', import.meta.url), 'utf8')));
const canonical = (text) => parseRoomCode(text)?.canonical ?? null;
const placeId = (text) => {
  const result = findPlace(map, text);
  return result.kind === 'found' ? result.place.id : result.kind;
};

test('normalize remove acentos, pontuacao e separa letra de numero', () => {
  assert.equal(normalize('  Á-15,  Sala!! '), 'a 15 sala');
  assert.equal(normalize('A15'), 'a 15');
});

test('codigos escritos de varias formas viram o mesmo codigo', () => {
  for (const text of ['A15', 'a 15', 'A-15', 'sala A15', 'Sala a 15.', 'á 15']) {
    assert.equal(canonical(text), 'A15', text);
  }
});

test('numeros falados viram digitos', () => {
  assert.equal(canonical('a quinze'), 'A15');
  assert.equal(canonical('sala a vinte e dois'), 'A22');
  assert.equal(canonical('a cento e cinco'), 'A105');
  assert.equal(canonical('b três'), 'B3');
  assert.equal(canonical('a um cinco'), 'A15');
  assert.equal(canonical('b dois um'), 'B21');
});

test('bloco e sala em qualquer ordem; sala sem bloco guarda so o numero', () => {
  assert.equal(canonical('bloco B sala 3'), 'B3');
  assert.equal(canonical('sala 3 do bloco b'), 'B3');
  assert.equal(canonical('quero ir para a sala 20'), '20');
  assert.equal(canonical('sala 12 e 15'), '12');
  assert.equal(canonical('sala dez e quinze'), '10');
  for (const text of ['biblioteca', 'secretaria', '', '???']) assert.equal(parseRoomCode(text), null, text);
});

test('o mapa fictício do bloco A carrega e todos os pontos estao ligados', () => {
  assert.ok(map.points.size > 20);
  for (const id of map.points.keys()) {
    assert.ok(map.adjacency.get(id).length > 0, `${id} sem trechos`);
  }
});

test('loadMap rejeita trecho com ponto inexistente e metros invalidos', () => {
  const base = { pontos: [{ id: 'a', nome: 'a' }, { id: 'b', nome: 'b' }], entradas: [] };
  assert.throws(() => loadMap({ ...base, trechos: [{ de: 'a', para: 'x', metros: 1, rumo: 0 }] }), /inexistente/);
  assert.throws(() => loadMap({ ...base, trechos: [{ de: 'a', para: 'b', metros: 0, rumo: 0 }] }), /metros/);
});

test('entradas sao reconhecidas por varios jeitos', () => {
  assert.equal(findEntrance(map, 'entrei pela entrada principal').id, 'entrada-principal');
  assert.equal(findEntrance(map, 'pela porta da frente').id, 'entrada-principal');
  assert.equal(findEntrance(map, 'Lateral').id, 'entrada-lateral');
  assert.equal(findEntrance(map, 'não sei'), null);
});

test('destinos: codigo, numero solto, apelido e erros', () => {
  assert.equal(placeId('quero ir para a sala A12'), 'A12');
  assert.equal(placeId('sala a doze'), 'A12');
  assert.equal(placeId('sala 12'), 'A12');
  assert.equal(placeId('sala 1'), 'A1');
  assert.equal(placeId('quero ir na secretaria'), 'secretaria');
  assert.equal(placeId('onde fica o banheiro'), 'banheiro');
  assert.equal(placeId('sala A30'), 'unknown-room');
  assert.equal(placeId('sala 30'), 'unknown-room');
  assert.equal(placeId('cantina'), 'none');
});

test('rota da entrada principal ate A12 e a mais curta', () => {
  const segments = planRoute(map, 'entrada-principal', 'A12');
  assert.deepEqual(segments.map((s) => s.para), ['hall', 'cruzamento', 'leste-1', 'leste-2', 'leste-3', 'A12']);
  assert.equal(segments.reduce((sum, s) => sum + s.metros, 0), 6 + 6 + 10 + 8 + 8 + 4);
});

test('instrucoes: A12 pela entrada principal junta a reta e vira a esquerda no fim', () => {
  const entrance = map.entrances.find((e) => e.id === 'entrada-principal');
  const steps = buildInstructions(map, entrance, planRoute(map, 'entrada-principal', 'A12'));
  assert.equal(steps.length, 3);
  assert.match(steps[0].texto, /^Siga em frente e ande cerca de 12 metros, ou 17 passos\./);
  assert.match(steps[1].texto, /^Vire à direita e ande cerca de 26 metros, ou 37 passos\./);
  assert.match(steps[2].texto, /^Vire à direita e ande cerca de 4 metros/);
  assert.match(steps[2].texto, /Você chegará na sala A12/);
});

test('o lado da curva se inverte pela entrada lateral', () => {
  const entrance = map.entrances.find((e) => e.id === 'entrada-lateral');
  const steps = buildInstructions(map, entrance, planRoute(map, 'entrada-lateral', 'A12'));
  assert.equal(steps.length, 2);
  assert.match(steps[0].texto, /^Siga em frente e ande cerca de 4 metros/);
  assert.match(steps[1].texto, /^Vire à esquerda e ande cerca de 4 metros/);
});

test('A1 pela entrada principal: esquerda no corredor, depois direita para a sala', () => {
  const entrance = map.entrances.find((e) => e.id === 'entrada-principal');
  const steps = buildInstructions(map, entrance, planRoute(map, 'entrada-principal', 'A1'));
  assert.match(steps[1].texto, /^Vire à esquerda/);
  assert.match(steps[2].texto, /^Vire à direita/);
});

test('sem caminho devolve null e origem igual ao destino devolve lista vazia', () => {
  assert.equal(planRoute(map, 'hall', 'nao-existe'), null);
  assert.deepEqual(planRoute(map, 'hall', 'hall'), []);
});

test('confirmacoes usam frase inteira', () => {
  for (const text of ['sim', 'SIM!', 'Sim, isso mesmo.', 'está certo']) assert.equal(classifyYesNo(text), 'yes', text);
  for (const text of ['não', 'Não está correto.', 'corrigir']) assert.equal(classifyYesNo(text), 'no', text);
  for (const text of ['talvez', 'sim ou não', 'não está errado', 'simples']) assert.equal(classifyYesNo(text), null, text);
});

test('comandos de voz', () => {
  assert.equal(classifyCommand('pronto'), 'next');
  assert.equal(classifyCommand('já cheguei'), 'next');
  assert.equal(classifyCommand('cheguei'), 'next');
  assert.equal(classifyCommand('pode repetir'), 'repeat');
  assert.equal(classifyCommand('repete por favor'), 'repeat');
  assert.equal(classifyCommand('voltar'), 'back');
  assert.equal(classifyCommand('quero parar'), 'stop');
  assert.equal(classifyCommand('parar'), 'stop');
  assert.equal(classifyCommand('recomeçar'), 'restart');
  assert.equal(classifyCommand('quero ir para a sala A12'), null);
});

test('artigos: sala e feminino, banheiro e masculino', () => {
  const sala = map.points.get('A12');
  const banheiro = map.points.get('banheiro');
  assert.equal(paraLugar(sala), 'para a sala A12');
  assert.equal(paraLugar(banheiro), 'para o banheiro');
  assert.equal(ateLugar(banheiro), 'até o banheiro');
  assert.equal(emLugar(sala), 'na sala A12');
  assert.equal(emLugar(banheiro), 'no banheiro');
});
