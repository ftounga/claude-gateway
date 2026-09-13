import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';

import { ClientSpace } from '../models/atelier.models';
import {
  HostSpaces,
  VigieCommitmentSummary,
  VigiePerson,
  VigieRadarCounts,
  VigieSubjectSummary,
  VigieSyncSummary,
} from '../models/vigie.models';

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
   * Purge le Radar d'un client qu'on retire de la Vigie (F-99 / SF-99-05, raison `VIGIE_REMOVED`).
   * La confirmation est explicite : c'est l'écran qui l'a demandée.
   */
  purgeRadar(hostId: string): Observable<unknown> {
    return this.http.post(`/api/radar/hosts/${hostId}/purge`,
      { reason: 'VIGIE_REMOVED', confirm: true });
  }

  /** L'annuaire du client (F-99). */
  people(hostId: string): Observable<VigiePerson[]> {
    return this.http.get<VigiePerson[]>(`/api/radar/hosts/${hostId}/people`);
  }

  /**
   * **Les compteurs du Radar d'un client**, pour le bandeau et la colonne : relances dues, sujets
   * bloqués, dernière synchro. Trois lectures, une fois par page.
   *
   * <p><b>Silencieux</b> : un Radar illisible (droit retiré, poste hors Vigie, gateway antérieure)
   * compte zéro et « aucune synchro », jamais une erreur — le bandeau est un résumé, pas un
   * diagnostic.</p>
   */
  radarCounts(hostId: string): Observable<VigieRadarCounts> {
    const base = `/api/radar/hosts/${hostId}`;
    return forkJoin({
      followUps: this.http
        .get<VigieCommitmentSummary[]>(`${base}/commitments`, { params: { followUpDue: 'true' } })
        .pipe(catchError(() => of([] as VigieCommitmentSummary[]))),
      blocked: this.http
        .get<VigieSubjectSummary[]>(`${base}/subjects`, { params: { state: 'BLOCKED' } })
        .pipe(catchError(() => of([] as VigieSubjectSummary[]))),
      syncs: this.http
        .get<VigieSyncSummary[]>(`${base}/syncs`)
        .pipe(catchError(() => of([] as VigieSyncSummary[]))),
    }).pipe(
      map(({ followUps, blocked, syncs }) => ({
        followUpsDue: (followUps ?? []).filter((commitment) => commitment.followUpDue).length,
        blockedSubjects: (blocked ?? []).length,
        lastSync: latestSync(syncs ?? []),
      })),
    );
  }
}

/** La synchro la plus récente, par date de début. */
export function latestSync(syncs: VigieSyncSummary[]): VigieSyncSummary | null {
  return [...syncs]
    .sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? ''))[0] ?? null;
}
