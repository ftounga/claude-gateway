import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AppRequestKind, AppRequestStatus, AppRequestView } from '../models/atelier.models';

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

  /**
   * Administration : les besoins de l'appelant, filtrés par statut (sans statut : tous les enregistrés) et
   * par type (F-269 ; sans type : tous).
   */
  list(status: AppRequestStatus | null, kind: AppRequestKind | null = null): Observable<AppRequestView[]> {
    let params = new HttpParams();
    if (status) {
      params = params.set('status', status);
    }
    if (kind) {
      params = params.set('kind', kind);
    }
    return this.http.get<AppRequestView[]>('/api/admin/app-requests', { params });
  }

  /**
   * Administration (F-269) : enregistre une **commande pour Claude Code**, texte tel quel. Réservé à
   * l'administrateur (403 sinon) ; elle entre au registre `EN_ATTENTE`.
   */
  createCommand(text: string): Observable<AppRequestView> {
    return this.http.post<AppRequestView>('/api/admin/app-requests/commands', { text });
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
  switch (kind) {
    case 'EVOLUTION': return 'Évolution';
    case 'COMMANDE': return 'Commande';
    default: return 'Bug';
  }
}

/** Icône Material du type de besoin (F-269 : `terminal` pour une commande). */
export function appRequestKindIcon(kind: string): string {
  switch (kind) {
    case 'EVOLUTION': return 'lightbulb';
    case 'COMMANDE': return 'terminal';
    default: return 'bug_report';
  }
}

/** Libellé d'un statut de besoin. */
export function appRequestStatusLabel(status: string): string {
  switch (status) {
    case 'PROPOSE': return 'Proposé';
    case 'EN_ATTENTE': return 'En attente';
    case 'CADRE': return 'Cadré';
    case 'REJETE': return 'Rejeté';
    case 'LIVRE': return 'Livré';
    case 'JOINT': return 'Ajouté au besoin';
    default: return status;
  }
}
