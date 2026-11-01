import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** F-233 / SF-233-07 — un candidat de l'audition, anonymisé. */
export interface AuditionCandidate {
  label: string;
  available: boolean;
}

/** F-233 / SF-233-07 — la voix choisie, révélée. */
export interface AuditionChoice {
  label: string | null;
  provider: string;
  voice: string;
  name: string;
}

/** F-233 / SF-233-07 — l'audition : phrases fixes, candidats anonymisés, choix éventuel. */
export interface Audition {
  phrases: string[];
  candidates: AuditionCandidate[];
  chosen: AuditionChoice | null;
}

/** **L'audition à l'aveugle de la voix de Jarvis** (F-233 / SF-233-07) — routes ADMIN. */
@Injectable({ providedIn: 'root' })
export class AdminJarvisVoiceService {
  private readonly http = inject(HttpClient);

  audition(): Observable<Audition> {
    return this.http.get<Audition>('/api/admin/jarvis/audition');
  }

  /** L'échantillon d'un candidat (mp3) pour la phrase d'index `phrase`. */
  sample(label: string, phrase: number): Observable<Blob> {
    return this.http.post(`/api/admin/jarvis/audition/${encodeURIComponent(label)}/sample`, { phrase },
      { responseType: 'blob' });
  }

  choose(label: string): Observable<AuditionChoice> {
    return this.http.post<AuditionChoice>(`/api/admin/jarvis/audition/${encodeURIComponent(label)}/choose`, {});
  }

  reset(): Observable<void> {
    return this.http.delete<void>('/api/admin/jarvis/audition/choice');
  }
}
