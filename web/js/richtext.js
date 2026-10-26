// Descriptions et commentaires : Vikunja les stocke en HTML (éditeur du site web), l'app les édite en texte.
// Port fidèle de RichText.kt : toute évolution de l'un se répercute sur l'autre (cas partagés :
// test/fixtures/richtext.json). Garantie : htmlToText(textToHtml(t)) === t pour tout texte.

const TAG = /<\/?[a-zA-Z][^>]*>/g;
const ITEM = '\u0000';

/** Le texte vient-il de l'éditeur HTML ? Un texte brut (anciennes versions) est rendu tel quel. */
export const looksLikeHtml = (s) => /<\/?[a-zA-Z][^>]*>/.test(s);

export function htmlToText(html) {
  const input = String(html ?? '');
  if (!looksLikeHtml(input)) return input;
  let s = input.replace(/>\s*\n\s*</g, '><').replace(/\n/g, ' ');
  // Puces : un repère de début de ligne, résolu à la fin. Cases à cocher avant les puces ordinaires.
  s = s.replace(/<li[^>]*data-checked="true"[^>]*>/gi, `${ITEM}- [x] `);
  s = s.replace(/<li[^>]*data-checked="false"[^>]*>/gi, `${ITEM}- [ ] `);
  s = s.replace(/<li[^>]*>/gi, `${ITEM}- `);
  // Lien dont le texte n'est pas l'adresse : on garde l'adresse.
  s = s.replace(/<a[^>]*href="([^"]*)"[^>]*>(.*?)<\/a>/gi, (_, href, label) =>
    (label.replace(TAG, '') === href || !href.trim() ? label : `${label} (${href})`));
  s = s.replace(/<br\s*\/?>/gi, '\n');
  // Fin de bloc suivie d'un AUTRE bloc (balise ouvrante) : nouvelle ligne.
  s = s.replace(/<\/(p|h[1-6]|blockquote|pre|ul|ol)>\s*<(?!\/)/gi, '</$1>\n<');
  s = s.replace(TAG, '');
  if (s.startsWith(ITEM)) s = s.slice(1);
  s = s.split(`\n${ITEM}`).join('\n').split(ITEM).join('\n');
  return decodeEntities(s);
}

export function textToHtml(text) {
  if (!text) return '';
  return String(text).split('\n').map((line) => `<p>${encodeEntities(line)}</p>`).join('');
}

/** Valeur à renvoyer au serveur : l'HTML d'origine si le texte n'a pas changé (sa mise en forme est gardée). */
export const htmlForServer = (text, serverHtml) =>
  (htmlToText(serverHtml || '') === text ? serverHtml || '' : textToHtml(text));

const encodeEntities = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const decodeEntities = (s) => s.replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
  .replace(/&#39;/g, "'").replace(/&#x27;/g, "'").replace(/&amp;/g, '&');
