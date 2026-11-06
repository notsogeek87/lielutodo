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
  // Session fermée (expirée, déconnexion) : rien ne part, comme hors ligne.
  if (!s) throw new NetworkError('Session fermée');
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
        // Déclaré même sans corps (DELETE d'une affectation…), comme VikunjaClient.kt : Vikunja refuse un autre type (415).
        ...(body || method !== 'GET' ? { 'Content-Type': 'application/json' } : {}),
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

// Vikunja 2.x : le jeton de connexion ne dure que quelques minutes ; un cookie HttpOnly (30 jours, chemin
// /api/v1/user/token/refresh, sans domaine imposé) en donne un nouveau. Pour que le navigateur le garde et le renvoie,
// connexion ET renouvellement passent par ce même chemin sur le site (réécriture Vercel vers le serveur par défaut).
const SAME_ORIGIN_API = '/api/v1';
const canUseSameOrigin = (baseUrl) =>
  baseUrl === DEFAULT_SERVER && typeof location !== 'undefined' && location.protocol.startsWith('http');

async function sameOrigin(method, path, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let res;
  try {
    res = await fetch(`${SAME_ORIGIN_API}${path}`, {
      method,
      signal: controller.signal,
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    throw new NetworkError(e.name === 'AbortError' ? 'Le serveur ne répond pas' : 'Serveur injoignable');
  } finally {
    clearTimeout(timer);
  }
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* page HTML : réécriture absente */ }
  if (!res.ok) throw new ApiError(res.status, json?.message || `Erreur ${res.status}`);
  if (!json) throw new ApiError(404, 'Réponse inattendue');
  return json;
}

/**
 * Nouveau jeton à partir du cookie de renouvellement. Null si impossible (pas de cookie, refusé : il faut se
 * reconnecter) ; une panne réseau remonte telle quelle (hors ligne : on garde la session).
 */
export async function refreshToken(s) {
  if (!s?.cookieRefresh || !canUseSameOrigin(s.baseUrl)) return null;
  try {
    return (await sameOrigin('POST', '/user/token/refresh')).token || null;
  } catch (e) {
    if (e instanceof ApiError && e.code < 500) return null;
    throw e;
  }
}

/**
 * Appel authentifié avec la session courante. Sur un 401, renouvelle le jeton en silence (un seul renouvellement à
 * la fois) et recommence une fois ; `onUnauthorized` n'est appelé que si c'est impossible.
 */
export function createApi(getSession, onUnauthorized, onSessionRenewed) {
  let renewing = null;
  const renew = (rejected) => {
    renewing ||= (async () => {
      const s = getSession();
      // Un autre appel a déjà obtenu un jeton neuf pendant que celui-ci attendait.
      if (s && s.token !== rejected) return true;
      const token = await refreshToken(s);
      if (!token) return false;
      onSessionRenewed?.({ ...s, token });
      return true;
    })().finally(() => { renewing = null; });
    return renewing;
  };
  return async (method, path, opts) => {
    const sent = getSession();
    try {
      return await request(sent, method, path, opts);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 401)) throw e;
      if (await renew(sent?.token)) {
        try {
          return await request(getSession(), method, path, opts);
        } catch (e2) {
          if (e2 instanceof ApiError && e2.code === 401) onUnauthorized?.();
          throw e2;
        }
      }
      onUnauthorized?.();
      throw e;
    }
  };
}

/** Renvoie la session (jeton inclus). Un code TOTP manquant ou faux donne une ApiError 412. */
export async function login(serverUrl, username, password, totp) {
  const baseUrl = normalizeUrl(serverUrl);
  const body = { username, password, long_token: true };
  if (totp && totp.trim()) body.totp_passcode = totp.trim();
  const withUser = async (s) => {
    // Compte connecté : sert à ne jamais envoyer les écritures en attente d'un autre compte (voir app.js).
    try { return { ...s, userId: (await request(s, 'GET', '/user')).json().id || 0 }; } catch { return s; }
  };
  if (canUseSameOrigin(baseUrl)) {
    try {
      const res = await sameOrigin('POST', '/login', body);
      // Les appels suivants passent par la réécriture /vk jusqu'à ce que l'appel direct soit autorisé (preferDirect).
      return withUser({ baseUrl, viaProxy: true, token: res.token, cookieRefresh: true });
    } catch (e) {
      // Réécriture pas encore déployée (404, page HTML) : ancien chemin. Une vraie réponse (401, 412…) est définitive.
      if (!(e instanceof ApiError && e.code === 404)) throw e;
    }
  }
  const tryVia = async (viaProxy) => {
    const s = { baseUrl, viaProxy, token: null };
    const res = await request(s, 'POST', '/login', { body });
    return withUser({ ...s, token: res.json().token });
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
