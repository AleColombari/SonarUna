// Pontos de GPS (data/*-pontos.geojson) e sugestao de onde a pessoa esta. Nao depende do navegador.
// O GPS so sugere: a pessoa sempre confirma por voz, e dentro dos predios ele nao serve para achar a sala.

/** Acima disso (metros) a leitura nao distingue uma entrada da outra; o app nao sugere nada. */
export const PRECISAO_MAXIMA = 60;

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
 * Feicoes sem coordenadas (ainda nao medidas) ou so de referencia ficam de fora das sugestoes.
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
  if (!posicao || !(posicao.precisao <= PRECISAO_MAXIMA)) return { kind: 'imprecisa' };
  if (gps.campus && distanciaMetros(gps.campus, posicao) > gps.campus.raio + posicao.precisao) {
    return { kind: 'fora-do-campus' };
  }
  let best = null;
  for (const ponto of gps.pontos) {
    const distancia = distanciaMetros(ponto, posicao);
    if (distancia <= ponto.raio + posicao.precisao && (!best || distancia < best.distancia)) {
      best = { place: ponto.place, distancia };
    }
  }
  return best ? { kind: 'sugestao', ...best } : { kind: 'nenhuma' };
}
