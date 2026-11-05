// Notes Joplin « local d'abord » (port de FileNotesRepository + NotesSync + SyncPlanner) : tout se lit et se modifie
// hors ligne ; la synchro (delta + cursor) envoie les carnets puis les notes modifiés, garde les deux versions d'un
// conflit, met les suppressions à la corbeille Joplin. Une seule synchro à la fois ; une note modifiée pendant son envoi
// reste à envoyer.
import {
  JoplinAuthError, JoplinError, JoplinNetworkError, createJoplinApi, joplinLogin, newId, parseItem, patchItem,
  serializeFolder, serializeNote,
} from './joplin.js';

const STATE_KEY = 'lielutodo.notes';
const CONN_KEY = 'lielutodo.joplin';

// deletedFolders : carnets supprimés ici, en attente de corbeille Joplin (hors de `folders` : l'écran ne les voit plus).
const emptyState = () => ({ notes: [], folders: [], deletedFolders: [], cursor: null, lastSync: null });

/** Plan d'une synchro, sans effet de bord (mêmes règles que SyncPlanner.kt). */
export function planSync(local, pulled, remoteDeleted) {
  const byId = new Map(local.map((n) => [n.id, n]));
  // Brouillon vide jamais envoyé (note qu'on vient d'ouvrir) : rien à envoyer tant qu'on n'y a rien écrit.
  const toPush = local.filter((n) => n.dirty && !n.deleted && !(n.serverRaw == null && !n.title.trim() && !n.body.trim()));
  const toDeleteRemote = local.filter((n) => n.deleted);
  const toApplyRemote = [];
  const conflicts = [];
  for (const remote of pulled) {
    const l = byId.get(remote.id);
    if (!l) { toApplyRemote.push(remote); continue; }
    if (l.deleted) continue;
    if (!l.dirty) {
      const known = l.serverUpdatedTime > 0 ? l.serverUpdatedTime : l.userUpdatedTime;
      if (remote.updatedTime > known) toApplyRemote.push(remote);
      continue;
    }
    const changed = l.serverUpdatedTime === 0 || remote.updatedTime > l.serverUpdatedTime;
    if (changed && !(l.title === remote.title && l.body === remote.body)) conflicts.push(remote);
  }
  const toDeleteLocal = local.filter((n) => remoteDeleted.has(n.id) && !n.dirty).map((n) => n.id);
  return { toPush, toDeleteRemote, toApplyRemote, toDeleteLocal, conflicts };
}

const ignored = (name) => name.startsWith('locks/') || name.startsWith('temp/') || name.startsWith('.resource/');
const conflictLabel = (ms) => new Intl.DateTimeFormat('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }).format(ms);

export function createNotesStore({ storage = globalThis.localStorage, apiFactory = createJoplinApi, login = joplinLogin, now = () => Date.now() } = {}) {
  const read = (k) => { try { return JSON.parse(storage.getItem(k)); } catch { return null; } };
  let state = { ...emptyState(), ...(read(STATE_KEY) || {}) };
  state.deletedFolders ||= [];
  // Brouillon vide jamais envoyé (onglet fermé sur une nouvelle note) : rien à garder.
  state.notes = state.notes.filter((n) => n.serverRaw != null || n.deleted || n.title.trim() || n.body.trim());
  let conn = read(CONN_KEY);
  const meta = { syncing: false, progress: 0, error: null, needsLogin: false, offline: false, invitations: [], allInvitations: [] };
  const listeners = new Set();
  let api = conn?.sessionId ? apiFactory(conn) : null;
  let chain = Promise.resolve();

  const emit = () => listeners.forEach((fn) => { try { fn(); } catch { /* écran */ } });
  const persist = () => {
    try { storage.setItem(STATE_KEY, JSON.stringify(state)); } catch {
      meta.error = 'Stockage du navigateur plein : les dernières notes ne sont gardées que dans cet onglet.';
    }
    emit();
  };
  const setNotes = (fn) => { state = { ...state, notes: fn(state.notes) }; persist(); };
  const setFolders = (fn) => {
    state = { ...state, folders: fn(state.folders).sort((a, b) => a.title.localeCompare(b.title, 'fr')) };
    persist();
  };
  const note = (id) => state.notes.find((n) => n.id === id);
  const folder = (id) => state.folders.find((f) => f.id === id);
  /** Lit-modifie-écrit une note ; `fn(current)` rend la nouvelle (null = supprimer). */
  const updateNote = (id, fn) => setNotes((list) => {
    const next = fn(list.find((n) => n.id === id) || null);
    return [...list.filter((n) => n.id !== id), ...(next ? [next] : [])];
  });
  const updateFolder = (id, fn) => setFolders((list) => {
    const next = fn(list.find((f) => f.id === id) || null);
    return [...list.filter((f) => f.id !== id), ...(next ? [next] : [])];
  });
  /** Une seule opération serveur à la fois (synchro, partage). */
  const exclusive = (fn) => { const run = chain.then(fn, fn); chain = run.catch(() => {}); return run; };

  // ---------- Édition locale ----------
  function create(parentId = '') {
    const t = now();
    const n = {
      id: newId(), title: '', body: '', parentId: parentId || state.folders[0]?.id || '', userUpdatedTime: t,
      dirty: true, deleted: false, createdTime: t, serverRaw: null, serverUpdatedTime: 0,
    };
    updateNote(n.id, () => n);
    return n;
  }
  const save = (id, title, body) => updateNote(id, (c) => c && { ...c, title, body, userUpdatedTime: now(), dirty: true });
  const setParent = (id, parentId) => updateNote(id, (c) => c && { ...c, parentId, userUpdatedTime: now(), dirty: true });
  /** Supprimer : jamais envoyée = retirée tout de suite ; sinon corbeille Joplin à la prochaine synchro. */
  const remove = (id) => updateNote(id, (c) => (!c ? null : c.serverRaw == null ? null : { ...c, deleted: true, dirty: true, userUpdatedTime: now() }));
  function createFolder(title) {
    const t = now();
    const f = { id: newId(), title: title.trim(), parentId: '', shareId: '', dirty: true, createdTime: t, serverRaw: null };
    updateFolder(f.id, () => f);
    return f;
  }

  /**
   * Supprime un carnet, ses sous-carnets et leurs notes (hors ligne aussi) : corbeille Joplin à la prochaine synchro
   * (récupérables depuis Joplin). Jamais envoyé = simplement retiré.
   */
  function removeFolder(id) {
    const ids = new Set([id]);
    let added = true;
    while (added) {
      added = false;
      state.folders.forEach((f) => { if (ids.has(f.parentId) && !ids.has(f.id)) { ids.add(f.id); added = true; } });
    }
    const gone = state.folders.filter((f) => ids.has(f.id));
    state = {
      ...state,
      deletedFolders: [
        ...state.deletedFolders.filter((f) => !ids.has(f.id)),
        ...gone.map((f) => ({ id: f.id, title: f.title, parentId: f.parentId, serverRaw: f.serverRaw ?? null, sent: f.serverRaw != null || !f.dirty })),
      ],
    };
    setFolders((list) => list.filter((f) => !ids.has(f.id)));
    state.notes.filter((n) => ids.has(n.parentId) && !n.deleted).forEach((n) => remove(n.id));
  }

  // ---------- Connexion ----------
  async function connect(serverUrl, email, password) {
    conn = await login(serverUrl, email, password);
    try { storage.setItem(CONN_KEY, JSON.stringify(conn)); } catch { /* reste en mémoire */ }
    api = apiFactory(conn);
    meta.needsLogin = false;
    emit();
  }
  function disconnect() {
    conn = null;
    api = null;
    try { storage.removeItem(CONN_KEY); } catch { /* ok */ }
    emit();
  }

  // ---------- Synchro ----------
  /** Contenus de plusieurs items, quelques requêtes en parallèle (première synchro : des centaines de notes). */
  async function fetchAll(names, parallel = 6) {
    const out = new Map();
    let next = 0;
    const worker = async () => {
      while (next < names.length) {
        const name = names[next++];
        out.set(name, await api.itemContent(name));
        meta.progress++;
        if (meta.progress % 10 === 0) emit();
      }
    };
    await Promise.all(Array.from({ length: Math.min(parallel, names.length) }, worker));
    return out;
  }

  async function pull(cursor) {
    const acc = { pulled: new Map(), remoteDeleted: new Set(), names: new Map(), folders: [], deletedFolders: new Set(), defaultFolder: '', encrypted: 0 };
    const add = (name, content) => {
      const p = parseItem(content);
      if (!p) return;
      if (p.encrypted) { if (p.type === 1) acc.encrypted++; return; }
      if (p.type === 2) {
        if (p.deletedTime) acc.deletedFolders.add(p.id);
        else {
          acc.folders.push({ id: p.id, title: p.title, parentId: p.parentId, shareId: p.shareId, dirty: false, createdTime: 0, serverRaw: content });
          acc.defaultFolder ||= p.id;
        }
      } else if (p.type === 1) {
        if (p.deletedTime) { acc.remoteDeleted.add(p.id); acc.pulled.delete(p.id); } else {
          acc.names.set(p.id, name);
          acc.pulled.set(p.id, { id: p.id, title: p.title, body: p.body, parentId: p.parentId, updatedTime: p.updatedTime, raw: content });
        }
      }
    };
    let full = cursor == null;
    let resynced = false;
    let seen = 0;
    for (;;) {
      let page;
      try { page = await api.delta(cursor); } catch (e) {
        if (e.code === 'resyncRequired') {
          if (!resynced) { resynced = true; full = true; cursor = null; continue; }
        }
        throw e;
      }
      seen += page.items.length;
      const wanted = page.items.filter((c) => !ignored(c.name) && (c.type === 3 || c.name.endsWith('.md')));
      const contents = await fetchAll(wanted.filter((c) => c.type !== 3).map((c) => c.name));
      for (const change of wanted) {
        const idFromName = change.name.replace(/\.md$/, '').split('/').pop();
        if (change.type === 3) { acc.remoteDeleted.add(idFromName); acc.deletedFolders.add(idFromName); acc.pulled.delete(idFromName); continue; }
        add(change.name, contents.get(change.name));
      }
      if (page.cursor) cursor = page.cursor;
      if (!page.hasMore) break;
    }
    if (full && seen === 0) {
      for (let p = 1; ; p++) {
        const children = await api.rootChildren(p);
        const names = children.items.map((c) => c.name).filter((n) => !ignored(n) && n.endsWith('.md'));
        const contents = await fetchAll(names);
        names.forEach((n) => add(n, contents.get(n)));
        if (!children.hasMore) break;
      }
    }
    acc.folders.forEach((f) => acc.deletedFolders.delete(f.id));
    return { ...acc, cursor };
  }

  async function runSync() {
    if (!api) throw new JoplinAuthError('Notes Joplin non connectées (voir Réglages).');
    let cursor = state.cursor;
    if (cursor && (!state.folders.length || !state.notes.some((n) => !n.deleted))) cursor = null;
    const p = await pull(cursor);
    if (p.folders.length) {
      setFolders((list) => {
        const local = new Map(list.map((f) => [f.id, f]));
        const gone = new Set(state.deletedFolders.map((f) => f.id));
        p.folders.forEach((f) => { if (!local.get(f.id)?.dirty && !gone.has(f.id)) local.set(f.id, f); });
        return [...local.values()];
      });
    }
    if (p.deletedFolders.size) setFolders((list) => list.filter((f) => !p.deletedFolders.has(f.id)));

    const plan = planSync(state.notes, [...p.pulled.values()], p.remoteDeleted);
    let fallback = p.defaultFolder || state.folders[0]?.id || '';
    const copies = plan.conflicts.map((r) => {
      const t = now();
      const c = { id: newId(), title: `${r.title || 'Sans titre'} (conflit ${conflictLabel(t)})`, body: r.body, parentId: r.parentId, userUpdatedTime: t, dirty: true, deleted: false, createdTime: t, serverRaw: null, serverUpdatedTime: 0 };
      updateNote(c.id, () => c);
      return c;
    });

    for (const f of [...state.deletedFolders]) {
      let base = f.serverRaw;
      if (!base && f.sent) {
        try { base = await api.itemContent(`${f.id}.md`); } catch (e) { if (e.status !== 404) throw e; }
      }
      if (base) {
        const t = now();
        await api.putContent(`${f.id}.md`, patchItem(base, { title: f.title, body: '', parentId: f.parentId, updatedTime: t, deletedTime: t }));
      }
      state = { ...state, deletedFolders: state.deletedFolders.filter((x) => x.id !== f.id) };
      persist();
    }

    for (const f of state.folders.filter((x) => x.dirty)) {
      const t = now();
      const content = f.serverRaw
        ? patchItem(f.serverRaw, { title: f.title, body: '', parentId: f.parentId, updatedTime: t, shareId: f.shareId })
        : serializeFolder({ id: f.id, title: f.title, parentId: f.parentId, updatedTime: t, createdTime: f.createdTime || t, shareId: f.shareId });
      await api.putContent(`${f.id}.md`, content);
      updateFolder(f.id, (c) => c && (c.title !== f.title || c.shareId !== f.shareId ? { ...c, serverRaw: content } : { ...c, dirty: false, serverRaw: content }));
      fallback ||= f.id;
    }

    let pushed = 0;
    for (const n of [...plan.toPush, ...copies]) {
      let parent = n.parentId || fallback;
      if (!parent) {
        const f = createFolder('Notes');
        const t = now();
        const content = serializeFolder({ id: f.id, title: f.title, updatedTime: t });
        await api.putContent(`${f.id}.md`, content);
        updateFolder(f.id, (c) => c && { ...c, dirty: false, serverRaw: content });
        fallback = parent = f.id;
      }
      const shareId = folder(parent)?.shareId || '';
      const t = now();
      const base = p.pulled.get(n.id)?.raw ?? n.serverRaw;
      const content = base
        ? patchItem(base, { title: n.title, body: n.body, parentId: parent, updatedTime: t, shareId })
        : serializeNote({ id: n.id, parentId: parent, title: n.title, body: n.body, updatedTime: t, createdTime: n.createdTime || n.userUpdatedTime, shareId });
      await api.putContent(p.names.get(n.id) || `${n.id}.md`, content);
      updateNote(n.id, (c) => {
        if (!c) return null;
        const editedMeanwhile = c.title !== n.title || c.body !== n.body || c.parentId !== n.parentId || c.deleted;
        return editedMeanwhile
          ? { ...c, serverRaw: content, serverUpdatedTime: t }
          : { ...c, parentId: parent, dirty: false, serverRaw: content, serverUpdatedTime: t, userUpdatedTime: t };
      });
      pushed++;
    }

    for (const n of plan.toDeleteRemote) {
      const base = p.pulled.get(n.id)?.raw ?? n.serverRaw;
      if (base) {
        const t = now();
        await api.putContent(p.names.get(n.id) || `${n.id}.md`, patchItem(base, { title: n.title, body: n.body, parentId: n.parentId, updatedTime: t, deletedTime: t }));
      } else {
        try { await api.deleteItem(`${n.id}.md`); } catch (e) { if (e.status !== 404) throw e; }
      }
      updateNote(n.id, (c) => (c && !c.deleted ? c : null));
    }
    for (const r of plan.toApplyRemote) {
      updateNote(r.id, (c) => (c?.dirty ? c : {
        ...(c || { id: r.id, createdTime: r.updatedTime }),
        title: r.title, body: r.body, parentId: r.parentId, userUpdatedTime: r.updatedTime, dirty: false, deleted: false,
        serverRaw: r.raw, serverUpdatedTime: r.updatedTime,
      }));
    }
    plan.toDeleteLocal.forEach((id) => updateNote(id, (c) => (c?.dirty ? c : null)));
    state = { ...state, cursor: p.cursor, lastSync: now() };
    persist();
    return { pushed, pulled: plan.toApplyRemote.length, conflicts: plan.conflicts.length, encrypted: p.encrypted };
  }

  const guard = async (fn) => {
    try {
      const r = await fn();
      meta.offline = false;
      meta.error = null;
      return r;
    } catch (e) {
      if (e instanceof JoplinAuthError) { meta.needsLogin = true; meta.error = e.message; }
      else if (e instanceof JoplinNetworkError) { meta.offline = true; }
      else meta.error = e.message;
      throw e;
    }
  };

  function sync() {
    if (!api) return Promise.resolve(null);
    return exclusive(async () => {
      meta.syncing = true;
      meta.progress = 0;
      emit();
      try { return await guard(runSync); } catch { return null; } finally { meta.syncing = false; emit(); }
    });
  }

  // ---------- Pièces jointes (ressources Joplin) ----------
  // Fichier d'une ressource (`![…](:/<id>)`) : gardé dans le Cache Storage du navigateur pour rester visible hors
  // ligne ; une adresse blob: par ressource et par onglet. Échec (hors ligne, jamais téléchargée) = null, réessayé
  // au prochain affichage.
  const RES_CACHE = 'lielutodo-joplin-resources';
  const resUrls = new Map();
  function resourceUrl(id) {
    if (!/^[0-9a-f]{32}$/.test(id)) return Promise.resolve(null);
    if (!resUrls.has(id)) {
      const key = `/__joplin-resource/${id}`;
      const p = (async () => {
        let cache = null;
        try {
          cache = await globalThis.caches?.open(RES_CACHE);
          const hit = await cache?.match(key);
          if (hit) return URL.createObjectURL(await hit.blob());
        } catch { cache = null; }
        if (!api) return null;
        const blob = await api.resourceBlob(id);
        try { await cache?.put(key, new Response(blob)); } catch { /* pas de cache : affichage quand même */ }
        return URL.createObjectURL(blob);
      })().catch((e) => {
        if (e instanceof JoplinAuthError) { meta.needsLogin = true; meta.error = e.message; emit(); }
        return null;
      });
      p.then((url) => { if (!url) resUrls.delete(id); });
      resUrls.set(id, p);
    }
    return resUrls.get(id);
  }

  // ---------- Partage (en ligne) ----------
  const shareOp = (fn) => exclusive(() => guard(async () => {
    if (!api) throw new JoplinAuthError('Notes Joplin non connectées (voir Réglages).');
    try { return await fn(); } catch (e) {
      if (e instanceof JoplinAuthError || e instanceof JoplinNetworkError) throw e;
      if (/User not found/i.test(e.message)) throw new JoplinError('Aucun compte avec cette adresse sur ce serveur Joplin.');
      throw e;
    }
  }));
  const markShareId = (folderId, shareId) => {
    updateFolder(folderId, (c) => c && { ...c, shareId, dirty: true });
    state.notes.filter((n) => n.parentId === folderId && !n.deleted).forEach((n) => updateNote(n.id, (c) => c && { ...c, dirty: true }));
  };

  /** Ordre vérifié contre Joplin Server : synchro, partage, carnet + notes renvoyés avec share_id, puis invitation. */
  const shareFolder = (folderId, email) => shareOp(async () => {
    const f = folder(folderId);
    if (!f) throw new JoplinError('Carnet introuvable.');
    if (f.parentId) throw new JoplinError('Joplin ne partage que les carnets à la racine (pas les sous-carnets).');
    if (!email.includes('@')) throw new JoplinError('Adresse e-mail invalide.');
    await runSync();
    let shareId = folder(folderId)?.shareId;
    if (!shareId) {
      shareId = await api.createShare(folderId);
      markShareId(folderId, shareId);
      await runSync();
    }
    await api.addShareRecipient(shareId, email);
  });
  const shareRecipients = (folderId) => shareOp(async () => {
    const shareId = folder(folderId)?.shareId;
    return shareId ? api.shareRecipients(shareId) : [];
  });
  const removeShareRecipient = (id) => shareOp(() => api.removeShareRecipient(id));
  const stopSharing = (folderId) => shareOp(async () => {
    const shareId = folder(folderId)?.shareId;
    if (!shareId) return;
    await api.deleteShare(shareId);
    markShareId(folderId, '');
    await runSync();
  });
  /** Toutes les invitations (une refusée reste chez Joplin et peut encore être acceptée) ; `invitations` = en attente. */
  const refreshInvitations = () => shareOp(async () => {
    meta.allInvitations = await api.invitations();
    meta.invitations = meta.allInvitations.filter((i) => i.status === 'pending');
    emit();
    return meta.invitations;
  }).catch(() => meta.invitations);
  const respondToInvitation = (inv, accept) => shareOp(async () => {
    await api.respondToInvitation(inv.shareUserId, accept);
    meta.allInvitations = meta.allInvitations.map((i) => (i.shareUserId === inv.shareUserId ? { ...i, status: accept ? 'accepted' : 'rejected' } : i));
    meta.invitations = meta.allInvitations.filter((i) => i.status === 'pending');
    if (accept) await runSync();
    emit();
  });

  return {
    get state() { return state; },
    meta,
    get connection() { return conn; },
    get connected() { return !!api; },
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    note, folder, create, save, setParent, remove, createFolder, removeFolder,
    connect, disconnect, sync, resourceUrl,
    shareFolder, shareRecipients, removeShareRecipient, stopSharing, refreshInvitations, respondToInvitation,
    clearError() { meta.error = null; },
    reset() { state = emptyState(); try { storage.removeItem(STATE_KEY); } catch { /* ok */ } emit(); },
  };
}
