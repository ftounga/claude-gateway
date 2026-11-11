import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** F-255 — le bilan d'une séance (calculé sans IA par la passerelle). */
export interface ReplayBilan {
  turns: number;
  heard: number;
  lost: number;
  lostRate: number | null;
  repeated: number;
  slow: number;
  firstSoundP50: number | null;
  firstSoundP90: number | null;
  wrongSubject: number;
  shown: number;
  voiciWithoutShow: number;
  delegations: number;
  silentDelegations: number;
  streamFallbacks: number;
  streamFallbackRate: number | null;
  unmeasured: number;
}

/** F-255 — une séance : des tours séparés de moins de 10 minutes. */
export interface ReplaySession {
  from: string;
  to: string;
  pages: number;
  turns: number;
  bilan: ReplayBilan;
}

export interface ReplaySubject {
  id: string;
  name: string | null;
}

export interface ReplayDelegation {
  at: string;
  subject: ReplaySubject | null;
  chantier: number | null;
  status: string | null;
  label: string | null;
  reply: string | null;
}

export interface ReplayShow {
  action: string;
  refused: boolean;
  reason: string | null;
}

export interface ReplayVerdict {
  code: string;
  detail: string | null;
}

/** F-255 — un tour rejoué. */
export interface ReplayTurn {
  number: number;
  turnId: string | null;
  pageId: string;
  correlation: 'IDENTIFIANT' | 'ESTIMEE';
  at: string;
  listenMode: string | null;
  heard: {
    chars: number | null;
    rejected: string | null;
    streamText: string | null;
    finalText: string | null;
    userText: string | null;
    captureId: string | null;
    captureSeconds: number | null;
  };
  steps: {
    speechMs: number | null;
    endSilenceMs: number | null;
    transcriptMs: number | null;
    sentMs: number | null;
    firstTokenMs: number | null;
    firstSoundMs: number | null;
    doneMs: number | null;
  };
  brain: string | null;
  speculation: string | null;
  delegationReason: string | null;
  emptyDiagnostic: string | null;
  saidChars: number | null;
  reply: string | null;
  screenSubject: ReplaySubject | null;
  delegations: ReplayDelegation[];
  shows: ReplayShow[];
  streamFallbacks: string[];
  streamError: boolean;
  measured: boolean;
  verdicts: ReplayVerdict[];
  events: string[];
}

export interface Replay {
  from: string;
  to: string;
  pages: number;
  turns: ReplayTurn[];
  bilan: ReplayBilan | null;
  thresholds: { slowFirstSoundMs: number; repeatWindow: number; sessionGapMinutes: number };
}

/** **Le rejeu des séances Jarvis** (F-255 / SF-255-03) — routes ADMIN, sur les séances du compte. */
@Injectable({ providedIn: 'root' })
export class AdminJarvisReplayService {
  private readonly http = inject(HttpClient);

  sessions(days = 7): Observable<ReplaySession[]> {
    return this.http.get<ReplaySession[]>('/api/admin/jarvis/replay/sessions', {
      params: new HttpParams().set('days', days),
    });
  }

  replay(from: string, to: string): Observable<Replay> {
    return this.http.get<Replay>('/api/admin/jarvis/replay', { params: new HttpParams().set('from', from).set('to', to) });
  }

  /** L'énoncé gardé (route existante des captures, F-239). */
  audio(captureId: string): Observable<Blob> {
    return this.http.get(`/api/admin/jarvis/captures/${encodeURIComponent(captureId)}/audio`, { responseType: 'blob' });
  }
}
