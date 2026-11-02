import { JarvisWatchSummary } from '../../../core/models/veille.models';

/**
 * F-236 / SF-236-25 — **l'horloge des veilles** (NF-14 / N10). Fichier <b>pur</b>.
 *
 * <p>Quand chaque veille passe, du lundi au vendredi (les veilles F-230 ne passent que ces jours-là), la prochaine en
 * surbrillance. Lu dans `GET /api/jarvis/watches` (filtré `user_id`) ; une réponse illisible ⇒ rien, aucune
 * erreur.</p>
 */

export const WEEK_DAYS = ['lun.', 'mar.', 'mer.', 'jeu.', 'ven.'] as const;

/** L'axe de l'horloge : de 6 h à 22 h (élargi si une veille passe en dehors). */
export const CLOCK_FROM = 6 * 60;
export const CLOCK_TO = 22 * 60;

export interface ClockMark {
  watchId: string;
  name: string;
  subject: string;
  /** Minutes depuis minuit. */
  minute: number;
  time: string;
  next: boolean;
}

export interface ClockDay {
  label: string;
  today: boolean;
  marks: ClockMark[];
}

export interface WatchClock {
  days: ClockDay[];
  from: number;
  to: number;
  /** La prochaine : « Tickets FORGE · mar. 07:30 », `null` sans veille. */
  next: { name: string; subject: string; when: string } | null;
}

const TIME = /^([01]?\d|2[0-3]):([0-5]\d)$/;

function minuteOf(text: unknown): number | null {
  const m = typeof text === 'string' ? TIME.exec(text.trim()) : null;
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

function hhmm(minute: number): string {
  return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
}

/** Lundi = 0 … vendredi = 4 ; samedi et dimanche ⇒ -1. */
function weekIndex(d: Date): number {
  const day = d.getDay();
  return day >= 1 && day <= 5 ? day - 1 : -1;
}

/** L'horloge de la semaine ; `null` si la réponse est illisible ou sans veille active. */
export function watchClock(list: unknown, now: Date): WatchClock | null {
  if (!Array.isArray(list)) {
    return null;
  }
  const days: ClockDay[] = WEEK_DAYS.map((label, i) => ({ label, today: weekIndex(now) === i, marks: [] }));
  let from = CLOCK_FROM;
  let to = CLOCK_TO;
  for (const item of list as JarvisWatchSummary[]) {
    const w = item?.watch;
    if (!w || typeof w.id !== 'string' || !w.active || w.proposed || !Array.isArray(w.runTimes)) {
      continue;
    }
    for (const raw of w.runTimes) {
      const minute = minuteOf(raw);
      if (minute === null) {
        continue;
      }
      from = Math.min(from, Math.floor(minute / 60) * 60);
      to = Math.max(to, Math.ceil((minute + 1) / 60) * 60);
      for (const day of days) {
        day.marks.push({ watchId: w.id, name: w.name ?? '', subject: item.workspaceName ?? '', minute,
          time: hhmm(minute), next: false });
      }
    }
  }
  const any = days.some((d) => d.marks.length > 0);
  if (!any) {
    return null;
  }
  for (const day of days) {
    day.marks.sort((a, b) => a.minute - b.minute || a.name.localeCompare(b.name));
  }
  // La prochaine : le premier passage après maintenant, en parcourant la semaine (lundi suivant le week-end).
  const today = weekIndex(now);
  const nowMinute = now.getHours() * 60 + now.getMinutes();
  let next: { day: number; mark: ClockMark } | null = null;
  for (let step = 0; step <= 5 && !next; step++) {
    const index = today < 0 ? step : (today + step) % 5;
    if (today < 0 && step === 5) {
      break;
    }
    const day = days[index];
    const mark = day.marks.find((m) => step > 0 || today < 0 || m.minute > nowMinute);
    if (mark) {
      next = { day: index, mark };
    }
  }
  if (next) {
    next.mark.next = true;
  }
  return {
    days, from, to,
    next: next ? { name: next.mark.name, subject: next.mark.subject,
      when: `${next.day === today ? 'aujourd’hui' : WEEK_DAYS[next.day]} ${next.mark.time}` } : null,
  };
}

/** Position d'un passage sur l'axe (0-100 %). */
export function clockX(clock: WatchClock, minute: number): number {
  return Math.round(((minute - clock.from) / Math.max(1, clock.to - clock.from)) * 1000) / 10;
}
