// Motor do SonarUna: reconhecimento de lugares falados, rota e instrucoes.
// Nao depende do navegador, entao roda tambem no Node para os testes.

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

  for (let i = 0; i < terms.length - 1; i++) {
    const block = blockOf(terms[i]);
    if (!block || terms[i + 1].num === undefined) continue;
    const previous = terms[i - 1]?.word;
    if (AMBIGUOUS_LETTERS.has(terms[i].word) && i > 0 && previous !== 'sala' && previous !== 'bloco') continue;
    return make(block.toUpperCase(), terms[i + 1].num);
  }
  // Letra depois do numero so quando fecha a frase: em "sala 20 a esquerda", o "a" e a crase.
  const last = terms.length - 1;
  if (last >= 1 && blockOf(terms[last]) && terms[last - 1].num !== undefined) {
    return make(blockOf(terms[last]).toUpperCase(), terms[last - 1].num);
  }
  if (roomNumber !== null) return make(null, roomNumber);
  return null;
}

/** Artigo do lugar ("a sala A12", "o banheiro"): "o" quando o mapa diz, senao "a". */
const article = (point) => point.artigo === 'o' ? 'o' : 'a';
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
      return room ? { kind: 'found', place: room } : { kind: 'unknown-room', code: code.canonical };
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

/** Direcao de quem sai de um lugar pelo seu unico trecho (porta de sala ou ponta do piso), ou null. */
export function exitHeading(map, pointId) {
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
    } else {
      groups.push({
        virar: turnPhrase(angle),
        metros: segment.metros,
        passos: stepsOf(map, segment),
        referencias: [...segment.referencias],
        destino: segment.para,
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
    return { texto: parts.join(' '), metros, passos, bruto: group.passos };
  });
}

const ORDINAIS = ['primeiro', 'segundo', 'terceiro', 'quarto', 'quinto', 'sexto', 'sétimo', 'oitavo', 'nono', 'décimo'];
const ordinal = (n) => ORDINAIS[n - 1] ?? `${n}º`;

/**
 * Guia pelo piso tatil, sem medidas: cada etapa vai ate uma curva, uma escada, o destino ou, numa reta longa,
 * ate um cruzamento no meio do caminho, e diz em qual cruzamento do piso parar. Todo ponto no meio de uma reta
 * conta como cruzamento (menos curvas e patamares), porque no mapa de piso os pontos sao as interseccoes que a
 * pessoa sente com a bengala. Cada item: { texto, cruzamentos, bruto: null }.
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
    const next = groups[index + 1];
    if (group.escada) {
      const action = { sobe: 'Suba a escada', desce: 'Desça a escada' }[group.escada];
      const turn = group.virar === 'Siga em frente' ? '' : `${group.virar}. `;
      steps.push(floorStep(map, group.trechos, [action ? `${turn}${action}.` : `${turn}Atenção, escada. Use o corrimão.`], isLast));
      return;
    }
    for (const [i, chunk] of splitLongStraight(map, group.trechos, isLast || next.escada).entries()) {
      const go = i > 0 ? 'Continue em frente pelo piso tátil'
        : group.virar === 'Siga em frente' ? 'Siga em frente pelo piso tátil' : `${group.virar} e siga o piso tátil`;
      const end = map.points.get(chunk.trechos[chunk.trechos.length - 1].para);
      const passing = chunk.passados > 0 ? `, passando por ${plural(chunk.passados, 'cruzamento', 'cruzamentos')},` : '';
      let text;
      if (chunk.pausa) text = `${go} até o ${ordinal(chunk.passados + 1)} cruzamento${landmark(map, end.id)}.`;
      else if (isLast) text = `${go}${passing} até o fim.`;
      else if (next.escada) text = `${go}${passing} até a escada.`;
      else if (end.tipo === 'curva') text = `${go}${passing} até a curva do piso.`;
      else text = `${go} até o ${ordinal(chunk.passados + 1)} cruzamento.`;
      steps.push(floorStep(map, chunk.trechos, [text], isLast && !chunk.pausa, chunk.passados));
    }
  });
  return steps;
}

const isCrossing = (map, id) => !['curva', 'patamar'].includes(map.points.get(id).tipo);

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

/** ", na altura da sala 14B": o primeiro lugar com nome ligado ao cruzamento, para a pessoa confirmar onde parou. */
function landmark(map, crossingId) {
  const named = map.adjacency.get(crossingId)
    .map((edge) => map.points.get(edge.to))
    .find((point) => !['cruzamento', 'curva', 'patamar'].includes(point.tipo));
  return named ? `, na altura ${deLugar(named)}` : '';
}

function floorStep(map, trechos, parts, arrives, passados = 0) {
  const referencias = [...new Set(trechos.flatMap((segment) => segment.referencias))];
  if (referencias.length > 0) parts.push(`Referência: ${referencias.join(', ')}.`);
  const end = map.points.get(trechos[trechos.length - 1].para);
  parts.push(arrives ? `Você chegará ${emLugar(end)}. Quando chegar, diga pronto.` : 'Quando terminar, diga pronto.');
  return { texto: parts.join(' '), cruzamentos: passados, bruto: null };
}

// ---------------------------------------------------------------------------------------------
// Comandos de voz

// Palavras e expressoes soltas em qualquer parte da frase: "acho que sim", "sim sim", "isso ai, pode ir".
const POSITIVE = [
  'sim', 'isso', 'correto', 'correta', 'certo', 'certa', 'confirmo', 'confirmado', 'confirma', 'exato', 'exatamente',
  'claro', 'ok', 'okay', 'beleza', 'positivo', 'afirmativo', 'perfeito', 'uhum', 'aham', 'certeza', 'pode',
  'quero', 'bora', 'vamos', 'ta bom', 'esta bom', 'tudo bem', 'e esse', 'e essa', 'e ela', 'e ele', 'esse mesmo',
  'essa mesma', 'yes',
];
const NEGATIVE = [
  'nao', 'errado', 'errada', 'incorreto', 'incorreta', 'negativo', 'nunca', 'corrigir', 'corrige', 'trocar',
  'troca', 'mudar', 'muda', 'nem', 'enganei',
];
// Duvida declarada: nao e resposta, mesmo com "nao" no meio.
const UNSURE = ['nao sei', 'sei la', 'talvez', 'duvida', 'nao tenho certeza'];

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
  ['stop', ['parar', 'pare', 'cancelar', 'cancela', 'encerrar', 'sair', 'finalizar']],
  ['restart', ['recomecar', 'reiniciar', 'comecar de novo', 'nova rota', 'outra rota', 'novo destino', 'outro destino']],
  ['help', ['ajuda']],
  ['repeat', ['repetir', 'repete', 'repita', 'de novo', 'nao ouvi', 'nao entendi']],
  ['back', ['voltar', 'volta', 'anterior', 'passo anterior']],
  ['next', ['pronto', 'proximo', 'continuar', 'continua', 'seguir', 'cheguei', 'ok', 'feito', 'ja fui', 'ja andei']],
];

/** 'assist' | 'stop' | 'restart' | 'help' | 'repeat' | 'back' | 'next' | null. */
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
