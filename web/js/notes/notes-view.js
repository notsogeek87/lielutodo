// Écrans Notes du site : liste (recherche, carnets, partage, invitations), éditeur Markdown (barre de mise en forme,
// listes prolongées, annuler/rétablir, aperçu) et connexion au serveur Joplin. Mêmes règles que l'APK.
import { icon } from '../icons.js';
import { $, confirmDialog, esc, modal, promptDialog, toast } from '../ui.js';
import { DEFAULT_NOTES_SERVER } from './joplin.js';
import * as md from './markdown.js';

const fmt = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' });
const HISTORY_PAUSE_MS = 1000;
const SAVE_DELAY_MS = 400;

const FOLDER_KEY = 'lielutodo.notesFolder';
/** Dernière liste de notes affichée (carnet choisi), retrouvée en revenant aux notes, même après rechargement. */
export function lastNotesHash() {
  try { const id = localStorage.getItem(FOLDER_KEY); return id ? `#/notes/${id}` : '#/notes'; } catch { return '#/notes'; }
}

export function createNotesView(store, go) {
  let query = '';
  let editor = null; // { id, initial, history, redo, lastEditAt, timer, last, preview }

  // ---------- Connexion ----------
  const connectForm = (title, text, { email = '', server = DEFAULT_NOTES_SERVER, onlyPassword = false } = {}) => `
    <form class="notes-connect" data-notes-connect>
      <h2>${esc(title)}</h2><p class="view-sub">${esc(text)}</p>
      <div class="fl" ${onlyPassword ? 'hidden' : ''}><label for="jp-srv">Serveur Joplin</label><input class="field" id="jp-srv" name="server" value="${esc(server)}" inputmode="url" autocapitalize="off" spellcheck="false" required></div>
      <div class="fl" ${onlyPassword ? 'hidden' : ''}><label for="jp-mail">E-mail du compte Joplin</label><input class="field" id="jp-mail" name="email" type="email" value="${esc(email)}" autocomplete="username" required></div>
      <div class="fl"><label for="jp-pwd">Mot de passe Joplin</label><input class="field" id="jp-pwd" name="password" type="password" autocomplete="current-password" required></div>
      <div class="err" role="alert"></div>
      <button class="btn primary">Connecter mes notes</button>
      <small class="view-sub">Le mot de passe ouvre une session puis n'est pas conservé.</small>
    </form>`;

  function bindConnect(root) {
    const form = $('[data-notes-connect]', root);
    if (!form) return;
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = form.elements;
      const btn = $('button', form);
      btn.disabled = true;
      btn.textContent = 'Connexion…';
      try {
        await store.connect(f.server.value, f.email.value.trim(), f.password.value);
        toast('Notes Joplin connectées');
        store.sync();
        store.refreshInvitations();
      } catch (ex) {
        $('.err', form).textContent = ex.message || 'Connexion impossible';
        btn.disabled = false;
        btn.textContent = 'Connecter mes notes';
      }
    });
  }

  // ---------- Liste ----------
  function renderList(content, folderId) {
    // Carnet mémorisé mais supprimé : retour à « Toutes ».
    if (folderId && store.state.folders.length && !store.folder(folderId)) { go('#/notes'); return; }
    try { localStorage.setItem(FOLDER_KEY, folderId || ''); } catch { /* confort */ }
    const key = `notes:${folderId || ''}`;
    if (content.dataset.view !== key) {
      content.dataset.view = key;
      content.innerHTML = '<div id="notes-head"></div><div id="notes-list"></div>';
      bindListEvents(content);
    }
    const s = store.state;
    const folder = folderId ? store.folder(folderId) : null;
    const connected = store.connected;
    const invitations = store.meta.invitations;
    const head = $('#notes-head', content);
    if (!$('#notes-search', head)) {
      head.innerHTML = `
        <div class="view-head"><h1 id="notes-title"></h1><span id="notes-actions" class="notes-actions"></span></div>
        <div class="search-box notes-search">${icon('search', 16)}<input class="field" id="notes-search" type="search" placeholder="Rechercher dans les notes" value="${esc(query)}" autocomplete="off"></div>
        <div class="notes-chips" id="notes-chips"></div>`;
      $('#notes-search', head).addEventListener('input', (e) => { query = e.target.value; drawList(content, folderId); });
    }
    $('#notes-title', head).textContent = folder ? folder.title : 'Notes';
    document.title = `${folder ? folder.title : 'Notes'} – LieluTodo`;
    $('#notes-actions', head).innerHTML = `
      ${invitations.length ? `<button class="btn" data-nact="invitations">${icon('mail', 16)} ${invitations.length} invitation${invitations.length > 1 ? 's' : ''}</button>` : ''}
      ${folder && connected && !folder.parentId ? `<button class="btn" data-nact="share">${icon('userPlus', 16)} Partager</button>` : ''}
      <button class="icon-btn" data-nact="new-folder" title="Nouveau carnet" aria-label="Nouveau carnet">${icon('folderPlus', 20)}</button>
      <button class="btn primary" data-nact="new-note">${icon('plus', 16)} Nouvelle note</button>`;
    $('#notes-chips', head).innerHTML = s.folders.length ? `
      <a class="pill${folderId ? '' : ' sel'}" href="#/notes">Toutes</a>
      ${s.folders.map((f) => `<a class="pill${f.id === folderId ? ' sel' : ''}" href="#/notes/${f.id}">${f.shareId ? icon('users', 14) : icon('folder', 14)} ${esc(f.title)}${f.dirty ? ' ·' : ''}</a>`).join('')}` : '';
    drawList(content, folderId);
  }

  function drawList(content, folderId) {
    const s = store.state;
    const q = query.trim().toLowerCase();
    const notes = s.notes
      .filter((n) => !n.deleted && (!folderId || n.parentId === folderId))
      .filter((n) => !q || n.title.toLowerCase().includes(q) || n.body.toLowerCase().includes(q))
      .sort((a, b) => b.userUpdatedTime - a.userUpdatedTime);
    const list = $('#notes-list', content);
    let banner = '';
    if (!store.connected) {
      banner = connectForm('Vos notes Joplin', 'Connectez votre serveur Joplin pour retrouver et modifier vos notes ici, comme dans l’app.');
    } else if (store.meta.needsLogin) {
      banner = connectForm('Session Joplin expirée', `Retapez le mot de passe de ${store.connection?.email || 'votre compte'}.`,
        { email: store.connection?.email, server: store.connection?.baseUrl, onlyPassword: true });
    }
    const folder = folderId ? store.folder(folderId) : null;
    const status = folder ? `<div class="view-sub">${folder.shareId ? `${icon('users', 14)} Carnet partagé` : folder.dirty ? 'Carnet pas encore envoyé à Joplin' : 'Carnet personnel'}</div>` : '';
    const empty = !notes.length
      ? `<div class="empty"><div class="big">${icon('note', 40)}</div><h2>${q ? 'Aucun résultat' : 'Aucune note'}</h2><p>${q ? `Rien ne correspond à « ${esc(query)} ».` : 'Créez votre première note avec « Nouvelle note ».'}</p></div>` : '';
    list.innerHTML = banner + status + notes.map((n) => {
      const f = store.folder(n.parentId);
      return `<a class="note-card" href="#/note/${n.id}">
        <div class="note-title">${esc(n.title || 'Sans titre')}</div>
        ${n.body.trim() ? `<div class="note-preview">${esc(md.plainPreview(n.body))}</div>` : ''}
        <div class="note-meta"><span>${esc(fmt.format(n.userUpdatedTime))}</span>${!folderId && f ? `<span>${icon(f.shareId ? 'users' : 'folder', 12)} ${esc(f.title)}</span>` : ''}${n.dirty ? `<span class="note-pending">${icon('offline', 12)} pas encore envoyée</span>` : ''}</div>
      </a>`;
    }).join('') + empty;
    bindConnect(list);
  }

  function bindListEvents(content) {
    // #content est le même élément pour toutes les pages : un seul écouteur, sinon un clic créerait plusieurs notes.
    if (content.dataset.notesBound) return;
    content.dataset.notesBound = '1';
    content.addEventListener('click', async (e) => {
      const b = e.target.closest('[data-nact]');
      if (!b) return;
      if (!content.dataset.view?.startsWith('notes:')) return;
      const folderId = content.dataset.view.split(':')[1] || '';
      switch (b.dataset.nact) {
        case 'new-note': { const n = store.create(folderId); go(`#/note/${n.id}`); break; }
        case 'new-folder': {
          const r = await promptDialog({ title: 'Nouveau carnet', placeholder: 'Nom du carnet', confirm: 'Créer' });
          if (r?.value?.trim()) { const f = store.createFolder(r.value); go(`#/notes/${f.id}`); store.sync(); }
          break;
        }
        case 'share': openShare(folderId); break;
        case 'invitations': openInvitations(); break;
        default: break;
      }
    });
  }

  // ---------- Partage ----------
  function openShare(folderId) {
    const f = store.folder(folderId);
    if (!f) return;
    const m = modal(`
      <div class="modal-head"><span class="grow">Partager « ${esc(f.title)} »</span><button class="icon-btn" data-close aria-label="Fermer">${icon('close')}</button></div>
      <div class="modal-body" style="display:grid;gap:10px">
        <p class="view-sub" style="margin:0">Invitez un compte du même serveur Joplin. Il accepte l'invitation dans Joplin ou LieluTodo, puis lit et modifie les notes de ce carnet.</p>
        <form class="share-form" data-invite><input class="field" name="email" type="email" placeholder="E-mail du compte Joplin" required><button class="btn primary">Inviter</button></form>
        <div class="err" role="alert" data-err></div>
        <div data-recipients><div class="view-sub">Chargement…</div></div>
      </div>
      <div class="modal-foot"><button class="btn danger" data-stop>Arrêter le partage</button><button class="btn" data-close>Fermer</button></div>`, { sheet: true });
    const err = (msg) => { $('[data-err]', m.root).textContent = msg || ''; };
    const load = async () => {
      try {
        const list = await store.shareRecipients(folderId);
        const label = { accepted: 'A accepté', rejected: 'A refusé', pending: 'Invitation en attente' };
        $('[data-recipients]', m.root).innerHTML = list.length
          ? list.map((r) => `<div class="share-row"><div class="grow"><div>${esc(r.email)}</div><div class="view-sub" style="margin:0">${label[r.status]}</div></div><button class="icon-btn" data-remove="${esc(r.shareUserId)}" aria-label="Retirer ${esc(r.email)}">${icon('userMinus', 18)}</button></div>`).join('')
          : '<div class="view-sub">Personne pour l’instant.</div>';
        $('[data-stop]', m.root).hidden = !store.folder(folderId)?.shareId;
      } catch (e) { err(e.message); }
    };
    load();
    $('[data-invite]', m.root).addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = e.target.elements.email;
      const btn = $('button', e.target);
      btn.disabled = true;
      err('');
      try {
        await store.shareFolder(folderId, input.value.trim());
        toast(`Invitation envoyée à ${input.value.trim()}`);
        input.value = '';
        await load();
      } catch (ex) { err(ex.message); }
      btn.disabled = false;
    });
    m.root.addEventListener('click', async (e) => {
      if (e.target.closest('[data-close]')) { m.close(); return; }
      const rm = e.target.closest('[data-remove]');
      if (rm) {
        try { await store.removeShareRecipient(rm.dataset.remove); await load(); } catch (ex) { err(ex.message); }
      }
      if (e.target.closest('[data-stop]')) {
        if (!(await confirmDialog('Arrêter le partage ? Les participants n’auront plus accès à ce carnet. Vos notes restent intactes.', 'Arrêter'))) return;
        try { await store.stopSharing(folderId); toast(`« ${f.title} » n’est plus partagé`); m.close(); } catch (ex) { err(ex.message); }
      }
    });
  }

  function openInvitations() {
    const m = modal(`
      <div class="modal-head"><span class="grow">Carnets partagés avec vous</span><button class="icon-btn" data-close aria-label="Fermer">${icon('close')}</button></div>
      <div class="modal-body" data-list></div>`, { sheet: true });
    const draw = () => {
      const list = store.meta.invitations;
      $('[data-list]', m.root).innerHTML = list.length ? list.map((inv, i) => `
        <div class="share-row"><div class="grow"><div>${esc(inv.ownerName || inv.ownerEmail)} vous partage un carnet</div><div class="view-sub" style="margin:0">${esc(inv.ownerEmail)}</div></div>
        <button class="btn" data-refuse="${i}">Refuser</button><button class="btn primary" data-accept="${i}">Accepter</button></div>`).join('')
        : '<p class="view-sub">Aucune invitation en attente.</p>';
    };
    draw();
    m.root.addEventListener('click', async (e) => {
      if (e.target.closest('[data-close]')) { m.close(); return; }
      const a = e.target.closest('[data-accept]');
      const r = e.target.closest('[data-refuse]');
      const inv = store.meta.invitations[Number((a || r)?.dataset.accept ?? (a || r)?.dataset.refuse)];
      if (!inv) return;
      try {
        await store.respondToInvitation(inv, !!a);
        toast(a ? 'Carnet partagé ajouté' : 'Invitation refusée');
        draw();
        if (!store.meta.invitations.length) m.close();
      } catch (ex) { toast(ex.message, { error: true }); }
    });
  }

  // ---------- Éditeur ----------
  function renderEditor(content, id) {
    const key = `note:${id}`;
    const n = store.note(id);
    if (!n || n.deleted) {
      if (editor?.id === id) editor = null;
      content.dataset.view = '';
      setTimeout(() => go('#/notes'), 0); // hors du rendu en cours
      return;
    }
    if (content.dataset.view === key && editor?.id === id) { drawEditorStatus(content); return; }
    content.dataset.view = key;
    editor = { id, initial: { title: n.title, body: n.body }, history: [], redo: [], lastEditAt: 0, timer: null, last: null, preview: false };
    document.title = `${n.title || 'Note'} – LieluTodo`;
    content.innerHTML = `
      <div class="note-editor">
        <div class="note-bar">
          <a class="icon-btn" href="${lastNotesHash()}" aria-label="Retour aux notes">${icon('chevronLeft', 20)}</a>
          <select class="field note-folder" aria-label="Carnet"></select>
          <span class="grow"></span>
          <button class="icon-btn" data-md="undo" title="Annuler (Ctrl+Z)" aria-label="Annuler">${icon('undo')}</button>
          <button class="icon-btn" data-md="redo" title="Rétablir (Ctrl+Maj+Z)" aria-label="Rétablir">${icon('redo')}</button>
          <button class="icon-btn" data-md="preview" title="Aperçu" aria-label="Aperçu">${icon('eye')}</button>
          <button class="icon-btn" data-md="delete" title="Supprimer la note" aria-label="Supprimer la note">${icon('trash')}</button>
        </div>
        <input class="note-title-input" maxlength="250" placeholder="Titre" value="${esc(n.title)}" aria-label="Titre">
        <div class="md-toolbar" role="toolbar" aria-label="Mise en forme">
          ${[['heading', 'Titre de section'], ['bold', 'Gras (Ctrl+B)'], ['italic', 'Italique (Ctrl+I)'], ['strike', 'Barré'], ['checkSquare', 'Case à cocher'], ['list', 'Liste à puces'], ['listOrdered', 'Liste numérotée'], ['quote', 'Citation'], ['code', 'Code'], ['link', 'Lien (Ctrl+K)']]
    .map(([k, label]) => `<button class="icon-btn" data-md="${k}" title="${label}" aria-label="${label}">${icon(k)}</button>`).join('')}
        </div>
        <textarea class="note-body" placeholder="Écrivez ici… (Markdown : la barre met en forme la ligne ou la sélection)" aria-label="Texte de la note"></textarea>
        <div class="note-preview-pane md" hidden></div>
        <div class="note-status view-sub"></div>
      </div>`;
    const title = $('.note-title-input', content);
    const body = $('.note-body', content);
    body.value = n.body;
    editor.last = { text: body.value, start: body.value.length, end: body.value.length };
    autosize(body);
    // Nouvelle note : on écrit tout de suite (focus immédiat, sinon les premières lettres tapées sont perdues).
    if (!n.title && !n.body) title.focus();

    title.addEventListener('input', () => scheduleSave(content));
    title.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); body.focus(); } });
    body.addEventListener('input', () => {
      const next = { text: body.value, start: body.selectionStart, end: body.selectionEnd };
      const t = Date.now();
      if (t - editor.lastEditAt > HISTORY_PAUSE_MS || !editor.history.length) pushHistory(editor.last);
      editor.lastEditAt = t;
      const fixed = md.continueList(editor.last, next);
      if (fixed !== next) apply(body, fixed);
      editor.last = { text: body.value, start: body.selectionStart, end: body.selectionEnd };
      autosize(body);
      scheduleSave(content);
    });
    body.addEventListener('select', () => { editor.last = { text: body.value, start: body.selectionStart, end: body.selectionEnd }; });
    body.addEventListener('keyup', () => { editor.last = { text: body.value, start: body.selectionStart, end: body.selectionEnd }; });
    body.addEventListener('click', () => { editor.last = { text: body.value, start: body.selectionStart, end: body.selectionEnd }; });
    body.addEventListener('keydown', (e) => {
      const mod = e.ctrlKey || e.metaKey;
      if (!mod) return;
      const k = e.key.toLowerCase();
      const map = { b: 'bold', i: 'italic', k: 'link' };
      if (map[k]) { e.preventDefault(); e.stopPropagation(); tool(content, map[k]); }
      else if (k === 'z' && !e.shiftKey) { e.preventDefault(); undo(content); }
      else if ((k === 'z' && e.shiftKey) || k === 'y') { e.preventDefault(); redo(content); }
    });
    $('.note-folder', content).addEventListener('change', (e) => { store.setParent(id, e.target.value); });
    content.querySelector('.note-editor').addEventListener('mousedown', (e) => { if (e.target.closest('.md-toolbar [data-md]')) e.preventDefault(); });
    content.querySelector('.note-editor').addEventListener('click', async (e) => {
      const b = e.target.closest('[data-md]');
      if (!b) return;
      const k = b.dataset.md;
      if (k === 'undo') undo(content);
      else if (k === 'redo') redo(content);
      else if (k === 'preview') togglePreview(content);
      else if (k === 'delete') {
        if (!(await confirmDialog('Supprimer cette note ? Elle sera placée dans la corbeille de Joplin à la prochaine synchronisation.', 'Supprimer'))) return;
        clearTimeout(editor.timer);
        editor = null;
        store.remove(id);
        go(lastNotesHash());
        store.sync();
      } else tool(content, k);
    });
    drawEditorStatus(content);
  }

  const apply = (ta, v) => {
    ta.value = v.text;
    ta.setSelectionRange(v.start, v.end);
  };
  const autosize = (ta) => { ta.style.height = 'auto'; ta.style.height = `${Math.max(ta.scrollHeight, 240)}px`; };
  function pushHistory(v) {
    editor.history.push(v);
    if (editor.history.length > 100) editor.history.shift();
    editor.redo = [];
  }
  function tool(content, k) {
    const ta = $('.note-body', content);
    if (editor.preview) togglePreview(content);
    const cur = { text: ta.value, start: ta.selectionStart, end: ta.selectionEnd };
    const t = {
      bold: () => md.toggleWrap(cur, '**'), italic: () => md.toggleWrap(cur, '*'), strike: () => md.toggleWrap(cur, '~~'),
      code: () => md.toggleWrap(cur, '`'), heading: () => md.toggleLinePrefix(cur, md.HEADING),
      list: () => md.toggleLinePrefix(cur, md.BULLET), listOrdered: () => md.toggleLinePrefix(cur, md.NUMBERED),
      checkSquare: () => md.toggleCheckbox(cur), quote: () => md.toggleLinePrefix(cur, md.QUOTE), link: () => md.insertLink(cur),
    }[k];
    if (!t) return;
    const next = t();
    if (next.text === cur.text && next.start === cur.start) return;
    pushHistory(cur);
    editor.lastEditAt = 0;
    apply(ta, next);
    ta.focus();
    editor.last = next;
    autosize(ta);
    scheduleSave(content);
  }
  function undo(content) {
    const prev = editor.history.pop();
    if (!prev) return;
    const ta = $('.note-body', content);
    editor.redo.push({ text: ta.value, start: ta.selectionStart, end: ta.selectionEnd });
    apply(ta, prev);
    editor.last = prev;
    editor.lastEditAt = 0;
    autosize(ta);
    scheduleSave(content);
  }
  function redo(content) {
    const next = editor.redo.pop();
    if (!next) return;
    const ta = $('.note-body', content);
    editor.history.push({ text: ta.value, start: ta.selectionStart, end: ta.selectionEnd });
    apply(ta, next);
    editor.last = next;
    editor.lastEditAt = 0;
    autosize(ta);
    scheduleSave(content);
  }
  function togglePreview(content) {
    editor.preview = !editor.preview;
    const pane = $('.note-preview-pane', content);
    const ta = $('.note-body', content);
    pane.hidden = !editor.preview;
    ta.hidden = editor.preview;
    $('.md-toolbar', content).classList.toggle('dim', editor.preview);
    if (editor.preview) pane.innerHTML = md.renderMarkdown(ta.value) || '<p class="view-sub">Note vide.</p>';
  }

  function scheduleSave(content) {
    clearTimeout(editor.timer);
    editor.dirty = true;
    drawEditorStatus(content);
    editor.timer = setTimeout(() => flushSave(content), SAVE_DELAY_MS);
  }
  function flushSave(content) {
    if (!editor) return;
    clearTimeout(editor.timer);
    const title = $('.note-title-input', content)?.value ?? editor.initial.title;
    const body = $('.note-body', content)?.value ?? editor.initial.body;
    if (title !== editor.initial.title || body !== editor.initial.body) {
      store.save(editor.id, title.replace(/\n/g, ' '), body);
      editor.initial = { title, body };
    }
    editor.dirty = false;
    drawEditorStatus(content);
  }

  function drawEditorStatus(content) {
    if (!editor) return;
    const n = store.note(editor.id);
    const sel = $('.note-folder', content);
    if (sel) {
      const folders = store.state.folders;
      const html = (n?.parentId ? '' : '<option value="">Choisir un carnet</option>')
        + folders.map((f) => `<option value="${esc(f.id)}"${f.id === n?.parentId ? ' selected' : ''}>${esc(f.title)}${f.shareId ? ' (partagé)' : ''}</option>`).join('');
      if (sel.dataset.html !== html) { sel.innerHTML = html; sel.dataset.html = html; }
      sel.hidden = !folders.length;
    }
    $('[data-md=undo]', content)?.toggleAttribute('disabled', !editor.history.length);
    $('[data-md=redo]', content)?.toggleAttribute('disabled', !editor.redo.length);
    const words = ($('.note-body', content)?.value || '').split(/\s+/).filter(Boolean).length;
    const state = editor.dirty ? 'Enregistrement…' : n?.dirty ? 'Enregistrée · pas encore envoyée à Joplin' : 'Enregistrée';
    const el = $('.note-status', content);
    if (el) el.textContent = `${state} · ${words} mot${words > 1 ? 's' : ''}`;
  }

  /**
   * Sortie de l'éditeur (autre page) : enregistre la dernière frappe, donne un titre à une note qui n'en a pas,
   * supprime une note restée vide, puis synchronise.
   */
  function leave() {
    if (!editor) return;
    const content = document.getElementById('content');
    if (content?.dataset.view === `note:${editor.id}`) flushSave(content);
    const n = store.note(editor.id);
    editor = null;
    if (!n || n.deleted) return;
    if (!n.title.trim() && n.body.trim()) store.save(n.id, md.titleFromBody(n.body), n.body);
    else if (!n.title.trim() && !n.body.trim()) store.remove(n.id);
    store.sync();
  }

  /** Fermeture de l'onglet : au moins la dernière frappe est gardée en local. */
  function flushOnHide() {
    const content = document.getElementById('content');
    if (editor && content?.dataset.view === `note:${editor.id}`) flushSave(content);
  }

  return { renderList, renderEditor, leave, flushOnHide };
}

