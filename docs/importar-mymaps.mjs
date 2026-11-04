// Importa os pontos exportados do Google My Maps (.kmz ou .kml) para o GeoJSON de pontos de GPS do app.
//   node docs/importar-mymaps.mjs "Mapa-guia SonarUna.kmz" [data/unaerp-pontos.geojson]
// Cada marcador vira uma feicao identificada pelo nome que tem no My Maps (propriedade "nomeMyMaps"). Numa
// reimportacao, a feicao de mesmo nome so tem coordenadas e descricao atualizadas: a ligacao com o mapa do piso
// ("ponto", "raio", "sugerir"), feita a mao no GeoJSON, e mantida. Feicoes de outras fontes (OpenStreetMap) nao
// sao tocadas. Sem dependencias: le o zip do .kmz com o zlib do Node.
import { readFileSync, writeFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';

const [input, output = 'data/unaerp-pontos.geojson'] = process.argv.slice(2);
if (!input) {
  console.error('Uso: node docs/importar-mymaps.mjs <arquivo.kmz|arquivo.kml> [data/unaerp-pontos.geojson]');
  process.exit(1);
}

/** O primeiro .kml dentro de um .kmz (zip), lido pelo diretorio central. */
function kmlFromKmz(buffer) {
  const end = buffer.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (end < 0) throw new Error('Arquivo .kmz inválido (não é um zip)');
  let at = buffer.readUInt32LE(end + 16);
  for (let i = 0; i < buffer.readUInt16LE(end + 10); i++) {
    const method = buffer.readUInt16LE(at + 10);
    const size = buffer.readUInt32LE(at + 20);
    const nameLength = buffer.readUInt16LE(at + 28);
    const extra = buffer.readUInt16LE(at + 30);
    const comment = buffer.readUInt16LE(at + 32);
    const local = buffer.readUInt32LE(at + 42);
    const name = buffer.toString('utf8', at + 46, at + 46 + nameLength);
    if (name.toLowerCase().endsWith('.kml')) {
      const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
      const data = buffer.subarray(start, start + size);
      return (method === 8 ? inflateRawSync(data) : data).toString('utf8');
    }
    at += 46 + nameLength + extra + comment;
  }
  throw new Error('Nenhum .kml dentro do .kmz');
}

const decode = (text) => text
  .replace(/^<!\[CDATA\[([\s\S]*)\]\]>$/, '$1')
  .replace(/<br\s*\/?>/gi, ' ')
  .replace(/<[^>]+>/g, '')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&')
  .replace(/\s+/g, ' ')
  .trim();
const tag = (xml, name) => xml.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))?.[1];

/** Marcadores (Placemark com Point) do KML: { nome, descricao, lon, lat }. Linhas e areas sao ignoradas. */
function placemarks(kml) {
  const result = [];
  for (const [, body] of kml.matchAll(/<Placemark>([\s\S]*?)<\/Placemark>/g)) {
    const point = tag(body, 'Point');
    if (!point) continue;
    const [lon, lat] = tag(point, 'coordinates').trim().split(',').map(Number);
    result.push({ nome: decode(tag(body, 'name') ?? ''), descricao: decode(tag(body, 'description') ?? ''), lon, lat });
  }
  return result;
}

const slug = (text) => text.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

const raw = readFileSync(input);
const kml = input.toLowerCase().endsWith('.kmz') ? kmlFromKmz(raw) : raw.toString('utf8');
const marks = placemarks(kml);
const geojson = JSON.parse(readFileSync(output, 'utf8'));
const byName = new Map(geojson.features.filter((f) => f.properties?.nomeMyMaps).map((f) => [f.properties.nomeMyMaps, f]));
const seen = new Set();
let added = 0;
let updated = 0;

for (const mark of marks) {
  if (!mark.nome || !Number.isFinite(mark.lat) || !Number.isFinite(mark.lon)) continue;
  seen.add(mark.nome);
  const geometry = { type: 'Point', coordinates: [mark.lon, mark.lat] };
  const existing = byName.get(mark.nome);
  if (existing) {
    existing.geometry = geometry;
    existing.properties.descricao = mark.descricao || undefined;
    updated++;
    continue;
  }
  geojson.features.push({
    type: 'Feature',
    id: `mymaps-${slug(mark.nome)}`,
    geometry,
    properties: { nomeMyMaps: mark.nome, descricao: mark.descricao || undefined, fonte: 'Google My Maps', ponto: null },
  });
  added++;
}

writeFileSync(output, `${JSON.stringify(geojson, null, 2)}\n`);
console.log(`${input}: ${marks.length} marcadores; ${updated} atualizados, ${added} novos em ${output}.`);
const unlinked = geojson.features.filter((f) => f.properties?.nomeMyMaps && !f.properties.ponto).map((f) => f.properties.nomeMyMaps);
if (unlinked.length > 0) console.log(`Sem ligação com o mapa do piso (preencha "ponto" se for um lugar de partida): ${unlinked.join('; ')}`);
const missing = [...byName.keys()].filter((name) => !seen.has(name));
if (missing.length > 0) console.log(`Não estão mais no My Maps (renomeados ou apagados?): ${missing.join('; ')}`);
