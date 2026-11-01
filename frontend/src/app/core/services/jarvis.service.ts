import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  JarvisAbsence, JarvisCompanionDevice, JarvisCompanionPairingCode, JarvisCompanionVersion, JarvisToSendList,
  JarvisSttTicket, TurnTimingReport,
} from '../models/jarvis.models';

/** F-232 / SF-232-02 — l'APK du compagnon Android, servi par le chemin des paquets du runner (F-44). */
export const JARVIS_COMPANION_DOWNLOAD_PATH = '/api/runner/download/android';

/**
 * **Le mode Jarvis** (F-231), côté passerelle.
 *
 * SF-231-06 — de retour au terminal : ce que Jarvis a fait depuis la dernière visite, et les messages qui attendent
 * d'être envoyés. **Jarvis n'envoie jamais rien à un tiers** : il prépare, le PO envoie.
 */
@Injectable({ providedIn: 'root' })
export class JarvisService {
  private readonly http = inject(HttpClient);

  /** Ce que Jarvis a fait sur ce terminal depuis `since` (dernière visite de l'appareil), sinon les dernières 24 h. */
  absence(workspaceId: string, since: string | null): Observable<JarvisAbsence> {
    const params = since ? new HttpParams().set('since', since) : undefined;
    return this.http.get<JarvisAbsence>(`/api/workspaces/${workspaceId}/jarvis/absence`, { params });
  }

  /**
   * SF-231-05 — la mesure d'adoption (D9) : une ouverture (`SESSION`), des secondes parlées (`SPOKEN`), un réveil par
   * le mot « Jarvis » (`WAKE`). Aucun contenu ; best-effort, jamais une raison de gêner l'écran.
   */
  usage(event: 'SESSION' | 'SPOKEN' | 'WAKE' | 'IDLE_END' | 'TOUCH' | 'SOBER', seconds?: number): void {
    this.http.post<void>('/api/jarvis/usage', seconds === undefined ? { event } : { event, seconds: Math.round(seconds) })
      .subscribe({ error: () => undefined });
  }

  /**
   * F-234 / SF-234-11 (D16) — ce que l'écran vivant a montré pendant un tour : compte par identifiant de scène, nœuds
   * allumés, demandes « Montrer ». Aucun contenu ; best-effort. Rien n'est envoyé pour un tour sans scène.
   */
  scenes(stats: { scenes: Record<string, number>; lit: number; shows: number }): void {
    if (Object.keys(stats.scenes).length === 0 && stats.lit === 0 && stats.shows === 0) {
      return;
    }
    this.http.post<void>('/api/jarvis/usage', { event: 'SCENES', scenes: stats.scenes, lit: stats.lit, shows: stats.shows })
      .subscribe({ error: () => undefined });
  }

  /**
   * F-233 / SF-233-01 — la mesure d'un tour vocal (marques en ms depuis la fin de parole, aucun contenu).
   * Best-effort : un envoi raté ne gêne jamais Jarvis.
   */
  timings(report: TurnTimingReport): void {
    this.http.post<void>('/api/jarvis/timings', report).subscribe({ error: () => undefined });
  }

  /**
   * F-233 / SF-233-04 — une session d'écoute en flux (jeton éphémère). 503 : le flux n'est pas configuré, l'écran
   * reste sur la dictée.
   */
  sttSession(): Observable<JarvisSttTicket> {
    return this.http.post<JarvisSttTicket>('/api/jarvis/stt-session', {});
  }

  /** F-233 / SF-233-04 — clôt la session et déclare sa durée (comptée, bornée par la passerelle). Best-effort. */
  closeSttSession(sessionId: string, seconds: number): void {
    this.http.post<void>(`/api/jarvis/stt-session/${sessionId}/close`, { seconds: Math.max(0, Math.round(seconds)) })
      .subscribe({ error: () => undefined });
  }

  /** Les encarts mail ou message pas encore marqués envoyés, pour les sujets d'un poste. */
  toSend(hostId: string): Observable<JarvisToSendList> {
    return this.http.get<JarvisToSendList>('/api/jarvis/to-send', { params: new HttpParams().set('hostId', hostId) });
  }

  /** F-232 / SF-232-02 — la version du compagnon Android que cette gateway sert (route publique). */
  companion(): Observable<JarvisCompanionVersion> {
    return this.http.get<JarvisCompanionVersion>(`${JARVIS_COMPANION_DOWNLOAD_PATH}/version`);
  }

  /** F-232 / SF-232-03 — un code d'appairage pour le compagnon (annonces téléphone verrouillé). */
  companionPairingCode(): Observable<JarvisCompanionPairingCode> {
    return this.http.post<JarvisCompanionPairingCode>('/api/jarvis/companions/pairing-code', {});
  }

  /** F-232 / SF-232-03 — les compagnons appairés au compte. */
  companions(): Observable<JarvisCompanionDevice[]> {
    return this.http.get<JarvisCompanionDevice[]>('/api/jarvis/companions');
  }

  /** F-232 / SF-232-03 — révoque un compagnon : son jeton est refusé dès sa relève suivante. */
  revokeCompanion(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/companions/${id}`);
  }
}
