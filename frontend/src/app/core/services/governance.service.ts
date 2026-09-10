import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  GovernanceDepositPlan,
  GovernancePackage,
  GovernanceProject,
  GovernanceSelection,
} from '../models/governance.models';

/**
 * Accès au catalogue de gouvernance (F-51 / SF-51-05).
 *
 * Un appel par endpoint, et rien de plus : aucune règle métier ici. **Aucune de ces URL ne porte
 * d'identifiant d'utilisateur** — l'isolation est entièrement portée par la gateway, à partir du JWT.
 */
@Injectable({ providedIn: 'root' })
export class GovernanceService {
  private readonly http = inject(HttpClient);

  /** Le catalogue publié par l'admin. */
  getCatalog(): Observable<GovernancePackage[]> {
    return this.http.get<GovernancePackage[]>('/api/governance/packages');
  }

  /** Mon catalogue personnel. */
  getSelection(): Observable<GovernanceSelection[]> {
    return this.http.get<GovernanceSelection[]>('/api/governance/selection');
  }

  /** Retient un paquet, ou met à jour son drapeau « appliqué par défaut ». */
  select(packageId: string, defaultApplied: boolean): Observable<GovernanceSelection[]> {
    return this.http.put<GovernanceSelection[]>(`/api/governance/selection/${packageId}`, {
      defaultApplied,
    });
  }

  /** Retire un paquet de mon catalogue. Les projets où il est actif ne changent pas. */
  deselect(packageId: string): Observable<void> {
    return this.http.delete<void>(`/api/governance/selection/${packageId}`);
  }

  /** Ce qui s'applique à ce projet, et ce qui pourrait s'y appliquer. */
  getProject(workspaceId: string): Observable<GovernanceProject> {
    return this.http.get<GovernanceProject>(`/api/workspaces/${workspaceId}/governance`);
  }

  /**
   * Ce que ce paquet écrirait sur ce projet, et où. **N'écrit rien** : c'est l'annonce qui précède
   * l'activation.
   */
  preview(workspaceId: string, packageId: string): Observable<GovernanceDepositPlan> {
    return this.http.get<GovernanceDepositPlan>(
      `/api/workspaces/${workspaceId}/governance/${packageId}/preview`,
    );
  }

  /** Active un paquet retenu sur ce projet, et dépose ses fichiers dans la foulée. */
  activate(workspaceId: string, packageId: string): Observable<GovernanceProject> {
    return this.http.post<GovernanceProject>(
      `/api/workspaces/${workspaceId}/governance/${packageId}`,
      {},
    );
  }

  /** Rejoue le dépôt : le geste offert quand la machine était éteinte. */
  apply(workspaceId: string, packageId: string): Observable<GovernanceDepositPlan> {
    return this.http.post<GovernanceDepositPlan>(
      `/api/workspaces/${workspaceId}/governance/${packageId}/apply`,
      {},
    );
  }

  /** Désactive un paquet sur ce projet. Les fichiers déjà déposés restent. */
  deactivate(workspaceId: string, packageId: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${workspaceId}/governance/${packageId}`);
  }
}
