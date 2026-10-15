// Interface web do SonarUna: ouve pelo microfone, fala com a voz do navegador e usa o mapa em data/bloco-a.json.

import { loadMap } from './engine.js';
import { createSession } from './conversation.js';
import { ROLES, callMode, loadStoredPhones, maskPhone, readContacts, savePhones } from './contacts.js';

const SPEECH_START_DELAY_MS = 250;

const els = {
  mic: document.getElementById('mic'),
  micLabel: document.getElementById('mic-label'),
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

const STATES = {
  loading: { label: 'Aguarde', status: 'Carregando o mapa…', aria: 'Carregando' },
  idle: { label: 'Começar', status: 'Toque no botão para começar.', aria: 'Começar orientação' },
  speaking: { label: 'Parar', status: 'Falando…', aria: 'Parar orientação. Falando' },
  listening: { label: 'Ouvindo', status: 'Ouvindo… pode falar.', aria: 'Parar orientação. Ouvindo' },
  typing: { label: 'Parar', status: 'Sem microfone. Digite sua resposta abaixo.', aria: 'Parar orientação' },
};

function setState(next, statusOverride) {
  state = next;
  const info = STATES[next];
  els.mic.dataset.state = next;
  els.mic.disabled = next === 'loading';
  els.mic.setAttribute('aria-label', info.aria);
  els.micLabel.textContent = info.label;
  els.status.textContent = statusOverride ?? info.status;
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
    if (voice) utterance.voice = voice;
    utterance.onend = utterance.onerror = () => resolve();
    synthesis.speak(utterance);
  });
}

// O Chrome interrompe falas longas; falar frase por frase evita isso.
async function speak(text, gen) {
  for (const sentence of text.split(/(?<=[.!?])\s+/)) {
    if (gen !== generation) return;
    if (sentence.trim()) await speakSentence(sentence);
  }
}

// --- Fluxo -----------------------------------------------------------------------------------

async function deliver(result, gen) {
  for (const line of result.say) {
    if (gen !== generation) return;
    addLog('app', line);
    setState('speaking');
    await speak(line, gen);
  }
  if (gen !== generation) return;
  if (result.action?.type === 'call') placeCall(result.action.contact);
  if (!result.listen) return setState('idle');
  await new Promise((resolve) => setTimeout(resolve, SPEECH_START_DELAY_MS));
  if (gen === generation) listen(gen);
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
  return deliver(session.hear(alternatives), gen);
}

function listen(gen) {
  if (!SpeechRecognitionApi || micBlocked) return setState('typing');
  const attempt = new SpeechRecognitionApi();
  recognition = attempt;
  attempt.lang = 'pt-BR';
  attempt.interimResults = false;
  attempt.continuous = false;
  attempt.maxAlternatives = 4;

  let heard = null;
  let failure = null;
  attempt.onresult = (event) => {
    heard = Array.from(event.results[0], (alternative) => alternative.transcript);
  };
  attempt.onerror = (event) => { failure = event.error; };
  attempt.onend = () => {
    if (gen !== generation) return;
    if (heard) return void handle(heard, gen);
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
      addLog('app', fatal);
      setState('typing', fatal);
      return void speak(fatal, generation);
    }
    deliver(session.silence(), gen);
  };

  setState('listening');
  try {
    attempt.start();
  } catch {
    setState('typing');
  }
}

function cancelEverything() {
  generation++;
  synthesis?.cancel();
  try { recognition?.abort(); } catch { /* ja encerrado */ }
  recognition = null;
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
