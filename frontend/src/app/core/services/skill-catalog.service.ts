import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Un skill invocable par `/nom` (F-177 / SF-177-03). */
export interface SkillEntry {
  name: string;
  path: string;
  description: string;
  origin: 'SUJET' | 'POSTE';
}

/** Un jeton `/nom` en cours de frappe, sans espace encore (même forme que les macros F-121, `_` admis). */
const TYPING_TOKEN = /^\/([a-z0-9_-]*)$/i;

/**
 * Les skills à proposer pour le brouillon courant, filtrés en préfixe ; `[]` dès que le brouillon ne
 * « tape pas une commande ».
 */
export function skillSuggestions(draft: string, catalog: readonly SkillEntry[]): SkillEntry[] {
  const match = TYPING_TOKEN.exec(draft ?? '');
  if (!match) {
    return [];
  }
  const prefix = match[1].toLowerCase();
  return catalog.filter((skill) => skill.name.startsWith(prefix));
}

/**
 * **Les skills du terminal** (F-177 / SF-177-03) : ceux du sujet puis du poste, lus par la gateway
 * (lecture seule, aucun tour). `/nom texte` part tel quel : c'est la gateway qui joint le skill au tour.
 */
@Injectable({ providedIn: 'root' })
export class SkillCatalogService {
  private readonly http = inject(HttpClient);

  list(workspaceId: string): Observable<SkillEntry[]> {
    return this.http.get<SkillEntry[]>(`/api/workspaces/${workspaceId}/skills`);
  }
}
