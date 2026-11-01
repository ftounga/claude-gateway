import { Component, effect, inject, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

import { shortDateTime } from '../../core/models/routine.models';
import { JarvisWatchSummary, watchScheduleLabel, watchStateLabel } from '../../core/models/veille.models';
import { VeilleService } from '../../core/services/veille.service';

/**
 * **Les veilles Jarvis dans le tableau du client** (F-230 / SF-230-04) : ce que Jarvis surveille pour ce poste —
 * nom, sujet, état, prochain passage. Un clic ouvre le sujet, où `/veilles` permet de les tenir. Absente quand il
 * n'y a aucune veille : le tableau reste calme.
 */
@Component({
  selector: 'app-client-board-veilles',
  imports: [MatIconModule, RouterLink],
  template: `
    @if (watches().length) {
      <section class="cbv" aria-labelledby="cbv-title">
        <h3 id="cbv-title" class="cbv__title">
          <mat-icon aria-hidden="true">radar</mat-icon> Veilles Jarvis
        </h3>
        <ul class="cbv__list">
          @for (item of watches(); track item.watch.id) {
            <li class="cbv__item" [class.cbv__item--off]="!item.watch.active || item.watch.broken">
              <a class="cbv__link" [routerLink]="['/atelier', item.watch.workspaceId]">
                <span class="cbv__name">{{ item.watch.name }}</span>
                <span class="cbv__meta">{{ item.workspaceName }} · {{ item.watch.platform }} · {{ schedule(item) }}</span>
              </a>
              <span class="cbv__state">{{ state(item) }}@if (item.watch.nextRunAt) { · {{ when(item.watch.nextRunAt) }}}</span>
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    .cbv {
      margin: 0 0 var(--cg-space-3); padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider); border-radius: 8px; font-family: var(--cg-font-body);
    }
    .cbv__title {
      display: flex; align-items: center; gap: var(--cg-space-1); margin: 0; font-size: 14px; font-weight: 600;
    }
    .cbv__list { list-style: none; margin: var(--cg-space-1) 0 0; padding: 0; }
    .cbv__item {
      display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: var(--cg-space-1)
        var(--cg-space-2); padding: var(--cg-space-1) 0; border-top: 1px solid var(--cg-divider); font-size: 13px;
    }
    .cbv__item:first-child { border-top: none; }
    .cbv__link { display: flex; flex-direction: column; min-width: 0; color: inherit; text-decoration: none; }
    .cbv__link:hover .cbv__name, .cbv__link:focus-visible .cbv__name { text-decoration: underline; }
    .cbv__name { font-weight: 600; overflow-wrap: anywhere; }
    .cbv__meta { opacity: 0.8; overflow-wrap: anywhere; }
    .cbv__state { font-size: 12px; color: var(--cg-success); }
    .cbv__item--off .cbv__state { color: var(--cg-orange-2); }
  `,
})
export class ClientBoardVeillesComponent {
  private readonly service = inject(VeilleService);

  /** Le poste du tableau. */
  readonly hostId = input<string | null>(null);

  readonly watches = signal<JarvisWatchSummary[]>([]);

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      if (!hostId) {
        this.watches.set([]);
        return;
      }
      this.service.listForAccount(hostId).subscribe({
        next: (watches) => this.watches.set(watches),
        // Les veilles ne sont jamais une raison de casser le tableau : la section reste absente.
        error: () => this.watches.set([]),
      });
    });
  }

  schedule(item: JarvisWatchSummary): string {
    return watchScheduleLabel(item.watch);
  }

  state(item: JarvisWatchSummary): string {
    return watchStateLabel(item.watch);
  }

  when(iso: string | null): string {
    return shortDateTime(iso);
  }
}
