import { JarvisUniverse, NodeRef, clientKey } from './jarvis-universe.models';

/**
 * **Le rapprochement de noms** (F-234 / SF-234-02, D2) — reconnaît, dans ce qui s'écrit ou dans la cible d'une
 * action, un nom de l'univers (client, sujet, chantier, personne). **Prudent** : casse, accents et apostrophes
 * normalisés ; frontière de mot ; au moins 3 caractères ; mots courants exclus ; un nom qui désigne deux nœuds
 * n'allume rien. Un faux positif coûte une lueur, un faux négatif rien. Tout se fait dans le navigateur.
 */

/** Les mots trop courants pour désigner un nœud, même s'ils sont le nom d'un sujet. */
export const EXCLUDED_NAMES: ReadonlySet<string> = new Set([
  'web', 'api', 'test', 'tests', 'dev', 'prod', 'main', 'master', 'app', 'code', 'data', 'doc', 'docs', 'demo',
  'git', 'aws', 'run', 'build', 'mail', 'message', 'plan', 'etape', 'note', 'notes', 'page', 'projet', 'sujet',
  'chantier', 'client', 'poste', 'terminal', 'jira', 'les', 'des', 'une', 'pour', 'avec', 'dans', 'tout', 'tous',
  'oui', 'non', 'bien', 'fait', 'faire', 'vous', 'nous', 'monsieur', 'jarvis', 'perso', 'divers', 'autre',
]);

/** Le plus long nom reconnu, en mots. */
const MAX_WORDS = 6;

/** Normalise : minuscules, sans accents, apostrophes et ponctuation en espaces. */
export function normalizeName(text: string): string {
  return (text ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

/** Un nom est-il assez distinctif pour allumer un nœud ? */
export function isMatchable(normalized: string): boolean {
  return normalized.length >= 3 && !EXCLUDED_NAMES.has(normalized)
    && normalized.split(' ').length <= MAX_WORDS && !/^\d+$/.test(normalized);
}

/** L'index des noms d'un univers : nom normalisé → nœud (les noms ambigus sont retirés). */
export class NameIndex {
  private readonly byName = new Map<string, NodeRef>();
  private readonly chantiers = new Map<string, NodeRef>();
  private readonly nodes = new Map<string, NodeRef>();

  constructor(universe: JarvisUniverse | null) {
    if (!universe) {
      return;
    }
    const all = new Map<string, Map<string, NodeRef>>();
    const add = (name: string | null | undefined, node: NodeRef) => {
      const key = normalizeName(name ?? '');
      if (!isMatchable(key)) {
        return;
      }
      const nodes = all.get(key) ?? new Map<string, NodeRef>();
      nodes.set(node.key, node);
      all.set(key, nodes);
    };
    for (const client of universe.clients) {
      const ck = clientKey(client);
      const clientNode: NodeRef = { kind: 'client', key: ck, label: client.name, clientKey: ck };
      this.nodes.set(clientNode.key, clientNode);
      add(client.name, clientNode);
      for (const subject of client.subjects) {
        const subjectNode: NodeRef = { kind: 'subject', key: subject.id, label: subject.name, clientKey: ck };
        this.nodes.set(subjectNode.key, subjectNode);
        if (!subject.hostTerminal) {
          add(subject.name, subjectNode);
        }
        for (const chantier of subject.chantiers) {
          const node: NodeRef = {
            kind: 'chantier', key: `${subject.id}#${chantier.number}`,
            label: `Chantier ${chantier.number} — ${chantier.title}`, clientKey: ck, subjectId: subject.id,
          };
          this.nodes.set(node.key, node);
          this.chantiers.set(node.key, node);
          add(chantier.title, node);
        }
      }
      for (const person of client.people) {
        const node: NodeRef = { kind: 'person', key: `${ck}:${normalizeName(person.name)}`, label: person.name, clientKey: ck };
        this.nodes.set(node.key, node);
        add(person.name, node);
        for (const variant of person.variants) {
          add(variant, node);
        }
      }
    }
    for (const [key, nodes] of all) {
      if (nodes.size === 1) {
        this.byName.set(key, [...nodes.values()][0]);
      }
    }
  }

  /** Le nœud de clé `key` (id de sujet, clé de client…), s'il existe. */
  node(key: string | null | undefined): NodeRef | null {
    return key ? this.nodes.get(key) ?? null : null;
  }

  /** Le chantier `number` du sujet `subjectId`, s'il est ouvert. */
  chantier(subjectId: string | null | undefined, number: number): NodeRef | null {
    return subjectId ? this.chantiers.get(`${subjectId}#${number}`) ?? null : null;
  }

  /**
   * Les nœuds nommés dans `text`, dans l'ordre d'apparition, sans doublon. Le plus long nom gagne (« Migration Forge »
   * plutôt que « Forge »). « chantier N » désigne le chantier N du premier sujet de contexte qui l'a ouvert (le
   * dernier sujet cité, puis le sujet courant).
   */
  match(text: string, context?: string | null | (string | null)[]): NodeRef[] {
    const subjects = (Array.isArray(context) ? context : [context]).filter((s): s is string => !!s);
    const normalized = normalizeName(text);
    if (!normalized) {
      return [];
    }
    const words = normalized.split(' ');
    const found: { at: number; node: NodeRef }[] = [];
    const used = new Array<boolean>(words.length).fill(false);
    for (let size = Math.min(MAX_WORDS, words.length); size >= 1; size--) {
      for (let i = 0; i + size <= words.length; i++) {
        if (used.slice(i, i + size).some(Boolean)) {
          continue;
        }
        const node = this.byName.get(words.slice(i, i + size).join(' '));
        if (node) {
          found.push({ at: i, node });
          used.fill(true, i, i + size);
        }
      }
    }
    for (let i = 0; i + 1 < words.length; i++) {
      if (words[i] === 'chantier' && /^\d{1,3}$/.test(words[i + 1])) {
        const number = Number(words[i + 1]);
        const node = subjects.map((s) => this.chantier(s, number)).find((n) => !!n) ?? null;
        if (node) {
          found.push({ at: i, node });
        }
      }
    }
    const seen = new Set<string>();
    return found
      .sort((a, b) => a.at - b.at)
      .map((f) => f.node)
      .filter((n) => (seen.has(n.key) ? false : (seen.add(n.key), true)));
  }
}
