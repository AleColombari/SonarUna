// Conversa do SonarUna: entrada -> destino -> confirmacao -> instrucoes passo a passo.
// Nao fala nem ouve: recebe o que foi ouvido e devolve o que deve ser dito. Quem fala e ouve e o app.js.

import {
  ateLugar, buildInstructions, classifyCommand, classifyYesNo, emLugar, findEntrance, findPlace, paraLugar, planRoute, roundSteps,
} from './engine.js';

export const MAX_SILENCE_RETRIES = 3;

/**
 * Cada metodo devolve { say: string[], listen: boolean, phase }.
 * `say` e dito em ordem; se `listen` for verdadeiro o app abre o microfone depois da ultima frase.
 */
export function createSession(map) {
  let phase = 'idle';
  let entrance = null;
  let destination = null;
  let candidate = null;
  let steps = [];
  let index = 0;
  let silences = 0;

  const reply = (say, listen = true) => ({ say, listen, phase });
  const entranceNames = () => map.entrances.map((e) => e.nome).join(' ou ');
  const stepLine = (i) => `Etapa ${i + 1} de ${steps.length}. ${steps[i].texto}`;

  function start() {
    phase = 'entrance';
    entrance = destination = candidate = null;
    steps = [];
    index = 0;
    silences = 0;
    return reply([
      `Olá. Eu sou o SonarUna, no bloco ${map.bloco}.`,
      `Por qual entrada você entrou? Diga ${entranceNames()}.`,
    ]);
  }

  function stop() {
    phase = 'idle';
    return reply(['Orientação encerrada. Toque no botão para começar de novo.'], false);
  }

  function help() {
    const common = 'Você pode dizer a qualquer momento: repetir, voltar, recomeçar ou parar.';
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
      default: return reply([], false);
    }
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
    const total = roundSteps(steps.reduce((sum, step) => sum + step.passos, 0));
    return reply([
      `Rota ${ateLugar(destination)}: cerca de ${total} passos, em ${steps.length} ${steps.length === 1 ? 'etapa' : 'etapas'}.`,
      stepLine(0),
    ]);
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
    stop,
    hear,
    silence,
    get phase() { return phase; },
    get steps() { return steps; },
  };
}
