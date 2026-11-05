// Joplin Server : format d'item (port de JoplinParser.kt) et client du protocole de synchro (port de HttpJoplinApi.kt).
// Le mot de passe n'est JAMAIS stocké : il ouvre une session (POST /api/sessions) dont seul l'id est gardé.
export const DEFAULT_NOTES_SERVER = 'https://notes.lielu.eu';
const PROXY_PREFIX = '/jp';
const TIMEOUT_MS = 20_000;

export class JoplinError extends Error {
  constructor(message, status = null, code = null) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
/** Session absente ou expirée : il faut redemander le mot de passe. */
export class JoplinAuthError extends JoplinError {}
/** Serveur injoignable (hors ligne, coupé, CORS refusé). */
export class JoplinNetworkError extends JoplinError {}

// ---------- Format d'item ----------
const pad = (n, w = 2) => String(n).padStart(w, '0');
export const formatTime = (ms) => {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}.${pad(d.getUTCMilliseconds(), 3)}Z`;
};
const parseTime = (v) => {
  if (v == null || v === '') return 0;
  if (/^\d+$/.test(v)) { const n = Number(v); return n > 10_000_000_000 ? n : n * 1000; }
  const t = Date.parse(v);
  return Number.isNaN(t) ? 0 : t;
};

/** Contenu (lignes avant le bloc de propriétés) et propriétés dans l'ordre du fichier. */
function split(content) {
  const lines = String(content).split('\n');
  let i = lines.length - 1;
  while (i >= 0 && !lines[i].trim()) i--;
  const props = [];
  while (i >= 0 && lines[i].trim()) {
    const colon = lines[i].indexOf(':');
    if (colon <= 0) break;
    props.unshift([lines[i].slice(0, colon).trim(), lines[i].slice(colon + 1).trim()]);
    i--;
  }
  return { contentLines: i >= 0 ? lines.slice(0, i) : [], props: new Map(props) };
}

export function parseItem(content) {
  const { contentLines, props } = split(content);
  const type = Number(props.get('type_'));
  if (!props.has('type_') || Number.isNaN(type)) return null;
  const id = props.get('id') || '';
  if (!/^[0-9a-fA-F]{32}$/.test(id)) return null;
  const title = contentLines[0] ?? '';
  const bodyStart = contentLines.length > 1 && !contentLines[1].trim() ? 2 : 1;
  const body = bodyStart >= contentLines.length ? '' : contentLines.slice(bodyStart).join('\n');
  return {
    id, type, title, body,
    parentId: props.get('parent_id') || '',
    updatedTime: parseTime(props.get('updated_time')),
    deletedTime: parseTime(props.get('deleted_time')),
    isTodo: props.get('is_todo') === '1',
    encrypted: props.get('encryption_applied') === '1',
    shareId: props.get('share_id') || '',
  };
}

// PAS de retour à la ligne final : Joplin lit les propriétés depuis la dernière ligne et s'arrête à la première ligne
// vide (vérifié contre Joplin Server : sinon « Missing required property: type_ »).
const assemble = (title, body, props) => (body ? `${title}\n\n${body}\n\n${props}` : `${title}\n\n${props}`);

/** Nouvelle note. */
export function serializeNote({ id, parentId, title, body, updatedTime, createdTime = updatedTime, shareId = '' }) {
  const c = formatTime(createdTime);
  const u = formatTime(updatedTime);
  return assemble(title, body, [
    `id: ${id}`, `parent_id: ${parentId}`, `created_time: ${c}`, `updated_time: ${u}`, 'is_conflict: 0', 'author: ',
    'source_url: ', 'is_todo: 0', 'todo_due: 0', 'todo_completed: 0', 'source: lielutodo-web',
    'source_application: eu.lielu.todo.web', 'application_data: ', 'order: 0', `user_created_time: ${c}`,
    `user_updated_time: ${u}`, 'encryption_cipher_text: ', 'encryption_applied: 0', 'markup_language: 1',
    'is_shared: 0', `share_id: ${shareId}`, 'type_: 1',
  ].join('\n'));
}

/** Nouveau carnet. */
export function serializeFolder({ id, title, parentId = '', updatedTime, createdTime = updatedTime, shareId = '' }) {
  const c = formatTime(createdTime);
  const u = formatTime(updatedTime);
  return assemble(title, '', [
    `id: ${id}`, `created_time: ${c}`, `updated_time: ${u}`, `user_created_time: ${c}`, `user_updated_time: ${u}`,
    'encryption_cipher_text: ', 'encryption_applied: 0', `parent_id: ${parentId}`, 'is_shared: 0',
    `share_id: ${shareId}`, 'type_: 2',
  ].join('\n'));
}

/**
 * Item existant modifié : titre, corps, carnet et dates remplacés, TOUTES les autres propriétés gardées (to-do,
 * échéance, auteur…). `deletedTime` = mise à la corbeille Joplin ; `shareId` (si fourni) = partage du carnet.
 */
export function patchItem(raw, { title, body, parentId, updatedTime, deletedTime = null, shareId = null }) {
  const { props } = split(raw);
  const u = formatTime(updatedTime);
  props.set('parent_id', parentId);
  props.set('updated_time', u);
  props.set('user_updated_time', u);
  if (deletedTime != null) props.set('deleted_time', String(deletedTime));
  if (shareId != null) props.set('share_id', shareId);
  const type = props.get('type_') ?? '1';
  props.delete('type_');
  props.set('type_', type);
  return assemble(title, body, [...props].map(([k, v]) => `${k}: ${v}`).join('\n'));
}

export const newId = () => {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
};

// ---------- Client ----------
export const normalizeNotesUrl = (input) => {
  let url = String(input || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`;
  return url.replace(/\/api$/, '');
};

/**
 * Joplin Server n'autorise le CORS que pour https://joplinapp.org (vérifié : en-tête Access-Control-Allow-Origin, et
 * X-API-AUTH absent des en-têtes permis) : depuis ce site, l'appel direct est TOUJOURS bloqué par le navigateur. Le
 * serveur par défaut passe donc par la réécriture `/jp/*` du site (vercel.json) ; en développement local, le faux
 * serveur relaie `/jp/*` vers JOPLIN_DEV_URL.
 */
const useProxy = (baseUrl) => {
  if (typeof location === 'undefined' || !location.protocol.startsWith('http')) return false;
  return normalizeNotesUrl(baseUrl) === DEFAULT_NOTES_SERVER || ['localhost', '127.0.0.1'].includes(location.hostname);
};

async function rawRequest(conn, method, path, { json, raw, auth = true } = {}) {
  const base = conn.viaProxy ? `${PROXY_PREFIX}/api` : `${conn.baseUrl}/api`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let res;
  try {
    res = await fetch(`${base}${path}`, {
      method,
      signal: controller.signal,
      headers: {
        ...(auth && conn.sessionId ? { 'X-API-AUTH': conn.sessionId, 'X-API-MIN-VERSION': '2.6.0' } : {}),
        ...(json ? { 'Content-Type': 'application/json' } : {}),
        ...(raw != null ? { 'Content-Type': 'application/octet-stream' } : {}),
      },
      body: json ? JSON.stringify(json) : raw ?? undefined,
    });
  } catch (e) {
    throw new JoplinNetworkError(e.name === 'AbortError' ? 'Le serveur Joplin ne répond pas' : 'Serveur Joplin injoignable');
  } finally {
    clearTimeout(timer);
  }
  const text = await res.text();
  if (!res.ok) {
    let detail = '';
    let code = null;
    try { const j = JSON.parse(text); detail = j.error || ''; code = j.code || null; } catch { detail = text.slice(0, 200); }
    if (res.status === 401 || res.status === 403) {
      if (auth && path !== '/sessions') throw new JoplinAuthError('Session Joplin expirée : reconnectez-vous.', res.status);
    }
    if (res.status === 429) throw new JoplinError('Trop de tentatives de connexion au serveur Joplin : réessayez dans une minute.', 429);
    throw new JoplinError(detail || `Joplin a répondu ${res.status}`, res.status, code);
  }
  return text;
}

/**
 * Ouvre une session. Renvoie `{ baseUrl, viaProxy, email, sessionId }` (sans mot de passe). Appel direct d'abord, puis
 * repli sur la réécriture du site (`/jp/*`) si le serveur par défaut refuse le CORS.
 */
export async function joplinLogin(serverUrl, email, password) {
  const baseUrl = normalizeNotesUrl(serverUrl);
  const attempt = async (viaProxy) => {
    const conn = { baseUrl, viaProxy, email, sessionId: null };
    const ping = await rawRequest(conn, 'GET', '/ping', { auth: false });
    if (!ping.includes('Joplin')) throw new JoplinError(`Cette adresse ne répond pas comme un serveur Joplin (${ping.slice(0, 60)})`);
    let text;
    try {
      text = await rawRequest(conn, 'POST', '/sessions', { json: { email, password }, auth: false });
    } catch (e) {
      if (e.status === 403 || e.status === 401) throw new JoplinAuthError('E-mail ou mot de passe Joplin incorrect.', e.status);
      throw e;
    }
    return { ...conn, sessionId: JSON.parse(text).id };
  };
  if (useProxy(baseUrl)) return attempt(true);
  try {
    return await attempt(false);
  } catch (e) {
    if (e instanceof JoplinNetworkError) {
      throw new JoplinNetworkError('Serveur Joplin injoignable depuis ce site (il n’autorise pas les appels d’autres sites).');
    }
    throw e;
  }
}

/** Client lié à une session ouverte. */
export function createJoplinApi(conn) {
  const req = (method, path, opts) => rawRequest(conn, method, path, opts);
  const json = async (method, path, opts) => { const t = await req(method, path, opts); return t ? JSON.parse(t) : {}; };
  const page = (j) => ({
    items: (j.items || []).map((c) => ({ name: c.item_name || c.name || '', type: c.type ?? 1, updatedTime: c.updated_time || 0 })),
    hasMore: !!j.has_more,
    cursor: j.cursor || null,
  });
  return {
    ping: () => req('GET', '/ping', { auth: false }),
    delta: async (cursor) => page(await json('GET', `/items/root:/:/delta${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`)),
    rootChildren: async (p) => page(await json('GET', `/items/root:/:/children?limit=100&page=${p}`)),
    itemContent: (name) => req('GET', `/items/root:/${name}:/content`),
    putContent: (name, content) => json('PUT', `/items/root:/${name}:/content`, { raw: content }),
    deleteItem: (name) => req('DELETE', `/items/root:/${name}:`),
    createShare: async (folderId) => (await json('POST', '/shares', { json: { folder_id: folderId } })).id,
    addShareRecipient: (shareId, email) => json('POST', `/shares/${shareId}/users`, { json: { email: email.trim() } }),
    shareRecipients: async (shareId) => ((await json('GET', `/shares/${shareId}/users`)).items || [])
      .map((i) => ({ shareUserId: i.id, email: i.user?.email || '', status: shareStatus(i.status) })),
    removeShareRecipient: (id) => req('DELETE', `/share_users/${id}`),
    deleteShare: (shareId) => req('DELETE', `/shares/${shareId}`),
    invitations: async () => ((await json('GET', '/share_users')).items || []).map((i) => ({
      shareUserId: i.id,
      status: shareStatus(i.status),
      folderId: i.share?.folder_id || '',
      ownerName: i.share?.user?.full_name || '',
      ownerEmail: i.share?.user?.email || '',
    })),
    respondToInvitation: (id, accept) => json('PATCH', `/share_users/${id}`, { json: { status: accept ? 1 : 2 } }),
  };
}

const shareStatus = (code) => (code === 1 ? 'accepted' : code === 2 ? 'rejected' : 'pending');
