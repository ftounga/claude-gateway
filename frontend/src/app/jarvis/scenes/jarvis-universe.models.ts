/**
 * **L'univers de Jarvis** (F-234 / SF-234-01, D4) — miroir de `GET /api/jarvis/universe`. Aucun contenu : des noms,
 * des numéros, des compteurs.
 */
export interface JarvisUniverse {
  generatedAt: string;
  clients: UniverseClient[];
  toSendCount: number;
}

export interface UniverseClient {
  /** `null` pour « Hébergé » (pas une machine). */
  id: string | null;
  name: string;
  virtual: boolean;
  connected: boolean;
  missionStatus: string | null;
  subjects: UniverseSubject[];
  people: UniversePerson[];
  toSend: string[];
  truncated: boolean;
}

export interface UniverseSubject {
  id: string;
  name: string;
  hostTerminal: boolean;
  live: boolean;
  chantiers: UniverseChantier[];
}

export interface UniverseChantier {
  number: number;
  title: string;
  phase: string | null;
  phaseLabel: string | null;
  done: number;
  total: number;
  step: { rank: number; title: string } | null;
  focus: boolean;
  live: boolean;
  waitingOn: string[];
}

export interface UniversePerson {
  name: string;
  variants: string[];
  role: string | null;
}

/** Un nœud de la constellation. */
export type NodeKind = 'client' | 'subject' | 'chantier' | 'person';

export interface NodeRef {
  kind: NodeKind;
  /** Clé stable : id du client (`hosted` pour « Hébergé »), id du sujet, `sujet#n`, `client:nom`. */
  key: string;
  label: string;
  /** Le client auquel le nœud appartient (clé du client). */
  clientKey: string;
  /** Le sujet auquel le nœud appartient (chantier), sinon absent. */
  subjectId?: string;
}

/** La clé d'un client : son id, ou `hosted`. */
export function clientKey(client: UniverseClient): string {
  return client.id ?? 'hosted';
}
