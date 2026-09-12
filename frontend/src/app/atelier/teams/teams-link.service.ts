import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Les trois états de la liaison Teams (F-87 / SF-87-03). Liste close, partagée avec le runner. */
export type TeamsLinkState = 'LINKED' | 'BROWSER_NOT_DETECTED' | 'TEAMS_CHANGED';

/**
 * Ce que l'écran sait de la liaison Teams. **Un état, et de quoi l'écrire** — aucune action :
 * l'indicateur dit où l'on en est, il ne répare rien.
 */
export interface TeamsLink {
  readonly state: TeamsLinkState;
  /** Libellé **toujours** écrit à côté de l'indicateur. */
  readonly label: string;
  /** Ce qui est dit à l'utilisateur. */
  readonly sentence: string;
  /** Ce qu'il peut faire — la ligne de commande, le cas échéant. Vide s'il n'y a rien à faire. */
  readonly remedy: string;
  readonly browser: string;
  readonly healthVerdict: string;
  readonly recognizedFields: number;
  readonly expectedFields: number;
  readonly missingFields: readonly string[];
  readonly observedApiVersions: readonly string[];
  /** Vrai si la sonde a réellement vu passer une réponse de Teams. */
  readonly conclusive: boolean;
}

/**
 * Relevé de la liaison Teams d'un projet (F-87 / SF-87-03).
 *
 * <p><b>Relevé à la demande</b>, comme l'état du runner : aucun canal poussé n'est ouvert pour cette
 * information. Et volontairement <b>peu fréquent</b> — chaque relevé fait jouer la sonde sur la
 * machine de l'utilisateur, laquelle demande un geste à sa fenêtre Teams.</p>
 */
@Injectable({ providedIn: 'root' })
export class TeamsLinkService {

  private readonly http = inject(HttpClient);

  /**
   * Où en est la liaison de ce projet. Répond toujours 200 quand le projet est celui de
   * l'utilisateur : une machine éteinte ou un navigateur non lancé sont des **états**.
   */
  getLink(workspaceId: string): Observable<TeamsLink> {
    return this.http.get<TeamsLink>(`/api/workspaces/${workspaceId}/teams/link`);
  }
}
