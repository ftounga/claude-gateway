import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  TerminalAction, TerminalActionBoard, TerminalActionCreate, TerminalActionEdit,
  TerminalActionElsewhere, TerminalActionStatusChange, TerminalActionSummary,
  TerminalActionReviewDecision, TerminalActionReviewItem, TerminalActionReviewResult,
  TerminalActionDuplicate, TerminalActionQualifyDecision, TerminalActionQualifyItem,
  Interlocuteur, InterlocuteurMergeSuggestion, InterlocuteurUpdate,
  AccesObtenu, AccesProposal, AccesRequest, InterlocuteurBoard, MorningPoint,
} from '../models/terminal-actions.models';

/**
 * **Les actions à faire d'un terminal** (F-154). Aucun appel ne porte d'identifiant de compte : la
 * gateway part du JWT et ne rend que ce que le compte possède.
 */
@Injectable({ providedIn: 'root' })
export class TerminalActionsService {
  private readonly http = inject(HttpClient);

  /** Les actions du terminal, les plus anciennes d'abord. Ouvertes seulement, sauf demande. */
  list(workspaceId: string, openOnly = true): Observable<TerminalAction[]> {
    const params = new HttpParams().set('openOnly', String(openOnly));
    return this.http.get<TerminalAction[]>(`/api/workspaces/${workspaceId}/actions`, { params });
  }

  /** Les actions ouvertes des **autres** projets du compte, pour la section « Ailleurs ». */
  elsewhere(excludeWorkspaceId: string): Observable<TerminalActionElsewhere[]> {
    const params = new HttpParams().set('exclude', excludeWorkspaceId);
    return this.http.get<TerminalActionElsewhere[]>('/api/terminal-actions', { params });
  }

  /** « C'est fait. » */
  close(workspaceId: string, actionId: string, reason?: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/close`, { reason: reason ?? null });
  }

  /** « Ça n'avait pas lieu d'être. » */
  cancel(workspaceId: string, actionId: string, reason?: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/cancel`, { reason: reason ?? null });
  }

  /** Le tableau : ce terminal, puis le reste du poste, et les compteurs (F-175 / SF-175-01). */
  board(workspaceId: string): Observable<TerminalActionBoard> {
    return this.http.get<TerminalActionBoard>(`/api/workspaces/${workspaceId}/actions/board`);
  }

  /** Les compteurs par poste et par terminal (F-175 / SF-175-06) : rail de la Forge, mosaïque. */
  summary(): Observable<TerminalActionSummary> {
    return this.http.get<TerminalActionSummary>('/api/terminal-actions/summary');
  }

  /** Les attentes héritées à vérifier, avec l'état proposé (F-175 / SF-175-07). */
  review(): Observable<TerminalActionReviewItem[]> {
    return this.http.get<TerminalActionReviewItem[]>('/api/terminal-actions/review');
  }

  /** Les décisions de l'utilisateur sur la reprise — rien ne change sans elles. */
  applyReview(decisions: TerminalActionReviewDecision[]): Observable<TerminalActionReviewResult> {
    return this.http.post<TerminalActionReviewResult>('/api/terminal-actions/review', { decisions });
  }

  /** Ajout à la main. */
  create(workspaceId: string, body: TerminalActionCreate): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(`/api/workspaces/${workspaceId}/actions`, body);
  }

  /** Changement d'état : À faire ↔ Demandé → Fait / Annulé. */
  changeStatus(workspaceId: string, actionId: string,
               change: TerminalActionStatusChange): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/status`, change);
  }

  /** Édition d'une attente ouverte. */
  edit(workspaceId: string, actionId: string, edit: TerminalActionEdit): Observable<TerminalAction> {
    return this.http.patch<TerminalAction>(`/api/workspaces/${workspaceId}/actions/${actionId}`, edit);
  }

  /** [Confirmer] — la fermeture proposée par l'agent est validée (F-175 / SF-175-02). */
  confirmProposal(workspaceId: string, actionId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/proposal/confirm`, {});
  }

  /** [Pas encore] — la proposition est écartée, l'attente reste ouverte. */
  dismissProposal(workspaceId: string, actionId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/proposal/dismiss`, {});
  }

  /** [Rattacher à un chantier] (F-227) : le chantier ouvert du sujet, ou `null` = au fil de l'eau. */
  attachToChantier(workspaceId: string, actionId: string, chantier: number | null): Observable<TerminalAction> {
    return this.http.put<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/chantier`, { chantier });
  }

  /** « Rétablir » — la fermeture s'était trompée. */
  reopen(workspaceId: string, actionId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/reopen`, {});
  }

  /** Le registre des accès du poste (F-180 / SF-180-04). */
  acces(workspaceId: string): Observable<AccesObtenu[]> {
    return this.http.get<AccesObtenu[]>(`/api/workspaces/${workspaceId}/acces`);
  }

  addAcces(workspaceId: string, body: AccesRequest): Observable<AccesObtenu> {
    return this.http.post<AccesObtenu>(`/api/workspaces/${workspaceId}/acces`, body);
  }

  updateAcces(workspaceId: string, id: string, body: AccesRequest): Observable<AccesObtenu> {
    return this.http.patch<AccesObtenu>(`/api/workspaces/${workspaceId}/acces/${id}`, body);
  }

  deleteAcces(workspaceId: string, id: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${workspaceId}/acces/${id}`);
  }

  /** Les attentes ACCES faites à proposer au registre. */
  accesProposals(workspaceId: string): Observable<AccesProposal[]> {
    return this.http.get<AccesProposal[]>(`/api/workspaces/${workspaceId}/acces/proposals`);
  }

  /** « Pas besoin » : l'attente n'est plus proposée. */
  dismissAccesProposal(workspaceId: string, actionId: string): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${workspaceId}/acces/proposals/${actionId}/dismiss`, {});
  }

  /** Le point du matin : relances dues, échéances, accès qui expirent (F-180 / SF-180-06). */
  morning(workspaceId: string): Observable<MorningPoint> {
    return this.http.get<MorningPoint>(`/api/workspaces/${workspaceId}/actions/morning`);
  }

  /** Qui bloque quoi, depuis quand, et les délais de réponse (F-180 / SF-180-05). */
  interlocuteurBoard(workspaceId: string): Observable<InterlocuteurBoard> {
    return this.http.get<InterlocuteurBoard>(`/api/workspaces/${workspaceId}/interlocuteurs/board`);
  }

  /** Les interlocuteurs du poste du terminal (F-180 / SF-180-03). */
  interlocuteurs(workspaceId: string): Observable<Interlocuteur[]> {
    return this.http.get<Interlocuteur[]>(`/api/workspaces/${workspaceId}/interlocuteurs`);
  }

  updateInterlocuteur(workspaceId: string, id: string, update: InterlocuteurUpdate): Observable<Interlocuteur> {
    return this.http.patch<Interlocuteur>(`/api/workspaces/${workspaceId}/interlocuteurs/${id}`, update);
  }

  interlocuteurSuggestions(workspaceId: string): Observable<InterlocuteurMergeSuggestion[]> {
    return this.http.get<InterlocuteurMergeSuggestion[]>(
      `/api/workspaces/${workspaceId}/interlocuteurs/merge-suggestions`);
  }

  mergeInterlocuteur(workspaceId: string, id: string, intoId: string): Observable<Interlocuteur> {
    return this.http.post<Interlocuteur>(`/api/workspaces/${workspaceId}/interlocuteurs/${id}/merge`, { intoId });
  }

  distinctInterlocuteurs(workspaceId: string, id: string, otherId: string): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${workspaceId}/interlocuteurs/${id}/distinct`, { otherId });
  }

  /** Les attentes ouvertes sans nature, avec la proposition (F-180 / SF-180-02). */
  qualify(): Observable<TerminalActionQualifyItem[]> {
    return this.http.get<TerminalActionQualifyItem[]>('/api/terminal-actions/qualify');
  }

  /** Les natures validées — rien ne change sans elles. */
  applyQualify(decisions: TerminalActionQualifyDecision[]): Observable<TerminalActionReviewResult> {
    return this.http.post<TerminalActionReviewResult>('/api/terminal-actions/qualify', { decisions });
  }

  /** Les doublons probables du poste, proposés à la fusion (F-180 / SF-180-01). */
  duplicates(workspaceId: string): Observable<TerminalActionDuplicate[]> {
    return this.http.get<TerminalActionDuplicate[]>(`/api/workspaces/${workspaceId}/actions/duplicates`);
  }

  /** [Fusionner] — l'attente passe « Annulé », fusionnée dans `intoId`. */
  merge(workspaceId: string, actionId: string, intoId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/merge`, { intoId });
  }

  /** [Ce n'est pas pareil] — l'attente n'est plus proposée comme doublon. */
  notDuplicate(workspaceId: string, actionId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/not-duplicate`, {});
  }
}
