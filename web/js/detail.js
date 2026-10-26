// Fenêtre de détail d'une tâche : titre, description, sous-tâches, commentaires, et colonne de réglages.
import { colorOf, isRecurring, todoistLevel, vikunjaPriority } from './model.js';
import { dueKind, dueText, repeatLabel, timeText } from './dates.js';
import { icon } from './icons.js';
import {
  $, $$, confirmDialog, esc, menu, modal, pickDate, pickLabels, PRIORITY_ITEMS, toast,
} from './ui.js';

const REMINDERS = [
  ['À l’échéance', 0], ['10 min avant', 600], ['30 min avant', 1800], ['1 h avant', 3600], ['1 jour avant', 86400],
];

const reminderText = (r) => {
  if (r.relativeTo) {
    const hit = REMINDERS.find(([, s]) => s === -r.relativePeriod);
    return hit ? hit[0] : `${Math.round(-r.relativePeriod / 60)} min avant`;
  }
  return r.at ? dueText(r.at) : '';
};

export function openDetail(ctx, taskId) {
  const { store, completeTask } = ctx;
  let id = taskId;
  const m = modal('<div class="detail-root"></div>', { wide: true });
  const rootEl = $('.detail-root', m.root);
  const live = () => store.task(id);
  m.taskId = () => id;

  function shell() {
    rootEl.innerHTML = `
      <div class="modal-head"><span class="grow" data-crumb></span>
        <button class="icon-btn" data-act="more" aria-label="Plus d’actions">${icon('more')}</button>
        <button class="icon-btn" data-act="close" aria-label="Fermer">${icon('close')}</button></div>
      <div class="detail">
        <div class="d-main">
          <div data-region="head"></div>
          <div data-region="subs"></div>
          <div class="d-section">${icon('comment', 16)} Commentaires</div>
          <div data-region="comments"></div>
          <form class="comment-form" data-comment-form><textarea class="field" rows="1" placeholder="Commenter…" aria-label="Commentaire"></textarea><button class="btn primary">Envoyer</button></form>
        </div>
        <div class="d-side" data-region="side"></div>
      </div>`;
  }

  const focusIn = (sel) => { const a = document.activeElement; const r = $(sel, rootEl); return !!(a && r && r.contains(a)); };

  function drawCrumb(t) {
    const p = store.state.projects.find((x) => x.id === t.projectId);
    const parent = t.parentId ? store.task(t.parentId) : null;
    $('[data-crumb]', rootEl).innerHTML =
      `<span style="font-weight:500;color:var(--muted)">${p ? `<span class="dot" style="display:inline-block;background:${colorOf(p.hex)};margin:0 6px 0 0"></span>${esc(p.title)}` : ''}${parent ? ` <span style="margin:0 4px">/</span> <a href="#" data-act="parent">${esc(parent.title)}</a>` : ''}</span>`;
  }

  function drawHead(t) {
    const lvl = todoistLevel(t.priority);
    $('[data-region=head]', rootEl).innerHTML = `
      <div class="d-title-row">
        <button class="check big p${lvl}${t.done ? ' is-done' : ''}" data-act="toggle" aria-label="${t.done ? 'Rouvrir' : 'Terminer'}">${icon('check', 14)}</button>
        <textarea class="d-title" rows="1" aria-label="Titre">${esc(t.title)}</textarea>
      </div>
      <textarea class="d-desc" placeholder="Description" aria-label="Description">${esc(t.description)}</textarea>`;
    $$('textarea', rootEl).forEach(autosize);
  }

  function drawSubs(t) {
    const subs = store.state.tasks.filter((x) => x.parentId === t.id);
    const open = subs.filter((s) => !s.done);
    const doneCount = subs.length - open.length;
    $('[data-region=subs]', rootEl).innerHTML = `
      <div class="d-section">${icon('subtask', 16)} Sous-tâches ${subs.length ? `<span class="sub" style="font-weight:400;color:var(--faint)">${doneCount}/${subs.length}</span>` : ''}</div>
      ${subs.map((s) => `<div class="subtask" data-sub="${s.id}"><button class="check p${todoistLevel(s.priority)}${s.done ? ' is-done' : ''}" data-act="toggle-sub" data-id="${s.id}" aria-label="Terminer">${icon('check', 12)}</button><span class="st-title ${s.done ? 'done' : ''}" style="${s.done ? 'text-decoration:line-through;color:var(--faint)' : ''}">${esc(s.title)}</span>${s.due != null ? `<span class="due due-${dueKind(s.due)}" style="font-size:12px">${esc(dueText(s.due))}</span>` : ''}</div>`).join('')}
      <form data-sub-form style="display:flex;gap:8px;margin-top:8px"><input class="field" name="s" placeholder="Ajouter une sous-tâche" maxlength="250" autocomplete="off"></form>`;
  }

  function drawComments(t) {
    const list = store.state.comments[t.id] || [];
    $('[data-region=comments]', rootEl).innerHTML = list.length
      ? list.map((c) => `<div class="comment"><span class="avatar">${esc((c.author || '?').slice(0, 1).toUpperCase())}</span><div><div class="c-meta"><b>${esc(c.author)}</b>${c.created ? esc(dueText(c.created)) + (c.created ? ' ' + esc(timeText(c.created)) : '') : ''}</div><div class="c-text">${esc(c.text)}</div></div></div>`).join('')
      : '<div class="c-meta" style="color:var(--faint)">Aucun commentaire.</div>';
  }

  function drawSide(t) {
    const projects = store.state.projects;
    const lvl = todoistLevel(t.priority);
    const due = t.due;
    $('[data-region=side]', rootEl).innerHTML = `
      <div><label>Projet</label><select class="field" data-f="project">${projects.map((p) => `<option value="${p.id}" ${p.id === t.projectId ? 'selected' : ''}>${esc(p.title)}</option>`).join('')}</select></div>
      <div><label>Échéance</label><button class="btn ghost field" style="text-align:left;justify-content:flex-start" data-act="date">${icon('calendar', 16)} <span class="${due != null ? `due-${dueKind(due)}` : ''}">${due != null ? esc(dueText(due)) : 'Ajouter une date'}</span></button>
        ${isRecurring(t) ? `<div class="c-meta" style="margin-top:4px;font-size:12px;color:var(--muted)">${icon('repeat', 13)} ${esc(repeatLabel(t.repeatAfter, t.repeatMode))}</div>` : ''}</div>
      <div><label>Priorité</label><button class="btn ghost field" style="text-align:left;justify-content:flex-start" data-act="priority"><svg class="ic" width="16" height="16" viewBox="0 0 24 24" fill="${lvl === 4 ? 'none' : 'currentColor'}" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="color:var(--p${lvl})"><path d="M4 22V4M4 4h13l-2 4 2 4H4"/></svg> Priorité ${lvl}</button></div>
      <div><label>Étiquettes</label><button class="btn ghost field" style="text-align:left;justify-content:flex-start;height:auto;flex-wrap:wrap" data-act="labels">${t.labels.length ? t.labels.map((l) => `<span class="lbl-chip">${esc(l.title)}</span>`).join(' ') : `${icon('tag', 16)} Ajouter`}</button></div>
      <div><label>Rappels</label>${t.reminders.map((r, i) => `<div class="lbl-chip" style="display:flex;margin-bottom:4px">${icon('bell', 13)} <span style="flex:1">${esc(reminderText(r))}</span><button data-act="rm-reminder" data-i="${i}" aria-label="Supprimer le rappel">${icon('close', 12)}</button></div>`).join('')}
        <button class="btn ghost field" style="text-align:left;justify-content:flex-start" data-act="reminder">${icon('plus', 16)} Ajouter</button></div>`;
  }

  function drawAll() {
    const t = live();
    if (!t) { m.close(); return; }
    drawCrumb(t);
    if (!focusIn('[data-region=head]')) drawHead(t);
    else $('.check', rootEl).classList.toggle('is-done', t.done);
    if (!focusIn('[data-region=subs]')) drawSubs(t);
    if (!focusIn('[data-region=side]')) drawSide(t);
    drawComments(t);
  }

  function autosize(el) {
    el.style.height = 'auto';
    el.style.height = `${Math.max(el.scrollHeight, el.classList.contains('d-desc') ? 60 : 0)}px`;
  }

  const patch = (changes, labelsChanged = false) => {
    const t = live();
    if (!t) return;
    store.updateTask({ ...t, ...changes }, labelsChanged);
  };

  shell();
  drawAll();
  store.loadComments(id);
  const unsubscribe = store.subscribe(drawAll);
  const origClose = m.close;
  m.close = () => { unsubscribe(); origClose(); ctx.onDetailClosed?.(); };

  // Édition du titre / de la description à la perte du focus
  rootEl.addEventListener('focusout', (e) => {
    const t = live();
    if (!t) return;
    if (e.target.classList.contains('d-title')) {
      const v = e.target.value.trim();
      if (v && v !== t.title) patch({ title: v });
      else e.target.value = t.title;
    } else if (e.target.classList.contains('d-desc')) {
      if (e.target.value !== t.description) patch({ description: e.target.value });
    }
  });
  rootEl.addEventListener('input', (e) => { if (e.target.matches('textarea')) autosize(e.target); });
  rootEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.classList.contains('d-title')) { e.preventDefault(); e.target.blur(); }
  });
  rootEl.addEventListener('change', (e) => {
    if (e.target.dataset.f === 'project') patch({ projectId: Number(e.target.value) });
  });

  rootEl.addEventListener('submit', (e) => {
    e.preventDefault();
    if (e.target.matches('[data-sub-form]')) {
      const input = e.target.elements.s;
      const title = input.value.trim();
      if (!title) return;
      const t = live();
      store.addTask({ title, projectId: t.projectId, parentId: t.id });
      input.value = '';
      input.focus();
    } else if (e.target.matches('[data-comment-form]')) {
      const ta = $('textarea', e.target);
      const text = ta.value.trim();
      if (!text) return;
      store.addComment(live().id, text);
      ta.value = '';
    }
  });
  $('[data-comment-form] textarea', rootEl).addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); $('[data-comment-form]', rootEl).requestSubmit(); }
  });

  rootEl.addEventListener('click', async (e) => {
    const sub = e.target.closest('[data-sub]');
    const el = e.target.closest('[data-act]');
    const t = live();
    if (!t) return;
    if (!el) {
      if (sub) { id = Number(sub.dataset.sub); shell(); drawAll(); store.loadComments(id); }
      return;
    }
    const act = el.dataset.act;
    if (act === 'close') m.close();
    else if (act === 'parent') { e.preventDefault(); id = t.parentId; shell(); drawAll(); store.loadComments(id); }
    else if (act === 'toggle') completeTask(t.id, !t.done);
    else if (act === 'toggle-sub') completeTask(Number(el.dataset.id), !store.task(Number(el.dataset.id)).done);
    else if (act === 'date') {
      const r = await pickDate(el, { due: t.due, repeatAfter: t.repeatAfter, repeatMode: t.repeatMode });
      if (r) patch({ due: r.due, repeatAfter: r.repeatAfter, repeatMode: r.repeatMode });
    } else if (act === 'priority') {
      const lvl = await menu(el, PRIORITY_ITEMS(todoistLevel(t.priority)));
      if (lvl) patch({ priority: vikunjaPriority(lvl) });
    } else if (act === 'labels') {
      pickLabels(el, {
        labels: store.state.labels, selected: t.labels,
        onChange: (sel) => patch({ labels: sel }, true),
        onCreate: (title) => store.createLabel(title),
      });
    } else if (act === 'reminder') {
      const hasDue = t.due != null;
      if (!hasDue) { toast('Ajoutez d’abord une échéance pour programmer un rappel.'); return; }
      const v = await menu(el, REMINDERS.map(([label, s]) => ({ value: s, label })));
      if (v != null) patch({ reminders: [...t.reminders, { at: null, relativePeriod: -v, relativeTo: 'due_date' }] });
    } else if (act === 'rm-reminder') {
      patch({ reminders: t.reminders.filter((_, i) => i !== Number(el.dataset.i)) });
    } else if (act === 'more') {
      const v = await menu(el, [
        { value: 'fav', label: t.isFavorite ? 'Retirer des favoris' : 'Ajouter aux favoris', icon: 'star' },
        { value: 'dup', label: 'Dupliquer', icon: 'copy' },
        { sep: true },
        { value: 'del', label: 'Supprimer la tâche', icon: 'trash', danger: true },
      ]);
      if (v === 'fav') patch({ isFavorite: !t.isFavorite });
      else if (v === 'dup') { store.duplicateTask(t.id); toast('Tâche dupliquée'); }
      else if (v === 'del') {
        if (await confirmDialog(`Supprimer « ${t.title} » ?`)) {
          const prev = { ...t };
          store.deleteTask(t.id);
          m.close();
          toast('Tâche supprimée', { action: 'Annuler', onAction: () => store.addTask({ ...prev, labels: prev.labels }) });
        }
      }
    }
  });

  return m;
}
