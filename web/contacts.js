// Contatos de ajuda. Os telefones sao dados pessoais: ficam so no armazenamento local do navegador
// e nunca vao para o repositorio, para o mapa ou para o registro da conversa.

/** Papeis fixos. `rotulo` e como a voz fala ("falar com o atendente"). */
export const ROLES = [
  { id: 'atendente', nome: 'atendente', rotulo: 'o atendente', aliases: ['atendente', 'atendimento'] },
  { id: 'seguranca', nome: 'segurança', rotulo: 'a segurança', aliases: ['seguranca', 'vigilante', 'guarda'] },
];

export const STORAGE_KEY = 'sonaruna.contatos';

/**
 * Devolve o telefone so com + e digitos, ou null se nao parecer um telefone.
 * Sem "+", 10 ou 11 digitos sao tratados como numero brasileiro (DDD + numero) e ganham +55.
 */
export function normalizePhone(text) {
  const raw = String(text ?? '').trim();
  if (!/^\+?[\d\s().-]+$/.test(raw)) return null;
  const digits = raw.replace(/\D/g, '');
  if (raw.startsWith('+')) return digits.length >= 8 && digits.length <= 15 ? `+${digits}` : null;
  if (digits.length === 10 || digits.length === 11) return `+55${digits}`;
  if ((digits.length === 12 || digits.length === 13) && digits.startsWith('55')) return `+${digits}`;
  return null;
}

/** "final 1234": e o que aparece na tela e no registro, nunca o numero inteiro. */
export function maskPhone(phone) {
  return `final ${String(phone).slice(-4)}`;
}

/** Le { atendente: '+55...', seguranca: '' } do armazenamento; qualquer problema vira "sem contatos". */
export function loadStoredPhones(storage) {
  try {
    const parsed = JSON.parse(storage.getItem(STORAGE_KEY) ?? '{}');
    const phones = {};
    for (const role of ROLES) phones[role.id] = normalizePhone(parsed?.[role.id]) ?? '';
    return phones;
  } catch {
    return Object.fromEntries(ROLES.map((role) => [role.id, '']));
  }
}

/** Guarda os telefones digitados. Devolve { ok, erros } e nao grava nada se algum numero for invalido. */
export function savePhones(storage, typed) {
  const phones = {};
  const errors = [];
  for (const role of ROLES) {
    const text = String(typed?.[role.id] ?? '').trim();
    if (!text) {
      phones[role.id] = '';
      continue;
    }
    const phone = normalizePhone(text);
    if (phone) phones[role.id] = phone;
    else errors.push(`O telefone de ${role.nome} não parece válido. Use DDD e número, por exemplo 16 99999-9999.`);
  }
  if (errors.length > 0) return { ok: false, erros: errors };
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(phones));
  } catch {
    return { ok: false, erros: ['Não foi possível guardar os telefones neste navegador.'] };
  }
  return { ok: true, erros: [] };
}

/**
 * Contatos prontos para a conversa: so os papeis que tem telefone.
 * O telefone vindo do .env (via servidor) vale mais que o digitado na pagina.
 */
export function readContacts(storage, envPhones = {}) {
  const stored = loadStoredPhones(storage);
  return ROLES
    .map((role) => ({ ...role, telefone: normalizePhone(envPhones?.[role.id]) || stored[role.id] }))
    .filter((contact) => contact.telefone);
}

/**
 * 'real' quando a pagina esta num celular (o link tel: abre o discador); 'simulada' no computador, que nao liga.
 * `?ligacao=real` ou `?ligacao=simulada` na URL forca um dos dois, para testes.
 */
export function callMode(userAgent, search = '') {
  const forced = new URLSearchParams(search).get('ligacao');
  if (forced === 'real' || forced === 'simulada') return forced;
  return /Android|iPhone|iPad|iPod|Mobile/i.test(userAgent ?? '') ? 'real' : 'simulada';
}
