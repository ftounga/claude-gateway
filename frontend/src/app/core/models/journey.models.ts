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
  guidedProposal?: { reason: string | null; proposedAt: string } | null;
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
  status: 'A_FAIRE' | 'FAIT' | 'VERIFIE' | 'ECHEC' | string;
  evidence: string | null;
  /** Nouvelle ou modifiée depuis le plan validé (amendement). */
  changed: boolean;
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
      return '✓ Mode guidé — investigation lancée.';
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
      return '✓ Chantier clos.';
    default:
      return null;
  }
}
