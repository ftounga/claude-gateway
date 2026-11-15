import { normalizeName } from '../../scenes/jarvis-name-matcher';
import { ArchNav, ArchOp, ArchRef, ArchRequest, trivialArchOp } from '../../pieces/jarvis-piece-arch';
import { ArchResolution } from '../../pieces/jarvis-piece-store';
import { ArchLayout } from './architecture-layout-3d';
import { ArchitectureNarrator } from './architecture-narration';

/**
 * F-238 / SF-238-10 — **résoudre une cible d'architecture** (cadrage §7, §12) : le texte dit au toucher, à la voix ou
 * par l'outil `afficher` (« la base », « le VPC », « RDS primaire », « rdsa ») ⇒ l'élément du graphe. Fichier
 * <b>pur</b>, aucun réseau : id exact, libellé, puis le rapprochement prudent de la narration (types, familles,
 * zones). Ambigu ⇒ la question (« il y en a deux : … »), jamais un choix deviné.
 */

const ENTRY = /^(?:l |le |la )?(?:entree|entrees|flux entrant depuis l exterieur|trafic entrant|depuis internet|internet|l exterieur)$/;
const OUT = /^(?:le |les )?(?:flux )?(?:sortant|sortants|sortie)$/;
const IN = /^(?:le |les )?(?:flux )?(?:entrant|entrants)$/;

/** Familles nommables quand plusieurs services y répondent (pour poser la question plutôt que rien). */
const FAMILY_WORDS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\bbases?\b|\bbase de donnees\b/, 'database'],
  [/\bstockage\b/, 'storage'],
];

export class ArchitectureTargets {
  private readonly narrator: ArchitectureNarrator;

  constructor(private readonly layout: ArchLayout) {
    this.narrator = new ArchitectureNarrator(layout);
  }

  /** La demande résolue, ou le refus et sa phrase. */
  resolve(request: ArchRequest, arch: ArchNav): ArchResolution {
    const trivial = trivialArchOp(request);
    if (trivial) {
      return { op: trivial };
    }
    switch (request.kind) {
      case 'focus': {
        const found = this.find(request.target, arch.focus?.id ?? null);
        return 'ref' in found ? { op: { kind: 'focus', ref: found.ref } } : found;
      }
      case 'fold': {
        if (request.target.trim() === '') {
          return { op: { kind: 'fold', refs: this.leafZones() } };
        }
        const zone = this.zoneOf(request.target, arch);
        return 'ref' in zone ? { op: { kind: 'fold', refs: [zone.ref] } } : zone;
      }
      case 'unfold': {
        const zone = this.zoneOf(request.target, arch);
        return 'ref' in zone ? { op: { kind: 'unfold', refs: [zone.ref] } } : zone;
      }
      case 'flow':
        return this.flow(request.target, arch);
      default:
        return { refusal: 'NOT_FOUND' };
    }
  }

  /** « replie tout » : les zones qui portent directement des services (le schéma garde son emprise). */
  leafZones(): ArchRef[] {
    const holding = new Set(this.layout.nodes.map((n) => n.plate).filter((p) => p >= 0));
    return this.layout.plates.filter((p) => holding.has(p.index)).map((p) => ({ id: p.id, label: p.label }));
  }

  private ref(id: string): ArchRef | null {
    const node = this.layout.nodeById.get(id);
    if (node !== undefined) {
      return { id, label: this.layout.nodes[node].label };
    }
    const plate = this.layout.plateById.get(id);
    return plate !== undefined ? { id, label: this.layout.plates[plate].label } : null;
  }

  private ambiguous(ids: readonly string[]): ArchResolution {
    const labels = ids.map((id) => this.ref(id)?.label).filter((l): l is string => !!l).slice(0, 4);
    const list = labels.length > 1 ? `${labels.slice(0, -1).join(', ')} et ${labels[labels.length - 1]}` : labels[0];
    return { refusal: 'AMBIGUOUS', detail: `Il y en a ${labels.length > 2 ? 'plusieurs' : 'deux'} : ${list}. Lequel ?` };
  }

  /** Un service ou une zone, par id, libellé, puis rapprochement prudent. */
  private find(target: string, focus: string | null): { ref: ArchRef } | Exclude<ArchResolution, { op: ArchOp }> {
    const raw = target.trim();
    const exact = this.ref(raw);
    if (exact) {
      return { ref: exact };
    }
    const wanted = normalizeName(raw);
    if (!wanted) {
      return { refusal: 'NOT_FOUND' };
    }
    const byLabel = [...this.layout.nodes.filter((n) => normalizeName(n.label) === wanted).map((n) => n.id),
      ...this.layout.plates.filter((p) => normalizeName(p.label) === wanted).map((p) => p.id)];
    if (byLabel.length === 1) {
      return { ref: this.ref(byLabel[0]) as ArchRef };
    }
    if (byLabel.length > 1) {
      return this.ambiguous(byLabel) as Exclude<ArchResolution, { op: ArchOp }>;
    }
    const read = this.narrator.read(raw, null);
    if (read.lit.length === 1 && read.flow.length === 0) {
      return { ref: this.ref(read.lit[0]) as ArchRef };
    }
    if (read.lit.length > 1) {
      return this.ambiguous(read.lit) as Exclude<ArchResolution, { op: ArchOp }>;
    }
    // Une famille citée qui désigne plusieurs services : la question, pas un choix.
    for (const [words, family] of FAMILY_WORDS) {
      if (words.test(wanted)) {
        const of = this.layout.nodes.filter((n) => n.family === family).map((n) => n.id);
        if (of.length === 1) {
          return { ref: this.ref(of[0]) as ArchRef };
        }
        if (of.length > 1) {
          return this.ambiguous(of) as Exclude<ArchResolution, { op: ArchOp }>;
        }
      }
    }
    // Un libellé partiel porté par un seul élément (« la primaire » ⇒ « RDS primaire »).
    const partial = [...this.layout.nodes, ...this.layout.plates]
      .filter((e) => wanted.length >= 4 && normalizeName(e.label).split(' ').includes(wanted.split(' ').pop() as string))
      .map((e) => e.id);
    if (partial.length === 1) {
      return { ref: this.ref(partial[0]) as ArchRef };
    }
    void focus;
    return { refusal: 'NOT_FOUND' };
  }

  /** La zone d'une cible : la zone nommée, ou celle qui porte le service nommé. */
  private zoneOf(target: string, arch: ArchNav): { ref: ArchRef } | Exclude<ArchResolution, { op: ArchOp }> {
    const found = this.find(target, arch.focus?.id ?? null);
    if (!('ref' in found)) {
      return found;
    }
    const node = this.layout.nodeById.get(found.ref.id);
    if (node === undefined) {
      return found;
    }
    const plate = this.layout.nodes[node].plate;
    return plate < 0 ? { refusal: 'NOT_FOUND' } : { ref: { id: this.layout.plates[plate].id,
      label: this.layout.plates[plate].label } };
  }

  /** Un flux : « entrée », « sortant » / « entrant » (du focus), deux services, ou les sorties d'un service. */
  private flow(target: string, arch: ArchNav): ArchResolution {
    const wanted = normalizeName(target);
    const { nodes, edges } = this.layout;
    if (wanted === '' || ENTRY.test(wanted)) {
      const ground = edges.filter((e) => nodes[e.from].plate < 0 && nodes[e.to].plate >= 0);
      const entry = ground.length ? ground : edges.filter((e) => nodes[e.from].flowRank === 0);
      return entry.length ? { op: { kind: 'flow', edges: entry.map((e) => e.id), label: 'entrée' } }
        : { refusal: 'NOT_FOUND' };
    }
    const focus = arch.focus ? this.layout.nodeById.get(arch.focus.id) : undefined;
    if ((OUT.test(wanted) || IN.test(wanted)) && focus !== undefined) {
      const out = OUT.test(wanted);
      const of = edges.filter((e) => (out ? e.from : e.to) === focus).map((e) => e.id);
      return of.length ? { op: { kind: 'flow', edges: of, label: `${out ? 'sortant de' : 'entrant dans'} ${nodes[focus].label}` } }
        : { refusal: 'NOT_FOUND' };
    }
    const read = this.narrator.read(target, arch.focus?.id ?? null);
    if (read.flow.length > 0) {
      return { op: { kind: 'flow', edges: read.flow, label: target.trim().slice(0, 60) } };
    }
    const found = this.find(target, arch.focus?.id ?? null);
    if (!('ref' in found)) {
      return found;
    }
    const node = this.layout.nodeById.get(found.ref.id);
    const of = node === undefined ? [] : edges.filter((e) => e.from === node).map((e) => e.id);
    return of.length ? { op: { kind: 'flow', edges: of, label: `sortant de ${found.ref.label}` } }
      : { refusal: 'NOT_FOUND' };
  }
}
