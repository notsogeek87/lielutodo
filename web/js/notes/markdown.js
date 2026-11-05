// Édition Markdown de la saisie d'une note (port de MarkdownEditing.kt : mêmes règles, mêmes cas de test) et rendu
// de l'aperçu. Une valeur = { text, start, end } (sélection du <textarea>).

const BLOCK = /^(\s*)(#{1,6} |[-*+] \[[ xX]] |[-*+] |\d+[.)] |> )/;
const LIST_LINE = /^(\s*)([-*+] \[[ xX]] |[-*+] |(\d+)([.)]) |> )(.*)$/;
export const HEADING = '## ';
export const BULLET = '- ';
export const NUMBERED = '1. ';
export const CHECKBOX = '- [ ] ';
export const QUOTE = '> ';

const isWord = (c) => /[\p{L}\p{N}'-]/u.test(c);
const markerMatch = (line) => BLOCK.exec(line)?.[2] ?? null;
const markerOf = (line) => {
  const m = markerMatch(line)?.trimEnd();
  if (!m) return null;
  if (/^\d/.test(m)) return NUMBERED.trimEnd();
  if (m.includes('[')) return CHECKBOX.trimEnd();
  return m;
};
function lineBounds(text, start, end) {
  const s = start === 0 ? 0 : text.lastIndexOf('\n', start - 1) + 1;
  const e = text.indexOf('\n', end);
  return [s, e < 0 ? text.length : e];
}
const replaceRange = (t, a, b, s) => t.slice(0, a) + s + t.slice(b);

/** Ajoute ou retire (bascule) un marqueur de début de ligne sur les lignes de la sélection. */
export function toggleLinePrefix(v, marker) {
  const [s, e] = lineBounds(v.text, v.start, v.end);
  const lines = v.text.slice(s, e).split('\n');
  const allHave = lines.every((l) => markerOf(l) === marker.trimEnd());
  let n = 1;
  const out = lines.map((line) => {
    const indent = /^[ \t]*/.exec(line)[0];
    const content = line.slice(indent.length).replace(markerMatch(line) ?? '', '');
    if (allHave) return indent + content;
    if (marker === NUMBERED) return `${indent}${n++}. ${content}`;
    return indent + marker + content;
  }).join('\n');
  const text = replaceRange(v.text, s, e, out);
  if (v.start === v.end && lines.length === 1) {
    const pos = Math.min(Math.max(v.start + out.length - (e - s), s), s + out.length);
    return { text, start: pos, end: pos };
  }
  return { text, start: s, end: s + out.length };
}

/** Case de la ligne du curseur : « - [ ] » ↔ « - [x] » ; une ligne sans case en reçoit une. */
export function toggleCheckbox(v) {
  const [s, e] = lineBounds(v.text, v.start, v.end);
  const line = v.text.slice(s, e);
  const box = /^(\s*[-*+] )\[([ xX])]/.exec(line);
  if (line.includes('\n') || !box) return toggleLinePrefix(v, CHECKBOX);
  const at = s + box[1].length + 1;
  return { ...v, text: replaceRange(v.text, at, at + 1, box[2] === ' ' ? 'x' : ' ') };
}

/** Gras, italique, barré, code : sur la sélection ou le mot sous le curseur ; 2e appui = retrait. */
export function toggleWrap(v, prefix, suffix = prefix) {
  const t = v.text;
  let a = v.start;
  let b = v.end;
  if (a === b) {
    while (a > 0 && isWord(t[a - 1])) a--;
    while (b < t.length && isWord(t[b])) b++;
    if (a === b) return { text: replaceRange(t, a, b, prefix + suffix), start: a + prefix.length, end: a + prefix.length };
  }
  const before = t.slice(0, a);
  const after = t.slice(b);
  const sel = t.slice(a, b);
  const insideLonger = prefix === '*' && before.endsWith('**') && !before.endsWith('***');
  if (!insideLonger && before.endsWith(prefix) && after.startsWith(suffix)) {
    const na = a - prefix.length;
    return { text: before.slice(0, -prefix.length) + sel + after.slice(suffix.length), start: na, end: na + sel.length };
  }
  if (sel.length >= prefix.length + suffix.length && sel.startsWith(prefix) && sel.endsWith(suffix)) {
    const inner = sel.slice(prefix.length, sel.length - suffix.length);
    return { text: before + inner + after, start: a, end: a + inner.length };
  }
  return { text: before + prefix + sel + suffix + after, start: a + prefix.length, end: a + prefix.length + sel.length };
}

/** Lien : la sélection (ou le mot) devient le texte, curseur sur l'adresse ; sans texte, « lien » sélectionné. */
export function insertLink(v) {
  const t = v.text;
  let a = v.start;
  let b = v.end;
  if (a === b) {
    while (a > 0 && isWord(t[a - 1])) a--;
    while (b < t.length && isWord(t[b])) b++;
  }
  const label = t.slice(a, b);
  if (!label) return { text: replaceRange(t, a, b, '[lien](https://)'), start: a + 1, end: a + 5 };
  const ins = `[${label}](https://)`;
  const pos = a + ins.length - 1;
  return { text: replaceRange(t, a, b, ins), start: pos, end: pos };
}

/** Entrée au bout d'un élément de liste : le prolonge ; sur un élément vide, sort de la liste. */
export function continueList(old, next) {
  if (next.start !== next.end || next.text.length !== old.text.length + 1) return next;
  const c = next.start;
  if (c === 0 || next.text[c - 1] !== '\n') return next;
  if (next.text.slice(0, c - 1) + next.text.slice(c) !== old.text) return next;
  const ls = next.text.lastIndexOf('\n', c - 2) + 1;
  const m = LIST_LINE.exec(next.text.slice(ls, c - 1));
  if (!m) return next;
  const [, indent, marker, num, delim, content] = m;
  if (!content.trim()) return { text: replaceRange(next.text, ls, c, ''), start: ls, end: ls };
  const nextMarker = num ? `${Number(num) + 1}${delim} ` : marker.includes('[') ? `${marker.slice(0, 2)}[ ] ` : marker;
  const ins = indent + nextMarker;
  return { text: replaceRange(next.text, c, c, ins), start: c + ins.length, end: c + ins.length };
}

/** Titre tiré de la première ligne non vide (sans Markdown), pour une note sans titre. */
export function titleFromBody(body) {
  const first = String(body).split('\n').map((l) => l.trim()).find((l) => l);
  if (!first) return '';
  const clean = first.replace(markerMatch(first) ?? '', '').replace(/[*_~`]/g, '').trim();
  return clean.length <= 80 ? clean : `${clean.slice(0, 80).trimEnd()}…`;
}

/** Aperçu sur une ligne pour la liste : sans Markdown, cases en ☐/☑. */
export function plainPreview(body, max = 160) {
  return String(body).split('\n').map((l) => l.trim()).filter(Boolean).map((line) => {
    const box = /^[-*+] \[([ xX])] /.exec(line);
    const clean = line.replace(markerMatch(line) ?? '', '').replace(/!\[([^\]]*)]\([^)\s]+\)/g, '🖼 $1').replace(/(\*\*|__|~~|`|\*)/g, '');
    return box ? `${box[1] === ' ' ? '☐' : '☑'} ${clean}` : clean;
  }).join(' · ').slice(0, max);
}

// ---------- Rendu de l'aperçu (sûr : tout le texte est échappé avant la mise en forme) ----------
const esc = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const safeUrl = (u) => (/^(https?:|mailto:)/i.test(u) ? u : '#');

function inline(text) {
  const codes = [];
  const tokens = []; // images et liens de ressources : à l'abri du gras/italique (attributs alt, ids)
  const keep = (html) => { tokens.push(html); return `\u0001${tokens.length - 1}\u0001`; };
  let s = esc(text).replace(/`([^`]+)`/g, (_, c) => { codes.push(c); return `\u0000${codes.length - 1}\u0000`; });
  s = s
    // Image jointe Joplin : `![nom](:/<id de ressource>)`, chargée ensuite depuis le serveur (data-jres).
    .replace(/!\[([^\]]*)]\(:\/([0-9a-fA-F]{32})\)/g, (_, alt, id) => keep(`<img class="md-img" data-jres="${id.toLowerCase()}" alt="${alt}">`))
    .replace(/!\[([^\]]*)]\((https?:[^)\s]+)\)/g, (_, alt, url) => keep(`<img class="md-img" src="${url}" alt="${alt}" loading="lazy">`))
    // Autre pièce jointe Joplin : lien ouvert depuis le serveur.
    .replace(/\[([^\]]+)]\(:\/([0-9a-fA-F]{32})\)/g, (_, label, id) => keep(`<a href="#" data-jres-link="${id.toLowerCase()}">${label}</a>`))
    .replace(/\[([^\]]+)]\(([^)\s]+)\)/g, (_, label, url) => `<a href="${safeUrl(url.replace(/&amp;/g, '&'))}" target="_blank" rel="noopener noreferrer">${label}</a>`)
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')
    .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
    .replace(/(^|[^\w])_([^_]+)_(?=$|[^\w])/g, '$1<em>$2</em>');
  return s
    .replace(/\u0001(\d+)\u0001/g, (_, i) => tokens[Number(i)])
    .replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[Number(i)]}</code>`);
}

/** Ids des ressources Joplin (`:/<id>`) citées dans une note. */
export const resourceIds = (md) => [...new Set([...String(md).matchAll(/\]\(:\/([0-9a-fA-F]{32})\)/g)].map((m) => m[1].toLowerCase()))];

/** Markdown courant (titres, listes, cases, citations, code, liens, gras/italique/barré) → HTML sûr. */
export function renderMarkdown(md) {
  const lines = String(md).replace(/\r/g, '').split('\n');
  const out = [];
  let list = null; // 'ul' | 'ol'
  let para = [];
  const flushPara = () => { if (para.length) { out.push(`<p>${para.map(inline).join('<br>')}</p>`); para = []; } };
  const closeList = () => { if (list) { out.push(`</${list}>`); list = null; } };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^```/.test(line)) {
      flushPara(); closeList();
      const code = [];
      for (i++; i < lines.length && !/^```/.test(lines[i]); i++) code.push(lines[i]);
      out.push(`<pre><code>${esc(code.join('\n'))}</code></pre>`);
      continue;
    }
    let m;
    if (!line.trim()) { flushPara(); closeList(); continue; }
    if ((m = /^(#{1,6}) (.*)$/.exec(line))) { flushPara(); closeList(); out.push(`<h${m[1].length + 1}>${inline(m[2])}</h${m[1].length + 1}>`); continue; }
    if ((m = /^> ?(.*)$/.exec(line))) { flushPara(); closeList(); out.push(`<blockquote>${inline(m[1])}</blockquote>`); continue; }
    if ((m = /^\s*[-*+] \[([ xX])] (.*)$/.exec(line))) {
      flushPara();
      if (list !== 'ul') { closeList(); out.push('<ul class="md-tasks">'); list = 'ul'; }
      out.push(`<li class="md-task${m[1] === ' ' ? '' : ' done'}"><span class="md-box" aria-hidden="true">${m[1] === ' ' ? '☐' : '☑'}</span> ${inline(m[2])}</li>`);
      continue;
    }
    if ((m = /^\s*[-*+] (.*)$/.exec(line))) {
      flushPara();
      if (list !== 'ul') { closeList(); out.push('<ul>'); list = 'ul'; }
      out.push(`<li>${inline(m[1])}</li>`);
      continue;
    }
    if ((m = /^\s*\d+[.)] (.*)$/.exec(line))) {
      flushPara();
      if (list !== 'ol') { closeList(); out.push('<ol>'); list = 'ol'; }
      out.push(`<li>${inline(m[1])}</li>`);
      continue;
    }
    closeList();
    para.push(line);
  }
  flushPara(); closeList();
  return out.join('');
}
