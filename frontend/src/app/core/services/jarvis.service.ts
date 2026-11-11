import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from './auth.service';

import {
  JarvisAbsence, JarvisCompanionDevice, JarvisCompanionPairingCode, JarvisCompanionVersion, JarvisHumour,
  JarvisBudgetStatus, JarvisCaptureMeta, JarvisCostSummary, JarvisPreferences, JarvisToSendList, JarvisSttTicket, JarvisUtterance, JarvisVoiceTicket, TurnTimingReport,
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
  private readonly auth = inject(AuthService);

  /**
   * F-236 / SF-236-26 — la carte d'activité : tours par sujet et par jour sur `days` jours (≤ 7), dans le fuseau
   * de l'appareil. Agrégat en lecture seule, filtré `user_id` côté passerelle, sans contenu.
   */
  activity(days = 7, tz: string | null = null): Observable<unknown> {
    let params = new HttpParams().set('days', String(days));
    if (tz) {
      params = params.set('tz', tz);
    }
    return this.http.get<unknown>('/api/jarvis/activity', { params });
  }

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
   * F-236 / SF-236-06 — la mesure du rendu d'une ouverture de l'écran : 3D ou 2D, et des compteurs au vocabulaire
   * fermé (raisons du repli, secondes par niveau, classe d'i/s, gestes, touchers…). Aucun contenu ; best-effort.
   */
  render(report: { mode: '3d' | '2d'; render: Record<string, number> }, leaving = false): void {
    const body = { event: 'RENDER', mode: report.mode, render: report.render };
    if (leaving) {
      this.sendLeaving('/api/jarvis/usage', body);
      return;
    }
    this.http.post<void>('/api/jarvis/usage', body).subscribe({ error: () => undefined });
  }

  /**
   * F-253 / SF-253-08 (audit #25) — un envoi qui survit à la fermeture de la page (`keepalive`, hors `HttpClient` qui
   * serait annulé) ; même jeton que les autres appels. Best-effort.
   */
  private sendLeaving(url: string, body: unknown): void {
    try {
      const token = this.auth.token();
      void fetch(url, {
        method: 'POST',
        keepalive: true,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: JSON.stringify(body),
      }).catch(() => undefined);
    } catch {
      // Best-effort : la mesure ne gêne jamais la fermeture.
    }
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

  /**
   * F-257 / SF-257-03 — un ticket de session vocale pour le lien en flux (`jarvis-voice`). 503 : coupe-circuit fermé,
   * l'écran garde l'ancienne boucle.
   */
  voiceSession(workspaceId: string | null): Observable<JarvisVoiceTicket> {
    return this.http.post<JarvisVoiceTicket>('/api/jarvis/voice-session', { workspaceId });
  }

  /**
   * F-239 / SF-239-04 — retranscrit l'énoncé complet (Scribe v2 batch + vocabulaire de l'univers, côté passerelle).
   * Rien n'est conservé. Rejeté si la passerelle refuse (éteinte 503, plafond 429, échec 502).
   */
  utterance(audio: Blob, seconds: number | null): Observable<JarvisUtterance> {
    const form = new FormData();
    form.append('audio', audio, audio.type.includes('webm') ? 'enonce.webm' : 'enonce.wav');
    if (seconds !== null && Number.isFinite(seconds) && seconds > 0) {
      form.append('seconds', String(Math.round(seconds * 10) / 10));
    }
    return this.http.post<JarvisUtterance>('/api/jarvis/utterance', form);
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

  /** F-235 / SF-235-09 (D18) — les préférences de Jarvis du compte (humour : aucun / léger, léger par défaut). */
  preferences(): Observable<JarvisPreferences> {
    return this.http.get<JarvisPreferences>('/api/jarvis/preferences');
  }

  /** F-239 / SF-239-12 — ce que Jarvis a coûté aujourd'hui et ce mois-ci, en euros, par poste. */
  costs(): Observable<JarvisCostSummary> {
    return this.http.get<JarvisCostSummary>('/api/jarvis/costs');
  }

  /** F-244 / SF-244-03 — le plafond de Jarvis du compte : montant, option BYOK, dépense du mois, état. */
  budget(): Observable<JarvisBudgetStatus> {
    return this.http.get<JarvisBudgetStatus>('/api/jarvis/budget');
  }

  /** F-244 / SF-244-03 — règle le plafond (en euros) et/ou l'option « appliquer aussi en BYOK ». */
  updateBudget(change: { monthlyCapEur?: number; applyOnByok?: boolean }): Observable<JarvisBudgetStatus> {
    return this.http.put<JarvisBudgetStatus>('/api/jarvis/budget', change);
  }

  /** F-244 / SF-244-03 — le « oui » à une délégation au-delà du plafond (vaut pour la suivante, deux minutes). */
  confirmBudget(): Observable<void> {
    return this.http.post<void>('/api/jarvis/budget/confirm', {});
  }

  /** F-239 / SF-239-03 — allume ou éteint la capture des énoncés (ADMIN ; l'éteindre efface les captures). */
  setCaptureAudio(on: boolean): Observable<JarvisPreferences> {
    return this.http.put<JarvisPreferences>('/api/jarvis/preferences', { captureAudio: on });
  }

  /** F-239 / SF-239-03 — garde un énoncé pour le banc (la passerelle l'ignore sans interrupteur). Best-effort. */
  capture(audio: Blob, meta: JarvisCaptureMeta): void {
    const form = new FormData();
    // F-255 / SF-255-04 — la dictée de repli garde son type (WebM) ; le reste est du WAV.
    form.append('audio', audio, audio.type.includes('webm') ? 'enonce.webm' : 'enonce.wav');
    if (meta.seconds !== null && Number.isFinite(meta.seconds)) {
      form.append('seconds', String(Math.round(meta.seconds * 10) / 10));
    }
    form.append('listenMode', meta.listenMode);
    if (meta.provider) {
      form.append('provider', meta.provider);
    }
    form.append('streamText', meta.streamText.slice(0, 2_000));
    form.append('finalText', meta.finalText.slice(0, 2_000));
    if (meta.turnId) {
      form.append('turnId', meta.turnId);
    }
    this.http.post<void>('/api/jarvis/captures', form).subscribe({ error: () => undefined });
  }

  /** F-239 / SF-239-06 — la correction du PO devient la référence du banc pour sa dernière capture. Best-effort. */
  correctCapture(text: string): void {
    this.http.put<void>('/api/jarvis/captures/latest/correction', { text: text.slice(0, 2_000) })
      .subscribe({ error: () => undefined });
  }

  /** F-235 / SF-235-09 (D18) — enregistre le réglage d'humour du compte. */
  setHumour(humour: JarvisHumour): Observable<JarvisPreferences> {
    return this.http.put<JarvisPreferences>('/api/jarvis/preferences', { humour });
  }
}
