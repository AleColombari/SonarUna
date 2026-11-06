// Roda um caso de casos/conversas.json na versao web e devolve as respostas: [{ fase, ouvir, dizer }].
// O app Android faz o mesmo em CasesTest.kt; as duas versoes precisam dar exatamente o mesmo resultado.
import { readFileSync } from 'node:fs';
import { loadMap } from '../web/engine.js';
import { createSession } from '../web/conversation.js';
import { loadGpsPoints, suggestOrigin } from '../web/localizacao.js';

const read = (name) => JSON.parse(readFileSync(new URL(`../data/${name}`, import.meta.url), 'utf8'));
const map = loadMap(read('unaerp.json'));
const gps = loadGpsPoints(read('unaerp-pontos.geojson'), map);

const answer = (reply) => ({ fase: reply.phase, ouvir: reply.listen, dizer: reply.say });

export function runCase(caso) {
  const session = createSession(map);
  const result = [answer(session.start(caso.gps ? suggestOrigin(gps, caso.gps) : null))];
  for (const passo of caso.passos) {
    if (passo === 'silencio') result.push(answer(session.silence()));
    else if (passo === 'retomar') result.push(answer(session.resume()));
    else result.push(answer(session.hear(passo)));
  }
  return result;
}
