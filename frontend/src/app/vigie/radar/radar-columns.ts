import {
  BoardCommitment,
  BoardSubject,
  RadarEvidenceSource,
  RadarSubjectState,
} from '../../core/models/radar.models';

/**
 * **Les trois colonnes, en fonctions pures** (F-102 / SF-102-02) : pastilles, dates de report, moments,
 * tri. Sans Angular ni HTTP.
 */

/** Ton d'une pastille : les classes §5 (`badge--*`), et le bleu du §17 pour ce qui est neuf ou à confirmer. */
export type ChipTone = 'error' | 'warning' | 'neutral' | 'success' | 'blue';

export interface Chip {
  label: string;
  tone: ChipTone;
}

/** Un sujet ouvert muet depuis ce nombre de jours est dit « silencieux ». */
export const SILENT_AFTER_DAYS = 7;

/** Une échéance dans ce nombre de jours se dit par son jour (« pour vendredi »). */
export const WEEKDAY_WITHIN_DAYS = 6;

/** Minuit local d'une date `AAAA-MM-JJ` ou d'un instant. */
function localDay(value: string | Date): Date {
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d);
  }
  const date = typeof value === 'string' ? new Date(value) : value;
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function daysBetween(from: Date, to: Date): number {
  return Math.round((localDay(to).getTime() - localDay(from).getTime()) / 86_400_000);
}

/** `AAAA-MM-JJ` d'une date locale. */
export function isoDay(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/**
 * La pastille d'un engagement : relance due › retard › question › échéance.
 *
 * @param today jour de référence (injecté pour les tests)
 */
export function commitmentChip(item: BoardCommitment, today: Date = new Date()): Chip | null {
  const c = item.commitment;
  if (c.direction === 'OTHER_TO_ME' && c.followUpDue) {
    return { label: 'relance due', tone: 'error' };
  }
  if (item.overdueDays > 0) {
    return { label: `en retard de ${item.overdueDays} j`, tone: 'error' };
  }
  if (item.question) {
    return { label: 'probable', tone: 'neutral' };
  }
  if (!c.dueDate) {
    return null;
  }
  const days = daysBetween(today, localDay(c.dueDate));
  if (days <= 0) {
    return { label: "pour aujourd'hui", tone: 'warning' };
  }
  if (days === 1) {
    return { label: 'pour demain', tone: 'warning' };
  }
  const due = localDay(c.dueDate);
  if (days <= WEEKDAY_WITHIN_DAYS) {
    return { label: `pour ${due.toLocaleDateString('fr-FR', { weekday: 'long' })}`, tone: 'warning' };
  }
  return { label: `pour le ${due.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })}`, tone: 'neutral' };
}

/** Le titre d'un engagement ; une question se lit comme une question. */
export function commitmentTitle(item: BoardCommitment): string {
  const text = item.commitment.description.trim();
  return item.question && !text.endsWith('?') ? `${text} ?` : text;
}

/** Qui, pour un attendu ou une mise en relation ; rien pour ce que je dois faire seul. */
export function commitmentPeople(item: BoardCommitment): string | null {
  const c = item.commitment;
  if (c.direction === 'OTHER_TO_ME') {
    return c.fromPerson?.displayName ?? null;
  }
  if (c.direction === 'INTRODUCTION') {
    const names = [c.toPerson?.displayName, c.otherPerson?.displayName].filter((n): n is string => !!n);
    return names.length > 0 ? `mise en relation : ${names.join(' et ')}` : 'mise en relation';
  }
  return c.toPerson ? `pour ${c.toPerson.displayName}` : null;
}

/** Les reports proposés. */
export type PostponeChoice = 'tomorrow' | 'next-monday' | 'one-week' | 'two-weeks';

export const POSTPONE_CHOICES: readonly { choice: PostponeChoice; label: string }[] = [
  { choice: 'tomorrow', label: 'Demain' },
  { choice: 'next-monday', label: 'Lundi prochain' },
  { choice: 'one-week', label: 'Dans une semaine' },
  { choice: 'two-weeks', label: 'Dans deux semaines' },
];

/** La nouvelle échéance d'un report, en `AAAA-MM-JJ`. */
export function postponeDate(choice: PostponeChoice, today: Date = new Date()): string {
  const day = localDay(today);
  switch (choice) {
    case 'tomorrow':
      day.setDate(day.getDate() + 1);
      break;
    case 'next-monday': {
      const ahead = ((8 - day.getDay()) % 7) || 7;
      day.setDate(day.getDate() + ahead);
      break;
    }
    case 'one-week':
      day.setDate(day.getDate() + 7);
      break;
    default:
      day.setDate(day.getDate() + 14);
  }
  return isoDay(day);
}

/** « aujourd'hui 14:32 », « hier 14:32 », « 12 sept. ». */
export function momentLabel(instant: string | null, now: Date = new Date()): string | null {
  if (!instant) {
    return null;
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const days = daysBetween(date, now);
  const hour = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  if (days === 0) {
    return `aujourd'hui ${hour}`;
  }
  if (days === 1) {
    return `hier ${hour}`;
  }
  return date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
}

/** « il y a 18 h », « il y a 9 j », « à l'instant ». */
export function sinceLabel(instant: string | null, now: Date = new Date()): string | null {
  if (!instant) {
    return null;
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const hours = Math.floor((now.getTime() - date.getTime()) / 3_600_000);
  if (hours < 1) {
    return "il y a moins d'une heure";
  }
  if (hours < 24) {
    return `il y a ${hours} h`;
  }
  return `il y a ${Math.floor(hours / 24)} j`;
}

/** L'icône Material d'une source de preuve (§17). */
export function evidenceSourceIcon(source: RadarEvidenceSource | null): string {
  switch (source) {
    case 'TEAMS_MEETING': return 'videocam';
    case 'LOCAL_RECORDING': return 'mic';
    case 'USER_NOTE': return 'edit_note';
    case 'PASTED_MAIL': return 'mail';
    default: return 'forum';
  }
}

/** Le nom d'une source, pour les lecteurs d'écran. */
export function evidenceSourceLabel(source: RadarEvidenceSource | null): string {
  switch (source) {
    case 'TEAMS_MEETING': return 'réunion Teams';
    case 'LOCAL_RECORDING': return 'enregistrement';
    case 'USER_NOTE': return 'votre note';
    case 'PASTED_MAIL': return 'courriel collé';
    default: return 'message Teams';
  }
}

/** Les jours de silence d'un sujet ouvert, ou `null` s'il n'est pas silencieux. */
export function silentDays(subject: BoardSubject, now: Date = new Date()): number | null {
  const s = subject.subject;
  const open = s.state === 'NEW' || s.state === 'ADVANCING' || s.state === 'WAITING' || s.state === 'BLOCKED';
  if (!open || !s.lastActivityAt) {
    return null;
  }
  const days = daysBetween(new Date(s.lastActivityAt), now);
  return days >= SILENT_AFTER_DAYS ? days : null;
}

/** La pastille d'état d'un sujet (§17). */
export function subjectChip(subject: BoardSubject, now: Date = new Date()): Chip {
  const s = subject.subject;
  if (s.awake) {
    return { label: 'se réveille', tone: 'blue' };
  }
  const silent = silentDays(subject, now);
  if (silent !== null && s.state !== 'BLOCKED') {
    return { label: `silencieux ${silent} j`, tone: 'warning' };
  }
  return SUBJECT_STATE_CHIPS[s.state];
}

export const SUBJECT_STATE_CHIPS: Record<RadarSubjectState, Chip> = {
  NEW: { label: 'nouveau', tone: 'blue' },
  ADVANCING: { label: 'avance', tone: 'success' },
  WAITING: { label: 'en attente', tone: 'warning' },
  BLOCKED: { label: 'bloqué', tone: 'error' },
  DORMANT: { label: 'en sommeil', tone: 'neutral' },
  CLOSE_PROPOSED: { label: 'clos ?', tone: 'blue' },
  CLOSED: { label: 'clos', tone: 'neutral' },
};

/** Tri des sujets. */
export type SubjectOrder = 'recent' | 'attention';

const ATTENTION_RANK: Record<RadarSubjectState, number> = {
  CLOSED: 0, CLOSE_PROPOSED: 1, BLOCKED: 2, DORMANT: 3, WAITING: 4, NEW: 5, ADVANCING: 6,
};

/** *Plus récents* : l'ordre de la gateway ; *À traiter d'abord* : réveillés, clos ?, bloqués, en sommeil… */
export function orderSubjects(subjects: BoardSubject[], order: SubjectOrder): BoardSubject[] {
  if (order === 'recent') {
    return subjects;
  }
  const rank = (s: BoardSubject) => (s.subject.awake ? -1 : ATTENTION_RANK[s.subject.state] ?? 9);
  return subjects.map((s, i) => ({ s, i }))
    .sort((a, b) => rank(a.s) - rank(b.s) || a.i - b.i)
    .map(({ s }) => s);
}
