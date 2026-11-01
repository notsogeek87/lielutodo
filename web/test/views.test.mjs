import test from 'node:test';
import assert from 'node:assert/strict';
import { buildView } from '../js/views.js';

const at = (d, h, m = 0) => new Date(2026, 10, d, h, m).getTime();
const task = (o) => ({
  projectId: 1, title: `t${o.id}`, done: false, due: null, priority: 0, labels: [], position: 0,
  reminders: [], parentId: 0, assignees: [], ...o,
});
const ui = { showDone: new Set() };
const ids = (view, i = 0) => view.sections[i].tasks.map((t) => t.id);

test('boîte de réception triée comme l\'APK : date, sans date à la fin, puis priorité et position', () => {
  const state = {
    defaultProjectId: 1,
    projects: [{ id: 1, title: 'Inbox' }],
    tasks: [
      task({ id: 1, position: 1 }),
      task({ id: 2, due: at(5, 9), priority: 1 }),
      task({ id: 3, due: at(3, 18) }),
      task({ id: 4, due: at(5, 9), priority: 4 }),
      task({ id: 5, position: 0, priority: 3 }),
      task({ id: 6, reminders: [{ at: at(4, 8), relativeTo: '' }] }),
    ],
    filterResults: {},
  };
  const view = buildView(state, { name: 'inbox' }, ui, at(1, 8));
  assert.deepEqual(ids(view), [3, 6, 4, 2, 5, 1]);
  assert.equal(view.draggable, false);
});

test('un projet garde l\'ordre manuel', () => {
  const state = {
    defaultProjectId: 9,
    projects: [{ id: 1, title: 'Courses' }, { id: 9, title: 'Inbox' }],
    tasks: [task({ id: 1, position: 2, due: at(2, 9) }), task({ id: 2, position: 1 })],
    filterResults: {},
  };
  const view = buildView(state, { name: 'project', id: 1 }, ui, at(1, 8));
  assert.deepEqual(ids(view), [2, 1]);
  assert.equal(view.draggable, true);
});

test('À venir : tâches avec heure d\'abord, puis heure et priorité', () => {
  const state = {
    projects: [{ id: 1, title: 'Inbox' }],
    tasks: [
      task({ id: 1, due: at(2, 12) }),
      task({ id: 2, due: at(2, 15) }),
      task({ id: 3, due: at(2, 9), priority: 1 }),
      task({ id: 4, due: at(2, 9), priority: 3 }),
    ],
    filterResults: {},
  };
  const view = buildView(state, { name: 'upcoming' }, { ...ui, upcomingStart: at(2, 0) }, at(2, 0));
  assert.deepEqual(ids(view), [4, 3, 2, 1]);
});
