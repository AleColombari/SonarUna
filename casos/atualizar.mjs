// Gera as respostas esperadas de casos/conversas.json rodando a versao web: node casos/atualizar.mjs
// Use depois de mudar uma fala de proposito; revise a diferenca no Git e leve a mesma mudanca para o Kotlin.
import { readFileSync, writeFileSync } from 'node:fs';
import { runCase } from './rodar.mjs';

const path = new URL('./conversas.json', import.meta.url);
const file = JSON.parse(readFileSync(path, 'utf8'));
for (const caso of file.casos) caso.esperado = runCase(caso);
writeFileSync(path, `${JSON.stringify(file, null, 2)}\n`);
console.log(`${file.casos.length} casos atualizados em casos/conversas.json`);
