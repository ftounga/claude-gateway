import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AppRequestStatus, AppRequestView } from '../models/atelier.models';

/**
 * **Le registre des besoins de l'application** (F-205 / SF-205-02) : la carte du fil ([Enregistrer],
 * [Non], statut relu) et la section Besoins de l'administration (liste, pièce, statut). Isolé côté
 * serveur : `user_id` + `requireOwned` (404 sur autrui), garde admin (403) sur `/admin/**`.
 */
@Injectable({ providedIn: 'root' })
export class AppRequestService {
  private readonly http = inject(HttpClient);

  private url(workspaceId: string, requestId: string): string {
    return `/api/workspaces/${workspaceId}/app-requests/${requestId}`;
  }

  get(workspaceId: string, requestId: string): Observable<AppRequestView> {
    return this.http.get<AppRequestView>(this.url(workspaceId, requestId));
  }

  record(workspaceId: string, requestId: string): Observable<AppRequestView> {
    return this.http.post<AppRequestView>(`${this.url(workspaceId, requestId)}/record`, {});
  }

  dismiss(workspaceId: string, requestId: string): Observable<AppRequestView> {
    return this.http.post<AppRequestView>(`${this.url(workspaceId, requestId)}/dismiss`, {});
  }

  /** Administration : les besoins de l'appelant, filtrés par statut (sans statut : tous les enregistrés). */
  list(status: AppRequestStatus | null): Observable<AppRequestView[]> {
    const params = status ? new HttpParams().set('status', status) : undefined;
    return this.http.get<AppRequestView[]>('/api/admin/app-requests', { params });
  }

  /** Administration : statut et/ou référence de feature (`''` efface la référence). */
  update(requestId: string, change: { status?: AppRequestStatus; featureRef?: string }): Observable<AppRequestView> {
    return this.http.patch<AppRequestView>(`/api/admin/app-requests/${requestId}`, change);
  }

  /** Administration : les octets d'une pièce copiée (pour un aperçu ou un téléchargement). */
  attachment(requestId: string, position: number): Observable<Blob> {
    return this.http.get(`/api/admin/app-requests/${requestId}/attachments/${position}`, { responseType: 'blob' });
  }
}

/** « 1,2 Mo », « 340 Ko », « 12 o ». */
export function sizeLabel(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} Mo`;
  }
  if (bytes >= 1024) {
    return `${Math.round(bytes / 1024)} Ko`;
  }
  return `${bytes} o`;
}

/** Libellé du type de besoin. */
export function appRequestKindLabel(kind: string): string {
  return kind === 'EVOLUTION' ? 'Évolution' : 'Bug';
}

/** Libellé d'un statut de besoin. */
export function appRequestStatusLabel(status: string): string {
  switch (status) {
    case 'PROPOSE': return 'Proposé';
    case 'EN_ATTENTE': return 'En attente';
    case 'CADRE': return 'Cadré';
    case 'REJETE': return 'Rejeté';
    case 'LIVRE': return 'Livré';
    default: return status;
  }
}
