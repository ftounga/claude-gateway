import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Un agent nommé d'un terminal (F-182 / SF-182-01) — jamais sa consigne. */
export interface NamedAgentEntry {
  name: string;
  path: string;
  origin: 'SUJET' | 'POSTE';
  description: string;
  access: 'LECTURE' | 'ECRITURE';
  tools: string[];
  /** Modèle écrit et justifié ; `null` = modèle de la Forge. */
  model: string | null;
  warnings: string[];
  errors: string[];
  usable: boolean;
}

/** La commande qui délègue à un agent nommé (F-182 / SF-182-02). */
export const AGENT_COMMAND = 'agent';

/** `/a`, `/ag`… : le jeton d'une commande en cours de frappe, sans espace encore. */
const TYPING_COMMAND = /^\/([a-z0-9_-]*)$/i;
/** `/agent re` : le nom d'agent en cours de frappe après `/agent `. */
const TYPING_AGENT = /^\/agent\s+([a-z0-9_-]*)$/i;

/** Vrai quand le brouillon tape une commande qui peut devenir `/agent` et qu'un agent existe. */
export function agentCommandSuggested(draft: string, catalog: readonly NamedAgentEntry[]): boolean {
  const match = TYPING_COMMAND.exec(draft ?? '');
  return !!match && AGENT_COMMAND.startsWith(match[1].toLowerCase()) && catalog.some((agent) => agent.usable);
}

/** Les agents utilisables à proposer après `/agent `, filtrés en préfixe ; `[]` sinon. */
export function agentSuggestions(draft: string, catalog: readonly NamedAgentEntry[]): NamedAgentEntry[] {
  const match = TYPING_AGENT.exec(draft ?? '');
  if (!match) {
    return [];
  }
  const prefix = match[1].toLowerCase();
  return catalog.filter((agent) => agent.usable && agent.name.startsWith(prefix));
}

/** Ce que fait un agent, en une ligne : description, accès, origine. */
export function agentMenuDescription(agent: NamedAgentEntry): string {
  const access = agent.access === 'ECRITURE' ? 'écrit dans un worktree isolé' : 'lecture seule';
  const origin = agent.origin === 'POSTE' ? 'agent du poste' : 'agent du sujet';
  return `${agent.description ? agent.description + ' · ' : ''}${access} · ${origin}`;
}

/**
 * **Les agents nommés du terminal** (F-182 / SF-182-01/02) : ceux du sujet puis du poste, lus par la gateway
 * (lecture seule, aucun tour). `/agent <nom> demande` part tel quel : c'est la gateway qui délègue.
 */
@Injectable({ providedIn: 'root' })
export class NamedAgentCatalogService {
  private readonly http = inject(HttpClient);

  list(workspaceId: string): Observable<NamedAgentEntry[]> {
    return this.http.get<NamedAgentEntry[]>(`/api/workspaces/${workspaceId}/agents`);
  }
}
