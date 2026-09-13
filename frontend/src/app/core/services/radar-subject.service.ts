import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { RadarSubjectDetail, RadarUnknownView } from '../models/radar-subject.models';

/**
 * **La page d'un sujet du Radar** (F-103), côté HTTP.
 *
 * <p>Aucun appel ne porte d'identifiant de compte : la gateway part du JWT, vérifie la possession du
 * poste et son activation dans la Vigie, puis filtre chaque lecture sur {@code user_id} et
 * {@code host_id} (F-99 / F-106).</p>
 */
@Injectable({ providedIn: 'root' })
export class RadarSubjectService {
  private readonly http = inject(HttpClient);

  /** La page d'un sujet : valeurs, preuves de chaque valeur, résumé, personnes, chronologie. */
  subject(hostId: string, subjectId: string): Observable<RadarSubjectDetail> {
    return this.http.get<RadarSubjectDetail>(
      `/api/radar/hosts/${encodeURIComponent(hostId)}/subjects/${encodeURIComponent(subjectId)}`);
  }

  /** Ce que le Radar ne sait pas sur le sujet, et à qui le demander (SF-103-02). */
  unknowns(hostId: string, subjectId: string): Observable<RadarUnknownView[]> {
    return this.http.get<RadarUnknownView[]>(
      `/api/radar/hosts/${encodeURIComponent(hostId)}/subjects/${encodeURIComponent(subjectId)}/unknowns`);
  }
}
