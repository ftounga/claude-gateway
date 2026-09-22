import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CommitmentGesture,
  RadarBoard,
  RadarBrief,
  RadarClosure,
  RadarCorrection,
  RadarDepositChunk,
  RadarDepositDone,
  RadarRecordingProgress,
  RadarDepositOpened,
  RadarDraft,
  RadarNews,
  RadarNewsUndo,
  RadarSubjectState,
  RadarSubjectSummary,
  RadarSyncStarted,
  RadarSchedule,
  RadarScheduleRequest,
  RadarThreadRule,
  RadarVerification,
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

  /** Les sujets suivis du poste (F-99), pour désigner une cible — p. ex. où pousser des actions (SF-128-06). */
  subjects(hostId: string): Observable<RadarSubjectSummary[]> {
    return this.http.get<RadarSubjectSummary[]>(`${this.base(hostId)}/subjects`);
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

  // ---------------------------------------------------------------- Donner la nouvelle (F-104 / SF-104-02)

  /** Une nouvelle, ou un courriel collé : un tour d'agent muni des outils Radar, **décompté**. */
  giveNews(hostId: string, text: string): Observable<RadarNews> {
    return this.http.post<RadarNews>(`${this.base(hostId)}/news`, { text });
  }

  /** Annule une nouvelle entière : toutes ses écritures, puis sa preuve. */
  undoNews(hostId: string, evidenceId: string): Observable<RadarNewsUndo> {
    return this.http.post<RadarNewsUndo>(`${this.base(hostId)}/news/${evidenceId}/undo`, null);
  }

  // ---------------------------------------------------------------- Déposer un enregistrement (F-104 / SF-104-04)

  /** Ouvre un dépôt sur le poste : le fichier ira sur la machine, jamais dans la gateway. */
  openDeposit(hostId: string, fileName: string, sizeBytes: number, title: string,
    recordedAt: string, subjectId: string): Observable<RadarDepositOpened> {
    return this.http.post<RadarDepositOpened>(`${this.base(hostId)}/recordings`,
      { fileName, sizeBytes, title, recordedAt, subjectId });
  }

  /** Un morceau du fichier, à sa position. */
  sendDepositChunk(hostId: string, uploadId: string, offset: number, chunk: Blob): Observable<RadarDepositChunk> {
    return this.http.put<RadarDepositChunk>(`${this.base(hostId)}/recordings/${uploadId}/chunks`, chunk, {
      params: { offset: String(offset) },
      headers: { 'Content-Type': 'application/octet-stream' },
    });
  }

  /** Termine le dépôt : le fichier apparaît dans le dossier du poste, avec son titre et sa date. */
  finishDeposit(hostId: string, uploadId: string): Observable<RadarDepositDone> {
    return this.http.post<RadarDepositDone>(`${this.base(hostId)}/recordings/${uploadId}/finish`, null);
  }

  /** Où en est la transcription lancée à la fin du dépôt (F-147 / SF-147-01). */
  recordingProgress(hostId: string, uploadId: string): Observable<RadarRecordingProgress> {
    return this.http.get<RadarRecordingProgress>(`${this.base(hostId)}/recordings/${uploadId}/status`);
  }

  /** Abandonne le dépôt. */
  abortDeposit(hostId: string, uploadId: string): Observable<void> {
    return this.http.delete<void>(`${this.base(hostId)}/recordings/${uploadId}`);
  }

  // ---------------------------------------------------------------- Relances et présentations (F-104 / SF-104-05)

  /** Prépare une relance ou une présentation : **un appel au fournisseur, décompté** ; rien n'est envoyé. */
  prepareDraft(hostId: string, commitmentId: string): Observable<RadarDraft> {
    return this.http.post<RadarDraft>(`${this.base(hostId)}/commitments/${commitmentId}/draft`, null);
  }

  // ---------------------------------------------------------------- La planification (F-100 / SF-100-07)

  /** L'heure du soir du client : activée ou non, heure, fuseau, prochaine exécution. */
  schedule(hostId: string): Observable<RadarSchedule> {
    return this.http.get<RadarSchedule>(`${this.base(hostId)}/schedule`);
  }

  /** Règle l'heure du soir ; la première activation exige l'autorisation du client. Ne lance aucune synchro. */
  updateSchedule(hostId: string, request: RadarScheduleRequest): Observable<RadarSchedule> {
    return this.http.put<RadarSchedule>(`${this.base(hostId)}/schedule`, request);
  }

  // ---------------------------------------------------------------- La vérification guidée (F-100 / SF-100-06)

  /** Demande au runner ce qu'il voit maintenant ; la gateway fusionne avec ce qu'il avait vu. */
  verify(hostId: string): Observable<RadarVerification> {
    return this.http.post<RadarVerification>(`${this.base(hostId)}/verification`, null);
  }

  /** Recommence la vérification : les cases sont décochées. */
  resetVerification(hostId: string): Observable<RadarVerification> {
    return this.http.delete<RadarVerification>(`${this.base(hostId)}/verification`);
  }

  /** Annuler un geste. */
  undo(hostId: string, correctionId: string): Observable<RadarCorrection> {
    return this.http.post<RadarCorrection>(`${this.base(hostId)}/corrections/${correctionId}/undo`, null);
  }
}
