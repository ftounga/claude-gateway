import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { GovernanceControl } from '../core/models/governance.models';
import { GovernancePackageAdmin, GovernancePackageDraft } from './governance-admin.models';

/**
 * Rédaction et publication des paquets de gouvernance (F-51 / SF-51-06).
 *
 * L'autorisation réelle (rôle ADMIN) est appliquée côté backend, par la garde unique du produit.
 * Cet écran ne la rejoue pas : il **affiche** son refus.
 */
@Injectable({ providedIn: 'root' })
export class GovernanceAdminService {
  private readonly http = inject(HttpClient);

  /** Tous les paquets, brouillons compris, contenu compris. */
  list(): Observable<GovernancePackageAdmin[]> {
    return this.http.get<GovernancePackageAdmin[]>('/api/admin/governance/packages');
  }

  /**
   * Les contrôles que **le serveur** fournit — la seule liste qu'un paquet a le droit de citer.
   *
   * C'est aussi ce qui permet à l'écran de proposer un choix plutôt qu'une saisie libre : le backend
   * refuse un identifiant inconnu, autant ne jamais laisser l'occasion d'en taper un.
   */
  controls(): Observable<GovernanceControl[]> {
    return this.http.get<GovernanceControl[]>('/api/admin/governance/controls');
  }

  /** Crée un paquet, non publié, en version 1. */
  create(draft: GovernancePackageDraft): Observable<GovernancePackageAdmin> {
    return this.http.post<GovernancePackageAdmin>('/api/admin/governance/packages', draft);
  }

  /** Remplace **intégralement** le contenu d'un paquet et incrémente sa version. */
  update(id: string, draft: GovernancePackageDraft): Observable<GovernancePackageAdmin> {
    return this.http.put<GovernancePackageAdmin>(`/api/admin/governance/packages/${id}`, draft);
  }

  /** Publie : le paquet entre dans le catalogue de tous. */
  publish(id: string): Observable<GovernancePackageAdmin> {
    return this.http.post<GovernancePackageAdmin>(
      `/api/admin/governance/packages/${id}/publish`,
      {},
    );
  }

  /** Dépublie : il en sort, sans toucher aux projets qui l'appliquent déjà. */
  unpublish(id: string): Observable<GovernancePackageAdmin> {
    return this.http.post<GovernancePackageAdmin>(
      `/api/admin/governance/packages/${id}/unpublish`,
      {},
    );
  }

  /** Supprime un paquet **non publié**. Le backend refuse les autres. */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/admin/governance/packages/${id}`);
  }
}
