// Formulaire d'ajout de tâche (en ligne dans une liste, ou en fenêtre « ajout rapide »).
// Le titre est analysé à chaque frappe (QuickAddParser) ; ce que l'utilisateur choisit à la main a priorité.
import { dueOf, parseQuickAdd } from './parser.js';
import { dueKind, dueText, repeatLabel } from './dates.js';
import { colorOf, vikunjaPriority, todoistLevel } from './model.js';
import { icon } from './icons.js';
import { $, esc, menu, pickDate, pickLabels, PRIORITY_ITEMS } from './ui.js';

const flagSvg = (lvl) =>
  `<svg class="ic" width="14" height="14" viewBox="0 0 24 24" fill="${lvl === 4 ? 'none' : 'currentColor'}" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color:var(--p${lvl})"><path d="M4 22V4M4 4h13l-2 4 2 4H4"/></svg>`;

/**
 * @param root     élément qui reçoit le formulaire
 * @param ctx      { store, projectFor(id), defaultProjectId() }
 * @param opts     { projectId, due, parentId, modal, placeholder, submitLabel, onSubmit(taskId, projectId), onCancel }
 */
export function mountComposer(root, ctx, opts = {}) {
  const { store } = ctx;
  const manual = {};
  if (opts.due != null) manual.due = opts.due;
  let projectId = opts.projectId || ctx.defaultProjectId();
  let projectChosen = !!opts.projectId;

  root.innerHTML = `
    <div class="composer${opts.modal ? ' in-modal' : ''}">
      <div class="c-fields">
        <input class="c-title" placeholder="${esc(opts.placeholder || 'ex. Appeler Marie demain 9h #Perso @urgent p1')}" maxlength="250" autocomplete="off" enterkeyhint="done">
        <textarea class="c-desc" rows="1" placeholder="Description"></textarea>
      </div>
      <div class="c-chips"></div>
      ${opts.modal ? '' : ''}
      <div class="c-foot">
        <button type="button" class="pill set" data-project></button>
        <span class="grow"></span>
        <button type="button" class="btn" data-cancel>Annuler</button>
        <button type="button" class="btn primary" data-submit disabled>${esc(opts.submitLabel || 'Ajouter')}</button>
      </div>
    </div>`;
  const title = $('.c-title', root);
  const desc = $('.c-desc', root);
  const chips = $('.c-chips', root);
  const submit = $('[data-submit]', root);
  const projectBtn = $('[data-project]', root);

  const parsed = () => parseQuickAdd(title.value, store.state.projects, store.state.labels);

  /** Valeurs effectives = choix manuel, sinon ce que l'analyse du texte a trouvé. */
  function effective() {
    const p = parsed();
    const labels = manual.labels ?? p.labels;
    const due = 'due' in manual ? manual.due : dueOf(p);
    const repeatAfter = 'repeatAfter' in manual ? manual.repeatAfter : p.repeatAfter;
    const repeatMode = 'repeatMode' in manual ? manual.repeatMode : p.repeatMode;
    const level = manual.level ?? (p.priority != null ? todoistLevel(p.priority) : 4);
    const pid = projectChosen ? projectId : p.projectId ?? projectId;
    return { p, labels, due, repeatAfter, repeatMode, level, pid };
  }

  function draw() {
    const e = effective();
    const project = store.state.projects.find((x) => x.id === e.pid);
    projectBtn.innerHTML = `${icon('inbox', 14)} ${esc(project ? project.title : 'Projet')} ${icon('chevronDown', 12)}`;
    const parts = [];
    parts.push(`<button type="button" class="pill ${e.due != null ? `set due-${dueKind(e.due)}` : ''}" data-chip="date">${icon('calendar', 14)} ${e.due != null ? esc(dueText(e.due)) : 'Date'}</button>`);
    if (e.repeatAfter > 0 || e.repeatMode) parts.push(`<span class="pill set">${icon('repeat', 14)} ${esc(repeatLabel(e.repeatAfter, e.repeatMode))}</span>`);
    parts.push(`<button type="button" class="pill ${e.level < 4 ? 'set' : ''}" data-chip="priority">${flagSvg(e.level)} ${e.level < 4 ? `P${e.level}` : 'Priorité'}</button>`);
    parts.push(`<button type="button" class="pill ${e.labels.length ? 'set' : ''}" data-chip="labels">${icon('tag', 14)} ${e.labels.length ? esc(e.labels.map((l) => l.title).join(', ')) : 'Étiquettes'}</button>`);
    chips.innerHTML = parts.join('');
    submit.disabled = !e.p.title;
  }

  chips.addEventListener('click', async (ev) => {
    const kind = ev.target.closest('[data-chip]')?.dataset.chip;
    if (!kind) return;
    const anchor = ev.target.closest('[data-chip]');
    const e = effective();
    if (kind === 'date') {
      const r = await pickDate(anchor, { due: e.due, repeatAfter: e.repeatAfter, repeatMode: e.repeatMode });
      if (r) Object.assign(manual, r);
    } else if (kind === 'priority') {
      const lvl = await menu(anchor, PRIORITY_ITEMS(e.level));
      if (lvl) manual.level = lvl;
    } else if (kind === 'labels') {
      pickLabels(anchor, {
        labels: store.state.labels, selected: e.labels,
        onChange: (sel) => { manual.labels = sel; draw(); },
        onCreate: (t) => store.createLabel(t),
      });
      return;
    }
    draw();
    title.focus();
  });

  projectBtn.addEventListener('click', async () => {
    const items = store.state.projects.map((p) => ({
      value: p.id, label: p.title, sel: p.id === effective().pid,
      iconHtml: `<span class="dot" style="background:${colorOf(p.hex)};margin:0 3px"></span>`, icon: 'x',
    }));
    const id = await menu(projectBtn, items);
    if (id != null) { projectId = id; projectChosen = true; draw(); title.focus(); }
  });

  function submitNow() {
    const e = effective();
    if (!e.p.title) return;
    const id = store.addTask({
      title: e.p.title, projectId: e.pid, due: e.due, priority: vikunjaPriority(e.level), labels: e.labels,
      description: desc.value.trim(), parentId: opts.parentId || 0, repeatAfter: e.repeatAfter, repeatMode: e.repeatMode,
    });
    opts.onSubmit?.(id, e.pid);
    if (!opts.modal) {
      // Saisie en rafale : on garde le formulaire ouvert, vide, avec la même date/priorité de départ.
      title.value = '';
      desc.value = '';
      for (const k of ['labels', 'level', 'repeatAfter', 'repeatMode']) delete manual[k];
      if (opts.due == null) delete manual.due; else manual.due = opts.due;
      draw();
      title.focus();
    }
  }

  title.addEventListener('input', draw);
  title.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter' && !ev.isComposing) { ev.preventDefault(); submitNow(); }
  });
  desc.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); submitNow(); }
  });
  desc.addEventListener('input', () => { desc.style.height = 'auto'; desc.style.height = `${desc.scrollHeight}px`; });
  submit.addEventListener('click', submitNow);
  $('[data-cancel]', root).addEventListener('click', () => opts.onCancel?.());
  root.addEventListener('keydown', (ev) => {
    if (ev.key === 'Escape' && !document.querySelector('.popover')) { ev.stopPropagation(); opts.onCancel?.(); }
  });

  draw();
  if (opts.autofocus !== false) setTimeout(() => title.focus(), 20);
  return { focus: () => title.focus(), redraw: draw };
}
