// Notes Joplin (web) : format d'item, plan de synchro, édition Markdown, rendu, store contre un faux Joplin Server.
// Avec JOPLIN_IT_URL (+ JOPLIN_IT_USER1/PASS1, USER2/PASS2), joue aussi le scénario contre un VRAI Joplin Server.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseItem, patchItem, serializeFolder, serializeNote, joplinLogin, createJoplinApi,
} from '../js/notes/joplin.js';
import { createNotesStore, planSync } from '../js/notes/notes-store.js';
import * as md from '../js/notes/markdown.js';

class MemoryStorage {
  constructor() { this.items = new Map(); }
  getItem(k) { return this.items.has(k) ? this.items.get(k) : null; }
  setItem(k, v) { this.items.set(k, String(v)); }
  removeItem(k) { this.items.delete(k); }
}
const v = (text, start, end = start) => ({ text, start, end });
const hex = (c) => c.repeat(32);

// ---------- Format d'item ----------
test('une nouvelle note sérialisée se relit et ne finit PAS par un retour à la ligne', () => {
  const t = serializeNote({ id: hex('a'), parentId: hex('b'), title: 'Titre', body: 'Corps', updatedTime: 1793728870000 });
  assert.ok(t.startsWith('Titre\n\nCorps\n\n'));
  assert.ok(t.endsWith('type_: 1'));
  const p = parseItem(t);
  assert.equal(p.title, 'Titre');
  assert.equal(p.body, 'Corps');
  assert.equal(p.updatedTime, 1793728870000);
  assert.equal(parseItem(serializeFolder({ id: hex('c'), title: 'Carnet', updatedTime: 1 })).type, 2);
});

test('patch garde les propriétés Joplin et remplace le contenu', () => {
  const raw = `Ancien\n\nancien\n\nid: ${hex('a')}\nparent_id: p1\nauthor: Moi\nis_todo: 1\ntodo_due: 1700000000000\nupdated_time: 2020-01-01T00:00:00.000Z\ntype_: 1`;
  const t = patchItem(raw, { title: 'Nouveau', body: 'corps', parentId: 'p2', updatedTime: 1793728870000, shareId: 's1' });
  for (const s of ['author: Moi\n', 'is_todo: 1\n', 'todo_due: 1700000000000\n', 'parent_id: p2\n', 'share_id: s1\n']) assert.ok(t.includes(s), s);
  assert.ok(t.startsWith('Nouveau\n\ncorps\n\n'));
  assert.ok(t.endsWith('type_: 1'));
});

test('item chiffré reconnu, id invalide refusé', () => {
  assert.equal(parseItem(`\n\nid: ${hex('5')}\nencryption_applied: 1\ntype_: 1`).encrypted, true);
  assert.equal(parseItem('t\n\nid: court\ntype_: 1'), null);
});

// ---------- Plan ----------
const local = (id, o = {}) => ({ id, title: `t${id}`, body: `b${id}`, parentId: '', userUpdatedTime: 0, dirty: false, deleted: false, serverUpdatedTime: 0, ...o });
const remote = (id, o = {}) => ({ id, title: `t${id}`, body: `b${id}`, parentId: '', updatedTime: 0, ...o });

test('plan : modifiée des deux côtés depuis la base = conflit, sinon envoi simple', () => {
  const conflict = planSync([local('a', { dirty: true, body: 'ici', serverUpdatedTime: 10 })], [remote('a', { updatedTime: 20, body: 'ailleurs' })], new Set());
  assert.deepEqual(conflict.conflicts.map((r) => r.body), ['ailleurs']);
  const simple = planSync([local('a', { dirty: true, body: 'ici', serverUpdatedTime: 20 })], [remote('a', { updatedTime: 20, body: 'avant' })], new Set());
  assert.equal(simple.conflicts.length, 0);
  assert.equal(simple.toPush.length, 1);
  assert.deepEqual(planSync([local('a')], [], new Set(['a'])).toDeleteLocal, ['a']);
  const drafts = planSync([local('d', { dirty: true, title: '', body: ' ', serverRaw: null }), local('e', { dirty: true, title: 'x', serverRaw: null })], [], new Set());
  assert.deepEqual(drafts.toPush.map((n) => n.id), ['e']);
});

// ---------- Édition (mêmes cas que MarkdownEditingTest.kt) ----------
test('édition Markdown', () => {
  assert.deepEqual(md.toggleWrap(v('le mot ici', 4), '**'), v('le **mot** ici', 5, 8));
  assert.equal(md.toggleWrap(v('le **mot** ici', 6), '**').text, 'le mot ici');
  assert.equal(md.toggleWrap(v('**mot**', 3), '*').text, '***mot***');
  assert.deepEqual(md.toggleWrap(v('a ', 2), '~~'), v('a ~~~~', 4));
  assert.deepEqual(md.toggleLinePrefix(v('un\ndeux\ntrois', 5), md.BULLET), v('un\n- deux\ntrois', 7));
  const avec = md.toggleLinePrefix(v('a\nb', 0, 3), md.BULLET);
  assert.equal(avec.text, '- a\n- b');
  assert.equal(md.toggleLinePrefix(avec, md.BULLET).text, 'a\nb');
  assert.equal(md.toggleLinePrefix(v('a\nb\nc', 0, 5), md.NUMBERED).text, '1. a\n2. b\n3. c');
  assert.equal(md.toggleLinePrefix(v('- courses', 3), md.HEADING).text, '## courses');
  const coche = md.toggleCheckbox(v('- [ ] pain', 8));
  assert.equal(coche.text, '- [x] pain');
  assert.equal(md.toggleCheckbox(coche).text, '- [ ] pain');
  assert.equal(md.toggleCheckbox(v('lait', 0)).text, '- [ ] lait');
  assert.deepEqual(md.insertLink(v('voir site', 7)), v('voir [site](https://)', 20));
  assert.deepEqual(md.insertLink(v('', 0)), v('[lien](https://)', 1, 5));
  assert.deepEqual(md.continueList(v('1. a', 4), v('1. a\n', 5)), v('1. a\n2. ', 8));
  assert.deepEqual(md.continueList(v('- a\n- ', 6), v('- a\n- \n', 7)), v('- a\n', 4));
  assert.deepEqual(md.continueList(v('- [x] pain', 10), v('- [x] pain\n', 11)), v('- [x] pain\n- [ ] ', 17));
  const hors = v('texte\n', 6);
  assert.equal(md.continueList(v('texte', 5), hors), hors);
  assert.equal(md.titleFromBody('\n\n# Courses\n- pain'), 'Courses');
  assert.equal(md.plainPreview('## Courses\n\n- [ ] pain\n- [x] lait\n**très** important'), 'Courses · ☐ pain · ☑ lait · très important');
});

test('rendu : mise en forme courante, aucun HTML injecté', () => {
  const html = md.renderMarkdown('# Titre\n- [x] fait\n- [ ] à faire\n**gras** et ~~barré~~ [lien](https://lielu.eu)\n<img src=x onerror=alert(1)>\n[x](javascript:alert(1))');
  assert.ok(html.includes('<h2>Titre</h2>'));
  assert.ok(html.includes('md-task done'));
  assert.ok(html.includes('<strong>gras</strong>') && html.includes('<del>barré</del>'));
  assert.ok(html.includes('href="https://lielu.eu"'));
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('javascript:'));
});

test('rendu : image jointe Joplin (:/id), image web, pièce jointe ; alt et adresse échappés', () => {
  const id = '208a92216ff547e1b0383d7cdc31c298';
  const html = md.renderMarkdown(`Affiche 2\n![signal_2026*10.png](:/${id})\n![x" onerror="a](https://lielu.eu/i.png?a=1&b=2) [doc.pdf](:/${id.replace('8', '9')})`);
  assert.ok(html.includes(`<img class="md-img" data-jres="${id}" alt="signal_2026*10.png">`));
  assert.ok(html.includes('src="https://lielu.eu/i.png?a=1&amp;b=2"'));
  assert.ok(html.includes('alt="x&quot; onerror=&quot;a"'));
  assert.ok(html.includes('data-jres-link="209a92216ff547e1b0383d7cdc31c298"'));
  assert.deepEqual(md.resourceIds(`![a](:/${id}) [b](:/${id})`), [id]);
  assert.equal(md.plainPreview(`Affiche ![s.png](:/${id})`), 'Affiche 🖼 s.png');
});

// ---------- Store contre un faux Joplin Server ----------
function fakeJoplin() {
  const items = new Map();
  const log = [];
  let shares = 0;
  let cursor = 0;
  const changes = [];
  return {
    items, log,
    api: {
      ping: async () => 'JoplinServer',
      delta: async (c) => {
        const from = c ? Number(c) : 0;
        const page = changes.slice(from).map((name) => ({ name, type: 1 }));
        cursor = changes.length;
        return { items: page, hasMore: false, cursor: String(cursor) };
      },
      rootChildren: async () => ({ items: [], hasMore: false, cursor: null }),
      itemContent: async (name) => items.get(name),
      putContent: async (name, content) => { items.set(name, content); log.push(`put ${name}`); return {}; },
      deleteItem: async (name) => { items.delete(name); log.push(`delete ${name}`); },
      createShare: async (folderId) => { log.push(`share ${folderId}`); return `s${++shares}`; },
      addShareRecipient: async (shareId, email) => { log.push(`invite ${shareId} ${email}`); },
    },
    /** Une écriture faite ailleurs (autre appareil). */
    external(name, content) { items.set(name, content); changes.push(name); },
  };
}
const newStore = (fake, clock = { t: 1_000_000 }) =>
  createNotesStore({ storage: new MemoryStorage(), apiFactory: () => fake.api, login: async () => ({ sessionId: 'x' }), now: () => ++clock.t });

test('store : carnet créé ici envoyé avant sa note, puis note propre', async () => {
  const fake = fakeJoplin();
  const store = newStore(fake);
  await store.connect('https://notes.lielu.eu', 'moi@lielu.eu', 'pw');
  const f = store.createFolder('Projets');
  const n = store.create(f.id);
  store.save(n.id, 'Idée', 'texte');
  await store.sync();
  assert.deepEqual(fake.log, [`put ${f.id}.md`, `put ${n.id}.md`]);
  assert.equal(store.note(n.id).dirty, false);
  assert.ok(fake.items.get(`${f.id}.md`).endsWith('type_: 2'));
});

test('store : un conflit garde les deux versions', async () => {
  const fake = fakeJoplin();
  const store = newStore(fake);
  await store.connect('u', 'e', 'p');
  const f = store.createFolder('C');
  const n = store.create(f.id);
  store.save(n.id, 'Note', 'origine');
  await store.sync();
  const serverTime = store.note(n.id).serverUpdatedTime;
  fake.external(`${n.id}.md`, patchItem(fake.items.get(`${n.id}.md`), { title: 'Note', body: 'version ordinateur', parentId: f.id, updatedTime: serverTime + 1000 }));
  store.save(n.id, 'Note', 'version téléphone');
  await store.sync();
  assert.equal(store.note(n.id).body, 'version téléphone');
  const copy = store.state.notes.find((x) => x.id !== n.id);
  assert.ok(copy.title.startsWith('Note (conflit'));
  assert.equal(copy.body, 'version ordinateur');
});

test('store : partager pose share_id sur le carnet et ses notes AVANT d’inviter', async () => {
  const fake = fakeJoplin();
  const store = newStore(fake);
  await store.connect('u', 'e', 'p');
  const f = store.createFolder('Famille');
  const n = store.create(f.id);
  store.save(n.id, 'Courses', 'pain');
  await store.sync();
  fake.log.length = 0;
  await store.shareFolder(f.id, 'bob@lielu.eu');
  assert.deepEqual(fake.log, [`share ${f.id}`, `put ${f.id}.md`, `put ${n.id}.md`, 'invite s1 bob@lielu.eu']);
  assert.ok(fake.items.get(`${n.id}.md`).includes('share_id: s1'));
  await assert.rejects(store.shareFolder(f.id, 'pas-un-email'), /invalide/);
});

test('store : image jointe téléchargée une fois depuis .resource/<id>', async () => {
  const fake = fakeJoplin();
  let calls = 0;
  fake.api.resourceBlob = async (id) => { calls++; assert.equal(id, 'ab'.repeat(16)); return new Blob(['png'], { type: 'image/png' }); };
  const store = newStore(fake);
  assert.equal(await store.resourceUrl('ab'.repeat(16)), null); // pas connecté : rien
  await store.connect('u', 'e', 'p');
  const url = await store.resourceUrl('ab'.repeat(16));
  assert.ok(url.startsWith('blob:'));
  assert.equal(await store.resourceUrl('ab'.repeat(16)), url);
  assert.equal(calls, 1);
  assert.equal(await store.resourceUrl('../x'), null);
});

test('store : la synchro compte les éléments reçus (affichés pendant la première synchro)', async () => {
  const fake = fakeJoplin();
  const f = serializeFolder({ id: 'f'.repeat(32), title: 'Carnet', updatedTime: 1000 });
  fake.external(`${'f'.repeat(32)}.md`, f);
  for (let i = 0; i < 12; i++) {
    const id = i.toString(16).padStart(32, '0');
    fake.external(`${id}.md`, serializeNote({ id, parentId: 'f'.repeat(32), title: `N${i}`, body: '', updatedTime: 1000 }));
  }
  const store = newStore(fake);
  await store.connect('u', 'e', 'p');
  let seenSyncing = false;
  store.subscribe(() => { if (store.meta.syncing && store.meta.progress) seenSyncing = true; });
  await store.sync();
  assert.ok(seenSyncing);
  assert.equal(store.meta.progress, 13);
  assert.equal(store.state.notes.length, 12);
});

test('store : supprimer une note jamais envoyée la retire sans appel serveur', async () => {
  const fake = fakeJoplin();
  const store = newStore(fake);
  const n = store.create();
  store.remove(n.id);
  assert.equal(store.note(n.id), undefined);
});

// ---------- Contre un vrai Joplin Server (facultatif) ----------
const IT = process.env.JOPLIN_IT_URL;
test('intégration : synchro, partage et écriture croisée contre un vrai Joplin Server', { skip: !IT && 'JOPLIN_IT_URL absent' }, async () => {
  const { JOPLIN_IT_USER1: u1, JOPLIN_IT_PASS1: p1, JOPLIN_IT_USER2: u2, JOPLIN_IT_PASS2: p2 } = process.env;
  const device = () => createNotesStore({ storage: new MemoryStorage() });
  const phone = device();
  await phone.connect(IT, u1, p1);
  assert.ok(await phone.sync());
  const stamp = Date.now();
  const f = phone.createFolder(`Web carnet ${stamp}`);
  const n = phone.create(f.id);
  phone.save(n.id, `Web note ${stamp}`, '- [ ] pain\n- [x] lait');
  const r = await phone.sync();
  assert.ok(r, phone.meta.error);
  assert.equal(phone.note(n.id).dirty, false);

  const laptop = device();
  await laptop.connect(IT, u1, p1);
  await laptop.sync();
  assert.equal(laptop.note(n.id)?.body, '- [ ] pain\n- [x] lait');

  await phone.shareFolder(f.id, u2);
  const shareId = phone.folder(f.id).shareId;
  assert.ok(shareId);

  const bob = device();
  await bob.connect(IT, u2, p2);
  const invites = await bob.refreshInvitations();
  const inv = invites.find((i) => i.folderId === f.id);
  assert.ok(inv, 'invitation reçue');
  await bob.respondToInvitation(inv, true);
  assert.equal(bob.note(n.id)?.body, '- [ ] pain\n- [x] lait');
  bob.save(n.id, `Web note ${stamp}`, 'écrit par Bob');
  await bob.sync();
  await phone.sync();
  assert.equal(phone.note(n.id).body, 'écrit par Bob');

  const recipients = await phone.shareRecipients(f.id);
  assert.ok(recipients.some((x) => x.email === u2 && x.status === 'accepted'));
  await phone.stopSharing(f.id);
  assert.equal(phone.folder(f.id).shareId, '');
  // Sans session valide, l'API lève une erreur d'authentification (et non une panne réseau).
  const bad = createJoplinApi({ baseUrl: IT, viaProxy: false, sessionId: 'invalide' });
  await assert.rejects(bad.delta(null), (e) => e.name === 'JoplinAuthError' || e.constructor.name === 'JoplinAuthError');
  await assert.rejects(joplinLogin(IT, u1, 'mauvais'), /incorrect|Trop/);
});
