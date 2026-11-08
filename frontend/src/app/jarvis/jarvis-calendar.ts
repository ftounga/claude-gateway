import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { normalizeName } from './scenes/jarvis-name-matcher';

/**
 * **Les agendas de Jarvis, côté écran** (F-243 / SF-243-01).
 *
 * `/api/jarvis/calendar/feeds` : ajouter un agenda Google par son « adresse secrète au format iCal » (lue et analysée
 * par la passerelle avant d'être enregistrée, chiffrée), la tester, la retirer. L'adresse n'est jamais relue : la
 * passerelle ne rend que ses 4 derniers caractères. Le volet n'apparaît jamais en présentation.
 */

export type CalendarFeedStatus = 'OK' | 'BROKEN';

export interface CalendarFeed {
  id: string;
  label: string;
  status: CalendarFeedStatus;
  secretLast4: string;
  eventCount: number;
  lastOkAt: string | null;
  lastError: string | null;
  createdAt: string;
}

/** Ce que dit la passerelle d'un refus (400 adresse refusée ou illisible, 503 injoignable). */
export interface CalendarError {
  error?: { error?: string; message?: string };
  status?: number;
}

/** F-243 / SF-243-03 — un droit d'écriture dans l'agenda pro (jamais le jeton). */
export interface CalendarGrant {
  id: string;
  account: string;
  clientKind: 'INTERNAL' | 'EXTERNAL';
  grantedAt: string;
}

/** F-243 / SF-243-03 — l'écriture : configurée ou non, et les droits. */
export interface CalendarGrants {
  configured: boolean;
  grants: CalendarGrant[];
}

/** L'aide Google : où trouver l'adresse secrète au format iCal d'un agenda. */
export const GOOGLE_ICAL_HELP_URL = 'https://support.google.com/calendar/answer/37648';

/** Le libellé lisible d'un état. */
export function calendarStatusLabel(status: CalendarFeedStatus): string {
  return status === 'OK' ? 'Lu' : 'Adresse refusée';
}

/** Le message lisible d'un refus de la passerelle. */
export function calendarErrorMessage(err: CalendarError | null | undefined): string {
  const message = err?.error?.message;
  if (message) {
    return message;
  }
  if (err?.status === 503) {
    return 'L’agenda ne répond pas pour l’instant. Réessayez dans un moment.';
  }
  return 'L’agenda n’a pas pu être ajouté.';
}

@Injectable({ providedIn: 'root' })
export class JarvisCalendarClient {
  private readonly http = inject(HttpClient);

  list(): Observable<CalendarFeed[]> {
    return this.http.get<CalendarFeed[]>('/api/jarvis/calendar/feeds');
  }

  link(url: string, label: string): Observable<CalendarFeed> {
    return this.http.post<CalendarFeed>('/api/jarvis/calendar/feeds', { url, label });
  }

  test(id: string): Observable<CalendarFeed> {
    return this.http.post<CalendarFeed>(`/api/jarvis/calendar/feeds/${id}/test`, {});
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/calendar/feeds/${id}`);
  }

  /** F-243 / SF-243-03 — l'écriture dans l'agenda pro. */
  grants(): Observable<CalendarGrants> {
    return this.http.get<CalendarGrants>('/api/jarvis/calendar/grants');
  }

  /** F-243 / SF-243-03 — le départ du parcours OAuth : l'adresse de consentement Google. */
  startOAuth(): Observable<{ url: string }> {
    return this.http.get<{ url: string }>('/api/jarvis/calendar/oauth/start');
  }

  revokeGrant(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/calendar/grants/${id}`);
  }

  /** F-243 / SF-243-02 — les rendez-vous de [from, to[ (ISO-8601), fusionnés par la passerelle. */
  events(from: string, to: string): Observable<CalendarWindow> {
    return this.http.get<CalendarWindow>('/api/jarvis/calendar/events', { params: { from, to } });
  }
}

/** F-243 / SF-243-02 — un rendez-vous fusionné (les deux agendas, sans doublon de même UID). */
export interface CalendarItem {
  id: string;
  feedLabel: string;
  uid: string;
  start: string;
  end: string;
  allDay: boolean;
  title: string;
  location: string;
  status: 'CONFIRMED' | 'TENTATIVE' | 'PROPOSED';
}

/** F-243 / SF-243-02 — une lecture de l'agenda. */
export interface CalendarWindow {
  from: string;
  to: string;
  items: CalendarItem[];
  updatedAt: string | null;
  feeds: number;
  /** F-243 / SF-243-04 — les rendez-vous repérés dans les mails, pas encore posés (en pointillé). */
  proposals?: CalendarItem[];
}

/** F-243 / SF-243-02 (D10) — ce que montre la vue d'agenda : une journée ou une semaine (à partir de `date`). */
export interface AgendaView {
  mode: 'jour' | 'semaine';
  date: string;
}

/** Le préfixe de la vue d'agenda dans l'ordre d'écran `afficher` (posé par la passerelle). */
export const AGENDA_PIECE_PREFIX = 'agenda:';

/** La vue d'agenda d'un ordre d'écran (`agenda:jour:2026-11-04`), ou `null`. */
export function agendaPieceView(pieces: readonly string[] | null | undefined): AgendaView | null {
  const hit = (pieces ?? []).find((p) => p.startsWith(AGENDA_PIECE_PREFIX));
  const m = hit ? /^agenda:(jour|semaine):(\d{4}-\d{2}-\d{2})$/.exec(hit) : null;
  return m ? { mode: m[1] as AgendaView['mode'], date: m[2] } : null;
}

/** Les journées d'une vue : une seule, ou de `date` au dimanche qui suit. */
export function agendaDays(view: AgendaView): string[] {
  const days = [view.date];
  if (view.mode === 'semaine') {
    let d = parseDay(view.date);
    while (d.getUTCDay() !== 0 && days.length < 7) {
      d = new Date(d.getTime() + 86_400_000);
      days.push(d.toISOString().slice(0, 10));
    }
  }
  return days;
}

function parseDay(day: string): Date {
  return new Date(`${day}T00:00:00Z`);
}

/** La journée (AAAA-MM-JJ, heure de Paris) d'un instant. */
export function parisDay(iso: string): string {
  return new Intl.DateTimeFormat('fr-CA', { timeZone: 'Europe/Paris', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(iso));
}

/** L'heure (hh:mm, heure de Paris) d'un instant. */
export function parisHour(iso: string): string {
  return new Intl.DateTimeFormat('fr-FR', { timeZone: 'Europe/Paris', hour: '2-digit', minute: '2-digit' })
    .format(new Date(iso));
}

/** Les minutes depuis minuit (heure de Paris) d'un instant. */
export function parisMinutes(iso: string): number {
  const [h, m] = parisHour(iso).split(':').map((x) => Number(x));
  return h * 60 + m;
}

/** Les mots trop courants d'un intitulé : à eux seuls, ils n'allument rien (F-253 / SF-253-08). */
const GENERIC_TITLE_WORDS: ReadonlySet<string> = new Set(['reunion', 'point', 'daily', 'weekly', 'call', 'comite', 'visio',
  'suivi', 'avec', 'pour', 'dans', 'chez', 'projet', 'atelier', 'revue', 'equipe', 'rendez', 'vous']);

/**
 * F-253 / SF-253-08 (F-248 §4.7) — les rendez-vous que nomme une phrase, sur l'appareil, sans appel : titre complet, mot
 * distinctif du titre (≥ 4 lettres, propre à un seul rendez-vous), ou heure de début dite (« 14 h 30 », « 9h »). Un
 * faux positif coûte un surlignage ; un mot partagé par deux rendez-vous n'allume rien.
 */
export function citedAgendaItems(sentence: string, items: readonly CalendarItem[]): string[] {
  const said = normalizeName(sentence);
  if (!said) {
    return [];
  }
  const timed = items.filter((i) => !i.allDay);
  const text = ` ${said} `;
  const owners = new Map<string, Set<string>>();
  for (const i of timed) {
    const title = normalizeName(i.title);
    for (const word of title.split(' ').filter((w) => w.length >= 4 && !/^\d+$/.test(w) && !GENERIC_TITLE_WORDS.has(w))) {
      owners.set(word, (owners.get(word) ?? new Set<string>()).add(i.id));
    }
  }
  const hours = new Set<string>();
  for (const m of sentence.toLowerCase().matchAll(/\b(\d{1,2})\s*(?:h|heures?)(?:\s*(\d{2}))?\b/g)) {
    hours.add(`${m[1].padStart(2, '0')}:${m[2] ?? '00'}`);
  }
  return timed.filter((i) => {
    const title = normalizeName(i.title);
    return (title.length >= 4 && text.includes(` ${title} `))
      || hours.has(parisHour(i.start))
      || title.split(' ').some((w) => owners.get(w)?.size === 1 && text.includes(` ${w} `));
  }).map((i) => i.id);
}

/** Le rendez-vous en cours, sinon le prochain (les journées entières n'en sont jamais). */
export function nextItem(items: readonly CalendarItem[], now: Date): CalendarItem | null {
  let best: CalendarItem | null = null;
  for (const i of items) {
    if (i.allDay || new Date(i.end).getTime() <= now.getTime()) {
      continue;
    }
    if (!best || new Date(i.start).getTime() < new Date(best.start).getTime()) {
      best = i;
    }
  }
  return best;
}

/** La place d'un rendez-vous sur la frise d'une journée [startMin, endMin], en pourcentages bornés à [0, 100]. */
export function friseSlot(item: CalendarItem, day: string, startMin: number, endMin: number): { left: number; width: number } {
  const span = Math.max(60, endMin - startMin);
  const from = parisDay(item.start) < day ? 0 : parisMinutes(item.start);
  const to = parisDay(item.end) > day ? 24 * 60 : parisMinutes(item.end);
  const left = Math.min(100, Math.max(0, ((from - startMin) / span) * 100));
  const right = Math.min(100, Math.max(0, ((Math.max(to, from + 15) - startMin) / span) * 100));
  return { left, width: Math.max(1.5, right - left) };
}
