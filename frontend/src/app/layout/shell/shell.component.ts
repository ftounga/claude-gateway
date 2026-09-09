import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AuthService } from '../../core/services/auth.service';
import { HelpChatWidgetComponent } from '../../help/help-chat-widget/help-chat-widget.component';
import { QuotaAlertBannerComponent } from '../quota-alert-banner/quota-alert-banner.component';

/**
 * Coquille applicative (F-19) : barre de navigation persistante enveloppant les pages authentifiées.
 * Expose les sections existantes (Chat, Documents, Q&A, Templates, Rapports, Facturation, Réglages,
 * Profil) et la déconnexion. Charte : barre fond {@code --cg-primary} (design system).
 *
 * <p>Porte aussi la bannière d'alerte de consommation (F-42) : elle est ici, et non sur l'écran de
 * facturation, parce qu'un utilisateur qui approche de son quota est en train de travailler dans le
 * chat ou l'Atelier. Elle ne rend rien tant qu'aucune alerte n'est levée — aucune route, aucun
 * guard, aucune redirection n'est ajoutée.</p>
 *
 * <p>Porte enfin la bulle d'aide produit (F-54). Elle est ici parce que la coquille <b>est</b> la
 * zone authentifiée : la réserver aux comptes connectés devient structurel, au lieu d'un test
 * d'authentification recopié dans un gabarit. Aucune route n'est ajoutée ni modifiée.</p>
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
    QuotaAlertBannerComponent,
    HelpChatWidgetComponent,
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
