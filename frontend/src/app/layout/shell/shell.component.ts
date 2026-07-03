import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AuthService } from '../../core/services/auth.service';

/**
 * Coquille applicative (F-19) : barre de navigation persistante enveloppant les pages authentifiées.
 * Expose les sections existantes (Chat, Documents, Q&A, Templates, Rapports, Facturation, Réglages,
 * Profil) et la déconnexion. Charte : barre fond {@code --cg-primary} (design system).
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatTooltipModule,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Affiche l'entrée « Administration » uniquement pour un utilisateur ADMIN (F-20). */
  protected readonly isAdmin = this.auth.isAdmin;

  /** Déconnexion : purge la session serveur puis redirige vers /login (best-effort en cas d'échec réseau). */
  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login']),
    });
  }
}
