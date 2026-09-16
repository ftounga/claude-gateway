import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Le TJM d'un poste (F-124) : identifiant du poste et taux journalier en centimes d'euro HT. */
export interface PosteRate {
  hostId: string;
  dailyRateCents: number;
}

/** Le réglage de suivi d'activité (F-124) : le mois de départ du cumul, au format `YYYY-MM`. */
export interface ActivitySettings {
  startMonth: string;
}

/** Le cumul de revenu d'un poste (F-124 / SF-124-02), montants en centimes d'euro HT. */
export interface PosteRevenue {
  hostId: string;
  tjmCents: number;
  cumulCents: number;
  declaredCents: number;
  supposedCents: number;
}

/** Le cumul de revenu de l'utilisateur (F-124 / SF-124-02) : par poste, et le total tous clients. */
export interface RevenueSummary {
  startMonth: string;
  currentMonth: string;
  totalCents: number;
  totalDeclaredCents: number;
  totalSupposedCents: number;
  postes: PosteRevenue[];
}

/**
 * Configuration du suivi d'activité et de revenu (F-124) : le TJM par poste et le mois de départ du
 * cumul. Comme partout, l'identité de l'utilisateur voyage dans le jeton (intercepteur), jamais dans
 * un corps ni un paramètre.
 */
@Injectable({ providedIn: 'root' })
export class PosteBillingService {
  private readonly http = inject(HttpClient);

  /** Le mois de départ du cumul (défaut `2025-09` côté serveur si rien n'est réglé). */
  settings(): Observable<ActivitySettings> {
    return this.http.get<ActivitySettings>('/api/activity/settings');
  }

  /** Fixe le mois de départ du cumul (format `YYYY-MM`). */
  setStartMonth(startMonth: string): Observable<ActivitySettings> {
    return this.http.put<ActivitySettings>('/api/activity/settings', { startMonth });
  }

  /** Les TJM des postes de l'utilisateur (un par poste ayant un TJM réglé). */
  rates(): Observable<PosteRate[]> {
    return this.http.get<PosteRate[]>('/api/activity/rates');
  }

  /** Fixe le TJM (centimes d'euro HT) d'un poste possédé. */
  setRate(hostId: string, dailyRateCents: number): Observable<PosteRate> {
    return this.http.put<PosteRate>(`/api/activity/rates/${hostId}`, { dailyRateCents });
  }

  /** Retire le TJM d'un poste possédé. */
  clearRate(hostId: string): Observable<void> {
    return this.http.delete<void>(`/api/activity/rates/${hostId}`);
  }

  /** Le cumul de revenu (F-124 / SF-124-02) : par poste, et le total tous clients. */
  revenue(): Observable<RevenueSummary> {
    return this.http.get<RevenueSummary>('/api/activity/revenue');
  }
}
