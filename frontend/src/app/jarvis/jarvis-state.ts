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

/** Une ligne d'une conversation capturée, datée par la passerelle (F-247 / SF-247-12/13). */
export interface CaptureLine {
  speaker: string;
  speakerKnown: boolean;
  saidAt: string;
  approx: boolean;
  text: string;
}

/** Une conversation capturée et lue (lot 2). */
export interface StateConversation {
  id: string;
  platform: 'TEAMS' | 'MAIL' | 'AUTRE';
  title: string | null;
  depositedAt: string;
  lineCount: number;
  lines: CaptureLine[];
}

/** Une sonde de relevé d'un client (F-247 / SF-247-08). */
export interface ReleveProbe {
  id: string;
  workspaceId: string;
  workspaceName: string;
  name: string;
  platform: string;
  scope: string;
  command: string;
  proposed: boolean;
  active: boolean;
  testedAt: string | null;
  testSummary: string | null;
  lastRunAt: string | null;
  lastRunStatus: string | null;
  broken: boolean;
}

export interface ReleveView {
  setupAt: string | null;
  probes: ReleveProbe[];
}

export interface ReleveValidateResult {
  watchId: string;
  name: string;
  activated: boolean;
  error: string | null;
}

/** La dépense du registre ce mois-ci, en euros (captures et constats, avec leurs plafonds propres). */
export interface StateSpend {
  captures: { monthEur: number; capEur: number };
  notes: { monthEur: number; capEur: number };
}

/** Le libellé d'une plateforme de capture. */
export function capturePlatformLabel(p: StateConversation['platform']): string {
  return p === 'TEAMS' ? 'Teams' : p === 'MAIL' ? 'Courriel' : 'Conversation';
}

/** Le statut d'une sonde de relevé, en clair. */
export function releveProbeStatus(p: ReleveProbe): string {
  if (p.proposed) {
    return p.testedAt ? 'Testée, à valider' : 'À valider';
  }
  if (p.broken) {
    return 'En panne';
  }
  return p.active ? 'Active' : 'En pause';
}

/** Un montant en euros, à la française. */
export function eur(value: number): string {
  return `${(value ?? 0).toFixed(2).replace('.', ',')} €`;
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

  conversations(hostId: string): Observable<StateConversation[]> {
    return this.http.get<StateConversation[]>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}/conversations`);
  }

  deleteCapture(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/state/captures/${encodeURIComponent(id)}`);
  }

  spend(): Observable<StateSpend> {
    return this.http.get<StateSpend>('/api/jarvis/state/spend');
  }

  releve(hostId: string): Observable<ReleveView> {
    return this.http.get<ReleveView>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}/releve`);
  }

  setupReleve(hostId: string): Observable<{ workspaceId: string; turnId: string }> {
    return this.http.post<{ workspaceId: string; turnId: string }>(
      `/api/jarvis/state/clients/${encodeURIComponent(hostId)}/releve/setup`, {});
  }

  validateAll(hostId: string): Observable<ReleveValidateResult[]> {
    return this.http.post<ReleveValidateResult[]>(
      `/api/jarvis/state/clients/${encodeURIComponent(hostId)}/releve/validate-all`, {});
  }
}
