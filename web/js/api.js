// Client Vikunja (fetch). Sur le site déployé, le serveur par défaut est joint via la réécriture Vercel `/vk/*`
// (même origine : pas de CORS). Sans elle (dev local, autre serveur), on tombe sur l'appel direct.
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
  if (baseUrl === DEFAULT_SERVER && location.protocol.startsWith('http')) {
    try {
      return await tryVia(true);
    } catch (e) {
      // 404/405/HTML = pas de réécriture ici (dev local) ; une vraie réponse du serveur (401, 412…) est définitive.
      const noProxy = e instanceof NetworkError || (e instanceof ApiError && [404, 405, 502, 504].includes(e.code));
      if (!noProxy) throw e;
    }
  }
  return tryVia(false);
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
