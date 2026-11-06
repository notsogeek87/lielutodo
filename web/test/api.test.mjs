// Connexion : appel direct au serveur d'abord, réécriture Vercel seulement si le navigateur le bloque (CORS).
import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.location = { protocol: 'https:' };
const { DEFAULT_SERVER, login, preferDirect, createApi, ApiError, NetworkError } = await import('../js/api.js');

const NOT_DEPLOYED = { status: 404, html: true };

/**
 * Faux `fetch` : `direct` / `proxy` / `site` (chemins /api/v1 du site : connexion et renouvellement) = réponse ou
 * 'cors' (le navigateur rejette). `site` absent = réécriture pas encore déployée (page HTML 404).
 * `calls` ne garde pas la lecture du compte (GET …/user) faite après la connexion.
 */
function fakeFetch({ direct, proxy, site = NOT_DEPLOYED, user = { id: 7 } }) {
  const calls = [];
  globalThis.fetch = async (url) => {
    const kind = url.startsWith('/vk/') ? 'proxy' : url.startsWith('/api/v1/') ? 'site' : 'direct';
    const isUser = url.endsWith('/user');
    if (!isUser) calls.push(kind);
    let r = kind === 'proxy' ? proxy : kind === 'site' ? site : direct;
    if (r === 'cors') throw new TypeError('Failed to fetch');
    if (isUser && r.status < 400) r = { status: 200, body: user };
    const text = r.html ? '<!doctype html><p>404</p>' : JSON.stringify(r.body);
    return { ok: r.status < 400, status: r.status, text: async () => text, headers: new Map() };
  };
  return calls;
}

test('le serveur autorise l’appel direct : aucun passage par le site', async () => {
  const calls = fakeFetch({ direct: { status: 200, body: { token: 't' } }, proxy: 'cors' });
  const s = await login(DEFAULT_SERVER, 'moi', 'mdp', '');
  assert.equal(s.viaProxy, false);
  // Réécriture du site pas encore déployée : ancien chemin.
  assert.deepEqual(calls, ['site', 'direct']);
});

test('appel direct bloqué (CORS) : repli sur la réécriture du site', async () => {
  const calls = fakeFetch({ direct: 'cors', proxy: { status: 200, body: { token: 't' } } });
  const s = await login(DEFAULT_SERVER, 'moi', 'mdp', '');
  assert.equal(s.viaProxy, true);
  assert.deepEqual(calls, ['site', 'direct', 'proxy']);
});

test('mauvais mot de passe : réponse définitive, pas de second essai', async () => {
  const calls = fakeFetch({ direct: { status: 401, body: { message: 'Mauvais identifiants' } }, proxy: 'cors' });
  await assert.rejects(login(DEFAULT_SERVER, 'moi', 'faux', ''), (e) => e instanceof ApiError && e.code === 401);
  assert.deepEqual(calls, ['site', 'direct']);
});

test('un autre serveur n’utilise jamais la réécriture', async () => {
  const calls = fakeFetch({ direct: 'cors', proxy: { status: 200, body: { token: 't' } } });
  await assert.rejects(login('https://autre.example', 'moi', 'mdp', ''));
  assert.deepEqual(calls, ['direct']);
});

test('une session passée par le site bascule en direct dès que le serveur l’autorise', async () => {
  const s = { baseUrl: DEFAULT_SERVER, viaProxy: true, token: 't' };
  fakeFetch({ direct: 'cors', proxy: { status: 200, body: {} } });
  assert.equal(await preferDirect(s), s);
  fakeFetch({ direct: { status: 200, body: { id: 1 } }, proxy: 'cors' });
  assert.equal((await preferDirect(s)).viaProxy, false);
});

test('connexion par le site (cookie de renouvellement gardé), compte noté', async () => {
  const calls = fakeFetch({ site: { status: 200, body: { token: 't' } }, direct: 'cors', proxy: { status: 200, body: {} } });
  const s = await login(DEFAULT_SERVER, 'moi', 'mdp', '');
  assert.deepEqual(calls, ['site']);
  assert.equal(s.cookieRefresh, true);
  assert.equal(s.viaProxy, true);
  assert.equal(s.userId, 7);
});

test('mauvais mot de passe par le site : pas d’autre essai', async () => {
  const calls = fakeFetch({ site: { status: 412, body: { message: 'Wrong username or password' } }, direct: 'cors' });
  await assert.rejects(login(DEFAULT_SERVER, 'moi', 'faux', ''), (e) => e instanceof ApiError && e.code === 412);
  assert.deepEqual(calls, ['site']);
});

/** Faux serveur : jeton « vieux » refusé (401), renouvellement par le site selon `refresh`. */
function expiringServer(refresh) {
  const log = [];
  globalThis.fetch = async (url, opts) => {
    const auth = opts.headers.Authorization;
    if (url === '/api/v1/user/token/refresh') {
      log.push('refresh');
      if (refresh === 'offline') throw new TypeError('Failed to fetch');
      return refresh === 'ok'
        ? { ok: true, status: 200, text: async () => JSON.stringify({ token: 'neuf' }), headers: new Map() }
        : { ok: false, status: 401, text: async () => '{"message":"no cookie"}', headers: new Map() };
    }
    log.push(`${opts.method} ${auth}`);
    const ok = auth === 'Bearer neuf';
    return { ok, status: ok ? 200 : 401, text: async () => (ok ? '[]' : '{"message":"expired"}'), headers: new Map() };
  };
  return log;
}

test('jeton expiré : renouvelé en silence (un seul renouvellement pour des appels simultanés), appel rejoué', async () => {
  const log = expiringServer('ok');
  let sess = { baseUrl: DEFAULT_SERVER, viaProxy: false, token: 'vieux', cookieRefresh: true };
  let expired = 0;
  const api = createApi(() => sess, () => { expired++; }, (s) => { sess = s; });
  const [a, b] = await Promise.all([api('GET', '/tasks'), api('GET', '/projects')]);
  assert.equal(a.status, 200);
  assert.equal(b.status, 200);
  assert.equal(sess.token, 'neuf');
  assert.equal(log.filter((l) => l === 'refresh').length, 1);
  assert.equal(expired, 0);
});

test('renouvellement refusé : reconnexion demandée', async () => {
  expiringServer('refused');
  const sess = { baseUrl: DEFAULT_SERVER, viaProxy: false, token: 'vieux', cookieRefresh: true };
  let expired = 0;
  const api = createApi(() => sess, () => { expired++; }, () => {});
  await assert.rejects(api('GET', '/tasks'), (e) => e instanceof ApiError && e.code === 401);
  assert.equal(expired, 1);
});

test('renouvellement impossible faute de réseau : hors ligne, pas déconnecté', async () => {
  expiringServer('offline');
  const sess = { baseUrl: DEFAULT_SERVER, viaProxy: false, token: 'vieux', cookieRefresh: true };
  let expired = 0;
  const api = createApi(() => sess, () => { expired++; }, () => {});
  await assert.rejects(api('GET', '/tasks'), (e) => e instanceof NetworkError);
  assert.equal(expired, 0);
});

test('session fermée : plus rien ne part', async () => {
  const log = expiringServer('ok');
  const api = createApi(() => null, () => {}, () => {});
  await assert.rejects(api('POST', '/tasks/1'), (e) => e instanceof NetworkError);
  assert.deepEqual(log, []);
});
