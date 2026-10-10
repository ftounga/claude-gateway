import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Un événement du catalogue, tel que l'écran le règle (F-185 / SF-185-06). */
export interface NotificationEventOption {
  code: string;
  title: string;
  /** Ce qui coûte une décision (D5) : jamais coupé. */
  critical: boolean;
}

export interface NotificationPreferences {
  mutedEvents: string[];
  quietFrom: string | null;
  quietTo: string | null;
  timeZone: string;
  events: NotificationEventOption[];
}

export type NotificationPreferencesRequest = Omit<NotificationPreferences, 'events'>;

/** Le fuseau du navigateur, envoyé avec les heures calmes. */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Paris';
  } catch {
    return 'Europe/Paris';
  }
}

/** Préférences de notification du compte (F-185 / SF-185-06). */
@Injectable({ providedIn: 'root' })
export class NotificationPreferencesService {
  private readonly http = inject(HttpClient);

  get(): Observable<NotificationPreferences> {
    return this.http.get<NotificationPreferences>('/api/notifications/preferences');
  }

  save(request: NotificationPreferencesRequest): Observable<NotificationPreferences> {
    return this.http.put<NotificationPreferences>('/api/notifications/preferences', request);
  }
}
