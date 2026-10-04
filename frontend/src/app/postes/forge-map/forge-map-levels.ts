import { MapEdge, MapGraph, MapNode } from '../../core/models/governance.models';

/**
 * **Les niveaux du plan** (F-173 / SF-173-02, décision D4), en fonctions pures.
 *
 * <p>Le plan ne dessine jamais toute la carte d'un coup : il dessine <b>un niveau</b>. Le niveau 1
 * est le client (les ressources sans parent), le niveau 2 une plateforme (ce qu'elle contient), le
 * niveau 3 la fiche d'une ressource. Au-delà de {@link LEVEL_MAX} ressources sur un niveau, elles
 * sont regroupées par type : c'est ce qui rend lisibles 2 000 ressources sans en dessiner 2 000.</p>
 *
 * <p>L'endroit où l'on est tient en une chaîne — le paramètre `?noeud=` de l'URL :</p>
 * <ul>
 *   <li>absent : le niveau 1 ;</li>
 *   <li>l'identifiant d'une ressource qui en contient d'autres : son intérieur ;</li>
 *   <li>l'identifiant d'une ressource sans enfant : son niveau, elle sélectionnée ;</li>
 *   <li>`groupe:<type>` ou `groupe:<type>@<parent>` : un groupe ouvert.</li>
 * </ul>
 */

/** Ressources affichées sur un niveau avant regroupement par type. */
export const LEVEL_MAX = 24;

const GROUP_PREFIX = 'groupe:';

export type MapShape = 'round-rectangle' | 'diamond' | 'ellipse' | 'tag' | 'rectangle';

/** Un élément dessiné sur le niveau : une ressource, ou un groupe de ressources du même type. */
export interface ViewNode {
  id: string;
  label: string;
  kind: string;
  shape: MapShape;
  group: boolean;
  /** Nombre de ressources du groupe (groupe), ou d'enfants (ressource). */
  count: number;
  /** Un clic y descend : un groupe, ou une ressource qui en contient d'autres. */
  enterable: boolean;
  traps: number;
  stale: boolean;
  toMap: boolean;
  node: MapNode | null;
}

/** Un maillon du fil d'Ariane ; `focus` est la valeur de `?noeud=` qui y ramène (`null` = niveau 1). */
export interface Crumb {
  focus: string | null;
  label: string;
}

/** Le niveau à dessiner. */
export interface MapLevel {
  /** La ressource dont on voit l'intérieur, ou `null` au niveau 1. */
  frame: MapNode | null;
  /** Le type du groupe ouvert, s'il y en a un. */
  groupKind: string | null;
  nodes: ViewNode[];
  edges: MapEdge[];
  trail: Crumb[];
  /** La ressource sélectionnée (sans enfant), s'il y en a une. */
  selected: MapNode | null;
  /** Vrai si `?noeud=` désignait quelque chose qui n'existe plus : on est retombé au niveau 1. */
  unknownFocus: boolean;
}

const KIND_LABELS: Record<string, [string, string]> = {
  compte_aws: ['Compte AWS', 'Comptes AWS'],
  cluster: ['Cluster', 'Clusters'],
  depot: ['Dépôt', 'Dépôts'],
  forge: ['Forge', 'Forges'],
  registre: ['Registre', 'Registres'],
  domaine: ['Domaine', 'Domaines'],
  hote: ['Hôte', 'Hôtes'],
  proxy: ['Proxy', 'Proxys'],
  acces: ['Accès', 'Accès'],
  jeton: ['Jeton', 'Jetons'],
  service: ['Service', 'Services'],
  equipe: ['Équipe', 'Équipes'],
  url: ['URL', 'URL'],
  ip: ['Adresse IP', 'Adresses IP'],
  arn: ['ARN', 'ARN'],
  autre: ['Autre', 'Autres'],
};

/** Le nom lisible d'un type de ressource, au singulier ou au pluriel. */
export function kindLabel(kind: string, plural = false): string {
  const known = KIND_LABELS[kind];
  if (known) {
    return plural ? known[1] : known[0];
  }
  const text = (kind || 'autre').replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * La forme dit le type, le nom reste écrit (D3 : la couleur ne porte jamais seule l'information) :
 * plateformes en rectangle arrondi, accès en losange, équipes en ellipse, identifiants en étiquette.
 */
export function shapeOf(kind: string): MapShape {
  switch (kind) {
    case 'compte_aws':
    case 'cluster':
    case 'forge':
    case 'registre':
    case 'depot':
    case 'service':
      return 'round-rectangle';
    case 'proxy':
    case 'acces':
    case 'hote':
    case 'jeton':
      return 'diamond';
    case 'equipe':
      return 'ellipse';
    case 'url':
    case 'ip':
    case 'arn':
    case 'domaine':
      return 'tag';
    default:
      return 'rectangle';
  }
}

/** La valeur de `?noeud=` qui ouvre un groupe. */
export function groupFocus(kind: string, parentId: string | null): string {
  return GROUP_PREFIX + kind + (parentId ? '@' + parentId : '');
}

function parseGroup(focus: string): { kind: string; parentId: string | null } | null {
  if (!focus.startsWith(GROUP_PREFIX)) {
    return null;
  }
  const rest = focus.slice(GROUP_PREFIX.length);
  const at = rest.indexOf('@');
  const kind = at < 0 ? rest : rest.slice(0, at);
  return kind ? { kind, parentId: at < 0 ? null : rest.slice(at + 1) || null } : null;
}

function viewOf(node: MapNode): ViewNode {
  return {
    id: node.id,
    label: node.label,
    kind: node.kind,
    shape: shapeOf(node.kind),
    group: false,
    count: node.children,
    enterable: node.children > 0,
    traps: node.traps,
    stale: node.stale,
    toMap: node.toMap,
    node,
  };
}

/** Regroupe par type au-delà de {@link LEVEL_MAX} ; un type seul reste une ressource. */
function grouped(members: MapNode[], parentId: string | null): ViewNode[] {
  if (members.length <= LEVEL_MAX) {
    return members.map(viewOf);
  }
  const byKind = new Map<string, MapNode[]>();
  for (const member of members) {
    const list = byKind.get(member.kind) ?? [];
    list.push(member);
    byKind.set(member.kind, list);
  }
  const out: ViewNode[] = [];
  for (const [kind, list] of byKind) {
    if (list.length === 1) {
      out.push(viewOf(list[0]));
      continue;
    }
    out.push({
      id: groupFocus(kind, parentId),
      label: `${kindLabel(kind, true)} · ${list.length}`,
      kind,
      shape: shapeOf(kind),
      group: true,
      count: list.length,
      enterable: true,
      traps: list.reduce((sum, n) => sum + n.traps, 0),
      stale: list.every((n) => n.stale),
      toMap: list.some((n) => n.toMap),
      node: null,
    });
  }
  return out.sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
}

/** Les ancêtres d'une ressource, de la racine vers elle (elle exclue). */
export function ancestorsOf(node: MapNode, byId: Map<string, MapNode>): MapNode[] {
  const out: MapNode[] = [];
  const seen = new Set<string>([node.id]);
  let cursor = node.parentId ? byId.get(node.parentId) : undefined;
  while (cursor && !seen.has(cursor.id)) {
    out.unshift(cursor);
    seen.add(cursor.id);
    cursor = cursor.parentId ? byId.get(cursor.parentId) : undefined;
  }
  return out;
}

/** Le niveau à dessiner pour `?noeud=focus`. */
export function levelFor(graph: MapGraph, focus: string | null, hostName: string): MapLevel {
  const byId = new Map<string, MapNode>();
  const childrenOf = new Map<string | null, MapNode[]>();
  for (const node of graph.nodes) {
    byId.set(node.id, node);
  }
  for (const node of graph.nodes) {
    const parent = node.parentId && byId.has(node.parentId) ? node.parentId : null;
    const list = childrenOf.get(parent) ?? [];
    list.push(node);
    childrenOf.set(parent, list);
  }

  let frame: MapNode | null = null;
  let groupKind: string | null = null;
  let selected: MapNode | null = null;
  let unknownFocus = false;

  const wanted = (focus ?? '').trim();
  if (wanted) {
    const group = parseGroup(wanted);
    if (group) {
      const parent = group.parentId ? byId.get(group.parentId) ?? null : null;
      if (group.parentId && !parent) {
        unknownFocus = true;
      } else {
        frame = parent;
        groupKind = group.kind;
      }
    } else {
      const node = byId.get(wanted);
      if (!node) {
        unknownFocus = true;
      } else if (node.children > 0 && (childrenOf.get(node.id)?.length ?? 0) > 0) {
        frame = node;
      } else {
        selected = node;
        frame = node.parentId ? byId.get(node.parentId) ?? null : null;
        const siblings = childrenOf.get(frame ? frame.id : null) ?? [];
        if (siblings.length > LEVEL_MAX && siblings.filter((s) => s.kind === node.kind).length > 1) {
          groupKind = node.kind;
        }
      }
    }
  }

  const members = childrenOf.get(frame ? frame.id : null) ?? [];
  let nodes: ViewNode[];
  if (groupKind !== null) {
    nodes = members.filter((m) => m.kind === groupKind).map(viewOf);
    if (nodes.length === 0) {
      // Un groupe vidé depuis (la carte a changé) : on retombe au niveau de son cadre.
      groupKind = null;
      nodes = grouped(members, frame ? frame.id : null);
    }
  } else {
    nodes = grouped(members, frame ? frame.id : null);
  }

  const shown = new Set(nodes.filter((n) => !n.group).map((n) => n.id));
  const edges = graph.edges.filter((e) => shown.has(e.source) && shown.has(e.target));

  const trail: Crumb[] = [{ focus: null, label: hostName }];
  if (frame) {
    for (const ancestor of ancestorsOf(frame, byId)) {
      trail.push({ focus: ancestor.id, label: ancestor.label });
    }
    trail.push({ focus: frame.id, label: frame.label });
  }
  if (groupKind !== null) {
    trail.push({ focus: groupFocus(groupKind, frame ? frame.id : null), label: kindLabel(groupKind, true) });
  }

  return { frame, groupKind, nodes, edges, trail, selected, unknownFocus };
}

