import { HttpClient } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  HostListener,
  NgZone,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { AtelierTerminalComponent } from '../atelier/terminal/atelier-terminal.component';
import { AtelierService } from '../core/services/atelier.service';
import { LiveTerminalEntry, LiveTerminals } from '../core/models/atelier.models';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import { HostTone, hostTone } from '../shared/host-identity';
import { LiveTurnView } from './live-turn-view';

/** Cadence de relecture du registre. Celle de la vue de supervision : une attente s'y voit vite. */
export const MOSAIQUE_REGISTRY_MS = 5_000;

/** Cadence des chronomètres. Une seule minuterie pour tout l'écran, quatre tuiles comprises. */
export const MOSAIQUE_TICK_MS = 1_000;

/** Une tuile : un terminal vivant, et la lecture ouverte dessus. */
export interface MosaiqueTile {
  workspaceId: string;
  projectName: string;
  hostName: string | null;
  /** Ton d'identité du poste (§9), ou `null` pour un projet sans machine — il n'en identifie aucune. */
  tone: HostTone | null;
  openedAt: string;
  view: LiveTurnView;
  /** Vrai si le registre **ou** le flux dit que ce terminal attend une décision. */
  awaiting: boolean;
}

/**
 * **La mosaïque** (F-83 / SF-83-02) : quatre vrais terminaux, vivants, en même temps.
 *
 * <p>F-76 avait livré des <b>vignettes</b> de six lignes ; ce n'était pas la demande. Le PO
 * voulait, et veut, <b>voir les quatre terminaux</b> — le contenu réel du flux, celui qu'on lit
 * dans un terminal ouvert. C'est ce que cet écran montre, en réutilisant le terminal lui-même
 * (`[readOnly]`, SF-83-01) : il n'y a qu'un rendu du flux dans le produit, donc rien à faire
 * diverger.</p>
 *
 * <p><b>La page ouvre ses propres flux</b> (voie A du cadrage) : une lecture par tuile, sur le
 * rebranchement de F-84 — le direct, pas un fil rejoué. Écarté : relire `atelier_messages`, qui
 * aurait livré un aperçu décalé, c'est-à-dire l'erreur de F-76 sous une autre forme.</p>
 *
 * <p><b>Regarder ne coûte aucune place.</b> Le registre de F-70 compte des <b>onglets</b> — des
 * flux payants, quatre tours facturés en parallèle. Cet écran n'en ouvre aucun : il ne fait que
 * lire le registre (`GET /api/terminals/live`) et brancher des lectures. Ouvrir la mosaïque laisse
 * donc les quatre places disponibles, et c'est vérifié par un test.</p>
 *
 * <p><b>On regarde, on n'écrit pas</b> : aucune tuile ne porte de champ ni de bouton de décision.
 * Écrire — et autoriser — reste un geste pris dans le terminal entier, devant son flux entier. Un
 * clic pour y entrer.</p>
 */
@Component({
  selector: 'app-mosaique',
  imports: [
    RouterLink,
    AtelierTerminalComponent,
    HostBadgeComponent,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './mosaique.component.html',
  styleUrl: './mosaique.component.scss',
})
export class MosaiqueComponent implements OnInit, OnDestroy {

  private readonly http = inject(HttpClient);
  private readonly atelier = inject(AtelierService);
  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);

  private registryTimer: ReturnType<typeof setInterval> | null = null;
  private tickTimer: ReturnType<typeof setInterval> | null = null;

  /** Les lectures ouvertes, par projet. Une par tuile, jamais deux. */
  private readonly views = new Map<string, LiveTurnView>();

  private readonly registry = signal<LiveTerminals | null>(null);

  /** Vrai tant que rien n'a jamais répondu. */
  readonly loading = signal(true);

  /** Vrai quand le **premier** chargement a échoué — le seul cas où l'on n'a rien à montrer. */
  readonly failed = signal(false);

  /** Vrai quand un rafraîchissement a échoué alors qu'on affiche déjà quelque chose. */
  readonly stale = signal(false);

  /** Heure du dernier état réellement obtenu. */
  readonly lastUpdated = signal<Date | null>(null);

  /**
   * Le projet dont la tuile est **agrandie** (F-83 / SF-83-03), ou `null` — la mosaïque.
   *
   * <p>Un état d'écran, pas une adresse : porter l'agrandissement dans l'URL rouvrirait la page — et
   * donc les quatre flux — au moindre retour arrière, ce que cet écran passe son temps à éviter.</p>
   */
  readonly zoomed = signal<string | null>(null);

  /** Les tuiles, **ce qui attend une décision d'abord** — le tri fait partie du signal (§12). */
  readonly tiles = computed<MosaiqueTile[]>(() => {
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

  readonly liveCount = computed(() => this.registry()?.live ?? 0);
  readonly limit = computed(() => this.registry()?.limit ?? 4);

  readonly lastUpdatedLabel = computed(() => {
    const updated = this.lastUpdated();
    return updated
      ? updated.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
      : null;
  });

  ngOnInit(): void {
    this.load();
    this.registryTimer = setInterval(() => this.load(), MOSAIQUE_REGISTRY_MS);
    this.tickTimer = setInterval(
      () => this.zone.run(() => this.views.forEach((view) => view.tick())),
      MOSAIQUE_TICK_MS,
    );
    // Ceinture et bretelles : une vue détruite par le routeur sans passer par `ngOnDestroy`
    // laisserait quatre lectures ouvertes dans le vide.
    this.destroyRef.onDestroy(() => this.teardown());
  }

  ngOnDestroy(): void {
    this.teardown();
  }

  /** Relance une lecture du registre — le bouton « Réessayer ». */
  refresh(): void {
    this.load();
  }

  /**
   * **Agrandir une tuile, et la rendre à la mosaïque** (F-83 / SF-83-03).
   *
   * <p>C'est la réponse à l'écran de portable, prévue dès le cadrage : quatre flux complets tiennent
   * sur un grand écran, et sur un petit on en regarde un à la fois — sans quitter la page.</p>
   *
   * <p><b>Le flux n'est pas rouvert au passage.</b> Agrandir ne change que la mise en page : les
   * quatre lectures restent branchées, les quatre terminaux restent dans le document, et celui qu'on
   * agrandit garde son défilement et son contenu. C'est le critère écrit au cadrage, et c'est un
   * test qui le tient.</p>
   */
  toggleZoom(workspaceId: string): void {
    this.zoomed.update((current) => (current === workspaceId ? null : workspaceId));
  }

  /** Le libellé du bouton dit **l'état**, jamais une icône seule. */
  zoomLabel(tile: MosaiqueTile): string {
    return this.zoomed() === tile.workspaceId
      ? `Rendre ${tile.projectName} à la mosaïque`
      : `Agrandir ${tile.projectName}`;
  }

  /**
   * Échap rend la mosaïque. Le geste standard pour « revenir », et il ne coûte aucun pixel — sur
   * cet écran, chaque ligne de chrome est comptée.
   */
  @HostListener('document:keydown.escape')
  closeZoom(): void {
    this.zoomed.set(null);
  }

  /** Ce que la tuile écrit sous le nom du projet : chez qui l'on est. */
  hostLabel(tile: MosaiqueTile): string {
    return tile.hostName ?? 'Hébergé';
  }

  // ------------------------------------------------------------------ interne

  private load(): void {
    // LECTURE PURE : `GET /api/terminals/live`, jamais la prise de place. Regarder ses agents ne
    // doit coûter aucun des quatre flux payants — c'est la décision d'architecture de F-83.
    this.http.get<LiveTerminals>('/api/terminals/live').subscribe({
      next: (registry) => {
        // Les lectures d'ABORD : `tiles` va lire la carte des vues, et une tuile sans sa lecture
        // afficherait un terminal muet le temps d'un rafraîchissement.
        this.syncViews(registry.terminals ?? []);
        this.registry.set(registry);
        this.loading.set(false);
        this.failed.set(false);
        this.stale.set(false);
        this.lastUpdated.set(new Date());
      },
      error: () => {
        this.loading.set(false);
        if (this.registry() === null) {
          this.failed.set(true);
          return;
        }
        // On GARDE ce qu'on affiche : vider la grille sur un hoquet réseau ferait croire que les
        // quatre agents se sont arrêtés — le contresens exact que cet écran doit éviter.
        this.stale.set(true);
      },
    });
  }

  /** Ouvre une lecture pour chaque terminal apparu, ferme celle de chaque terminal disparu. */
  private syncViews(terminals: LiveTerminalEntry[]): void {
    const seen = new Set(terminals.map((terminal) => terminal.workspaceId));
    for (const [workspaceId, view] of this.views) {
      if (!seen.has(workspaceId)) {
        view.close();
        this.views.delete(workspaceId);
      }
    }
    // Une tuile agrandie qui quitte le registre rend la mosaïque : on ne garde pas un
    // agrandissement sur un terminal qui n'existe plus.
    const zoomed = this.zoomed();
    if (zoomed !== null && !seen.has(zoomed)) {
      this.zoomed.set(null);
    }
    for (const terminal of terminals) {
      if (!this.views.has(terminal.workspaceId)) {
        const view = new LiveTurnView(terminal.workspaceId, this.atelier, this.zone);
        this.views.set(terminal.workspaceId, view);
        view.open();
      }
    }
  }

  private toTile(terminal: LiveTerminalEntry): MosaiqueTile {
    const view = this.views.get(terminal.workspaceId)
      ?? new LiveTurnView(terminal.workspaceId, this.atelier, this.zone);
    return {
      workspaceId: terminal.workspaceId,
      projectName: terminal.workspaceName ?? 'Projet',
      hostName: terminal.hostName ?? null,
      // Un projet sans machine (poste « Hébergé », F-71) n'emprunte AUCUN des dix tons : ceux-ci
      // identifient une machine, et il n'en est pas une (règle posée en SF-71-03).
      tone: terminal.hostName ? hostTone(terminal.hostName) : null,
      openedAt: terminal.openedAt,
      view,
      // DEUX SOURCES POUR UN SEUL SIGNAL, et c'est voulu : le flux le dit en quelques dizaines de
      // millisecondes, le registre le redit au battement suivant. Là où l'on regarde quatre choses
      // à la fois, une attente ne doit pas dépendre d'un seul canal (leçon du 2026-09-08, F-47).
      awaiting: view.pending() !== null || terminal.activity === 'AWAITING_APPROVAL',
    };
  }

  private teardown(): void {
    if (this.registryTimer !== null) {
      clearInterval(this.registryTimer);
      this.registryTimer = null;
    }
    if (this.tickTimer !== null) {
      clearInterval(this.tickTimer);
      this.tickTimer = null;
    }
    // Quitter l'écran ABANDONNE les lectures, et rien de plus : depuis F-84 / SF-84-01, fermer un
    // flux ne touche pas au tour. Aucune place n'est rendue — aucune n'avait été prise.
    this.views.forEach((view) => view.close());
    this.views.clear();
  }
}
