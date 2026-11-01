/** Le point de situation d'un terminal (F-225), tel que la passerelle le compose — aucun appel modèle. */
export interface Situation {
  generatedAt: string;
  lastActivityAt: string | null;
  idleHours: number | null;
  mode: string;
  chantiers: SituationChantier[];
  attentes: SituationAttente[];
  envois: SituationEnvoi[];
  exchange: SituationExchange | null;
  pieces: SituationPiece[];
  suites: SituationSuite[];
  /** Le dernier message ÉCRIT par l'utilisateur (demandes automatiques écartées), ou `null` (SF-225-03). */
  lastUserMessageAt?: string | null;
  /** Un tour tourne dans le fil que l'écran principal montre : pas d'ouverture d'elle-même (SF-225-03). */
  turnLive?: boolean;
}

export interface SituationChantier {
  number: number;
  title: string | null;
  phaseLabel: string | null;
  focus: boolean;
  done: number;
  total: number;
  nextRank: number | null;
  nextStep: string | null;
  planAwaitingValidation: boolean;
  diagnosisPending: boolean;
  who: string | null;
  whoDetail: string | null;
  stale: boolean;
}

export interface SituationAttente {
  id: string;
  description: string;
  status: string;
  qui: string | null;
  to: string | null;
  since: string | null;
  chantierNumber: number | null;
}

export interface SituationEnvoi {
  messageId: string;
  blockIndex: number;
  kind: 'MAIL' | 'MESSAGE';
  subject: string | null;
  recipient: string | null;
  proposedAt: string | null;
  /** `null` : pas marqué envoyé. */
  sentAt: string | null;
  attenteKey: string | null;
  attenteStatus: string | null;
  excerpt: string | null;
  /** Le fil (chantier) du message qui l'a proposé, `null` = fil commun (SF-225-03). */
  chantierNumber?: number | null;
  /** Préparé dans un tour né à la voix, dans l'écran Jarvis (F-231 / SF-231-06). */
  viaJarvis?: boolean;
}

export interface SituationExchange {
  request: string | null;
  requestAt: string | null;
  reply: string | null;
  replyAt: string | null;
  /** La demande a été dite à la voix, dans l'écran Jarvis (F-231 / SF-231-06). */
  viaJarvis?: boolean;
}

export interface SituationPiece {
  id: string;
  path: string;
  sizeBytes: number;
  createdAt: string | null;
  messageId: string | null;
}

export interface SituationSuite {
  title: string;
  request: string | null;
}

/** Le seuil d'ouverture automatique, en heures d'inactivité ; `0` = jamais (réglage par appareil). */
export const SITUATION_IDLE_KEY = 'cg.situation.idleHours';
export const SITUATION_IDLE_DEFAULT = 4;
export const SITUATION_IDLE_CHOICES: readonly { hours: number; label: string }[] = [
  { hours: 2, label: '2 h' },
  { hours: 4, label: '4 h' },
  { hours: 8, label: '8 h' },
  { hours: 24, label: '24 h' },
  { hours: 0, label: 'jamais' },
];

/** Le seuil réglé sur cet appareil (défaut 4 h) ; un stockage indisponible rend le défaut. */
export function situationIdleThreshold(): number {
  try {
    const raw = localStorage.getItem(SITUATION_IDLE_KEY);
    const value = raw === null ? SITUATION_IDLE_DEFAULT : Number(raw);
    return Number.isFinite(value) && value >= 0 ? value : SITUATION_IDLE_DEFAULT;
  } catch {
    return SITUATION_IDLE_DEFAULT;
  }
}

/** La dernière visite de chaque terminal SUR CET APPAREIL (SF-225-03) : `{ [workspaceId]: ISO }`. */
export const SITUATION_VISITS_KEY = 'cg.situation.visits';
/** Au plus autant de terminaux retenus ; les plus anciennes visites partent d'abord. */
const SITUATION_VISITS_MAX = 100;

function readVisits(): Record<string, string> {
  try {
    const raw = localStorage.getItem(SITUATION_VISITS_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, string> : {};
  } catch {
    return {};
  }
}

/** La dernière visite de ce terminal sur cet appareil, ou `null` (jamais venu, ou stockage indisponible). */
export function readLastVisit(workspaceId: string): string | null {
  const value = readVisits()[workspaceId];
  return typeof value === 'string' && !isNaN(Date.parse(value)) ? value : null;
}

/** Note la visite de ce terminal sur cet appareil (à l'entrée, en le quittant, en masquant l'onglet). */
export function recordVisit(workspaceId: string, at: Date = new Date()): void {
  if (!workspaceId) {
    return;
  }
  const visits = readVisits();
  visits[workspaceId] = at.toISOString();
  const entries = Object.entries(visits);
  const kept = entries.length <= SITUATION_VISITS_MAX ? entries
    : entries.sort((a, b) => Date.parse(b[1]) - Date.parse(a[1])).slice(0, SITUATION_VISITS_MAX);
  try {
    localStorage.setItem(SITUATION_VISITS_KEY, JSON.stringify(Object.fromEntries(kept)));
  } catch {
    // stockage indisponible : l'inactivité ne repose plus que sur le dernier message écrit
  }
}

/**
 * Heures d'inactivité (SF-225-03) : depuis le plus récent de **votre dernier message** et de **votre dernière
 * visite sur cet appareil** — une course autonome qui a répondu la nuit ne remet rien à zéro, un aller-retour sans
 * écrire non plus. `null` quand rien n'est connu. Un serveur d'avant SF-225-03 : son `idleHours`.
 */
export function situationIdleHours(situation: Pick<Situation, 'idleHours'> & Partial<Pick<Situation, 'lastUserMessageAt'>>,
    lastVisit: string | null, now: number = Date.now()): number | null {
  if (situation.lastUserMessageAt === undefined && lastVisit === null) {
    return situation.idleHours;
  }
  const marks = [situation.lastUserMessageAt, lastVisit]
    .map((iso) => (iso ? Date.parse(iso) : NaN))
    .filter((ms) => !isNaN(ms));
  if (marks.length === 0) {
    return null;
  }
  return Math.max(0, Math.floor((now - Math.max(...marks)) / 3_600_000));
}

/**
 * Vrai si la carte s'ouvre d'elle-même à l'entrée : inactif depuis au moins le seuil (jamais si seuil 0), et aucun
 * tour ne tourne dans le fil montré (SF-225-03).
 */
export function situationOpensOnEntry(
    situation: (Pick<Situation, 'idleHours'> & Partial<Pick<Situation, 'lastUserMessageAt' | 'turnLive'>>) | null,
    threshold: number, lastVisit: string | null = null, now: number = Date.now()): boolean {
  if (!situation || threshold <= 0 || situation.turnLive === true) {
    return false;
  }
  const idle = situationIdleHours(situation, lastVisit, now);
  return idle !== null && idle >= threshold;
}

/** Un mail ou un message tel que la carte le montre : ses propositions répétées regroupées (SF-225-03). */
export interface SituationEnvoiGroup {
  key: string;
  /** La proposition la plus récente : [Voir] la montre, [Marquer envoyé] la marque. */
  latest: SituationEnvoi;
  /** La proposition marquée envoyée la plus récente, ou `null` : le groupe est « pas marqué envoyé ». */
  sent: SituationEnvoi | null;
  /** Combien de fois l'agent l'a proposé ces 7 jours. */
  count: number;
  /** Les fils (chantiers) où il a été proposé — `null` = fil commun. */
  chantiers: (number | null)[];
}

function normalized(value: string | null | undefined): string {
  return (value ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
}

/**
 * Regroupe les propositions d'un même mail ou message — même **type**, même **destinataire**, même **objet**
 * (SF-225-03) : l'agent qui repropose trois fois le mail à Zahi ne fait plus trois lignes. Un encart sans
 * destinataire ni objet reste seul. Le groupe est « envoyé » dès qu'une de ses propositions est marquée envoyée.
 * L'entrée arrive la plus récente d'abord ; l'ordre des groupes suit leur proposition la plus récente.
 */
export function groupEnvois(envois: readonly SituationEnvoi[]): SituationEnvoiGroup[] {
  const groups = new Map<string, SituationEnvoiGroup>();
  for (const e of envois) {
    const recipient = normalized(e.recipient);
    const subject = normalized(e.subject);
    const key = recipient || subject ? `${e.kind}|${recipient}|${subject}` : `${e.kind}|${e.messageId}#${e.blockIndex}`;
    const lane = e.chantierNumber ?? null;
    const group = groups.get(key);
    if (!group) {
      groups.set(key, { key, latest: e, sent: e.sentAt ? e : null, count: 1, chantiers: [lane] });
      continue;
    }
    group.count += 1;
    if (!group.sent && e.sentAt) {
      group.sent = e;
    }
    if (!group.chantiers.includes(lane)) {
      group.chantiers.push(lane);
    }
  }
  return [...groups.values()];
}
