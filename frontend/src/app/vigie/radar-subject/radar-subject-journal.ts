import { RadarCorrectionView } from '../../core/models/radar-subject.models';
import { dayLabel, stateBadge } from './radar-subject-view';

/**
 * **Les corrections d'un sujet, en mots** (F-99 / SF-99-06) — ce que la chronologie de la page sujet
 * écrit de chaque geste souverain, pour qu'il puisse être annulé en sachant ce qu'on annule. Sans
 * Angular ni HTTP.
 */

/** Une correction en mots, et le sujet vers lequel elle mène le cas échéant. */
export interface CorrectionLine {
  text: string;
  /** Le sujet né d'une séparation, ou le sujet absorbé d'une fusion : un lien, jamais deviné. */
  linkSubjectId: string | null;
  linkLabel: string | null;
}

const COMMITMENT_WORDS: Record<string, string> = {
  DONE: 'Engagement fait',
  NOT_MINE: 'Engagement : pas moi',
  ABANDON: 'Engagement abandonné',
  CONFIRM: "Engagement : c'est moi",
  REOPEN: 'Engagement rouvert',
  ADD_COMMITMENT: 'Engagement ajouté',
};

const SUBJECT_WORDS: Record<string, string> = {
  CLOSE: 'Sujet clos',
  CONFIRM_CLOSE: 'Clôture confirmée',
  REJECT_CLOSE: 'Clôture refusée : le sujet reste ouvert',
  DISMISS_WAKE: 'Laissé clos malgré le réveil',
  CREATE_SUBJECT: 'Sujet créé par votre nouvelle',
};

function str(values: Record<string, unknown> | null | undefined, key: string): string | null {
  const value = values?.[key];
  return typeof value === 'string' && value.trim().length > 0 ? value : null;
}

const quoted = (value: string) => `« ${value} »`;

/**
 * Écrit une correction. Un engagement est nommé par sa description quand la page la connaît ; une action
 * inconnue (gateway plus récente) s'écrit telle quelle plutôt que de disparaître.
 */
export function correctionLine(
  correction: RadarCorrectionView,
  commitmentNames: ReadonlyMap<string, string> = new Map(),
): CorrectionLine {
  const line = (text: string, linkSubjectId: string | null = null, linkLabel: string | null = null) =>
    ({ text, linkSubjectId, linkLabel });
  const { after, before } = correction;
  switch (correction.action) {
    case 'RENAME': {
      const name = str(after, 'name');
      return line(name ? `Renommé en ${quoted(name)}` : 'Renommé');
    }
    case 'SET_STATE':
      return line(`État dit : ${stateBadge(str(after, 'state')).label}`);
    case 'SET_NEXT_STEP': {
      const next = str(after, 'nextStep');
      return line(next ? `Prochaine étape dite : ${quoted(next)}` : 'Prochaine étape effacée');
    }
    case 'SET_DUE_DATE': {
      const due = dayLabel(str(after, 'dueDate'));
      return line(due ? `Échéance dite : ${due}` : 'Échéance effacée');
    }
    case 'SPLIT':
      return line('Séparé : une partie est devenue un nouveau sujet', str(after, 'created'), 'Ouvrir le nouveau sujet');
    case 'MERGE': {
      const source = str(after, 'source');
      return source === correction.subjectId
        ? line('Fusionné dans un autre sujet', str(after, 'into'), 'Ouvrir le sujet cible')
        : line('Un autre sujet y a été fusionné', source, 'Ouvrir le sujet absorbé');
    }
    case 'ADD_ALIAS': {
      const alias = str(after, 'alias');
      return line(alias ? `Alias ajouté : ${quoted(alias)}` : 'Alias ajouté');
    }
    case 'REMOVE_ALIAS': {
      const alias = str(before, 'alias');
      const kind = before?.['rejected'] === true ? 'Consigne retirée' : 'Alias retiré';
      return line(alias ? `${kind} : ${quoted(alias)}` : kind);
    }
    case 'POSTPONE': {
      const due = dayLabel(str(after, 'dueDate'));
      return line(withCommitment(due ? `Engagement reporté au ${due}` : 'Engagement reporté', correction, commitmentNames));
    }
    default:
      break;
  }
  if (COMMITMENT_WORDS[correction.action]) {
    return line(withCommitment(COMMITMENT_WORDS[correction.action], correction, commitmentNames));
  }
  return line(SUBJECT_WORDS[correction.action] ?? correction.action);
}

function withCommitment(text: string, correction: RadarCorrectionView,
  commitmentNames: ReadonlyMap<string, string>): string {
  const name = correction.targetKind === 'COMMITMENT' ? commitmentNames.get(correction.targetId) : undefined;
  return name ? `${text} — ${quoted(name)}` : text;
}

/** La correction d'un geste d'alias qui vient d'être fait, retrouvée dans le journal relu. */
export function aliasCorrection(
  journal: readonly RadarCorrectionView[],
  action: 'ADD_ALIAS' | 'REMOVE_ALIAS',
  aliasId: string,
): RadarCorrectionView | null {
  return journal.find((c) => c.action === action && c.undoneAt === null
    && str(action === 'ADD_ALIAS' ? c.after : c.before, 'aliasId') === aliasId) ?? null;
}

/** Séparer demande au moins une preuve qui part et une qui reste. */
export function canSplit(selected: number, total: number, name: string): boolean {
  return name.trim().length > 0 && selected > 0 && selected < total;
}
