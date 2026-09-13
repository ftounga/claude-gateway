import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CommitmentGesture,
  RadarBoard,
  RadarBrief,
  RadarClosure,
  RadarCorrection,
  RadarSubjectState,
  RadarSyncStarted,
  RadarThreadRule,
} from '../models/radar.models';

/**
 * **Le Radar d'un client**, lu et piloté depuis son onglet dans la Vigie (F-102).
 *
 * <p>Aucun appel ne porte d'identifiant de compte : la gateway part du JWT, vérifie la possession du
 * poste et son activation dans la Vigie.</p>
 */
@Injectable({ providedIn: 'root' })
export class RadarService {
  private readonly http = inject(HttpClient);

  private base(hostId: string): string {
    return `/api/radar/hosts/${hostId}`;
  }

  /** Le résumé du matin : ce qui a bougé, les compteurs, la couverture (SF-102-01). */
  brief(hostId: string): Observable<RadarBrief> {
    return this.http.get<RadarBrief>(`${this.base(hostId)}/brief`);
  }

  /** « Synchroniser maintenant » (F-100) : 202, la synchro tourne sur la machine. */
  syncNow(hostId: string): Observable<RadarSyncStarted> {
    return this.http.post<RadarSyncStarted>(`${this.base(hostId)}/syncs`, null);
  }

  /** Annule la synchro en cours ; ce qui a été lu est conservé. */
  cancelSync(hostId: string, syncId: string): Observable<unknown> {
    return this.http.post(`${this.base(hostId)}/syncs/${syncId}/cancel`, null);
  }

  /** Les règles posées sur les fils du client. */
  threadRules(hostId: string): Observable<RadarThreadRule[]> {
    return this.http.get<RadarThreadRule[]>(`${this.base(hostId)}/thread-rules`);
  }

  /** *Ignorer ce fil* ou *lire ce canal* : une correction souveraine, annulable. */
  addThreadRule(hostId: string, conversationRef: string, rule: 'IGNORE' | 'READ_CHANNEL',
    label: string | null): Observable<RadarThreadRule> {
    return this.http.post<RadarThreadRule>(`${this.base(hostId)}/thread-rules`, { conversationRef, rule, label });
  }

  removeThreadRule(hostId: string, ruleId: string): Observable<void> {
    return this.http.delete<void>(`${this.base(hostId)}/thread-rules/${ruleId}`);
  }

  // ---------------------------------------------------------------- les trois colonnes (SF-102-02)

  /** À faire par moi · sujets en cours · j'attends des autres. */
  board(hostId: string): Observable<RadarBoard> {
    return this.http.get<RadarBoard>(`${this.base(hostId)}/board`);
  }

  /** *Fait*, *Pas moi*, *Reporter* (avec la nouvelle échéance), *C'est moi*, *Abandonner* — souverains (F-99). */
  correctCommitment(hostId: string, commitmentId: string, action: CommitmentGesture,
    dueDate: string | null = null): Observable<RadarCorrection> {
    return this.http.post<RadarCorrection>(`${this.base(hostId)}/commitments/${commitmentId}/corrections`,
      dueDate === null ? { action } : { action, dueDate });
  }

  /** *Clore* un sujet : immédiat ; rend ses engagements encore ouverts. */
  closeSubject(hostId: string, subjectId: string): Observable<RadarClosure> {
    return this.http.post<RadarClosure>(`${this.base(hostId)}/subjects/${subjectId}/close`, null);
  }

  /** Confirmer un « clos ? ». */
  confirmClosure(hostId: string, subjectId: string): Observable<RadarClosure> {
    return this.http.post<RadarClosure>(`${this.base(hostId)}/subjects/${subjectId}/close-proposal/confirm`, null);
  }

  /** Refuser un « clos ? » : le sujet reste ouvert. */
  rejectClosure(hostId: string, subjectId: string): Observable<RadarCorrection> {
    return this.http.post<RadarCorrection>(`${this.base(hostId)}/subjects/${subjectId}/close-proposal/reject`, null);
  }

  /** Laisser clos un sujet qui se réveille. */
  dismissWake(hostId: string, subjectId: string): Observable<RadarCorrection> {
    return this.http.post<RadarCorrection>(`${this.base(hostId)}/subjects/${subjectId}/wake/dismiss`, null);
  }

  /** Dire l'état d'un sujet — y compris le rouvrir. */
  setSubjectState(hostId: string, subjectId: string, state: RadarSubjectState): Observable<RadarCorrection> {
    return this.http.post<RadarCorrection>(`${this.base(hostId)}/subjects/${subjectId}/corrections`,
      { action: 'SET_STATE', state });
  }

  /** Annuler un geste. */
  undo(hostId: string, correctionId: string): Observable<RadarCorrection> {
    return this.http.post<RadarCorrection>(`${this.base(hostId)}/corrections/${correctionId}/undo`, null);
  }
}
