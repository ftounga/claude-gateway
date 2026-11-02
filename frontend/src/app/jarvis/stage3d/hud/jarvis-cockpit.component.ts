import {
  ChangeDetectionStrategy, Component, ElementRef, InjectionToken, Injector, NgZone, OnDestroy, computed, effect, inject,
  input, output, signal, untracked, viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Observable } from 'rxjs';

import { AtelierMessage } from '../../../core/models/atelier.models';
import { AtelierService } from '../../../core/services/atelier.service';
import { DayTurn, FAMILY_TONE, FAMILY_WORD, Pearl, TurnTimeline, dayAxis, dayTurns } from './jarvis-timelines';

import { JarvisUniverse, clientKey } from '../../scenes/jarvis-universe.models';
import { ShowFilter } from '../../scenes/jarvis-show';
import { DayFigure, dayBoard, weatherRows } from './jarvis-day-board';
import { WEATHER_WORD } from './jarvis-weather';
import { FloatId, floatSpot, peopleMap, sendShelf, ticketWall } from './jarvis-people-map';
import { SessionSnapshot } from '../scenes3d/jarvis-session-memory';
import { InspectorCard, SearchResult, inspect, search } from './jarvis-inspector';
import { Node3dKind } from '../jarvis-layout-3d';
import { IDLE_SCREEN, ScreenSignals } from '../jarvis-stage-director';
import {
  CockpitFolds, CockpitInsets, CockpitMode, cockpitInsets, cockpitMode, readFolds, writeFolds,
} from './jarvis-cockpit-layout';

/** Ce que le cockpit lit de la scène 3D (le composant, ou une doublure de test). */
export interface CockpitStage {
  shown(): boolean;
  readonly isNarrow: boolean;
  project(key: string): { x: number; y: number } | null;
  /** SF-236-18 — la mémoire de session de l'écran (tickets vus), si la scène la tient. */
  session?: () => SessionSnapshot;
  /** SF-236-19 — regarder un nœud (la caméra le cadre). */
  look?: (key: string) => void;
}

/** Une ligne de liste reliée à un nœud de l'univers (filament au survol). */
export interface CockpitNodeItem {
  key: string;
  label: string;
  /** Le mot d'état (la couleur ne porte jamais seule l'information). */
  note: string | null;
  tone: 'normal' | 'green' | 'off' | 'wait';
  /** Sujet : il se choisit (D12) ; les autres se regardent. */
  subjectId?: string;
  current?: boolean;
  depth: 0 | 1;
}

/**
 * SF-236-17 — l'historique du fil d'un sujet (H14), lu <b>hors tour, à la demande</b> ; injectable pour les tests.
 * Résolu au premier chargement seulement (le cockpit ne dépend pas du client HTTP pour s'afficher).
 */
export const JARVIS_DAY_HISTORY = new InjectionToken<(subjectId: string) => Observable<AtelierMessage[]>>(
  'JARVIS_DAY_HISTORY', {
    providedIn: 'root',
    factory: () => {
      const atelier = inject(AtelierService);
      return (subjectId: string) => atelier.getHistory(subjectId);
    },
  });

/** Une perle à l'écran : position sur l'arc et teinte. */
export interface PearlDot {
  x: number;
  y: number;
  tone: string;
  word: string;
}

/** L'arc de perles du tour (N14) : 160° d'une ellipse 110 × 96 dans un cadre 280 × 120, de gauche à droite. */
export function pearlArc(pearls: readonly Pearl[]): PearlDot[] {
  const n = pearls.length;
  return pearls.map((p, i) => {
    const a = Math.PI * (1 - (0.0555 + 0.889 * (n === 1 ? 0.5 : i / (n - 1))));
    return { x: Math.round((140 + 110 * Math.cos(a)) * 10) / 10, y: Math.round((112 - 96 * Math.sin(a)) * 10) / 10,
      tone: FAMILY_TONE[p.family], word: FAMILY_WORD[p.family] };
  });
}

const STATE_WORD: Record<ScreenSignals['state'], string> = {
  repos: 'Au repos', ecoute: 'Écoute', reflexion: 'Réflexion', parole: 'Parole',
};

/** La liste « Univers » du panneau gauche : postes puis leurs sujets (hors terminal du poste). */
export function universeItems(universe: JarvisUniverse | null, subjectId: string | null): CockpitNodeItem[] {
  const items: CockpitNodeItem[] = [];
  for (const c of universe?.clients ?? []) {
    items.push({
      key: clientKey(c), label: c.name, depth: 0,
      note: c.virtual ? 'hébergé' : c.connected ? 'connecté' : 'déconnecté',
      tone: c.virtual ? 'normal' : c.connected ? 'green' : 'off',
    });
    for (const s of c.subjects) {
      if (s.hostTerminal) {
        continue;
      }
      items.push({ key: s.id, label: s.name, depth: 1, subjectId: s.id, current: s.id === subjectId,
        note: s.live ? 'au travail' : null, tone: s.live ? 'green' : 'normal' });
    }
  }
  return items;
}

/**
 * F-236 / SF-236-15 — **le cockpit PC** (cadrage §12.1, calque L5) : panneaux latéraux en verre holographique,
 * posés au-dessus de l'univers 3D qui passe derrière (pleine largeur). Repliables (T-P4, retenu par appareil) ;
 * chaque ligne qui désigne un nœud tend, au survol ou au focus clavier, un <b>filament</b> lumineux vers ce nœud
 * dans l'univers. Rien n'est demandé au réseau : tout est lu dans ce que l'écran a déjà.
 *
 * <p>Hors cockpit (téléphone, PC étroit, 2D, mode sobre) : rien n'est rendu ; l'écran de l'itération 2 reste
 * identique.</p>
 */
@Component({
  selector: 'app-jarvis-cockpit',
  imports: [NgTemplateOutlet],
  templateUrl: './jarvis-cockpit.component.html',
  styleUrl: './jarvis-cockpit.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-mode]': 'mode()',
    '(window:resize)': 'width.set(viewportWidth())',
    '(window:keydown)': 'onGlobalKey($event)',
  },
})
export class JarvisCockpitComponent implements OnDestroy {
  /** La scène 3D (filaments, décision d'affichage). */
  readonly stage = input<CockpitStage | null>(null);
  readonly signals = input<ScreenSignals>(IDLE_SCREEN);
  readonly sober = input(false);
  /** Un sujet touché dans une liste : l'écran le choisit (comme dans l'univers). */
  readonly choose = output<string>();
  /** SF-236-16 — messages finis pendant le tour (compteur « À envoyer » du jour). */
  readonly toSendAdded = input(0);
  /** SF-236-16 — le filtre « Montrer » posé localement (chiffre enfoncé). */
  readonly shownFilter = input<ShowFilter | null>(null);
  /** SF-236-16 — un chiffre touché : « Montrer » local, sans tour. */
  readonly show = output<ShowFilter>();

  private readonly zone = inject(NgZone);
  private readonly filament = viewChild<ElementRef<SVGLineElement>>('filament');
  private readonly filamentEnd = viewChild<ElementRef<SVGCircleElement>>('filamentEnd');

  readonly width = signal(viewportWidth());
  readonly folds = signal<CockpitFolds>(readFolds());

  readonly mode = computed<CockpitMode>(() => {
    const stage = this.stage();
    return cockpitMode({ width: this.width(), narrow: stage?.isNarrow ?? false, shown3d: stage?.shown() ?? false,
      sober: this.sober() });
  });

  /** Ce que couvrent les panneaux : relayé à la scène (l'univers se centre dans la zone libre). */
  readonly insets = computed<CockpitInsets>(() => cockpitInsets(this.mode(), this.folds()),
    { equal: (a, b) => a.left === b.left && a.right === b.right });

  /** SF-236-16 (NF-1) — « Aujourd'hui » : les chiffres comptés dans l'univers et l'ambiance. */
  readonly board = computed(() => dayBoard(this.signals().universe, this.signals().ambient, this.toSendAdded()));
  /** SF-236-16 (NF-2) — la météo des chantiers, du plus grave au plus serein. */
  readonly weather = computed(() => weatherRows(this.signals().universe));
  readonly weatherWord = WEATHER_WORD;

  /** Un chiffre touché : son filtre « Montrer », appliqué localement par l'écran (aucun tour). */
  touchFigure(figure: DayFigure): void {
    if (figure.filter) {
      this.show.emit(figure.filter);
    }
  }

  readonly universeList = computed(() => universeItems(this.signals().universe, this.signals().subjectId));

  readonly turn = computed(() => {
    const s = this.signals();
    return {
      state: STATE_WORD[s.state],
      scene: s.scene ? (s.scene.count > 1 ? `${s.scene.libelle} ×${s.scene.count}` : s.scene.libelle) : null,
      actions: s.pulses,
      delegations: s.delegations,
      active: s.turnActive,
    };
  });

  /** SF-236-17 (NF-4 / N14) — la chronologie du tour en cours : une perle par appel, famille du registre. */
  private readonly timeline = new TurnTimeline();
  readonly turnPearls = signal<{ dots: PearlDot[]; more: number }>({ dots: [], more: 0 });
  private readonly timelineFollow = effect(() => {
    const s = this.signals();
    untracked(() => {
      if (this.timeline.observe({ turnActive: s.turnActive, pulses: s.pulses, scene: s.scene })) {
        const snap = this.timeline.snapshot();
        this.turnPearls.set({ dots: pearlArc(snap.pearls), more: snap.more });
      }
    });
  });

  /** SF-236-17 (NF-3) — la journée du sujet : chargée à la demande, jamais pendant un tour. */
  private readonly injector = inject(Injector);
  readonly day = signal<{ subjectId: string; turns: DayTurn[]; axis: { from: number; to: number } } | null>(null);
  readonly dayState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly dayPick = signal<DayTurn | null>(null);
  readonly familyTone = FAMILY_TONE;

  /** Charge les tours du jour du sujet courant ; refusé pendant un tour (aucun appel pendant un tour). */
  loadDay(): void {
    const s = this.signals();
    if (s.turnActive || !s.subjectId || this.dayState() === 'loading') {
      return;
    }
    const subjectId = s.subjectId;
    this.dayState.set('loading');
    this.dayPick.set(null);
    this.injector.get(JARVIS_DAY_HISTORY)(subjectId).subscribe({
      next: (messages) => {
        const now = new Date();
        const turns = dayTurns(messages ?? [], now);
        this.day.set({ subjectId, turns, axis: dayAxis(turns, now) });
        this.dayState.set('idle');
      },
      error: () => this.dayState.set('error'),
    });
  }

  /** Position d'un tour sur l'axe de la journée (0-100 %). */
  dayX(turn: DayTurn): number {
    const axis = this.day()?.axis;
    if (!axis) {
      return 0;
    }
    return Math.round(((turn.minute - axis.from) / Math.max(1, axis.to - axis.from)) * 1000) / 10;
  }

  hourLabel(minute: number): string {
    return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
  }

  /** SF-236-18 (NF-5) — personnes et attentes ; (NF-6) mur de tickets ; étagère « À envoyer ». */
  readonly people = computed(() => peopleMap(this.signals().universe));
  readonly shelf = computed(() => sendShelf(this.signals().universe));
  readonly tickets = computed(() => ticketWall(this.stage()?.session?.().tickets ?? [], this.signals().universe));

  /** SF-236-18 — tableaux détachés du panneau et posés dans l'espace (calque L4), ancrés à un client. */
  readonly detached = signal<readonly FloatId[]>([]);
  private readonly floatHost = viewChild<ElementRef<HTMLElement>>('floats');
  private floatFrame: number | null = null;

  /** SF-236-19 (NF-7) — l'inspecteur : la fiche du nœud touché (univers, liste, recherche). */
  readonly inspected = signal<{ key: string; kind: Node3dKind } | null>(null);
  readonly card = computed<InspectorCard | null>(() => {
    const target = this.inspected();
    return target ? inspect(this.signals().universe, target.key, target.kind) : null;
  });

  /** Ouvre la fiche d'un nœud ; `look` = la caméra le cadre aussi (recherche, liens de la fiche). */
  inspectNode(key: string, kind: Node3dKind, look = false): void {
    this.inspected.set({ key, kind });
    if (look) {
      this.stage()?.look?.(key);
    }
  }

  closeInspector(): void {
    this.inspected.set(null);
  }

  /** SF-236-19 (NF-8) — la recherche « montre-moi » : filtres de « Montrer » + noms, sans tour. */
  readonly query = signal('');
  readonly results = computed<SearchResult[]>(() => search(this.signals().universe, this.query()));
  readonly active = signal(0);
  /** Un résultat « Montrer » choisi : l'écran l'applique localement (filtre, client normalisé). */
  readonly showSearch = output<{ filter: ShowFilter; client: string | null }>();
  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('searchBox');

  setQuery(value: string): void {
    this.query.set(value);
    this.active.set(0);
  }

  apply(result: SearchResult | undefined): void {
    if (!result) {
      return;
    }
    if (result.type === 'show') {
      this.showSearch.emit({ filter: result.filter, client: result.client });
    } else {
      this.inspectNode(result.key, result.kind, true);
    }
  }

  /** Clavier complet : ↓ ↑ parcourent, Entrée applique, Échap efface puis rend le focus. */
  onSearchKey(event: KeyboardEvent): void {
    const n = this.results().length;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.active.set(n === 0 ? 0 : (this.active() + 1) % n);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.active.set(n === 0 ? 0 : (this.active() - 1 + n) % n);
        break;
      case 'Enter':
        event.preventDefault();
        this.apply(this.results()[this.active()]);
        break;
      case 'Escape':
        event.preventDefault();
        if (this.query()) {
          this.setQuery('');
        } else {
          (event.target as HTMLElement).blur();
        }
        break;
    }
  }

  /** `/` ou Ctrl+K (⌘K) n'importe où ⇒ la recherche (sauf pendant une saisie). */
  onGlobalKey(event: KeyboardEvent): void {
    if (this.mode() !== 'cockpit') {
      return;
    }
    const target = event.target as HTMLElement | null;
    const typing = !!target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
    const shortcut = (event.key === '/' && !typing) || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k');
    if (shortcut) {
      event.preventDefault();
      if (this.folds().left) {
        this.toggle('left');
      }
      this.searchBox()?.nativeElement.focus();
    }
  }

  readonly rightBoards: readonly FloatId[] = ['envoyer', 'tickets'];
  readonly floatTitle: Readonly<Record<FloatId, string>> = {
    personnes: 'Personnes et attentes', tickets: 'Mur de tickets', envoyer: 'À envoyer',
  };

  /** Le mot d'une personne : ce que j'attends d'elle, et le message prêt. */
  personNote(p: { awaitedBy: string[]; message: boolean }): string {
    const parts: string[] = [];
    if (p.awaitedBy.length > 0) {
      parts.push(p.awaitedBy.length > 1 ? `attendue (${p.awaitedBy.length})` : 'attendue');
    }
    if (p.message) {
      parts.push('message prêt');
    }
    return parts.join(' · ');
  }

  isDetached(id: FloatId): boolean {
    return this.detached().includes(id);
  }

  detach(id: FloatId): void {
    if (!this.isDetached(id)) {
      this.detached.update((list) => [...list, id]);
      if (this.floatFrame === null) {
        this.zone.runOutsideAngular(() => this.floatTick());
      }
    }
  }

  attach(id: FloatId): void {
    this.detached.update((list) => list.filter((d) => d !== id));
  }

  /** Le client auquel un tableau s'ancre : celui du sujet courant, sinon le premier qui a des éléments. */
  anchorOf(id: FloatId): string | null {
    const s = this.signals();
    const current = s.universe?.clients.find((c) => c.subjects.some((x) => x.id === s.subjectId));
    if (current) {
      return clientKey(current);
    }
    const first = id === 'personnes' ? this.people()[0]?.clientKey : id === 'envoyer' ? this.shelf()[0]?.clientKey
      : this.tickets()[0]?.clientKey;
    return first ?? null;
  }

  /** Une image des tableaux flottants : chacun suit son ancre projetée (hors zone, style écrit directement). */
  floatTick(): void {
    this.floatFrame = null;
    const ids = this.detached();
    const host = this.floatHost()?.nativeElement;
    if (ids.length === 0 || this.mode() !== 'cockpit') {
      return;
    }
    if (host) {
      const insets = this.insets();
      const view = { width: typeof window === 'undefined' ? 1920 : window.innerWidth,
        height: typeof window === 'undefined' ? 1080 : window.innerHeight, left: insets.left, right: insets.right };
      ids.forEach((id, i) => {
        const card = host.querySelector<HTMLElement>(`[data-float="${id}"]`);
        if (card) {
          const anchor = this.anchorOf(id);
          const spot = floatSpot(anchor ? this.stage()?.project(anchor) ?? null : null, i, view);
          card.style.transform = `translate(${spot.x}px, ${spot.y}px)`;
        }
      });
    }
    if (typeof requestAnimationFrame === 'function') {
      this.floatFrame = requestAnimationFrame(() => this.floatTick());
    }
  }

  toggle(side: 'left' | 'right'): void {
    const next = { ...this.folds(), [side]: !this.folds()[side] };
    this.folds.set(next);
    writeFolds(next);
  }

  /** Une ligne touchée : un sujet se choisit ; tout nœud ouvre sa fiche dans l'inspecteur (SF-236-19). */
  pick(item: CockpitNodeItem): void {
    if (item.subjectId) {
      this.choose.emit(item.subjectId);
    }
    // SF-236-19 — et sa fiche s'ouvre dans l'inspecteur.
    this.inspectNode(item.key, item.depth === 0 ? 'client' : 'subject');
  }

  private hover: { key: string; el: HTMLElement; side: 'left' | 'right' } | null = null;
  private frame: number | null = null;

  /** Le filament part de la ligne survolée (bord tourné vers l'univers) et rejoint le nœud, à chaque image. */
  link(key: string, event: Event, side: 'left' | 'right'): void {
    this.hover = { key, el: event.currentTarget as HTMLElement, side };
    if (this.frame === null) {
      this.zone.runOutsideAngular(() => this.tick());
    }
  }

  unlink(): void {
    this.hover = null;
    this.draw(null);
  }

  /** Une image du filament (exposée aux tests). */
  tick(): void {
    this.frame = null;
    const hover = this.hover;
    if (!hover || this.mode() !== 'cockpit') {
      this.draw(null);
      return;
    }
    const target = this.stage()?.project(hover.key) ?? null;
    const rect = hover.el.getBoundingClientRect();
    this.draw(target ? {
      x1: hover.side === 'left' ? rect.right : rect.left, y1: rect.top + rect.height / 2, x2: target.x, y2: target.y,
    } : null);
    if (typeof requestAnimationFrame === 'function') {
      this.frame = requestAnimationFrame(() => this.tick());
    }
  }

  ngOnDestroy(): void {
    this.hover = null;
    if (this.frame !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(this.frame);
    }
    this.frame = null;
    this.detached.set([]);
    if (this.floatFrame !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(this.floatFrame);
    }
    this.floatFrame = null;
  }

  private draw(line: { x1: number; y1: number; x2: number; y2: number } | null): void {
    const el = this.filament()?.nativeElement;
    const end = this.filamentEnd()?.nativeElement;
    if (!el || !end) {
      return;
    }
    if (!line) {
      el.setAttribute('data-on', '0');
      end.setAttribute('data-on', '0');
      return;
    }
    el.setAttribute('x1', line.x1.toFixed(1));
    el.setAttribute('y1', line.y1.toFixed(1));
    el.setAttribute('x2', line.x2.toFixed(1));
    el.setAttribute('y2', line.y2.toFixed(1));
    end.setAttribute('cx', line.x2.toFixed(1));
    end.setAttribute('cy', line.y2.toFixed(1));
    el.setAttribute('data-on', '1');
    end.setAttribute('data-on', '1');
  }
}

function viewportWidth(): number {
  return typeof window === 'undefined' ? 0 : window.innerWidth;
}
