import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * **Les mails Outlook de vos clients, côté écran** (F-249 / SF-249-07) — l'état par poste (dossier de dépôt, runner à
 * jour, dernier dépôt, rappel), les réglages (rappel du soir, alertes avant réunion), les mails répétitifs proposés et
 * les règles « ignorer ».
 */
export interface ClientMailSettings {
  reminderTime: string;
  reminderMax: number;
  reminderEveryMinutes: number;
  weekdaysOnly: boolean;
  meetingAlertMinutes: number;
}

export interface ClientMailClient {
  hostId: string;
  name: string;
  active: boolean;
  upToDate: boolean;
  folder: string;
  lastDepositAt: string | null;
  mails: number;
  depositedToday: boolean;
}

export interface IgnoreOffer {
  id: string;
  hostId: string;
  kind: 'SENDER' | 'SUBJECT';
  sample: string;
  occurrences: number;
}

export interface IgnoreRule {
  id: string;
  hostId: string;
  kind: 'SENDER' | 'SUBJECT';
  sample: string;
}

export interface ClientMailOverview {
  settings: ClientMailSettings;
  clients: ClientMailClient[];
  offers?: IgnoreOffer[];
  rules?: IgnoreRule[];
}

@Injectable({ providedIn: 'root' })
export class JarvisClientMailClient {
  private readonly http = inject(HttpClient);

  overview(): Observable<ClientMailOverview> {
    return this.http.get<ClientMailOverview>('/api/jarvis/client-mail');
  }

  saveSettings(s: ClientMailSettings): Observable<ClientMailSettings> {
    return this.http.put<ClientMailSettings>('/api/jarvis/client-mail/settings', s);
  }

  setActive(hostId: string, active: boolean): Observable<ClientMailClient> {
    return this.http.put<ClientMailClient>(`/api/jarvis/client-mail/hosts/${hostId}`, { active });
  }

  accept(offerId: string): Observable<IgnoreRule> {
    return this.http.post<IgnoreRule>(`/api/jarvis/client-mail/offers/${offerId}/accept`, {});
  }

  decline(offerId: string): Observable<void> {
    return this.http.post<void>(`/api/jarvis/client-mail/offers/${offerId}/decline`, {});
  }

  removeRule(ruleId: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/client-mail/rules/${ruleId}`);
  }
}

/** « Expéditeur » / « Objet ». */
export function ignoreKindLabel(kind: 'SENDER' | 'SUBJECT'): string {
  return kind === 'SENDER' ? 'Expéditeur' : 'Objet';
}

/** Ce qu'il faut afficher d'une valeur de réglage refusée (400). */
export function settingsError(err: { error?: { message?: string } }): string {
  return err?.error?.message ?? 'Réglage refusé.';
}
