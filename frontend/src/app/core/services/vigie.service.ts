import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { ClientSpace } from '../models/atelier.models';
import { RadarBrief } from '../models/radar.models';
import { RadarProjectSubjects } from '../models/radar-subject.models';
import {
  HostSpaces,
  RadarPurgeReason,
  VigiePerson,
  VigieRadarCounts,
  VigieSyncSummary,
} from '../models/vigie.models';
import { VigieReadiness } from '../models/vigie-readiness.models';

/**
 * **La Vigie et les espaces d'un client** (F-106 / SF-106-02).
 *
 * <p>Deux familles d'appels. Les <b>espaces</b> (F-106 / SF-106-01) : lire, activer, retirer. Et les
 * lectures du <b>Radar</b> (F-99) dont la Vigie a besoin avant que l'écran du Radar n'existe
 * (F-102) : les compteurs du bandeau et l'annuaire. Aucun appel ne porte d'identifiant de compte :
 * la gateway part du JWT, et vérifie la possession de chaque poste.</p>
 */
@Injectable({ providedIn: 'root' })
export class VigieService {
  private readonly http = inject(HttpClient);

  /** Tous les postes du compte et leurs espaces. */
  hostSpaces(): Observable<HostSpaces[]> {
    return this.http.get<HostSpaces[]>('/api/runner-hosts/spaces');
  }

  /** Active un client dans un espace — aucun appairage. Idempotent. */
  activate(hostId: string, space: ClientSpace): Observable<HostSpaces> {
    return this.http.put<HostSpaces>(`/api/runner-hosts/${hostId}/spaces/${space}`, null);
  }

  /** Retire un client d'un espace — rien n'est supprimé ailleurs ; le dernier espace est refusé (409). */
  remove(hostId: string, space: ClientSpace): Observable<HostSpaces> {
    return this.http.delete<HostSpaces>(`/api/runner-hosts/${hostId}/spaces/${space}`);
  }

  /**
   * Purge le Radar d'un client (F-99 / SF-99-05) : retiré de la Vigie (`VIGIE_REMOVED`) ou mission
   * clôturée (`MISSION_CLOSED`, SF-99-07). La confirmation est explicite : c'est l'écran qui l'a demandée,
   * après avoir proposé l'export.
   */
  purgeRadar(hostId: string, reason: RadarPurgeReason = 'VIGIE_REMOVED'): Observable<unknown> {
    return this.http.post(`/api/radar/hosts/${hostId}/purge`, { reason, confirm: true });
  }

  /**
   * **L'export Markdown du Radar d'un client** (F-99 / SF-99-07) : la réponse entière, pour lire le nom
   * de fichier de `Content-Disposition`. Sans droit d'option : récupérer ses données ne dépend pas d'un
   * abonnement.
   */
  exportRadar(hostId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`/api/radar/hosts/${hostId}/export`, { responseType: 'blob', observe: 'response' });
  }

  /** L'annuaire du client (F-99). */
  people(hostId: string): Observable<VigiePerson[]> {
    return this.http.get<VigiePerson[]>(`/api/radar/hosts/${hostId}/people`);
  }

  /**
   * **Les compteurs du Radar d'un client**, pour le bandeau, la colonne et l'onglet : relances dues,
   * sujets bloqués, ce qui est à traiter, la synchro à dire (en cours d'abord). **Une lecture** du
   * résumé du matin (F-102 / SF-102-03), une fois par page.
   *
   * <p><b>Silencieux</b> : un Radar illisible (droit retiré, poste hors Vigie, gateway antérieure)
   * compte zéro et « aucune synchro », jamais une erreur — le bandeau est un résumé, pas un
   * diagnostic.</p>
   */
  /**
   * **Les sujets de la Vigie liés à chaque projet** d'un client (F-106 / SF-106-06) — la passerelle que la
   * Forge pose sur la tuile d'un projet. <b>Silencieux</b> : droit Vigie absent, client hors Vigie ou
   * gateway antérieure rendent une liste vide, et la tuile ne dit rien.
   */
  projectSubjects(hostId: string): Observable<RadarProjectSubjects[]> {
    return this.http.get<RadarProjectSubjects[]>(`/api/radar/hosts/${encodeURIComponent(hostId)}/project-subjects`)
      .pipe(catchError(() => of([] as RadarProjectSubjects[])));
  }

  radarCounts(hostId: string): Observable<VigieRadarCounts> {
    return this.http.get<RadarBrief>(`/api/radar/hosts/${hostId}/brief`).pipe(
      map((brief) => countsOfBrief(brief)),
      catchError(() => of({ followUpsDue: 0, blockedSubjects: 0, toHandle: 0, lastSync: null })),
    );
  }

  /**
   * **La check-list de mise en service de la Vigie** (F-122 / SF-122-02) : les quatre vérifications
   * vert/rouge/en attente, à passer avant de démarrer.
   */
  readiness(hostId: string): Observable<VigieReadiness> {
    return this.http.get<VigieReadiness>(
      `/api/runner-hosts/${encodeURIComponent(hostId)}/vigie/readiness`,
    );
  }
}

/** Les compteurs d'un client, tirés de son résumé du matin. */
export function countsOfBrief(brief: RadarBrief | null | undefined): VigieRadarCounts {
  const sync = brief?.running ?? brief?.lastSync ?? null;
  return {
    followUpsDue: brief?.counts?.followUpsDue ?? 0,
    blockedSubjects: brief?.counts?.blockedSubjects ?? 0,
    toHandle: brief?.counts?.toHandle ?? 0,
    lastSync: sync === null
      ? null
      : { id: sync.id, status: sync.status, startedAt: sync.startedAt, finishedAt: sync.finishedAt },
  };
}

/** La synchro la plus récente, par date de début. */
export function latestSync(syncs: VigieSyncSummary[]): VigieSyncSummary | null {
  return [...syncs]
    .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))[0] ?? null;
}
