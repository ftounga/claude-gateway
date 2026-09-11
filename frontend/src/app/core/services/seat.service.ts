import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { SeatsView } from '../models/seat.models';

/**
 * Accès aux **postes comptés** (F-65 / SF-65-01). L'isolation est garantie côté backend par le
 * `user_id` porté par le JWT (ajouté par l'`authInterceptor`) : **aucun identifiant utilisateur
 * n'est transmis par le client**, et l'endpoint ne rend que les postes du porteur du jeton.
 */
@Injectable({ providedIn: 'root' })
export class SeatService {
  private readonly http = inject(HttpClient);

  /** Postes comptés pour la période courante de l'utilisateur. */
  getSeats(): Observable<SeatsView> {
    return this.http.get<SeatsView>('/api/billing/seats');
  }
}
