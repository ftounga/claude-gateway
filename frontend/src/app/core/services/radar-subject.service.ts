import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  RadarManagerAnswer,
  RadarNewsUndoResult,
  RadarSubjectProjects,
  RadarSubjectDetail,
  RadarUnknownView,
} from '../models/radar-subject.models';

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

  /**
   * Prépare la réponse pour le manager (SF-103-03). **Un appel au fournisseur, décompté** : l'écran ne
   * l'émet que sur un geste.
   */
  managerAnswer(hostId: string, subjectId: string): Observable<RadarManagerAnswer> {
    return this.http.post<RadarManagerAnswer>(
      `/api/radar/hosts/${encodeURIComponent(hostId)}/subjects/${encodeURIComponent(subjectId)}/manager-answer`, null);
  }

  /** Annule une nouvelle (F-104 / SF-104-02) : toutes ses écritures, puis sa preuve. */
  /** Les projets liés au sujet, les propositions et les projets liables (F-106 / SF-106-06). */
  projects(hostId: string, subjectId: string): Observable<RadarSubjectProjects> {
    return this.http.get<RadarSubjectProjects>(
      `/api/radar/hosts/${encodeURIComponent(hostId)}/subjects/${encodeURIComponent(subjectId)}/projects`);
  }

  /** Lie le sujet au projet, ou confirme la proposition. */
  linkProject(hostId: string, subjectId: string, workspaceId: string): Observable<RadarSubjectProjects> {
    return this.http.put<RadarSubjectProjects>(this.projectUrl(hostId, subjectId, workspaceId), null);
  }

  /** Délie, ou refuse la proposition : la paire ne sera plus proposée. */
  unlinkProject(hostId: string, subjectId: string, workspaceId: string): Observable<RadarSubjectProjects> {
    return this.http.delete<RadarSubjectProjects>(this.projectUrl(hostId, subjectId, workspaceId));
  }

  private projectUrl(hostId: string, subjectId: string, workspaceId: string): string {
    return `/api/radar/hosts/${encodeURIComponent(hostId)}/subjects/${encodeURIComponent(subjectId)}`
      + `/projects/${encodeURIComponent(workspaceId)}`;
  }

  undoNews(hostId: string, evidenceId: string): Observable<RadarNewsUndoResult> {
    return this.http.post<RadarNewsUndoResult>(
      `/api/radar/hosts/${encodeURIComponent(hostId)}/news/${encodeURIComponent(evidenceId)}/undo`, null);
  }
}
