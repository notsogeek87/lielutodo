// Source de vérité « local d'abord », comme TodoRepository.kt : chaque action modifie l'état local puis met une
// écriture en file ; la file part dans l'ordre, les ids temporaires (négatifs) sont remappés à la création.
// `refresh()` n'écrase jamais une écriture en attente. Le serveur est personnel et peut être coupé : tout reste
// lisible et modifiable hors ligne.
import { ApiError, NetworkError, fetchPaged } from './api.js';
import { nextOccurrence } from './dates.js';
import { textToHtml } from './richtext.js';
import {
  commentFromJson, isRecurring, labelFromJson, labelToJson, projectFromJson, taskFromJson, taskToRequest,
} from './model.js';

const STATE_KEY = 'lielutodo.state';
// Écritures pas encore acceptées par le serveur (en file ou refusées) : rangées à part et écrites en premier. Ce
// sont des saisies de l'utilisateur, pas du cache : un quota dépassé par le cache ne doit pas les emporter.
const PENDING_KEY = 'lielutodo.pending';
const POSITION_STEP = 65536;
const POSITIONS_EVERY_MS = 10 * 60_000;
// Erreurs 500 de suite sur une même écriture (le serveur répondant au reste) avant de la mettre de côté.
const MAX_SERVER_ERRORS = 3;
// Panne passagère : réessayer plus tard sans compter d'échec. 408 délai, 429 trop de requêtes, 502-504 proxy.
const TRANSIENT_CODES = new Set([408, 429, 502, 503, 504]);
// Écritures qui changent les champs de la tâche : sa version locale prime sur celle du serveur au rafraîchissement.
const TASK_FIELD_KINDS = new Set(['create', 'update', 'delete', 'position', 'relate']);

const emptyState = () => ({
  projects: [], labels: [], tasks: [], filters: [], filterResults: {}, comments: {}, ops: [],
  // Écritures refusées par le serveur : { op, code, message, at }, jusqu'à ce que l'utilisateur décide.
  failed: [],
  defaultProjectId: 0, lastSync: 0, user: null,
});

/** Liste du serveur, sauf ce que des écritures non envoyées contredisent (voir TodoRepository.mergeTasks). */
export function mergeTasks(server, s) {
  const writes = [...s.ops, ...s.failed.map((f) => f.op)].filter((o) => TASK_FIELD_KINDS.has(o.kind));
  const deleted = new Set(writes.filter((o) => o.kind === 'delete').map((o) => o.taskId));
  const touched = new Set(writes.map((o) => o.taskId));
  const local = s.tasks.filter((t) => touched.has(t.id));
  const localIds = new Set(local.map((t) => t.id));
  return [...server.filter((t) => !deleted.has(t.id) && !localIds.has(t.id)), ...local];
}

const unsentIds = (s, kind) => new Set([...s.ops, ...s.failed.map((f) => f.op)].filter((o) => o.kind === kind).map((o) => o.taskId));

/** Projets/étiquettes créés ici et pas encore acceptés : gardés, sinon leurs tâches n'auraient plus de projet. */
export const mergeCreated = (server, local, s, kind) => {
  const unsent = unsentIds(s, kind);
  return [...server, ...local.filter((x) => x.id < 0 && unsent.has(x.id))];
};

/** Ce que l'écriture voulait faire, en clair (liste des modifications refusées). */
export function describeWrite(op, s) {
  const t = s.tasks.find((x) => x.id === op.taskId);
  const task = t ? `« ${t.title} »` : 'une tâche';
  switch (op.kind) {
    case 'create': return `Nouvelle tâche ${task}`;
    case 'update': return `Modification de ${task}`;
    case 'delete': return 'Suppression d’une tâche';
    case 'createProject': return `Nouveau projet « ${op.text} »`;
    case 'createLabel': return `Nouvelle étiquette « ${op.text} »`;
    case 'comment': return `Commentaire sur ${task} : « ${op.text} »`;
    case 'relate': return `Sous-tâche ${task}`;
    case 'position': return `Ordre de ${task}`;
    default: return op.kind;
  }
}

export function createStore(api) {
  let state = emptyState();
  const meta = { loaded: false, syncing: false, offline: false, error: null };
  const listeners = new Set();
  let nextTempId = -1;
  let saveTimer = null;
  let flushTimer = null;
  let syncing = Promise.resolve();
  let positionsSyncedAt = 0;

  const emit = () => {
    scheduleSave();
    listeners.forEach((fn) => fn());
  };
  const set = (patch) => {
    state = { ...state, ...patch };
    emit();
  };
  const setMeta = (patch) => {
    Object.assign(meta, patch);
    listeners.forEach((fn) => fn());
  };

  function scheduleSave() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 150);
  }
  let storageWarned = false;
  const trySet = (key, value) => {
    try {
      localStorage.setItem(key, value);
      return true;
    } catch {
      return false;
    }
  };

  /** Les écritures non envoyées ET ce qu'elles visent (tâches touchées, projets/étiquettes créés ici). */
  function pendingSnapshot() {
    const writes = [...state.ops, ...state.failed.map((f) => f.op)];
    const ids = new Set(writes.flatMap((o) => [o.taskId, o.otherId]));
    return {
      ops: state.ops,
      failed: state.failed,
      tasks: state.tasks.filter((t) => ids.has(t.id)),
      projects: state.projects.filter((p) => p.id < 0),
      labels: state.labels.filter((l) => l.id < 0),
    };
  }

  function save() {
    const { ops, failed, ...cache } = state;
    const pending = JSON.stringify(pendingSnapshot());
    let pendingSaved = trySet(PENDING_KEY, pending);
    if (!pendingSaved) {
      // Quota dépassé : le cache (relu du serveur) cède sa place aux saisies pas encore envoyées.
      try { localStorage.removeItem(STATE_KEY); } catch { /* rien */ }
      pendingSaved = trySet(PENDING_KEY, pending);
    }
    // Un cache trop gros n'est pas grave : l'ancien reste, et les écritures en attente sont rangées à part.
    trySet(STATE_KEY, JSON.stringify(cache));
    if (!pendingSaved && !storageWarned) {
      storageWarned = true;
      setMeta({
        error: 'Stockage du navigateur plein : les modifications pas encore envoyées seraient perdues en fermant la page. '
          + 'Reconnectez le serveur ou libérez de la place.',
      });
    } else if (pendingSaved) {
      storageWarned = false;
    }
  }

  function load() {
    try {
      const saved = JSON.parse(localStorage.getItem(STATE_KEY));
      if (saved) {
        state = { ...emptyState(), ...saved };
        meta.loaded = true;
      }
    } catch { /* cache illisible : on repart de zéro */ }
    try {
      // Anciennes versions : la file était dans le cache ; la clé dédiée fait foi dès qu'elle existe.
      const p = JSON.parse(localStorage.getItem(PENDING_KEY));
      if (p) {
        const overlay = (list, extra = []) => [...list.filter((x) => !extra.some((y) => y.id === x.id)), ...extra];
        state = {
          ...state,
          ops: p.ops || [],
          failed: p.failed || [],
          tasks: overlay(state.tasks, p.tasks),
          projects: overlay(state.projects, p.projects),
          labels: overlay(state.labels, p.labels),
        };
      }
    } catch { /* illisible : on garde ce que le cache contenait */ }
    const lowest = Math.min(0, ...state.tasks.map((t) => t.id), ...state.projects.map((p) => p.id),
      ...state.labels.map((l) => l.id), ...[...state.ops, ...state.failed.map((f) => f.op)].map((o) => o.taskId));
    nextTempId = Math.min(-1, lowest - 1);
  }
  window.addEventListener('pagehide', save);

  const taskById = (id) => state.tasks.find((t) => t.id === id);
  const lastPosition = (projectId) =>
    Math.max(0, ...state.tasks.filter((t) => t.projectId === projectId).map((t) => t.position));

  function enqueue(ops, op) {
    const same = (o) => o.kind === op.kind && o.taskId === op.taskId;
    if (op.kind === 'update') {
      // Une tâche pas encore créée porte déjà tout dans son CREATE ; un UPDATE existant est fusionné.
      if (ops.some((o) => o.kind === 'create' && o.taskId === op.taskId)) return ops;
      const existing = ops.find(same);
      if (!existing) return [...ops, op];
      return ops.map((o) => (o === existing ? { ...o, flag: o.flag || op.flag, done: op.done !== undefined ? op.done : o.done } : o));
    }
    if (op.kind === 'position') return ops.some(same) ? ops : [...ops, op];
    return [...ops, op];
  }

  function flushSoon() {
    clearTimeout(flushTimer);
    flushTimer = setTimeout(() => syncPending(), 400);
  }

  // ---------- Actions ----------
  const actions = {
    addTask({ title, projectId, due = null, priority = 0, labels = [], description = '', parentId = 0, repeatAfter = 0, repeatMode = 0 }) {
      const id = nextTempId--;
      const task = {
        id, projectId, title, description, done: false, due, priority, labels, isFavorite: false,
        position: lastPosition(projectId) + POSITION_STEP, commentCount: 0, raw: { id, project_id: projectId },
        reminders: [], repeatAfter, repeatMode, parentId, assignees: [],
      };
      let ops = enqueue(state.ops, { kind: 'create', taskId: id });
      if (parentId) ops = enqueue(ops, { kind: 'relate', taskId: id, otherId: parentId });
      set({ tasks: [...state.tasks, task], ops });
      flushSoon();
      return id;
    },

    /** Remplace la tâche locale du même id ; `labelsChanged` = les étiquettes ont changé. */
    updateTask(task, labelsChanged = false, done = undefined) {
      set({
        tasks: state.tasks.map((t) => (t.id === task.id ? task : t)),
        ops: enqueue(state.ops, { kind: 'update', taskId: task.id, flag: labelsChanged, done }),
      });
      flushSoon();
    },

    /** Renvoie la tâche mise à jour. Une tâche répétée reste ouverte avec sa prochaine échéance. */
    setDone(id, done) {
      const t = taskById(id);
      if (!t) return null;
      let next = { ...t, done };
      if (done && isRecurring(t) && t.due != null) {
        next = { ...t, done: false, due: nextOccurrence(t.due, t.repeatAfter, t.repeatMode) };
      }
      // On envoie `done` réel : c'est le serveur qui fait avancer la récurrence, la réponse remplace le local.
      set({
        tasks: state.tasks.map((x) => (x.id === id ? next : x)),
        ops: enqueue(state.ops, { kind: 'update', taskId: id, flag: false, done }),
      });
      flushSoon();
      return next;
    },

    deleteTask(id) {
      const children = state.tasks.filter((t) => t.parentId === id).map((t) => t.id);
      [id, ...children].forEach((tid) => {
        const related = (o) => o.kind !== 'createProject' && o.kind !== 'createLabel'
          && (o.taskId === tid || (o.kind === 'relate' && o.otherId === tid));
        const ops = tid < 0 ? state.ops.filter((o) => !related(o)) : [...state.ops.filter((o) => !related(o)), { kind: 'delete', taskId: tid }];
        // Une écriture refusée sur une tâche supprimée n'a plus d'objet.
        set({ tasks: state.tasks.filter((t) => t.id !== tid), ops, failed: state.failed.filter((f) => !related(f.op)) });
      });
      flushSoon();
    },

    reorder(id, position) {
      if (!taskById(id)) return;
      set({
        tasks: state.tasks.map((t) => (t.id === id ? { ...t, position } : t)),
        ops: enqueue(state.ops, { kind: 'position', taskId: id }),
      });
      flushSoon();
    },

    createProject(title, hex = '') {
      const id = nextTempId--;
      const position = Math.max(0, ...state.projects.map((p) => p.position)) + POSITION_STEP;
      set({
        projects: [...state.projects, { id, title, parentId: 0, hex, isFavorite: false, isArchived: false, position, listViewId: 0 }],
        ops: enqueue(state.ops, { kind: 'createProject', taskId: id, text: title, color: hex }),
      });
      flushSoon();
      return id;
    },

    createLabel(title) {
      const label = { id: nextTempId--, title, hex: '' };
      set({ labels: [...state.labels, label], ops: enqueue(state.ops, { kind: 'createLabel', taskId: label.id, text: title }) });
      flushSoon();
      return label;
    },

    addComment(taskId, text) {
      const local = { id: nextTempId--, text, author: 'Moi', created: Date.now() };
      set({
        comments: { ...state.comments, [taskId]: [...(state.comments[taskId] || []), local] },
        tasks: state.tasks.map((t) => (t.id === taskId ? { ...t, commentCount: t.commentCount + 1 } : t)),
        ops: enqueue(state.ops, { kind: 'comment', taskId, text }),
      });
      flushSoon();
    },

    duplicateTask(id) {
      const t = taskById(id);
      if (!t) return null;
      return actions.addTask({ ...t, labels: t.labels });
    },

    /** Reporte à aujourd'hui toutes les tâches en retard (en gardant l'heure). Renvoie leur nombre. */
    rescheduleOverdue(todayStart) {
      const late = state.tasks.filter((t) => !t.done && t.due != null && t.due < todayStart);
      late.forEach((t) => {
        const d = new Date(t.due);
        const moved = new Date(todayStart);
        moved.setHours(d.getHours(), d.getMinutes(), 0, 0);
        actions.updateTask({ ...t, due: moved.getTime() });
      });
      return late.length;
    },

    // Gestion d'un projet : en ligne (rare, le serveur doit valider).
    async updateProject(id, change) {
      const json = (await api('GET', `/projects/${id}`)).json();
      change(json);
      await api('POST', `/projects/${id}`, { body: json });
      await actions.refresh();
    },
    async deleteProject(id) {
      await api('DELETE', `/projects/${id}`);
      set({ projects: state.projects.filter((p) => p.id !== id), tasks: state.tasks.filter((t) => t.projectId !== id) });
      await actions.refresh();
    },

    async loadComments(taskId) {
      if (taskId < 0) return;
      try {
        const list = (await api('GET', `/tasks/${taskId}/comments`)).json().map(commentFromJson);
        const pending = (state.comments[taskId] || []).filter((c) => c.id < 0);
        set({ comments: { ...state.comments, [taskId]: [...list, ...pending] } });
      } catch { /* hors ligne : les commentaires déjà connus restent affichés */ }
    },

    // ---- Écritures refusées : renvoyer ou abandonner ----

    /** Remet une écriture refusée en file (à la fin). */
    retryFailed(f) {
      if (!state.failed.includes(f)) return;
      set({ failed: state.failed.filter((x) => x !== f), ops: enqueue(state.ops, f.op) });
      flushSoon();
    },
    retryAllFailed() {
      set({ failed: [], ops: state.failed.reduce((ops, f) => enqueue(ops, f.op), state.ops) });
      flushSoon();
    },
    /**
     * Renonce à une écriture refusée : ce qu'elle avait créé ici disparaît (les tâches d'un projet abandonné passent
     * dans la boîte de réception) ; le reste reprend la version du serveur au rafraîchissement.
     */
    abandonFailed(f) {
      if (!state.failed.includes(f)) return;
      set({ failed: state.failed.filter((x) => x !== f) });
      const { op } = f;
      if (op.kind === 'create' && op.taskId < 0) actions.deleteTask(op.taskId);
      if (op.kind === 'createLabel') {
        set({
          labels: state.labels.filter((l) => l.id !== op.taskId),
          tasks: state.tasks.map((t) => ({ ...t, labels: t.labels.filter((l) => l.id !== op.taskId) })),
        });
      }
      if (op.kind === 'createProject') {
        const inbox = state.projects.some((p) => p.id === state.defaultProjectId) ? state.defaultProjectId
          : state.projects.find((p) => p.id > 0)?.id || 0;
        set({
          projects: state.projects.filter((p) => p.id !== op.taskId),
          tasks: state.tasks.map((t) => (t.projectId === op.taskId ? { ...t, projectId: inbox } : t)),
        });
      }
      if (op.kind === 'comment') {
        const list = state.comments[op.taskId] || [];
        const local = list.find((c) => c.id < 0 && c.text === op.text);
        if (local) {
          set({
            comments: { ...state.comments, [op.taskId]: list.filter((c) => c !== local) },
            tasks: state.tasks.map((t) => (t.id === op.taskId ? { ...t, commentCount: Math.max(0, t.commentCount - 1) } : t)),
          });
        }
      }
      return actions.refresh();
    },

    refresh() {
      syncing = syncing.then(doRefresh, doRefresh);
      return syncing;
    },
    syncPending: () => syncPending(),

    clearError: () => setMeta({ error: null }),

    reset() {
      state = emptyState();
      localStorage.removeItem(STATE_KEY);
      localStorage.removeItem(PENDING_KEY);
      listeners.forEach((fn) => fn());
    },
    exportJson: () => JSON.stringify({ format: 'lielutodo-web', version: 1, exportedAt: new Date().toISOString(), ...state }, null, 2),
    importJson(text) {
      const data = JSON.parse(text);
      if (data.format !== 'lielutodo-web') throw new Error('Ce fichier n’est pas une sauvegarde LieluTodo web.');
      const { format, version, exportedAt, ...rest } = data;
      state = { ...emptyState(), ...rest };
      emit();
      flushSoon();
    },
  };

  // ---------- Réseau ----------
  async function syncPending() {
    syncing = syncing.then(async () => {
      try {
        await flush();
      } catch (e) {
        report(e);
      }
    });
    return syncing;
  }

  function report(e) {
    if (e instanceof NetworkError || (e instanceof ApiError && e.code >= 500)) {
      setMeta({ offline: true });
    } else if (e instanceof ApiError && e.code === 401) {
      setMeta({ error: 'Session expirée, reconnectez-vous.' });
    } else {
      setMeta({ error: e.message || String(e) });
    }
  }

  async function doRefresh() {
    setMeta({ syncing: true });
    try {
      const clean = await flush();
      await pull(clean);
      setMeta({ loaded: true, offline: false });
      set({ lastSync: Date.now() });
    } catch (e) {
      report(e);
      setMeta({ loaded: true });
    } finally {
      setMeta({ syncing: false });
    }
  }

  /**
   * Envoie la file dans l'ordre. `true` si elle est vide à la fin. Comme TodoRepository.flush : une écriture refusée
   * (4xx, ou 500 répété alors que le serveur répond au reste) part dans `failed` au lieu de disparaître ou de
   * bloquer les suivantes ; une panne (réseau, 502-504, 408, 429, 500 partout) arrête l'envoi pour plus tard.
   */
  async function flush() {
    for (;;) {
      const op = state.ops[0];
      if (!op) return true;
      try {
        await apply(op);
      } catch (e) {
        if (!(e instanceof ApiError) || e.code === 401 || TRANSIENT_CODES.has(e.code)) throw e;
        if (e.code >= 500) {
          if (!(await serverAnswers())) throw e;
          if ((op.attempts || 0) + 1 < MAX_SERVER_ERRORS) {
            state = { ...state, ops: state.ops.map((o, i) => (i === 0 ? { ...o, attempts: (o.attempts || 0) + 1 } : o)) };
            emit();
            setMeta({ error: `Le serveur refuse une modification (${e.message}), nouvel essai plus tard` });
            return false;
          }
        }
        setAside(e);
        continue;
      }
      state = { ...state, ops: state.ops.slice(1) };
      emit();
    }
  }

  async function serverAnswers() {
    try {
      await api('GET', '/user');
      return true;
    } catch {
      return false;
    }
  }

  /** Sort l'écriture en tête (dans sa version à jour : ids remappés) et la garde avec la raison du refus. */
  function setAside(e) {
    const head = state.ops[0];
    if (!head) return;
    const { attempts, ...op } = head;
    state = { ...state, ops: state.ops.slice(1), failed: [...state.failed, { op, code: e.code, message: e.message || '', at: Date.now() }] };
    emit();
    setMeta({ error: `Modification refusée par le serveur (${e.message}) : voir « Modifications non envoyées »` });
  }

  const remap = (from, to) => {
    state = {
      ...state,
      tasks: state.tasks.map((t) => (t.id === from ? { ...t, id: to } : t.parentId === from ? { ...t, parentId: to } : t)),
      ops: state.ops.map((o) => ({ ...o, taskId: o.taskId === from ? to : o.taskId, otherId: o.otherId === from ? to : o.otherId })),
      failed: state.failed.map((f) => ({
        ...f, op: { ...f.op, taskId: f.op.taskId === from ? to : f.op.taskId, otherId: f.op.otherId === from ? to : f.op.otherId },
      })),
      comments: Object.fromEntries(Object.entries(state.comments).map(([k, v]) => [Number(k) === from ? to : k, v])),
    };
  };

  async function apply(op) {
    switch (op.kind) {
      case 'create': {
        // Id déjà positif : la tâche a été créée, seules ses étiquettes ont échoué. La recréer la doublerait.
        if (op.taskId > 0) return apply({ ...op, kind: 'update', flag: true });
        const local = taskById(op.taskId);
        if (!local) return;
        const body = taskToRequest(local);
        delete body.id;
        const created = taskFromJson((await api('PUT', `/projects/${local.projectId}/tasks`, { body })).json());
        // Remapper AVANT les étiquettes : si elles échouent, la tâche existe déjà sur le serveur sous son vrai id.
        remap(local.id, created.id);
        state = { ...state, tasks: state.tasks.map((t) => (t.id === created.id ? { ...t, raw: created.raw, commentCount: created.commentCount } : t)) };
        if (local.labels.length) await setLabels(created.id, local.labels);
        break;
      }
      case 'update': {
        const local = taskById(op.taskId);
        if (!local) return;
        const body = taskToRequest(local);
        if (op.done !== undefined) body.done = op.done;
        const saved = taskFromJson((await api('POST', `/tasks/${local.id}`, { body })).json());
        if (op.flag) await setLabels(local.id, local.labels);
        // Vikunja efface les affectations d'une tâche mise à jour sans `assignees` (champ en lecture seule ici) :
        // on remet celles que la réponse ne contient plus. Un échec (déjà affecté) est sans importance.
        const kept = new Set((saved.assignees || []).map((u) => u.id));
        for (const u of local.assignees || []) {
          if (kept.has(u.id)) continue;
          try {
            await api('PUT', `/tasks/${local.id}/assignees`, { body: { user_id: u.id } });
          } catch (e) {
            if (!(e instanceof ApiError)) throw e;
          }
        }
        // Une tâche répétée revient « non terminée » avec une nouvelle échéance : on prend la réponse, sauf si
        // l'utilisateur a encore modifié la tâche pendant l'envoi.
        state = {
          ...state,
          tasks: state.tasks.map((t) => {
            if (t.id !== local.id || t !== local) return t;
            return { ...saved, labels: t.labels, assignees: t.assignees, parentId: t.parentId, position: t.position };
          }),
        };
        break;
      }
      case 'delete':
        try {
          await api('DELETE', `/tasks/${op.taskId}`);
        } catch (e) {
          if (!(e instanceof ApiError && e.code === 404)) throw e;
        }
        break;
      case 'createProject': {
        const body = { title: op.text };
        if (op.color) body.hex_color = op.color.replace('#', '');
        const created = projectFromJson((await api('PUT', '/projects', { body })).json());
        state = {
          ...state,
          projects: state.projects.map((p) => (p.id === op.taskId ? created : p)),
          tasks: state.tasks.map((t) => (t.projectId === op.taskId ? { ...t, projectId: created.id } : t)),
        };
        break;
      }
      case 'createLabel': {
        const created = labelFromJson((await api('PUT', '/labels', { body: { title: op.text } })).json());
        state = {
          ...state,
          labels: state.labels.map((l) => (l.id === op.taskId ? created : l)),
          tasks: state.tasks.map((t) => ({ ...t, labels: t.labels.map((l) => (l.id === op.taskId ? created : l)) })),
        };
        break;
      }
      case 'comment':
        await api('PUT', `/tasks/${op.taskId}/comments`, { body: { comment: textToHtml(op.text) } });
        // Le commentaire local temporaire est remplacé par la vraie liste du serveur.
        try {
          const list = (await api('GET', `/tasks/${op.taskId}/comments`)).json().map(commentFromJson);
          state = { ...state, comments: { ...state.comments, [op.taskId]: list } };
        } catch {
          state = { ...state, comments: { ...state.comments, [op.taskId]: (state.comments[op.taskId] || []).filter((c) => c.id > 0) } };
        }
        break;
      case 'relate':
        await api('PUT', `/tasks/${op.taskId}/relations`, { body: { other_task_id: op.otherId, relation_kind: 'parenttask' } });
        break;
      case 'position': {
        const task = taskById(op.taskId);
        const view = task && state.projects.find((p) => p.id === task.projectId)?.listViewId;
        if (!task || !view) return;
        await api('POST', `/tasks/${task.id}/position`, { body: { position: task.position, project_view_id: view } });
        break;
      }
      default:
        break;
    }
  }

  const setLabels = (taskId, labels) =>
    api('POST', `/tasks/${taskId}/labels/bulk`, { body: { labels: labels.map((l) => ({ id: l.id })) } });

  async function pull(clean) {
    const projectJson = await fetchPaged(api, '/projects', { is_archived: 'false' });
    const projects = projectJson.filter((p) => p.id > 0).map(projectFromJson);
    const labels = (await fetchPaged(api, '/labels')).map(labelFromJson);
    const user = (await api('GET', '/user')).json();
    const defaultProjectId = user.settings?.default_project_id || 0;

    // Les filtres enregistrés apparaissent dans /projects avec un id négatif : -(id du filtre) - 1.
    const filters = [];
    for (const p of projectJson.filter((x) => x.id < -1)) {
      const id = -p.id - 1;
      try {
        const o = (await api('GET', `/filters/${id}`)).json();
        filters.push({ id, title: o.title || p.title, query: o.filters?.filter || '', includeNulls: !!o.filters?.filter_include_nulls });
      } catch {
        const old = state.filters.find((f) => f.id === id);
        if (old) filters.push(old);
      }
    }

    let tasks = null;
    let filterResults = null;
    if (clean) {
      const open = (await fetchPaged(api, '/tasks', { filter: 'done = false', sort_by: 'id' })).map(taskFromJson);
      let recent;
      try {
        recent = (await fetchPaged(api, '/tasks', { filter: 'done = true && done_at > now-30d', sort_by: 'id' })).map(taskFromJson);
      } catch {
        recent = state.tasks.filter((t) => t.done && t.id > 0);
      }
      const openIds = new Set(open.map((t) => t.id));
      tasks = await withPositions([...open, ...recent.filter((t) => !openIds.has(t.id))], projects);
      filterResults = {};
      for (const f of filters) {
        try {
          const res = await fetchPaged(api, '/tasks', { filter: f.query, filter_include_nulls: String(f.includeNulls) });
          filterResults[f.id] = res.map(taskFromJson).map((t) => t.id);
        } catch {
          filterResults[f.id] = state.filterResults[f.id] || [];
        }
      }
    }
    // Fusion avec l'état ACTUEL : une écriture faite pendant la lecture du serveur n'est pas écrasée.
    state = {
      ...state,
      projects: mergeCreated(projects, state.projects, state, 'createProject'),
      labels: mergeCreated(labels, state.labels, state, 'createLabel'),
      filters, defaultProjectId, user: { id: user.id, username: user.username, name: user.name || '' },
      tasks: tasks ? mergeTasks(tasks, state) : state.tasks, filterResults: filterResults ?? state.filterResults,
    };
    emit();
  }

  /** `/tasks` ne donne pas l'ordre manuel : il vit dans la vue « liste » de chaque projet, relue au plus toutes les 10 min. */
  async function withPositions(tasks, projects) {
    const now = Date.now();
    if (now - positionsSyncedAt < POSITIONS_EVERY_MS) {
      const known = new Map(state.tasks.map((t) => [t.id, t.position]));
      return tasks.map((t) => (known.has(t.id) ? { ...t, position: known.get(t.id) } : t));
    }
    const positions = new Map();
    let complete = true;
    for (const p of projects.filter((x) => x.listViewId > 0)) {
      try {
        (await fetchPaged(api, `/projects/${p.id}/views/${p.listViewId}/tasks`, { filter: 'done = false' }))
          .forEach((o) => positions.set(o.id, o.position ?? 0));
      } catch {
        complete = false;
      }
    }
    if (complete) positionsSyncedAt = now;
    return tasks.map((t) => (positions.has(t.id) ? { ...t, position: positions.get(t.id) } : t));
  }

  load();
  return {
    get state() { return state; },
    meta,
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    ...actions,
    task: taskById,
    labelToJson,
  };
}
