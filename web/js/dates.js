// Dates : Vikunja ne stocke qu'un instant. Une date « sans heure » est enregistrée à midi (heure locale) et l'heure
// est alors masquée dans l'interface, exactement comme dans l'app Android.

export const ZERO_DATE = '0001-01-01T00:00:00Z';
export const DAY_MS = 86_400_000;
export const DAY_SECONDS = 86_400;
export const WEEK_SECONDS = 7 * DAY_SECONDS;

export function parseDate(value) {
  if (!value || value === 'null' || String(value).startsWith('0001')) return null;
  const t = Date.parse(value);
  return Number.isNaN(t) ? null : t;
}

export const toIso = (ms) => (ms == null ? ZERO_DATE : new Date(ms).toISOString());

export function startOfDay(ms) {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export function addDays(ms, n) {
  const d = new Date(ms);
  d.setDate(d.getDate() + n);
  return d.getTime();
}

export function addMonths(ms, n) {
  const d = new Date(ms);
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + n);
  d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
  return d.getTime();
}

export const sameDay = (a, b) => startOfDay(a) === startOfDay(b);

export function atTime(dayMs, hours, minutes) {
  const d = new Date(dayMs);
  d.setHours(hours, minutes, 0, 0);
  return d.getTime();
}

/** Midi pile = « pas d'heure ». */
export function hasTime(ms) {
  if (ms == null) return false;
  const d = new Date(ms);
  return !(d.getHours() === 12 && d.getMinutes() === 0);
}

const pad = (n) => String(n).padStart(2, '0');
export const dateInputValue = (ms) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
export const timeInputValue = (ms) => {
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/** Combine un `<input type=date>` et un `<input type=time>` (vide = midi, « sans heure »). */
export function fromInputs(dateValue, timeValue) {
  if (!dateValue) return null;
  const [y, m, d] = dateValue.split('-').map(Number);
  const [hh, mm] = timeValue ? timeValue.split(':').map(Number) : [12, 0];
  return new Date(y, m - 1, d, hh, mm, 0, 0).getTime();
}

const MONTHS = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' });
const MONTHS_YEAR = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
const WEEKDAY = new Intl.DateTimeFormat('fr-FR', { weekday: 'long' });
const WEEKDAY_SHORT = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' });
const LONG = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' });
const TIME = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' });

export const weekdayName = (ms) => WEEKDAY.format(ms);
export const weekdayShort = (ms) => WEEKDAY_SHORT.format(ms).replace('.', '');
export const longDay = (ms) => LONG.format(ms);
export const timeText = (ms) => TIME.format(ms).replace(':', ':');

/** « Aujourd'hui », « Demain », « lundi », « 12 oct. »… avec l'heure si elle existe. */
export function dueText(ms, now = Date.now()) {
  if (ms == null) return '';
  const diff = Math.round((startOfDay(ms) - startOfDay(now)) / DAY_MS);
  let day;
  if (diff === 0) day = "Aujourd'hui";
  else if (diff === 1) day = 'Demain';
  else if (diff === -1) day = 'Hier';
  else if (diff > 1 && diff < 7) day = weekdayName(ms);
  else if (new Date(ms).getFullYear() === new Date(now).getFullYear()) day = MONTHS.format(ms);
  else day = MONTHS_YEAR.format(ms);
  return hasTime(ms) ? `${day} ${timeText(ms)}` : day;
}

/** Statut de couleur d'une échéance : overdue | today | tomorrow | later. */
export function dueKind(ms, now = Date.now()) {
  if (ms == null) return 'none';
  const diff = Math.round((startOfDay(ms) - startOfDay(now)) / DAY_MS);
  if (diff < 0) return 'overdue';
  if (diff === 0) return 'today';
  if (diff === 1) return 'tomorrow';
  return 'later';
}

/** Titre d'un jour dans Aujourd'hui / À venir : « 12 oct. · Demain · lundi ». */
export function dayHeading(ms, now = Date.now()) {
  const diff = Math.round((startOfDay(ms) - startOfDay(now)) / DAY_MS);
  const base = longDay(ms);
  if (diff === 0) return `${base} · Aujourd'hui`;
  if (diff === 1) return `${base} · Demain`;
  return `${base} · ${weekdayName(ms)}`;
}

/** Prochaine occurrence d'une tâche répétée (même règle que le serveur : on avance jusqu'à une date future). */
export function nextOccurrence(due, repeatAfter, repeatMode, now = Date.now()) {
  if (repeatMode === 1) {
    let next = addMonths(due, 1);
    while (next <= now) next = addMonths(next, 1);
    return next;
  }
  if (repeatAfter <= 0) return due;
  const step = repeatAfter * 1000;
  if (repeatMode === 2) return now + step;
  let next = due + step;
  while (next <= now) next += step;
  return next;
}

export function repeatLabel(repeatAfter, repeatMode) {
  if (repeatMode === 1) return 'Tous les mois';
  if (repeatAfter <= 0) return '';
  if (repeatAfter % WEEK_SECONDS === 0) {
    const n = repeatAfter / WEEK_SECONDS;
    return n === 1 ? 'Toutes les semaines' : `Toutes les ${n} semaines`;
  }
  if (repeatAfter % DAY_SECONDS === 0) {
    const n = repeatAfter / DAY_SECONDS;
    return n === 1 ? 'Tous les jours' : `Tous les ${n} jours`;
  }
  return `Toutes les ${Math.round(repeatAfter / 3600)} h`;
}
