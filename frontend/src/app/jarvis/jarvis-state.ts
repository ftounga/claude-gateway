import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * **Le registre d'état, côté écran** (F-247 / SF-247-15) — `/api/jarvis/state/*` : par client, le réglage (activé
 * d'office, couper purge), les sources, les familles, la rétention, « effacer tout », et ce que Jarvis sait (faits
 * datés, sources), effaçables un à un.
 */
export interface StateClient {
  hostId: string;
  name: string;
  enabled: boolean;
  disabledFamilies: string[];
  extractFromTurns: boolean;
  notesEnabled: boolean;
  releveEnabled: boolean;
  capturesEnabled: boolean;
  factRetentionDays: number;
  captureRetentionDays: number;
  facts: number;
}

export interface StateFact {
  id: string;
  objectKind: string;
  objectKey: string;
  attribute: string;
  value: string;
  previousValue: string | null;
  observedAt: string;
  source: 'TOUR' | 'NOTE' | 'RELEVE' | 'VEILLE';
  confidence: 'HAUTE' | 'MOYENNE';
}

export type StateSettingsPatch = Partial<Pick<StateClient, 'enabled' | 'disabledFamilies' | 'extractFromTurns'
  | 'notesEnabled' | 'releveEnabled' | 'capturesEnabled' | 'factRetentionDays' | 'captureRetentionDays'>>;

/** Les familles montrées (dans l'ordre), avec leur libellé. */
export const STATE_FAMILIES: ReadonlyArray<{ id: string; label: string }> = [
  { id: 'TICKET', label: 'Tickets' }, { id: 'MR', label: 'Revues' }, { id: 'PIPELINE', label: 'CI' },
  { id: 'DEPLOYMENT', label: 'Déploiements' }, { id: 'RELEASE', label: 'Versions' },
  { id: 'CLOUD_RESOURCE', label: 'Cloud' }, { id: 'INFRA_STATE', label: 'Infra' }, { id: 'DB_SCHEMA', label: 'Base' },
  { id: 'GIT_BRANCH', label: 'Git' }, { id: 'PAGE', label: 'Pages' }, { id: 'ACCESS', label: 'Accès' },
  { id: 'OTHER', label: 'Autres' },
];

/** Le libellé d'une source. */
export function stateSourceLabel(source: StateFact['source']): string {
  switch (source) {
    case 'RELEVE': return 'relevé';
    case 'VEILLE': return 'veille';
    case 'NOTE': return 'constat';
    default: return 'tour';
  }
}

@Injectable({ providedIn: 'root' })
export class JarvisStateClient {
  private readonly http = inject(HttpClient);

  clients(): Observable<StateClient[]> {
    return this.http.get<StateClient[]>('/api/jarvis/state/clients');
  }

  save(hostId: string, patch: StateSettingsPatch): Observable<StateClient> {
    return this.http.put<StateClient>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}`, patch);
  }

  facts(hostId: string): Observable<StateFact[]> {
    return this.http.get<StateFact[]>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}/facts`);
  }

  eraseAll(hostId: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}/facts`);
  }

  deleteFact(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/state/facts/${encodeURIComponent(id)}`);
  }
}
