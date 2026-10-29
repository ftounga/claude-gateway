import { AutonomyBoardEntry, AutonomyMode } from '../../core/models/autonomy.models';

/** Ce que l'overlay propose pour un chantier (F-221 / SF-221-04). */
export interface ParallelChoice {
  /** Le mode retenu pour partir, ou `null` : le chantier ne peut pas partir maintenant. */
  mode: AutonomyMode | null;
  /** Pourquoi il ne peut pas partir (ou ce qui restera en avançant jusqu'au blocage). */
  reasons: string[];
}

/**
 * **Le mode du départ en parallèle** (F-221 / SF-221-04), en fonction pure : *avancer jusqu'au blocage*
 * quand c'est possible (SF-219-05, le geste que le PO tape à la main), sinon l'exécution autonome stricte si
 * le préflight est prêt, sinon rien — avec les raisons. Un mode demandé par le bouton du tableau est
 * respecté s'il est possible.
 */
export function parallelChoice(entry: AutonomyBoardEntry | null | undefined,
    requested: AutonomyMode | null): ParallelChoice {
  if (!entry) {
    return { mode: null, reasons: ['L’état du chantier n’a pas pu être lu.'] };
  }
  const canAdvance = entry.preflight.advance?.possible === true;
  const ready = entry.preflight.ready;
  const reasons = (entry.preflight.reasons ?? []).map((reason) => reason.label);
  if (requested === 'STRICT' && ready) {
    return { mode: 'STRICT', reasons: [] };
  }
  if (canAdvance) {
    return { mode: 'UNTIL_BLOCKED', reasons: entry.preflight.advance?.label ? [entry.preflight.advance.label] : [] };
  }
  if (ready) {
    return { mode: 'STRICT', reasons: [] };
  }
  return { mode: null, reasons: reasons.length > 0 ? reasons
    : [entry.preflight.advance?.label ?? 'Rien n’est à la portée de l’outil pour l’instant.'] };
}
