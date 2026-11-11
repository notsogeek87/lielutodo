import { ApiError, createApi, login, preferDirect, session } from './api.js';
import { CONFIG_HASH, saveServers, servers } from './servers.js';
import { createStore, describeWrite } from './store.js';
import { icon } from './icons.js';
import { colorOf, todoistLevel } from './model.js';
import { addDays, dueKind, dueText, startOfDay } from './dates.js';
import { searchTasks, fold } from './search.js';
import { mountComposer } from './composer.js';
import { openDetail } from './detail.js';
import { buildView, inboxProject, openTasks, sectionHtml, weekStrip } from './views.js';
import { createNotesStore } from './notes/notes-store.js';
import { createNotesView, lastNotesHash } from './notes/notes-view.js';
import {
  $, $$, closePopover, closeTopModal, confirmDialog, esc, menu, modal, promptDialog, toast, PROJECT_COLORS,
} from './ui.js';

const LS = {
  get(k, d) { try { const v = localStorage.getItem(`lielutodo.${k}`); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(`lielutodo.${k}`, JSON.stringify(v)); } catch { /* confort */ } },
};

const ui = {
  sidebarCollapsed: LS.get('sidebarCollapsed', false),
  sidebarOpen: false,
  collapsedSections: new Set(LS.get('collapsedSections', [])),
  collapsedProjects: new Set(LS.get('collapsedProjects', [])),
  collapsedTasks: new Set(LS.get('collapsedTasks', [])),
  showDone: new Set(LS.get('showDone', [])),
  upcomingStart: null,
};
const persistUi = () => {
  LS.set('sidebarCollapsed', ui.sidebarCollapsed);
  LS.set('collapsedSections', [...ui.collapsedSections]);
  LS.set('collapsedProjects', [...ui.collapsedProjects]);
  LS.set('collapsedTasks', [...ui.collapsedTasks]);
  LS.set('showDone', [...ui.showDone]);
};

let sess = session.load();
let bootGen = 0;
let vkApi = null;
let store = null;
let ctx = null;
let route = { name: 'inbox' };
let detail = null;
let notes = null;
let notesView = null;

// ---------- Routage (hash) ----------
function parseRoute() {
  const [, name = 'inbox', arg] = location.hash.split('/');
  if (name === 'notes') return { name, id: arg && /^[0-9a-f]{32}$/i.test(arg) ? arg : null };
  if (name === 'note' && /^[0-9a-f]{32}$/i.test(arg || '')) return { name, id: arg };
  const id = Number(arg);
  if (['inbox', 'today', 'upcoming', 'completed', 'filters'].includes(name)) return { name };
  if (['project', 'label', 'filter'].includes(name) && Number.isFinite(id)) return { name, id };
  return { name: 'inbox' };
}
export const go = (hash) => { if (location.hash === hash) { route = parseRoute(); render(); } else location.hash = hash; };

// ---------- Démarrage ----------
function boot() {
  applyTheme();
  if (location.hash === CONFIG_HASH) return renderServerConfig();
  if (!sess) return renderLogin();
  // Chaque démarrage a son propre client : celui d'une session expirée cesse d'envoyer quoi que ce soit (sinon ses
  // minuteries enverraient ses écritures une seconde fois avec la nouvelle session).
  const gen = ++bootGen;
  const api = createApi(() => (gen === bootGen ? sess : null), () => {
    // Jeton refusé et pas de renouvellement possible : on redemande la connexion SANS effacer le cache local, les
    // modifications pas encore envoyées partiront une fois reconnecté (même compte, vérifié au démarrage).
    if (!sess) return;
    session.clear(); sess = null; renderLogin('Session expirée, reconnectez-vous.');
  }, (renewed) => { if (gen === bootGen) { sess = renewed; session.save(renewed); } });
  vkApi = api;
  store = createStore(api);
  // Autre compte que celui du cache : ses écritures en attente ne doivent jamais partir sous ce compte-ci.
  if (sess.userId && store.state.user?.id && store.state.user.id !== sess.userId) store.reset();
  // Dès que le serveur autorise l'appel direct, on cesse de passer par la réécriture du site.
  preferDirect(sess).then((direct) => {
    if (direct !== sess && sess) { sess = direct; session.save(direct); }
  });
  ctx = {
    store,
    defaultProjectId: () => inboxProject(store.state)?.id || 0,
    completeTask,
    openQuickAdd,
    onDetailClosed: () => { detail = null; },
  };
  // Notes Joplin : même logique « local d'abord », serveur à part (voir js/notes/).
  notes = createNotesStore();
  notesView = createNotesView(notes, go);
  route = parseRoute();
  renderShell();
  store.subscribe(scheduleRender);
  notes.subscribe(scheduleRender);
  const refreshAll = () => { store.refresh(); notes.sync(); };
  window.addEventListener('hashchange', () => {
    if (route.name === 'note') notesView.leave();
    route = parseRoute(); ui.sidebarOpen = false; ui.upcomingStart = null; render(); closeAllOverlays();
  });
  window.addEventListener('online', refreshAll);
  window.addEventListener('pagehide', () => notesView.flushOnHide());
  document.addEventListener('visibilitychange', () => { if (document.hidden) notesView.flushOnHide(); else refreshAll(); });
  setInterval(() => { if (!document.hidden) refreshAll(); }, 60_000);
  notes.sync();
  notes.refreshInvitations();
  document.addEventListener('keydown', onKey);
  store.refresh();
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) navigator.serviceWorker.register('sw.js').catch(() => {});
}

function applyTheme() {
  const t = LS.get('theme', 'auto');
  if (t === 'auto') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t;
  try { localStorage.setItem('lielutodo.theme', t); } catch { /* ok */ }
}

function closeAllOverlays() { closePopover(); while (closeTopModal()); }

// ---------- Connexion ----------
function renderLogin(message = '') {
  // Notes Joplin : section facultative, comme dans l'app (rien n'est fait si l'e-mail Joplin reste vide).
  const notesStore = notes || createNotesStore();
  const jp = notesStore.connected ? notesStore.connection : null;
  $('#app').innerHTML = `
    <div class="login-wrap"><div class="login">
      <form id="login-vk" autocomplete="on">
      <img class="logo" src="icon.svg" alt="" width="64" height="64">
      <h1>LieluTodo</h1><p>Connectez-vous à vos tâches.</p>
      <div class="fl"><label for="usr">Pseudo ou adresse e-mail</label><input class="field" id="usr" name="username" type="text" autocomplete="username" autocapitalize="off" spellcheck="false" required></div>
      <div class="fl"><label for="pwd">Mot de passe</label><input class="field" id="pwd" name="password" type="password" autocomplete="current-password" required></div>
      <div class="fl" id="totp-wrap" hidden><label for="totp">Code à deux facteurs</label><input class="field" id="totp" name="totp" inputmode="numeric" autocomplete="one-time-code" maxlength="8"></div>
      </form>
      <!-- Formulaire Joplin séparé : un gestionnaire de mots de passe ne remplit qu'un identifiant par formulaire. -->
      <form id="login-jp" class="login-section" autocomplete="on">
        <h2>Notes (Joplin Server)</h2>
        ${jp ? `<p>Connectées : ${esc(jp.email)}</p>` : `<p>Facultatif : synchronise vos notes avec un serveur Joplin.</p>
        <div class="fl"><label for="jp-mail">E-mail Joplin</label><input class="field" id="jp-mail" name="jpEmail" type="email" autocomplete="username" autocapitalize="off" spellcheck="false"></div>
        <div class="fl"><label for="jp-pwd">Mot de passe Joplin</label><input class="field" id="jp-pwd" name="jpPassword" type="password" autocomplete="current-password"></div>`}
      </form>
      <div class="err" role="alert">${esc(message)}</div>
      <button class="btn primary" form="login-vk">Se connecter</button>
      <small>Vos tâches et vos notes restent lisibles et modifiables hors ligne.</small>
    </div></div>`;
  const box = $('.login');
  const form = $('#login-vk');
  const jpForm = $('#login-jp');
  // Entrée dans un champ Joplin = « Se connecter » (ce formulaire sans bouton ne s'envoie pas seul).
  jpForm.addEventListener('submit', (e) => e.preventDefault());
  jpForm.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); form.requestSubmit(); }
  });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const { username, password, totp } = form.elements;
    const f = { username, password, totp, jpEmail: jpForm.elements.jpEmail, jpPassword: jpForm.elements.jpPassword };
    const err = $('.err', box);
    const btn = $('button.btn', box);
    err.textContent = '';
    btn.disabled = true;
    btn.textContent = 'Connexion…';
    try {
      if (!jp && f.jpEmail.value.trim() && !f.jpPassword.value) throw new Error('Entrez le mot de passe Joplin (ou videz l’e-mail Joplin).');
      sess = await login(servers().vikunja, f.username.value.trim(), f.password.value, f.totp.value);
      session.save(sess);
      let notesError = '';
      if (!jp && f.jpEmail.value.trim()) {
        btn.textContent = 'Connexion des notes…';
        try {
          await notesStore.connect(servers().joplin, f.jpEmail.value.trim(), f.jpPassword.value);
        } catch (jex) { notesError = `Notes non connectées : ${jex.message || 'connexion impossible'}`; }
      }
      location.hash = '#/inbox';
      boot();
      if (notesError) toast(notesError, { error: true, ms: 8000 });
    } catch (ex) {
      if (ex instanceof ApiError && ex.code === 412) {
        $('#totp-wrap', form).hidden = false;
        f.totp.focus();
        err.textContent = 'Entrez le code de votre application d’authentification.';
      } else {
        err.textContent = ex instanceof ApiError ? (ex.code === 412 ? '' : ex.message) : ex.message || 'Connexion impossible';
      }
      btn.disabled = false;
      btn.textContent = 'Se connecter';
    }
  });
}

// ---------- Adresses des serveurs (page cachée #/serveurs, aucun lien n'y mène) ----------
function renderServerConfig() {
  const cur = servers();
  $('#app').innerHTML = `
    <div class="login-wrap"><form class="login" autocomplete="off">
      <h1>Serveurs</h1><p>Adresses utilisées par ce navigateur.</p>
      <div class="fl"><label for="cfg-vk">Tâches (Vikunja)</label><input class="field" id="cfg-vk" name="vikunja" value="${esc(cur.vikunja)}" inputmode="url" autocapitalize="off" spellcheck="false" required></div>
      <div class="fl"><label for="cfg-jp">Notes (Joplin Server)</label><input class="field" id="cfg-jp" name="joplin" value="${esc(cur.joplin)}" inputmode="url" autocapitalize="off" spellcheck="false" required></div>
      <div class="err" role="alert"></div>
      <button class="btn primary">Enregistrer</button>
      <button type="button" class="btn" data-cfg-default>Valeurs par défaut</button>
      <button type="button" class="btn" data-cfg-back>Retour</button>
      <small>Changer d’adresse demande de se reconnecter à ce serveur.</small>
    </form></div>`;
  const form = $('.login');
  const leave = () => { location.hash = ''; location.reload(); };
  const apply = async (wanted) => {
    const before = servers();
    let next;
    try {
      next = saveServers(wanted);
    } catch (ex) {
      $('.err', form).textContent = ex.message || 'Adresse invalide';
      return;
    }
    // Autre serveur de tâches : la session et le cache de l'ancien n'ont plus de sens ici.
    if (next.vikunja !== before.vikunja && sess) {
      const s = JSON.parse(localStorage.getItem('lielutodo.pending') || 'null');
      const unsent = s ? (s.ops?.length || 0) + (s.failed?.length || 0) : 0;
      const ok = await confirmDialog(unsent
        ? `Changer de serveur de tâches ? Vous serez déconnecté et ${unsent} modification${unsent > 1 ? 's' : ''} pas encore envoyée${unsent > 1 ? 's' : ''} ${unsent > 1 ? 'seront perdues' : 'sera perdue'}.`
        : 'Changer de serveur de tâches ? Vous serez déconnecté.', 'Changer', unsent > 0);
      if (!ok) { saveServers(before); return; }
      session.clear();
      store?.reset();
      if (!store) { localStorage.removeItem('lielutodo.state'); localStorage.removeItem('lielutodo.pending'); }
    }
    if (next.joplin !== before.joplin) (notes || createNotesStore()).disconnect();
    leave();
  };
  form.addEventListener('submit', (e) => { e.preventDefault(); apply({ vikunja: form.elements.vikunja.value, joplin: form.elements.joplin.value }); });
  $('[data-cfg-default]', form).addEventListener('click', () => apply({}));
  $('[data-cfg-back]', form).addEventListener('click', leave);
}
// La page de configuration s'ouvre aussi depuis l'écran de connexion (en tapant l'adresse).
window.addEventListener('hashchange', () => { if (location.hash === CONFIG_HASH) renderServerConfig(); });

// ---------- Squelette ----------
function renderShell() {
  $('#app').innerHTML = `
    <div class="shell">
      <aside class="sidebar" id="sidebar"></aside>
      <div class="scrim" data-act="scrim"></div>
      <main class="main">
        <div class="topbar">
          <button class="icon-btn" data-act="toggle-sidebar" aria-label="Menu">${icon('menu', 20)}</button>
          <span class="spacer"></span><span id="sync-badge"></span>
          <button class="icon-btn" data-act="search" aria-label="Rechercher">${icon('search', 20)}</button>
        </div>
        <div class="scroller" id="scroller"><div class="content" id="content"></div></div>
      </main>
    </div>
    <button class="fab" data-act="quick-add" aria-label="Ajouter une tâche">${icon('plus', 26)}</button>`;
  $('#app').addEventListener('click', onClick);
  $('#app').addEventListener('dragstart', onDragStart);
  $('#app').addEventListener('dragover', onDragOver);
  $('#app').addEventListener('dragleave', onDragLeave);
  $('#app').addEventListener('drop', onDrop);
  $('#app').addEventListener('dragend', clearDrop);
  render();
}

let renderQueued = false;
function scheduleRender() {
  if (renderQueued || dragId != null) return;
  renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; render(); });
}

function render() {
  // Écran de connexion affiché (session expirée) : plus de barre latérale à redessiner.
  if (!store || !$('#sidebar')) return;
  renderSidebar();
  renderBadge();
  renderContent();
}

// ---------- Barre latérale ----------
function navItem(href, iconName, label, count, active, extra = '') {
  return `<a class="nav-item${active ? ' active' : ''}" href="${href}">${iconName}<span class="label">${esc(label)}</span>${count ? `<span class="count">${count}</span>` : ''}${extra}</a>`;
}

function renderSidebar() {
  const s = store.state;
  const open = openTasks(s);
  const today = startOfDay(Date.now());
  const inbox = inboxProject(s);
  const nToday = open.filter((t) => t.due != null && t.due < today + 86_400_000).length;
  const nInbox = inbox ? open.filter((t) => t.projectId === inbox.id || t.assignees?.some((a) => a.id === s.user?.id)).length : 0;
  const counts = new Map();
  open.forEach((t) => counts.set(t.projectId, (counts.get(t.projectId) || 0) + 1));
  const isRoute = (name, id) => route.name === name && (id == null || route.id === id);
  const inboxActive = route.name === 'inbox';

  const projectRow = (p, depth) => {
    const kids = s.projects.filter((c) => c.parentId === p.id).sort((a, b) => a.position - b.position);
    const folded = ui.collapsedProjects.has(p.id);
    const active = route.name === 'project' && route.id === p.id;
    return `<div class="nav-item${active ? ' active' : ''}" style="padding-left:${8 + depth * 18}px" data-nav="#/project/${p.id}" data-pid="${p.id}">
      ${kids.length ? `<button class="tree-toggle" data-act="fold-project" data-id="${p.id}" aria-label="Replier">${icon(folded ? 'chevronRight' : 'chevronDown', 12)}</button>` : ''}
      <i class="dot" style="background:${colorOf(p.hex)}"></i><span class="label">${esc(p.title)}</span>
      ${counts.get(p.id) ? `<span class="count">${counts.get(p.id)}</span>` : ''}
      <button class="icon-btn row-actions" data-act="project-menu" data-id="${p.id}" aria-label="Actions du projet">${icon('more', 16)}</button></div>
      ${kids.length && !folded ? kids.map((k) => projectRow(k, depth + 1)).join('') : ''}`;
  };
  const roots = s.projects.filter((p) => p.id !== inbox?.id && (!p.parentId || !s.projects.some((x) => x.id === p.parentId)))
    .sort((a, b) => a.position - b.position);
  const favorites = s.projects.filter((p) => p.isFavorite && p.id !== inbox?.id);
  const section = (key, title, body, addAct) => `
    <div class="nav-section"><button class="title" data-act="fold-section" data-key="${key}">${icon(ui.collapsedSections.has(key) ? 'chevronRight' : 'chevronDown', 14)} ${title}</button>${addAct ? `<button class="icon-btn" data-act="${addAct}" aria-label="Ajouter">${icon('plus', 16)}</button>` : ''}</div>
    ${ui.collapsedSections.has(key) ? '' : body}`;

  const user = s.user?.name || s.user?.username || sess?.username || '';
  $('#sidebar').className = `sidebar${ui.sidebarCollapsed ? ' collapsed' : ''}${ui.sidebarOpen ? ' open' : ''}`;
  $('.scrim').classList.toggle('show', ui.sidebarOpen);
  $('#sidebar').innerHTML = `
    <div class="side-top">
      <button class="user-btn" data-act="user-menu"><span class="avatar">${esc((user || 'L').slice(0, 1).toUpperCase())}</span><span class="name">${esc(user || 'LieluTodo')}</span>${icon('chevronDown', 14)}</button>
      <button class="icon-btn" data-act="toggle-sidebar" aria-label="Masquer le menu">${icon('sidebar', 18)}</button>
    </div>
    <button class="add-task-btn" data-act="quick-add"><span class="plus">${icon('plus', 16)}</span>Ajouter une tâche</button>
    <nav class="nav" aria-label="Navigation">
      <button class="nav-item" data-act="search">${icon('search', 18)}<span class="label">Rechercher</span></button>
      ${navItem('#/inbox', `<span class="nav-ic-inbox">${icon('inbox', 18)}</span>`, inbox?.title || 'Boîte de réception', nInbox, inboxActive)}
      ${navItem('#/today', `<span class="nav-ic-today">${icon('today', 18)}</span>`, "Aujourd'hui", nToday, isRoute('today'))}
      ${navItem('#/upcoming', `<span class="nav-ic-upcoming">${icon('upcoming', 18)}</span>`, 'À venir', 0, isRoute('upcoming'))}
      ${navItem('#/filters', `<span class="nav-ic-filters">${icon('filters', 18)}</span>`, 'Filtres et étiquettes', 0, isRoute('filters') || isRoute('label') || isRoute('filter'))}
      ${navItem('#/completed', icon('checkCircle', 18), 'Terminées', 0, isRoute('completed'))}
      ${navItem(lastNotesHash(), icon('note', 18), 'Notes', notes.meta.invitations.length, route.name === 'notes' || route.name === 'note')}
      ${s.failed.length ? `<button class="nav-item nav-warn" data-act="failed-ops">${icon('offline', 18)}<span class="label">Modifications non envoyées</span><span class="count">${s.failed.length}</span></button>` : ''}
      ${favorites.length ? section('favorites', 'Favoris', favorites.map((p) => `<a class="nav-item" href="#/project/${p.id}"><i class="dot" style="background:${colorOf(p.hex)}"></i><span class="label">${esc(p.title)}</span></a>`).join('')) : ''}
      ${section('projects', 'Mes projets', roots.map((p) => projectRow(p, 0)).join('') || '<div class="menu-sub">Aucun projet</div>', 'add-project')}
    </nav>
    <div class="side-foot"><span class="grow" id="foot-status"></span><button class="icon-btn" data-act="sync" aria-label="Synchroniser">${icon('sync', 16)}</button><button class="icon-btn" data-act="settings" aria-label="Réglages">${icon('settings', 16)}</button></div>`;
}

function syncText() {
  const { offline, syncing, error } = store.meta;
  const pending = store.state.ops.length;
  const refused = store.state.failed.length;
  if (syncing) return 'Synchronisation…';
  if (refused && !offline) return `${refused} refusée${refused > 1 ? 's' : ''}${pending ? ` · ${pending} en attente` : ''}`;
  if (offline) return `Hors ligne${pending ? ` · ${pending} en attente` : ''}`;
  if (pending) return `${pending} en attente`;
  if (error) return error;
  if (store.state.lastSync) return `À jour · ${new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' }).format(store.state.lastSync)}`;
  return '';
}

function renderBadge() {
  const { offline } = store.meta;
  // Tâches OU notes en cours de synchro : la roue tourne (la première synchro des notes peut être longue).
  const syncing = store.meta.syncing || !!notes?.meta.syncing;
  const pending = store.state.ops.length;
  const el = $('#sync-badge');
  if (el) {
    el.innerHTML = offline
      ? `<span class="sync-badge warn">${icon('offline', 14)} Hors ligne${pending ? ` · ${pending}` : ''}</span>`
      : syncing ? `<span class="sync-badge">${icon('sync', 14, 'spin')}</span>`
        : pending ? `<span class="sync-badge">${icon('sync', 14)} ${pending}</span>` : '';
  }
  const foot = $('#foot-status');
  if (foot) foot.textContent = syncText();
  if (store.meta.error) {
    toast(store.meta.error, { error: true, ms: 6000 });
    store.clearError();
  }
}

// ---------- Contenu ----------
function renderContent() {
  const content = $('#content');
  // Le bouton + du bas ajoute une tâche ; il n'a pas de sens dans les notes (elles ont leur bouton).
  $('.fab')?.toggleAttribute('hidden', route.name === 'notes' || route.name === 'note');
  if (route.name === 'notes') return notesView.renderList(content, route.id);
  if (route.name === 'note') return notesView.renderEditor(content, route.id);
  if (route.name === 'filters') return renderBrowse(content);
  const scrollTop = $('#scroller').scrollTop;
  const view = buildView(store.state, route, ui);
  const s = store.state;
  const keepComposer = content.dataset.view === viewKey() && $('#view-composer', content);
  const total = view.sections.reduce((n, sec) => n + sec.tasks.length, 0);

  const head = `<div class="view-head" ${view.color ? '' : ''}>
      <h1>${esc(view.title)}</h1>
      ${view.project ? `<button class="icon-btn" data-act="view-menu" aria-label="Options de la vue">${icon('more', 20)}</button>` : ''}
    </div>${view.subtitle ? `<div class="view-sub">${esc(view.subtitle)}</div>` : ''}`;

  const strip = view.upcoming ? weekStrip(view) : '';
  const sections = view.sections.map((sec) => sectionHtml(sec, view, s, ui)).join('');
  const empty = !total && view.empty && !(view.kind === 'upcoming')
    ? `<div class="empty"><div class="big">${view.empty.icon}</div><h2>${esc(view.empty.title)}</h2><p>${esc(view.empty.text)}</p></div>` : '';

  if (!keepComposer) {
    content.dataset.view = viewKey();
    content.innerHTML = `<div id="view-head"></div><div id="view-list"></div><div id="view-composer"></div>`;
    $('#view-composer').innerHTML = view.canAdd
      ? `<button class="add-inline" data-act="inline-add"><span class="plus">${icon('plus', 16)}</span>Ajouter une tâche</button>` : '';
  }
  $('#view-head', content).innerHTML = head + strip;
  $('#view-list', content).innerHTML = (sections || '') + empty;
  $('#scroller').scrollTop = keepComposer ? scrollTop : 0;
  document.title = `${view.title} – LieluTodo`;
  currentView = view;
}
let currentView = null;
const viewKey = () => `${route.name}:${route.id ?? ''}`;

function renderBrowse(content) {
  const s = store.state;
  content.dataset.view = viewKey();
  const open = openTasks(s);
  content.innerHTML = `
    <div class="view-head"><h1>Filtres et étiquettes</h1></div>
    <div class="sec-head"><span>Filtres</span><span class="grow"></span></div>
    ${s.filters.map((f) => `<a class="nav-item" href="#/filter/${f.id}">${icon('filters', 18)}<span class="label">${esc(f.title)}</span></a>`).join('') || '<div class="view-sub" style="margin:10px 0">Aucun filtre enregistré sur le serveur.</div>'}
    <div class="sec-head"><span>Étiquettes</span><span class="grow"></span><button class="icon-btn" data-act="add-label" aria-label="Nouvelle étiquette">${icon('plus', 16)}</button></div>
    ${s.labels.map((l) => `<a class="nav-item" href="#/label/${l.id}">${icon('tag', 18)}<span class="label">${esc(l.title)}</span><span class="count">${open.filter((t) => t.labels.some((x) => x.id === l.id)).length || ''}</span></a>`).join('') || '<div class="view-sub" style="margin:10px 0">Aucune étiquette.</div>'}`;
  document.title = 'Filtres et étiquettes – LieluTodo';
  currentView = null;
}

// ---------- Actions ----------
function completeTask(id, done) {
  const t = store.task(id);
  if (!t) return;
  const row = $(`.task[data-id="${id}"]`);
  const run = () => {
    const prev = { ...t };
    const next = store.setDone(id, done);
    if (done) {
      const recurring = next && !next.done;
      toast(recurring ? `Reporté au ${dueText(next.due).toLowerCase()}` : 'Tâche terminée', {
        action: 'Annuler',
        onAction: () => (recurring ? store.updateTask(prev, false, false) : store.setDone(id, false)),
      });
    }
  };
  if (row && done) { row.classList.add('leaving'); setTimeout(run, 220); } else run();
}

function openQuickAdd(opts = {}) {
  closePopover();
  const m = modal('<div class="modal-head"><span class="grow">Ajout rapide</span><button class="icon-btn" data-close aria-label="Fermer">' + icon('close') + '</button></div><div data-composer></div>', { sheet: true });
  m.root.addEventListener('click', (e) => { if (e.target.closest('[data-close]')) m.close(); });
  const defaults = currentView?.project ? currentView.project.id : 0;
  mountComposer($('[data-composer]', m.root), ctx, {
    modal: true,
    projectId: opts.projectId ?? defaults,
    due: opts.due ?? currentView?.addDue ?? null,
    onCancel: () => m.close(),
    onSubmit: (id, pid) => {
      m.close();
      const p = store.state.projects.find((x) => x.id === pid);
      toast(`Ajoutée à ${p ? p.title : 'votre liste'}`, { action: 'Voir', onAction: () => openTaskDetail(id) });
    },
  });
}

function openTaskDetail(id) {
  if (detail) detail.close();
  detail = openDetail(ctx, id);
}

function openSearch() {
  closePopover();
  const m = modal(`
    <div class="search-box">${icon('search', 20)}<input placeholder="Rechercher une tâche, un projet, une étiquette…" aria-label="Rechercher" autocomplete="off"><button class="icon-btn" data-close aria-label="Fermer">${icon('close')}</button></div>
    <div class="search-results"></div>`);
  const input = $('input', m.root);
  const out = $('.search-results', m.root);
  let items = [];
  let sel = 0;
  const draw = () => {
    const q = input.value.trim();
    const s = store.state;
    items = [];
    if (!q) {
      items.push(...[['Boîte de réception', '#/inbox', 'inbox'], ["Aujourd'hui", '#/today', 'today'], ['À venir', '#/upcoming', 'upcoming'], ['Terminées', '#/completed', 'checkCircle']]
        .map(([label, href, ic]) => ({ label, href, icon: ic })));
    } else {
      const f = fold(q);
      s.projects.filter((p) => fold(p.title).includes(f)).slice(0, 5).forEach((p) => items.push({ label: p.title, href: `#/project/${p.id}`, icon: 'hash', sub: 'Projet' }));
      s.labels.filter((l) => fold(l.title).includes(f)).slice(0, 5).forEach((l) => items.push({ label: l.title, href: `#/label/${l.id}`, icon: 'tag', sub: 'Étiquette' }));
      searchTasks(s.tasks, s.projects, q).sort((a, b) => Number(a.done) - Number(b.done)).slice(0, 30).forEach((t) => {
        const p = s.projects.find((x) => x.id === t.projectId);
        items.push({ label: t.title, task: t.id, icon: 'checkCircle', sub: `${p ? p.title : ''}${t.due != null ? ` · ${dueText(t.due)}` : ''}${t.done ? ' · terminée' : ''}` });
      });
    }
    sel = Math.min(sel, Math.max(0, items.length - 1));
    out.innerHTML = items.length
      ? items.map((it, i) => `<button class="res-item${i === sel ? ' sel' : ''}" data-i="${i}">${icon(it.icon, 18)}<span class="grow">${esc(it.label)}</span><span class="sub">${esc(it.sub || '')}</span></button>`).join('')
      : '<div class="empty" style="padding:28px">Aucun résultat</div>';
  };
  const choose = (i) => {
    const it = items[i];
    if (!it) return;
    m.close();
    if (it.task) openTaskDetail(it.task); else go(it.href);
  };
  input.addEventListener('input', () => { sel = 0; draw(); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); sel = Math.min(sel + 1, items.length - 1); draw(); $('.res-item.sel', out)?.scrollIntoView({ block: 'nearest' }); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); sel = Math.max(sel - 1, 0); draw(); $('.res-item.sel', out)?.scrollIntoView({ block: 'nearest' }); }
    else if (e.key === 'Enter') { e.preventDefault(); choose(sel); }
  });
  m.root.addEventListener('click', (e) => {
    if (e.target.closest('[data-close]')) return m.close();
    const b = e.target.closest('[data-i]');
    if (b) choose(Number(b.dataset.i));
  });
  draw();
  input.focus();
}

async function addProject() {
  const r = await promptDialog({ title: 'Nouveau projet', placeholder: 'Nom du projet', confirm: 'Ajouter', colors: PROJECT_COLORS, color: PROJECT_COLORS[16] });
  if (!r) return;
  const id = store.createProject(r.value, r.color);
  go(`#/project/${id}`);
}

async function projectMenu(anchor, id) {
  const p = store.state.projects.find((x) => x.id === id);
  if (!p) return;
  const v = await menu(anchor, [
    { value: 'edit', label: 'Modifier', icon: 'edit' },
    { value: 'fav', label: p.isFavorite ? 'Retirer des favoris' : 'Ajouter aux favoris', icon: 'star' },
    { sep: true },
    { value: 'del', label: 'Supprimer le projet', icon: 'trash', danger: true },
  ]);
  if (v === 'edit') {
    const r = await promptDialog({ title: 'Modifier le projet', value: p.title, colors: PROJECT_COLORS, color: colorOf(p.hex, '') });
    if (!r) return;
    await guardOnline(() => store.updateProject(id, (j) => { j.title = r.value; if (r.color) j.hex_color = r.color.replace('#', ''); }));
  } else if (v === 'fav') {
    await guardOnline(() => store.updateProject(id, (j) => { j.is_favorite = !p.isFavorite; }));
  } else if (v === 'del') {
    if (!(await confirmDialog(`Supprimer le projet « ${p.title} » et toutes ses tâches ?`))) return;
    await guardOnline(async () => { await store.deleteProject(id); if (route.name === 'project' && route.id === id) go('#/inbox'); });
  }
}

async function guardOnline(fn) {
  try { await fn(); } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Cette action demande une connexion au serveur.', { error: true });
  }
}

function openSettings() {
  const theme = LS.get('theme', 'auto');
  const m = modal(`
    <div class="modal-head"><span class="grow">Réglages</span><button class="icon-btn" data-close aria-label="Fermer">${icon('close')}</button></div>
    <div class="modal-body" style="display:grid;gap:16px">
      <div><div class="menu-sub" style="padding-left:0">Compte</div><div>${esc(store.state.user?.name || store.state.user?.username || '')}</div><div class="view-sub" style="margin:2px 0 0">${esc(sess.baseUrl)}${sess.viaProxy ? ' (via ce site)' : ''}</div></div>
      <div><div class="menu-sub" style="padding-left:0">Thème</div>
        <div style="display:flex;gap:6px">${[['auto', 'Auto'], ['light', 'Clair'], ['dark', 'Sombre']].map(([v, l]) => `<button class="btn${theme === v ? ' primary' : ''}" data-theme="${v}">${l}</button>`).join('')}</div></div>
      <div><div class="menu-sub" style="padding-left:0">Notes Joplin</div>
        ${notes.connected
    ? `<div>${esc(notes.connection?.email || '')}</div><div class="view-sub" style="margin:2px 0 6px">${esc(notes.connection?.baseUrl || '')}${notes.connection?.viaProxy ? ' (via ce site)' : ''}</div><button class="btn" data-notes-disconnect>Déconnecter les notes</button>`
    : '<div class="view-sub" style="margin:0 0 6px">Non connectées.</div><a class="btn" href="#/notes" data-close>Connecter mes notes</a>'}</div>
      <div><div class="menu-sub" style="padding-left:0">Données</div>
        <div style="display:flex;gap:6px;flex-wrap:wrap"><button class="btn" data-export>${icon('download', 16)} Exporter</button><button class="btn" data-import>${icon('upload', 16)} Importer</button><input type="file" accept="application/json,.json" hidden></div>
        <div class="view-sub" style="margin:6px 0 0">Raccourcis : <b>Q</b> ajout rapide · <b>/</b> recherche · <b>G</b> puis <b>I</b>/<b>T</b>/<b>U</b>/<b>N</b> pour aller à Boîte de réception/Aujourd'hui/À venir/Notes · dans une note : <b>Ctrl+B</b>/<b>I</b>/<b>K</b>, <b>Ctrl+Z</b> · <b>M</b> menu.</div></div>
      <div style="display:flex;gap:6px"><button class="btn danger" data-logout>${icon('logout', 16)} Se déconnecter</button></div>
    </div>`, { sheet: true });
  m.root.addEventListener('click', (e) => {
    if (e.target.closest('[data-close]')) return m.close();
    if (e.target.closest('[data-notes-disconnect]')) {
      const unsent = notes.state.notes.filter((n) => n.dirty).length;
      confirmDialog(unsent
        ? `Déconnecter les notes ? ${unsent} note${unsent > 1 ? 's' : ''} pas encore envoyée${unsent > 1 ? 's' : ''} restera${unsent > 1 ? 'ont' : ''} dans ce navigateur.`
        : 'Déconnecter les notes Joplin de ce navigateur ?', 'Déconnecter', false).then((ok) => { if (ok) { notes.disconnect(); m.close(); } });
      return;
    }
    const th = e.target.closest('[data-theme]');
    if (th) { LS.set('theme', th.dataset.theme); applyTheme(); $$('[data-theme]', m.root).forEach((b) => b.classList.toggle('primary', b === th)); }
    if (e.target.closest('[data-export]')) {
      const blob = new Blob([store.exportJson()], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `lielutodo-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(a.href);
    }
    if (e.target.closest('[data-import]')) $('input[type=file]', m.root).click();
    if (e.target.closest('[data-logout]')) {
      // Déconnecte les tâches (Vikunja) ET les notes (Joplin) : sessions, caches et écritures en attente.
      const unsent = store.state.ops.length + store.state.failed.length
        + (notes ? notes.state.notes.filter((n) => n.dirty).length + (notes.state.deletedFolders || []).length
          + notes.state.folders.filter((f) => f.dirty).length : 0);
      const warning = unsent
        ? `Se déconnecter des tâches et des notes ? ${unsent} modification${unsent > 1 ? 's' : ''} pas encore sur le serveur ${unsent > 1 ? 'seront perdues' : 'sera perdue'}.`
        : 'Se déconnecter des tâches et des notes ?';
      confirmDialog(warning, 'Se déconnecter', unsent > 0).then(async (ok) => {
        if (!ok) return;
        // Côté serveur Vikunja : fin de la session et du cookie de renouvellement (au mieux, 3 s maximum).
        await Promise.race([vkApi?.('POST', '/user/logout').catch(() => {}), new Promise((r) => setTimeout(r, 3000))]);
        notes?.disconnect();
        notes?.reset();
        try { await globalThis.caches?.delete('lielutodo-joplin-resources'); } catch { /* pas de cache */ }
        session.clear(); store.reset(); location.hash = ''; location.reload();
      });
    }
  });
  $('input[type=file]', m.root).addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try { store.importJson(await file.text()); toast('Sauvegarde importée'); m.close(); } catch (ex) { toast(ex.message, { error: true }); }
  });
}

/** Modifications refusées par le serveur : renvoyer ou abandonner, rien n'est effacé sans accord. */
function openFailedOps() {
  const m = modal(`
    <div class="modal-head"><span class="grow">Modifications non envoyées</span><button class="icon-btn" data-close aria-label="Fermer">${icon('close')}</button></div>
    <div class="modal-body" data-list></div>
    <div class="modal-foot" data-foot></div>`, { sheet: true, onClose: () => unsubscribe() });
  const fmt = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
  const draw = () => {
    const list = store.state.failed;
    if (!list.length) { m.close(); return; }
    $('[data-list]', m.root).innerHTML = `
      <p class="view-sub" style="margin:0 0 8px">Le serveur a refusé ces modifications. Elles restent ici jusqu'à ce que vous les renvoyiez ou y renonciez.</p>
      ${list.map((f, i) => `<div class="failed-op">
        <div>${esc(describeWrite(f.op, store.state))}</div>
        <div class="failed-why">Refusée le ${esc(fmt.format(f.at))} : ${esc(f.message || 'erreur')} (HTTP ${f.code})</div>
        <div style="display:flex;gap:6px;justify-content:flex-end"><button class="btn" data-abandon="${i}">Abandonner</button><button class="btn primary" data-retry="${i}">Renvoyer</button></div>
      </div>`).join('')}`;
    $('[data-foot]', m.root).innerHTML = list.length > 1 ? '<button class="btn" data-retry-all>Tout renvoyer</button>' : '';
  };
  const unsubscribe = store.subscribe(draw);
  draw();
  m.root.addEventListener('click', (e) => {
    if (e.target.closest('[data-close]')) return m.close();
    const r = e.target.closest('[data-retry]');
    if (r) store.retryFailed(store.state.failed[Number(r.dataset.retry)]);
    const a = e.target.closest('[data-abandon]');
    if (a) store.abandonFailed(store.state.failed[Number(a.dataset.abandon)]);
    if (e.target.closest('[data-retry-all]')) store.retryAllFailed();
  });
}

// ---------- Clics ----------
async function onClick(e) {
  const nav = e.target.closest('[data-nav]');
  const el = e.target.closest('[data-act]');
  if (!el && nav) { go(nav.dataset.nav); return; }
  if (!el) {
    const row = e.target.closest('.task');
    if (row) openTaskDetail(Number(row.dataset.id));
    return;
  }
  const act = el.dataset.act;
  const id = Number(el.dataset.id);
  switch (act) {
    case 'check': { const row = el.closest('.task'); const t = store.task(Number(row.dataset.id)); completeTask(t.id, !t.done); break; }
    case 'fold': e.stopPropagation(); ui.collapsedTasks.has(id) ? ui.collapsedTasks.delete(id) : ui.collapsedTasks.add(id); persistUi(); render(); break;
    case 'fold-project': e.stopPropagation(); ui.collapsedProjects.has(id) ? ui.collapsedProjects.delete(id) : ui.collapsedProjects.add(id); persistUi(); render(); break;
    case 'fold-section': { const k = el.dataset.key; ui.collapsedSections.has(k) ? ui.collapsedSections.delete(k) : ui.collapsedSections.add(k); persistUi(); render(); break; }
    case 'toggle-sidebar':
      if (matchMedia('(max-width: 860px)').matches) ui.sidebarOpen = !ui.sidebarOpen; else ui.sidebarCollapsed = !ui.sidebarCollapsed;
      persistUi(); render(); break;
    case 'scrim': ui.sidebarOpen = false; render(); break;
    case 'quick-add': ui.sidebarOpen = false; openQuickAdd(); break;
    case 'search': ui.sidebarOpen = false; openSearch(); break;
    case 'sync': store.refresh(); notes.sync(); toast('Synchronisation…', { ms: 1200 }); break;
    case 'settings': openSettings(); break;
    case 'failed-ops': ui.sidebarOpen = false; openFailedOps(); break;
    case 'add-project': addProject(); break;
    case 'add-label': {
      const r = await promptDialog({ title: 'Nouvelle étiquette', placeholder: 'Nom', confirm: 'Ajouter' });
      if (r) store.createLabel(r.value);
      break;
    }
    case 'project-menu': e.stopPropagation(); projectMenu(el, id); break;
    case 'user-menu': {
      const v = await menu(el, [{ value: 'settings', label: 'Réglages', icon: 'settings' }, { value: 'sync', label: 'Synchroniser', icon: 'sync' }]);
      if (v === 'settings') openSettings(); else if (v === 'sync') store.refresh();
      break;
    }
    case 'view-menu': {
      const p = currentView?.project;
      if (!p) break;
      const shown = ui.showDone.has(p.id);
      const v = await menu(el, [
        { value: 'done', label: shown ? 'Masquer les tâches terminées' : 'Afficher les tâches terminées', icon: 'eye' },
        { value: 'edit', label: 'Modifier le projet', icon: 'edit' },
      ]);
      if (v === 'done') { shown ? ui.showDone.delete(p.id) : ui.showDone.add(p.id); persistUi(); render(); }
      else if (v === 'edit') projectMenu(el, p.id);
      break;
    }
    case 'inline-add': {
      const host = $('#view-composer');
      mountComposer(host, ctx, {
        projectId: currentView?.projectId, due: currentView?.addDue ?? null,
        onCancel: () => { host.innerHTML = `<button class="add-inline" data-act="inline-add"><span class="plus">${icon('plus', 16)}</span>Ajouter une tâche</button>`; },
      });
      host.scrollIntoView({ block: 'center' });
      break;
    }
    case 'add-day': openQuickAdd({ due: Number(el.dataset.day) + 12 * 3600_000 }); break;
    case 'reschedule': {
      const n = store.rescheduleOverdue(startOfDay(Date.now()));
      toast(`${n} tâche${n > 1 ? 's' : ''} reportée${n > 1 ? 's' : ''} à aujourd'hui`);
      break;
    }
    case 'week-prev': ui.upcomingStart = addDays(ui.upcomingStart ?? startOfDay(Date.now()), -7); render(); break;
    case 'week-next': ui.upcomingStart = addDays(ui.upcomingStart ?? startOfDay(Date.now()), 7); render(); break;
    case 'week-day': ui.upcomingStart = Number(el.dataset.day); render(); break;
    default: break;
  }
}

// ---------- Glisser-déposer (ordre manuel d'un projet) ----------
let dragId = null;
function onDragStart(e) {
  const row = e.target.closest?.('.task[draggable=true]');
  if (!row) return;
  dragId = Number(row.dataset.id);
  e.dataTransfer.effectAllowed = 'move';
  e.dataTransfer.setData('text/plain', String(dragId));
  row.classList.add('dragging');
}
function clearDrop() {
  dragId = null;
  $$('.dragging, .drop-before, .drop-after').forEach((n) => n.classList.remove('dragging', 'drop-before', 'drop-after'));
  scheduleRender();
}
function onDragOver(e) {
  if (dragId == null) return;
  const row = e.target.closest('.task[draggable=true]');
  if (!row || Number(row.dataset.id) === dragId) return;
  e.preventDefault();
  const r = row.getBoundingClientRect();
  const before = e.clientY < r.top + r.height / 2;
  $$('.drop-before, .drop-after').forEach((n) => n.classList.remove('drop-before', 'drop-after'));
  row.classList.add(before ? 'drop-before' : 'drop-after');
}
function onDragLeave(e) { if (!e.relatedTarget || !e.relatedTarget.closest?.('.task')) $$('.drop-before, .drop-after').forEach((n) => n.classList.remove('drop-before', 'drop-after')); }
function onDrop(e) {
  if (dragId == null) return;
  const row = e.target.closest('.task[draggable=true]');
  if (!row) return;
  e.preventDefault();
  const targetId = Number(row.dataset.id);
  const r = row.getBoundingClientRect();
  const before = e.clientY < r.top + r.height / 2;
  const roots = (currentView?.sections[0]?.tasks || []).filter((t) => t.id !== dragId);
  const idx = roots.findIndex((t) => t.id === targetId);
  if (idx < 0) return clearDrop();
  const at = before ? idx : idx + 1;
  const prev = roots[at - 1];
  const next = roots[at];
  const position = prev && next ? (prev.position + next.position) / 2 : prev ? prev.position + 65536 : next.position - 65536;
  const moved = dragId;
  dragId = null;
  store.reorder(moved, position);
  clearDrop();
}

// ---------- Clavier ----------
let gPending = false;
function onKey(e) {
  if (e.key === 'Escape') {
    if (document.querySelector('.popover')) return;
    if (closeTopModal()) e.preventDefault();
    else if (ui.sidebarOpen) { ui.sidebarOpen = false; render(); }
    return;
  }
  const t = e.target;
  const typing = t.matches?.('input, textarea, select, [contenteditable]');
  if ((e.key === 'k' && (e.ctrlKey || e.metaKey)) || (e.key === '/' && !typing)) { e.preventDefault(); openSearch(); return; }
  if (typing || e.ctrlKey || e.metaKey || e.altKey || document.querySelector('.backdrop')) return;
  if (gPending) {
    gPending = false;
    const dest = { i: '#/inbox', t: '#/today', u: '#/upcoming', f: '#/filters', n: lastNotesHash() }[e.key];
    if (dest) { e.preventDefault(); go(dest); }
    return;
  }
  if (e.key === 'q' || e.key === 'a') { e.preventDefault(); openQuickAdd(); }
  else if (e.key === 'g') { gPending = true; setTimeout(() => { gPending = false; }, 1200); }
  else if (e.key === 'm') { e.preventDefault(); $('[data-act=toggle-sidebar]')?.click(); }
}

boot();
