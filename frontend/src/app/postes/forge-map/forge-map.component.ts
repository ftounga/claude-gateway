import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { map, of } from 'rxjs';

import { MapConsolidation, MapDeadline, MapGraph, MapNode, MapToMap } from '../../core/models/governance.models';
import { AtelierService } from '../../core/services/atelier.service';
import { GovernanceService } from '../../core/services/governance.service';
import { RADAR_DRAFT_STATE } from '../../shared/radar-draft';
import { askAboutDeadline, askAboutNode, askToMap } from './forge-map-ask';
import { ForgeMapCanvasComponent } from './forge-map-canvas.component';
import { ForgeMapCardComponent } from './forge-map-card.component';
import { EnvironmentGrid, environmentGrid } from './forge-map-grid';
import { AccessPath, accessPath } from './forge-map-path';
import {
  DeadlineGroups,
  SignalSummary,
  deadlineGroups,
  dueLabel,
  signalCount,
  signalSummary,
  todayKey,
} from './forge-map-signals';
import { MapLevel, ViewNode, kindLabel, levelFor } from './forge-map-levels';

/** Les vues de l'onglet Carte (D5, D6) : le plan, son équivalent en liste, et les fichiers d'avant. */
export type MapView = 'plan' | 'liste' | 'grille' | 'signaux' | 'fichiers';

/** Sous cette largeur, le plan devient une liste (D6) : un graphe ne se manie pas au doigt. */
export const MOBILE_QUERY = '(max-width: 767px)';

/** La vue demandée par `?vue-carte=` ; Plan par défaut. */
export function mapViewFrom(value: string | null | undefined): MapView {
  return value === 'liste' || value === 'grille' || value === 'signaux' || value === 'fichiers' ? value : 'plan';
}

/**
 * **La carte vivante** (F-173 / SF-173-02) : l'onglet Carte d'un poste, dessiné comme un plan.
 *
 * <p>Le plan est lu dans l'index en base (D1), jamais sur le poste : il s'affiche aussi poste hors
 * ligne, daté. L'endroit où l'on est (`?noeud=`) et la vue (`?vue-carte=`) vivent dans l'URL : un
 * lien y ramène, le retour arrière remonte d'un niveau.</p>
 *
 * <p>La vue <b>Fichiers</b> est l'écran d'avant, inchangé (D5) : il est projeté par l'onglet
 * (`[forgeMapFiles]`) et seulement masqué quand une autre vue est ouverte.</p>
 */
@Component({
  selector: 'app-forge-map',
  standalone: true,
  imports: [DatePipe, MatButtonModule, MatIconModule, MatProgressSpinnerModule, ForgeMapCanvasComponent, ForgeMapCardComponent],
  templateUrl: './forge-map.component.html',
  styleUrl: './forge-map.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForgeMapComponent {
  readonly hostRef = input.required<string>();
  readonly hostName = input.required<string>();
  /** L'identifiant de la machine (null pour « Hébergé ») : « Demander à la Forge » ouvre SON terminal. */
  readonly hostId = input<string | null>(null);
  /** Le poste est-il en ligne ? Hors ligne, le plan se lit (D1) mais la Forge ne peut pas agir. */
  readonly online = input<boolean>(true);

  private readonly governance = inject(GovernanceService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly atelier = inject(AtelierService);
  private readonly snackBar = inject(MatSnackBar);

  private readonly canvas = viewChild(ForgeMapCanvasComponent);

  readonly graph = signal<MapGraph | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  /** cytoscape n'a pas pu être chargé : le plan se replie sur la liste. */
  readonly canvasUnavailable = signal(false);
  readonly mobile = signal(false);

  // `queryParamMap` / `snapshot` peuvent manquer d'un ActivatedRoute simulé : on part alors d'une URL vide.
  private readonly params = toSignal(this.route.queryParamMap ?? of(convertToParamMap({})), {
    initialValue: this.route.snapshot?.queryParamMap ?? convertToParamMap({}),
  });

  readonly requestedView = computed<MapView>(() => mapViewFrom(this.params().get('vue-carte')));
  readonly focus = computed<string | null>(() => this.params().get('noeud'));

  /** La vue réellement montrée : sur téléphone, ou sans cytoscape, le plan est une liste. */
  readonly view = computed<MapView>(() => {
    const wanted = this.requestedView();
    if (wanted === 'plan' && (this.mobile() || this.canvasUnavailable())) {
      return 'liste';
    }
    return wanted;
  });

  readonly level = computed<MapLevel | null>(() => {
    const graph = this.graph();
    if (!graph || !graph.indexed) {
      return null;
    }
    return levelFor(graph, this.focus(), this.hostName());
  });

  readonly kindLabel = kindLabel;

  /** La grille domaine × environnement (SF-173-04), calculée sur le plan déjà lu. */
  readonly grid = computed<EnvironmentGrid | null>(() => {
    const graph = this.graph();
    return graph && graph.indexed && this.view() === 'grille' ? environmentGrid(graph) : null;
  });

  /**
   * La ressource dont la fiche est ouverte (SF-173-03) : celle sélectionnée, sinon la plateforme dont
   * on voit l'intérieur. Aucune en vue Fichiers.
   */
  readonly cardNodeId = computed<string | null>(() => {
    const level = this.level();
    if (!level || this.view() === 'fichiers') {
      return null;
    }
    return level.selected?.id ?? level.frame?.id ?? null;
  });

  /** Aujourd'hui (jour local), pour les échéances (SF-173-06). */
  readonly today = signal(todayKey());

  /** Le compteur de « Signaux » : échéances dépassées ou dans les 14 jours. */
  readonly signalCount = computed(() => signalCount(this.graph(), this.today()));

  readonly deadlines = computed<DeadlineGroups | null>(() => {
    const graph = this.graph();
    return graph && graph.indexed ? deadlineGroups(graph.deadlines, this.today()) : null;
  });

  readonly summary = computed<SignalSummary | null>(() => {
    const graph = this.graph();
    return graph && graph.indexed ? signalSummary(graph, this.today()) : null;
  });

  readonly dueLabel = dueLabel;

  /** Un terminal est en cours d'ouverture : un seul à la fois (double clic). */
  readonly asking = signal(false);

  /** Le geste est possible : une machine, en ligne, aucun terminal en cours d'ouverture. */
  readonly canAsk = computed(() => this.hostId() !== null && this.online() && !this.asking());

  /** Les propositions de consolidation (F-174 / SF-174-06), lues à l'ouverture de la vue Signaux. */
  readonly consolidation = signal<MapConsolidation | null>(null);
  private consolidationRead: string | null = null;

  readonly askAboutNode = askAboutNode;
  readonly askAboutDeadline = askAboutDeadline;
  readonly askToMap = askToMap;

  /** « Comment j'atteins X » (SF-173-05), calculé sur le plan déjà lu. */
  readonly path = computed<AccessPath | null>(() => {
    const graph = this.graph();
    const id = this.cardNodeId();
    return graph && id ? accessPath(graph, id) : null;
  });

  /** Fermer la fiche : remonter au niveau qui la contient. */
  closeCard(): void {
    const level = this.level();
    if (!level) {
      return;
    }
    const trail = level.trail;
    if (level.selected) {
      this.goTo(trail[trail.length - 1].focus);
    } else {
      this.goTo(trail.length > 1 ? trail[trail.length - 2].focus : null);
    }
  }

  constructor() {
    effect(() => {
      const ref = this.hostRef();
      untracked(() => this.load(ref));
    });
    // La consolidation n'est lue qu'à l'ouverture de la vue Signaux, une fois par poste : SILENCIEUSE
    // en cas d'échec (un confort, comme les autres lectures annexes de la Forge).
    effect(() => {
      const ref = this.hostRef();
      if (this.view() !== 'signaux' || this.consolidationRead === ref) {
        return;
      }
      this.consolidationRead = ref;
      untracked(() =>
        this.governance.hostMapConsolidation(ref).subscribe({
          next: (value) => this.consolidation.set(value ?? null),
          error: () => this.consolidation.set(null),
        }),
      );
    });
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      const query = window.matchMedia(MOBILE_QUERY);
      this.mobile.set(query.matches);
      const listener = (event: MediaQueryListEvent) => this.mobile.set(event.matches);
      query.addEventListener?.('change', listener);
      this.destroyRef.onDestroy(() => query.removeEventListener?.('change', listener));
    }
  }

  /** Lit (ou relit) le plan. */
  load(ref: string = this.hostRef()): void {
    this.loading.set(true);
    this.failed.set(false);
    this.governance
      .hostMapGraph(ref)
      .pipe(map((graph) => graph ?? null))
      .subscribe({
        next: (graph) => {
          this.graph.set(graph);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.failed.set(true);
        },
      });
  }

  /** Change de vue : elle entre dans l'URL ; Plan, la vue par défaut, n'encombre pas l'adresse. */
  selectView(view: MapView): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { 'vue-carte': view === 'plan' ? null : view },
      queryParamsHandling: 'merge',
    });
  }

  /** Va à un endroit du plan : il entre dans l'URL et dans l'historique (retour arrière = remonter). */
  goTo(focus: string | null): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { noeud: focus },
      queryParamsHandling: 'merge',
    });
  }

  /** Un clic sur un élément du niveau : descendre (groupe, plateforme) ou sélectionner (ressource). */
  open(view: ViewNode): void {
    this.goTo(view.id);
  }

  onTap(id: string): void {
    this.goTo(id);
  }

  onCanvasFailed(): void {
    this.canvasUnavailable.set(true);
  }

  fit(): void {
    this.canvas()?.fit();
  }

  /** D'où vient une ligne : « acces.md § Proxy · L7 ». */
  sourceOf(path: string | null, heading: string | null, lineNo: number | null): string {
    if (!path) {
      return '';
    }
    return `${path}${heading ? ' § ' + heading : ''}${lineNo ? ' · L' + lineNo : ''}`;
  }

  /** La ressource du plan, par son identifiant. */
  nodeById(id: string): MapNode | undefined {
    return this.graph()?.nodes.find((n) => n.id === id);
  }

  /** La consigne pour la ressource dont la fiche est ouverte. */
  askNode(node: MapNode | null | undefined): void {
    if (node) {
      this.ask(askAboutNode(node));
    }
  }

  askDeadline(deadline: MapDeadline): void {
    this.ask(askAboutDeadline(deadline));
  }

  askMapping(item: MapToMap): void {
    this.ask(askToMap(item));
  }

  /**
   * **Demander à la Forge** (SF-173-07, D7) : ouvre le terminal du poste et y dépose la consigne,
   * **sans l'envoyer** — l'utilisateur relit et envoie. Le dépôt réutilise le brouillon du Radar (F-103) :
   * l'état de navigation, jamais l'adresse. La navigation n'a lieu qu'au succès.
   */
  ask(prompt: string): void {
    const hostId = this.hostId();
    if (!this.canAsk() || hostId === null || !prompt.trim()) {
      return;
    }
    this.asking.set(true);
    this.atelier.openHostTerminal(hostId).subscribe({
      next: (terminal) => {
        this.asking.set(false);
        void this.router.navigate(['/atelier', terminal.id], { state: { [RADAR_DRAFT_STATE]: prompt } });
      },
      error: () => {
        this.asking.set(false);
        this.snackBar.open("Le terminal de ce poste n'a pas pu être ouvert. Rien n'a été envoyé.", 'Fermer', {
          duration: 5000,
        });
      },
    });
  }

  /** Un tronçon dit en mots — jamais la couleur seule. */
  hopLabel(status: string): string {
    switch (status) {
      case 'ouvert':
        return 'ouvert';
      case 'ferme':
        return 'fermé';
      case 'depart':
        return 'départ';
      default:
        return 'inconnu';
    }
  }

  /** Ce que dit un élément de la liste, en plus de son nom. */
  describe(view: ViewNode): string {
    const parts: string[] = [];
    if (view.group) {
      parts.push(`${view.count} ressource${view.count > 1 ? 's' : ''}`);
    } else {
      parts.push(kindLabel(view.kind));
      if (view.count > 0) {
        parts.push(`contient ${view.count}`);
      }
    }
    if (view.traps > 0) {
      parts.push(`⚠ ${view.traps} piège${view.traps > 1 ? 's' : ''}`);
    }
    if (view.stale) {
      parts.push('périmé');
    }
    if (view.toMap) {
      parts.push('à cartographier');
    }
    return parts.join(' · ');
  }
}
