// Ajout rapide en langage naturel, port fidèle de QuickAddParser.kt (l'app Android).
// « Appeler Marie demain 9h #Perso @urgent p1 » -> titre, date, heure, projet, étiquettes, priorité, récurrence.
import { addDays, addMonths, atTime, DAY_SECONDS, WEEK_SECONDS, startOfDay } from './dates.js';

const WEEKDAYS = { lundi: 1, mardi: 2, mercredi: 3, jeudi: 4, vendredi: 5, samedi: 6, dimanche: 0 };
const PRIORITY = { 1: 4, 2: 3, 3: 2, 4: 0 };
const WD = 'lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche';

const R = {
  priority: /(?<=\s)[pP]([1-4])(?=\s)/g,
  project: /(?<=\s)#(\S+)/g,
  label: /(?<=\s)@(\S+)/g,
  time: /(?<=\s)(?:[àa]\s+)?(\d{1,2})[h:](\d{2})?(?=\s)/i,
  dayAfterTomorrow: /(?<=\s)apr[eè]s[- ]demain(?=\s)/i,
  today: /(?<=\s)(?:aujourd'?hui|auj)(?=\s)/i,
  tomorrow: /(?<=\s)demain(?=\s)/i,
  inN: /(?<=\s)dans\s+(\d{1,3})\s+(jours?|semaines?|mois)(?=\s)/i,
  weekday: new RegExp(`(?<=\\s)(?:le\\s+)?(${WD})(?:\\s+prochain)?(?=\\s)`, 'i'),
  numeric: /(?<=\s)(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?(?=\s)/,
  everyDay: /(?<=\s)(?:tous\s+les\s+jours|chaque\s+jour)(?=\s)/i,
  everyWeek: /(?<=\s)(?:toutes\s+les\s+semaines|chaque\s+semaine)(?=\s)/i,
  everyMonth: /(?<=\s)(?:tous\s+les\s+mois|chaque\s+mois)(?=\s)/i,
  everyN: /(?<=\s)(?:tous|toutes)\s+les\s+(\d{1,3})\s+(jours|semaines)(?=\s)/i,
  everyWeekday: new RegExp(`(?<=\\s)chaque\\s+(${WD})(?=\\s)`, 'i'),
  dayOfMonth: /(?<=\s)le\s+(\d{1,2})(?:er)?(?=\s)/i,
};

const normalize = (text) =>
  text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[\s_-]+/g, '');

const lastMatch = (re, s) => [...s.matchAll(re)].pop();
const cut = (s, m) => s.slice(0, m.index) + s.slice(m.index + m[0].length);

function daysUntil(targetDow, today) {
  const dow = new Date(today).getDay();
  let d = (targetDow - dow + 7) % 7;
  if (d === 0) d = 7;
  return d;
}

/**
 * @returns {{title:string,date:number|null,time:[number,number]|null,priority:number|null,projectId:number|null,
 *            labels:object[],repeatAfter:number,repeatMode:number}}
 */
export function parseQuickAdd(input, projects = [], labels = [], now = Date.now()) {
  const today = startOfDay(now);
  let s = ` ${input.trim()} `;
  let priority = null;
  let projectId = null;
  const matched = [];

  const p = lastMatch(R.priority, s);
  if (p) {
    priority = PRIORITY[Number(p[1])];
    s = cut(s, p);
  }

  for (const m of [...s.matchAll(R.project)]) {
    const wanted = normalize(m[1]);
    const project = projects.find((x) => normalize(x.title) === wanted);
    if (project && projectId == null) {
      projectId = project.id;
      s = s.replace(m[0], '');
    }
  }
  for (const m of [...s.matchAll(R.label)]) {
    const wanted = normalize(m[1]);
    const label = labels.find((x) => normalize(x.title) === wanted);
    if (label && !matched.includes(label)) {
      matched.push(label);
      s = s.replace(m[0], '');
    }
  }

  let time = null;
  const tm = R.time.exec(s);
  if (tm) {
    const hour = Number(tm[1]);
    const minute = Number(tm[2] || 0);
    if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
      time = [hour, minute];
      s = cut(s, tm);
    }
  }

  let date = null;
  let repeatAfter = 0;
  let repeatMode = 0;
  const n = R.everyN.exec(s);
  if (n) {
    repeatAfter = Number(n[1]) * (n[2].toLowerCase() === 'semaines' ? WEEK_SECONDS : DAY_SECONDS);
    s = cut(s, n);
  }
  if (!repeatAfter) {
    const m = R.everyWeekday.exec(s);
    if (m) {
      repeatAfter = WEEK_SECONDS;
      date = addDays(today, daysUntil(WEEKDAYS[m[1].toLowerCase()], today));
      s = cut(s, m);
    }
  }
  if (!repeatAfter) {
    for (const [re, secs] of [[R.everyDay, DAY_SECONDS], [R.everyWeek, WEEK_SECONDS]]) {
      const m = re.exec(s);
      if (m) {
        repeatAfter = secs;
        s = cut(s, m);
        break;
      }
    }
  }
  if (!repeatAfter) {
    const m = R.everyMonth.exec(s);
    if (m) {
      repeatMode = 1;
      s = cut(s, m);
    }
  }

  const take = (re, compute) => {
    if (date != null) return;
    const m = re.exec(s);
    if (!m) return;
    const result = compute(m);
    if (result == null) return;
    date = result;
    s = cut(s, m);
  };
  take(R.dayAfterTomorrow, () => addDays(today, 2));
  take(R.today, () => today);
  take(R.tomorrow, () => addDays(today, 1));
  take(R.inN, (m) => {
    const k = Number(m[1]);
    const unit = m[2].toLowerCase();
    if (unit.startsWith('semaine')) return addDays(today, 7 * k);
    if (unit.startsWith('mois')) return addMonths(today, k);
    return addDays(today, k);
  });
  take(R.weekday, (m) => addDays(today, daysUntil(WEEKDAYS[m[1].toLowerCase()], today)));
  take(R.numeric, (m) => {
    const day = Number(m[1]);
    const month = Number(m[2]);
    let year = m[3] ? Number(m[3]) : new Date(today).getFullYear();
    if (m[3] && year < 100) year += 2000;
    const d = new Date(year, month - 1, day);
    if (d.getMonth() !== month - 1 || d.getDate() !== day) return null;
    if (!m[3] && d.getTime() < today) d.setFullYear(year + 1);
    return d.getTime();
  });
  take(R.dayOfMonth, (m) => {
    const day = Number(m[1]);
    const t = new Date(today);
    const d = new Date(t.getFullYear(), t.getMonth(), day);
    if (d.getDate() !== day) return null;
    return d.getTime() < today ? addMonths(d.getTime(), 1) : d.getTime();
  });

  // Vikunja ne répète que les tâches qui ont une échéance : sans date, la première occurrence est aujourd'hui.
  if ((repeatAfter > 0 || repeatMode !== 0) && date == null && time == null) date = today;

  return {
    title: s.trim().replace(/\s+/g, ' '),
    date,
    time,
    priority,
    projectId,
    labels: matched,
    repeatAfter,
    repeatMode,
  };
}

/** Échéance (ms) d'un résultat d'analyse : sans heure -> midi ; heure seule -> aujourd'hui ou demain si passée. */
export function dueOf(parsed, now = Date.now()) {
  if (parsed.date == null && parsed.time == null) return null;
  let day = parsed.date;
  if (day == null) {
    const [h, m] = parsed.time;
    day = atTime(startOfDay(now), h, m) > now ? startOfDay(now) : addDays(startOfDay(now), 1);
  }
  return parsed.time ? atTime(day, parsed.time[0], parsed.time[1]) : atTime(day, 12, 0);
}
