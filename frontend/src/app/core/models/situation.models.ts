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
}

export interface SituationExchange {
  request: string | null;
  requestAt: string | null;
  reply: string | null;
  replyAt: string | null;
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

/** Vrai si la carte s'ouvre d'elle-même à l'entrée : inactif depuis au moins le seuil (jamais si seuil 0). */
export function situationOpensOnEntry(situation: Pick<Situation, 'idleHours'> | null, threshold: number): boolean {
  return !!situation && threshold > 0 && situation.idleHours !== null && situation.idleHours >= threshold;
}
