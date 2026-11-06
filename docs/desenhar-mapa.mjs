// Desenha um mapa (data/*.json) como SVG para conferir de olho:
//   node docs/desenhar-mapa.mjs [data/unaerp.json] [docs/mapa-unaerp.svg] [data/unaerp-pontos.geojson]
// Com o GeoJSON, os pontos com coordenada ganham um numero verde no desenho e uma tabela embaixo (latitude, longitude).
// Cada trecho vira uma linha na direcao do seu rumo (0 = cima, 90 = direita...). Cruzamentos sao bolinhas
// pretas, lugares sao etiquetas, escadas sao tracejadas em vermelho. Sem escala: so ordem e lado importam.
import { readFileSync, writeFileSync } from 'node:fs';

const [input = 'data/unaerp.json', output = 'docs/mapa-unaerp.svg', gpsInput = 'data/unaerp-pontos.geojson'] = process.argv.slice(2);
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
  liape: 'LIAPE', 'entrada-b': 'porta do bloco B', 'entrada-principal': 'ENTRADA PRINCIPAL', corrimao: 'corrimão', 'saida-a': 'entrada do bloco A',
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

// Coordenadas (GeoJSON): numeradas na ordem do arquivo; as que apontam um ponto do piso aparecem no desenho.
let gps = [];
try {
  gps = JSON.parse(readFileSync(gpsInput, 'utf8')).features.filter((f) => f.geometry?.type === 'Point');
} catch {
  // Sem GeoJSON: so o piso.
}
const pins = gps.map((f, i) => {
  const at = pos.get(f.properties.ponto);
  if (!at) return '';
  const n = i + 1;
  return `<circle cx="${at.x}" cy="${at.y}" r="13" fill="none" stroke="#1e8449" stroke-width="3"/>`
    + `<circle cx="${at.x - 16}" cy="${at.y - 16}" r="10" fill="#1e8449"/>`
    + `<text x="${at.x - 16}" y="${at.y - 12}" class="pin" text-anchor="middle">${n}</text>`;
});
const ROW = 30;
const tableTop = minY + height + 20;
const header = ['nº', 'nome', 'ponto do piso', 'latitude', 'longitude', 'sugere origem'];
const columns = [0, 50, 690, 900, 1080, 1260];
const rows = gps.map((f, i) => {
  const [lon, lat] = f.geometry.coordinates;
  const props = f.properties;
  const name = props.nomeMyMaps ?? props.nome ?? '';
  const sugere = props.sugerir ? `sim (raio ${props.raio ?? '?'} m)` : props.tipo === 'campus' ? `campus (raio ${props.raio} m)` : '';
  const ponto = props.ponto ?? (props.tipo === 'referencia' || props.tipo === 'campus' ? 'referência (OSM)' : '—');
  return [String(i + 1), name, ponto, lat.toFixed(7), lon.toFixed(7), sugere];
});
const cell = (values, y, cls) => values.map((v, c) => `<text x="${minX + 20 + columns[c]}" y="${y}" class="${cls}">${esc(v)}</text>`).join('');
const table = gps.length === 0 ? '' : [
  `<text x="${minX + 20}" y="${tableTop + 10}" class="tt">Coordenadas (WGS 84). Verde no desenho: ponto do piso com coordenada. Fontes: Google My Maps e OpenStreetMap (ODbL).</text>`,
  cell(header, tableTop + ROW + 6, 'th'),
  ...rows.map((r, i) => cell(r, tableTop + ROW * (i + 2) + 6, 'td')),
].join('\n');
const totalHeight = height + (gps.length ? ROW * (gps.length + 3) + 30 : 0);
const totalWidth = Math.max(width, gps.length ? 1550 : 0);

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${minX} ${minY} ${totalWidth} ${totalHeight}" width="100%" preserveAspectRatio="xMidYMin meet">
<style>text{font-family:Segoe UI,Arial,sans-serif}.place{font-size:13px;font-weight:600;fill:#111}.id{font-size:9px;fill:#888}
.pin{font-size:11px;font-weight:700;fill:#fff}.th{font-size:18px;font-weight:700;fill:#111}.td{font-size:18px;fill:#222}.tt{font-size:20px;font-weight:700}
.esc{font-size:11px;fill:#c0392b}.ref{font-size:11px;fill:#1f5fbf;font-style:italic}.t{font-size:15px;font-weight:700}</style>
<rect x="${minX}" y="${minY}" width="${totalWidth}" height="${totalHeight}" fill="#fff"/>
<text x="${minX + 20}" y="${minY + 30}" class="t">${esc(raw.nome ?? input)}: piso tátil (azul), escadas (vermelho), cruzamentos (pretos), lugares (laranja). Sem escala.</text>
${lines.join('\n')}
${nodes.join('\n')}
${pins.join('\n')}
${table}
</svg>
`;
writeFileSync(output, svg);
console.log(`${output}: ${pos.size} pontos, ${raw.trechos.length} trechos, ${gps.length} coordenadas`);
