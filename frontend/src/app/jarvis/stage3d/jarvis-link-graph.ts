import { normalizeName } from '../scenes/jarvis-name-matcher';
import { Layout3d } from './jarvis-layout-3d';
import { HOLO_ALPHA, HoloRole } from './jarvis-motion';

/**
 * F-236 / SF-236-04 — **le graphe des liaisons lumineuses** (cadrage §8.2). Fichier <b>pur</b>.
 *
 * <p>Familles dont la donnée est dans l'univers : L-poste (cœur ↔ client), L-sujet (client ↔ sujet), L-chantier
 * (sujet ↔ chantier), L-étape (chantier ↔ étapes, chapelet), L-attente (chantier ↔ personne attendue),
 * L-interlocuteur (client ↔ personne). Cinq états : absent · latent 4 % · contexte 18 % · actif 45 % · allumé 100 %.</p>
 *
 * <p><b>Règles</b> : le chemin du sujet courant (cœur → client → sujet) est <b>actif</b> ; ses voisins (les autres
 * sujets du client courant) en <b>contexte</b> ; ce dont on parle (≤ 3 nœuds cités) est <b>allumé</b> de bout en bout
 * jusqu'au cœur ; « Montrer » allume ce qui est demandé et met tout le reste en latent ; en veille, tout est latent.</p>
 */

export type LinkFamily = 'poste' | 'sujet' | 'chantier' | 'etape' | 'attente' | 'interlocuteur';
export type LinkState = 'latent' | 'context' | 'active' | 'lit';

export const CORE_KEY = '@coeur';
/** D2 — au plus trois nœuds allumés à la fois. */
export const MAX_LIT_NODES = 3;

export interface Link3d {
  key: string;
  family: LinkFamily;
  from: string;
  to: string;
  state: LinkState;
  /** Teinte de la liaison (grammaire §23). */
  role: HoloRole;
  /** Pointillé (L-attente). */
  dashed: boolean;
}

export interface GraphContext {
  subjectId: string | null;
  /** Les nœuds cités, par priorité décroissante (le lecteur F-234 les ordonne). */
  lit: readonly string[];
  /** « Montrer » : les nœuds demandés (null = pas de « Montrer » en cours). */
  shown: readonly string[] | null;
  asleep: boolean;
  /** SF-236-20 — téléphone : seul le chemin du focus est dessiné ; le reste est absent (pas latent, §12.2). */
  narrow?: boolean;
}

export interface Graph3d {
  links: Link3d[];
  /** État de chaque nœud (le plus haut de ses liaisons, ou le sien s'il est allumé). */
  nodes: Map<string, LinkState>;
}

const RANK: Record<LinkState, number> = { latent: 0, context: 1, active: 2, lit: 3 };

/** L'opacité d'un état (§7.1 : 5 paliers seulement). */
export function stateAlpha(state: LinkState): number {
  return state === 'lit' ? HOLO_ALPHA.lit : state === 'active' ? HOLO_ALPHA.active
    : state === 'context' ? HOLO_ALPHA.context : HOLO_ALPHA.latent;
}

export function linkGraph(layout: Layout3d, ctx: GraphContext): Graph3d {
  const byKey = new Map(layout.nodes.map((n) => [n.key, n]));
  const links: Link3d[] = [];
  const parent = new Map<string, string>();
  const add = (family: LinkFamily, from: string, to: string, role: HoloRole = 'normal', dashed = false): void => {
    links.push({ key: `${family}:${from}>${to}`, family, from, to, state: 'latent', role, dashed });
  };
  for (const node of layout.nodes) {
    switch (node.kind) {
      case 'client':
        add('poste', CORE_KEY, node.key, node.connected ? 'normal' : 'off');
        parent.set(node.key, CORE_KEY);
        break;
      case 'subject':
        add('sujet', node.clientKey, node.key);
        parent.set(node.key, node.clientKey);
        break;
      case 'chantier':
        if (node.subjectId) {
          add('chantier', node.subjectId, node.key);
          parent.set(node.key, node.subjectId);
        }
        for (const name of node.waitingOn ?? []) {
          const person = byKey.get(`${node.clientKey}:${normalizeName(name)}`);
          if (person) {
            add('attente', node.key, person.key, 'wait', true);
          }
        }
        break;
      case 'step':
        if (node.chantierKey) {
          add('etape', node.chantierKey, node.key, node.done ? 'normal' : 'off');
          parent.set(node.key, node.chantierKey);
        }
        break;
      case 'person':
        add('interlocuteur', node.clientKey, node.key);
        parent.set(node.key, node.clientKey);
        break;
    }
  }

  const nodeState = new Map<string, LinkState>();
  const raise = (key: string, state: LinkState): void => {
    if (RANK[state] > RANK[nodeState.get(key) ?? 'latent']) {
      nodeState.set(key, state);
    }
  };
  const linkState = new Map<string, LinkState>();
  const raiseLink = (from: string, to: string, state: LinkState): void => {
    for (const link of links) {
      if (link.from === from && link.to === to && RANK[state] > RANK[linkState.get(link.key) ?? 'latent']) {
        linkState.set(link.key, state);
      }
    }
  };
  /**
   * Le chemin d'un nœud jusqu'au cœur : ses liaisons prennent l'état demandé ; seul le nœud lui-même est « allumé »,
   * les nœuds traversés restent « actifs » (D2 : au plus trois nœuds allumés).
   */
  const path = (key: string, state: LinkState): void => {
    let at: string | undefined = key;
    let guard = 0;
    while (at && at !== CORE_KEY && guard++ < 8) {
      raise(at, at === key || state !== 'lit' ? state : 'active');
      const up = parent.get(at);
      if (!up) {
        break;
      }
      raiseLink(up, at, state);
      at = up;
    }
    raise(CORE_KEY, 'active');
  };

  if (!ctx.asleep) {
    if (ctx.shown !== null) {
      for (const key of ctx.shown) {
        if (byKey.has(key)) {
          path(key, 'lit');
        }
      }
      // « Montrer » : tout est mis en avant, la borne D2 ne s'applique pas aux éléments demandés (≤ 8, F-234).
      for (const key of ctx.shown) {
        if (byKey.has(key)) {
          nodeState.set(key, 'lit');
        }
      }
    } else {
      const current = ctx.subjectId ? byKey.get(ctx.subjectId) : undefined;
      if (current) {
        // Voisins : les autres sujets du client courant et ses chantiers.
        for (const node of layout.nodes) {
          if (node.kind === 'subject' && node.clientKey === current.clientKey && node.key !== current.key) {
            raise(node.key, 'context');
            raiseLink(node.clientKey, node.key, 'context');
          }
          if (node.kind === 'chantier' && node.subjectId === current.key) {
            raise(node.key, 'context');
            raiseLink(current.key, node.key, 'context');
          }
        }
        path(current.key, 'active');
      }
      const lit = ctx.lit.filter((k) => byKey.has(k)).slice(0, MAX_LIT_NODES);
      for (const key of lit) {
        path(key, 'lit');
        // Un chantier cité allume son chapelet et ses attentes ; une personne citée, l'attente qui la vise.
        for (const link of links) {
          if ((link.from === key && (link.family === 'etape' || link.family === 'attente'))
              || (link.to === key && link.family === 'attente')) {
            linkState.set(link.key, 'lit');
            if (link.to !== key) {
              raise(link.to, 'active');
            }
            if (link.from !== key) {
              raise(link.from, 'active');
            }
          }
        }
      }
    }
  }
  for (const link of links) {
    link.state = linkState.get(link.key) ?? 'latent';
  }
  if (ctx.narrow) {
    return { links: links.filter((l) => l.state === 'active' || l.state === 'lit'), nodes: nodeState };
  }
  return { links, nodes: nodeState };
}

/** Les nœuds allumés (pour la borne D2 et les tests). */
export function litNodes(graph: Graph3d): string[] {
  return [...graph.nodes.entries()].filter(([k, s]) => s === 'lit' && k !== CORE_KEY).map(([k]) => k);
}
