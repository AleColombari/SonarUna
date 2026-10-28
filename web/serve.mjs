// Servidor local sem dependencias: node web/serve.mjs  ->  http://localhost:8080/web/
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { dirname, extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const port = Number(process.env.PORT ?? 8080);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.geojson': 'application/geo+json; charset=utf-8',
};

/** Le o .env da raiz (CHAVE=valor, # comentario). Sem arquivo, devolve {}. */
function readEnv() {
  try {
    const env = {};
    for (const line of readFileSync(join(root, '.env'), 'utf8').split(/\r?\n/)) {
      if (line.trim().startsWith('#')) continue;
      const match = line.match(/^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (match) env[match[1]] = match[2].replace(/^(["'])(.*)\1$/, '$2');
    }
    return env;
  } catch {
    return {};
  }
}

createServer(async (request, response) => {
  try {
    let path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (path === '/') {
      response.writeHead(302, { Location: '/web/' }).end();
      return;
    }
    if (path === '/config.json') {
      // So os dois telefones de ajuda saem do .env; nada mais.
      const env = readEnv();
      response.writeHead(200, { 'Content-Type': types['.json'], 'Cache-Control': 'no-store' });
      response.end(JSON.stringify({ atendente: env.ATENDENTE_TELEFONE ?? '', seguranca: env.SEGURANCA_TELEFONE ?? '' }));
      return;
    }
    if (path.endsWith('/')) path += 'index.html';
    const file = normalize(join(root, path));
    // So serve web/ e data/, nunca o resto do repositorio.
    const allowed = ['web', 'data'].some((dir) => file.startsWith(join(root, dir) + sep));
    if (!allowed || !types[extname(file)]) {
      response.writeHead(404).end('Nao encontrado');
      return;
    }
    response.writeHead(200, { 'Content-Type': types[extname(file)], 'Cache-Control': 'no-store' });
    response.end(await readFile(file));
  } catch {
    response.writeHead(404).end('Nao encontrado');
  }
}).listen(port, '127.0.0.1', () => console.log(`SonarUna em http://localhost:${port}/web/`));
