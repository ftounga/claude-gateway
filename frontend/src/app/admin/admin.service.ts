import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AdminUser } from './admin.models';

/** Accès à l'API d'administration (F-20). L'autorisation réelle (ADMIN) est appliquée côté backend. */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  /** Liste des utilisateurs avec abonnement et consommation (ADMIN uniquement). */
  getUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>('/api/admin/users');
  }
}
