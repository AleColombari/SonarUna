// Interface web do SonarUna: ouve pelo microfone, fala com a voz do navegador e usa o mapa em data/bloco-a.json.

import { loadMap, normalize } from './engine.js';
import { createSession } from './conversation.js';
import { ROLES, callMode, loadStoredPhones, maskPhone, readContacts, savePhones } from './contacts.js';

const SPEECH_START_DELAY_MS = 150;
// Um pouco mais rapido que o padrao do navegador (1,0), que soa arrastado nas instrucoes longas.
const SPEECH_RATE = 1.2;
// Fracao de palavras ouvidas que, se tambem estao na fala do app, indica que o microfone pegou o alto-falante.
const ECHO_RATIO = 0.6;
const DEBUG = new URLSearchParams(window.location.search).has('teste');

const els = {
  mic: document.getElementById('mic'),
  transcript: document.getElementById('transcript'),
  debug: document.getElementById('debug'),
  status: document.getElementById('status'),
  log: document.getElementById('log'),
  notice: document.getElementById('notice'),
  support: document.getElementById('support'),
  form: document.getElementById('typed'),
  typed: document.getElementById('typed-text'),
  call: document.getElementById('call'),
  callText: document.getElementById('call-text'),
  callLink: document.getElementById('call-link'),
  contactsForm: document.getElementById('contacts'),
  contactsMessage: document.getElementById('contacts-message'),
};

const SpeechRecognitionApi = window.SpeechRecognition || window.webkitSpeechRecognition;
const synthesis = window.speechSynthesis;

let session = null;
let recognition = null;
let voice = null;
let generation = 0;
let state = 'loading';
let micBlocked = false;
let envPhones = {};
// Fala atual do app: permite interromper e reconhecer o eco do alto-falante.
let speech = { interrupted: false, words: new Set(), active: false };

const STATES = {
  loading: { status: 'Carregando o mapa…', aria: 'Carregando' },
  idle: { status: 'Toque no botão para começar.', aria: 'Começar orientação' },
  speaking: { status: 'Falando… você pode responder por cima.', aria: 'Parar orientação. Falando' },
  listening: { status: 'Ouvindo… pode falar.', aria: 'Parar orientação. Ouvindo' },
  typing: { status: 'Sem microfone. Digite sua resposta abaixo.', aria: 'Parar orientação' },
};

function setState(next, statusOverride) {
  state = next;
  const info = STATES[next];
  els.mic.dataset.state = next;
  els.mic.disabled = next === 'loading';
  els.mic.setAttribute('aria-label', info.aria);
  els.status.textContent = statusOverride ?? info.status;
  els.form.hidden = next !== 'typing' && !DEBUG;
  if (next === 'listening' && !('live' in els.transcript.dataset)) showTranscript('Ouvindo…', false);
}

/** Mostra abaixo do botao o que o microfone esta ouvindo; live = texto vindo da fala da pessoa. */
function showTranscript(text, live) {
  els.transcript.textContent = text;
  if (live) els.transcript.dataset.live = '';
  else delete els.transcript.dataset.live;
}

function addLog(who, text) {
  const item = document.createElement('li');
  item.dataset.who = who;
  item.textContent = `${who === 'you' ? 'Você' : 'SonarUna'}: ${text}`;
  els.log.append(item);
  els.log.scrollTop = els.log.scrollHeight;
}

// --- Voz (sintese) ---------------------------------------------------------------------------

function pickVoice() {
  const voices = synthesis?.getVoices() ?? [];
  voice = voices.find((v) => v.lang === 'pt-BR') ?? voices.find((v) => v.lang.startsWith('pt')) ?? null;
}

function speakSentence(text) {
  return new Promise((resolve) => {
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'pt-BR';
    utterance.rate = SPEECH_RATE;
    if (voice) utterance.voice = voice;
    utterance.onend = utterance.onerror = () => resolve();
    synthesis.speak(utterance);
  });
}

// O Chrome interrompe falas longas; falar frase por frase evita isso.
async function speak(text, gen) {
  for (const sentence of text.split(/(?<=[.!?])\s+/)) {
    if (gen !== generation || speech.interrupted) return;
    if (!sentence.trim()) continue;
    // So o que o app ja comecou a dizer pode voltar como eco pelo microfone.
    for (const word of normalize(sentence).split(' ')) speech.words.add(word);
    await speakSentence(sentence);
  }
}

// --- Fluxo -----------------------------------------------------------------------------------

async function deliver(result, gen) {
  const current = { interrupted: false, words: new Set(), active: true };
  speech = current;
  setState('speaking');
  // Quando o app espera resposta, o microfone ja escuta enquanto ele fala: a pessoa pode responder por cima.
  if (result.listen && !result.action && SpeechRecognitionApi && !micBlocked) listen(gen, true);
  for (const line of result.say) {
    if (gen !== generation || current.interrupted) break;
    addLog('app', line);
    await speak(line, gen);
  }
  current.active = false;
  // Interrompido: a escuta aberta durante a fala continua e entrega a resposta.
  if (gen !== generation || current.interrupted) return;
  if (result.action?.type === 'call') placeCall(result.action.contact);
  if (!result.listen) return setState('idle');
  stopRecognition();
  await new Promise((resolve) => setTimeout(resolve, SPEECH_START_DELAY_MS));
  if (gen === generation) listen(gen);
}

/** A pessoa comecou a falar: a fala dela tem prioridade sobre a do app. */
function interruptSpeech() {
  speech.interrupted = true;
  synthesis?.cancel();
  setState('listening');
}

/** O microfone ouviu o proprio app pelo alto-falante, e nao a pessoa. */
function isEcho(text) {
  const words = normalize(text).split(' ').filter(Boolean);
  if (words.length === 0) return true;
  const repeated = words.filter((word) => speech.words.has(word)).length;
  return repeated / words.length >= ECHO_RATIO;
}

function stopRecognition() {
  const attempt = recognition;
  recognition = null;
  try { attempt?.abort(); } catch { /* ja encerrado */ }
}

// --- Ligacao ---------------------------------------------------------------------------------

// Um computador nao disca sozinho: o link abre o aplicativo de chamadas do sistema, se houver um.
// No Android a ligacao sera automatica (ACTION_CALL, com a permissao CALL_PHONE).
function placeCall(contact) {
  const label = `${contact.nome} (${maskPhone(contact.telefone)})`;
  els.call.hidden = false;
  if (callMode(navigator.userAgent, window.location.search) === 'simulada') {
    els.callText.textContent = `SIMULAÇÃO: no celular o site ligaria agora para ${label}. Este computador não faz ligações.`;
    els.callLink.hidden = true;
    addLog('app', `Ligação simulada para ${label}.`);
    return;
  }
  els.callLink.hidden = false;
  els.callLink.href = `tel:${contact.telefone}`;
  els.callLink.textContent = `Ligar para ${label}`;
  els.callText.textContent = 'Se a ligação não abrir sozinha, toque no botão abaixo.';
  addLog('app', `Ligação pedida para ${label}.`);
  // O clique no proprio link permite interceptar a chamada em testes; o botao continua disponivel se for bloqueada.
  els.callLink.click();
}

function getContacts() {
  try {
    return readContacts(window.localStorage, envPhones);
  } catch {
    return readContacts({ getItem: () => null }, envPhones);
  }
}

async function loadEnvPhones() {
  try {
    const response = await fetch('/config.json', { cache: 'no-store' });
    if (response.ok) envPhones = await response.json();
  } catch {
    // Sem servidor de configuracao (site estatico): vale o que foi digitado na pagina.
  }
}

function fillContactsForm() {
  try {
    const phones = loadStoredPhones(window.localStorage);
    for (const role of ROLES) {
      const input = document.getElementById(`phone-${role.id}`);
      const fromEnv = Boolean(readContacts({ getItem: () => null }, envPhones).find((c) => c.id === role.id));
      input.disabled = fromEnv;
      input.placeholder = fromEnv ? 'Definido no arquivo .env' : '16 99999-9999';
      input.value = fromEnv ? '' : phones[role.id];
    }
  } catch {
    els.contactsMessage.textContent = 'Este navegador não permite guardar os telefones.';
  }
}

els.contactsForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const typed = Object.fromEntries(ROLES.map((role) => [role.id, document.getElementById(`phone-${role.id}`).value]));
  let result;
  try {
    result = savePhones(window.localStorage, typed);
  } catch {
    result = { ok: false, erros: ['Este navegador não permite guardar os telefones.'] };
  }
  els.contactsMessage.textContent = result.ok ? 'Telefones guardados neste navegador.' : result.erros.join(' ');
  if (result.ok) fillContactsForm();
});

function handle(alternatives, gen) {
  addLog('you', alternatives[0]);
  showTranscript(alternatives[0], false);
  return deliver(session.hear(alternatives), gen);
}

/**
 * Uma resposta por tentativa. Com duringSpeech, a tentativa comeca enquanto o app fala e so assume a conversa
 * quando ouve a pessoa (nao o eco do alto-falante); se acabar antes, e reaberta enquanto a fala continuar.
 */
function listen(gen, duringSpeech = false) {
  if (!SpeechRecognitionApi || micBlocked) return setState('typing');
  const attempt = new SpeechRecognitionApi();
  recognition = attempt;
  attempt.lang = 'pt-BR';
  attempt.interimResults = true;
  attempt.continuous = false;
  attempt.maxAlternatives = 4;

  let heard = null;
  let failure = null;
  let userSpoke = !duringSpeech;
  attempt.onresult = (event) => {
    if (recognition !== attempt) return;
    const results = Array.from(event.results);
    const text = results.map((result) => result[0].transcript).join(' ').trim();
    if (!userSpoke) {
      if (isEcho(text)) return;
      userSpoke = true;
      interruptSpeech();
    }
    showTranscript(text, true);
    const final = results.find((result) => result.isFinal);
    if (final) heard = Array.from(final, (alternative) => alternative.transcript);
  };
  attempt.onerror = (event) => { failure = event.error; };
  attempt.onend = () => {
    if (gen !== generation || recognition !== attempt) return;
    recognition = null;
    const fatal = {
      'not-allowed': 'O microfone está bloqueado. Libere o microfone no ícone ao lado do endereço e toque no botão.',
      'service-not-allowed': 'O navegador não permitiu o reconhecimento de voz. Tente no Google Chrome.',
      'audio-capture': 'Não encontrei um microfone. Conecte um microfone ou digite abaixo.',
      'network': 'O reconhecimento de voz precisa de internet no Chrome. Verifique a conexão ou digite abaixo.',
      'language-not-supported': 'O reconhecimento em português do Brasil não está disponível neste navegador.',
    }[failure];
    if (fatal) {
      // A conversa continua pelo campo de texto; o microfone so volta a ser tentado ao tocar no botao.
      micBlocked = true;
      generation++;
      speech = { interrupted: false, words: new Set(), active: false };
      synthesis?.cancel();
      addLog('app', fatal);
      setState('typing', fatal);
      return void speak(fatal, generation);
    }
    if (!userSpoke) {
      // So ouviu silencio ou o eco do app: reabre enquanto ele fala; depois, deliver abre a tentativa normal.
      if (speech.active) {
        setTimeout(() => {
          if (gen === generation && speech.active && !recognition) listen(gen, true);
        }, 100);
      }
      return;
    }
    if (heard) return void handle(heard, gen);
    deliver(session.silence(), gen);
  };

  if (!duringSpeech) setState('listening');
  try {
    attempt.start();
  } catch {
    recognition = null;
    if (!duringSpeech) setState('typing');
  }
}

function cancelEverything() {
  generation++;
  speech.active = false;
  synthesis?.cancel();
  stopRecognition();
}

function begin() {
  micBlocked = false;
  cancelEverything();
  const gen = generation;
  els.call.hidden = true;
  // Depois de uma ligacao a conversa continua de onde parou; so recomeca se tinha acabado.
  deliver(session.phase === 'idle' ? session.start() : session.resume(), gen);
}

function end() {
  cancelEverything();
  session.stop();
  showTranscript('', false);
  setState('idle', 'Orientação encerrada.');
}

els.mic.addEventListener('click', () => {
  if (state === 'idle') begin();
  else if (state !== 'loading') end();
});

els.form.addEventListener('submit', (event) => {
  event.preventDefault();
  const text = els.typed.value.trim();
  els.typed.value = '';
  if (!text || !session) return;
  if (session.phase === 'idle') return begin();
  cancelEverything();
  handle([text], generation);
});

// --- Inicio ----------------------------------------------------------------------------------

async function init() {
  els.debug.hidden = !DEBUG;
  if (synthesis) {
    pickVoice();
    synthesis.addEventListener?.('voiceschanged', pickVoice);
  }
  const problems = [];
  if (!synthesis) problems.push('Este navegador não tem síntese de voz.');
  else if (!SpeechRecognitionApi) problems.push('Este navegador não reconhece voz. Use o Google Chrome ou o Edge. Você ainda pode digitar.');
  if (problems.length > 0) {
    els.support.textContent = problems.join(' ');
    els.support.hidden = false;
    // Fora do modo de teste, o aviso aparece abaixo do botao, no lugar da transcricao.
    showTranscript(problems.join(' '), false);
  }
  try {
    const response = await fetch('../data/bloco-a.json');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const map = loadMap(await response.json());
    await loadEnvPhones();
    session = createSession(map, getContacts);
    fillContactsForm();
    if (map.aviso) {
      els.notice.textContent = 'Dados de exemplo: o bloco A usado aqui é fictício.';
      els.notice.hidden = false;
    }
    setState('idle');
  } catch (error) {
    setState('loading', `Não foi possível carregar o mapa: ${error.message}`);
  }
}

init();
