// Construction des vues (Boîte de réception, Aujourd'hui, À venir, projet, étiquette, filtre, terminées) : de pures
// fonctions état -> description, rendues ensuite en HTML.
import { colorOf, isRecurring, todoistLevel } from './model.js';
import {
  addDays, dayHeading, dueKind, dueText, repeatLabel, startOfDay, weekdayShort, DAY_MS,
} from './dates.js';
import { icon } from './icons.js';
import { esc } from './ui.js';

export const INBOX_TITLES = ['inbox', 'boite de reception', 'boîte de réception'];

export function inboxProject(state) {
  return state.projects.find((p) => p.id === state.defaultProjectId)
    || state.projects.find((p) => INBOX_TITLES.includes(p.title.toLowerCase()))
    || state.projects[0];
}

const byDueThenPriority = (a, b) => (a.due ?? Infinity) - (b.due ?? Infinity) || b.priority - a.priority || a.id - b.id;
const byPosition = (a, b) => a.position - b.position || a.id - b.id;
const doneAt = (t) => Date.parse(t.raw?.done_at) || 0;

export const openTasks = (state) => state.tasks.filter((t) => !t.done);

/** Tâches d'un projet : racines triées, chaque racine suivie de ses sous-tâches. */
export function projectTree(tasks, showDone) {
  const visible = tasks.filter((t) => showDone || !t.done);
  const ids = new Set(visible.map((t) => t.id));
  const children = new Map();
  visible.filter((t) => t.parentId && ids.has(t.parentId)).forEach((t) => {
    if (!children.has(t.parentId)) children.set(t.parentId, []);
    children.get(t.parentId).push(t);
  });
  const roots = visible.filter((t) => !t.parentId || !ids.has(t.parentId))
    .sort((a, b) => Number(a.done) - Number(b.done) || byPosition(a, b));
  children.forEach((list) => list.sort((a, b) => Number(a.done) - Number(b.done) || byPosition(a, b)));
  return { roots, children };
}

/**
 * @returns {{title:string, icon:string, color?:string, sections:object[], kind:string, projectId?:number,
 *            canAdd:boolean, addDue?:number, draggable?:boolean, project?:object, empty:object}}
 */
export function buildView(state, route, ui, now = Date.now()) {
  const today = startOfDay(now);
  const open = openTasks(state);
  const base = { canAdd: true, sections: [], kind: route.name };

  switch (route.name) {
    case 'inbox':
    case 'project': {
      const project = route.name === 'inbox' ? inboxProject(state) : state.projects.find((p) => p.id === route.id);
      if (!project) return { ...base, title: 'Projet introuvable', icon: 'inbox', canAdd: false, empty: { icon: '🗂️', title: 'Projet introuvable', text: 'Il a peut-être été supprimé.' } };
      const showDone = ui.showDone.has(project.id);
      // La boîte de réception réunit aussi les tâches des autres projets affectées à l'utilisateur connecté.
      const mine = (t) => route.name === 'inbox' && state.user?.id && t.assignees?.some((a) => a.id === state.user.id);
      const { roots, children } = projectTree(state.tasks.filter((t) => t.projectId === project.id || mine(t)), showDone);
      return {
        ...base, title: project.title, project, projectId: project.id, color: colorOf(project.hex), draggable: true,
        icon: route.name === 'inbox' ? 'inbox' : 'hash', showProject: route.name === 'inbox',
        sections: roots.length ? [{ key: 'all', tasks: roots, children }] : [],
        empty: { icon: '🌤️', title: 'Rien à faire ici', text: 'Ajoutez une tâche pour commencer.' },
      };
    }
    case 'today': {
      const late = open.filter((t) => t.due != null && t.due < today).sort(byDueThenPriority);
      const todays = open.filter((t) => t.due != null && t.due >= today && t.due < today + DAY_MS).sort(byDueThenPriority);
      const sections = [];
      if (late.length) sections.push({ key: 'late', heading: 'En retard', tasks: late, reschedule: true, showDue: true });
      if (todays.length || late.length) sections.push({ key: 'today', heading: late.length ? dayHeading(today, now) : null, tasks: todays });
      return {
        ...base, title: "Aujourd'hui", icon: 'today', addDue: today + 12 * 3600_000, showProject: true,
        subtitle: `${late.length + todays.length} tâche${late.length + todays.length > 1 ? 's' : ''}`,
        sections: late.length + todays.length ? sections : [],
        empty: { icon: '🏖️', title: 'Journée libre', text: "Rien d'ici ce soir. Profitez-en !" },
      };
    }
    case 'upcoming': {
      const start = ui.upcomingStart ?? today;
      const days = [];
      for (let i = 0; i < 14; i++) {
        const d = addDays(start, i);
        days.push({ key: `d${d}`, day: d, heading: dayHeading(d, now), tasks: open.filter((t) => t.due != null && t.due >= d && t.due < addDays(d, 1)).sort(byDueThenPriority), addDay: true });
      }
      const late = open.filter((t) => t.due != null && t.due < today).sort(byDueThenPriority);
      const sections = [];
      if (late.length && start <= today) sections.push({ key: 'late', heading: 'En retard', tasks: late, reschedule: true, showDue: true });
      return { ...base, title: 'À venir', icon: 'upcoming', showProject: true, canAdd: false, upcoming: { start, today }, sections: [...sections, ...days], empty: null };
    }
    case 'label': {
      const label = state.labels.find((l) => l.id === route.id);
      const tasks = open.filter((t) => t.labels.some((l) => l.id === route.id)).sort(byDueThenPriority);
      return {
        ...base, title: label ? label.title : 'Étiquette', icon: 'tag', canAdd: false, showProject: true,
        sections: tasks.length ? [{ key: 'all', tasks }] : [],
        empty: { icon: '🏷️', title: 'Aucune tâche', text: 'Aucune tâche ouverte avec cette étiquette.' },
      };
    }
    case 'filter': {
      const filter = state.filters.find((f) => f.id === route.id);
      const ids = new Set(state.filterResults[route.id] || []);
      const tasks = state.tasks.filter((t) => ids.has(t.id) && !t.done).sort(byDueThenPriority);
      return {
        ...base, title: filter ? filter.title : 'Filtre', icon: 'filters', canAdd: false, showProject: true,
        subtitle: filter ? filter.query : '',
        sections: tasks.length ? [{ key: 'all', tasks }] : [],
        empty: { icon: '🔍', title: 'Aucun résultat', text: 'Aucune tâche ne correspond à ce filtre.' },
      };
    }
    case 'completed': {
      const tasks = state.tasks.filter((t) => t.done).sort((a, b) => doneAt(b) - doneAt(a));
      const groups = new Map();
      tasks.forEach((t) => {
        const k = startOfDay(doneAt(t) || now);
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(t);
      });
      return {
        ...base, title: 'Terminées', icon: 'checkCircle', canAdd: false, showProject: true, subtitle: 'Les 30 derniers jours',
        sections: [...groups].map(([day, list]) => ({ key: `c${day}`, heading: dayHeading(day, now), tasks: list })),
        empty: { icon: '✅', title: 'Rien de terminé', text: 'Les tâches terminées apparaissent ici.' },
      };
    }
    default:
      return { ...base, title: '', icon: 'inbox', sections: [], canAdd: false, empty: { icon: '🤷', title: 'Page introuvable', text: '' } };
  }
}

// ---------- Rendu HTML ----------
export function taskRow(t, state, o = {}) {
  const lvl = todoistLevel(t.priority);
  const project = state.projects.find((p) => p.id === t.projectId);
  const subs = state.tasks.filter((x) => x.parentId === t.id);
  const subDone = subs.filter((x) => x.done).length;
  const due = t.due != null
    ? `<span class="due due-${t.done ? 'later' : dueKind(t.due)}">${icon('calendar', 13)}${esc(dueText(t.due))}${isRecurring(t) ? ` ${icon('repeat', 12)}` : ''}</span>`
    : '';
  const labels = t.labels.map((l) => `<span class="lbl">${icon('tag', 12)}${esc(l.title)}</span>`).join('');
  const meta = [
    due,
    subs.length ? `<span class="chip">${icon('subtask', 12)}${subDone}/${subs.length}</span>` : '',
    t.commentCount ? `<span class="chip">${icon('comment', 12)}${t.commentCount}</span>` : '',
    t.reminders.length ? `<span class="chip">${icon('bell', 12)}</span>` : '',
    labels,
  ].filter(Boolean).join('');
  const where = o.showProject && project
    ? `<span class="t-project" title="${esc(project.title)}"><span>${esc(project.title)}</span><i class="dot" style="background:${colorOf(project.hex)};margin:0"></i></span>` : '';
  const cls = ['task', t.done ? 'done' : '', o.child ? 'child' : ''].filter(Boolean).join(' ');
  return `<div class="${cls}" data-id="${t.id}" ${o.draggable ? 'draggable="true"' : ''}>
    ${o.draggable ? `<span class="t-grip" aria-hidden="true">${icon('drag', 16)}</span>` : ''}
    ${o.hasChildren ? `<button class="t-fold" data-act="fold" data-id="${t.id}" aria-label="Replier">${icon(o.collapsed ? 'chevronRight' : 'chevronDown', 14)}</button>` : ''}
    <button class="check p${lvl}${t.done ? ' is-done' : ''}" data-act="check" aria-label="${t.done ? 'Rouvrir la tâche' : 'Terminer la tâche'}">${icon('check', 12)}</button>
    <div class="t-body">
      <div class="t-title">${esc(t.title)}</div>
      ${t.description ? `<div class="t-desc">${esc(t.description.split('\n')[0])}</div>` : ''}
      ${meta ? `<div class="t-meta">${meta}</div>` : ''}
    </div>
    ${where}
  </div>`;
}

export function sectionHtml(sec, view, state, ui) {
  const tasks = sec.tasks;
  const rows = [];
  if (sec.children) {
    const render = (t, child) => {
      const kids = sec.children.get(t.id) || [];
      const collapsed = ui.collapsedTasks.has(t.id);
      rows.push(taskRow(t, state, { draggable: view.draggable && !child, child, hasChildren: kids.length > 0, collapsed, showProject: view.showProject }));
      if (!collapsed) kids.forEach((k) => render(k, true));
    };
    tasks.forEach((t) => render(t, false));
  } else {
    tasks.forEach((t) => rows.push(taskRow(t, state, { showProject: view.showProject })));
  }
  let head = '';
  if (sec.heading) {
    head = `<div class="sec-head"><span>${esc(sec.heading)}</span><span class="sub">${tasks.length || ''}</span><span class="grow"></span>${sec.reschedule ? '<button class="link" data-act="reschedule">Reporter</button>' : ''}${sec.addDay ? `<button class="icon-btn" data-act="add-day" data-day="${sec.day}" aria-label="Ajouter une tâche ce jour-là">${icon('plus', 16)}</button>` : ''}</div>`;
  }
  return `<section data-sec="${sec.key}">${head}${rows.join('')}</section>`;
}

export const weekStrip = (view, now = Date.now()) => {
  const { start, today } = view.upcoming;
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i));
  return `<div class="week"><button class="icon-btn" data-act="week-prev" aria-label="Semaine précédente">${icon('chevronLeft')}</button>
    <div class="days">${days.map((d) => `<button class="day${d === today ? ' today' : ''}${d === start ? ' sel' : ''}" data-act="week-day" data-day="${d}"><span>${weekdayShort(d)}</span><b>${new Date(d).getDate()}</b></button>`).join('')}</div>
    <button class="icon-btn" data-act="week-next" aria-label="Semaine suivante">${icon('chevronRight')}</button></div>`;
};
