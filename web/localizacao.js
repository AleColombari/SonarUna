// Pontos de GPS (data/*-pontos.geojson) e sugestao de onde a pessoa esta. Nao depende do navegador.
// O GPS so sugere: a pessoa sempre confirma por voz, e dentro dos predios ele nao serve para achar a sala.

/**
 * Acima disso (metros) a leitura nao distingue uma entrada da outra; o app nao sugere nada. As entradas mais
 * proximas (principal e bloco B) ficam a uns 26 m: com raio de 15 m e erro de ate 10 m, nunca se confundem.
 */
export const PRECISAO_MAXIMA = 10;

/** Ate isso (metros) a leitura ainda vale, mas so quando uma unica entrada cabe nela. */
export const PRECISAO_FOLGADA = 30;

const RAIO_TERRA = 6371000;
const rad = (graus) => (graus * Math.PI) / 180;

/** Distancia em metros entre duas posicoes { lat, lon } (formula de haversine). */
export function distanciaMetros(a, b) {
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * RAIO_TERRA * Math.asin(Math.sqrt(h));
}

/**
 * Valida o GeoJSON (RFC 7946: coordenadas em [longitude, latitude]) e liga cada feicao ao ponto do mapa.
 * So entram nas sugestoes as feicoes com "ponto", coordenadas e "sugerir": true (lugares de partida, como as
 * entradas). As outras ligadas ("sugerir": false, como cruzamentos) so tem o ponto conferido; sem coordenadas
 * (ainda nao medidas) ficam pendentes.
 * Devolve { campus, pontos: [{ id, lat, lon, raio, place }], pendentes: [ids sem coordenadas] }.
 */
export function loadGpsPoints(geojson, map) {
  if (geojson?.type !== 'FeatureCollection' || !Array.isArray(geojson.features)) {
    throw new Error('O arquivo de pontos precisa ser um FeatureCollection do GeoJSON');
  }
  let campus = null;
  const pontos = [];
  const pendentes = [];
  for (const feature of geojson.features) {
    const props = feature.properties ?? {};
    const name = feature.id ?? props.nome ?? '?';
    if (feature.geometry === null) {
      if (props.ponto) pendentes.push(props.ponto);
      continue;
    }
    if (feature.geometry?.type !== 'Point') throw new Error(`Feição ${name}: use geometria Point`);
    const [lon, lat] = feature.geometry.coordinates;
    if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) throw new Error(`Feição ${name}: coordenadas inválidas`);
    if (props.tipo === 'campus') {
      campus = { lat, lon, raio: props.raio ?? 400, nome: props.nome };
      continue;
    }
    if (!props.ponto) continue;
    const place = map.points.get(props.ponto);
    if (!place) throw new Error(`Feição ${name} aponta para um ponto que não existe no mapa: ${props.ponto}`);
    if (props.sugerir !== true) continue;
    if (!(props.raio > 0)) throw new Error(`Feição ${name} precisa de raio maior que zero`);
    pontos.push({ id: props.ponto, lat, lon, raio: props.raio, place });
  }
  return { campus, pontos, pendentes };
}

/**
 * A partir de uma leitura { lat, lon, precisao } (precisao em metros), devolve:
 * { kind: 'sugestao', place, distancia } | { kind: 'imprecisa' } | { kind: 'fora-do-campus' } | { kind: 'nenhuma' }.
 * Um ponto entra quando a distancia cabe no seu raio mais a margem de erro da leitura; vence o mais perto.
 */
export function suggestOrigin(gps, posicao) {
  if (!posicao || !(posicao.precisao <= PRECISAO_FOLGADA)) return { kind: 'imprecisa' };
  if (gps.campus && distanciaMetros(gps.campus, posicao) > gps.campus.raio + posicao.precisao) {
    return { kind: 'fora-do-campus' };
  }
  const candidatos = gps.pontos
    .map((ponto) => ({ place: ponto.place, distancia: distanciaMetros(ponto, posicao), raio: ponto.raio }))
    .filter((c) => c.distancia <= c.raio + posicao.precisao);
  // Leitura boa distingue ate as entradas mais proximas: vence a mais perto. Uma pior (logo ao sair do predio)
  // ainda vale quando so uma entrada cabe nela, como a do bloco A, longe das outras; quando cabem duas (principal
  // e bloco B), nao da para saber qual.
  if (posicao.precisao > PRECISAO_MAXIMA && candidatos.length > 1) return { kind: 'imprecisa' };
  const best = candidatos.reduce((a, b) => (!a || b.distancia < a.distancia ? b : a), null);
  return best ? { kind: 'sugestao', place: best.place, distancia: best.distancia } : { kind: 'nenhuma' };
}
