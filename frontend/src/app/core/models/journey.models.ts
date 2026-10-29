import { UnblockerMark } from './client-board.models';

/**
 * **Le parcours du sujet d'un terminal** (F-176) : Libre par défaut, ou Guidé —
 * Investigation → Plan → Exécution → Vérification → Clos.
 */
export type JourneyMode = 'LIBRE' | 'GUIDE';

export type JourneyPhase = 'INVESTIGATION' | 'PLAN' | 'EXECUTION' | 'VERIFICATION' | 'CLOS';

/** Les phases dans l'ordre, avec leur libellé. */
export const JOURNEY_PHASES: ReadonlyArray<{ phase: JourneyPhase; label: string }> = [
  { phase: 'INVESTIGATION', label: 'Investigation' },
  { phase: 'PLAN', label: 'Plan' },
  { phase: 'EXECUTION', label: 'Exécution' },
  { phase: 'VERIFICATION', label: 'Vérification' },
  { phase: 'CLOS', label: 'Clos' },
];

/** Le parcours tel que la gateway le rend (GET /workspaces/{id}/journey). */
export interface SubjectJourney {
  mode: JourneyMode;
  /** `null` tant que le sujet n'a jamais été guidé. */
  phase: JourneyPhase | null;
  phaseLabel: string | null;
  phaseChangedAt: string | null;
  /** La carte [Passer en guidé] [Rester libre] qui attend un geste (SF-176-02), ou `null`. */
  guidedProposal?: { reason: string | null; proposedAt: string; newChantier?: boolean } | null;
  /** L'utilisateur a choisi de rester libre sur ce sujet (SF-176-02). */
  guidedDeclined?: boolean;
  /** Le plan structuré du sujet guidé (SF-176-03), ou `null`. */
  plan?: JourneyPlan | null;
  /** Le diagnostic posé en fin d'investigation (SF-176-05), ou `null`. */
  diagnosis?: JourneyDiagnosis | null;
  /** Toutes les étapes vérifiées : la clôture attend un geste (SF-176-05). */
  closeProposed?: boolean;
  /** La porte refuse les modifications — calculée par la gateway, comme dans la boucle (SF-176-07). */
  gateClosed?: boolean;
  /** Le message exact du refus, ou `null` si la porte est ouverte (SF-176-07). */
  gateMessage?: string | null;
  /** Le chantier courant (SF-176-11), `null` si le sujet n'a jamais été guidé. */
  chantier?: JourneyChantier | null;
  /** Combien de chantiers clos ce sujet porte (SF-176-11). */
  closedChantiers?: number;
  /** Les chantiers OUVERTS du sujet, focus compris, par numéro croissant (F-206). */
  chantiers?: OpenChantier[];
}

/** Un chantier ouvert du sujet (F-206) : la bande en montre un onglet dès qu'il y en a deux. */
export interface OpenChantier {
  number: number;
  title: string | null;
  openedAt: string | null;
  phase: JourneyPhase | null;
  phaseLabel: string | null;
  /** Le chantier affiché, celui que visent les gestes. */
  focus: boolean;
  planVersion: number;
  validatedVersion: number | null;
  awaitingValidation: boolean;
  diagnosisPending: boolean;
  closeProposed: boolean;
  /** En Exécution sur son plan validé : il ouvre la porte des modifications. */
  executing: boolean;
  done: number;
  total: number;
  /** Le plan détaillé du chantier (F-208), `null` sans plan — absent d'une gateway antérieure. */
  plan?: JourneyPlan | null;
  /** Qui doit agir pour qu'il avance (F-220 / SF-220-01) — absent d'une gateway antérieure. */
  unblocker?: UnblockerMark | null;
}

/** Vrai si un chantier ouvert attend un geste de l'utilisateur (F-206). */
export function chantierAwaitsUser(c: OpenChantier): boolean {
  return c.awaitingValidation || c.diagnosisPending || c.closeProposed;
}

/** Le chantier au focus du parcours en direct, ou `null` (F-206). */
export function focusChantierNumber(journey: SubjectJourney | null | undefined): number | null {
  if (!journey || journey.mode !== 'GUIDE' || !journey.phase || journey.phase === 'CLOS') {
    return null;
  }
  return journey.chantier?.number ?? null;
}

/** Le chantier courant d'un sujet (SF-176-11). */
export interface JourneyChantier {
  number: number;
  title: string | null;
  openedAt: string | null;
}

/** Un chantier clos, consultable depuis l'en-tête (SF-176-11). */
export interface ClosedChantier {
  number: number;
  title: string | null;
  openedAt: string | null;
  closedAt: string;
  diagnosis: string | null;
  diagnosisConfidence: string | null;
  planVersion: number | null;
  plan: JourneyPlanStep[];
}

/** Le diagnostic (SF-176-05). */
export interface JourneyDiagnosis {
  text: string;
  evidence: string | null;
  confidence: 'FAIBLE' | 'MOYENNE' | 'ELEVEE' | string;
  /** « Prêt à planifier » attend le geste de l'utilisateur. */
  pending: boolean;
}

/** « faible », « moyenne », « élevée ». */
export function confidenceLabel(confidence: string | null | undefined): string {
  switch (confidence) {
    case 'FAIBLE': return 'faible';
    case 'ELEVEE': return 'élevée';
    default: return 'moyenne';
  }
}

/** La classe de risque d'une étape. */
export type JourneyRisk = 'LECTURE' | 'NOTES' | 'REVERSIBLE' | 'EXTERNE';

/** Une étape du plan (SF-176-03). */
export interface JourneyPlanStep {
  title: string;
  risk: JourneyRisk | string;
  riskLabel: string;
  verify: string | null;
  rollback: string | null;
  /** La clé de l'attente (F-175) dont l'étape dépend. */
  waitsOn: string | null;
  /** L'état de cette attente : A_FAIRE, DEMANDE, FAIT, ANNULE — `null` si aucune ne porte la clé. */
  waitsOnStatus: string | null;
  /** `BLOQUE` (F-219) : un vrai blocage posé par l'agent, la raison est dans `evidence`. */
  status: 'A_FAIRE' | 'FAIT' | 'VERIFIE' | 'ECHEC' | 'BLOQUE' | string;
  evidence: string | null;
  /** Nouvelle ou modifiée depuis le plan validé (amendement). */
  changed: boolean;
  /** Les rangs (à partir de 1) des étapes antérieures dont elle dépend, déclarés (F-208). */
  dependsOn?: number[];
  /** Rien ne la bloque : dépendances faites, attente non ouverte (F-208). */
  unlocked?: boolean;
  /** Ce qui la bloque, lisible : « étape 3 », « attente cert-gino » (F-208). */
  blockedBy?: string[];
  /** À faire, mais au moins une sous-étape faite (F-208). */
  inProgress?: boolean;
  /** Ses sous-étapes cochées (F-208). */
  subSteps?: JourneySubStep[];
  /** Qui doit agir pour qu'elle avance (F-220 / SF-220-01) ; `null` si faite. */
  unblocker?: UnblockerMark | null;
  /** Qui l'exécute (SF-219-06) — `OUTIL` quand rien n'est précisé. */
  actor?: StepActor | string;
  /** « par l'outil », « par vous », « par un tiers ». */
  actorLabel?: string;
  /** Faux pour une étape d'un plan d'avant SF-219-06 (acteur non précisé). */
  actorExplicit?: boolean;
}

/** Qui exécute une étape (F-219 / SF-219-06) : l'outil, l'utilisateur, ou quelqu'un d'autre. */
export type StepActor = 'OUTIL' | 'MOI' | 'TIERS';

/** Une sous-étape (F-208). */
export interface JourneySubStep {
  title: string;
  done: boolean;
  evidence: string | null;
}

/** Une version du plan d'un chantier et ce qui a changé depuis la précédente (F-208 / SF-208-02). */
export interface JourneyPlanVersion {
  version: number;
  createdAt: string;
  stepCount: number;
  added: string[];
  removed: string[];
  modified: string[];
}

/** L'état affiché d'une étape (F-208) — « en cours » et « bloquée » sont dérivés. */
export type StepState = 'verified' | 'done' | 'failed' | 'progress' | 'blocked' | 'todo';

export function stepState(step: JourneyPlanStep): StepState {
  switch (step.status) {
    case 'VERIFIE': return 'verified';
    case 'FAIT': return 'done';
    case 'ECHEC': return 'failed';
    case 'BLOQUE': return 'blocked';
  }
  if (step.inProgress) {
    return 'progress';
  }
  return step.unlocked === false ? 'blocked' : 'todo';
}

/** Libellé d'un état affiché. */
export function stepStateLabel(state: StepState): string {
  switch (state) {
    case 'verified': return 'vérifiée';
    case 'done': return 'faite';
    case 'failed': return 'échec';
    case 'progress': return 'en cours';
    case 'blocked': return 'bloquée';
    default: return 'à faire';
  }
}

/** L'icône Material d'un état affiché. */
export function stepStateIcon(state: StepState): string {
  switch (state) {
    case 'verified': return 'verified';
    case 'done': return 'check_circle';
    case 'failed': return 'error';
    case 'progress': return 'timelapse';
    case 'blocked': return 'lock';
    default: return 'radio_button_unchecked';
  }
}

/**
 * L'étape « en cours » d'un plan (F-208) : la première entamée, sinon la première à faire et débloquée,
 * sinon la première à faire ; `-1` si tout est fait.
 */
export function currentStepIndex(plan: JourneyPlan | null | undefined): number {
  const steps = plan?.steps ?? [];
  const started = steps.findIndex((s) => stepState(s) === 'progress');
  if (started >= 0) {
    return started;
  }
  const ready = steps.findIndex((s) => stepState(s) === 'todo');
  if (ready >= 0) {
    return ready;
  }
  return steps.findIndex((s) => s.status === 'A_FAIRE' || s.status === 'ECHEC');
}

/**
 * Combien de gestes un chantier ouvert attend de l'utilisateur (F-208) : plan à valider, diagnostic à
 * confirmer, clôture proposée, étapes qui attendent une attente ouverte.
 */
export function chantierGestures(c: OpenChantier): number {
  return (c.awaitingValidation ? 1 : 0) + (c.diagnosisPending ? 1 : 0) + (c.closeProposed ? 1 : 0)
    + (c.plan?.waitingInputs ?? 0);
}

/** Le badge du tableau des chantiers : les gestes attendus sur tous les chantiers ouverts (F-208). */
export function chantiersGestures(journey: SubjectJourney | null | undefined): number {
  return (journey?.chantiers ?? []).reduce((sum, c) => sum + chantierGestures(c), 0);
}

/** Le plan structuré (SF-176-03). */
export interface JourneyPlan {
  version: number;
  validatedVersion: number | null;
  validatedAt: string | null;
  /** Cette version attend le clic de l'utilisateur. */
  awaitingValidation: boolean;
  /** C'est la modification d'un plan déjà validé. */
  amendment: boolean;
  /** Étapes qui attendent une attente encore ouverte. */
  waitingInputs: number;
  steps: JourneyPlanStep[];
}

/** « à faire », « fait », « vérifié », « échec ». */
export function stepStatusLabel(status: string): string {
  switch (status) {
    case 'FAIT': return 'fait';
    case 'VERIFIE': return 'vérifié';
    case 'ECHEC': return 'échec';
    case 'BLOQUE': return 'bloquée';
    default: return 'à faire';
  }
}

/** « demandé », « à faire », « reçu », « annulé » — l'attente dont dépend une étape. */
export function waitsOnLabel(status: string | null): string {
  switch (status) {
    case 'DEMANDE': return 'demandé';
    case 'A_FAIRE': return 'à demander';
    case 'FAIT': return 'reçu';
    case 'ANNULE': return 'annulé';
    default: return 'attente inconnue';
  }
}

/** « Libre », « Guidé · Investigation ». */
export function journeyLabel(journey: SubjectJourney | null): string {
  if (!journey || journey.mode !== 'GUIDE') {
    return 'Libre';
  }
  return journey.phaseLabel ? `Guidé · ${journey.phaseLabel}` : 'Guidé';
}

/**
 * **Un clic = la décision ET la reprise** (F-176 / SF-176-09, décision D6) : le message visible qui
 * relance l'agent après un geste, ou `null` si le geste ne relance rien ([Rester libre], [Pas encore],
 * [Valider sans lancer]). `before` est le parcours vu au moment du clic.
 */
export function journeyResumeMessage(gesture: string, before: SubjectJourney | null): string | null {
  switch (gesture) {
    case 'accept-guided':
      // F-206 : sur un sujet déjà guidé, la proposition ouvrait un chantier de plus.
      return before?.guidedProposal?.newChantier
        ? '✓ Nouveau chantier ouvert — investigation lancée.'
        : '✓ Mode guidé — investigation lancée.';
    case 'confirm-diagnosis':
      return '✓ Diagnostic confirmé — planification lancée.';
    case 'dismiss-diagnosis':
      return '↻ Investigation poursuivie.';
    case 'validate-plan': {
      const plan = before?.plan;
      const version = plan ? ` v${plan.version}` : '';
      return plan?.amendment
        ? `✓ Amendement${version} validé — exécution reprise.`
        : `✓ Plan${version} validé — exécution lancée.`;
    }
    case 'close':
      // F-196 / SF-196-03 : le message de clôture est la trace persistée de la résolution — rendu en
      // séparateur dans le fil, ici et à l'historique.
      return chantierResolvedMessage(before?.chantier?.number ?? null);
    default:
      return null;
  }
}

/** Le texte du séparateur de résolution (F-196 / SF-196-03). */
export function chantierResolvedMessage(number: number | null): string {
  const label = number ? `Chantier ${number}` : 'Chantier';
  return `✓ ${label} résolu — ce qui suit va au-delà du besoin de départ`;
}

/** Une marque de résolution en tête de message, et ce qui la suit (un brouillon parti avec elle). */
export interface ChantierResolution {
  label: string;
  rest: string;
}

const RESOLUTION_MARK = /^✓ Chantier( \d+)? (résolu|clos)(?![\p{L}\p{N}])/u;

/**
 * Reconnaît la marque de résolution d'un chantier en **première ligne** d'un message utilisateur
 * (F-196 / SF-196-03) — y compris l'ancienne « ✓ Chantier clos. » d'avant F-196 —, ou `null`.
 */
export function chantierResolution(content: string | null | undefined): ChantierResolution | null {
  const text = (content ?? '').replace(/^\s+/, '');
  const newline = text.indexOf('\n');
  const first = (newline < 0 ? text : text.slice(0, newline)).trim();
  if (!RESOLUTION_MARK.test(first)) {
    return null;
  }
  return { label: first, rest: newline < 0 ? '' : text.slice(newline + 1).trim() };
}
