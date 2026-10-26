// Petits composants d'interface sans framework : échappement, notifications, fenêtres, menus, sélecteurs.
import { icon } from './icons.js';
import {
  addDays, atTime, dateInputValue, fromInputs, hasTime, repeatLabel, startOfDay, timeInputValue, weekdayName, DAY_SECONDS, WEEK_SECONDS,
} from './dates.js';

export const esc = (s) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

// ---------- Notifications ----------
export function toast(message, { action, onAction, error = false, ms = 4500 } = {}) {
  const root = $('#toast-root');
  const el = document.createElement('div');
  el.className = `toast${error ? ' err' : ''}`;
  el.innerHTML = `<span>${esc(message)}</span>${action ? `<button type="button">${esc(action)}</button>` : ''}`;
  const remove = () => el.remove();
  if (action) $('button', el).addEventListener('click', () => { onAction?.(); remove(); });
  root.append(el);
  while (root.children.length > 3) root.firstChild.remove();
  setTimeout(remove, ms);
  return remove;
}

// ---------- Fenêtres ----------
const openModals = [];

/** Ouvre une fenêtre ; `sheet` = feuille du bas sur mobile. Renvoie { root, close }. */
export function modal(html, { wide = false, sheet = false, onClose } = {}) {
  const backdrop = document.createElement('div');
  backdrop.className = `backdrop${sheet ? ' sheet' : ''}`;
  backdrop.innerHTML = `<div class="modal${wide ? ' wide' : ''}" role="dialog" aria-modal="true">${html}</div>`;
  const handle = {
    root: backdrop.firstElementChild,
    close() {
      const i = openModals.indexOf(handle);
      if (i >= 0) openModals.splice(i, 1);
      backdrop.remove();
      onClose?.();
    },
  };
  backdrop.addEventListener('mousedown', (e) => { backdrop._down = e.target === backdrop; });
  backdrop.addEventListener('click', (e) => { if (e.target === backdrop && backdrop._down) handle.close(); });
  $('#overlay-root').append(backdrop);
  openModals.push(handle);
  return handle;
}
export const topModal = () => openModals[openModals.length - 1];
export const closeTopModal = () => { const m = topModal(); if (m) m.close(); return !!m; };

export function confirmDialog(message, confirmLabel = 'Supprimer', danger = true) {
  return new Promise((resolve) => {
    const m = modal(`
      <div class="modal-body"><p style="margin:0">${esc(message)}</p></div>
      <div class="modal-foot"><button class="btn" data-v="0">Annuler</button><button class="btn primary" data-v="1" ${danger ? 'style="background:var(--overdue)"' : ''}>${esc(confirmLabel)}</button></div>`,
    { sheet: true, onClose: () => resolve(false) });
    m.root.addEventListener('click', (e) => {
      const b = e.target.closest('[data-v]');
      if (!b) return;
      resolve(b.dataset.v === '1');
      m.close();
    });
  });
}

export function promptDialog({ title, value = '', placeholder = '', confirm = 'Enregistrer', colors = null, color = '' }) {
  return new Promise((resolve) => {
    let chosen = color;
    const m = modal(`
      <div class="modal-head"><span class="grow">${esc(title)}</span><button class="icon-btn" data-close aria-label="Fermer">${icon('close')}</button></div>
      <form class="modal-body">
        <input class="field" name="v" value="${esc(value)}" placeholder="${esc(placeholder)}" maxlength="250" autocomplete="off" required>
        ${colors ? `<div class="swatches">${colors.map((c) => `<button type="button" class="swatch${c === chosen ? ' sel' : ''}" data-color="${c}" style="background:${c}" aria-label="Couleur ${c}"></button>`).join('')}</div>` : ''}
        <div class="modal-foot" style="padding:12px 0 0;border:0"><button type="button" class="btn" data-close>Annuler</button><button class="btn primary">${esc(confirm)}</button></div>
      </form>`, { sheet: true, onClose: () => resolve(null) });
    const input = $('input', m.root);
    setTimeout(() => { input.focus(); input.select(); }, 30);
    m.root.addEventListener('click', (e) => {
      if (e.target.closest('[data-close]')) m.close();
      const sw = e.target.closest('[data-color]');
      if (sw) {
        chosen = sw.dataset.color;
        $$('.swatch', m.root).forEach((x) => x.classList.toggle('sel', x === sw));
      }
    });
    $('form', m.root).addEventListener('submit', (e) => {
      e.preventDefault();
      const v = input.value.trim();
      if (!v) return;
      resolve({ value: v, color: chosen });
      m.root.closest('.backdrop').remove();
      const i = openModals.indexOf(openModals.find((x) => x.root === m.root));
      if (i >= 0) openModals.splice(i, 1);
    });
  });
}

// ---------- Popovers ----------
let current = null;
export function closePopover() {
  if (current) { current.cleanup(); current = null; }
}

/** Affiche un popover sous `anchor`. `setup(root, close)` branche les événements. */
export function popover(anchor, html, setup) {
  closePopover();
  const root = document.createElement('div');
  root.className = 'popover';
  root.innerHTML = html;
  document.body.append(root);
  const place = () => {
    const r = anchor.getBoundingClientRect();
    const w = root.offsetWidth;
    const h = root.offsetHeight;
    let left = Math.min(Math.max(8, r.left), window.innerWidth - w - 8);
    let top = r.bottom + 4;
    if (top + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 4);
    root.style.left = `${left}px`;
    root.style.top = `${top}px`;
  };
  place();
  const onDown = (e) => { if (!root.contains(e.target) && !anchor.contains(e.target)) closePopover(); };
  const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); closePopover(); } };
  setTimeout(() => document.addEventListener('mousedown', onDown), 0);
  document.addEventListener('keydown', onKey, true);
  window.addEventListener('resize', closePopover);
  const cleanup = () => {
    root.remove();
    document.removeEventListener('mousedown', onDown);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('resize', closePopover);
  };
  current = { cleanup, root };
  setup?.(root, closePopover);
  place();
  return root;
}

/** Menu de choix : renvoie la `value` choisie (ou null). */
export function menu(anchor, items) {
  return new Promise((resolve) => {
    let picked = false;
    const html = items.map((it, i) => {
      if (it.sep) return '<div class="menu-sep"></div>';
      if (it.heading) return `<div class="menu-sub">${esc(it.heading)}</div>`;
      return `<button type="button" class="menu-item${it.sel ? ' sel' : ''}${it.danger ? ' danger' : ''}" data-i="${i}">${it.icon ? it.iconHtml || icon(it.icon) : ''}<span>${esc(it.label)}</span>${it.right ? `<span class="right">${esc(it.right)}</span>` : ''}</button>`;
    }).join('');
    const root = popover(anchor, html, (r, close) => {
      r.addEventListener('click', (e) => {
        const b = e.target.closest('[data-i]');
        if (!b) return;
        picked = true;
        resolve(items[Number(b.dataset.i)].value);
        close();
      });
    });
    const watch = setInterval(() => {
      if (!root.isConnected) { clearInterval(watch); if (!picked) resolve(null); }
    }, 120);
  });
}

// ---------- Sélecteur de date ----------
const REPEATS = [
  ['', 'Ne se répète pas', 0, 0],
  ['d1', 'Tous les jours', DAY_SECONDS, 0],
  ['w1', 'Toutes les semaines', WEEK_SECONDS, 0],
  ['w2', 'Toutes les 2 semaines', 2 * WEEK_SECONDS, 0],
  ['m1', 'Tous les mois', 0, 1],
];

const repeatKey = (after, mode) => {
  if (mode === 1) return 'm1';
  const hit = REPEATS.find((r) => r[2] === after && r[2] > 0);
  return hit ? hit[0] : after > 0 ? 'custom' : '';
};

/** Sélecteur d'échéance : renvoie { due, repeatAfter, repeatMode } ou null si fermé sans choix. */
export function pickDate(anchor, { due = null, repeatAfter = 0, repeatMode = 0 } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; resolve(v); closePopover(); };
    const now = Date.now();
    const today = startOfDay(now);
    const keepTime = (day) => (due != null && hasTime(due) ? atTime(day, new Date(due).getHours(), new Date(due).getMinutes()) : atTime(day, 12, 0));
    const dow = new Date(today).getDay();
    const saturday = addDays(today, (6 - dow + 7) % 7 || 7);
    const monday = addDays(today, (1 - dow + 7) % 7 || 7);
    const quick = [
      ['sun', "Aujourd'hui", today, weekdayName(today).slice(0, 3)],
      ['sun', 'Demain', addDays(today, 1), weekdayName(addDays(today, 1)).slice(0, 3)],
      ['calendar', 'Ce week-end', saturday, 'sam.'],
      ['calendar', 'Semaine prochaine', monday, 'lun.'],
    ];
    const current = repeatKey(repeatAfter, repeatMode);
    const html = `
      ${quick.map((q, i) => `<button type="button" class="menu-item" data-quick="${i}">${icon(q[0])}<span>${q[1]}</span><span class="right">${q[3]}</span></button>`).join('')}
      <button type="button" class="menu-item" data-quick="none">${icon('close')}<span>Sans date</span></button>
      <div class="menu-sep"></div>
      <div style="padding:4px 8px 8px;display:grid;gap:6px">
        <div class="row2" style="display:flex;gap:6px"><input type="date" class="field" name="d" value="${due != null ? dateInputValue(due) : ''}" style="padding:5px 8px"><input type="time" class="field" name="t" value="${due != null && hasTime(due) ? timeInputValue(due) : ''}" style="padding:5px 8px;width:110px"></div>
        <select class="field" name="r" style="padding:5px 8px">
          ${REPEATS.map((r) => `<option value="${r[0]}" ${r[0] === current ? 'selected' : ''}>${r[1]}</option>`).join('')}
          ${current === 'custom' ? `<option value="custom" selected>${esc(repeatLabel(repeatAfter, repeatMode))}</option>` : ''}
        </select>
        <button type="button" class="btn primary" data-ok>Valider</button>
      </div>`;
    const root = popover(anchor, html, (r) => {
      const sel = () => {
        const rep = REPEATS.find((x) => x[0] === r.querySelector('[name=r]').value);
        return rep ? { repeatAfter: rep[2], repeatMode: rep[3] } : { repeatAfter, repeatMode };
      };
      r.addEventListener('click', (e) => {
        const q = e.target.closest('[data-quick]');
        if (q) {
          if (q.dataset.quick === 'none') return finish({ due: null, repeatAfter: 0, repeatMode: 0 });
          return finish({ due: keepTime(quick[Number(q.dataset.quick)][2]), ...sel() });
        }
        if (e.target.closest('[data-ok]')) {
          const d = r.querySelector('[name=d]').value;
          const t = r.querySelector('[name=t]').value;
          finish({ due: d ? fromInputs(d, t) : null, ...(d ? sel() : { repeatAfter: 0, repeatMode: 0 }) });
        }
      });
    });
    const watch = setInterval(() => { if (!root.isConnected) { clearInterval(watch); if (!done) { done = true; resolve(null); } } }, 120);
  });
}

// ---------- Sélecteur d'étiquettes ----------
/** Popover à cocher ; applique chaque changement immédiatement via `onChange(selectedLabels)`. */
export function pickLabels(anchor, { labels, selected, onChange, onCreate }) {
  let sel = [...selected];
  const draw = (root, query = '') => {
    const q = query.trim().toLowerCase();
    const rows = labels.filter((l) => !q || l.title.toLowerCase().includes(q));
    const exact = labels.some((l) => l.title.toLowerCase() === q);
    root.querySelector('.rows').innerHTML =
      rows.map((l) => `<label class="menu-item"><input type="checkbox" data-id="${l.id}" ${sel.some((s) => s.id === l.id) ? 'checked' : ''}>${icon('tag', 16)}<span>${esc(l.title)}</span></label>`).join('')
      + (q && !exact ? `<button type="button" class="menu-item" data-create="${esc(query.trim())}">${icon('plus', 16)}<span>Créer « ${esc(query.trim())} »</span></button>` : '')
      + (!rows.length && !q ? '<div class="menu-sub">Aucune étiquette</div>' : '');
  };
  popover(anchor, `<div class="menu-input"><input class="field" placeholder="Rechercher ou créer…" style="padding:5px 8px"></div><div class="rows"></div>`, (root) => {
    const input = $('input', root);
    draw(root);
    input.focus();
    input.addEventListener('input', () => draw(root, input.value));
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); const c = $('[data-create]', root); if (c) c.click(); }
    });
    root.addEventListener('change', (e) => {
      const id = Number(e.target.dataset.id);
      const label = labels.find((l) => l.id === id);
      if (!label) return;
      sel = e.target.checked ? [...sel, label] : sel.filter((s) => s.id !== id);
      onChange(sel);
    });
    root.addEventListener('click', (e) => {
      const c = e.target.closest('[data-create]');
      if (!c) return;
      const label = onCreate(c.dataset.create);
      labels = [...labels, label];
      sel = [...sel, label];
      onChange(sel);
      input.value = '';
      draw(root);
    });
  });
}

export const PRIORITY_ITEMS = (current) => [1, 2, 3, 4].map((lvl) => ({
  value: lvl, label: `Priorité ${lvl}`, icon: 'flag', sel: lvl === current,
  iconHtml: `<svg class="ic" width="18" height="18" viewBox="0 0 24 24" fill="${lvl === 4 ? 'none' : 'currentColor'}" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="color:var(--p${lvl})"><path d="M4 22V4M4 4h13l-2 4 2 4H4"/></svg>`,
}));

export const PROJECT_COLORS = ['#b8255f', '#db4035', '#ff9933', '#fad000', '#7ecc49', '#299438', '#6accbc', '#158fad', '#14aaf5', '#96c3eb', '#4073ff', '#884dff', '#af38eb', '#eb96eb', '#e05194', '#ff8d85', '#808080', '#b8b8b8'];
