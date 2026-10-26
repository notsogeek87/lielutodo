import { htmlForServer, htmlToText } from './richtext.js';
import { parseDate, toIso, ZERO_DATE } from './dates.js';

export const REPEAT_MONTHLY = 1;
export const REPEAT_FROM_COMPLETION = 2;

/** Priorité Vikunja (0-5) -> niveau Todoist 1 (urgent) à 4 (aucune). */
export const todoistLevel = (priority) => (priority >= 4 ? 1 : priority === 3 ? 2 : priority === 2 ? 3 : 4);
export const vikunjaPriority = (level) => ({ 1: 4, 2: 3, 3: 2, 4: 0 })[level] ?? 0;

export function projectFromJson(o) {
  const views = o.views || [];
  const list = views.find((v) => v.view_kind === 'list');
  return {
    id: o.id,
    title: o.title || '',
    parentId: o.parent_project_id || 0,
    hex: o.hex_color || '',
    isFavorite: !!o.is_favorite,
    isArchived: !!o.is_archived,
    position: o.position ?? 0,
    listViewId: list ? list.id : o.list_view_id || 0,
  };
}

export const labelFromJson = (o) => ({ id: o.id, title: o.title || '', hex: o.hex_color || '' });
export const labelToJson = (l) => ({ id: l.id, title: l.title, hex_color: l.hex });
export const userFromJson = (o) => ({ id: o.id, username: o.username || '', name: o.name || '' });

export function taskFromJson(o) {
  const parents = o.related_tasks && o.related_tasks.parenttask;
  return {
    id: o.id,
    projectId: o.project_id || 0,
    title: o.title || '',
    description: htmlToText(o.description || ''),
    done: !!o.done,
    due: parseDate(o.due_date),
    priority: o.priority || 0,
    labels: (o.labels || []).map(labelFromJson),
    isFavorite: !!o.is_favorite,
    position: o.position ?? 0,
    commentCount: o.comment_count || 0,
    raw: o,
    reminders: (o.reminders || []).map((r) => ({
      at: parseDate(r.reminder),
      relativePeriod: r.relative_period || 0,
      relativeTo: r.relative_to || '',
    })),
    repeatAfter: o.repeat_after || 0,
    repeatMode: o.repeat_mode || 0,
    parentId: parents && parents.length ? parents[0].id : 0,
    assignees: (o.assignees || []).map(userFromJson),
  };
}

const reminderToJson = (r) =>
  r.relativeTo
    ? { reminder: ZERO_DATE, relative_period: r.relativePeriod, relative_to: r.relativeTo }
    : { reminder: toIso(r.at), relative_period: 0, relative_to: '' };

const READ_ONLY = [
  'labels', 'assignees', 'created_by', 'attachments', 'related_tasks', 'reactions', 'comments',
  'subscription', 'buckets', 'created', 'updated', 'comment_count', 'is_unread',
];

/** Corps d'une écriture : le JSON du serveur (qu'on ne connaît pas en entier) + nos modifications. */
export function taskToRequest(t) {
  const o = { ...(t.raw || {}) };
  Object.assign(o, {
    id: t.id,
    project_id: t.projectId,
    title: t.title,
    // Vikunja stocke du HTML : renvoyé tel quel si le texte n'a pas changé (mise en forme du site gardée).
    description: htmlForServer(t.description, (t.raw || {}).description),
    done: t.done,
    due_date: toIso(t.due),
    priority: t.priority,
    is_favorite: t.isFavorite,
    position: t.position,
    repeat_after: t.repeatAfter,
    repeat_mode: t.repeatMode,
    reminders: t.reminders.map(reminderToJson),
  });
  READ_ONLY.forEach((k) => delete o[k]);
  return o;
}

export function commentFromJson(o) {
  const a = o.author || {};
  return { id: o.id, text: htmlToText(o.comment || ''), author: a.name || a.username || '', created: parseDate(o.created) };
}

export const isRecurring = (t) => t.repeatAfter > 0 || t.repeatMode === REPEAT_MONTHLY;

/** Couleur CSS d'un projet/étiquette (Vikunja stocke l'hex sans « # »). */
export function colorOf(hex, fallback = '#808080') {
  // Valeur du serveur insérée dans des attributs style : on n'accepte qu'une couleur hexadécimale.
  if (typeof hex !== 'string' || !/^#?[0-9a-f]{3,8}$/i.test(hex)) return fallback;
  return hex.startsWith('#') ? hex : `#${hex}`;
}

/**
 * Fusion à trois à l'envoi d'une modification faite ici (port de mergeEdits, Models.kt) : `base` = la version du
 * serveur sur laquelle elle a été faite (`raw`), `local` = la nôtre, `server` = l'actuelle. Un champ changé ici
 * prend notre valeur, les autres celle du serveur : une modification faite ailleurs entre-temps n'est pas écrasée.
 */
export function mergeEdits(base, local, server) {
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const pick = (k) => (same(local[k], base[k]) ? server[k] : local[k]);
  const merged = { ...server, id: local.id, position: local.position, parentId: local.parentId };
  for (const k of ['projectId', 'title', 'description', 'done', 'due', 'priority', 'isFavorite', 'reminders',
    'repeatAfter', 'repeatMode', 'labels', 'assignees']) merged[k] = pick(k);
  return merged;
}
