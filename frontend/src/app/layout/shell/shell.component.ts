import { Component, HostListener, OnDestroy, computed, effect, inject, signal } from '@angular/core';
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
import { NotificationBellComponent } from '../notification-bell/notification-bell.component';
import { spaceLinks } from '../../shared/space-links';
import { PushActivationService } from '../../core/services/push-activation.service';
import { CurrentClientService } from '../../core/services/current-client.service';

/** Préférence par appareil : la barre de navigation reste affichée sur les écrans de travail (F-207 / SF-207-01). */
export const NAV_PINNED_KEY = 'cg.nav.pinned';

/** Délai avant de replier la barre quand la souris la quitte : pas de clignotement au frôlement. */
export const NAV_HIDE_DELAY_MS = 350;

/**
 * **Écran de travail** (F-207 / SF-207-01) : le terminal d'un projet (`/atelier/:id`) et la mosaïque
 * (`/forge/mosaique`). Sur ces écrans, la barre de navigation globale cède sa hauteur au contenu.
 * Le gestionnaire de fichiers (`/atelier/:id/fichiers`) n'en est pas un : il garde sa barre.
 */
export function isWorkspaceScreen(url: string | null | undefined): boolean {
  const path = (url ?? '').split('?')[0].split('#')[0];
  return /^\/atelier\/[^/]+\/?$/.test(path) || path === '/forge/mosaique';
}

function readPinned(): boolean {
  try {
    return localStorage.getItem(NAV_PINNED_KEY) === '1';
  } catch {
    return false;
  }
}

function writePinned(pinned: boolean): void {
  try {
    if (pinned) {
      localStorage.setItem(NAV_PINNED_KEY, '1');
    } else {
      localStorage.removeItem(NAV_PINNED_KEY);
    }
  } catch {
    // Stockage indisponible (navigation privée, bloqué) : la préférence vaut pour la session.
  }
}

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
    NotificationBellComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  host: {
    '[class.shell--autohide]': 'navAutoHide()',
  },
})
export class ShellComponent implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly pushActivation = inject(PushActivationService);
  /** Le client du dernier terminal ouvert (F-199 / SF-199-01) : porté quand l'adresse n'en dit aucun. */
  private readonly currentClient = inject(CurrentClientService);

  /** Affiche l'entrée « Administration » uniquement pour un utilisateur ADMIN (F-20). */
  protected readonly isAdmin = this.auth.isAdmin;

  /**
   * Menu de navigation replié (F-151 / SF-151-01) : sous 819 px, les 7 liens du shell se
   * cachent derrière un bouton hamburger. Ce drapeau dit si le panneau est déployé. Il n'a
   * aucun effet ≥ 820 px, où le CSS `@media` réaffiche la barre en ligne quoi qu'il vaille.
   */
  protected readonly menuOpen = signal(false);

  /** L'écran courant est-il un écran de travail (F-207 / SF-207-01) ? */
  protected readonly workspaceScreen = computed(() => isWorkspaceScreen(this.currentUrl()));

  /** La barre est épinglée par l'utilisateur sur les écrans de travail (préférence par appareil). */
  protected readonly navPinned = signal(readPinned());

  /** Sur un écran de travail non épinglé, la barre se replie en lisière (effectif ≥ 820 px, CSS). */
  protected readonly navAutoHide = computed(() => this.workspaceScreen() && !this.navPinned());

  private readonly navHover = signal(false);
  private readonly navFocus = signal(false);
  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  /** La barre repliée est-elle déployée (survol, focus clavier, menu ouvert depuis elle) ? */
  protected readonly navRevealed = computed(() => this.navAutoHide() && (this.navHover() || this.navFocus()));

  constructor() {
    // F-204 / SF-204-01 — notifications actives par défaut : permission déjà accordée ⇒ cet appareil
    // est (ré)abonné sans aucune UI. Aucun effet sinon (la demande se fait sur le premier envoi).
    void this.pushActivation.ensureSubscribed();
    // Toute navigation terminée referme le menu : ouvrir un lien, c'est vouloir la page, pas
    // rester sur le panneau. On écoute l'URL déjà exposée plutôt que de recâbler le routeur.
    effect(() => {
      this.currentUrl();
      this.menuOpen.set(false);
    });
  }

  ngOnDestroy(): void {
    this.clearHideTimer();
  }

  /** La souris entre dans la lisière ou la barre : déployer, annuler un repli en attente. */
  protected onNavEnter(): void {
    this.clearHideTimer();
    this.navHover.set(true);
  }

  /** La souris quitte la barre : replier après un court délai, sauf si un de ses menus est ouvert. */
  protected onNavLeave(): void {
    this.clearHideTimer();
    this.hideTimer = setTimeout(() => {
      this.hideTimer = null;
      if (this.menuPanelOpen()) {
        this.onNavLeave();
        return;
      }
      this.navHover.set(false);
    }, NAV_HIDE_DELAY_MS);
  }

  /** Le focus clavier entre dans la lisière ou la barre. */
  protected onNavFocusIn(): void {
    this.navFocus.set(true);
  }

  /** Le focus quitte la barre (vers une page ou vers un menu ouvert depuis elle). */
  protected onNavFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    const host = event.currentTarget as HTMLElement | null;
    if (next && host?.contains(next)) {
      return;
    }
    this.navFocus.set(false);
    if (this.navHover()) {
      return;
    }
    // Le focus part dans un menu (Compte, cloche) : la barre reste là le temps qu'il est ouvert.
    this.navHover.set(true);
    this.onNavLeave();
  }

  /** Épingle ou replie la barre sur les écrans de travail. */
  protected togglePin(): void {
    const pinned = !this.navPinned();
    this.navPinned.set(pinned);
    writePinned(pinned);
    if (!pinned) {
      this.navHover.set(false);
      this.navFocus.set(false);
    }
  }

  private menuPanelOpen(): boolean {
    return typeof document !== 'undefined' && !!document.querySelector('.cdk-overlay-container .mat-mdc-menu-panel');
  }

  private clearHideTimer(): void {
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
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
    if (this.navRevealed() && !this.menuPanelOpen()) {
      this.clearHideTimer();
      this.navHover.set(false);
      this.navFocus.set(false);
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
  protected readonly spaceLinks = computed(() => spaceLinks(this.currentUrl(), this.currentClient.ref()));

  /** Déconnexion : purge la session serveur puis redirige vers /login (best-effort en cas d'échec réseau). */
  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login']),
    });
  }
}
