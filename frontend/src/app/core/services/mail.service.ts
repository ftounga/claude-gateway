import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { HostMailAddress } from '../models/mail.models';

/**
 * **Le courriel du client** (F-110). Aucun appel ne porte d'identifiant de compte ni de destinataire : la
 * gateway part du JWT et vérifie la possession du poste.
 */
@Injectable({ providedIn: 'root' })
export class MailService {
  private readonly http = inject(HttpClient);

  private base(hostId: string): string {
    return `/api/runner-hosts/${hostId}/mail-address`;
  }

  /** L'état de l'adresse de réception d'un client. */
  address(hostId: string): Observable<HostMailAddress> {
    return this.http.get<HostMailAddress>(this.base(hostId));
  }

  /** Déclare l'adresse : un code à 6 chiffres y est envoyé. */
  declare(hostId: string, address: string): Observable<HostMailAddress> {
    return this.http.put<HostMailAddress>(this.base(hostId), { address });
  }

  /** Vérifie le code reçu. */
  verify(hostId: string, code: string): Observable<HostMailAddress> {
    return this.http.post<HostMailAddress>(`${this.base(hostId)}/verify`, { code });
  }

  /** Renvoie un nouveau code (une fois par minute). */
  resend(hostId: string): Observable<HostMailAddress> {
    return this.http.post<HostMailAddress>(`${this.base(hostId)}/code`, null);
  }

  /** Retire l'adresse : retour au repli sur l'adresse du compte. */
  remove(hostId: string): Observable<HostMailAddress> {
    return this.http.delete<HostMailAddress>(this.base(hostId));
  }
}
