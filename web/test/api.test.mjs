// Connexion : appel direct au serveur d'abord, réécriture Vercel seulement si le navigateur le bloque (CORS).
import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.location = { protocol: 'https:' };
const { DEFAULT_SERVER, login, preferDirect, ApiError } = await import('../js/api.js');

/** Faux `fetch` : `direct` / `proxy` = réponse ou 'cors' (le navigateur rejette, comme sans en-têtes CORS). */
function fakeFetch({ direct, proxy }) {
  const calls = [];
  globalThis.fetch = async (url) => {
    const viaProxy = url.startsWith('/vk/');
    calls.push(viaProxy ? 'proxy' : 'direct');
    const r = viaProxy ? proxy : direct;
    if (r === 'cors') throw new TypeError('Failed to fetch');
    return { ok: r.status < 400, status: r.status, text: async () => JSON.stringify(r.body), headers: new Map() };
  };
  return calls;
}

test('le serveur autorise l’appel direct : aucun passage par le site', async () => {
  const calls = fakeFetch({ direct: { status: 200, body: { token: 't' } }, proxy: 'cors' });
  const s = await login(DEFAULT_SERVER, 'moi', 'mdp', '');
  assert.equal(s.viaProxy, false);
  assert.deepEqual(calls, ['direct']);
});

test('appel direct bloqué (CORS) : repli sur la réécriture du site', async () => {
  const calls = fakeFetch({ direct: 'cors', proxy: { status: 200, body: { token: 't' } } });
  const s = await login(DEFAULT_SERVER, 'moi', 'mdp', '');
  assert.equal(s.viaProxy, true);
  assert.deepEqual(calls, ['direct', 'proxy']);
});

test('mauvais mot de passe : réponse définitive, pas de second essai', async () => {
  const calls = fakeFetch({ direct: { status: 401, body: { message: 'Mauvais identifiants' } }, proxy: 'cors' });
  await assert.rejects(login(DEFAULT_SERVER, 'moi', 'faux', ''), (e) => e instanceof ApiError && e.code === 401);
  assert.deepEqual(calls, ['direct']);
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
