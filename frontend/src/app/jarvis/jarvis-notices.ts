import { HttpClient } from '@angular/common/http';
import { InjectionToken, Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * **Les annonces d'interface** (F-249 / SF-249-08) — nouveaux mails sur un sujet, récap du matin, rappel du soir,
 * réunion imminente, mails répétitifs à trier. Lues par sondage (`/api/jarvis/notices`), fermées d'un « OK »,
 * marquées « dites » par Jarvis. Jamais un message dans le fil d'un sujet.
 */
export type NoticeKind = 'MAILS_ON_SUBJECT' | 'MORNING_RECAP' | 'DEPOSIT_REMINDER' | 'MEETING_SOON' | 'IGNORE_OFFER'
  /** F-272 / SF-272-05 — la réponse d'une délégation de Jarvis, dite si l'écran ne l'a pas dite. */
  | 'DELEGATION_DONE';

export interface JarvisNotice {
  id: string;
  kind: NoticeKind;
  text: string;
  speech: string;
  link: string | null;
  count: number;
  hostId: string | null;
  workspaceId: string | null;
  updatedAt: string;
  expiresAt: string;
  spoken: boolean;
  /** F-272 / SF-272-05 — la délégation d'une annonce `DELEGATION_DONE`. */
  delegationId?: string | null;
}

/** Cadence du sondage (60 s) : une requête légère, indexée par compte. */
export const NOTICE_POLL_MS = 60_000;
/** La cadence du sondage dans `/jarvis` (0 : pas de sondage périodique — les specs de l'écran). */
export const JARVIS_NOTICE_POLL_MS = new InjectionToken<number>('JARVIS_NOTICE_POLL_MS', {
  providedIn: 'root', factory: () => NOTICE_POLL_MS,
});
/** Annonces montrées à la fois. */
export const MAX_SHOWN = 3;

@Injectable({ providedIn: 'root' })
export class JarvisNoticesClient {
  private readonly http = inject(HttpClient);

  open(): Observable<JarvisNotice[]> {
    return this.http.get<JarvisNotice[]>('/api/jarvis/notices');
  }

  seen(id: string): Observable<JarvisNotice> {
    return this.http.post<JarvisNotice>(`/api/jarvis/notices/${id}/seen`, {});
  }

  spoken(id: string): Observable<JarvisNotice> {
    return this.http.post<JarvisNotice>(`/api/jarvis/notices/${id}/spoken`, {});
  }

  /** F-272 / SF-272-05 — l'écran a dit lui-même la fin de cette délégation : son annonce est close (ou d'avance). */
  delegationSpoken(delegationId: string): Observable<void> {
    return this.http.post<void>(`/api/jarvis/notices/delegations/${delegationId}/spoken`, {});
  }
}

/** L'icône d'une annonce (Material Icons). */
export function noticeIcon(kind: NoticeKind): string {
  switch (kind) {
    case 'MAILS_ON_SUBJECT':
      return 'mark_email_unread';
    case 'MORNING_RECAP':
      return 'wb_sunny';
    case 'DEPOSIT_REMINDER':
      return 'drive_folder_upload';
    case 'MEETING_SOON':
      return 'event';
    case 'DELEGATION_DONE':
      return 'forum';
    default:
      return 'filter_alt';
  }
}

/** Les annonces à dire par Jarvis : pas encore dites, les plus anciennes d'abord. */
export function toSpeak(list: JarvisNotice[]): JarvisNotice[] {
  return list.filter((n) => !n.spoken).sort((a, b) => a.updatedAt.localeCompare(b.updatedAt));
}
