import { Component, OnChanges, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import {
  AgendaView, CalendarItem, CalendarWindow, JarvisCalendarClient, agendaDays, friseSlot, nextItem, parisDay, parisHour,
  parisMinutes,
} from './jarvis-calendar';

/** Une journée de la vue : ses rendez-vous en heures, ses journées entières, et la frise. */
interface AgendaDay {
  day: string;
  label: string;
  timed: CalendarItem[];
  allDay: CalendarItem[];
}

const DAY_LABEL = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' });

/**
 * **La vue d'agenda** (F-243 / SF-243-02, D10) — ouverte par la passerelle quand Jarvis lit l'agenda (ordre d'écran
 * `agenda:jour|semaine:<date>`). Une **frise holographique** par journée (de 7 h à 21 h, élargie si un rendez-vous en
 * sort), les rendez-vous posés dessus, le rendez-vous **en cours ou le prochain** mis en avant (liseré plein et halo
 * orange, mot écrit « en cours » / « prochain »), puis la liste lisible. Les deux agendas sont déjà fusionnés par la
 * passerelle (aucun doublon de même UID). Aucune couleur nouvelle (§23). Le parent ne l'affiche **jamais** en
 * présentation.
 */
@Component({
  selector: 'app-jarvis-agenda',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <section class="jag" aria-label="Agenda">
      <header class="jag__head">
        <h2 class="jag__title">{{ view().mode === 'semaine' ? 'Cette semaine' : headLabel() }}</h2>
        @if (updated()) { <span class="jag__meta">à jour à {{ updated() }}</span> }
        <button mat-icon-button type="button" class="jag__close" aria-label="Fermer l’agenda" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      @if (loading()) {
        <p class="jag__hint">Lecture de l’agenda…</p>
      } @else if (error()) {
        <p class="jag__hint" role="alert">{{ error() }}</p>
      } @else {
        @for (d of days(); track d.day) {
          <div class="jag__day">
            @if (view().mode === 'semaine') { <h3 class="jag__day-label">{{ d.label }}</h3> }
            @for (a of d.allDay; track a.id) {
              <p class="jag__allday">Toute la journée — {{ a.title || '(sans titre)' }}</p>
            }
            <div class="jag__frise" aria-hidden="true">
              <span class="jag__tick jag__tick--start">{{ startHour() }} h</span>
              <span class="jag__tick jag__tick--end">{{ endHour() }} h</span>
              @if (nowLeft(d.day); as left) { <span class="jag__now" [style.left.%]="left"></span> }
              @for (i of d.timed; track i.id) {
                <span class="jag__block" [class.jag__block--next]="i.id === next()?.id"
                      [style.left.%]="slot(i, d.day).left" [style.width.%]="slot(i, d.day).width"></span>
              }
            </div>
            @if (d.timed.length === 0 && d.allDay.length === 0) {
              <p class="jag__hint jag__empty">Rien de prévu.</p>
            }
            <ul class="jag__list">
              @for (i of d.timed; track i.id) {
                <li class="jag__item" [class.jag__item--next]="i.id === next()?.id">
                  <span class="jag__hour">{{ hour(i.start) }}–{{ hour(i.end) }}</span>
                  <span class="jag__what">{{ i.title || '(sans titre)' }}@if (i.location) { <span class="jag__where"> · {{ i.location }}</span> }</span>
                  @if (i.id === next()?.id) { <span class="jag__state">{{ inProgress(i) ? 'en cours' : 'prochain' }}</span> }
                  @if (i.feedLabel) { <span class="jag__feed">{{ i.feedLabel }}</span> }
                </li>
              }
            </ul>
          </div>
        }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jag {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); left: var(--cg-space-4);
      width: min(560px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-3);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .jag__head { display: flex; align-items: center; gap: var(--cg-space-2); min-width: 0; }
    .jag__title { margin: 0; flex: 1; min-width: 0; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream);
      text-transform: capitalize; }
    .jag__meta, .jag__tick, .jag__hour { font-family: var(--cg-font-mono); font-size: 12px; opacity: .75; }
    .jag__close { color: var(--cg-cream); }
    .jag__hint { margin: 0; font-size: 13px; opacity: .8; }
    .jag__day { display: flex; flex-direction: column; gap: var(--cg-space-1); min-width: 0; }
    .jag__day-label { margin: 0; font-family: var(--cg-font-heading); font-size: 13px; text-transform: uppercase;
      letter-spacing: .12em; color: var(--cg-orange-2); }
    .jag__allday { margin: 0; font-size: 13px; padding: 0 var(--cg-space-1); border-left: 2px solid var(--cg-orange-2); }
    .jag__frise { position: relative; height: 22px; margin: 14px 0 2px; border-radius: 4px;
      background: color-mix(in srgb, var(--cg-orange) 4%, transparent);
      border-top: 1px solid color-mix(in srgb, var(--cg-orange) 35%, transparent);
      border-bottom: 1px solid color-mix(in srgb, var(--cg-orange) 35%, transparent); }
    .jag__tick { position: absolute; top: -15px; }
    .jag__tick--start { left: 0; }
    .jag__tick--end { right: 0; }
    .jag__now { position: absolute; top: -3px; bottom: -3px; width: 2px; background: var(--cg-cream); opacity: .75; }
    .jag__block { position: absolute; top: 3px; bottom: 3px; border-radius: 3px;
      background: color-mix(in srgb, var(--cg-orange) 45%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-orange) 75%, transparent); box-sizing: border-box; }
    .jag__block--next { background: var(--cg-orange); box-shadow: 0 0 10px color-mix(in srgb, var(--cg-orange) 75%, transparent); }
    .jag__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .jag__item { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0 var(--cg-space-2); font-size: 14px;
      padding: 2px var(--cg-space-1); border-left: 2px solid transparent; min-width: 0; }
    .jag__item--next { border-left-color: var(--cg-orange); background: color-mix(in srgb, var(--cg-orange) 18%, transparent); }
    .jag__what { flex: 1 1 160px; min-width: 0; }
    .jag__where { opacity: .75; }
    .jag__state { font-size: 12px; color: var(--cg-orange-2); text-transform: uppercase; letter-spacing: .08em; }
    .jag__feed { font-size: 12px; opacity: .6; }
    @media (max-width: 767px) {
      .jag { left: var(--cg-space-2); right: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
    }
  `],
})
export class JarvisAgendaComponent implements OnChanges {
  private readonly client = inject(JarvisCalendarClient);

  readonly view = input.required<AgendaView>();
  /** L'horloge (injectable par les tests). */
  readonly now = input<() => Date>(() => new Date());
  readonly closed = output<void>();

  readonly window = signal<CalendarWindow | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  readonly next = computed(() => nextItem(this.window()?.items ?? [], this.now()()));
  readonly updated = computed(() => {
    const at = this.window()?.updatedAt;
    return at ? parisHour(at) : '';
  });
  readonly headLabel = computed(() => DAY_LABEL.format(new Date(`${this.view().date}T00:00:00Z`)));

  /** La frise va de 7 h à 21 h, élargie aux heures pleines si un rendez-vous en sort. */
  readonly startHour = computed(() => {
    const timed = (this.window()?.items ?? []).filter((i) => !i.allDay);
    const first = Math.min(7 * 60, ...timed.map((i) => (parisDay(i.start) === this.view().date || this.view().mode === 'semaine'
      ? parisMinutes(i.start) : 7 * 60)));
    return Math.max(0, Math.floor(first / 60));
  });
  readonly endHour = computed(() => {
    const timed = (this.window()?.items ?? []).filter((i) => !i.allDay);
    const last = Math.max(21 * 60, ...timed.map((i) => {
      const m = parisMinutes(i.end);
      return m === 0 ? 24 * 60 : m;
    }));
    return Math.min(24, Math.ceil(last / 60));
  });

  readonly days = computed<AgendaDay[]>(() => {
    const items = this.window()?.items ?? [];
    return agendaDays(this.view()).map((day) => {
      const touching = items.filter((i) => parisDay(i.start) <= day && day <= lastDay(i));
      return {
        day,
        label: DAY_LABEL.format(new Date(`${day}T00:00:00Z`)),
        timed: touching.filter((i) => !i.allDay),
        allDay: touching.filter((i) => i.allDay),
      };
    });
  });

  ngOnChanges(): void {
    this.load();
  }

  load(): void {
    const days = agendaDays(this.view());
    const from = new Date(`${days[0]}T00:00:00Z`);
    const to = new Date(`${days[days.length - 1]}T00:00:00Z`);
    // Marge d'un jour de part et d'autre (fuseau de Paris) ; la passerelle borne à 31 jours.
    from.setUTCDate(from.getUTCDate() - 1);
    to.setUTCDate(to.getUTCDate() + 2);
    this.loading.set(true);
    this.error.set('');
    this.client.events(from.toISOString(), to.toISOString()).subscribe({
      next: (w) => {
        this.loading.set(false);
        const wanted = new Set(days);
        this.window.set({ ...w, items: w.items.filter((i) => this.inDays(i, wanted)) });
      },
      error: () => {
        this.loading.set(false);
        this.error.set('L’agenda ne répond pas pour l’instant.');
      },
    });
  }

  hour(iso: string): string {
    return parisHour(iso);
  }

  slot(item: CalendarItem, day: string): { left: number; width: number } {
    return friseSlot(item, day, this.startHour() * 60, this.endHour() * 60);
  }

  inProgress(item: CalendarItem): boolean {
    const now = this.now()().getTime();
    return new Date(item.start).getTime() <= now && new Date(item.end).getTime() > now;
  }

  /** La position de « maintenant » sur la frise du jour (ou `null` hors du jour). */
  nowLeft(day: string): number | null {
    const now = this.now()();
    if (parisDay(now.toISOString()) !== day) {
      return null;
    }
    const start = this.startHour() * 60;
    const span = Math.max(60, this.endHour() * 60 - start);
    const left = ((parisMinutes(now.toISOString()) - start) / span) * 100;
    return left > 0 && left < 100 ? left : null;
  }

  private inDays(i: CalendarItem, days: Set<string>): boolean {
    for (const d of days) {
      if (parisDay(i.start) <= d && d <= lastDay(i)) {
        return true;
      }
    }
    return false;
  }
}

/** La dernière journée touchée par un rendez-vous (la fin est exclue : une journée entière finit à minuit). */
function lastDay(i: CalendarItem): string {
  const end = new Date(i.end).getTime();
  const start = new Date(i.start).getTime();
  return parisDay(new Date(end > start ? end - 1 : start).toISOString());
}
