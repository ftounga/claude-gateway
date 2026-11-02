import {
  ChangeDetectionStrategy, Component, ElementRef, InjectionToken, Injector, NgZone, OnDestroy, computed, effect, inject,
  input, output, signal, untracked, viewChild,
} from '@angular/core';
import { Observable } from 'rxjs';

import { AtelierMessage } from '../../../core/models/atelier.models';
import { AtelierService } from '../../../core/services/atelier.service';
import { DayTurn, FAMILY_TONE, FAMILY_WORD, Pearl, TurnTimeline, dayAxis, dayTurns } from './jarvis-timelines';

import { JarvisUniverse, clientKey } from '../../scenes/jarvis-universe.models';
import { ShowFilter } from '../../scenes/jarvis-show';
import { DayFigure, dayBoard, weatherRows } from './jarvis-day-board';
import { WEATHER_WORD } from './jarvis-weather';
import { IDLE_SCREEN, ScreenSignals } from '../jarvis-stage-director';
import {
  CockpitFolds, CockpitInsets, CockpitMode, cockpitInsets, cockpitMode, readFolds, writeFolds,
} from './jarvis-cockpit-layout';

/** Ce que le cockpit lit de la scène 3D (le composant, ou une doublure de test). */
export interface CockpitStage {
  shown(): boolean;
  readonly isNarrow: boolean;
  project(key: string): { x: number; y: number } | null;
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
  templateUrl: './jarvis-cockpit.component.html',
  styleUrl: './jarvis-cockpit.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-mode]': 'mode()',
    '(window:resize)': 'width.set(viewportWidth())',
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

  toggle(side: 'left' | 'right'): void {
    const next = { ...this.folds(), [side]: !this.folds()[side] };
    this.folds.set(next);
    writeFolds(next);
  }

  /** Une ligne touchée : un sujet se choisit ; les autres nœuds se regardent (inspecteur à venir, SF-236-19). */
  pick(item: CockpitNodeItem): void {
    if (item.subjectId) {
      this.choose.emit(item.subjectId);
    }
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
