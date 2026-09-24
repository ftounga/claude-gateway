import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AuthService } from '../../core/services/auth.service';
import { HelpChatWidgetComponent } from '../../help/help-chat-widget/help-chat-widget.component';
import { QuotaAlertBannerComponent } from '../quota-alert-banner/quota-alert-banner.component';
import { spaceLinks } from '../../shared/space-links';

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

  /**
   * Menu de navigation replié (F-151 / SF-151-01) : sous 819 px, les 7 liens du shell se
   * cachent derrière un bouton hamburger. Ce drapeau dit si le panneau est déployé. Il n'a
   * aucun effet ≥ 820 px, où le CSS `@media` réaffiche la barre en ligne quoi qu'il vaille.
   */
  protected readonly menuOpen = signal(false);

  constructor() {
    // Toute navigation terminée referme le menu : ouvrir un lien, c'est vouloir la page, pas
    // rester sur le panneau. On écoute l'URL déjà exposée plutôt que de recâbler le routeur.
    effect(() => {
      this.currentUrl();
      this.menuOpen.set(false);
    });
  }

  /** Bascule le panneau de navigation replié (bouton hamburger, < 820 px). */
  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  /** Referme le panneau de navigation replié (clic hors zone, clic sur un lien, Échap). */
  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  /** Échap referme le panneau replié quand il est ouvert (accessibilité). */
  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.menuOpen()) {
      this.closeMenu();
    }
  }

  /** URL courante, sans query ni fragment — recalculée à chaque navigation terminée. */
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /**
   * L'entrée « Forge » est-elle la section où l'on se trouve (F-68 / SF-68-01) ?
   *
   * <p>`routerLinkActive` ne saurait pas le dire : la Forge s'ouvre sur `/forge` mais on y travaille
   * sur `/atelier/:id` — une route conservée telle quelle par F-58. Un onglet qui s'éteint dès
   * qu'on entre dans un projet ferait perdre le repère que F-68 vient précisément consolider.</p>
   */
  protected readonly forgeActive = computed(() => {
    const path = (this.currentUrl() ?? '').split('?')[0].split('#')[0];
    return path === '/forge' || path.startsWith('/forge/')
      || path === '/atelier' || path.startsWith('/atelier/');
  });

  /** L'entrée « Vigie » est-elle la section où l'on se trouve (F-106 / SF-106-02) ? */
  protected readonly vigieActive = computed(() => {
    const path = (this.currentUrl() ?? '').split('?')[0].split('#')[0];
    return path === '/vigie' || path.startsWith('/vigie/');
  });

  /**
   * **Les passerelles** (F-106 / SF-106-04) : les entrées Forge et Vigie portent le client ouvert,
   * pour qu'on change d'espace sans le perdre.
   */
  protected readonly spaceLinks = computed(() => spaceLinks(this.currentUrl()));

  /** Déconnexion : purge la session serveur puis redirige vers /login (best-effort en cas d'échec réseau). */
  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login']),
    });
  }
}
