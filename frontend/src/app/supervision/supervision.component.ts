import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { LiveTerminalEntry, LiveTerminals, TerminalPreview } from '../core/models/atelier.models';
import { ForgeBreadcrumbComponent, ForgeCrumb } from '../shared/forge-breadcrumb/forge-breadcrumb.component';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import { HostTone, hostTone } from '../shared/host-identity';
import { LiveBadgeComponent } from '../shared/live-badge/live-badge.component';
import { TerminalPreviewComponent } from '../shared/terminal-preview/terminal-preview.component';

/**
 * Cadence de rafraîchissement. Cinq secondes : assez pour qu'une attente d'autorisation se voie
 * presque tout de suite, assez peu pour qu'un écran laissé ouvert ne martèle pas la gateway.
 */
export const SUPERVISION_REFRESH_MS = 5_000;

/** Une tuile : un terminal vivant, avec de quoi la peindre entièrement. */
export interface SupervisionTile {
  workspaceId: string;
  projectName: string;
  hostName: string | null;
  /** Ton d'identité du poste (§9), ou `null` pour un projet sans machine — il n'en identifie aucune. */
  tone: HostTone | null;
  preview: TerminalPreview | null;
  awaiting: boolean;
  openedAt: string;
}

/**
 * **La vue de supervision** (F-76 / SF-76-03) : voir travailler ses terminaux.
 *
 * <p>Depuis F-70, quatre agents peuvent travailler en parallèle — mais l'écran n'en montre
 * <b>qu'un</b>, et l'on bascule de l'un à l'autre. Cet écran montre les quatre : une tuile chacun,
 * ses dernières lignes, et ce qu'il fait à l'instant. Un clic entre dans le terminal.</p>
 *
 * <p><b>Ce qu'on cherche du coin de l'œil</b>, ce n'est pas le flux : c'est <i>est-ce que ça
 * avance</i> et <i>est-ce que ça attend quelque chose de moi</i>. D'où l'aperçu plutôt que quatre
 * flux complets rejoués — illisibles dans une tuile, et chacun consommerait pendant qu'on regarde
 * ailleurs.</p>
 *
 * <p><b>Ce qui attend une autorisation se signale franchement, et trois fois</b> : la tuile passe
 * en tête, elle porte l'anneau et la pastille écrite, et l'en-tête le compte en toutes lettres.
 * Aucun de ces signaux ne suffit seul — le 2026-09-08, la demande <b>était</b> à l'écran, et elle a
 * échappé douze heures durant (F-47).</p>
 *
 * <p><b>Cet écran ne prend aucune place au registre.</b> Il lit, il n'ouvre rien : regarder ses
 * agents ne doit pas coûter un des quatre flux payants.</p>
 *
 * <p><b>On regarde, on n'écrit pas</b> : écrire dans une tuile est hors périmètre. Le terminal qui
 * reçoit ce qu'on tape est à un clic, et il est entier.</p>
 */
@Component({
  selector: 'app-supervision',
  imports: [
    RouterLink,
    ForgeBreadcrumbComponent,
    HostBadgeComponent,
    LiveBadgeComponent,
    TerminalPreviewComponent,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './supervision.component.html',
  styleUrl: './supervision.component.scss',
})
export class SupervisionComponent implements OnInit, OnDestroy {

  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  private timer: ReturnType<typeof setInterval> | null = null;

  private readonly registry = signal<LiveTerminals | null>(null);

  /** Vrai tant que rien n'a jamais répondu : la grille n'affiche qu'un attente. */
  readonly loading = signal(true);

  /** Vrai quand le **premier** chargement a échoué — le seul cas où l'on n'a rien à montrer. */
  readonly failed = signal(false);

  /** Heure du dernier état réellement obtenu, ou `null`. */
  readonly lastUpdated = signal<Date | null>(null);

  /** Vrai quand le dernier rafraîchissement a échoué alors qu'on affiche déjà quelque chose. */
  readonly stale = signal(false);

  /** Fil d'Ariane : « Forge › Supervision ». */
  readonly crumbs: ForgeCrumb[] = [{ label: 'Supervision', link: ['/forge', 'supervision'] }];

  /**
   * Les tuiles, **ce qui attend une décision d'abord**. Ce tri n'est pas cosmétique : c'est l'un
   * des trois signaux de l'exigence non négociable, et le seul qui vaille encore quand la grille
   * déborde de l'écran.
   */
  readonly tiles = computed<SupervisionTile[]>(() => {
    const terminals = this.registry()?.terminals ?? [];
    return terminals
      .map((terminal) => this.toTile(terminal))
      .sort((left, right) => {
        if (left.awaiting !== right.awaiting) {
          return left.awaiting ? -1 : 1;
        }
        return left.openedAt.localeCompare(right.openedAt);
      });
  });

  /** Combien attendent une décision. Zéro ⇒ l'en-tête n'écrit rien : il n'y a rien à dire. */
  readonly awaitingCount = computed(() => this.tiles().filter((tile) => tile.awaiting).length);

  /** Le compte, **écrit**, accordé. Un garde-fou qu'on doit déchiffrer n'en est pas un. */
  readonly awaitingLabel = computed(() => {
    const count = this.awaitingCount();
    return count > 1
      ? `${count} terminaux attendent votre autorisation`
      : '1 terminal attend votre autorisation';
  });

  /** Plafond et compte, tels que la gateway les déclare (F-70). */
  readonly liveCount = computed(() => this.registry()?.live ?? 0);
  readonly limit = computed(() => this.registry()?.limit ?? 4);

  /** Heure du dernier état connu, pour le dire quand le rafraîchissement échoue. */
  readonly lastUpdatedLabel = computed(() => {
    const updated = this.lastUpdated();
    return updated
      ? updated.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
      : null;
  });

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), SUPERVISION_REFRESH_MS);
    // Ceinture et bretelles : `ngOnDestroy` arrête déjà la minuterie, mais une vue détruite par le
    // routeur sans passer par là laisserait un rafraîchissement tourner dans le vide.
    this.destroyRef.onDestroy(() => this.stopTimer());
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  /** Relance une lecture — le bouton « Réessayer » et le bouton « Rafraîchir ». */
  refresh(): void {
    this.load();
  }

  /** Durée d'ouverture, calculée **à l'affichage** : une durée calculée au serveur vieillit ici. */
  openedLabel(tile: SupervisionTile): string {
    const opened = Date.parse(tile.openedAt);
    if (Number.isNaN(opened)) {
      return '';
    }
    const minutes = Math.max(0, Math.floor((Date.now() - opened) / 60_000));
    if (minutes < 1) {
      return 'ouvert à l’instant';
    }
    if (minutes < 60) {
      return `ouvert depuis ${minutes} min`;
    }
    return `ouvert depuis ${Math.floor(minutes / 60)} h`;
  }

  // ------------------------------------------------------------------ interne

  private load(): void {
    // LECTURE SEULE : `GET /api/terminals/live`, jamais la prise de place. Regarder ses agents ne
    // doit pas consommer un des quatre flux payants.
    this.http.get<LiveTerminals>('/api/terminals/live').subscribe({
      next: (registry) => {
        this.registry.set(registry);
        this.loading.set(false);
        this.failed.set(false);
        this.stale.set(false);
        this.lastUpdated.set(new Date());
      },
      error: () => {
        this.loading.set(false);
        if (this.registry() === null) {
          // Rien n'a jamais répondu : il n'y a rien à conserver, on le dit.
          this.failed.set(true);
          return;
        }
        // On GARDE ce qu'on affiche. Vider la grille sur un hoquet réseau ferait croire que les
        // quatre agents se sont arrêtés — exactement le contresens que cet écran doit éviter.
        this.stale.set(true);
      },
    });
  }

  private toTile(terminal: LiveTerminalEntry): SupervisionTile {
    const lines = terminal.previewLines ?? [];
    const hasPreview =
      (terminal.activity != null && terminal.activity !== 'IDLE') || lines.length > 0;
    return {
      workspaceId: terminal.workspaceId,
      projectName: terminal.workspaceName ?? 'Projet',
      hostName: terminal.hostName ?? null,
      // Un projet sans machine (poste « Hébergé », F-71) n'emprunte AUCUN des dix tons : ceux-ci
      // identifient une machine, et il n'en est pas une (règle posée en SF-71-03).
      tone: terminal.hostName ? hostTone(terminal.hostName) : null,
      preview: hasPreview
        ? {
            activity: terminal.activity ?? 'IDLE',
            activityDetail: terminal.activityDetail ?? null,
            lines,
            at: terminal.activityAt ?? null,
          }
        : null,
      awaiting: terminal.activity === 'AWAITING_APPROVAL',
      openedAt: terminal.openedAt,
    };
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
