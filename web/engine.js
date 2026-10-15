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

const isLetter = (term) => term.word !== undefined && term.word.length === 1 && /[a-z]/.test(term.word);

function letterAfter(terms, keyword) {
  for (let i = 0; i < terms.length - 1; i++) {
    if (terms[i].word === keyword && isLetter(terms[i + 1])) return terms[i + 1].word;
  }
  return null;
}

function numberAfter(terms, keyword) {
  for (let i = 0; i < terms.length - 1; i++) {
    if (terms[i].word === keyword && terms[i + 1].num !== undefined) return terms[i + 1].num;
  }
  return null;
}

/**
 * "A15", "a 15", "a quinze", "bloco B sala 3", "sala 20". As letras "e" e "o" sao ignoradas como bloco,
 * pois quase sempre sao a conjuncao ou o artigo ("sala 12 e 15").
 */
export function parseRoomCode(text) {
  const terms = toTerms(normalize(text).split(' ').filter(Boolean));
  const make = (block, number) => ({ block, number, canonical: `${block ?? ''}${number}` });

  const blockLetter = letterAfter(terms, 'bloco');
  const roomNumber = numberAfter(terms, 'sala');
  if (blockLetter && roomNumber !== null) return make(blockLetter.toUpperCase(), roomNumber);

  for (let i = 0; i < terms.length - 1; i++) {
    if (isLetter(terms[i]) && !'eo'.includes(terms[i].word) && terms[i + 1].num !== undefined) {
      return make(terms[i].word.toUpperCase(), terms[i + 1].num);
    }
  }
  if (roomNumber !== null) return make(null, roomNumber);
  return null;
}

/** Artigo do lugar ("a sala A12", "o banheiro"): "o" quando o mapa diz, senao "a". */
const article = (point) => point.artigo === 'o' ? 'o' : 'a';
export const paraLugar = (point) => `para ${article(point)} ${point.nome}`;
export const ateLugar = (point) => `até ${article(point)} ${point.nome}`;
export const emLugar = (point) => `n${article(point)} ${point.nome}`;

const containsPhrase = (normalizedText, phrase) => ` ${normalizedText} `.includes(` ${phrase} `);

// ---------------------------------------------------------------------------------------------
// Mapa

/** Valida o JSON do mapa e monta a lista de vizinhos. Falha com mensagem clara se algo estiver errado. */
export function loadMap(raw) {
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
    if (!(edge.metros > 0)) throw new Error(`Trecho ${edge.de} -> ${edge.para} precisa de metros maiores que zero`);
    if (edge.passos !== undefined && !(edge.passos > 0)) {
      throw new Error(`Trecho ${edge.de} -> ${edge.para} precisa de passos maiores que zero`);
    }
    const references = edge.referencias ?? [];
    const common = { metros: edge.metros, passos: edge.passos, referencias: references };
    adjacency.get(edge.de).push({ to: edge.para, rumo: mod360(edge.rumo), ...common });
    adjacency.get(edge.para).push({ to: edge.de, rumo: mod360(edge.rumo + 180), ...common });
  }
  const entrances = (raw.entradas ?? []).map((entrance) => {
    if (!points.has(entrance.ponto)) throw new Error(`Entrada ${entrance.id} usa ponto inexistente: ${entrance.ponto}`);
    return {
      ...entrance,
      aliases: [normalize(entrance.nome), ...(entrance.aliases ?? []).map(normalize)],
      rumo: mod360(entrance.rumo),
    };
  });
  return {
    bloco: raw.bloco,
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
 * Resultado: { kind: 'found', place } | { kind: 'ambiguous', places } | { kind: 'unknown-room', code } | { kind: 'none' }.
 */
export function findPlace(map, text) {
  const rooms = [...map.points.values()].filter((point) => point.codigo);
  const code = parseRoomCode(text);
  if (code) {
    if (code.block) {
      const room = rooms.find((point) => point.codigo === code.canonical);
      return room ? { kind: 'found', place: room } : { kind: 'unknown-room', code: code.canonical };
    }
    const matches = rooms.filter((point) => Number(point.codigo.replace(/\D/g, '')) === code.number);
    if (matches.length === 1) return { kind: 'found', place: matches[0] };
    if (matches.length > 1) return { kind: 'ambiguous', places: matches };
    return { kind: 'unknown-room', code: String(code.number) };
  }
  const query = normalize(text);
  let best = null;
  for (const point of map.points.values()) {
    if (point.codigo || point.tipo === 'entrada') continue;
    for (const alias of point.aliases) {
      if (alias && containsPhrase(query, alias) && (!best || alias.length > best.length)) {
        best = { point, length: alias.length };
      }
    }
  }
  return best ? { kind: 'found', place: best.point } : { kind: 'none' };
}

// ---------------------------------------------------------------------------------------------
// Rota

/** Caminho mais curto em metros (Dijkstra). Devolve a lista de trechos, [] se origem = destino, ou null se nao ha caminho. */
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
      const candidate = distance.get(current) + edge.metros;
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
    segments.unshift({ de: from, para: at, metros: edge.metros, passos: edge.passos, rumo: edge.rumo, referencias: edge.referencias });
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

// ---------------------------------------------------------------------------------------------
// Comandos de voz

const POSITIVE = new Set([
  'sim', 'correto', 'isso', 'isso mesmo', 'esta certo', 'esta correto', 'certo', 'confirmo', 'confirmado',
  'pode confirmar', 'sim correto', 'sim esta correto', 'sim esta certo', 'sim isso', 'sim isso mesmo',
  'sim confirmo', 'sim pode confirmar', 'positivo', 'pode ser', 'claro',
]);
const NEGATIVE = new Set([
  'nao', 'errado', 'incorreto', 'nao esta correto', 'nao esta certo', 'esta errado', 'nao esta certo nao',
  'nao esta correto nao', 'nao e isso', 'nao confirmo', 'corrigir', 'quero corrigir', 'nao correto', 'negativo',
]);

/** Frases inteiras, para "nao esta correto" nunca virar "correto". Devolve 'yes', 'no' ou null. */
export function classifyYesNo(text) {
  const normalized = normalize(text);
  if (POSITIVE.has(normalized)) return 'yes';
  if (NEGATIVE.has(normalized)) return 'no';
  return null;
}

const COMMANDS = [
  ['stop', ['parar', 'pare', 'cancelar', 'cancela', 'encerrar', 'sair', 'finalizar']],
  ['restart', ['recomecar', 'reiniciar', 'comecar de novo', 'nova rota', 'outra rota', 'novo destino', 'outro destino']],
  ['help', ['ajuda', 'socorro']],
  ['repeat', ['repetir', 'repete', 'repita', 'de novo', 'nao ouvi', 'nao entendi']],
  ['back', ['voltar', 'volta', 'anterior', 'passo anterior']],
  ['next', ['pronto', 'proximo', 'continuar', 'continua', 'seguir', 'cheguei', 'ok', 'feito', 'ja fui', 'ja andei']],
];

/** 'stop' | 'restart' | 'help' | 'repeat' | 'back' | 'next' | null. */
export function classifyCommand(text) {
  const normalized = normalize(text);
  for (const [name, phrases] of COMMANDS) {
    if (phrases.some((phrase) => containsPhrase(normalized, phrase))) return name;
  }
  return null;
}
