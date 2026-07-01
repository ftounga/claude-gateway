import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface HelloResponse {
  service: string;
  status: string;
  timestamp: string;
}

/**
 * Service de fumée : interroge l'endpoint de santé du backend (GET /api/hello via proxy).
 */
@Injectable({ providedIn: 'root' })
export class HomeService {
  private readonly http = inject(HttpClient);

  hello(): Observable<HelloResponse> {
    return this.http.get<HelloResponse>('/api/hello');
  }
}
