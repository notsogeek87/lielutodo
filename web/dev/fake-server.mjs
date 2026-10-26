// Faux serveur Vikunja en mémoire + fichiers statiques, pour développer et tester l'app web sans le vrai serveur :
//   node web/dev/fake-server.mjs [port]   puis ouvrir http://localhost:PORT  (connexion : demo / demo)
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(fileURLToPath(import.meta.url), '..', '..');
const PORT = Number(process.argv[2] || 5173);
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };

const iso = (d) => d.toISOString();
const at = (days, h = 12, m = 0) => { const d = new Date(); d.setDate(d.getDate() + days); d.setHours(h, m, 0, 0); return iso(d); };
const ZERO = '0001-01-01T00:00:00Z';
let nextId = 100;
const db = {
  projects: [
    { id: 1, title: 'Boîte de réception', hex_color: '', position: 1, parent_project_id: 0, is_favorite: false, views: [{ id: 11, view_kind: 'list' }] },
    { id: 2, title: 'Perso', hex_color: '299438', position: 2, parent_project_id: 0, is_favorite: true, views: [{ id: 21, view_kind: 'list' }] },
    { id: 3, title: 'Travail', hex_color: '4073ff', position: 3, parent_project_id: 0, is_favorite: false, views: [{ id: 31, view_kind: 'list' }] },
  ],
  labels: [{ id: 1, title: 'urgent', hex_color: 'db4035' }, { id: 2, title: 'maison', hex_color: '' }],
  tasks: [],
  comments: {},
};
const task = (o) => ({ id: nextId++, project_id: 1, title: '', description: '', done: false, due_date: ZERO, priority: 0, labels: [], is_favorite: false, position: 0, repeat_after: 0, repeat_mode: 0, reminders: [], related_tasks: {}, comment_count: 0, created: iso(new Date()), updated: iso(new Date()), ...o });
db.tasks.push(
  task({ title: 'Réviser le budget du mois', project_id: 1, due_date: at(-2), priority: 4, position: 1, labels: [db.labels[0]] }),
  task({ title: 'Appeler Marie', project_id: 2, due_date: at(0, 9, 30), priority: 3, position: 1, description: 'Pour le week-end' }),
  task({ title: 'Faire les courses', project_id: 2, due_date: at(0), position: 2, labels: [db.labels[1]] }),
  task({ title: 'Sport', project_id: 2, due_date: at(1, 18), repeat_after: 86400 * 7, position: 3 }),
  task({ title: 'Préparer la réunion', project_id: 3, due_date: at(3, 14), priority: 2, position: 1 }),
  task({ title: 'Envoyer la facture', project_id: 3, due_date: at(5), position: 2 }),
  task({ title: 'Idée de cadeau', project_id: 1, position: 2 }),
);

const json = (res, code, body, headers = {}) => { res.writeHead(code, { 'Content-Type': 'application/json', ...headers }); res.end(JSON.stringify(body)); };
const readBody = (req) => new Promise((r) => { let s = ''; req.on('data', (c) => { s += c; }); req.on('end', () => r(s ? JSON.parse(s) : {})); });

async function api(req, res, url) {
  const path = url.pathname.replace(/^\/api\/v1/, '');
  const m = req.method;
  const body = ['POST', 'PUT'].includes(m) ? await readBody(req) : {};
  if (path === '/login') return body.username === 'demo' && body.password === 'demo' ? json(res, 200, { token: 'demo-token' }) : json(res, 401, { message: 'Mauvais identifiants' });
  if (req.headers.authorization !== 'Bearer demo-token') return json(res, 401, { message: 'Non autorisé' });
  if (path === '/user') return json(res, 200, { id: 1, username: 'demo', name: 'Démo', settings: { default_project_id: 1 } });
  if (path === '/projects' && m === 'GET') return json(res, 200, db.projects, { 'x-pagination-total-pages': '1' });
  if (path === '/projects' && m === 'PUT') { const p = { id: nextId++, position: 9, parent_project_id: 0, is_favorite: false, views: [{ id: nextId++, view_kind: 'list' }], hex_color: '', ...body }; db.projects.push(p); return json(res, 201, p); }
  let r;
  if ((r = path.match(/^\/projects\/(\d+)$/))) {
    const p = db.projects.find((x) => x.id === +r[1]);
    if (m === 'GET') return json(res, 200, p);
    if (m === 'POST') { Object.assign(p, body); return json(res, 200, p); }
    if (m === 'DELETE') { db.projects = db.projects.filter((x) => x !== p); db.tasks = db.tasks.filter((t) => t.project_id !== p.id); return json(res, 200, {}); }
  }
  if ((r = path.match(/^\/projects\/(\d+)\/views\/\d+\/tasks$/))) return json(res, 200, db.tasks.filter((t) => t.project_id === +r[1] && !t.done), { 'x-pagination-total-pages': '1' });
  if ((r = path.match(/^\/projects\/(\d+)\/tasks$/)) && m === 'PUT') {
    const t = task({ ...body, project_id: +r[1], labels: [] }); db.tasks.push(t); return json(res, 201, t);
  }
  if (path === '/labels' && m === 'GET') return json(res, 200, db.labels, { 'x-pagination-total-pages': '1' });
  if (path === '/labels' && m === 'PUT') { const l = { id: nextId++, hex_color: '', ...body }; db.labels.push(l); return json(res, 201, l); }
  if (path === '/tasks' && m === 'GET') {
    const f = url.searchParams.get('filter') || '';
    const list = db.tasks.filter((t) => (f.startsWith('done = false') ? !t.done : f.startsWith('done = true') ? t.done : true));
    return json(res, 200, list, { 'x-pagination-total-pages': '1' });
  }
  if ((r = path.match(/^\/tasks\/(\d+)$/))) {
    const t = db.tasks.find((x) => x.id === +r[1]);
    if (!t) return json(res, 404, { message: 'Tâche introuvable' });
    if (m === 'POST') {
      const wasDone = t.done;
      Object.assign(t, body, { labels: t.labels, updated: iso(new Date()) });
      if (t.done && !wasDone && t.repeat_after > 0 && t.due_date !== ZERO) {
        let d = new Date(t.due_date);
        while (d <= new Date()) d = new Date(+d + t.repeat_after * 1000);
        t.due_date = iso(d); t.done = false;
      } else if (t.done && !wasDone) t.done_at = iso(new Date());
      return json(res, 200, t);
    }
    if (m === 'DELETE') { db.tasks = db.tasks.filter((x) => x !== t); return json(res, 200, {}); }
  }
  if ((r = path.match(/^\/tasks\/(\d+)\/labels\/bulk$/))) { const t = db.tasks.find((x) => x.id === +r[1]); t.labels = body.labels.map((l) => db.labels.find((x) => x.id === l.id)).filter(Boolean); return json(res, 200, t); }
  if ((r = path.match(/^\/tasks\/(\d+)\/comments$/))) {
    const list = (db.comments[r[1]] ||= []);
    if (m === 'PUT') { const c = { id: nextId++, comment: body.comment, author: { name: 'Démo' }, created: iso(new Date()) }; list.push(c); db.tasks.find((x) => x.id === +r[1]).comment_count++; return json(res, 201, c); }
    return json(res, 200, list);
  }
  if ((r = path.match(/^\/tasks\/(\d+)\/relations$/)) && m === 'PUT') {
    const t = db.tasks.find((x) => x.id === +r[1]); t.related_tasks = { parenttask: [{ id: body.other_task_id }] }; return json(res, 201, {});
  }
  if ((r = path.match(/^\/tasks\/(\d+)\/position$/))) { db.tasks.find((x) => x.id === +r[1]).position = body.position; return json(res, 200, {}); }
  return json(res, 404, { message: `Route inconnue ${m} ${path}` });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  try {
    if (url.pathname.startsWith('/api/v1')) return await api(req, res, url);
    if (url.pathname.startsWith('/vk/')) { url.pathname = url.pathname.slice(3); return await api(req, res, url); }
    let file = normalize(url.pathname).replace(/^(\.\.[/\\])+/, '');
    if (file === '/' || file === '\\') file = '/index.html';
    const data = await readFile(join(ROOT, file));
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream' });
    res.end(data);
  } catch (e) {
    res.writeHead(e.code === 'ENOENT' ? 404 : 500); res.end(String(e.message));
  }
}).listen(PORT, () => console.log(`LieluTodo web (faux serveur) : http://localhost:${PORT}  — demo / demo`));
