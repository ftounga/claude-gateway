import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Routine, RoutineRequest, RoutineRun } from '../models/routine.models';

/**
 * **Les routines d'un sujet** (F-183). Aucun appel ne porte d'identifiant de compte : la gateway part
 * du JWT, relit le sujet par `requireOwned` et ne rend que ce que le compte possède.
 */
@Injectable({ providedIn: 'root' })
export class RoutineService {
  private readonly http = inject(HttpClient);

  private base(workspaceId: string): string {
    return `/api/workspaces/${workspaceId}/routines`;
  }

  /** Les routines du sujet, dans l'ordre de leur déclaration. */
  list(workspaceId: string): Observable<Routine[]> {
    return this.http.get<Routine[]>(this.base(workspaceId));
  }

  /** Déclare une routine. */
  create(workspaceId: string, request: RoutineRequest): Observable<Routine> {
    return this.http.post<Routine>(this.base(workspaceId), request);
  }

  /** Modifie une routine (un champ absent garde sa valeur). */
  update(workspaceId: string, routineId: string, request: RoutineRequest): Observable<Routine> {
    return this.http.put<Routine>(`${this.base(workspaceId)}/${routineId}`, request);
  }

  /** Supprime une routine — et refuse ainsi une proposition de l'agent. */
  remove(workspaceId: string, routineId: string): Observable<void> {
    return this.http.delete<void>(`${this.base(workspaceId)}/${routineId}`);
  }

  /** « Lancer maintenant » (SF-183-02) : l'exécution part en tâche de fond. */
  runNow(workspaceId: string, routineId: string): Observable<RoutineRun> {
    return this.http.post<RoutineRun>(`${this.base(workspaceId)}/${routineId}/run`, {});
  }

  /** Les 20 dernières exécutions d'une routine (SF-183-02). */
  history(workspaceId: string, routineId: string): Observable<RoutineRun[]> {
    return this.http.get<RoutineRun[]>(`${this.base(workspaceId)}/${routineId}/runs`);
  }

  /** Valide une proposition de l'agent. */
  accept(workspaceId: string, routineId: string): Observable<Routine> {
    return this.http.post<Routine>(`${this.base(workspaceId)}/${routineId}/accept`, {});
  }
}
