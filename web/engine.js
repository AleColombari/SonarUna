// Motor do SonarUna: reconhecimento de lugares falados, rota e instrucoes.
// Nao depende do navegador, entao roda tambem no Node para os testes.

// Nome das letras dos blocos, para a voz ler "5B" como "5 bê": algumas vozes leem "5B" como "5 bilhoes".
const LETTER_SOUNDS = { A: 'á', B: 'bê', C: 'cê', D: 'dê', E: 'é', F: 'éfe', G: 'gê', H: 'agá', I: 'i', J: 'jota', K: 'cá', L: 'éle', M: 'ême', N: 'êne', O: 'ó', P: 'pê', Q: 'quê', R: 'érre', S: 'ésse' };

/**
 * Texto para a voz: sala "5B" vira "5 bê"; siglas ditas como palavra ("UNAERP", "LIAPE") viram "Unaerp", porque em
 * maiusculas algumas vozes soletram. O texto na tela continua como esta.
 */
export function forSpeech(text) {
  return String(text)
    .replace(/\b(\d+)([A-S])\b/g, (_, number, letter) => `${number} ${LETTER_SOUNDS[letter]}`)
    .replace(/\b([A-Z])([A-Z]{2,})\b/g, (_, first, rest) => first + rest.toLowerCase());
}

/** Minusculas, sem acentos, sem pontuacao; letras e numeros colados sao separados ("a15" -> "a 15"). */
export function normalize(text) {
  return String(text ?? '')
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .replace(/(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

const UNIT = 1;
const TENS = 2;
const HUNDREDS = 3;

const NUMBER_WORDS = new Map();
['zero', 'um', 'dois', 'tres', 'quatro', 'cinco', 'seis', 'sete', 'oito', 'nove'].forEach((word, value) =>
  NUMBER_WORDS.set(word, { value, rank: UNIT }));
NUMBER_WORDS.set('uma', { value: 1, rank: UNIT });
NUMBER_WORDS.set('duas', { value: 2, rank: UNIT });
['dez', 'onze', 'doze', 'treze', 'catorze', 'quinze', 'dezesseis', 'dezessete', 'dezoito', 'dezenove'].forEach(
  (word, index) => NUMBER_WORDS.set(word, { value: 10 + index, rank: TENS, teen: true }));
NUMBER_WORDS.set('quatorze', { value: 14, rank: TENS, teen: true });
['vinte', 'trinta', 'quarenta', 'cinquenta', 'sessenta', 'setenta', 'oitenta', 'noventa'].forEach((word, index) =>
  NUMBER_WORDS.set(word, { value: 20 + 10 * index, rank: TENS }));
NUMBER_WORDS.set('cem', { value: 100, rank: HUNDREDS });
NUMBER_WORDS.set('cento', { value: 100, rank: HUNDREDS });
['duzentos', 'trezentos', 'quatrocentos', 'quinhentos', 'seiscentos', 'setecentos', 'oitocentos', 'novecentos'].forEach(
  (word, index) => NUMBER_WORDS.set(word, { value: 200 + 100 * index, rank: HUNDREDS }));

/** Devolve [valor, quantidade de palavras usadas] ou null quando nenhum numero comeca aqui. */
function parseNumber(tokens, start) {
  const first = tokens[start];
  if (/^[0-9]+$/.test(first)) return first.length <= 3 ? [Number(first), 1] : null;
  let index = start;
  let total = 0;
  let previousRank = Infinity;
  let previousTeen = false;
  let matched = false;
  while (index < tokens.length) {
    const joined = matched && tokens[index] === 'e';
    const wordIndex = joined ? index + 1 : index;
    const entry = NUMBER_WORDS.get(tokens[wordIndex]);
    if (!entry) break;
    if (entry.rank >= previousRank || previousTeen || (matched && entry.value === 0)) break;
    total += entry.value;
    previousRank = entry.rank;
    previousTeen = Boolean(entry.teen);
    matched = true;
    index = wordIndex + 1;
  }
  if (!matched) return null;
  // Digitos falados um a um: "um cinco" e 15.
  if (index === start + 1 && previousRank === UNIT) {
    let digits = String(total);
    while (digits.length < 3) {
      const next = NUMBER_WORDS.get(tokens[index]);
      if (!next || next.rank !== UNIT) break;
      digits += next.value;
      index++;
    }
    return [Number(digits), index - start];
  }
  return [total, index - start];
}

function toTerms(tokens) {
  const terms = [];
  let index = 0;
  while (index < tokens.length) {
    const parsed = parseNumber(tokens, index);
    if (parsed) {
      terms.push({ num: parsed[0] });
      index += parsed[1];
    } else {
      terms.push({ word: tokens[index] });
      index++;
    }
  }
  return terms;
}

/** Blocos que existem no campus. Para incluir outro (arquitetura, musica), acrescente a letra aqui. */
export const BLOCOS = 'abcdefghijklmnopqrs';

// Como o reconhecedor pode escrever cada letra: sozinha ("b") ou pelo nome ("be" de "bê").
const LETTER_NAMES = new Map([...BLOCOS].map((letter) => [letter, letter]));
for (const [name, letter] of Object.entries({
  be: 'b', ce: 'c', de: 'd', efe: 'f', ge: 'g', aga: 'h', jota: 'j', ka: 'k', ca: 'k', ele: 'l', eme: 'm',
  ene: 'n', pe: 'p', que: 'q', erre: 'r', esse: 's',
})) {
  if (BLOCOS.includes(letter)) LETTER_NAMES.set(name, letter);
}

// Tambem sao palavras comuns ("sala 12 e 15", "o banheiro", "sala de aula"). Antes do numero, so valem como
// bloco no inicio da frase ou logo depois de "sala"/"bloco".
const AMBIGUOUS_LETTERS = new Set(['e', 'o', 'de', 'que', 'ele', 'esse']);

const blockOf = (term) => (term?.word !== undefined ? LETTER_NAMES.get(term.word) ?? null : null);

function letterAfter(terms, keyword) {
  for (let i = 0; i < terms.length - 1; i++) {
    if (terms[i].word === keyword && blockOf(terms[i + 1])) return blockOf(terms[i + 1]);
  }
  return null;
}

function numberAfter(terms, keyword) {
  for (let i = 0; i < terms.length - 1; i++) {
    if (terms[i].word === keyword && terms[i + 1].num !== undefined) return terms[i + 1].num;
  }
  return null;
}

/** Palavras de cortesia no fim da frase, ignoradas para achar a letra que fecha "30 A". */
const TRAILING_COURTESY = ['por favor', 'obrigado', 'obrigada'];

function withoutTrailingCourtesy(tokens) {
  const text = ` ${tokens.join(' ')} `;
  for (const phrase of TRAILING_COURTESY) {
    if (text.endsWith(` ${phrase} `)) return tokens.slice(0, tokens.length - phrase.split(' ').length);
  }
  return tokens;
}

/**
 * "A15", "a quinze", "30A", "trinta a", "bê doze", "é doze", "bloco B sala 3", "sala 20". O bloco vem antes ou
 * depois do numero, como letra ou pelo nome; o canonico e sempre letra + numero ("30A" -> "A30"). So valem as
 * letras de BLOCOS.
 */
export function parseRoomCode(text) {
  const terms = toTerms(withoutTrailingCourtesy(normalize(text).split(' ').filter(Boolean)));
  const make = (block, number) => ({ block, number, canonical: `${block ?? ''}${number}` });

  const blockLetter = letterAfter(terms, 'bloco');
  const roomNumber = numberAfter(terms, 'sala');
  if (blockLetter && roomNumber !== null) return make(blockLetter.toUpperCase(), roomNumber);

  // Letra depois do numero so quando fecha a frase: em "sala 20 a esquerda", o "a" e a crase. Vem antes da letra
  // anterior ao numero, porque em "quero ir para a 10B" o "a" e o artigo e o bloco e o B.
  const last = terms.length - 1;
  if (last >= 1 && blockOf(terms[last]) && terms[last - 1].num !== undefined) {
    return make(blockOf(terms[last]).toUpperCase(), terms[last - 1].num);
  }
  for (let i = 0; i < terms.length - 1; i++) {
    const block = blockOf(terms[i]);
    if (!block || terms[i + 1].num === undefined) continue;
    const previous = terms[i - 1]?.word;
    if (AMBIGUOUS_LETTERS.has(terms[i].word) && i > 0 && previous !== 'sala' && previous !== 'bloco') continue;
    return make(block.toUpperCase(), terms[i + 1].num);
  }
  if (roomNumber !== null) return make(null, roomNumber);
  return null;
}

/** Artigo do lugar ("a sala A12", "o banheiro"): o do mapa; sem ele, "o" para cruzamentos e patamares, senao "a". */
const article = (point) => point.artigo ?? (['cruzamento', 'patamar'].includes(point.tipo) ? 'o' : 'a');
export const paraLugar = (point) => `para ${article(point)} ${point.nome}`;
export const ateLugar = (point) => `até ${article(point)} ${point.nome}`;
export const emLugar = (point) => `n${article(point)} ${point.nome}`;
export const deLugar = (point) => `d${article(point)} ${point.nome}`;

const containsPhrase = (normalizedText, phrase) => ` ${normalizedText} `.includes(` ${phrase} `);

// ---------------------------------------------------------------------------------------------
// Mapa

/**
 * Valida o JSON do mapa e monta a lista de vizinhos. Falha com mensagem clara se algo estiver errado.
 * Com "guia": "piso" os trechos nao precisam de metros: a rota conta cruzamentos do piso tatil, e o
 * "peso" opcional de um trecho (padrao 1) so serve para preferir um caminho a outro.
 */
export function loadMap(raw) {
  const guia = raw.guia === 'piso' ? 'piso' : 'passos';
  const points = new Map();
  for (const point of raw.pontos ?? []) {
    if (points.has(point.id)) throw new Error(`Ponto repetido: ${point.id}`);
    points.set(point.id, {
      ...point,
      aliases: (point.aliases ?? []).map(normalize),
      codigo: point.codigo ? point.codigo.toUpperCase() : null,
    });
  }
  const adjacency = new Map([...points.keys()].map((id) => [id, []]));
  for (const edge of raw.trechos ?? []) {
    for (const end of [edge.de, edge.para]) {
      if (!points.has(end)) throw new Error(`Trecho usa ponto inexistente: ${end}`);
    }
    const name = `Trecho ${edge.de} -> ${edge.para}`;
    if (guia === 'passos' && !(edge.metros > 0)) throw new Error(`${name} precisa de metros maiores que zero`);
    if (edge.metros !== undefined && !(edge.metros > 0)) throw new Error(`${name} precisa de metros maiores que zero`);
    if (edge.passos !== undefined && !(edge.passos > 0)) throw new Error(`${name} precisa de passos maiores que zero`);
    if (edge.peso !== undefined && !(edge.peso > 0)) throw new Error(`${name} precisa de peso maior que zero`);
    if (typeof edge.rumo !== 'number') throw new Error(`${name} precisa de rumo`);
    if (edge.escada !== undefined && !['sobe', 'desce', 'indefinida'].includes(edge.escada)) {
      throw new Error(`${name}: escada deve ser "sobe", "desce" (no sentido de "de" para "para") ou "indefinida"`);
    }
    const references = edge.referencias ?? [];
    const common = {
      metros: edge.metros ?? null,
      passos: edge.passos,
      peso: edge.peso ?? (guia === 'piso' ? 1 : edge.metros),
      referencias: references,
    };
    const back = { sobe: 'desce', desce: 'sobe' }[edge.escada] ?? edge.escada;
    adjacency.get(edge.de).push({ to: edge.para, rumo: mod360(edge.rumo), escada: edge.escada, ...common });
    adjacency.get(edge.para).push({ to: edge.de, rumo: mod360(edge.rumo + 180), escada: back, ...common });
  }
  const entrances = (raw.entradas ?? []).map((entrance) => {
    if (!points.has(entrance.ponto)) throw new Error(`Entrada ${entrance.id} usa ponto inexistente: ${entrance.ponto}`);
    return {
      ...entrance,
      aliases: [normalize(entrance.nome), ...(entrance.aliases ?? []).map(normalize)],
      rumo: mod360(entrance.rumo),
    };
  });
  // "origem": "lugar" pergunta onde a pessoa esta (qualquer sala ou lugar do mapa), em vez de por qual entrada entrou.
  const origem = raw.origem === 'lugar' ? 'lugar' : 'entrada';
  return {
    bloco: raw.bloco,
    nome: raw.nome ?? (raw.bloco ? `bloco ${raw.bloco}` : null),
    exemplo: raw.exemplo ?? 'sala A12',
    exemploOrigem: raw.exemploOrigem ?? null,
    // Mais que isso numa reta vira varias etapas: contar 20 cruzamentos com a bengala nao da.
    cruzamentosPorEtapa: raw.cruzamentosPorEtapa ?? 5,
    origem,
    guia,
    passoMetros: raw.passoMetros ?? 0.7,
    entrances,
    points,
    adjacency,
    aviso: raw.aviso ?? null,
  };
}

const mod360 = (degrees) => ((degrees % 360) + 360) % 360;

export function findEntrance(map, text) {
  const query = normalize(text);
  let best = null;
  for (const entrance of map.entrances) {
    for (const alias of entrance.aliases) {
      if (alias && containsPhrase(query, alias) && (!best || alias.length > best.length)) {
        best = { entrance, length: alias.length };
      }
    }
  }
  return best?.entrance ?? null;
}

/**
 * Resultado: { kind: 'found', place } | { kind: 'ambiguous', places } | { kind: 'several', places } |
 * { kind: 'unknown-room', code } | { kind: 'none' }.
 * 'ambiguous': um numero de sala sem bloco que existe em mais de um bloco (a pessoa precisa escolher).
 * 'several': varios lugares atendem pelo mesmo nome ("banheiro"); quem chama pode escolher o mais perto.
 */
export function findPlace(map, text) {
  const rooms = [...map.points.values()].filter((point) => point.codigo);
  const code = parseRoomCode(text);
  if (code) {
    if (code.block) {
      // O mapa pode escrever o codigo "A30" ou "30A"; os dois viram o mesmo canonico.
      const room = rooms.find((point) => parseRoomCode(point.codigo)?.canonical === code.canonical);
      if (room) return { kind: 'found', place: room };
      // "para a 10" lido como sala A10: o "a" era o artigo. Se so uma sala tem esse numero, e ela.
      if (code.block === 'A') {
        const same = rooms.filter((point) => Number(point.codigo.replace(/\D/g, '')) === code.number);
        if (same.length === 1) return { kind: 'found', place: same[0] };
      }
      return { kind: 'unknown-room', code: code.canonical };
    }
    const matches = rooms.filter((point) => Number(point.codigo.replace(/\D/g, '')) === code.number);
    if (matches.length === 1) return { kind: 'found', place: matches[0] };
    if (matches.length > 1) return { kind: 'ambiguous', places: matches };
    return { kind: 'unknown-room', code: String(code.number) };
  }
  const query = normalize(text);
  // Vence o apelido mais longo; empate entre lugares diferentes ("banheiro feminino") devolve todos.
  let bestLength = 0;
  let best = [];
  for (const point of map.points.values()) {
    if (point.codigo || point.tipo === 'entrada') continue;
    const length = Math.max(0, ...point.aliases.filter((alias) => alias && containsPhrase(query, alias)).map((a) => a.length));
    if (length === 0 || length < bestLength) continue;
    if (length > bestLength) best = [];
    bestLength = length;
    best.push(point);
  }
  if (best.length === 1) return { kind: 'found', place: best[0] };
  if (best.length > 1) return { kind: 'several', places: best };
  return { kind: 'none' };
}

// Frase com origem e destino: "estou na 3A e quero ir para a 10B", "quero ir pra 10B, estou na 3A".
const DESTINATION_MARKERS = [
  'quero ir para', 'quero ir pra', 'quero ir pro', 'quero ir ate', 'quero ir na', 'quero ir no', 'quero ir',
  'preciso ir para', 'preciso ir pra', 'preciso ir pro', 'preciso ir ate', 'preciso ir',
  'vou para', 'vou pra', 'vou pro', 'vou ate', 'ir para', 'ir pra', 'ir pro', 'ir ate',
  'me leva para', 'me leva pra', 'me leve para', 'me leve pra', 'me levar para',
  // Sozinhas, por ultimo: "estou na 3B, quero a 10B", "estou na 3B, pra 10B".
  'quero', 'vou', 'para', 'pra', 'pro', 'ate',
];
const ORIGIN_MARKERS = ['estou', 'to', 'tou', 'estamos', 'aqui'];
// Sobras no fim de um pedaco ("estou na 3A e ..."), que atrapalham ler o codigo da sala.
const LINKING_WORDS = new Set(['e', 'eu', 'ai', 'entao', 'mas', 'agora']);

const trimLinking = (text) => {
  const words = text.split(' ').filter(Boolean);
  while (words.length > 0 && LINKING_WORDS.has(words[words.length - 1])) words.pop();
  while (words.length > 0 && LINKING_WORDS.has(words[0])) words.shift();
  return words.join(' ');
};

const firstMarker = (padded, markers) => {
  let best = null;
  for (const marker of markers) {
    const at = padded.indexOf(` ${marker} `);
    if (at >= 0 && (!best || at < best.at || (at === best.at && marker.length > best.marker.length))) best = { at, marker };
  }
  return best;
};

/** "3A e 10B", "estou na 3B, 10B": duas salas com bloco, sem palavra de destino; a primeira e onde a pessoa esta. */
function twoRooms(text) {
  const words = text.split(' ').filter(Boolean);
  for (let i = 1; i < words.length; i++) {
    const origem = trimLinking(words.slice(0, i).join(' '));
    const destino = trimLinking(words.slice(i).join(' '));
    const from = parseRoomCode(origem);
    const to = parseRoomCode(destino);
    if (from?.block && to?.block && from.canonical !== to.canonical) return { origem, destino };
  }
  return null;
}

/**
 * Separa a parte que diz onde a pessoa esta da parte que diz aonde ela quer ir. Devolve { origem, destino }, cada um
 * texto normalizado ou null; sem marca de destino ("quero ir para"...), tudo e origem.
 */
export function splitTrip(text) {
  const padded = ` ${normalize(text)} `;
  const destination = firstMarker(padded, DESTINATION_MARKERS);
  if (!destination) return twoRooms(padded.trim()) ?? { origem: trimLinking(padded.trim()) || null, destino: null };
  const before = trimLinking(padded.slice(0, destination.at));
  const after = ` ${padded.slice(destination.at + destination.marker.length + 1).trim()} `;
  const origin = firstMarker(after, ORIGIN_MARKERS);
  if (origin) {
    return { origem: trimLinking(after.slice(origin.at)) || null, destino: trimLinking(after.slice(0, origin.at)) || null };
  }
  return { origem: before || null, destino: trimLinking(after) || null };
}

/** Peso total da rota (Infinity sem caminho): serve para escolher o lugar mais perto entre varios. */
export function routeWeight(map, fromId, toId) {
  const segments = planRoute(map, fromId, toId);
  if (!segments) return Infinity;
  let total = 0;
  let at = fromId;
  for (const segment of segments) {
    total += map.adjacency.get(at).find((edge) => edge.to === segment.para).peso;
    at = segment.para;
  }
  return total;
}

/**
 * Direcao de quem sai de um lugar pelo seu unico trecho (porta de sala ou ponta do piso), ou null. Um ponto no
 * meio do piso (uma porta de bloco) diz para onde a pessoa costuma seguir com "rumoPartida".
 */
export function exitHeading(map, pointId) {
  const point = map.points.get(pointId);
  if (typeof point?.rumoPartida === 'number') return mod360(point.rumoPartida);
  const edges = map.adjacency.get(pointId) ?? [];
  return edges.length === 1 ? edges[0].rumo : null;
}

// ---------------------------------------------------------------------------------------------
// Rota

/** Caminho mais curto pelo peso dos trechos: metros, ou 1 por trecho no modo piso (Dijkstra). Devolve a lista de trechos, [] se origem = destino, ou null se nao ha caminho. */
export function planRoute(map, fromId, toId) {
  if (!map.points.has(fromId) || !map.points.has(toId)) return null;
  const distance = new Map([[fromId, 0]]);
  const previous = new Map();
  const pending = new Set([fromId]);
  const done = new Set();
  while (pending.size > 0) {
    let current = null;
    for (const id of pending) {
      if (current === null || distance.get(id) < distance.get(current)) current = id;
    }
    pending.delete(current);
    if (current === toId) break;
    done.add(current);
    for (const edge of map.adjacency.get(current)) {
      if (done.has(edge.to)) continue;
      const candidate = distance.get(current) + edge.peso;
      if (!distance.has(edge.to) || candidate < distance.get(edge.to)) {
        distance.set(edge.to, candidate);
        previous.set(edge.to, { from: current, edge });
        pending.add(edge.to);
      }
    }
  }
  if (!distance.has(toId)) return null;
  const segments = [];
  for (let at = toId; at !== fromId; at = previous.get(at).from) {
    const { from, edge } = previous.get(at);
    segments.unshift({
      de: from, para: at, metros: edge.metros, passos: edge.passos, rumo: edge.rumo, escada: edge.escada,
      referencias: edge.referencias,
    });
  }
  return segments;
}

/** Diferenca de rumo entre -180 e 180: positivo e curva para a direita. */
const turnAngle = (from, to) => mod360(to - from + 180) - 180;

function turnPhrase(angle) {
  const size = Math.abs(angle);
  if (size < 30) return 'Siga em frente';
  if (size > 150) return 'Dê meia-volta';
  return angle > 0 ? 'Vire à direita' : 'Vire à esquerda';
}

/** Passos de um trecho: o valor medido no mapa ou, se faltar, metros / tamanho do passo. */
const stepsOf = (map, segment) => segment.passos ?? segment.metros / map.passoMetros;

/** Ate 10 passos conta exato; depois arredonda de 5 em 5, para nao fingir uma precisao que o passo nao tem. */
export const roundSteps = (steps) => (steps <= 10 ? Math.max(1, Math.round(steps)) : Math.round(steps / 5) * 5);

const plural = (count, one, many) => `${count} ${count === 1 ? one : many}`;

/** "cerca de 15 passos" quando a conta quebrou e foi arredondada; "10 passos" quando fecha exata. */
export function stepsPhrase(rawSteps) {
  const steps = roundSteps(rawSteps);
  const approximate = Math.abs(rawSteps - steps) > 0.05;
  return `${approximate ? 'cerca de ' : ''}${plural(steps, 'passo', 'passos')}`;
}

/**
 * Uma instrucao por mudanca de direcao. Trechos em linha reta seguidos viram uma unica instrucao.
 * Cada item: { texto, metros, passos (arredondado), bruto (sem arredondar) }. A fala usa so passos, que a pessoa sente andando; os metros
 * ficam no mapa como fonte (passos medidos no trecho, quando existirem, valem mais que metros / passoMetros).
 */
export function buildInstructions(map, entrance, segments) {
  if (map.guia === 'piso') return buildFloorInstructions(map, entrance, segments);
  const groups = [];
  let heading = entrance.rumo;
  for (const segment of segments) {
    const angle = turnAngle(heading, segment.rumo);
    const last = groups[groups.length - 1];
    if (last && Math.abs(angle) < 30) {
      last.metros += segment.metros;
      last.passos += stepsOf(map, segment);
      last.referencias.push(...segment.referencias);
      last.destino = segment.para;
      last.rumo = segment.rumo;
    } else {
      groups.push({
        virar: turnPhrase(angle),
        metros: segment.metros,
        passos: stepsOf(map, segment),
        referencias: [...segment.referencias],
        destino: segment.para,
        rumo: segment.rumo,
      });
    }
    heading = segment.rumo;
  }
  return groups.map((group, index) => {
    const metros = Math.round(group.metros);
    const passos = roundSteps(group.passos);
    const referencias = [...new Set(group.referencias)];
    const isLast = index === groups.length - 1;
    const parts = [
      `${group.virar} e ande ${stepsPhrase(group.passos)}.`,
    ];
    if (referencias.length > 0) parts.push(`Referência: ${referencias.join(', ')}.`);
    if (isLast) {
      parts.push(`Você chegará ${emLugar(map.points.get(group.destino))}. Quando chegar, diga pronto.`);
    } else {
      parts.push('Quando terminar, diga pronto.');
    }
    return { texto: parts.join(' '), metros, passos, bruto: group.passos, ponto: group.destino, rumo: group.rumo };
  });
}

const STRAIGHT = 'Siga em frente';
const turnVerb = (virar) => virar.charAt(0).toLowerCase() + virar.slice(1);

/**
 * Guia pelo piso tatil, sem medidas e sem salas como referencia: a pessoa conta os cruzamentos que sente com a
 * bengala ("Daqui a 3 cruzamentos, vire à esquerda"). A virada e avisada na etapa que leva ate ela; a sala so
 * aparece quando e o destino. Uma reta longa vira etapas de ate `cruzamentosPorEtapa` cruzamentos. Todo ponto no
 * meio de uma reta conta como cruzamento (menos curvas, patamares, portas e marcos).
 * Cada item: { texto, cruzamentos, bruto: null, ponto, rumo } (ponto e rumo: onde a etapa termina e para que lado
 * a pessoa fica virada, ja depois da virada avisada).
 */
function buildFloorInstructions(map, entrance, segments) {
  const groups = [];
  // Sem direcao conhecida (partida de um ponto com varios trechos), a primeira etapa nao fala em virar.
  let heading = entrance.rumo ?? segments[0]?.rumo;
  for (const segment of segments) {
    const angle = turnAngle(heading, segment.rumo);
    const last = groups[groups.length - 1];
    if (last && !last.escada && !segment.escada && Math.abs(angle) < 30) {
      last.trechos.push(segment);
    } else {
      groups.push({ virar: turnPhrase(angle), escada: segment.escada, trechos: [segment] });
    }
    heading = segment.rumo;
  }
  const steps = [];
  groups.forEach((group, index) => {
    const isLast = index === groups.length - 1;
    const previous = groups[index - 1];
    const next = groups[index + 1];
    if (group.escada) {
      const action = { sobe: 'Suba a escada', desce: 'Desça a escada' }[group.escada];
      const turn = group.virar === STRAIGHT ? '' : `${group.virar}. `;
      steps.push(floorStep(map, group.trechos, [action ? `${turn}${action}.` : `${turn}Atenção, escada. Use o corrimão.`], isLast));
      return;
    }
    // A virada do comeco ja foi dita no fim da etapa anterior, a nao ser no inicio da rota ou depois de uma escada.
    const startTurn = (!previous || previous.escada) && group.virar !== STRAIGHT ? group.virar : null;
    // A virada do fim (no cruzamento onde a proxima reta comeca) e dita nesta etapa.
    const endTurn = next && !next.escada ? next.virar : null;
    const chunks = splitLongStraight(map, group.trechos, isLast || Boolean(next?.escada));
    chunks.forEach((chunk, i) => {
      const lastChunk = i === chunks.length - 1;
      const turnNow = i === 0 ? startTurn : null;
      const go = turnNow ? `${turnNow} e siga o piso tátil` : `${i > 0 ? 'Continue' : 'Siga'} em frente pelo piso tátil`;
      const end = map.points.get(chunk.trechos[chunk.trechos.length - 1].para);
      const passing = chunk.passados > 0 ? `, passando por ${plural(chunk.passados, 'cruzamento', 'cruzamentos')},` : '';
      const count = chunk.passados + 1;
      let text;
      if (chunk.pausa) {
        text = `${go} por ${plural(count, 'cruzamento', 'cruzamentos')}.`;
      } else if (isLast) {
        text = `${go}${passing} até ${end.tipo === 'porta' ? 'a porta' : 'o fim'}.`;
      } else if (next.escada) {
        text = `${go}${passing} até a escada.`;
      } else if (end.tipo === 'curva') {
        text = `${go}${passing} até a curva do piso e ${turnVerb(endTurn)}.`;
      } else {
        const when = count === 1 ? 'No próximo cruzamento' : `Daqui a ${count} cruzamentos`;
        text = `${turnNow ? `${go}. ` : ''}${when}, ${turnVerb(endTurn)}.`;
      }
      const faces = lastChunk && endTurn ? next.trechos[0].rumo : undefined;
      steps.push(floorStep(map, chunk.trechos, [text], isLast && !chunk.pausa, chunk.passados, faces));
    });
  });
  return steps;
}

// Portas e marcos (corrimao) ficam no caminho, mas nao sao cruzamentos que a bengala sente no piso.
const isCrossing = (map, id) => !['curva', 'patamar', 'porta', 'marco'].includes(map.points.get(id).tipo);

/**
 * Corta uma reta em pedacos de ate `cruzamentosPorEtapa` cruzamentos a contar. Os pedacos do meio terminam num
 * cruzamento (`pausa`); o ultimo mantem o fim original da reta (curva, escada, destino ou cruzamento de virar).
 * Quando a reta termina num cruzamento de virar, ele tambem conta, pois a pessoa precisa conta-lo para parar.
 */
function splitLongStraight(map, trechos, endsWithoutTurn) {
  const limit = map.cruzamentosPorEtapa;
  const chunks = [];
  let current = [];
  let counted = 0;
  trechos.forEach((segment, i) => {
    current.push(segment);
    const isEnd = i === trechos.length - 1;
    if (isEnd || !isCrossing(map, segment.para)) return;
    counted++;
    // Restam cruzamentos a contar depois deste? So entao vale pausar aqui.
    const remaining = trechos.slice(i + 1, -1).filter((s) => isCrossing(map, s.para)).length
      + (endsWithoutTurn ? 0 : 1);
    if (counted === limit && remaining > 0) {
      chunks.push({ trechos: current, passados: counted - 1, pausa: true });
      current = [];
      counted = 0;
    }
  });
  chunks.push({ trechos: current, passados: counted, pausa: false });
  return chunks;
}

function floorStep(map, trechos, parts, arrives, passados = 0, faces = undefined) {
  const referencias = [...new Set(trechos.flatMap((segment) => segment.referencias))];
  if (referencias.length > 0) parts.push(`Referência: ${referencias.join(', ')}.`);
  const end = map.points.get(trechos[trechos.length - 1].para);
  parts.push(arrives ? `Você chegará ${emLugar(end)}. Quando chegar, diga pronto.` : 'Quando terminar, diga pronto.');
  // ponto/rumo: onde a etapa termina e para que lado a pessoa esta virada, para trocar de destino no meio da rota.
  return { texto: parts.join(' '), cruzamentos: passados, bruto: null, ponto: end.id, rumo: faces ?? trechos[trechos.length - 1].rumo };
}

// ---------------------------------------------------------------------------------------------
// Comandos de voz

// Palavras e expressoes soltas em qualquer parte da frase: "acho que sim", "sim sim", "isso ai, pode ir".
// A mesma lista esta em app/.../guia/Answers.kt; as duas sao geradas juntas e os casos compartilhados conferem.
const POSITIVE = [
  'sim', 'isso', 'correto', 'correta', 'certo', 'certa', 'confirmo', 'confirmado', 'confirma', 'exato', 'exatamente',
  'claro', 'ok', 'okay', 'beleza', 'positivo', 'afirmativo', 'perfeito', 'uhum', 'aham', 'certeza', 'pode', 'quero',
  'bora', 'vamos', 'vamo', 'ta bom', 'esta bom', 'tudo bem', 'e esse', 'e essa', 'e ela', 'e ele', 'esse mesmo',
  'essa mesma', 'yes', 'isso mesmo', 'isso ai', 'e isso', 'com certeza', 'sem duvida', 'certissimo', 'certinho',
  'confere', 'concordo', 'de acordo', 'fechado', 'fechou', 'combinado', 'show', 'otimo', 'otima', 'blz', 'firmeza',
  'demorou', 'tranquilo', 'suave', 'ta certo', 'esta certo', 'ta ok', 'acertou', 'sim senhor', 'sim senhora',
  'aham sim', 'hum hum', 'unhum', 'pode crer', 'manda ver', 'manda bala', 'positivo e operante', 'yep', 'yeah',
];
const NEGATIVE = [
  'nao', 'errado', 'errada', 'incorreto', 'incorreta', 'negativo', 'nunca', 'corrigir', 'corrige', 'trocar', 'troca',
  'mudar', 'muda', 'nem', 'enganei', 'jamais', 'de jeito nenhum', 'nada disso', 'que nada', 'negado', 'errei',
  'errou', 'nem pensar', 'longe disso', 'outro lugar', 'outro local', 'outra sala', 'nope', 'na na', 'nananinanao',
];
// Duvida declarada: nao e resposta, mesmo com "nao" no meio. ("sem duvida" e sim: por isso nao ha "duvida" sozinha.)
const UNSURE = ['nao sei', 'sei la', 'talvez', 'tenho duvida', 'em duvida', 'na duvida', 'nao tenho certeza', 'acho que nao sei'];

const containsAny = (normalizedText, phrases) => phrases.some((phrase) => containsPhrase(normalizedText, phrase));

/**
 * Le sim ou nao em qualquer parte da frase. A negacao vence ("nao esta correto", "acho que nao, isso nao"),
 * "sim" e "nao" juntos sao contradicao, e "nao esta errado" (dupla negacao) fica sem resposta.
 * Devolve 'yes', 'no' ou null.
 */
export function classifyYesNo(text) {
  const normalized = normalize(text);
  if (!normalized || containsAny(normalized, UNSURE)) return null;
  const saysYes = containsPhrase(normalized, 'sim');
  const saysNo = containsPhrase(normalized, 'nao');
  if (saysYes && saysNo) return null;
  if (saysNo && containsAny(normalized, ['errado', 'errada', 'incorreto', 'incorreta'])) return null;
  if (containsAny(normalized, NEGATIVE)) return 'no';
  if (containsAny(normalized, POSITIVE)) return 'yes';
  return null;
}

const COMMANDS = [
  // "preciso de ajuda" chama alguem; "ajuda" sozinho so explica o app. Por isso `assist` vem antes de `help`.
  ['assist', [
    'preciso de ajuda', 'preciso de socorro', 'preciso de um atendente', 'preciso de atendente', 'quero ajuda',
    'chamar ajuda', 'pedir ajuda', 'pedir socorro', 'me ajuda', 'me ajude', 'socorro', 'estou perdido',
    'estou perdida', 'chamar atendente', 'chamar seguranca', 'falar com atendente', 'falar com um atendente',
    'falar com o atendente', 'falar com seguranca', 'falar com a seguranca', 'falar com um seguranca',
  ]],
  // "sair" nao para a orientacao: "quero sair" e um destino. "para" sozinho tambem nao: e preposicao.
  ['stop', [
    'parar', 'pare', 'cancelar', 'cancela', 'encerrar', 'encerra', 'finalizar', 'finaliza', 'desligar', 'desliga',
    'tchau', 'fechar o app', 'fechar o aplicativo', 'pode parar', 'para tudo',
  ]],
  ['restart', [
    'recomecar', 'reiniciar', 'comecar de novo', 'do comeco', 'desde o comeco',
  ]],
  // Trocar so o destino mantem onde a pessoa esta (ou onde terminou a ultima etapa feita).
  ['destination', [
    'novo destino', 'outro destino', 'nova rota', 'outra rota', 'mudar o destino', 'mudar destino',
    'trocar o destino', 'trocar destino', 'mudar de destino', 'quero ir para outro lugar', 'ir para outro lugar',
  ]],
  ['remaining', [
    'quanto falta', 'falta muito', 'quantas etapas faltam', 'quantas faltam', 'falta quanto', 'falta pouco',
    'ta longe', 'esta longe', 'ainda falta', 'quantas etapas',
  ]],
  // Antes de 'repeat', porque "repete o caminho" tem "repete".
  ['overview', [
    'repasse o caminho', 'repassa o caminho', 'repassar o caminho', 'repete o caminho', 'repita o caminho',
    'caminho todo', 'caminho completo', 'resumo do caminho', 'resumo da rota', 'me fala o caminho',
    'qual e o caminho', 'como e o caminho',
  ]],
  ['where', [
    'onde estou', 'onde eu estou', 'aonde estou', 'aonde eu estou', 'onde eu to', 'onde to', 'onde e que eu estou',
    'em que lugar eu estou', 'em que lugar estou', 'onde estamos',
  ]],
  ['help', [
    'ajuda', 'o que eu posso falar', 'o que eu posso dizer', 'como funciona', 'quais comandos',
  ]],
  ['repeat', [
    'repetir', 'repete', 'repita', 'de novo', 'nao ouvi', 'nao entendi', 'nao escutei', 'nao captei', 'outra vez',
    'mais uma vez', 'hein', 'o que voce disse', 'o que voce falou', 'fala de novo', 'como e que e',
  ]],
  ['back', [
    'voltar', 'volta', 'volte', 'anterior', 'passo anterior', 'etapa anterior', 'a de antes',
  ]],
  // No caminho, um "sim" qualquer ("beleza", "certo", "isso") tambem passa para a proxima etapa (conversation.js).
  ['next', [
    'pronto', 'pronta', 'prontinho', 'proximo', 'proxima', 'proxima etapa', 'proximo passo', 'continuar', 'continua',
    'continue', 'seguir', 'segue', 'avancar', 'avanca', 'cheguei', 'ja cheguei', 'ok', 'feito', 'fiz', 'terminei',
    'acabei', 'consegui', 'ja fui', 'ja andei', 'ja fiz', 'ja passei', 'e agora', 'e depois', 'e dai',
    'qual a proxima', 'qual e a proxima', 'manda a proxima', 'pode mandar', 'pode continuar', 'pode seguir', 'next',
    'foi', 'fui', 'ja', 'andei', 'passei', 'ta feito', 'esta feito', 'cheguei la', 'to la', 'estou la', 'ja era',
  ]],
];

/** 'assist' | 'stop' | 'restart' | 'destination' | 'remaining' | 'overview' | 'where' | 'help' | 'repeat' | 'back' | 'next' | null. */
export function classifyCommand(text) {
  const normalized = normalize(text);
  for (const [name, phrases] of COMMANDS) {
    if (phrases.some((phrase) => containsPhrase(normalized, phrase))) return name;
  }
  return null;
}

/** Contato citado no texto ("com a segurança", "atendente"), ou null. Vence o apelido mais longo. */
export function findContact(contacts, text) {
  const query = normalize(text);
  let best = null;
  for (const contact of contacts) {
    for (const alias of contact.aliases.map(normalize)) {
      if (alias && containsPhrase(query, alias) && (!best || alias.length > best.length)) {
        best = { contact, length: alias.length };
      }
    }
  }
  return best?.contact ?? null;
}
