import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * **Le portrait de Jarvis, côté écran** (F-241 / SF-241-01).
 *
 * `/api/jarvis/portrait` : sept sections datées, souvenirs numérotés `S<n>`, interrupteur « Jarvis se sert de mon
 * portrait », compteur de taille (≤ 1 500 jetons). Tout est filtré par compte côté passerelle ; rien n'est gardé sur
 * l'appareil. Le volet n'apparaît jamais en présentation.
 */

export type PortraitSectionKey = 'QUI' | 'ENTOURAGE' | 'MOMENT' | 'GOUTS' | 'HUMOUR' | 'INTERDITS' | 'DATES';

export interface PortraitSection {
  key: PortraitSectionKey;
  title: string;
  sensitive: boolean;
  text: string;
  updatedAt: string | null;
  stale: boolean;
}

export interface PortraitSouvenir {
  id: string;
  number: number;
  text: string;
  sensitive: boolean;
  notedOn: string;
  source: 'VOIX' | 'ECRAN' | 'ENTRETIEN';
  stale: boolean;
}

export interface PortraitSize {
  chars: number;
  tokens: number;
  maxTokens: number;
  maxChars: number;
}

export interface Portrait {
  exists: boolean;
  enabled: boolean;
  version: number;
  sections: PortraitSection[];
  souvenirs: PortraitSouvenir[];
  size: PortraitSize;
  lastInterviewAt: string | null;
  interviewOffers: number;
}

/** Ce que dit la passerelle d'un refus (400 trop long, 409 mémoire pleine). */
export interface PortraitError {
  error?: { error?: string; message?: string };
  status?: number;
}

/** La borne D4 : 1 500 jetons, soit ≈ 6 000 caractères (jetons ≈ caractères / 4). */
export const PORTRAIT_MAX_TOKENS = 1500;
export const PORTRAIT_CHARS_PER_TOKEN = 4;

/** Les jetons estimés d'un nombre de caractères (même règle que la passerelle). */
export function portraitTokens(chars: number): number {
  return Math.ceil(Math.max(0, chars) / PORTRAIT_CHARS_PER_TOKEN);
}

/** La taille d'un brouillon (sections en cours d'édition + souvenirs), en caractères. */
export function draftChars(sections: Record<string, string>, souvenirs: readonly { text: string }[]): number {
  return Object.values(sections).reduce((n, t) => n + (t ?? '').length, 0)
    + souvenirs.reduce((n, s) => n + (s.text ?? '').length, 0);
}

/** Le compteur affiché : « 312 / 1 500 jetons ». */
export function counterLabel(chars: number): string {
  return `${portraitTokens(chars)} / ${PORTRAIT_MAX_TOKENS.toLocaleString('fr-FR')} jetons`;
}

/** Le message lisible d'un refus de la passerelle. */
export function portraitErrorMessage(err: PortraitError | null | undefined): string {
  const message = err?.error?.message;
  if (message) {
    return message;
  }
  if (err?.status === 503) {
    return 'Le portrait est indisponible pour le moment.';
  }
  return 'Le portrait n’a pas pu être enregistré.';
}

@Injectable({ providedIn: 'root' })
export class JarvisPortraitClient {
  private readonly http = inject(HttpClient);

  get(): Observable<Portrait> {
    return this.http.get<Portrait>('/api/jarvis/portrait');
  }

  update(sections: Partial<Record<PortraitSectionKey, string>> | null, enabled?: boolean, interviewed = false):
    Observable<Portrait> {
    return this.http.put<Portrait>('/api/jarvis/portrait', {
      ...(sections ? { sections } : {}),
      ...(enabled === undefined ? {} : { enabled }),
      ...(interviewed ? { interviewed: true } : {}),
    });
  }

  eraseAll(): Observable<void> {
    return this.http.delete<void>('/api/jarvis/portrait');
  }

  addSouvenir(text: string, sensitive = false, source: 'VOIX' | 'ECRAN' | 'ENTRETIEN' = 'ECRAN'):
    Observable<PortraitSouvenir> {
    return this.http.post<PortraitSouvenir>('/api/jarvis/portrait/souvenirs', { text, sensitive, source });
  }

  patchSouvenir(id: string, change: { text?: string; sensitive?: boolean }): Observable<PortraitSouvenir> {
    return this.http.patch<PortraitSouvenir>(`/api/jarvis/portrait/souvenirs/${id}`, change);
  }

  deleteSouvenir(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/portrait/souvenirs/${id}`);
  }

  integrate(id: string, section: PortraitSectionKey): Observable<Portrait> {
    return this.http.post<Portrait>(`/api/jarvis/portrait/souvenirs/${id}/integrate`, { section });
  }
}
