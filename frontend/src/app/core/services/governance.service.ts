import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  GovernanceDepositPlan,
  GovernanceFileComparison,
  GovernanceHost,
  GovernanceHostSummary,
  GovernancePackage,
  GovernanceSelection,
} from '../models/governance.models';

/**
 * Accès au catalogue de gouvernance (F-51 / SF-51-05, regrainé par F-75 / SF-75-03).
 *
 * Un appel par endpoint, et rien de plus : aucune règle métier ici. **Aucune de ces URL ne porte
 * d'identifiant d'utilisateur** — l'isolation est entièrement portée par la gateway, à partir du JWT.
 *
 * Le poste se désigne par son `ref` : un identifiant, ou le mot réservé `hosted` pour le poste
 * « Hébergé » (F-71), qui reste sans identifiant public.
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

  /** Retire un paquet de mon catalogue. Les postes où il est actif ne changent pas. */
  deselect(packageId: string): Observable<void> {
    return this.http.delete<void>(`/api/governance/selection/${packageId}`);
  }

  /** Mes postes gouvernables : mes machines, puis « Hébergé » s'il porte des dossiers. */
  getHosts(): Observable<GovernanceHostSummary[]> {
    return this.http.get<GovernanceHostSummary[]>('/api/governance/hosts');
  }

  /** Ce qui s'applique à ce poste, ce qui pourrait s'y appliquer, et les dossiers concernés. */
  getHost(hostRef: string): Observable<GovernanceHost> {
    return this.http.get<GovernanceHost>(`/api/governance/hosts/${hostRef}`);
  }

  /**
   * Ce que ce paquet écrirait dans chacun des dossiers de ce poste. **N'écrit rien** : c'est
   * l'annonce qui précède l'activation.
   */
  preview(hostRef: string, packageId: string): Observable<GovernanceDepositPlan> {
    return this.http.get<GovernanceDepositPlan>(
      `/api/governance/hosts/${hostRef}/${packageId}/preview`,
    );
  }

  /**
   * Le contenu d'un fichier du paquet, et ce que chaque dossier porte **déjà** sous ce chemin
   * (F-75 / SF-75-02) : de quoi lire avant d'accepter, et voir ce qui sera laissé en place.
   */
  readFile(
    hostRef: string,
    packageId: string,
    path: string,
  ): Observable<GovernanceFileComparison> {
    return this.http.get<GovernanceFileComparison>(
      `/api/governance/hosts/${hostRef}/${packageId}/file`,
      { params: new HttpParams().set('path', path) },
    );
  }

  /** Active un paquet retenu sur ce poste, et dépose ses fichiers dans chacun de ses dossiers. */
  activate(hostRef: string, packageId: string): Observable<GovernanceHost> {
    return this.http.post<GovernanceHost>(
      `/api/governance/hosts/${hostRef}/${packageId}`,
      {},
    );
  }

  /** Rejoue le dépôt : le geste offert quand la machine était éteinte. */
  apply(hostRef: string, packageId: string): Observable<GovernanceDepositPlan> {
    return this.http.post<GovernanceDepositPlan>(
      `/api/governance/hosts/${hostRef}/${packageId}/apply`,
      {},
    );
  }

  /** Désactive un paquet sur ce poste. Les fichiers déjà déposés restent. */
  deactivate(hostRef: string, packageId: string): Observable<void> {
    return this.http.delete<void>(`/api/governance/hosts/${hostRef}/${packageId}`);
  }
}
