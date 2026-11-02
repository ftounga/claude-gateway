import {
  ChangeDetectionStrategy, Component, Injector, NgZone, OnDestroy, OnInit, effect, inject, input, output, signal,
  untracked,
} from '@angular/core';

import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { VeilleService } from '../../../core/services/veille.service';
import { NightAlert, NightWake, NightWatch, nextWatches, nightAlerts } from './jarvis-night';

/** Pas de l'horloge du veilleur (et du réveil qui retombe). */
export const NIGHT_TICK_MS = 1000;

/**
 * F-236 / SF-236-24 — **le veilleur de nuit** (NF-13) : un voile sombre sur la scène, l'horloge, les prochaines
 * veilles (lues une fois à l'entrée, `GET /api/jarvis/watches`, filtrée `user_id`) et les alertes de l'ambiance.
 * Une alerte nouvelle ou un toucher réveille l'écran une minute. « Quitter le veilleur » ou Échap le referme.
 */
@Component({
  selector: 'app-jarvis-night',
  template: `
    <div class="nt" [class.nt--awake]="awake()" role="region" aria-label="Veilleur de nuit" (pointerdown)="touch()">
      <p class="nt__clock" aria-live="off">{{ clock() }}</p>
      @if (alerts().length > 0) {
        <ul class="nt__alerts" role="alert">
          @for (a of alerts(); track a.key) {
            <li>{{ a.text }}</li>
          }
        </ul>
      }
      @if (watches().length > 0) {
        <ul class="nt__watches" aria-label="Prochaines veilles">
          @for (w of watches(); track w.id) {
            <li><span class="nt__when">{{ w.when }}</span> {{ w.name }} <span class="nt__subject">· {{ w.subject }}</span></li>
          }
        </ul>
      } @else {
        <p class="nt__none">Aucune veille à venir.</p>
      }
      <button type="button" class="nt__exit" (click)="exit.emit()">Quitter le veilleur</button>
    </div>
  `,
  styleUrl: './jarvis-night.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(window:keydown.escape)': 'exit.emit()' },
})
export class JarvisNightComponent implements OnInit, OnDestroy {
  readonly ambient = input<Ambient | null>(null);
  readonly exit = output<void>();

  private readonly injector = inject(Injector);
  private readonly zone = inject(NgZone);
  private readonly wake = new NightWake();
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly clock = signal(clockOf(new Date()));
  readonly alerts = signal<NightAlert[]>([]);
  readonly watches = signal<NightWatch[]>([]);
  readonly awake = signal(false);

  constructor() {
    effect(() => {
      const list = nightAlerts(this.ambient());
      untracked(() => {
        this.alerts.set(list);
        this.wake.observe(list, Date.now());
        this.awake.set(this.wake.awake(Date.now()));
      });
    });
  }

  ngOnInit(): void {
    try {
      this.injector.get(VeilleService).listForAccount().subscribe({
        next: (list) => this.watches.set(nextWatches(list, new Date())),
        error: () => this.watches.set([]),
      });
    } catch {
      this.watches.set([]);
    }
    this.zone.runOutsideAngular(() => {
      this.timer = setInterval(() => this.tick(Date.now()), NIGHT_TICK_MS);
    });
  }

  /** Le temps passe : horloge, réveil qui retombe (public pour les tests). */
  tick(now: number): void {
    this.clock.set(clockOf(new Date(now)));
    this.awake.set(this.wake.awake(now));
  }

  touch(): void {
    this.wake.touch(Date.now());
    this.awake.set(true);
  }

  ngOnDestroy(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}

function clockOf(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
