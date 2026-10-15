import { test } from 'node:test';
import assert from 'node:assert/strict';
import { STORAGE_KEY, callMode, loadStoredPhones, maskPhone, normalizePhone, readContacts, savePhones } from '../contacts.js';

const memoryStorage = (initial = {}) => {
  const data = { ...initial };
  return {
    getItem: (key) => data[key] ?? null,
    setItem: (key, value) => { data[key] = String(value); },
    data,
  };
};

test('telefones brasileiros e internacionais viram +digitos', () => {
  assert.equal(normalizePhone('16 99999-9999'), '+5516999999999');
  assert.equal(normalizePhone('(16) 3333-4444'), '+551633334444');
  assert.equal(normalizePhone('+55 16 99999-9999'), '+5516999999999');
  assert.equal(normalizePhone('5516999999999'), '+5516999999999');
  assert.equal(normalizePhone('+1 (202) 555-0100'), '+12025550100');
});

test('texto que nao e telefone e rejeitado, inclusive tentativas de injetar no link tel:', () => {
  for (const text of ['', 'abc', '123', '16 9999', 'tel:16999999999', '16999999999;rm', '16 99999-9999 ligue', '+12', null, undefined]) {
    assert.equal(normalizePhone(text), null, String(text));
  }
});

test('o numero so aparece mascarado', () => {
  assert.equal(maskPhone('+5516999991234'), 'final 1234');
});

test('guardar e ler: so entram os papeis com telefone', () => {
  const storage = memoryStorage();
  assert.deepEqual(readContacts(storage), []);
  assert.deepEqual(savePhones(storage, { atendente: '16 99999-1111', seguranca: '' }), { ok: true, erros: [] });
  const contacts = readContacts(storage);
  assert.equal(contacts.length, 1);
  assert.equal(contacts[0].id, 'atendente');
  assert.equal(contacts[0].rotulo, 'o atendente');
  assert.equal(contacts[0].telefone, '+5516999991111');
});

test('um numero invalido nao grava nada', () => {
  const storage = memoryStorage();
  const result = savePhones(storage, { atendente: '16 99999-1111', seguranca: 'abc' });
  assert.equal(result.ok, false);
  assert.match(result.erros[0], /segurança/);
  assert.equal(storage.data[STORAGE_KEY], undefined);
});

test('armazenamento quebrado, vazio ou adulterado vira sem contatos', () => {
  assert.deepEqual(readContacts(memoryStorage({ [STORAGE_KEY]: '{nao e json' })), []);
  assert.deepEqual(readContacts(memoryStorage({ [STORAGE_KEY]: JSON.stringify({ atendente: 'tel:1;2' }) })), []);
  assert.deepEqual(loadStoredPhones({ getItem() { throw new Error('bloqueado'); } }), { atendente: '', seguranca: '' });
  assert.equal(savePhones({ setItem() { throw new Error('cheio'); } }, { atendente: '16 99999-1111' }).ok, false);
});

test('telefone do .env vale mais que o digitado, e cada papel e independente', () => {
  const storage = memoryStorage();
  savePhones(storage, { atendente: '16 99999-1111', seguranca: '16 99999-2222' });
  const contacts = readContacts(storage, { atendente: '16 98888-0000', seguranca: '' });
  assert.equal(contacts.find((c) => c.id === 'atendente').telefone, '+5516988880000');
  assert.equal(contacts.find((c) => c.id === 'seguranca').telefone, '+5516999992222');
});

test('so o .env, sem nada digitado, ja basta; .env invalido e ignorado', () => {
  assert.equal(readContacts(memoryStorage(), { atendente: '16 98888-0000' }).length, 1);
  assert.deepEqual(readContacts(memoryStorage(), { atendente: 'tel:1;2', seguranca: 42 }), []);
  assert.deepEqual(readContacts(memoryStorage(), null), []);
});

test('ligacao real no celular, simulada no computador, e a URL pode forcar', () => {
  assert.equal(callMode('Mozilla/5.0 (Linux; Android 14; Pixel 7) Mobile Safari'), 'real');
  assert.equal(callMode('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)'), 'real');
  assert.equal(callMode('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130'), 'simulada');
  assert.equal(callMode('Mozilla/5.0 (Windows NT 10.0)', '?ligacao=real'), 'real');
  assert.equal(callMode('Mozilla/5.0 (Linux; Android 14) Mobile', '?ligacao=simulada'), 'simulada');
  assert.equal(callMode('Mozilla/5.0 (Windows NT 10.0)', '?ligacao=xyz'), 'simulada');
  assert.equal(callMode(undefined), 'simulada');
});
