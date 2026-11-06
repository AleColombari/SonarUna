import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runCase } from '../../casos/rodar.mjs';

// Conversas de referencia compartilhadas com o app Android (app/src/test/.../CasesTest.kt).
const { casos } = JSON.parse(readFileSync(new URL('../../casos/conversas.json', import.meta.url), 'utf8'));

for (const caso of casos) {
  test(`caso compartilhado: ${caso.nome}`, () => {
    assert.ok(caso.esperado, 'sem respostas esperadas: rode node casos/atualizar.mjs');
    assert.deepEqual(runCase(caso), caso.esperado);
  });
}
