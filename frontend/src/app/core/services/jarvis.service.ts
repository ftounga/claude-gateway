import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { JarvisAbsence, JarvisToSendList } from '../models/jarvis.models';

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
  usage(event: 'SESSION' | 'SPOKEN' | 'WAKE', seconds?: number): void {
    this.http.post<void>('/api/jarvis/usage', seconds === undefined ? { event } : { event, seconds: Math.round(seconds) })
      .subscribe({ error: () => undefined });
  }

  /** Les encarts mail ou message pas encore marqués envoyés, pour les sujets d'un poste. */
  toSend(hostId: string): Observable<JarvisToSendList> {
    return this.http.get<JarvisToSendList>('/api/jarvis/to-send', { params: new HttpParams().set('hostId', hostId) });
  }
}
