import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierService } from '../core/services/atelier.service';
import { HostProjectSummary, RunnerHostOverview } from '../core/models/atelier.models';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import { HostTone, hostTone } from '../shared/host-identity';

/** Période de rafraîchissement de la vue, en millisecondes. */
export const POSTES_REFRESH_MS = 15_000;

/** Ce qui empêche la vue d'exister — distinct d'un simple hoquet pendant un rafraîchissement. */
export type PostesError = 'none' | 'network' | 'forbidden';

/**
 * Écran **Postes** (F-49 / SF-49-02) : le seul endroit d'où l'on voit **toutes ses machines** —
 * connectées ou non, depuis quand, sous quel système, quel interpréteur, quels droits, quels projets
 * vivent dessous et ce qui tourne. Le terminal d'un projet est à **un clic**.
 *
 * <p>F-48 a réuni les projets sous un poste, mais les informations restaient éparpillées : la
 * présence dans le dialogue d'appairage d'un projet, l'interpréteur dans son détail, l'activité dans
 * le journal de chacun. Pour savoir si sa machine du bureau était encore connectée, il fallait ouvrir
 * un projet qui vit dessus.</p>
 *
 * <p><b>Une vue d'état, pas des terminaux vivants</b> (arbitrage n° 3 du cadrage du 2026-09-10) :
 * cet écran n'ouvre <b>aucun</b> canal SSE ou WebSocket. Il rejoue une lecture toutes les quinze
 * secondes, et <b>rien du tout</b> quand l'onglet est masqué — une vue que personne ne regarde n'a
 * aucune raison d'appeler la gateway.</p>
 *
 * <p><b>Lecture seule</b> : renommer, supprimer, couper une machine ou révoquer un jeton restent
 * dans le dialogue de mise en service. Une vue d'ensemble qui porterait ces gestes sur chaque carte
 * transformerait un écran de consultation en champ de mines.</p>
 *
 * <p>L'isolation est garantie côté gateway : l'appel ne porte aucun identifiant, la vue part du
 * JWT.</p>
 */
@Component({
  selector: 'app-postes',
  imports: [
    RouterLink,
    HostBadgeComponent,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './postes.component.html',
  styleUrl: './postes.component.scss',
})
export class PostesComponent implements OnInit {
  private readonly atelier = inject(AtelierService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly hosts = signal<RunnerHostOverview[]>([]);
  /** Premier chargement : c'est le seul moment où l'écran a le droit d'être vide. */
  readonly loading = signal(true);
  readonly error = signal<PostesError>('none');
  /** Heure de la dernière lecture réussie — ce qui permet de juger si la vue vieillit. */
  readonly lastUpdatedAt = signal<Date | null>(null);

  readonly isEmpty = computed(() => !this.loading() && this.error() === 'none'
    && this.hosts().length === 0);

  private timer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.load(true);
    this.startPolling();
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.destroyRef.onDestroy(() => {
      this.stopPolling();
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
    });
  }

  /** Relecture demandée par l'utilisateur (bouton « Rafraîchir » ou « Réessayer »). */
  refresh(): void {
    this.load(this.hosts().length === 0);
  }

  /**
   * Ton d'identité d'un poste (F-49 / SF-49-03) : **dérivé de son nom**, jamais rangé nulle part.
   * C'est ce qui rattache visuellement chaque projet à sa machine — le filet de la carte et celui
   * de chaque projet dessous sortent d'ici. Le nom reste écrit à côté : la couleur ne porte jamais
   * seule l'information.
   */
  tone(host: RunnerHostOverview): HostTone {
    return hostTone(host.name);
  }

  /** Ouvre le terminal du projet — le « à un clic » que la vue promet. */
  openTerminal(project: HostProjectSummary): void {
    this.router.navigate(['/atelier', project.id]);
  }

  // ---------------------------------------------------------------- libellés

  /**
   * Durée écoulée depuis un instant, en relatif. Calculée **à l'écran** : une durée calculée au
   * serveur vieillit dans le navigateur et redevient fausse entre deux rafraîchissements.
   */
  elapsedLabel(instant: string | null | undefined): string | null {
    if (!instant) {
      return null;
    }
    const elapsed = Math.floor((Date.now() - new Date(instant).getTime()) / 1000);
    if (!Number.isFinite(elapsed) || elapsed < 0) {
      return null;
    }
    if (elapsed < 60) {
      return `il y a ${elapsed} s`;
    }
    if (elapsed < 3600) {
      return `il y a ${Math.floor(elapsed / 60)} min`;
    }
    if (elapsed < 86_400) {
      return `il y a ${Math.floor(elapsed / 3600)} h`;
    }
    return `il y a ${Math.floor(elapsed / 86_400)} j`;
  }

  /** État de la machine, en une phrase : « Connecté » ou depuis quand on ne l'a plus vue. */
  hostStateLabel(host: RunnerHostOverview): string {
    if (host.connected) {
      return 'Connecté';
    }
    const seen = this.elapsedLabel(host.lastSeenAt);
    return seen ? `Vu ${seen}` : 'Jamais connecté';
  }

  /** Chemin du projet sous la racine du poste — la racine elle-même quand il est vide. */
  projectPathLabel(project: HostProjectSummary): string {
    return project.projectPath?.trim() ? project.projectPath : 'la racine';
  }

  /** Ce qui tourne sur cette machine, ou `null` quand il n'y a rien à dire. */
  activityLabel(host: RunnerHostOverview): string | null {
    if (host.activeProjects > 0) {
      return host.activeProjects === 1 ? '1 projet actif'
        : `${host.activeProjects} projets actifs`;
    }
    const last = this.elapsedLabel(host.lastActivityAt);
    return last ? `Dernière activité ${last}` : null;
  }

  /** Ce que le projet a fait en dernier — le nom de l'outil, jamais sa cible. */
  projectActivityLabel(project: HostProjectSummary): string | null {
    const last = this.elapsedLabel(project.lastActivityAt);
    if (!last) {
      return null;
    }
    return project.lastTool ? `${project.lastTool} · ${last}` : last;
  }

  /** Heure de la dernière lecture réussie, en clair. */
  lastUpdatedLabel(): string | null {
    const at = this.lastUpdatedAt();
    if (!at) {
      return null;
    }
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
  }

  // ---------------------------------------------------------------- interne

  /**
   * Lit la vue.
   *
   * @param blocking vrai au premier chargement : c'est le seul cas où l'on a le droit de vider
   *        l'écran. Un échec pendant un rafraîchissement **conserve** la vue précédente — une vue
   *        d'état qui clignote à chaque hoquet réseau est pire que la même vue légèrement en retard.
   */
  private load(blocking: boolean): void {
    if (blocking) {
      this.loading.set(true);
    }
    this.atelier.runnerHostsOverview().subscribe({
      next: (hosts) => {
        this.hosts.set(hosts.map((host) => ({ ...host, projects: host.projects ?? [] })));
        this.error.set('none');
        this.loading.set(false);
        this.lastUpdatedAt.set(new Date());
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (err instanceof HttpErrorResponse && err.status === 403) {
          // L'accès Atelier est refusé : la vue n'existera pas, quelle que soit la relecture.
          this.error.set('forbidden');
          this.hosts.set([]);
          this.stopPolling();
          return;
        }
        if (blocking) {
          this.error.set('network');
        }
      },
    });
  }

  private startPolling(): void {
    this.stopPolling();
    if (this.error() === 'forbidden') {
      // Un refus d'accès ne se répare pas en relisant : on n'arme pas le sondage.
      return;
    }
    this.timer = setInterval(() => this.load(false), POSTES_REFRESH_MS);
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /**
   * Onglet masqué : on cesse d'appeler. Retour au premier plan : on relit tout de suite, parce que
   * ce que l'écran montre a pu vieillir de plusieurs minutes.
   */
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') {
      this.stopPolling();
      return;
    }
    if (this.error() !== 'forbidden') {
      this.load(false);
      this.startPolling();
    }
  };
}
