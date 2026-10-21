// Conversa do SonarUna: entrada -> destino -> confirmacao -> instrucoes passo a passo.
// Nao fala nem ouve: recebe o que foi ouvido e devolve o que deve ser dito. Quem fala e ouve e o app.js.

import {
  ateLugar, buildInstructions, classifyCommand, classifyYesNo, emLugar, findContact, findEntrance, findPlace, paraLugar, planRoute, stepsPhrase,
} from './engine.js';

export const MAX_SILENCE_RETRIES = 3;

/**
 * Cada metodo devolve { say: string[], listen: boolean, phase }.
 * `say` e dito em ordem; se `listen` for verdadeiro o app abre o microfone depois da ultima frase.
 * Quando o usuario pede ajuda e escolhe alguem, vem tambem `action: { type: 'call', contact }`: quem liga e o app.js.
 * `getContacts()` devolve os contatos de ajuda com telefone (veja contacts.js); e lido a cada uso.
 */
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

  const reply = (say, listen = true) => ({ say, listen, phase });
  const entranceNames = () => map.entrances.map((e) => e.nome).join(' ou ');
  const contactNames = (contacts) => contacts.map((c) => c.nome).join(' ou ');
  const stepLine = (i) => `Etapa ${i + 1} de ${steps.length}. ${steps[i].texto}`;

  function start() {
    phase = 'entrance';
    entrance = destination = candidate = null;
    steps = [];
    index = 0;
    silences = 0;
    return reply([
      `Olá. Eu sou o SonarUna, no bloco ${map.bloco}.`,
      ...(getContacts().length > 0 ? ['Se precisar falar com alguém, diga preciso de ajuda a qualquer momento.'] : []),
      `Por qual entrada você entrou? Diga ${entranceNames()}.`,
    ]);
  }

  function stop() {
    phase = 'idle';
    return reply(['Orientação encerrada. Toque no botão para começar de novo.'], false);
  }

  function help() {
    const common = 'Você pode dizer a qualquer momento: repetir, voltar, recomeçar ou parar.'
      + (getContacts().length > 0 ? ' Para falar com alguém, diga preciso de ajuda.' : '');
    switch (phase) {
      case 'entrance': return reply([`Diga por qual entrada você entrou: ${entranceNames()}.`, common]);
      case 'destination': return reply(['Diga para onde quer ir, por exemplo: sala A12, secretaria ou banheiro.', common]);
      case 'confirm': return reply(['Diga sim para confirmar o destino ou não para escolher outro.', common]);
      case 'navigating': return reply(['Diga pronto quando terminar a etapa e quiser a próxima.', common]);
      default: return reply(['Toque no botão para começar.'], false);
    }
  }

  function announceCurrentQuestion() {
    switch (phase) {
      case 'entrance': return reply([`Por qual entrada você entrou? Diga ${entranceNames()}.`]);
      case 'destination': return reply(['Para onde você quer ir? Diga, por exemplo, sala A12.']);
      case 'confirm': return reply([`Você quer ir ${paraLugar(candidate)}. Está certo? Diga sim ou não.`]);
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
    return reply(['Retomando.', ...announceCurrentQuestion().say]);
  }

  function hearEntrance(alternatives) {
    for (const text of alternatives) {
      const found = findEntrance(map, text);
      if (found) {
        entrance = found;
        phase = 'destination';
        return reply([`${found.nome.charAt(0).toUpperCase()}${found.nome.slice(1)}. Para onde você quer ir? Diga, por exemplo, sala A12.`]);
      }
    }
    return reply([`Não reconheci essa entrada. Diga ${entranceNames()}.`]);
  }

  function hearDestination(alternatives) {
    let unknown = null;
    let ambiguous = null;
    for (const text of alternatives) {
      const result = findPlace(map, text);
      if (result.kind === 'found') {
        if (result.place.id === entrance.ponto) {
          return reply(['Você já está na entrada. Diga para onde quer ir.']);
        }
        candidate = result.place;
        phase = 'confirm';
        return reply([`Você quer ir ${paraLugar(candidate)}. Está certo? Diga sim ou não.`]);
      }
      unknown ??= result.kind === 'unknown-room' ? result : null;
      ambiguous ??= result.kind === 'ambiguous' ? result : null;
    }
    if (ambiguous) {
      const names = ambiguous.places.map((place) => place.nome).join(', ');
      return reply([`Encontrei mais de uma sala: ${names}. Diga o nome completo da sala.`]);
    }
    if (unknown) {
      return reply([`Não encontrei a sala ${unknown.code} neste bloco. Diga outra sala, a secretaria ou o banheiro.`]);
    }
    return reply(['Não entendi o destino. Diga, por exemplo, sala A12, secretaria ou banheiro.']);
  }

  function hearConfirmation(alternatives) {
    // Citar outro lugar troca o destino ("não, a secretaria"), em vez de "quero" contar como sim.
    for (const text of alternatives) {
      const result = findPlace(map, text);
      const same = result.kind === 'found' && result.place.id === candidate.id;
      if (result.kind !== 'none' && !same) return hearDestination(alternatives);
    }
    for (const text of alternatives) {
      const answer = classifyYesNo(text);
      if (answer === 'yes') return beginRoute();
      if (answer === 'no') {
        candidate = null;
        phase = 'destination';
        return reply(['Certo. Para onde você quer ir?']);
      }
    }
    return reply(['Não entendi. Diga apenas sim ou não.']);
  }

  function beginRoute() {
    const segments = planRoute(map, entrance.ponto, candidate.id);
    if (!segments || segments.length === 0) {
      phase = 'destination';
      return reply([`Não encontrei um caminho da ${entrance.nome} ${ateLugar(candidate)}. Escolha outro destino.`]);
    }
    destination = candidate;
    steps = buildInstructions(map, entrance, segments);
    index = 0;
    phase = 'navigating';
    const etapas = `${steps.length} ${steps.length === 1 ? 'etapa' : 'etapas'}`;
    // No modo piso nao ha medida: a rota so diz quantas etapas tem.
    const summary = map.guia === 'piso'
      ? `Rota ${ateLugar(destination)}, em ${etapas}, seguindo o piso tátil.`
      : `Rota ${ateLugar(destination)}: ${stepsPhrase(steps.reduce((sum, step) => sum + step.bruto, 0))}, em ${etapas}.`;
    return reply([summary, stepLine(0)]);
  }

  function hearNavigation(alternatives) {
    for (const text of alternatives) {
      switch (classifyCommand(text)) {
        case 'next':
          if (index + 1 >= steps.length) {
            phase = 'idle';
            return reply([`Você chegou ${emLugar(destination)}. Toque no botão para uma nova orientação.`], false);
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
    return reply(['Não entendi. Diga pronto para a próxima etapa, repetir, voltar ou parar.']);
  }

  /** `alternatives`: o que o reconhecimento de voz entendeu, da melhor para a pior hipotese. */
  function hear(alternatives) {
    if (phase === 'idle') return reply([], false);
    const heard = alternatives.map((text) => String(text ?? '').trim()).filter(Boolean);
    if (heard.length === 0) return silence();
    silences = 0;

    if (phase === 'assist-choose' || phase === 'assist-confirm') return hearAssist(heard);
    if (heard.some((text) => classifyCommand(text) === 'assist')) return beginAssist(heard);

    for (const text of heard) {
      const command = classifyCommand(text);
      if (command === 'stop') return stop();
      if (command === 'restart') return start();
      if (command === 'help') return help();
    }
    if (phase !== 'navigating') {
      for (const text of heard) {
        const command = classifyCommand(text);
        if (command === 'repeat') return announceCurrentQuestion();
        if (command === 'back' && phase !== 'entrance') {
          phase = phase === 'confirm' ? 'destination' : 'entrance';
          candidate = null;
          return announceCurrentQuestion();
        }
      }
    }

    switch (phase) {
      case 'entrance': return hearEntrance(heard);
      case 'destination': return hearDestination(heard);
      case 'confirm': return hearConfirmation(heard);
      case 'navigating': return hearNavigation(heard);
      default: return reply([], false);
    }
  }

  /** Nada foi ouvido (silencio, ruido ou erro passageiro do microfone). */
  function silence() {
    if (phase === 'idle') return reply([], false);
    silences++;
    if (silences >= MAX_SILENCE_RETRIES) {
      phase = 'idle';
      silences = 0;
      return reply(['Não ouvi nenhuma resposta. Toque no botão quando quiser continuar.'], false);
    }
    return reply(['Não ouvi. Pode repetir?']);
  }

  return {
    start,
    resume,
    stop,
    hear,
    silence,
    get phase() { return phase; },
    get steps() { return steps; },
  };
}
