// Desenha um mapa (data/*.json) como SVG para conferir de olho: node docs/desenhar-mapa.mjs [data/unaerp.json] [docs/mapa-unaerp.svg]
// Cada trecho vira uma linha na direcao do seu rumo (0 = cima, 90 = direita...). Cruzamentos sao bolinhas
// pretas, lugares sao etiquetas, escadas sao tracejadas em vermelho. Sem escala: so ordem e lado importam.
import { readFileSync, writeFileSync } from 'node:fs';

const [input = 'data/unaerp.json', output = 'docs/mapa-unaerp.svg'] = process.argv.slice(2);
const raw = JSON.parse(readFileSync(input, 'utf8'));
const points = new Map(raw.pontos.map((p) => [p.id, p]));
const isPlace = (id) => !['cruzamento', 'curva', 'patamar'].includes(points.get(id).tipo);

// Posicoes: percorre os trechos a partir do primeiro ponto. Trechos ate lugares sao curtos.
const STEP = 60;
const LEAF = 34;
const edges = raw.trechos.flatMap((t) => [
  { a: t.de, b: t.para, rumo: t.rumo, t },
  { a: t.para, b: t.de, rumo: t.rumo + 180, t },
]);
const parent = new Map();
const pos = new Map([[raw.trechos[0].de, { x: 0, y: 0 }]]);
const queue = [raw.trechos[0].de];
while (queue.length > 0) {
  const at = queue.shift();
  for (const e of edges.filter((edge) => edge.a === at && !pos.has(edge.b))) {
    const length = isPlace(e.b) || isPlace(e.a) ? LEAF : e.t.escada ? STEP * 1.6 : STEP;
    const rad = (e.rumo * Math.PI) / 180;
    parent.set(e.b, at);
    pos.set(e.b, { x: pos.get(at).x + Math.sin(rad) * length, y: pos.get(at).y - Math.cos(rad) * length });
    queue.push(e.b);
  }
}

const xs = [...pos.values()].map((p) => p.x);
const ys = [...pos.values()].map((p) => p.y);
const pad = 140;
const minX = Math.min(...xs) - pad;
const minY = Math.min(...ys) - pad;
const width = Math.max(...xs) - minX + pad;
const height = Math.max(...ys) - minY + pad;
// Siglas do esboco, para os nomes caberem no desenho.
const SIGLAS = {
  'banheiro-f-b': 'BF', 'banheiro-m-b': 'BM', 'banheiro-f-a': 'BF', 'banheiro-m-a': 'BM', audiovisual: 'AV',
  direito: 'Direito', multiatendimento: 'MA', financeiro: 'Financeiro', cobranca: 'Cobrança', professores: 'Prof',
  informacoes: 'Info', protocolo: 'Protocolo', 'sala-estudos': 'Sala de estudos', 'sem-numero': 'sem nº',
  liape: 'LIAPE', 'entrada-b': 'porta do bloco B', 'entrada-principal': 'ENTRADA PRINCIPAL', corrimao: 'corrimão',
};
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');

const lines = raw.trechos.map((t) => {
  const a = pos.get(t.de);
  const b = pos.get(t.para);
  const style = t.escada ? 'stroke="#c0392b" stroke-width="5" stroke-dasharray="6 4"' : 'stroke="#1f5fbf" stroke-width="5"';
  const label = t.escada
    ? `<text x="${(a.x + b.x) / 2 + 10}" y="${(a.y + b.y) / 2}" class="esc">escada (${t.escada} de ${t.de} para ${t.para})</text>` : '';
  const refs = (t.referencias ?? []).length
    ? `<text x="${(a.x + b.x) / 2}" y="${(a.y + b.y) / 2 - 10}" class="ref" text-anchor="middle">${esc(t.referencias.join(', '))}</text>` : '';
  return `<line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" ${style}/>${label}${refs}`;
});
const nodes = [...pos.entries()].map(([id, p]) => {
  const point = points.get(id);
  if (!isPlace(id)) {
    return `<circle cx="${p.x}" cy="${p.y}" r="6" fill="#111"/><text x="${p.x + 8}" y="${p.y + 16}" class="id">${esc(id)}</text>`;
  }
  const name = point.codigo ?? SIGLAS[id] ?? point.nome;
  // Etiqueta do lado oposto ao cruzamento de onde o lugar sai.
  const from = pos.get(parent.get(id)) ?? { x: p.x, y: p.y + 1 };
  const dx = p.x - from.x;
  const dy = p.y - from.y;
  const [tx, ty, anchor] = Math.abs(dx) > Math.abs(dy)
    ? [p.x + Math.sign(dx) * 10, p.y + 5, dx > 0 ? 'start' : 'end']
    : [p.x, p.y + (dy < 0 ? -10 : 20), 'middle'];
  return `<g><rect x="${p.x - 4}" y="${p.y - 4}" width="8" height="8" fill="#e67e22"/>`
    + `<text x="${tx}" y="${ty}" class="place" text-anchor="${anchor}">${esc(name)}</text></g>`;
});

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${minX} ${minY} ${width} ${height}" width="100%" preserveAspectRatio="xMidYMin meet">
<style>text{font-family:Segoe UI,Arial,sans-serif}.place{font-size:13px;font-weight:600;fill:#111}.id{font-size:9px;fill:#888}
.esc{font-size:11px;fill:#c0392b}.ref{font-size:11px;fill:#1f5fbf;font-style:italic}.t{font-size:15px;font-weight:700}</style>
<rect x="${minX}" y="${minY}" width="${width}" height="${height}" fill="#fff"/>
<text x="${minX + 20}" y="${minY + 30}" class="t">${esc(raw.nome ?? input)}: piso tátil (azul), escadas (vermelho), cruzamentos (pretos), lugares (laranja). Sem escala.</text>
${lines.join('\n')}
${nodes.join('\n')}
</svg>
`;
writeFileSync(output, svg);
console.log(`${output}: ${pos.size} pontos, ${raw.trechos.length} trechos`);
