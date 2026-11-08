// Conversa do SonarUna: entrada -> destino -> confirmacao -> instrucoes passo a passo.
// Nao fala nem ouve: recebe o que foi ouvido e devolve o que deve ser dito. Quem fala e ouve e o app.js.

import {
  ateLugar, buildInstructions, classifyCommand, classifyYesNo, deLugar, emLugar, exitHeading, findContact, findEntrance, findPlace, normalize,
  paraLugar, planRoute, routeWeight, splitTrip, stepsPhrase,
} from './engine.js';

export const MAX_SILENCE_RETRIES = 3;

/**
 * Cada metodo devolve { say: string[], listen: boolean, phase }.
 * `say` e dito em ordem; se `listen` for verdadeiro o app abre o microfone depois da ultima frase.
 * Quando o usuario pede ajuda e escolhe alguem, vem tambem `action: { type: 'call', contact }`: quem liga e o app.js.
 * `getContacts()` devolve os contatos de ajuda com telefone (veja contacts.js); e lido a cada uso.
 */
// O reconhecedor confunde numeros curtos ("dez bê" virando "3B"); com o bloco antes, ele costuma acertar.
const ROOM_TIP = 'Tente dizer o bloco e o número, por exemplo: bloco B, sala 10.';

export function createSession(map, getContacts = () => []) {
  let phase = 'idle';
  let entrance = null;
  let destination = null;
  let candidate = null;
  let steps = [];
  let index = 0;
  let silences = 0;
  let returnPhase = null;
  let pendingContact = null;
  let suggested = null;
  let locating = false;
  // Destino dito antes da origem ("quero ir para a 10B" sem dizer onde esta): vale assim que a origem for conhecida.
  let pendingDestination = null;

  const reply = (say, listen = true) => ({ say, listen, phase });
  const entranceNames = () => map.entrances.map((e) => e.nome).join(' ou ');
  // Com "origem": "lugar" a conversa comeca perguntando onde a pessoa esta, e nao por qual entrada entrou.
  const byPlace = map.origem === 'lugar';
  // Perguntas curtas; os exemplos ficam na ajuda.
  const originQuestion = () => (byPlace ? 'Onde você está agora?' : `Por qual entrada você entrou? Diga ${entranceNames()}.`);
  const destinationQuestion = () => 'Para onde você quer ir?';
  const contactNames = (contacts) => contacts.map((c) => c.nome).join(' ou ');
  // A primeira etapa diz quantas sao e ensina o "pronto"; as outras vao direto ao que fazer.
  const stepLine = (i) => {
    if (i === 0) return `Etapa 1 de ${steps.length}. ${steps[0].texto}`;
    const label = i === steps.length - 1 ? 'Última etapa.' : `Etapa ${i + 1}.`;
    return `${label} ${withoutPrompt(steps[i].texto)}`;
  };
  const withoutPrompt = (texto) => texto.replace(/ Quando (terminar|chegar), diga pronto\.$/, '');
  // Na UNAERP as salas se chamam pelo numero e depois o bloco ("10B"); o codigo interno vem com o bloco antes.
  const roomName = (code) => (byPlace ? code.replace(/^([A-Z]+)(\d+)$/, '$2$1') : code);
  const capitalize = (text) => `${text.charAt(0).toUpperCase()}${text.slice(1)}`;

  const suggestionQuestion = () => `Parece que você está ${emLugar(suggested)}. Está certo?`;

  function reset() {
    phase = 'entrance';
    entrance = destination = candidate = suggested = null;
    steps = [];
    index = 0;
    silences = 0;
    locating = false;
    pendingDestination = null;
  }

  /** O que o GPS sugere vira pergunta; o GPS nunca decide sozinho onde a pessoa esta. */
  function originFromGps(gps) {
    if (byPlace && gps?.kind === 'sugestao') {
      suggested = gps.place;
      phase = 'confirm-origin';
      return [suggestionQuestion()];
    }
    if (byPlace && gps?.kind === 'fora-do-campus') {
      return [`Você não parece estar ${map.nome ? `no ${map.nome}` : 'no mapa'}.`, originQuestion()];
    }
    return [originQuestion()];
  }

  /** Comeco com o GPS ja lido (ou sem GPS): so a pergunta, sem apresentacao. */
  function start(gps = null) {
    reset();
    return reply(originFromGps(gps));
  }

  /**
   * Comeco enquanto o GPS ainda procura: o app ja escuta. Se a pessoa disser onde esta antes do GPS responder,
   * vale o que ela disse; senao, locationFound faz a pergunta com o que o GPS achou.
   */
  function startLocating() {
    reset();
    locating = true;
    return reply(['Procurando sua localização.']);
  }

  /** Resultado do GPS depois de startLocating. Devolve null se a pessoa ja respondeu antes. */
  function locationFound(gps) {
    if (!locating || phase !== 'entrance' || entrance) return null;
    locating = false;
    return reply(originFromGps(gps));
  }

  function stop() {
    phase = 'idle';
    return reply(['Orientação encerrada. Toque no botão para começar de novo.'], false);
  }

  function help() {
    const common = 'Você pode dizer a qualquer momento: repetir, voltar, recomeçar ou parar.'
      + (getContacts().length > 0 ? ' Para falar com alguém, diga preciso de ajuda.' : '');
    switch (phase) {
      case 'entrance': return reply([byPlace ? `Diga onde você está, por exemplo, ${map.exemploOrigem ?? 'balcão de informações'}. Pode dizer também para onde quer ir.` : originQuestion(), common]);
      case 'confirm-origin': return reply(['Diga sim se você está nesse lugar, ou diga onde você está.', common]);
      case 'destination': return reply([`Diga para onde quer ir, por exemplo, ${map.exemplo}.`, common]);
      case 'navigating': return reply(['Diga pronto quando terminar a etapa e quiser a próxima. Para trocar o destino, diga o novo lugar.', common]);
      default: return reply(['Toque no botão para começar.'], false);
    }
  }

  function announceCurrentQuestion() {
    switch (phase) {
      case 'entrance': return reply([originQuestion()]);
      case 'confirm-origin': return reply([suggestionQuestion()]);
      case 'destination': return reply([destinationQuestion()]);
      case 'navigating': return reply([stepLine(index)]);
      case 'assist-choose': return reply([`Com quem você quer falar? Diga ${contactNames(getContacts())}, ou diga cancelar.`]);
      case 'assist-confirm': return reply([`Você quer falar com ${pendingContact.rotulo}? Diga sim ou não.`]);
      default: return reply([], false);
    }
  }

  /** O usuario pediu ajuda: descobre com quem falar. Com um contato so, confirma; com varios, pergunta. */
  function beginAssist(heard) {
    const contacts = getContacts();
    if (contacts.length === 0) {
      return reply([
        'Não há nenhum contato de ajuda cadastrado neste aparelho. Peça a alguém para cadastrar os telefones na página.',
        ...announceCurrentQuestion().say,
      ]);
    }
    returnPhase = phase;
    const named = heard.map((text) => findContact(contacts, text)).find(Boolean);
    if (named) return callContact(named);
    if (contacts.length === 1) {
      pendingContact = contacts[0];
      phase = 'assist-confirm';
      return reply([`Certo, vou pedir ajuda. Você quer falar com ${pendingContact.rotulo}? Diga sim ou não.`]);
    }
    phase = 'assist-choose';
    return reply([`Certo, vou pedir ajuda. Com quem você quer falar? Diga ${contactNames(contacts)}, ou diga cancelar.`]);
  }

  function hearAssist(heard) {
    const contacts = getContacts();
    for (const text of heard) {
      if (phase === 'assist-choose') {
        const chosen = findContact(contacts, text);
        if (chosen) return callContact(chosen);
      } else if (classifyYesNo(text) === 'yes') {
        return callContact(pendingContact);
      }
      if (classifyYesNo(text) === 'no' || ['stop', 'back'].includes(classifyCommand(text))) return cancelAssist();
    }
    return reply(phase === 'assist-choose'
      ? [`Não entendi. Diga ${contactNames(contacts)}, ou diga cancelar.`]
      : ['Não entendi. Diga sim ou não.']);
  }

  /** A ligacao em si e feita pelo app.js; a conversa so guarda onde parou para retomar depois. */
  function callContact(contact) {
    phase = returnPhase;
    pendingContact = null;
    return {
      say: [`Ligando para ${contact.rotulo} agora. Quando terminar a ligação, toque no botão para continuar.`],
      listen: false,
      phase,
      action: { type: 'call', contact: { id: contact.id, nome: contact.nome, telefone: contact.telefone } },
    };
  }

  function cancelAssist() {
    phase = returnPhase;
    pendingContact = null;
    return reply(['Tudo bem. Vamos continuar.', ...announceCurrentQuestion().say]);
  }

  /** Volta de uma ligacao (ou de uma pausa): repete onde a conversa estava, sem perder a rota. */
  function resume() {
    if (phase === 'idle') return start();
    silences = 0;
    if (phase === 'navigating') {
      return reply([`Retomando a etapa ${index + 1} de ${steps.length}. Diga pronto para a próxima, ou repetir para ouvir de novo.`]);
    }
    return reply(['Retomando.', ...announceCurrentQuestion().say]);
  }

  const noRouteYet = () => reply(['Ainda não há uma rota.', ...announceCurrentQuestion().say]);

  /** "Quanto falta?": quantas etapas faltam depois da atual. */
  function remaining() {
    if (phase !== 'navigating') return noRouteYet();
    const rest = steps.length - index - 1;
    const left = rest === 0
      ? `Esta é a última etapa ${ateLugar(destination)}.`
      : `Você está na etapa ${index + 1} de ${steps.length}. Depois desta, ${rest === 1 ? 'falta 1 etapa' : `faltam ${rest} etapas`} ${ateLugar(destination)}.`;
    return reply([left]);
  }

  /** "Repasse o caminho": as etapas que faltam, da atual ate o destino, e onde a pessoa esta nelas. */
  function overview() {
    if (phase !== 'navigating') return noRouteYet();
    const rest = steps.slice(index).map((step, i) => `Etapa ${index + i + 1}: ${withoutPrompt(step.texto)}`);
    return reply([`Caminho ${ateLugar(destination)}.`, ...rest, `Agora você está na etapa ${index + 1}. Quando terminar, diga pronto.`]);
  }

  /** "Onde estou?": o lugar de partida, ou o fim da ultima etapa feita. */
  function whereAmI() {
    if (phase === 'navigating') {
      if (index === 0) return reply([`Você está ${emLugar(entrance)}, no começo da rota.`, stepLine(index)]);
      const done = steps[index - 1];
      return reply([`Se você terminou a etapa ${index}, está ${emLugar(map.points.get(done.ponto))}.`, stepLine(index)]);
    }
    if (entrance) return reply([`Você está ${emLugar(entrance)}.`, ...announceCurrentQuestion().say]);
    return reply(['Ainda não sei onde você está.', ...announceCurrentQuestion().say]);
  }

  /**
   * Novo destino sem perder onde a pessoa esta. No meio da rota, ela esta onde terminou a ultima etapa feita,
   * virada para o lado em que andava.
   */
  function changeDestination() {
    if (phase === 'navigating' && index > 0 && steps[index - 1].ponto) {
      const done = steps[index - 1];
      const place = map.points.get(done.ponto);
      entrance = { id: place.id, nome: place.nome, artigo: place.artigo, tipo: place.tipo, ponto: place.id, rumo: done.rumo };
    }
    candidate = destination = null;
    steps = [];
    index = 0;
    phase = 'destination';
    return reply(['Certo. Para onde você quer ir?']);
  }

  /** Onde a pessoa esta: qualquer lugar do mapa; ela sai dele pelo unico trecho do piso que leva ate ali. */
  function hearOrigin(alternatives) {
    let several = null;
    let unknown = null;
    for (const text of alternatives) {
      const result = findPlace(map, text);
      if (result.kind === 'found') return setOrigin(result.place);
      several ??= ['several', 'ambiguous'].includes(result.kind) ? result : null;
      unknown ??= result.kind === 'unknown-room' ? result : null;
    }
    if (several) {
      const names = several.places.map((place) => place.nome).join(', ');
      return reply([`Encontrei mais de um lugar: ${names}. Diga o nome completo.`]);
    }
    if (unknown) return reply([`Não encontrei a sala ${roomName(unknown.code)} neste mapa. ${ROOM_TIP}`]);
    return reply([`Não reconheci esse lugar. ${originQuestion()}`]);
  }

  function setOrigin(place) {
    entrance = { id: place.id, nome: place.nome, artigo: place.artigo, tipo: place.tipo, ponto: place.id, rumo: exitHeading(map, place.id) };
    suggested = null;
    phase = 'destination';
    if (pendingDestination) {
      const text = pendingDestination;
      pendingDestination = null;
      const route = routeTo(text);
      if (route) return route;
    }
    return reply([`Você está ${emLugar(place)}. ${destinationQuestion()}`]);
  }

  /** Origem e destino ditos juntos: sai direto, dizendo de onde para onde. Null se nao achar o destino. */
  function routeTo(text) {
    let to = findPlace(map, text);
    if (to.kind === 'several') to = { kind: 'found', place: nearestOf(to.places) };
    if (to.kind !== 'found' || to.place.id === entrance.ponto) return null;
    candidate = to.place;
    return beginRoute(true);
  }

  /** Entre varios lugares com o mesmo nome ("banheiro"), o mais perto de onde a pessoa esta. */
  const nearestOf = (places) => [...places].sort((a, b) => routeWeight(map, entrance.ponto, a.id) - routeWeight(map, entrance.ponto, b.id))[0];

  /** Resposta a "parece que voce esta em X": sim confirma; nao pergunta; citar outro lugar ja corrige. */
  function hearOriginConfirmation(alternatives) {
    for (const text of alternatives) {
      const result = findPlace(map, text);
      const same = result.kind === 'found' && result.place.id === suggested.id;
      if (result.kind !== 'none' && !same) return hearOrigin(alternatives);
    }
    for (const text of alternatives) {
      const answer = classifyYesNo(text);
      if (answer === 'yes') return setOrigin(suggested);
      if (answer === 'no') {
        suggested = null;
        phase = 'entrance';
        return reply([`Tudo bem. ${originQuestion()}`]);
      }
    }
    return reply(['Não entendi. Diga sim ou não, ou diga onde você está.']);
  }

  function hearEntrance(alternatives) {
    if (byPlace) return hearOrigin(alternatives);
    for (const text of alternatives) {
      const found = findEntrance(map, text);
      if (found) {
        entrance = found;
        phase = 'destination';
        return reply([`${found.nome.charAt(0).toUpperCase()}${found.nome.slice(1)}. ${destinationQuestion()}`]);
      }
    }
    return reply([`Não reconheci essa entrada. Diga ${entranceNames()}.`]);
  }

  /**
   * O destino ja vira rota, sem pergunta de confirmacao: o resumo diz o lugar, e dizer outro lugar no caminho troca.
   * `avoid`: o destino que a pessoa acabou de trocar. Se o reconhecedor ouvir de novo o mesmo lugar como melhor
   * hipotese ("3B" quando ela disse "10B"), vale a proxima hipotese que traz outro lugar.
   */
  function hearDestination(alternatives, avoid = null) {
    let unknown = null;
    let ambiguous = null;
    let same = null;
    for (const text of alternatives) {
      const result = findPlace(map, text);
      if (result.kind === 'several') {
        const nearest = nearestOf(result.places);
        if (routeWeight(map, entrance.ponto, nearest.id) < Infinity) Object.assign(result, { kind: 'found', place: nearest });
      }
      if (result.kind === 'found' && result.place.id === avoid) {
        same ??= result.place;
        continue;
      }
      if (result.kind === 'found') {
        if (result.place.id === entrance.ponto) {
          return reply([byPlace ? 'Você já está aí. Diga outro destino.' : 'Você já está na entrada. Diga para onde quer ir.']);
        }
        candidate = result.place;
        return beginRoute();
      }
      unknown ??= result.kind === 'unknown-room' ? result : null;
      ambiguous ??= result.kind === 'ambiguous' ? result : null;
    }
    if (same) {
      candidate = same;
      return beginRoute();
    }
    if (ambiguous) {
      const names = ambiguous.places.map((place) => place.nome).join(', ');
      return reply([`Encontrei mais de uma sala: ${names}. Diga o nome completo da sala.`]);
    }
    if (unknown) {
      return reply([`Não encontrei a sala ${roomName(unknown.code)} neste mapa. ${ROOM_TIP}`]);
    }
    return reply([`Não entendi o destino. ${destinationQuestion()}`]);
  }

  /** `fromOrigin`: a pessoa acabou de dizer onde esta; o resumo diz de onde para onde. */
  function beginRoute(fromOrigin = false) {
    const segments = planRoute(map, entrance.ponto, candidate.id);
    if (!segments || segments.length === 0) {
      phase = 'destination';
      return reply([`Não encontrei um caminho ${deLugar(entrance)} ${ateLugar(candidate)}. Escolha outro destino.`]);
    }
    destination = candidate;
    steps = buildInstructions(map, entrance, segments);
    index = 0;
    phase = 'navigating';
    const etapas = `${steps.length} ${steps.length === 1 ? 'etapa' : 'etapas'}`;
    // No modo piso nao ha medida: a rota so diz quantas etapas tem.
    const going = fromOrigin ? `${capitalize(deLugar(entrance))} ${paraLugar(destination)}` : `Indo ${paraLugar(destination)}`;
    const summary = map.guia === 'piso'
      ? `${going}, em ${etapas}.`
      : `${going}: ${stepsPhrase(steps.reduce((sum, step) => sum + step.bruto, 0))}, em ${etapas}.`;
    return reply([summary, stepLine(0)]);
  }

  function hearNavigation(alternatives) {
    // "E agora quero ir para o banheiro": um destino dito com "quero ir", "para"... vale antes de "e agora" contar
    // como pronto.
    if (alternatives.some((text) => {
      const trip = splitTrip(text);
      return trip.destino && findPlace(map, trip.destino).kind !== 'none';
    })) {
      const previous = destination.id;
      changeDestination();
      return hearDestination(alternatives, previous);
    }
    for (const text of alternatives) {
      // "Pronto", "foi" ou qualquer sim ("beleza", "isso", "certo"): a etapa foi feita.
      switch (classifyCommand(text) ?? (classifyYesNo(text) === 'yes' ? 'next' : null)) {
        case 'next':
          if (index + 1 >= steps.length) {
            // Chegou: o destino vira o novo ponto de partida e o app ja escuta o proximo, sem precisar tocar.
            const arrived = destination;
            setOrigin(arrived);
            candidate = destination = null;
            steps = [];
            index = 0;
            return reply([`Você chegou ${emLugar(arrived)}. Diga outro destino, ou parar para encerrar.`]);
          }
          index++;
          return reply([stepLine(index)]);
        case 'repeat':
          return reply([stepLine(index)]);
        case 'back':
          index = Math.max(0, index - 1);
          return reply([stepLine(index)]);
        default:
          break;
      }
    }
    // Dizer um lugar no meio da rota ("quero ir para o banheiro") troca o destino a partir de onde a pessoa esta.
    if (alternatives.some((text) => findPlace(map, text).kind !== 'none')) {
      const previous = destination.id;
      changeDestination();
      return hearDestination(alternatives, previous);
    }
    // "Nao" no caminho: o destino ouvido estava errado; pergunta de novo, sem perder onde a pessoa esta.
    if (alternatives.some((text) => classifyYesNo(text) === 'no' && normalize(text).split(' ').length <= 4)) return changeDestination();
    return reply(['Não entendi. Diga pronto para a próxima etapa, repetir, voltar ou parar.']);
  }

  /**
   * "Estou na 3A e quero ir para a 10B": com origem e destino na mesma frase, o app ja faz o caminho, sem perguntar.
   * So o destino, antes de saber a origem ("quero ir para a 10B"): o app guarda o destino e pergunta onde a pessoa
   * esta. Devolve null quando a frase nao fala de destino.
   */
  function hearTrip(alternatives) {
    if (!byPlace) return null;
    const originKnown = !['entrance', 'confirm-origin'].includes(phase);
    for (const text of alternatives) {
      const trip = splitTrip(text);
      if (!trip.destino) continue;
      let from = trip.origem ? findPlace(map, trip.origem) : { kind: 'none' };
      const answer = trip.origem ? classifyYesNo(trip.origem) : null;
      // "Sim, quero ir para a 15B" depois de "parece que voce esta em X": confirma X e ja vai.
      if (from.kind !== 'found' && phase === 'confirm-origin' && answer === 'yes') from = { kind: 'found', place: suggested };
      if (from.kind === 'found') {
        // setOrigin ja sai para o destino; se o destino nao for reconhecido, pergunta de novo.
        pendingDestination = trip.destino;
        return setOrigin(from.place);
      }
      if (!originKnown && from.kind !== 'none') {
        // A origem dita nao existe no mapa ("3B") ou e ambigua: guarda o destino e pergunta so a origem de novo.
        pendingDestination = trip.destino;
        suggested = null;
        phase = 'entrance';
        return hearOrigin([trip.origem]);
      }
      if (originKnown || findPlace(map, trip.destino).kind === 'none') continue;
      pendingDestination = trip.destino;
      if (phase === 'confirm-origin' && answer !== 'no') return reply([suggestionQuestion()]);
      suggested = null;
      phase = 'entrance';
      return reply([originQuestion()]);
    }
    return null;
  }

  /** `alternatives`: o que o reconhecimento de voz entendeu, da melhor para a pior hipotese. */
  function hear(alternatives) {
    if (phase === 'idle') return reply([], false);
    const heard = alternatives.map((text) => String(text ?? '').trim()).filter(Boolean);
    if (heard.length === 0) return silence();
    silences = 0;
    // A pessoa falou: o que o GPS achar depois nao muda mais a conversa.
    locating = false;

    if (phase === 'assist-choose' || phase === 'assist-confirm') return hearAssist(heard);
    if (heard.some((text) => classifyCommand(text) === 'assist')) return beginAssist(heard);

    for (const text of heard) {
      const command = classifyCommand(text);
      if (command === 'stop') return stop();
      if (command === 'restart') return start();
      if (command === 'destination') return entrance ? changeDestination() : announceCurrentQuestion();
      if (command === 'remaining') return remaining();
      if (command === 'overview') return overview();
      if (command === 'where') return whereAmI();
      if (command === 'help') return help();
    }
    if (phase !== 'navigating') {
      for (const text of heard) {
        const command = classifyCommand(text);
        if (command === 'repeat') return announceCurrentQuestion();
        if (command === 'back' && phase !== 'entrance') {
          phase = 'entrance';
          candidate = null;
          return announceCurrentQuestion();
        }
      }
    }

    if (['entrance', 'confirm-origin', 'destination'].includes(phase)) {
      const trip = hearTrip(heard);
      if (trip) return trip;
    }

    switch (phase) {
      case 'entrance': return hearEntrance(heard);
      case 'confirm-origin': return hearOriginConfirmation(heard);
      case 'destination': return hearDestination(heard);
      case 'navigating': return hearNavigation(heard);
      default: return reply([], false);
    }
  }

  /**
   * Nada foi ouvido (silencio, ruido ou erro passageiro do microfone). Depois do primeiro toque a pessoa nao precisa
   * tocar de novo: o microfone segue ouvindo ate ela dizer parar. Nas perguntas, o app pede para repetir algumas
   * vezes e depois so escuta; na rota, so escuta.
   */
  function silence() {
    if (phase === 'idle') return reply([], false);
    // Na rota, silencio e a pessoa andando: o microfone continua ouvindo, calado, ate ela dizer pronto.
    // Enquanto o GPS procura, silencio e so esperar: quem fala depois e o GPS (locationFound).
    if (phase === 'navigating' || locating) return reply([]);
    silences++;
    if (silences >= MAX_SILENCE_RETRIES) {
      if (phase === 'assist-choose' || phase === 'assist-confirm') {
        silences = 0;
        return cancelAssist();
      }
      // Ja perguntou de novo algumas vezes: continua ouvindo sem repetir, para nao falar sem parar.
      return reply([]);
    }
    return reply(['Não ouvi. Pode repetir?']);
  }

  return {
    start,
    startLocating,
    locationFound,
    resume,
    stop,
    hear,
    silence,
    get phase() { return phase; },
    get steps() { return steps; },
  };
}
