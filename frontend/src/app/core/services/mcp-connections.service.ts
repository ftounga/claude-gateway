import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CreateMcpTokenRequest,
  CreatedMcpToken,
  McpHostOption,
  McpJournalEntry,
  McpToken,
} from '../models/mcp-connections.models';

/**
 * Accès à l'API « IA connectées » (F-112 / SF-112-03). Le frontend ne dialogue qu'avec Claude Gateway
 * (`/api/mcp-connections/...`) ; l'isolation est garantie côté backend par le `user_id` porté par le
 * JWT (ajouté par l'`authInterceptor`). Le secret d'un jeton n'est renvoyé qu'à la création.
 */
@Injectable({ providedIn: 'root' })
export class McpConnectionsService {
  private readonly http = inject(HttpClient);

  listTokens(): Observable<McpToken[]> {
    return this.http.get<McpToken[]>('/api/mcp-connections/tokens');
  }

  createToken(request: CreateMcpTokenRequest): Observable<CreatedMcpToken> {
    return this.http.post<CreatedMcpToken>('/api/mcp-connections/tokens', request);
  }

  revokeToken(id: string): Observable<void> {
    return this.http.delete<void>(`/api/mcp-connections/tokens/${id}`);
  }

  journal(): Observable<McpJournalEntry[]> {
    return this.http.get<McpJournalEntry[]>('/api/mcp-connections/journal');
  }

  hosts(): Observable<McpHostOption[]> {
    return this.http.get<McpHostOption[]>('/api/mcp-connections/hosts');
  }
}
