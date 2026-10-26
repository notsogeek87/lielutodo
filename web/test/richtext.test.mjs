import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { htmlForServer, htmlToText, textToHtml } from '../js/richtext.js';
import { taskFromJson, taskToRequest, commentFromJson } from '../js/model.js';

// Mêmes cas que RichTextTest.kt : les deux ports doivent rester identiques.
const cases = JSON.parse(readFileSync(new URL('./fixtures/richtext.json', import.meta.url), 'utf8'));

test('l’HTML devient un texte lisible', () => {
  for (const [html, text] of cases.toText) assert.equal(htmlToText(html), text, html);
});

test('le texte devient des paragraphes', () => {
  for (const [text, html] of cases.toHtml) assert.equal(textToHtml(text), html);
});

test('tout texte survit à l’aller-retour', () => {
  for (const t of cases.roundTrip) assert.equal(htmlToText(textToHtml(t)), t);
});

test('une description inchangée garde la mise en forme du serveur', () => {
  const rich = '<p><strong>Important</strong> : voir <a href="https://x.fr">le site</a></p>';
  assert.equal(htmlForServer(htmlToText(rich), rich), rich);
  assert.equal(htmlForServer('Autre', rich), '<p>Autre</p>');

  const task = taskFromJson({ id: 1, project_id: 2, description: rich });
  assert.equal(task.description, 'Important : voir le site (https://x.fr)');
  assert.equal(taskToRequest(task).description, rich);
  assert.equal(taskToRequest({ ...task, description: 'Nouvelle\nligne' }).description, '<p>Nouvelle</p><p>ligne</p>');
  assert.equal(commentFromJson({ id: 1, comment: '<p>Salut</p><p>toi</p>' }).text, 'Salut\ntoi');
});
