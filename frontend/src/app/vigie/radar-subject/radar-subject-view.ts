import {
  RadarEvidenceSource,
  RadarEvidenceView,
  RadarSubjectDetail,
  RadarSubjectState,
} from '../../core/models/radar-subject.models';

/**
 * **La page sujet, en fonctions pures** (F-103 / SF-103-01) : les mots d'un état et d'une source, la
 * numérotation des renvois, les dates en mots, le lien profond. Sans Angular ni HTTP.
 */

/** Un état en mots, et sa pastille §5 — la couleur ne porte jamais seule l'information. */
export interface StateBadge {
  label: string;
  badgeClass: string;
}

const STATE_BADGES: Record<RadarSubjectState, StateBadge> = {
  NEW: { label: 'nouveau', badgeClass: 'badge--info' },
  ADVANCING: { label: 'avance', badgeClass: 'badge--success' },
  WAITING: { label: 'en attente', badgeClass: 'badge--warning' },
  BLOCKED: { label: 'bloqué', badgeClass: 'badge--error' },
  DORMANT: { label: 'en sommeil', badgeClass: 'badge--neutral' },
  CLOSE_PROPOSED: { label: 'clos ?', badgeClass: 'badge--info' },
  CLOSED: { label: 'clos', badgeClass: 'badge--neutral' },
};

/** La pastille d'un état ; un état inconnu (gateway plus récente) s'écrit tel quel, en neutre. */
export function stateBadge(state: string | null | undefined): StateBadge {
  return STATE_BADGES[state as RadarSubjectState] ?? { label: state ?? '—', badgeClass: 'badge--neutral' };
}

/** Une source en mots et son icône Material. */
export interface SourceView {
  label: string;
  icon: string;
}

const SOURCES: Record<RadarEvidenceSource, SourceView> = {
  TEAMS_MESSAGE: { label: 'Message Teams', icon: 'chat' },
  TEAMS_MEETING: { label: 'Réunion Teams', icon: 'videocam' },
  LOCAL_RECORDING: { label: 'Enregistrement hors Teams', icon: 'mic' },
  USER_NOTE: { label: 'Votre nouvelle', icon: 'edit_note' },
  PASTED_MAIL: { label: 'Courriel collé', icon: 'mail' },
};

export function sourceView(source: string | null | undefined): SourceView {
  return SOURCES[source as RadarEvidenceSource] ?? { label: 'Source', icon: 'description' };
}

/**
 * **La numérotation des renvois** : chaque preuve reçoit un numéro dans l'ordre où la page la cite —
 * résumé d'abord, puis état, prochaine étape, échéance, signal de clôture, réveil — et **le même
 * numéro partout**. Une preuve de la chronologie qui n'est citée nulle part reçoit le numéro suivant,
 * pour que chaque entrée de la chronologie porte le sien.
 */
export function evidenceNumbers(detail: RadarSubjectDetail): Map<string, number> {
  const numbers = new Map<string, number>();
  const cite = (ids: readonly string[] | null | undefined) => {
    for (const id of ids ?? []) {
      if (!numbers.has(id)) {
        numbers.set(id, numbers.size + 1);
      }
    }
  };
  for (const sentence of [...(detail.summary ?? [])].sort((a, b) => a.position - b.position)) {
    cite(sentence.evidenceIds);
  }
  cite(detail.stateEvidenceIds);
  cite(detail.nextStepEvidenceIds);
  cite(detail.dueDateEvidenceIds);
  cite(detail.closeSignalEvidenceIds);
  cite(detail.wakeEvidenceIds);
  cite((detail.chronology ?? []).map((evidence) => evidence.id));
  return numbers;
}

/** Les numéros d'une liste de preuves, triés et sans doublon. */
export function refsOf(ids: readonly string[] | null | undefined, numbers: Map<string, number>): number[] {
  return [...new Set((ids ?? []).map((id) => numbers.get(id)).filter((n): n is number => n !== undefined))]
    .sort((a, b) => a - b);
}

const pad = (n: number) => String(n).padStart(2, '0');

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** Une date de chronologie en mots : « aujourd'hui 14:32 », « hier 09:10 », « 12 sept. ». */
export function whenLabel(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (sameDay(date, now)) {
    return `aujourd'hui ${time}`;
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (sameDay(date, yesterday)) {
    return `hier ${time}`;
  }
  const options: Intl.DateTimeFormatOptions = date.getFullYear() === now.getFullYear()
    ? { day: 'numeric', month: 'short' }
    : { day: 'numeric', month: 'short', year: 'numeric' };
  return date.toLocaleDateString('fr-FR', options);
}

/** Une date (jour seul) en mots : « 1 octobre 2026 ». */
export function dayLabel(iso: string | null | undefined): string | null {
  if (!iso) {
    return null;
  }
  // Une date sans heure (`2026-10-01`) est lue comme un jour local, jamais décalée par le fuseau.
  const date = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(`${iso}T00:00:00`) : new Date(iso);
  return Number.isNaN(date.getTime()) ? null
    : date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' });
}

/** Le lien profond, seulement s'il est `https://` — jamais `javascript:`, jamais en clair. */
export function safeLink(url: string | null | undefined): string | null {
  const trimmed = (url ?? '').trim();
  return /^https:\/\/[^\s]+$/i.test(trimmed) ? trimmed : null;
}

/** Le libellé du lien profond d'une preuve, selon sa source — la seconde pour une réunion. */
export function linkLabel(evidence: Pick<RadarEvidenceView, 'source' | 'occurredAt'>): string {
  switch (evidence.source) {
    case 'TEAMS_MEETING': {
      const date = new Date(evidence.occurredAt);
      return Number.isNaN(date.getTime()) ? 'Ouvrir le moment'
        : `Ouvrir le moment · ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
    }
    case 'LOCAL_RECORDING':
      return 'Ouvrir la transcription';
    case 'PASTED_MAIL':
    case 'USER_NOTE':
      return 'Ouvrir';
    default:
      return 'Ouvrir dans Teams';
  }
}
