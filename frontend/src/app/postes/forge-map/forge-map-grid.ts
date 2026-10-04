import { MapGraph, MapNode } from '../../core/models/governance.models';

/**
 * **La grille des environnements** (F-173 / SF-173-04), en fonctions pures : domaine × environnement,
 * calculée sur le plan déjà lu — aucun appel de plus.
 */

/** Ressources affichées par cellule avant « + n autres ». */
export const CELL_MAX = 12;

/** Les environnements connus, dans l'ordre Sandbox → Prod. */
const ORDER: readonly { key: string; label: string; synonyms: readonly string[] }[] = [
  { key: 'sandbox', label: 'Sandbox', synonyms: ['sandbox', 'bac a sable', 'bac à sable', 'sbx', 'lab'] },
  { key: 'dev', label: 'Dev', synonyms: ['dev', 'development', 'developpement', 'développement'] },
  { key: 'recette', label: 'Recette / Test', synonyms: ['recette', 'test', 'tests', 'qa', 'uat', 'int', 'integration', 'intégration'] },
  { key: 'hors-prod', label: 'Hors-prod', synonyms: ['hors-prod', 'hors prod', 'horsprod', 'preprod', 'pre-prod', 'pré-prod', 'préprod', 'staging', 'hp', 'nonprod', 'non-prod'] },
  { key: 'prod', label: 'Prod', synonyms: ['prod', 'production', 'prd'] },
];

const UNSET = '—';

/** La clé d'environnement d'une valeur libre : synonymes rapprochés, sinon la valeur en minuscules. */
export function envKey(value: string | null | undefined): string {
  const text = (value ?? '').trim().toLowerCase();
  if (!text) {
    return UNSET;
  }
  for (const env of ORDER) {
    if (env.synonyms.includes(text)) {
      return env.key;
    }
  }
  return text;
}

export interface GridItem {
  node: MapNode;
  /** « accordé par X », si la carte le dit (relation `accorde`). */
  role: string | null;
}

export interface GridCell {
  items: GridItem[];
  more: number;
}

export interface GridColumn {
  key: string;
  label: string;
}

export interface GridRow {
  domain: string;
  cells: GridCell[];
}

export interface EnvironmentGrid {
  columns: GridColumn[];
  rows: GridRow[];
  empty: boolean;
}

/** La grille domaine × environnement des ressources qui portent l'un ou l'autre. */
export function environmentGrid(graph: MapGraph): EnvironmentGrid {
  const byId = new Map(graph.nodes.map((n) => [n.id, n] as const));
  const grantedBy = new Map<string, string[]>();
  for (const edge of graph.edges) {
    if (edge.nature === 'accorde') {
      const grantor = byId.get(edge.source);
      if (grantor) {
        grantedBy.set(edge.target, [...(grantedBy.get(edge.target) ?? []), grantor.label]);
      }
    }
  }

  const placed = graph.nodes.filter((n) => (n.environment ?? '').trim() || (n.domain ?? '').trim());
  if (placed.length === 0) {
    return { columns: [], rows: [], empty: true };
  }

  const envKeys = new Set(placed.map((n) => envKey(n.environment)));
  const columns: GridColumn[] = [];
  for (const env of ORDER) {
    if (envKeys.has(env.key)) {
      columns.push({ key: env.key, label: env.label });
    }
  }
  const known = new Set(ORDER.map((e) => e.key));
  [...envKeys]
    .filter((k) => !known.has(k) && k !== UNSET)
    .sort((a, b) => a.localeCompare(b))
    .forEach((k) => columns.push({ key: k, label: k }));
  if (envKeys.has(UNSET)) {
    columns.push({ key: UNSET, label: 'Non précisé' });
  }

  const domains = new Map<string, MapNode[]>();
  for (const node of placed) {
    const domain = (node.domain ?? '').trim() || 'Sans domaine';
    domains.set(domain, [...(domains.get(domain) ?? []), node]);
  }
  const domainNames = [...domains.keys()].sort((a, b) =>
    a === 'Sans domaine' ? 1 : b === 'Sans domaine' ? -1 : a.localeCompare(b));

  const rows: GridRow[] = domainNames.map((domain) => {
    const nodes = domains.get(domain) ?? [];
    return {
      domain,
      cells: columns.map((column) => {
        const inCell = nodes
          .filter((n) => envKey(n.environment) === column.key)
          .sort((a, b) => a.label.localeCompare(b.label));
        return {
          items: inCell.slice(0, CELL_MAX).map((node) => {
            const grantors = grantedBy.get(node.id);
            return { node, role: grantors?.length ? `accordé par ${grantors.join(', ')}` : null };
          }),
          more: Math.max(0, inCell.length - CELL_MAX),
        };
      }),
    };
  });
  return { columns, rows, empty: false };
}
