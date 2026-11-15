import { isMatchable, normalizeName } from '../../scenes/jarvis-name-matcher';
import { ArchLayout } from './architecture-layout-3d';

/**
 * F-238 / SF-238-12 — **la narration synchronisée** (cadrage §11) : quand Jarvis prononce une phrase, ce qu'elle
 * nomme s'allume. Fichier <b>pur</b>, <b>aucun appel réseau</b>, aucun coût : un rapprochement local et prudent sur
 * le graphe — noms, types et sigles (égalités seulement), familles (seulement si un seul service), flux (deux
 * services cités dans l'ordre et reliés), zones. Une lueur fausse coûte la confiance : dans le doute, rien.
 */

/** Au plus trois groupes allumés (règle F-234 D2) ; un chemin ou un type = un groupe. */
export const NARRATION_MAX_GROUPS = 3;
/** Un flux se cherche sur au plus quatre liaisons. */
export const FLOW_MAX_HOPS = 4;
/** Au-delà, la phrase est tronquée (une phrase dite fait rarement plus de 300 caractères). */
export const NARRATION_MAX_CHARS = 1000;

export interface NarrationResult {
  /** Services et zones allumés (ids du graphe). */
  lit: string[];
  /** Liaisons des flux allumés (T-A7). */
  flow: string[];
  /** Groupes retenus (≤ {@link NARRATION_MAX_GROUPS}). */
  groups: number;
}

/**
 * Sigles et noms parlés ⇒ clés de service (dernier segment du `type` ou du chemin d'icône, normalisé sans espace).
 * Table d'alias F-142 (`cloud.py`, `ALIASES`) reprise côté front : des <b>égalités</b>, jamais des ressemblances.
 */
const SPOKEN_TYPES: ReadonlyArray<readonly [readonly string[], readonly string[]]> = [
  [['alb'], ['alb', 'elbapplicationloadbalancer']],
  [['nlb'], ['nlb', 'elbnetworkloadbalancer']],
  [['load balancer', 'repartiteur de charge', 'equilibreur de charge'],
    ['alb', 'nlb', 'clb', 'elb', 'elbapplicationloadbalancer', 'elbnetworkloadbalancer', 'elbclassicloadbalancer',
      'elasticloadbalancing']],
  [['nat', 'passerelle nat', 'nat gateway'], ['natgateway', 'natgw']],
  [['instance', 'instances', 'ec2'], ['ec2']],
  [['rds'], ['rds']],
  [['aurora'], ['aurora']],
  [['lambda', 'lambdas', 'fonction lambda'], ['lambda']],
  [['s3', 'bucket', 'bucket s3'], ['s3', 'simplestorageservices3']],
  [['route 53', 'route53', 'dns'], ['route53', 'r53']],
  [['cloudfront'], ['cloudfront']],
  [['api gateway'], ['apigateway', 'apigw']],
  [['dynamodb'], ['dynamodb', 'ddb']],
  [['sqs'], ['sqs', 'simplequeueservicesqs']],
  [['sns'], ['sns', 'simplenotificationservicesns']],
  [['elasticache', 'redis'], ['elasticache']],
  [['cloudwatch'], ['cloudwatch', 'cw']],
  [['eks', 'kubernetes'], ['eks', 'elastickubernetesservice']],
  [['ecs'], ['ecs', 'elasticcontainerservice']],
  [['fargate'], ['fargate']],
  [['kinesis'], ['kinesis']],
  [['utilisateurs', 'les utilisateurs'], ['users', 'user']],
];

/** Familles (« la base ») : un service de la famille dans le focus, sinon rien. */
const SPOKEN_FAMILIES: ReadonlyArray<readonly [readonly string[], (n: { family: string; keys: string[] }) => boolean]> = [
  [['la base', 'base de donnees', 'la base de donnees'], (n) => n.family === 'database'],
  [['le cache'], (n) => n.keys.includes('elasticache')],
  [['la file', 'file d attente'], (n) => n.keys.some((k) => k === 'sqs' || k === 'simplequeueservicesqs')],
  [['le stockage'], (n) => n.family === 'storage'],
];

/** Natures de zone nommables quand la zone est seule de sa nature. */
const SPOKEN_KINDS: Readonly<Record<string, readonly string[]>> = {
  vpc: ['vpc'],
  region: ['region'],
  account: ['compte'],
  onprem: ['centre de donnees', 'datacenter', 'on premise'],
  subnet_public: ['sous reseau public', 'subnet public'],
  subnet_private: ['sous reseau prive', 'subnet prive'],
  security_group: ['security group', 'groupe de securite'],
  asg: ['auto scaling', 'groupe d auto scaling'],
};

/** Toutes les AZ à la fois. */
const ALL_ZONES = ['les deux zones', 'deux zones', 'les trois zones', 'trois zones', 'les zones', 'les az'];

const ENTRY_WORDS = /(^| )(entre|entrent|arrive|arrivent)( |$)/;
const OUT_WORDS = /(^| )(sortant|sortants|sortante|sortantes)( |$)/;
const IN_WORDS = /(^| )(entrant|entrants|entrante|entrantes|d ou vient)( |$)/;

type TermKind = 'node' | 'zone' | 'type' | 'family' | 'zones';

interface Term {
  text: string;
  kind: TermKind;
  /** Services (node, type, family) ou zones (zone, zones) désignés. */
  ids: string[];
  /** Famille : le filtre à appliquer dans le focus. */
  family?: (n: { family: string; keys: string[] }) => boolean;
}

interface Mention {
  start: number;
  end: number;
  term: Term;
}

function key(text: string): string {
  return normalizeName(text).replace(/ /g, '');
}

export class ArchitectureNarrator {
  private readonly terms: Term[];
  private readonly keysOf: Map<string, string[]>;
  /** Liaisons sortantes par service (indices de liaisons). */
  private readonly out: Map<string, number[]>;

  constructor(private readonly layout: ArchLayout) {
    const { nodes, plates, edges } = layout;
    this.keysOf = new Map(nodes.map((n) => [n.id, [key(n.type.split('.').slice(1).join('.') || n.type),
      key((n.resolved ?? '').split('/').pop() ?? '')].filter((k) => k.length > 0)]));
    this.out = new Map(nodes.map((n) => [n.id, [] as number[]]));
    edges.forEach((e, i) => this.out.get(nodes[e.from].id)?.push(i));

    const terms: Term[] = [];
    const byText = new Map<string, Term | null>();
    const add = (text: string, kind: TermKind, ids: string[], family?: Term['family']): void => {
      const t = normalizeName(text);
      if (!t || ids.length === 0 && !family) {
        return;
      }
      const prior = byText.get(t);
      if (prior === undefined) {
        const term = { text: t, kind, ids, family };
        byText.set(t, term);
        terms.push(term);
      } else if (prior && (prior.kind === 'node' || prior.kind === 'zone') && (kind === 'node' || kind === 'zone')
          && prior.ids.join() !== ids.join()) {
        // Un même libellé pour deux éléments : ambigu ⇒ rien (le nom ne désigne plus personne).
        prior.ids = [];
      }
    };
    // 1. Noms : libellés des services et des zones.
    for (const n of nodes) {
      if (isMatchable(normalizeName(n.label))) {
        add(n.label, 'node', [n.id]);
      }
    }
    for (const p of plates) {
      if (isMatchable(normalizeName(p.label))) {
        add(p.label, 'zone', [p.id]);
      }
      if (p.kind === 'az') {
        const letter = /([a-z0-9])$/.exec(normalizeName(p.label))?.[1];
        if (letter) {
          add(`zone ${letter}`, 'zone', [p.id]);
          add(`az ${letter}`, 'zone', [p.id]);
        }
      }
    }
    // 5. Zones nommées par leur nature, si elles sont seules de leur nature ; toutes les AZ.
    for (const [kind, words] of Object.entries(SPOKEN_KINDS)) {
      const of = plates.filter((p) => p.kind === kind);
      if (of.length === 1) {
        words.forEach((w) => add(w, 'zone', [of[0].id]));
      }
    }
    const azs = plates.filter((p) => p.kind === 'az').map((p) => p.id);
    if (azs.length >= 2) {
      ALL_ZONES.forEach((w) => add(w, 'zones', azs));
    }
    // 2. Types et sigles.
    for (const [words, keys] of SPOKEN_TYPES) {
      const ids = nodes.filter((n) => (this.keysOf.get(n.id) ?? []).some((k) => keys.includes(k))).map((n) => n.id);
      if (ids.length > 0) {
        words.forEach((w) => add(w, 'type', ids));
      }
    }
    // 3. Familles (résolues à la lecture, dans le focus).
    for (const [words, family] of SPOKEN_FAMILIES) {
      words.forEach((w) => add(w, 'family', [], family));
    }
    this.terms = terms.filter((t) => t.ids.length > 0 || t.family).sort((a, b) => b.text.length - a.text.length);
  }

  /** Ce qu'une phrase allume, dans le focus courant (id de service ou de zone, sinon tout le graphe). */
  read(phrase: string, focus: string | null = null): NarrationResult {
    const text = ` ${normalizeName((phrase ?? '').slice(0, NARRATION_MAX_CHARS))} `;
    const mentions = this.mentions(text, focus);
    const nodeMentions = mentions.filter((m) => m.term.kind !== 'zone' && m.term.kind !== 'zones');

    // 4. Flux.
    const flows: string[][] = [];
    const usedPairs = new Set<number>();
    if (ENTRY_WORDS.test(text) && nodeMentions.length > 0) {
      const path = this.pathFromOutside(nodeMentions[0].term.ids);
      if (path.length > 0) {
        flows.push(path);
      }
    }
    const subject = focus !== null && this.layout.nodeById.has(focus) ? [focus] : nodeMentions[0]?.term.ids ?? [];
    if (OUT_WORDS.test(text) && subject.length > 0) {
      flows.push(subject.flatMap((id) => (this.out.get(id) ?? []).map((i) => this.layout.edges[i].id)));
    } else if (IN_WORDS.test(text) && subject.length > 0) {
      const index = new Set(subject.map((id) => this.layout.nodeById.get(id)));
      flows.push(this.layout.edges.filter((e) => index.has(e.to)).map((e) => e.id));
    }
    for (let i = 0; i + 1 < nodeMentions.length; i++) {
      const a = nodeMentions[i].term.ids;
      const b = nodeMentions[i + 1].term.ids;
      if (a.join() === b.join()) {
        continue;
      }
      const path = this.paths(a, b);
      if (path.length > 0) {
        flows.push(path);
        usedPairs.add(i);
      }
    }

    // Priorité flux > service > zone ; ≤ 3 groupes ; un même groupe cité deux fois compte une fois.
    const groups: { kind: 'flow' | 'lit'; ids: string[] }[] = [];
    const seen = new Set<string>();
    const push = (kind: 'flow' | 'lit', ids: string[]): void => {
      const sig = `${kind}:${[...ids].sort().join()}`;
      if (ids.length > 0 && !seen.has(sig) && groups.length < NARRATION_MAX_GROUPS) {
        seen.add(sig);
        groups.push({ kind, ids });
      }
    };
    flows.forEach((f) => push('flow', f));
    nodeMentions.forEach((m) => push('lit', m.term.ids));
    mentions.filter((m) => m.term.kind === 'zone' || m.term.kind === 'zones').forEach((m) => push('lit', m.term.ids));
    const lit = [...new Set(groups.filter((g) => g.kind === 'lit').flatMap((g) => g.ids))];
    const flow = [...new Set(groups.filter((g) => g.kind === 'flow').flatMap((g) => g.ids))];
    return { lit, flow, groups: groups.length };
  }

  /** Les termes reconnus, dans l'ordre de la phrase, sans recouvrement (le plus long d'abord). */
  private mentions(text: string, focus: string | null): Mention[] {
    const taken: [number, number][] = [];
    const found: Mention[] = [];
    for (const term of this.terms) {
      const needle = ` ${term.text} `;
      for (let from = 0; ;) {
        const at = text.indexOf(needle, from);
        if (at < 0) {
          break;
        }
        const start = at + 1;
        const end = start + term.text.length;
        from = at + 1;
        if (taken.some(([s, e]) => start < e && s < end)) {
          continue;
        }
        const resolved = term.family ? this.familyIn(term.family, focus) : term;
        if (!resolved) {
          taken.push([start, end]); // famille ambiguë : le mot est lu, mais n'allume rien
          continue;
        }
        taken.push([start, end]);
        found.push({ start, end, term: resolved });
      }
    }
    return found.sort((a, b) => a.start - b.start);
  }

  /** Une famille n'allume que si un seul de ses services est dans le focus. */
  private familyIn(family: NonNullable<Term['family']>, focus: string | null): Term | null {
    const scope = this.scope(focus);
    const hits = this.layout.nodes.filter((n) => scope.has(n.id)
      && family({ family: n.family, keys: this.keysOf.get(n.id) ?? [] }));
    return hits.length === 1 ? { text: '', kind: 'node', ids: [hits[0].id] } : null;
  }

  /** Les services du focus : un service et ses voisins, une zone et ses descendants, sinon tout. */
  private scope(focus: string | null): Set<string> {
    const { nodes, edges, plates, nodeById, plateById } = this.layout;
    const all = new Set(nodes.map((n) => n.id));
    if (focus === null) {
      return all;
    }
    const node = nodeById.get(focus);
    if (node !== undefined) {
      const ids = new Set([focus]);
      edges.forEach((e) => {
        if (e.from === node || e.to === node) {
          ids.add(nodes[e.from].id);
          ids.add(nodes[e.to].id);
        }
      });
      return ids;
    }
    const plate = plateById.get(focus);
    if (plate === undefined) {
      return all;
    }
    const under = (p: number): boolean => {
      for (let q = p, g = 0; q >= 0 && g < 64; q = plates[q].parent, g++) {
        if (q === plate) {
          return true;
        }
      }
      return false;
    };
    return new Set(nodes.filter((n) => under(n.plate)).map((n) => n.id));
  }

  /** Plus court chemin orienté (≤ 4 liaisons) de chaque source vers l'une des cibles ; union des liaisons. */
  private paths(from: string[], to: string[]): string[] {
    const targets = new Set(to);
    const out: string[] = [];
    for (const start of from) {
      const path = this.shortest(start, (id) => targets.has(id));
      out.push(...path);
    }
    return [...new Set(out)];
  }

  /** « Entre par X » : depuis l'acteur externe (au sol, sans liaison entrante), le premier chemin le plus court. */
  private pathFromOutside(to: string[]): string[] {
    const { nodes, edges } = this.layout;
    const incoming = new Set(edges.map((e) => e.to));
    const sources = nodes.filter((n) => n.plate < 0 && !incoming.has(n.index) && !to.includes(n.id));
    const targets = new Set(to);
    let best: string[] = [];
    for (const s of sources) {
      const path = this.shortest(s.id, (id) => targets.has(id));
      if (path.length > 0 && (best.length === 0 || path.length < best.length)) {
        best = path;
      }
    }
    return best;
  }

  private shortest(start: string, isTarget: (id: string) => boolean): string[] {
    const { edges, nodes } = this.layout;
    const back = new Map<string, number>();
    let frontier = [start];
    const seen = new Set([start]);
    for (let hop = 0; hop < FLOW_MAX_HOPS && frontier.length > 0; hop++) {
      const next: string[] = [];
      for (const id of frontier) {
        for (const i of this.out.get(id) ?? []) {
          const to = nodes[edges[i].to].id;
          if (seen.has(to)) {
            continue;
          }
          seen.add(to);
          back.set(to, i);
          if (isTarget(to)) {
            const path: string[] = [];
            for (let cur = to; cur !== start;) {
              const e = back.get(cur) as number;
              path.unshift(edges[e].id);
              cur = nodes[edges[e].from].id;
            }
            return path;
          }
          next.push(to);
        }
      }
      frontier = next;
    }
    return [];
  }
}
