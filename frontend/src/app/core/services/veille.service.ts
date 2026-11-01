import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { JarvisAlert, JarvisWatch, JarvisWatchRun, JarvisWatchTest } from '../models/veille.models';

/**
 * **Les veilles Jarvis d'un sujet** (F-230). Aucun appel ne porte d'identifiant de compte : la gateway part du
 * JWT, relit le sujet par `requireOwned` et ne rend que ce que le compte possède.
 */
@Injectable({ providedIn: 'root' })
export class VeilleService {
  private readonly http = inject(HttpClient);

  private base(workspaceId: string): string {
    return `/api/workspaces/${workspaceId}/watches`;
  }

  /** Les veilles du sujet, dans l'ordre de leur pose. */
  list(workspaceId: string): Observable<JarvisWatch[]> {
    return this.http.get<JarvisWatch[]>(this.base(workspaceId));
  }

  /** Tester la sonde devant le PO : une exécution sur le poste. */
  test(workspaceId: string, watchId: string): Observable<JarvisWatchTest> {
    return this.http.post<JarvisWatchTest>(`${this.base(workspaceId)}/${watchId}/test`, {});
  }

  /** Activer une veille testée. */
  accept(workspaceId: string, watchId: string): Observable<JarvisWatch> {
    return this.http.post<JarvisWatch>(`${this.base(workspaceId)}/${watchId}/accept`, {});
  }

  /** « Lancer maintenant » (SF-230-02) : rend le passage terminé. */
  runNow(workspaceId: string, watchId: string): Observable<JarvisWatchRun> {
    return this.http.post<JarvisWatchRun>(`${this.base(workspaceId)}/${watchId}/run`, {});
  }

  /** Les 20 derniers passages (SF-230-02). */
  history(workspaceId: string, watchId: string): Observable<JarvisWatchRun[]> {
    return this.http.get<JarvisWatchRun[]>(`${this.base(workspaceId)}/${watchId}/runs`);
  }

  /** Une alerte de veille (SF-230-03). */
  alert(alertId: string): Observable<JarvisAlert> {
    return this.http.get<JarvisAlert>(`/api/jarvis/alerts/${alertId}`);
  }

  /** Le PO a ouvert l'alerte (mesure d'adoption, D8). */
  openAlert(alertId: string): Observable<void> {
    return this.http.post<void>(`/api/jarvis/alerts/${alertId}/open`, {});
  }

  /** Supprimer une veille — et refuser ainsi une proposition. */
  remove(workspaceId: string, watchId: string): Observable<void> {
    return this.http.delete<void>(`${this.base(workspaceId)}/${watchId}`);
  }
}
