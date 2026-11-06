// Adresses des serveurs (Vikunja pour les tâches, Joplin pour les notes). Masquées sur les pages de connexion ;
// modifiables seulement sur la page cachée #/serveurs (aucun lien n'y mène). Propres à ce navigateur.
import { DEFAULT_SERVER, normalizeUrl } from './api.js';
import { DEFAULT_NOTES_SERVER } from './notes/joplin.js';

const KEY = 'lielutodo.servers';
export const CONFIG_HASH = '#/serveurs';

export function servers() {
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem(KEY)); } catch { saved = null; }
  return {
    vikunja: saved?.vikunja || DEFAULT_SERVER,
    joplin: saved?.joplin || DEFAULT_NOTES_SERVER,
  };
}

function checked(url, what) {
  let u;
  try { u = new URL(url); } catch { u = null; }
  if (!u || !/^https?:$/.test(u.protocol) || !u.hostname.includes('.') && u.hostname !== 'localhost') {
    throw new Error(`Adresse du serveur de ${what} invalide`);
  }
  return url;
}

/** Enregistre les adresses (normalisées) ; une adresse égale à celle par défaut n'est pas gardée. */
export function saveServers({ vikunja, joplin }) {
  const v = checked(normalizeUrl(vikunja || DEFAULT_SERVER), 'tâches');
  const j = checked(normalizeUrl(joplin || DEFAULT_NOTES_SERVER).replace(/\/api$/, ''), 'notes');
  const value = {};
  if (v !== DEFAULT_SERVER) value.vikunja = v;
  if (j !== DEFAULT_NOTES_SERVER) value.joplin = j;
  try {
    if (Object.keys(value).length) localStorage.setItem(KEY, JSON.stringify(value));
    else localStorage.removeItem(KEY);
  } catch { /* stockage indisponible : valeurs par défaut */ }
  return { vikunja: v, joplin: j };
}
