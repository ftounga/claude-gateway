import { SubjectJourney } from '../../core/models/journey.models';
import { AtelierPlanStep } from '../../core/models/atelier.models';
import { TerminalActionBoard } from '../../core/models/terminal-actions.models';

/**
 * **La barre d'état du bas du terminal** (F-207 / SF-207-03) — fonctions pures : quelles puces
 * montrer, et quand le parcours attend une décision (qui, elle, ne se cache jamais).
 */
export type DockKey = 'chantier' | 'plan' | 'matin' | 'attentes';

export interface DockChip {
  key: DockKey;
  icon: string;
  label: string;
  /** Le parcours attend un geste de l'utilisateur. */
  await: boolean;
  /** Quelque chose est dû (relance). */
  warn: boolean;
}

/** Les puces, dans l'ordre de la barre, avec leur libellé dans le menu « Afficher dans la barre ». */
export const DOCK_ITEMS: { key: DockKey; label: string }[] = [
  { key: 'chantier', label: 'Chantier' },
  { key: 'plan', label: 'Plan reporté' },
  { key: 'matin', label: 'Point du matin' },
  { key: 'attentes', label: 'Attentes et relances' },
];

export const DOCK_HIDDEN_KEY = 'cg.dock.hidden';

/** Même règle que la bande du parcours (`TerminalJourneyStripComponent.guided`). */
export function journeyGuided(j: SubjectJourney | null): boolean {
  return !!j && j.mode === 'GUIDE' && !!j.phase && j.phase !== 'CLOS';
}

/**
 * Le parcours attend un geste : proposition de passer en guidé (ou d'ouvrir un chantier de plus),
 * diagnostic à confirmer, plan ou amendement à valider, clôture proposée. Mêmes règles que les cartes
 * de la bande du parcours.
 */
export function journeyAwaitsGesture(j: SubjectJourney | null): boolean {
  if (!j) {
    return false;
  }
  if (j.guidedProposal && (j.mode !== 'GUIDE' || j.guidedProposal.newChantier)) {
    return true;
  }
  if (!journeyGuided(j)) {
    return false;
  }
  return !!j.plan?.awaitingValidation
    || (j.phase === 'INVESTIGATION' && !!j.diagnosis?.pending)
    || !!j.closeProposed;
}

/** La puce du parcours, ou `null` quand le sujet est libre et que rien n'est proposé. */
export function journeyChip(j: SubjectJourney | null): DockChip | null {
  if (!j) {
    return null;
  }
  const awaits = journeyAwaitsGesture(j);
  if (journeyGuided(j)) {
    const open = j.chantiers?.length ?? 0;
    const n = j.chantier?.number;
    const phase = j.phaseLabel ?? '';
    const head = open > 1 && n ? `Chantier ${n}` : 'Guidé';
    const more = open > 1 ? ` · ${open} chantiers` : '';
    return { key: 'chantier', icon: 'route', label: `${head}${phase ? ' · ' + phase : ''}${more}`, await: awaits, warn: false };
  }
  if (awaits) {
    return { key: 'chantier', icon: 'route', label: 'Chantier proposé', await: true, warn: false };
  }
  if (j.mode !== 'GUIDE' && j.phase === 'CLOS') {
    return { key: 'chantier', icon: 'task_alt', label: 'Chantier clos', await: false, warn: false };
  }
  return null;
}

export function planChip(plan: AtelierPlanStep[], streaming: boolean): DockChip | null {
  if (streaming || plan.length === 0) {
    return null;
  }
  const done = plan.filter((step) => step.status === 'done').length;
  return { key: 'plan', icon: 'checklist', label: `Plan · ${done}/${plan.length}`, await: false, warn: false };
}

export function attentesChip(board: TerminalActionBoard | null, toReview: number): DockChip | null {
  const open = board ? board.aFaire + board.demande : 0;
  if (open === 0 && toReview <= 0) {
    return null;
  }
  const due = board?.aRelancer ?? 0;
  const parts = [`Attentes · ${open}`];
  if (due > 0) {
    parts.push(`${due} à relancer`);
  }
  if (toReview > 0) {
    parts.push(`${toReview} à vérifier`);
  }
  return { key: 'attentes', icon: 'pending_actions', label: parts.join(' · '), await: false, warn: due > 0 };
}

export function readDockHidden(): Set<DockKey> {
  try {
    const raw = localStorage.getItem(DOCK_HIDDEN_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    const known = new Set(DOCK_ITEMS.map((item) => item.key));
    return new Set(Array.isArray(parsed) ? (parsed.filter((k) => known.has(k as DockKey)) as DockKey[]) : []);
  } catch {
    return new Set();
  }
}

export function writeDockHidden(hidden: Set<DockKey>): void {
  try {
    if (hidden.size === 0) {
      localStorage.removeItem(DOCK_HIDDEN_KEY);
    } else {
      localStorage.setItem(DOCK_HIDDEN_KEY, JSON.stringify([...hidden]));
    }
  } catch {
    // Stockage indisponible : la préférence vaut pour la session.
  }
}
