import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AtelierGovernanceProposalView } from '../models/atelier.models';

/**
 * **Les propositions de gouvernance d'un terminal** (F-177 / SF-177-02) : relire le statut,
 * [Appliquer], [Refuser]. Isolées côté serveur (`user_id` + `requireOwned`, 404 sur autrui).
 */
@Injectable({ providedIn: 'root' })
export class GovernanceProposalService {
  private readonly http = inject(HttpClient);

  private url(workspaceId: string, proposalId: string): string {
    return `/api/workspaces/${workspaceId}/governance-proposals/${proposalId}`;
  }

  get(workspaceId: string, proposalId: string): Observable<AtelierGovernanceProposalView> {
    return this.http.get<AtelierGovernanceProposalView>(this.url(workspaceId, proposalId));
  }

  apply(workspaceId: string, proposalId: string): Observable<AtelierGovernanceProposalView> {
    return this.http.post<AtelierGovernanceProposalView>(`${this.url(workspaceId, proposalId)}/apply`, {});
  }

  refuse(workspaceId: string, proposalId: string): Observable<AtelierGovernanceProposalView> {
    return this.http.post<AtelierGovernanceProposalView>(`${this.url(workspaceId, proposalId)}/refuse`, {});
  }
}
