import { MapGraph, MapNode } from '../../core/models/governance.models';

/**
 * **Le chemin d'accès** (F-173 / SF-173-05), en fonction pure : « comment j'atteins X », tiré des
 * relations de la carte. Le produit ne sonde rien : l'état d'un tronçon est celui écrit dans la carte.
 */

export type HopStatus = 'ouvert' | 'ferme' | 'inconnu' | 'depart';

export interface PathHop {
  /** `null` pour « Ce poste » et « Accès non cartographié ». */
  nodeId: string | null;
  label: string;
  kind: string | null;
  status: HopStatus;
}

export interface AccessPath {
  hops: PathHop[];
  /** Vrai si aucune ressource d'accès n'a été trouvée : la chaîne le dit. */
  entryUnknown: boolean;
}

/** Profondeur maximale de la recherche d'une entrée. */
export const ENTRY_DEPTH = 6;
/** Étapes affichées au plus. */
export const MAX_HOPS = 12;

const ACCESS_KINDS = new Set(['proxy', 'acces', 'hote']);
const ACCESS_WORDS = /vpn|bastion|proxy|jump/i;

export function isAccess(node: MapNode): boolean {
  return ACCESS_KINDS.has(node.kind) || ACCESS_WORDS.test(node.label);
}

export function hopStatus(state: string | null): HopStatus {
  switch ((state ?? '').toLowerCase()) {
    case 'joignable':
    case 'actif':
      return 'ouvert';
    case 'injoignable':
    case 'obsolete':
      return 'ferme';
    default:
      return 'inconnu';
  }
}

function hop(node: MapNode): PathHop {
  return { nodeId: node.id, label: node.label, kind: node.kind, status: hopStatus(node.state) };
}

/** La chaîne « Ce poste → accès → … → parents → X », ou `null` si X n'est pas sur le plan. */
export function accessPath(graph: MapGraph, nodeId: string): AccessPath | null {
  const byId = new Map(graph.nodes.map((n) => [n.id, n] as const));
  const target = byId.get(nodeId);
  if (!target) {
    return null;
  }
  const start: PathHop = { nodeId: null, label: 'Ce poste', kind: null, status: 'depart' };
  if (isAccess(target)) {
    return { hops: [start, hop(target)], entryUnknown: false };
  }

  // 1. La remontée : X, ses parents, jusqu'à la racine.
  const climb: MapNode[] = [target];
  const seenClimb = new Set([target.id]);
  let cursor = target.parentId ? byId.get(target.parentId) : undefined;
  while (cursor && !seenClimb.has(cursor.id)) {
    climb.push(cursor);
    seenClimb.add(cursor.id);
    cursor = cursor.parentId ? byId.get(cursor.parentId) : undefined;
  }

  // 2. L'entrée : le plus court chemin (liens dans les deux sens + parents) vers une ressource d'accès.
  const neighbours = new Map<string, Set<string>>();
  const link = (a: string, b: string) => {
    if (!byId.has(a) || !byId.has(b)) return;
    neighbours.set(a, (neighbours.get(a) ?? new Set()).add(b));
    neighbours.set(b, (neighbours.get(b) ?? new Set()).add(a));
  };
  for (const edge of graph.edges) {
    link(edge.source, edge.target);
  }
  for (const node of graph.nodes) {
    if (node.parentId) {
      link(node.id, node.parentId);
    }
  }

  const previous = new Map<string, string | null>();
  let frontier = climb.map((n) => n.id);
  frontier.forEach((id) => previous.set(id, null));
  let entry: string | null = null;
  for (let depth = 0; depth <= ENTRY_DEPTH && frontier.length && entry === null; depth++) {
    const next: string[] = [];
    for (const id of frontier) {
      const node = byId.get(id);
      if (node && depth > 0 && isAccess(node)) {
        entry = id;
        break;
      }
      for (const other of neighbours.get(id) ?? []) {
        if (!previous.has(other)) {
          previous.set(other, id);
          next.push(other);
        }
      }
    }
    frontier = next;
  }

  const hops: PathHop[] = [start];
  let joinAt: string | null = null;
  if (entry === null) {
    hops.push({ nodeId: null, label: 'Accès non cartographié', kind: null, status: 'inconnu' });
  } else {
    // En amont de l'entrée, les autres accès qui y mènent (poste → proxy → VPN → …), sans revenir en arrière.
    const upstream: MapNode[] = [];
    const used = new Set<string>([...previous.keys()]);
    let head = entry;
    for (let guard = 0; guard < ENTRY_DEPTH; guard++) {
      const before = [...(neighbours.get(head) ?? [])]
        .map((id) => byId.get(id)!)
        .find((n) => isAccess(n) && !used.has(n.id));
      if (!before) break;
      upstream.unshift(before);
      used.add(before.id);
      head = before.id;
    }
    upstream.forEach((n) => hops.push(hop(n)));
    // Le chemin entrée → … → point de la remontée où il se raccroche.
    let id: string | null = entry;
    while (id !== null) {
      if (seenClimb.has(id)) {
        joinAt = id;
        break;
      }
      hops.push(hop(byId.get(id)!));
      id = previous.get(id) ?? null;
    }
  }

  // 3. La remontée inversée, depuis le point de jonction (ou la racine) jusqu'à X.
  const fromIndex = joinAt === null ? climb.length - 1 : climb.findIndex((n) => n.id === joinAt);
  for (let i = fromIndex; i >= 0; i--) {
    hops.push(hop(climb[i]));
  }

  const seen = new Set<string>();
  const unique = hops.filter((h) => h.nodeId === null || (!seen.has(h.nodeId) && seen.add(h.nodeId) !== undefined));
  const bounded = unique.length > MAX_HOPS ? [...unique.slice(0, MAX_HOPS - 1), unique[unique.length - 1]] : unique;
  return { hops: bounded, entryUnknown: entry === null };
}
