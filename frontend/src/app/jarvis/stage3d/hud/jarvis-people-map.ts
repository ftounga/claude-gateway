import { normalizeName } from '../../scenes/jarvis-name-matcher';
import { JarvisUniverse, UniverseClient, UniversePerson, clientKey } from '../../scenes/jarvis-universe.models';
import { SessionTicket } from '../scenes3d/jarvis-session-memory';

/**
 * F-236 / SF-236-18 — **personnes et attentes** (NF-5), **mur de tickets** (NF-6) et **étagère « À envoyer »**.
 * Fichier <b>pur</b> : tout est lu dans l'univers (`people`, `waitingOn`, `toSend`) et la mémoire de session de
 * l'écran (tickets vus) — des noms, jamais un contenu ; aucun appel.
 */

/** Une personne du graphe : ce que j'attends d'elle, et si un message lui est prêt. */
export interface PersonRow {
  /** La clé du nœud personne de l'univers 3D (`client:nom`). */
  key: string;
  name: string;
  role: string | null;
  clientKey: string;
  clientName: string;
  /** Les chantiers qui l'attendent (« Chantier 3 — Bascule DNS »). */
  awaitedBy: string[];
  /** Un message prêt lui est adressé (étagère « À envoyer »). */
  message: boolean;
}

function matches(person: UniversePerson, name: string): boolean {
  const wanted = normalizeName(name);
  const full = normalizeName(person.name);
  return full === wanted || person.variants.some((v) => normalizeName(v) === wanted)
    || full.split(' ')[0] === wanted;
}

/**
 * Le graphe des personnes : par client, chaque personne connue, attendue (`waitingOn`) ou destinataire d'un message
 * prêt (`toSend`). Un nom attendu qui désigne une personne connue (nom, variante, prénom) ne fait qu'une ligne ; la
 * clé est celle du nœud que l'univers 3D pose (le nom attendu, comme `jarvis-layout-3d`).
 */
export function peopleMap(universe: JarvisUniverse | null): PersonRow[] {
  const rows: PersonRow[] = [];
  for (const c of universe?.clients ?? []) {
    rows.push(...clientPeople(c));
  }
  // Les plus sollicités d'abord : attendus, puis avec un message, puis le reste ; par nom.
  const weight = (r: PersonRow) => (r.awaitedBy.length > 0 ? 0 : r.message ? 1 : 2);
  return rows.sort((a, b) => weight(a) - weight(b) || a.name.localeCompare(b.name));
}

function clientPeople(c: UniverseClient): PersonRow[] {
  const ck = clientKey(c);
  const byKey = new Map<string, PersonRow>();
  const row = (name: string): PersonRow => {
    const known = c.people.find((p) => matches(p, name)) ?? null;
    // Même clé que le nœud 3D : le nom exact de la personne s'il est identique, sinon le nom tel qu'attendu.
    const keyName = known && normalizeName(known.name) === normalizeName(name) ? known.name : name;
    const key = `${ck}:${normalizeName(keyName)}`;
    const existing = byKey.get(key) ?? [...byKey.values()].find((r) => known && r.name === known.name);
    if (existing) {
      return existing;
    }
    const created: PersonRow = { key, name: known?.name ?? name, role: known?.role ?? null, clientKey: ck,
      clientName: c.name, awaitedBy: [], message: false };
    byKey.set(key, created);
    return created;
  };
  for (const s of c.subjects) {
    for (const ch of s.chantiers) {
      for (const name of ch.waitingOn) {
        const r = row(name);
        const label = `Chantier ${ch.number} — ${ch.title}`;
        if (!r.awaitedBy.includes(label)) {
          r.awaitedBy.push(label);
        }
      }
    }
  }
  for (const name of c.toSend) {
    row(name).message = true;
  }
  for (const p of c.people) {
    row(p.name);
  }
  return [...byKey.values()];
}

/** Une étagère « À envoyer » : par client, les destinataires des messages prêts. */
export interface ShelfRow {
  clientKey: string;
  clientName: string;
  recipients: string[];
}

export function sendShelf(universe: JarvisUniverse | null): ShelfRow[] {
  return (universe?.clients ?? []).filter((c) => c.toSend.length > 0)
    .map((c) => ({ clientKey: clientKey(c), clientName: c.name, recipients: [...c.toSend] }));
}

/** Le mur de tickets : les tickets vus pendant la session, groupés par poste (les plus récents d'abord). */
export interface TicketColumn {
  clientKey: string | null;
  clientName: string;
  tickets: { key: string; source: string }[];
}

export function ticketWall(tickets: readonly SessionTicket[], universe: JarvisUniverse | null): TicketColumn[] {
  const columns = new Map<string, TicketColumn>();
  for (const t of [...tickets].sort((a, b) => b.seenAt - a.seenAt)) {
    const id = t.clientKey ?? '';
    let column = columns.get(id);
    if (!column) {
      const client = universe?.clients.find((c) => clientKey(c) === t.clientKey);
      column = { clientKey: t.clientKey, clientName: client?.name ?? 'Sans poste', tickets: [] };
      columns.set(id, column);
    }
    column.tickets.push({ key: t.key, source: t.source });
  }
  return [...columns.values()];
}

/** Les tableaux détachables (calque L4). */
export type FloatId = 'personnes' | 'tickets' | 'envoyer';

/** Où poser un tableau flottant : à côté de son client (ancre projetée), empilé, gardé dans la zone libre. */
export function floatSpot(anchor: { x: number; y: number } | null, index: number,
    view: { width: number; height: number; left: number; right: number }): { x: number; y: number } {
  const w = 260;
  const top = 80;
  const minX = view.left + 12;
  const maxX = Math.max(minX, view.width - view.right - w - 12);
  const base = anchor ?? { x: (view.left + view.width - view.right) / 2 - w / 2, y: top };
  const x = Math.min(maxX, Math.max(minX, base.x + 40));
  const y = Math.min(view.height - 220, Math.max(top, base.y - 60 + index * 150));
  return { x: Math.round(x), y: Math.round(y) };
}
