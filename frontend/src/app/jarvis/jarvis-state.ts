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

/** Une phase de « Synchroniser » (F-247 / SF-247-16). */
export interface SyncPhase {
  key: 'HISTORY' | 'NOTES' | 'CAPTURES' | 'RELEVE';
  status: 'PENDING' | 'RUNNING' | 'WAITING' | 'PAUSED' | 'DONE' | 'SKIPPED' | 'EXPIRED' | 'CANCELLED';
  total: number;
  done: number;
  facts: number;
  durationMs: number;
  costEur: number;
  detail: string | null;
  read: number;
  missing: number;
  /** SF-247-17 (NOTES) : estimation au lancement, enveloppe ponctuelle du passage, garde de citation, appels. */
  estimateEur?: number;
  envelopeEur?: number;
  kept?: number;
  rejected?: number;
  calls?: number;
}

/** L'enveloppe par défaut des constats sur l'historique (F-247 / SF-247-17), montrée avant le lancement. */
export interface SyncEnvelope {
  notesEnvelopeEur: number;
  notesMonthlyCapEur: number;
  maxEnvelopeEur: number;
}

/** Un passage de synchronisation d'un client, tel que la passerelle le garde (l'écran le relit). */
export interface StateSync {
  id: string;
  hostId: string;
  status: 'RUNNING' | 'DONE' | 'CANCELLED';
  note: 'TURN' | 'HOST_OFF' | 'READING' | null;
  windowDays: number;
  startedAt: string;
  finishedAt: string | null;
  phases: SyncPhase[];
}

/** La part faite d'un passage, de 0 à 100 (chaque phase pèse un tiers ; sautée ou finie = pleine). */
export function syncPercent(s: StateSync): number {
  if (s.status !== 'RUNNING') {
    return 100;
  }
  const parts = s.phases.map((p) => {
    if (['DONE', 'SKIPPED', 'EXPIRED', 'CANCELLED'].includes(p.status)) {
      return 1;
    }
    if (p.status === 'PENDING' || p.total <= 0) {
      return 0;
    }
    return Math.min(1, p.done / p.total);
  });
  return Math.round((parts.reduce((a, b) => a + b, 0) / Math.max(1, parts.length)) * 100);
}

/** Le libellé d'une phase. */
export function syncPhaseLabel(p: SyncPhase): string {
  switch (p.key) {
    case 'HISTORY': return 'Historique';
    case 'NOTES': return 'Constats';
    case 'CAPTURES': return 'Captures';
    default: return 'Relevé du jour';
  }
}

const SKIP_REASONS: Record<string, string> = {
  source_off: 'source coupée',
  not_setup: 'relevé non mis en place',
  runner_old: 'runner à mettre à jour',
  disarmed: 'relevé désarmé aujourd’hui',
  registry_off: 'registre coupé',
  envelope_zero: 'enveloppe à 0 €',
  before: 'passage antérieur',
};

const NOTES_ENDS: Record<string, string> = {
  envelope: ' · arrêté : enveloppe atteinte',
  budget_hard: ' · arrêté : budget Jarvis épuisé',
  provider_error: ' · arrêté : fournisseur indisponible',
};

/** Les constats sur l'historique, en clair : sorties lues, faits, part rejetée par la garde, € dépensés / enveloppe. */
export function syncNotesText(p: SyncPhase): string {
  const kept = p.kept ?? 0;
  const rejected = p.rejected ?? 0;
  const proposed = kept + rejected;
  const share = proposed > 0 ? ` · ${Math.round((rejected / proposed) * 100)} % rejetés (citation)` : '';
  const money = `${eur(p.costEur)} / ${eur(p.envelopeEur ?? 0)}`;
  const estimate = (p.estimateEur ?? 0) > 0 ? ` (≈ ${eur(p.estimateEur ?? 0)} estimés)` : '';
  return `${p.done}/${p.total} sorties · ${p.facts} fait${p.facts > 1 ? 's' : ''}${share} · ${money}${estimate}`;
}

/** L'état d'une phase, en clair. */
export function syncPhaseText(p: SyncPhase, note: StateSync['note'], current: boolean): string {
  if (p.key === 'NOTES') {
    switch (p.status) {
      case 'PENDING': return (p.estimateEur ?? 0) > 0
        ? `à venir · ≈ ${eur(p.estimateEur ?? 0)} estimés · enveloppe ${eur(p.envelopeEur ?? 0)}`
        : `à venir · enveloppe ${eur(p.envelopeEur ?? 0)}`;
      case 'SKIPPED': return `sautée : ${SKIP_REASONS[p.detail ?? ''] ?? 'rien à faire'}`;
      case 'PAUSED': return `en pause : tour en cours · ${syncNotesText(p)}`;
      case 'DONE': return p.detail === 'nothing' ? 'rien de nouveau à lire'
        : `${syncNotesText(p)}${NOTES_ENDS[p.detail ?? ''] ?? ''}`;
      case 'CANCELLED': return `annulée · ${syncNotesText(p)}`;
      default: return syncNotesText(p);
    }
  }
  const unit = p.key === 'HISTORY' ? 'réponses' : p.key === 'CAPTURES' ? 'captures' : 'sondes';
  const count = `${p.done}/${p.total} ${unit}`;
  const facts = p.key === 'CAPTURES'
    ? `${p.read} lue${p.read > 1 ? 's' : ''} · ${p.facts} message${p.facts > 1 ? 's' : ''}`
    : `${p.facts} fait${p.facts > 1 ? 's' : ''}`;
  const capped = p.detail?.startsWith('capped:') ? ` · ${p.detail.slice(7)} reportée(s) (plafond)` : '';
  switch (p.status) {
    case 'PENDING': return 'à venir';
    case 'SKIPPED': return `sautée : ${SKIP_REASONS[p.detail ?? ''] ?? 'rien à faire'}`;
    case 'EXPIRED': return 'abandonnée : poste éteint depuis 7 jours';
    case 'CANCELLED': return `annulée · ${facts}`;
    case 'WAITING': return 'poste éteint — en attente';
    case 'PAUSED': return `en pause : tour en cours · ${count}`;
    case 'DONE': return `${facts}${capped}`;
    default:
      if (current && note === 'READING') {
        return `lecture des captures en file · ${count} · ${facts}${capped}`;
      }
      return `${count} · ${facts}`;
  }
}

/** Le bilan d'un passage terminé : faits, durée, dépense. */
export function syncSummary(s: StateSync): string {
  const facts = s.phases.reduce((n, p) => n + p.facts, 0);
  const ms = s.phases.reduce((n, p) => n + p.durationMs, 0);
  const cost = s.phases.reduce((n, p) => n + (p.costEur ?? 0), 0);
  const seconds = Math.max(1, Math.round(ms / 1000));
  return `${facts} élément${facts > 1 ? 's' : ''} ajouté${facts > 1 ? 's' : ''} · ${seconds} s de travail · ${eur(cost)}`;
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

  /** Lance (ou rend) le passage de synchronisation d'un client. */
  startSync(hostId: string): Observable<StateSync> {
    return this.http.post<StateSync>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}/sync`, {});
  }

  cancelSync(hostId: string): Observable<StateSync> {
    return this.http.post<StateSync>(`/api/jarvis/state/clients/${encodeURIComponent(hostId)}/sync/cancel`, {});
  }

  /** Le dernier passage (7 j) de chaque client. */
  /** L'enveloppe par défaut des constats sur l'historique (SF-247-17). */
  syncEnvelope(): Observable<SyncEnvelope> {
    return this.http.get<SyncEnvelope>('/api/jarvis/state/syncs/envelope');
  }

  syncs(): Observable<StateSync[]> {
    return this.http.get<StateSync[]>('/api/jarvis/state/syncs');
  }

  validateAll(hostId: string): Observable<ReleveValidateResult[]> {
    return this.http.post<ReleveValidateResult[]>(
      `/api/jarvis/state/clients/${encodeURIComponent(hostId)}/releve/validate-all`, {});
  }
}
