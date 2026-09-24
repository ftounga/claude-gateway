import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { TerminalAction, TerminalActionElsewhere } from '../models/terminal-actions.models';

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

  /** « Rétablir » — la fermeture s'était trompée. */
  reopen(workspaceId: string, actionId: string): Observable<TerminalAction> {
    return this.http.post<TerminalAction>(
      `/api/workspaces/${workspaceId}/actions/${actionId}/reopen`, {});
  }
}
