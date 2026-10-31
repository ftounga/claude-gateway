import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Situation } from '../models/situation.models';

/** Le point de situation d'un terminal (F-225 / SF-225-02) : une lecture, aucun tour. 404 chez autrui. */
@Injectable({ providedIn: 'root' })
export class SituationService {
  private readonly http = inject(HttpClient);

  situation(workspaceId: string): Observable<Situation> {
    return this.http.get<Situation>(`/api/workspaces/${workspaceId}/situation`);
  }
}
