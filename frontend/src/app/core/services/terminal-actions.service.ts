import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  TerminalAction, TerminalActionBoard, TerminalActionCreate, TerminalActionEdit,
  TerminalActionElsewhere, TerminalActionStatusChange, TerminalActionSummary,
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

  /** « Rétablir » — la fermeture s'était trompée. */
  reopen(workspaceId: string, actionId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/reopen`, {});
  }
}
