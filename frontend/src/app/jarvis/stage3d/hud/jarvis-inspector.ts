import { normalizeName } from '../../scenes/jarvis-name-matcher';
import { ShowFilter, resolveShow } from '../../scenes/jarvis-show';
import {
  JarvisUniverse, UniverseChantier, UniverseClient, UniverseSubject, clientKey,
} from '../../scenes/jarvis-universe.models';
import { Node3dKind } from '../jarvis-layout-3d';
import { peopleMap } from './jarvis-people-map';
import { WEATHER_WORD, weatherOf } from './jarvis-weather';

/**
 * F-236 / SF-236-19 — **l'inspecteur de nœud** (NF-7) et la **recherche « montre-moi »** (NF-8). Fichier <b>pur</b> :
 * la fiche d'un nœud est lue dans l'univers déjà chargé (rien d'autre) ; la recherche combine les filtres fermés de
 * « Montrer » (`jarvis-show.ts`) et les noms des nœuds. Aucun appel, aucun tour.
 */

export interface InspectorFact {
  label: string;
  value: string;
}

/** Un lien de la fiche vers un autre nœud (touché ⇒ sa fiche). */
export interface InspectorLink {
  key: string;
  kind: Node3dKind;
  label: string;
  note: string | null;
}

export interface InspectorCard {
  key: string;
  kind: Node3dKind;
  /** « Poste », « Sujet », « Chantier », « Personne », « Étape ». */
  type: string;
  title: string;
  facts: InspectorFact[];
  links: InspectorLink[];
  /** Un sujet se choisit (D12) : l'id à passer à `touchSubject`. */
  subjectId?: string;
}

interface Located {
  client: UniverseClient;
  subject?: UniverseSubject;
  chantier?: UniverseChantier;
}

function findChantier(universe: JarvisUniverse, key: string): Located | null {
  const [subjectId, number] = key.split('#');
  for (const client of universe.clients) {
    const subject = client.subjects.find((s) => s.id === subjectId);
    const chantier = subject?.chantiers.find((c) => String(c.number) === (number ?? '').split('/')[0]);
    if (subject && chantier) {
      return { client, subject, chantier };
    }
  }
  return null;
}

/** La fiche d'un nœud ; `null` si la clé n'est plus dans l'univers. */
export function inspect(universe: JarvisUniverse | null, key: string, kind: Node3dKind): InspectorCard | null {
  if (!universe) {
    return null;
  }
  switch (kind) {
    case 'client': {
      const c = universe.clients.find((x) => clientKey(x) === key);
      if (!c) {
        return null;
      }
      const subjects = c.subjects.filter((s) => !s.hostTerminal);
      return {
        key, kind, type: c.virtual ? 'Hébergé' : 'Poste', title: c.name,
        facts: [
          { label: 'Poste', value: c.virtual ? 'hébergé' : c.connected ? 'connecté' : 'déconnecté' },
          ...(c.missionStatus ? [{ label: 'Mission', value: c.missionStatus.toLowerCase() }] : []),
          { label: 'Sujets', value: `${subjects.length}${c.truncated ? '+' : ''}` },
          { label: 'Chantiers', value: `${c.subjects.reduce((n, s) => n + s.chantiers.length, 0)}` },
          { label: 'À envoyer', value: `${c.toSend.length}` },
        ],
        links: [
          ...subjects.map((s) => ({ key: s.id, kind: 'subject' as const, label: s.name, note: s.live ? 'au travail' : null })),
          ...peopleMap({ ...universe, clients: [c] }).map((p) => ({ key: p.key, kind: 'person' as const, label: p.name,
            note: p.role })),
        ],
      };
    }
    case 'subject': {
      const client = universe.clients.find((c) => c.subjects.some((s) => s.id === key));
      const s = client?.subjects.find((x) => x.id === key);
      if (!client || !s) {
        return null;
      }
      return {
        key, kind, type: 'Sujet', title: s.name, subjectId: s.id,
        facts: [
          { label: 'Poste', value: client.name },
          { label: 'Terminal', value: s.live ? 'au travail' : 'au repos' },
          { label: 'Chantiers', value: `${s.chantiers.length}` },
        ],
        links: s.chantiers.map((ch) => ({ key: `${s.id}#${ch.number}`, kind: 'chantier' as const,
          label: `Chantier ${ch.number} — ${ch.title}`, note: weatherOf(ch).note })),
      };
    }
    case 'chantier':
    case 'step': {
      const found = findChantier(universe, kind === 'step' ? key.split('/')[0] : key);
      if (!found?.chantier || !found.subject) {
        return null;
      }
      const ch = found.chantier;
      const w = weatherOf(ch);
      const chKey = `${found.subject.id}#${ch.number}`;
      return {
        key, kind, type: kind === 'step' ? 'Étape' : 'Chantier', title: `Chantier ${ch.number} — ${ch.title}`,
        facts: [
          { label: 'Sujet', value: `${found.client.name} · ${found.subject.name}` },
          ...(ch.phaseLabel ? [{ label: 'Phase', value: ch.phaseLabel }] : []),
          { label: 'Avancement', value: ch.total > 0 ? `${ch.done}/${ch.total}` : '—' },
          ...(ch.step ? [{ label: 'Étape en cours', value: `${ch.step.rank} — ${ch.step.title}` }] : []),
          { label: 'Météo', value: `${WEATHER_WORD[w.weather]} — ${w.note}` },
        ],
        links: [
          { key: found.subject.id, kind: 'subject', label: found.subject.name, note: null },
          ...ch.waitingOn.map((name) => ({ key: `${clientKey(found.client)}:${normalizeName(name)}`, kind: 'person' as const,
            label: name, note: 'attendue' })),
          ...(kind === 'step' ? [{ key: chKey, kind: 'chantier' as const, label: 'Le chantier', note: null }] : []),
        ],
      };
    }
    case 'person': {
      const row = peopleMap(universe).find((p) => p.key === key);
      if (!row) {
        return null;
      }
      return {
        key, kind, type: 'Personne', title: row.name,
        facts: [
          { label: 'Poste', value: row.clientName },
          ...(row.role ? [{ label: 'Rôle', value: row.role }] : []),
          { label: 'Attendue par', value: row.awaitedBy.length > 0 ? `${row.awaitedBy.length} chantier(s)` : 'aucun' },
          { label: 'Message prêt', value: row.message ? 'oui' : 'non' },
        ],
        links: row.awaitedBy.map((label) => ({ key: chantierKeyOf(universe, row.clientKey, label), kind: 'chantier' as const,
          label, note: null })).filter((l) => l.key !== ''),
      };
    }
  }
}

function chantierKeyOf(universe: JarvisUniverse, ck: string, label: string): string {
  const client = universe.clients.find((c) => clientKey(c) === ck);
  for (const s of client?.subjects ?? []) {
    for (const ch of s.chantiers) {
      if (`Chantier ${ch.number} — ${ch.title}` === label) {
        return `${s.id}#${ch.number}`;
      }
    }
  }
  return '';
}

/** Un résultat de recherche : un « Montrer » local, ou un nœud à regarder. */
export type SearchResult =
  | { type: 'show'; filter: ShowFilter; client: string | null; label: string; count: number }
  | { type: 'node'; key: string; kind: Node3dKind; label: string; where: string };

/** Les mots qui désignent un filtre de « Montrer » (normalisés). */
const FILTER_WORDS: readonly [RegExp, ShowFilter][] = [
  [/\b(bloque|bloques|bloquee|bloquees|attente|attentes|attend)\b/, 'chantiers-bloques'],
  [/\b(deconnecte|deconnectes|eteint|eteints|hors ligne)\b/, 'deconnectes'],
  [/\b(connecte|connectes|en ligne)\b/, 'connectes'],
  [/\b(envoyer|envoi|messages?)\b/, 'a-envoyer'],
  [/\b(etapes?|en cours)\b/, 'etapes-en-cours'],
  [/\b(clients?|postes?)\b/, 'clients'],
];

/** Les mots sans poids (« montre-moi les chantiers bloqués »). */
const STOP_WORDS: ReadonlySet<string> = new Set(['montre', 'montrer', 'moi', 'les', 'des', 'mes', 'du', 'de', 'la',
  'le', 'au', 'aux', 'en', 'et', 'chez', 'pour', 'sur', 'tous', 'toutes', 'qui', 'sont', 'chantiers']);

/** Au plus autant de résultats. */
export const MAX_RESULTS = 8;

/**
 * La recherche « montre-moi » : « edenred bloqué » ⇒ le filtre `chantiers-bloques` restreint à Edenred (résolu
 * exactement comme « Montrer »), puis les nœuds dont le nom contient les mots restants. Filtre sans résultat ⇒ omis.
 */
export function search(universe: JarvisUniverse | null, query: string): SearchResult[] {
  const q = normalizeName(query);
  if (!universe || q.length < 2) {
    return [];
  }
  const results: SearchResult[] = [];
  const filter = FILTER_WORDS.find(([re]) => re.test(q));
  const rest = (filter ? q.replace(filter[0], ' ') : q).split(' ')
    .filter((w) => w.length >= 2 && !STOP_WORDS.has(w)).join(' ');
  if (filter) {
    const client = rest ? universe.clients.find((c) => normalizeName(c.name).includes(rest)) ?? null : null;
    const focus = resolveShow(universe, { filter: filter[1], client: client ? normalizeName(client.name) : null });
    if (focus && focus.items.length > 0) {
      results.push({ type: 'show', filter: filter[1], client: client ? normalizeName(client.name) : null,
        label: client ? `${focus.title} · ${client.name}` : focus.title, count: focus.items.length + focus.more });
    }
  }
  const words = rest.split(' ').filter((w) => w.length > 0);
  if (words.length > 0) {
    const hit = (name: string) => {
      const n = normalizeName(name);
      return words.every((w) => n.includes(w));
    };
    for (const c of universe.clients) {
      const ck = clientKey(c);
      if (hit(c.name)) {
        results.push({ type: 'node', key: ck, kind: 'client', label: c.name, where: c.virtual ? 'hébergé' : 'poste' });
      }
      for (const s of c.subjects) {
        if (!s.hostTerminal && hit(s.name)) {
          results.push({ type: 'node', key: s.id, kind: 'subject', label: s.name, where: c.name });
        }
        for (const ch of s.chantiers) {
          if (hit(ch.title) || hit(`chantier ${ch.number}`)) {
            results.push({ type: 'node', key: `${s.id}#${ch.number}`, kind: 'chantier',
              label: `Chantier ${ch.number} — ${ch.title}`, where: `${c.name} · ${s.name}` });
          }
        }
      }
    }
    for (const p of peopleMap(universe)) {
      if (hit(p.name)) {
        results.push({ type: 'node', key: p.key, kind: 'person', label: p.name, where: p.clientName });
      }
    }
  }
  return results.slice(0, MAX_RESULTS);
}
