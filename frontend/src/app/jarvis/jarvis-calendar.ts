import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

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
}
