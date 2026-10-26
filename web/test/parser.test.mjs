import test from 'node:test';
import assert from 'node:assert/strict';
import { parseQuickAdd, dueOf } from '../js/parser.js';
import { nextOccurrence, hasTime, dueText } from '../js/dates.js';

// Mercredi 15 octobre 2025, 10:00 (heure locale)
const NOW = new Date(2025, 9, 15, 10, 0).getTime();
const day = (y, m, d, h = 0, mi = 0) => new Date(y, m - 1, d, h, mi).getTime();
const projects = [{ id: 7, title: 'Perso' }, { id: 8, title: 'Boîte de réception' }];
const labels = [{ id: 3, title: 'urgent' }];

test('date relative, heure, projet, étiquette et priorité', () => {
  const p = parseQuickAdd('Appeler Marie demain 9h #Perso @urgent p1', projects, labels, NOW);
  assert.equal(p.title, 'Appeler Marie');
  assert.equal(p.date, day(2025, 10, 16));
  assert.deepEqual(p.time, [9, 0]);
  assert.equal(p.projectId, 7);
  assert.deepEqual(p.labels.map((l) => l.id), [3]);
  assert.equal(p.priority, 4);
  assert.equal(dueOf(p, NOW), day(2025, 10, 16, 9, 0));
});

test('une date seule est stockée à midi, masquée dans l’affichage', () => {
  const p = parseQuickAdd('Payer le loyer vendredi', [], [], NOW);
  assert.equal(p.date, day(2025, 10, 17));
  const due = dueOf(p, NOW);
  assert.equal(due, day(2025, 10, 17, 12, 0));
  assert.equal(hasTime(due), false);
  assert.equal(dueText(due, NOW), 'vendredi');
});

test('repère inconnu = reste dans le titre', () => {
  const p = parseQuickAdd('Acheter du pain #Inconnu @rien', projects, labels, NOW);
  assert.equal(p.title, 'Acheter du pain #Inconnu @rien');
  assert.equal(p.projectId, null);
});

test('récurrences', () => {
  assert.equal(parseQuickAdd('Sport chaque lundi', [], [], NOW).repeatAfter, 604800);
  const d = parseQuickAdd('Sport chaque lundi', [], [], NOW);
  assert.equal(d.date, day(2025, 10, 20));
  assert.equal(parseQuickAdd('Ménage tous les jours', [], [], NOW).repeatAfter, 86400);
  assert.equal(parseQuickAdd('Loyer tous les mois', [], [], NOW).repeatMode, 1);
  const e = parseQuickAdd('Plantes tous les 3 jours', [], [], NOW);
  assert.equal(e.repeatAfter, 3 * 86400);
  assert.equal(e.date, day(2025, 10, 15));
});

test('dans N jours / date numérique / heure seule', () => {
  assert.equal(parseQuickAdd('RDV dans 3 jours', [], [], NOW).date, day(2025, 10, 18));
  assert.equal(parseQuickAdd('Anniv 3/11', [], [], NOW).date, day(2025, 11, 3));
  assert.equal(parseQuickAdd('Anniv 3/9', [], [], NOW).date, day(2026, 9, 3));
  const later = parseQuickAdd('Appel 14h30', [], [], NOW);
  assert.equal(dueOf(later, NOW), day(2025, 10, 15, 14, 30));
  const passed = parseQuickAdd('Appel 8h', [], [], NOW);
  assert.equal(dueOf(passed, NOW), day(2025, 10, 16, 8, 0));
});

test('prochaine occurrence', () => {
  const due = day(2025, 10, 10, 12);
  assert.equal(nextOccurrence(due, 86400, 0, NOW), day(2025, 10, 15, 12));
  assert.equal(nextOccurrence(due, 0, 1, NOW), day(2025, 11, 10, 12));
});
