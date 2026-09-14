import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { McpConnectionsService } from '../core/services/mcp-connections.service';

/**
 * Écran « Connecter une IA » (F-112 / SF-112-08) : l'adresse du serveur MCP et le pas-à-pas vérifié
 * pour Claude Code, Claude Desktop, claude.ai et Codex, avec une vérification de la connexion en un
 * clic (lecture du journal MCP).
 *
 * <p>Conforme au design system : MatCard, MatButton, MatIcon, jetons de charte uniquement, aucune
 * couleur hors charte. Aucune donnée sensible : l'adresse du serveur est publique, la connexion se
 * fait par OAuth dans le navigateur ou par jeton personnel (écran « IA connectées »).</p>
 */
@Component({
  selector: 'app-connect-ai',
  imports: [RouterLink, MatCardModule, MatButtonModule, MatIconModule],
  templateUrl: './connect-ai.component.html',
  styleUrl: './connect-ai.component.scss',
})
export class ConnectAiComponent implements OnInit {
  private readonly service = inject(McpConnectionsService);
  private readonly snackBar = inject(MatSnackBar);

  /** L'adresse publique du serveur MCP, dérivée de l'origine courante. */
  readonly serverUrl = signal('');
  /** La commande Claude Code toute prête. */
  readonly claudeCodeCommand = signal('');

  /** Statut de la vérification : inconnu, en cours, connecté récemment, aucune activité. */
  readonly verifyState = signal<'idle' | 'checking' | 'active' | 'none'>('idle');

  ngOnInit(): void {
    const origin =
      typeof window !== 'undefined' && window.location ? window.location.origin : '';
    const url = `${origin}/api/mcp`;
    this.serverUrl.set(url);
    this.claudeCodeCommand.set(`claude mcp add --transport http claude-gateway ${url}`);
  }

  copy(value: string): void {
    if (typeof navigator === 'undefined' || !navigator.clipboard) {
      this.snackBar.open('Copie indisponible dans ce navigateur.', 'Fermer', { duration: 4000 });
      return;
    }
    navigator.clipboard.writeText(value).then(
      () => this.snackBar.open('Copié.', 'Fermer', { duration: 2000 }),
      () => this.snackBar.open('Copie impossible.', 'Fermer', { duration: 4000 }),
    );
  }

  /** Vérifie la connexion : une IA a-t-elle appelé le serveur MCP récemment (journal) ? */
  verify(): void {
    this.verifyState.set('checking');
    this.service.journal().subscribe({
      next: (entries) => {
        const tenMinutesAgo = Date.now() - 10 * 60 * 1000;
        const recent = entries.some((e) => new Date(e.createdAt).getTime() >= tenMinutesAgo);
        this.verifyState.set(recent ? 'active' : 'none');
      },
      error: () => {
        this.verifyState.set('none');
        this.snackBar.open('Vérification impossible pour le moment.', 'Fermer', { duration: 4000 });
      },
    });
  }
}
