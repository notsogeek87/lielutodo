// Tests du store « local d'abord » contre un faux serveur Vikunja en mémoire (comme TodoRepositoryTest.kt).
import test from 'node:test';
import assert from 'node:assert/strict';

// Le store vit dans un navigateur : stockage et fenêtre minimaux.
class MemoryStorage {
  constructor() { this.items = new Map(); this.quota = Infinity; }
  getItem(k) { return this.items.has(k) ? this.items.get(k) : null; }
  setItem(k, v) {
    const size = [...this.items].filter(([key]) => key !== k).reduce((n, [, x]) => n + x.length, 0) + String(v).length;
    if (size > this.quota) throw new Error('QuotaExceededError');
    this.items.set(k, String(v));
  }
  removeItem(k) { this.items.delete(k); }
}
globalThis.localStorage = new MemoryStorage();
globalThis.window = { addEventListener() {} };

const { createStore } = await import('../js/store.js');
const { ApiError, NetworkError } = await import('../js/api.js');

function fakeServer() {
  const srv = {
    online: true,
    tasks: new Map(),
    nextId: 100,
    labelCalls: 0,
    assigned: [],
    /** Statut d'erreur à renvoyer pour « MÉTHODE chemin », ou undefined. */
    refuse: () => undefined,
    onCall: () => {},
  };
  const res = (body) => ({
    status: 200, text: JSON.stringify(body), headers: { get: () => '1' }, json: () => JSON.parse(JSON.stringify(body)),
  });
  srv.api = async (method, path, opts = {}) => {
    const key = `${method} ${path}`;
    srv.onCall(key);
    if (!srv.online) throw new NetworkError('Serveur injoignable');
    const code = srv.refuse(key);
    if (code) throw new ApiError(code, 'refusé');
    const body = opts.body;
    let m;
    if (key === 'GET /projects') return res([{ id: 1, title: 'Boîte de réception' }, { id: 2, title: 'Perso' }]);
    if (key === 'GET /labels') return res([{ id: 10, title: 'urgent' }]);
    if (key === 'GET /user') return res({ id: 1, username: 'moi', settings: { default_project_id: 1 } });
    if (key === 'GET /tasks') {
      const done = String(opts.query?.filter || '').startsWith('done = true');
      return res([...srv.tasks.values()].filter((t) => !!t.done === done));
    }
    if ((m = key.match(/^PUT \/projects\/(\d+)\/tasks$/))) {
      const t = { ...body, id: srv.nextId++, done: false, project_id: Number(m[1]) };
      srv.tasks.set(t.id, t);
      return res(t);
    }
    if ((m = key.match(/^POST \/tasks\/(\d+)$/))) {
      const id = Number(m[1]);
      if (!srv.tasks.has(id)) throw new ApiError(404, 'introuvable');
      // Comme Vikunja : une mise à jour sans `assignees` les efface.
      const t = { ...body, assignees: body.assignees || [] };
      srv.tasks.set(id, t);
      return res(t);
    }
    if ((m = key.match(/^PUT \/tasks\/(\d+)\/assignees$/))) {
      srv.assigned.push(`${m[1]}>${body.user_id}`);
      return res({});
    }
    if (key.endsWith('/labels/bulk')) { srv.labelCalls++; return res({}); }
    if (key === 'PUT /projects') return res({ id: 50, title: body.title });
    if (key === 'PUT /labels') return res({ id: 60, title: body.title });
    throw new ApiError(404, `route inconnue ${key}`);
  };
  return srv;
}

const fresh = () => {
  globalThis.localStorage = new MemoryStorage();
  const srv = fakeServer();
  return { srv, store: createStore(srv.api) };
};

test('une écriture refusée est mise de côté sans bloquer les suivantes, puis renvoyée', async () => {
  const { srv, store } = fresh();
  srv.tasks.set(5, { id: 5, title: 'Déjà là', project_id: 2, done: false });
  await store.refresh();
  srv.refuse = (k) => (k === 'PUT /projects/2/tasks' ? 400 : undefined);
  const id = store.addTask({ title: 'Refusée', projectId: 2 });
  store.setDone(5, true);
  await store.syncPending();
  assert.equal(store.state.ops.length, 0);
  assert.equal(store.state.failed.length, 1);
  assert.equal(store.state.failed[0].code, 400);
  assert.equal(srv.tasks.get(5).done, true);

  srv.tasks.set(6, { id: 6, title: 'Créée sur le web', project_id: 2, done: false });
  await store.refresh();
  assert.ok(store.state.tasks.some((t) => t.id === id && t.title === 'Refusée'));
  assert.ok(store.state.tasks.some((t) => t.id === 6));

  srv.refuse = () => undefined;
  store.retryFailed(store.state.failed[0]);
  await store.syncPending();
  assert.equal(store.state.failed.length, 0);
  assert.ok([...srv.tasks.values()].some((t) => t.title === 'Refusée'));
  assert.ok(store.state.tasks.every((t) => t.id > 0));
});

test('une erreur 500 répétée sur une seule écriture est mise de côté après quelques essais', async () => {
  const { srv, store } = fresh();
  srv.tasks.set(5, { id: 5, title: 'T', project_id: 2, done: false });
  await store.refresh();
  srv.refuse = (k) => (k === 'POST /tasks/5' ? 500 : undefined);
  store.updateTask({ ...store.task(5), title: 'Modifiée ici' });
  store.addTask({ title: 'Après', projectId: 2 });
  await store.syncPending();
  await store.syncPending();
  assert.equal(store.state.failed.length, 0);
  assert.equal(store.state.ops.length, 2);
  await store.syncPending();
  assert.equal(store.state.failed[0].code, 500);
  assert.ok([...srv.tasks.values()].some((t) => t.title === 'Après'));

  await store.refresh();
  assert.equal(store.task(5).title, 'Modifiée ici');
  await store.abandonFailed(store.state.failed[0]);
  assert.equal(store.task(5).title, 'T');
});

test('des erreurs 500 partout = serveur en panne : rien n’est mis de côté', async () => {
  const { srv, store } = fresh();
  srv.tasks.set(5, { id: 5, title: 'T', project_id: 2, done: false });
  await store.refresh();
  srv.refuse = () => 500;
  store.setDone(5, true);
  for (let i = 0; i < 5; i++) await store.syncPending();
  assert.equal(store.state.failed.length, 0);
  assert.equal(store.state.ops.length, 1);
  assert.equal(store.meta.offline, true);
});

test('abandonner un projet refusé range ses tâches dans la boîte de réception', async () => {
  const { srv, store } = fresh();
  await store.refresh();
  srv.refuse = (k) => (k === 'PUT /projects' ? 403 : undefined);
  const project = store.createProject('Interdit');
  const task = store.addTask({ title: 'À garder', projectId: project });
  await store.syncPending();
  assert.equal(store.state.failed.length, 2);

  srv.refuse = () => undefined;
  await store.abandonFailed(store.state.failed.find((f) => f.op.taskId === project));
  assert.ok(store.state.projects.every((p) => p.id !== project));
  assert.equal(store.task(task).projectId, 1);
  store.retryFailed(store.state.failed[0]);
  await store.syncPending();
  assert.equal(store.state.failed.length, 0);
  assert.ok([...srv.tasks.values()].some((t) => t.title === 'À garder' && t.project_id === 1));
});

test('une tâche créée dont les étiquettes sont refusées n’est jamais recréée', async () => {
  const { srv, store } = fresh();
  await store.refresh();
  srv.refuse = (k) => (k.endsWith('/labels/bulk') ? 400 : undefined);
  store.addTask({ title: 'Étiquetée', projectId: 2, labels: [{ id: 10, title: 'urgent', hex: '' }] });
  await store.syncPending();
  assert.equal(store.state.failed.length, 1);
  assert.equal(srv.tasks.size, 1);

  srv.refuse = () => undefined;
  store.retryFailed(store.state.failed[0]);
  await store.syncPending();
  assert.equal(srv.tasks.size, 1);
  assert.equal(srv.labelCalls, 1);
  assert.equal(store.state.failed.length, 0);
});

test('un projet créé hors ligne survit à un rafraîchissement tant qu’il est en file', async () => {
  const { srv, store } = fresh();
  srv.tasks.set(5, { id: 5, title: 'T', project_id: 2, done: false });
  await store.refresh();
  srv.refuse = (k) => (k === 'POST /tasks/5' ? 500 : undefined);
  store.setDone(5, true);
  const project = store.createProject('Voyage');
  const label = store.createLabel('valise');
  store.addTask({ title: 'Passeport', projectId: project, labels: [label] });
  await store.refresh();
  assert.ok(store.state.projects.some((p) => p.id === project));
  assert.ok(store.state.labels.some((l) => l.id === label.id));
  assert.ok(store.state.tasks.some((t) => t.title === 'Passeport'));
});

test('une tâche ajoutée pendant la lecture du serveur est gardée', async () => {
  const { srv, store } = fresh();
  let added = 0;
  srv.onCall = (k) => { if (k === 'GET /tasks' && !added) added = store.addTask({ title: 'Pendant la synchro', projectId: 2 }); };
  await store.refresh();
  assert.ok(store.state.tasks.some((t) => t.id === added));
});

test('modifier une tâche garde ses affectations (Vikunja les efface sinon)', async () => {
  const { srv, store } = fresh();
  srv.tasks.set(5, { id: 5, title: 'T', project_id: 2, done: false, assignees: [{ id: 7, username: 'marie' }] });
  await store.refresh();
  store.updateTask({ ...store.task(5), title: 'T2' });
  await store.syncPending();
  assert.deepEqual(srv.assigned, ['5>7']);
});

test('stockage plein : les écritures en attente (et leurs tâches) passent avant le cache', async () => {
  const { srv, store } = fresh();
  for (let i = 0; i < 40; i++) srv.tasks.set(200 + i, { id: 200 + i, title: `Tâche ${'x'.repeat(200)} ${i}`, project_id: 2, done: false });
  await store.refresh();
  srv.online = false;
  store.addTask({ title: 'Hors ligne', projectId: 2 });
  // Assez pour la file, pas pour le cache entier.
  localStorage.quota = 4000;
  await new Promise((r) => setTimeout(r, 200));
  assert.ok(localStorage.getItem('lielutodo.pending'));

  const again = createStore(srv.api);
  assert.equal(again.state.ops.length, 1);
  assert.ok(again.state.tasks.some((t) => t.title === 'Hors ligne'));
});
