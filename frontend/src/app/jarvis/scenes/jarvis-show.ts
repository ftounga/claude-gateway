import { normalizeName } from './jarvis-name-matcher';
import { JarvisUniverse, NodeRef, clientKey } from './jarvis-universe.models';

/**
 * **« Montrer »** (F-234 / SF-234-13, P5, D18) — le PO **demande à voir** (« montre-moi mes clients », « ceux qui
 * sont connectés », « les chantiers bloqués »…). L'agent écrit, sous sa ligne 🎙, une ligne marqueur
 * `🖥 montrer: <filtre> [client]` (consigne orale du **message**, aucun outil : préfixe intact) ; l'écran la lit, la
 * **masque** et la résout ici, sur l'univers déjà chargé — aucun appel réseau, aucun appel modèle.
 *
 * Filtres **fermés** : un filtre inconnu ne montre rien.
 */

/** La marque de la ligne « Montrer ». */
export const SHOW_MARK = '🖥';

/** Les filtres compris. */
export type ShowFilter = 'clients' | 'connectes' | 'deconnectes' | 'chantiers-bloques' | 'etapes-en-cours' | 'a-envoyer';

const FILTERS: Record<string, ShowFilter> = {
  'clients': 'clients',
  'connectes': 'connectes',
  'deconnectes': 'deconnectes',
  'chantiers bloques': 'chantiers-bloques',
  'etapes en cours': 'etapes-en-cours',
  'a envoyer': 'a-envoyer',
};

/** Au plus autant d'éléments montrés (lisibilité sur téléphone). */
export const MAX_SHOWN = 8;

/** F-237 / SF-237-09 — les formes « pièces » de la ligne : résolues par l'index des pièces, pas par l'univers. */
export type PieceShowFilter = 'pieces' | 'preuves' | 'entrees';

/** Ce que l'agent a demandé de montrer. */
export interface ShowRequest {
  filter: ShowFilter | PieceShowFilter;
  /** Le nom du client qui restreint, tel qu'écrit. */
  client: string | null;
  /** F-237 / SF-237-09 — `preuves chantier 3`. */
  chantier?: number | null;
  /** F-237 / SF-237-09 — `pieces proof:ab12,page:…` (≤ 8). */
  ids?: string[];
}

/** F-237 / SF-237-09 — au plus autant d'identifiants dans `🖥 montrer: pieces …`. */
export const MAX_SHOW_IDS = 8;
const PIECE_ID = /^(dep|proof|page|video|img|slides|send|mail|moment|need|diag):[A-Za-z0-9@/_.:-]{1,120}$/;

/** Vrai si la demande vise les pièces (galerie) plutôt que l'univers. */
export function isPieceShow(request: ShowRequest | null | undefined): boolean {
  return request?.filter === 'pieces' || request?.filter === 'preuves' || request?.filter === 'entrees';
}

/** F-237 / SF-237-09 — `preuves [chantier N]`, `entrees [chantier N]`, `pieces <id>,<id>` ; `null` sinon. */
export function parsePieceShow(rest: string): ShowRequest | null {
  const raw = rest.trim();
  const ids = /^pi[eè]ces?\s*:?\s+(.+)$/i.exec(raw);
  if (ids) {
    const list = ids[1].split(/[\s,;]+/).map((id) => id.trim()).filter((id) => PIECE_ID.test(id));
    const unique = [...new Set(list)].slice(0, MAX_SHOW_IDS);
    return unique.length ? { filter: 'pieces', client: null, ids: unique } : null;
  }
  const words = normalizeName(raw);
  const kind = /^(preuves?|entrees?)(?:\s+(?:du\s+)?chantier\s+(\d{1,4}))?$/.exec(words);
  if (!kind) {
    return null;
  }
  return {
    filter: kind[1].startsWith('preuve') ? 'preuves' : 'entrees', client: null,
    chantier: kind[2] ? Number(kind[2]) : null,
  };
}

/** Un élément montré. */
export interface ShownItem {
  node: NodeRef;
  /** Ce qui s'écrit à côté (« connecté », « attend Zahi », « étape 2/5 — Valider le TTL »). */
  note: string | null;
  /** Poste connecté (halo vert) / déconnecté (éteint) ; `null` si sans objet. */
  connected: boolean | null;
}

/** Ce que l'écran met au premier plan. */
export interface ShowFocus {
  filter: ShowFilter;
  /** « 2 postes connectés sur 3 ». */
  title: string;
  items: ShownItem[];
  /** Plus d'éléments que {@link MAX_SHOWN} : le reste n'est pas dessiné. */
  more: number;
}

/** Une ligne de réponse est-elle la ligne « Montrer » ? (elle n'est alors ni lue, ni dite, ni affichée). */
export function isShowLine(line: string): boolean {
  return (line ?? '').trimStart().startsWith(SHOW_MARK);
}

/** Lit `🖥 montrer: <filtre> [client]` ; `null` si la ligne n'en est pas une ou si le filtre est inconnu. */
export function parseShowLine(line: string): ShowRequest | null {
  const trimmed = (line ?? '').trim();
  if (!trimmed.startsWith(SHOW_MARK)) {
    return null;
  }
  const match = /^montrer\s*:\s*(.+)$/i.exec(trimmed.slice(SHOW_MARK.length).replace(/^\uFE0F/, '').trim());
  if (!match) {
    return null;
  }
  const pieces = parsePieceShow(match[1]);
  if (pieces) {
    return pieces;
  }
  const words = normalizeName(match[1]);
  for (const [name, filter] of Object.entries(FILTERS)) {
    if (words === name || words.startsWith(`${name} `)) {
      const rest = words.slice(name.length).trim();
      return { filter, client: rest || null };
    }
  }
  return null;
}

/** Retire les lignes « Montrer » d'un texte à afficher. */
export function stripShowLines(text: string): string {
  return (text ?? '').split('\n').filter((l) => !isShowLine(l)).join('\n');
}

/** Résout une demande sur l'univers. `null` si l'univers manque ou si le client nommé est inconnu. */
export function resolveShow(universe: JarvisUniverse | null, request: ShowRequest | null): ShowFocus | null {
  if (!universe || !request) {
    return null;
  }
  const filter = request.filter;
  if (filter === 'pieces' || filter === 'preuves' || filter === 'entrees') {
    return null; // F-237 / SF-237-09 — les pièces s'affichent dans la galerie, pas sur l'univers
  }
  let clients = universe.clients;
  if (request.client) {
    const wanted = request.client;
    clients = clients.filter((c) => normalizeName(c.name) === wanted || normalizeName(c.name).includes(wanted));
    if (clients.length === 0) {
      return null;
    }
  }
  const items: ShownItem[] = [];
  const clientNode = (c: (typeof clients)[number]): NodeRef =>
    ({ kind: 'client', key: clientKey(c), label: c.name, clientKey: clientKey(c) });
  let title: string;
  switch (filter) {
    case 'clients':
    case 'connectes':
    case 'deconnectes': {
      const wanted = clients.filter((c) => request.filter === 'clients'
        || (request.filter === 'connectes' ? c.connected : !c.connected));
      for (const c of wanted) {
        items.push({ node: clientNode(c), note: c.virtual ? 'hébergé' : c.connected ? 'connecté' : 'déconnecté',
          connected: c.virtual ? null : c.connected });
      }
      const connected = clients.filter((c) => c.connected).length;
      title = request.filter === 'clients'
        ? `${plural(wanted.length, 'client')} · ${connected} connecté${connected > 1 ? 's' : ''}`
        : request.filter === 'connectes'
          ? `${plural(wanted.length, 'poste')} connecté${wanted.length > 1 ? 's' : ''} sur ${clients.length}`
          : `${plural(wanted.length, 'poste')} déconnecté${wanted.length > 1 ? 's' : ''} sur ${clients.length}`;
      break;
    }
    case 'chantiers-bloques':
    case 'etapes-en-cours': {
      const blocked = request.filter === 'chantiers-bloques';
      for (const c of clients) {
        for (const s of c.subjects) {
          for (const ch of s.chantiers) {
            if (blocked ? ch.waitingOn.length === 0 : !ch.step) {
              continue;
            }
            items.push({
              node: { kind: 'chantier', key: `${s.id}#${ch.number}`, label: `Chantier ${ch.number} — ${ch.title}`,
                clientKey: clientKey(c), subjectId: s.id },
              note: blocked
                ? `attend ${ch.waitingOn.join(', ')}`
                : `étape ${ch.step!.rank}/${Math.max(ch.total, ch.step!.rank)} — ${ch.step!.title}`,
              connected: null,
            });
          }
        }
      }
      title = blocked
        ? `${plural(items.length, 'chantier')} bloqué${items.length > 1 ? 's' : ''}`
        : `${plural(items.length, 'étape')} en cours`;
      break;
    }
    case 'a-envoyer': {
      let total = 0;
      for (const c of clients) {
        for (const name of c.toSend) {
          total++;
          const person = c.people.find((p) => normalizeName(p.name) === normalizeName(name)
            || p.variants.some((v) => normalizeName(v) === normalizeName(name)));
          items.push({
            node: { kind: 'person', key: `${clientKey(c)}:${normalizeName(person?.name ?? name)}`,
              label: person?.name ?? name, clientKey: clientKey(c) },
            note: c.name, connected: null,
          });
        }
      }
      const count = request.client ? total : Math.max(total, universe.toSendCount);
      title = `${plural(count, 'message')} à envoyer`;
      break;
    }
  }
  return { filter, title, items: items.slice(0, MAX_SHOWN), more: Math.max(0, items.length - MAX_SHOWN) };
}

function plural(n: number, noun: string): string {
  return `${n} ${noun}${n > 1 ? 's' : ''}`;
}

/** « c'est bon », « merci », « ferme » : le PO a vu, l'écran revient à l'état normal (sans tour). */
export function isShowDismiss(said: string): boolean {
  const words = normalizeName(said).replace(/^jarvis\s+/, '');
  return /^(c est bon|c est bon merci|merci c est bon|ok merci|merci|ferme|masque|cache|ca ira|c est vu|vu)$/.test(words);
}
