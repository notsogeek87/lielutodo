// Client Vikunja (fetch). Le serveur est appelé EN DIRECT dès qu'il l'autorise (CORS : VIKUNJA_CORS_ORIGINS), pour
// que jetons et tâches ne transitent pas par un tiers. Sinon, pour le serveur par défaut, repli sur la réécriture
// Vercel `/vk/*` (même origine : pas de CORS).
export const DEFAULT_SERVER = 'https://task.lielu.eu';
const PROXY_PREFIX = '/vk';
const TIMEOUT_MS = 20_000;
const SESSION_KEY = 'lielutodo.session';

export class ApiError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/** Panne réseau (serveur coupé, hors ligne) : à distinguer d'une réponse d'erreur du serveur. */
export class NetworkError extends Error {}

export const normalizeUrl = (input) => {
  let url = String(input || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`;
  return url.replace(/\/api\/v1$/, '');
};

export const session = {
  load() {
    try {
      return JSON.parse(localStorage.getItem(SESSION_KEY)) || null;
    } catch {
      return null;
    }
  },
  save(s) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
  },
  clear() {
    localStorage.removeItem(SESSION_KEY);
  },
};

const base = (s) => (s.viaProxy ? `${PROXY_PREFIX}/api/v1` : `${s.baseUrl}/api/v1`);

async function request(s, method, path, { query, body } = {}) {
  const qs = query ? `?${new URLSearchParams(query)}` : '';
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let res;
  try {
    res = await fetch(`${base(s)}${path}${qs}`, {
      method,
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        ...(s.token ? { Authorization: `Bearer ${s.token}` } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    throw new NetworkError(e.name === 'AbortError' ? 'Le serveur ne répond pas' : 'Serveur injoignable');
  } finally {
    clearTimeout(timer);
  }
  const text = await res.text();
  if (!res.ok) {
    let message = `Erreur ${res.status}`;
    try {
      message = JSON.parse(text).message || message;
    } catch { /* corps non JSON */ }
    throw new ApiError(res.status, message);
  }
  return { status: res.status, text, headers: res.headers, json: () => JSON.parse(text) };
}

/** Appel authentifié avec la session courante ; `onUnauthorized` est appelé sur un 401. */
export function createApi(getSession, onUnauthorized) {
  return async (method, path, opts) => {
    try {
      return await request(getSession(), method, path, opts);
    } catch (e) {
      if (e instanceof ApiError && e.code === 401) onUnauthorized?.();
      throw e;
    }
  };
}

/** Renvoie la session (jeton inclus). Un code TOTP manquant ou faux donne une ApiError 412. */
export async function login(serverUrl, username, password, totp) {
  const baseUrl = normalizeUrl(serverUrl);
  const body = { username, password, long_token: true };
  if (totp && totp.trim()) body.totp_passcode = totp.trim();
  const tryVia = async (viaProxy) => {
    const s = { baseUrl, viaProxy, token: null };
    const res = await request(s, 'POST', '/login', { body });
    return { ...s, token: res.json().token };
  };
  try {
    return await tryVia(false);
  } catch (e) {
    // Appel direct bloqué (CORS pas encore autorisé côté serveur : le navigateur n'envoie même pas la requête) :
    // repli sur la réécriture du site. Une vraie réponse du serveur (401, 412…) est définitive.
    const canProxy = baseUrl === DEFAULT_SERVER && location.protocol.startsWith('http');
    if (!(e instanceof NetworkError) || !canProxy) throw e;
  }
  return tryVia(true);
}

/**
 * Session passée par la réécriture : le serveur autorise-t-il maintenant l'appel direct ? Renvoie la session à
 * utiliser (directe si oui). Un échec n'a aucune conséquence : on reste sur la réécriture.
 */
export async function preferDirect(s) {
  if (!s?.viaProxy || !s.token) return s;
  try {
    await request({ ...s, viaProxy: false }, 'GET', '/user');
    return { ...s, viaProxy: false };
  } catch {
    return s;
  }
}

export async function fetchPaged(api, path, query = {}) {
  const all = [];
  for (let page = 1; ; page++) {
    const res = await api('GET', path, { query: { ...query, page, per_page: 100 } });
    all.push(...res.json());
    const total = Number(res.headers.get('x-pagination-total-pages') || 1);
    if (page >= total) return all;
  }
}
