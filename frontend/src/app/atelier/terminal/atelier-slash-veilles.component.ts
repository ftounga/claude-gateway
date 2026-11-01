import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { Observable } from 'rxjs';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { ago, shortDateTime } from '../../core/models/routine.models';
import { JarvisWatch, runStatusLabel, watchScheduleLabel, watchStateLabel } from '../../core/models/veille.models';
import { VeilleService } from '../../core/services/veille.service';
import { formatEuros } from '../../shared/euros';

/**
 * **`/veilles` — les veilles Jarvis du sujet** (F-230 / SF-230-01) : un panneau du fil, **aucun tour modèle**.
 * La liste (état, plateforme, heures, prochain passage), et la suppression confirmée. Une veille se **pose** en le
 * demandant à l'agent (« mets-moi un suivi Jarvis… ») : il écrit la sonde, la carte en tête du fil la fait
 * tester puis activer.
 */
@Component({
  selector: 'app-atelier-slash-veilles',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (loading()) {
      <p class="veilles__hint">Chargement des veilles…</p>
    } @else if (watches().length === 0) {
      <p class="veilles__hint">
        Aucune veille sur ce sujet. Demandez-la à l'agent : « mets-moi un suivi Jarvis sur ce chantier, via les tickets
        Jira de tel tableau, à 9 h et 14 h ». Il écrit la sonde ; vous la testez puis l'activez.
      </p>
    } @else {
      <ul class="veilles__list">
        @for (watch of watches(); track watch.id) {
          <li class="veilles__item">
            <div class="veilles__head">
              <span class="veilles__name">{{ watch.name }}</span>
              <span class="veilles__state" [class.veilles__state--off]="!watch.active">{{ state(watch) }}</span>
            </div>
            <p class="veilles__line">{{ watch.platform }} · {{ schedule(watch) }}@if (watch.chantierNumber) { · chantier {{ watch.chantierNumber }}}</p>
            <p class="veilles__line veilles__scope">{{ watch.scope }}</p>
            @if (watch.lastRunAt) {
              <p class="veilles__line">Dernier passage : {{ since(watch.lastRunAt) }} · {{ lastRun(watch) }}</p>
            }
            @if (watch.nextRunAt) {
              <p class="veilles__line">Prochain passage : {{ when(watch.nextRunAt) }}</p>
            }
            @if (!watch.proposed) {
              <p class="veilles__line">Rédaction IA ce mois-ci : {{ money(watch.monthSpentEur ?? 0) }} sur {{ money(watch.monthlyBudgetEur) }}</p>
            }
            @if (!readOnly()) {
              <div class="veilles__gestures">
                @if (!watch.proposed) {
                  @if (watch.active) {
                    <button mat-stroked-button type="button" class="veilles__secondary" [disabled]="busy()"
                            [attr.aria-label]="'Mettre en pause la veille ' + watch.name" (click)="setActive(watch, false)">
                      <mat-icon aria-hidden="true">pause</mat-icon> Pause
                    </button>
                  } @else {
                    <button mat-stroked-button type="button" class="veilles__secondary" [disabled]="busy()"
                            [attr.aria-label]="'Reprendre la veille ' + watch.name" (click)="setActive(watch, true)">
                      <mat-icon aria-hidden="true">play_arrow</mat-icon> Reprendre
                    </button>
                  }
                  <button mat-stroked-button type="button" class="veilles__secondary" [disabled]="busy()"
                          [attr.aria-label]="'Lancer maintenant la veille ' + watch.name" (click)="runNow(watch)">
                    <mat-icon aria-hidden="true">radar</mat-icon> Lancer maintenant
                  </button>
                }
                <button mat-button type="button" class="veilles__secondary" [disabled]="busy()"
                        [attr.aria-label]="'Supprimer la veille ' + watch.name" (click)="remove(watch)">
                  <mat-icon aria-hidden="true">delete</mat-icon> Supprimer
                </button>
              </div>
            }
          </li>
        }
      </ul>
    }
    @if (notice()) {
      <p class="veilles__notice" role="status">{{ notice() }}</p>
    }
    @if (failure()) {
      <p class="veilles__error" role="alert">{{ failure() }}</p>
    }
  `,
  styles: `
    :host { display: block; font-family: var(--cg-font-body); }
    .veilles__hint, .veilles__line, .veilles__error, .veilles__notice { margin: var(--cg-space-1) 0 0; font-size: 13px; overflow-wrap: anywhere; }
    .veilles__hint { opacity: 0.85; }
    .veilles__list { list-style: none; margin: 0; padding: 0; }
    .veilles__item { padding: var(--cg-space-2) 0; border-bottom: 1px solid var(--cg-divider); }
    .veilles__item:last-child { border-bottom: none; }
    .veilles__head { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cg-space-2); }
    .veilles__name { font-weight: 600; overflow-wrap: anywhere; }
    .veilles__state { font-size: 12px; color: var(--cg-success); }
    .veilles__state--off { color: var(--cg-orange-2); }
    .veilles__scope { white-space: pre-wrap; opacity: 0.85; }
    .veilles__error { color: var(--cg-orange-2); }
    .veilles__gestures { display: flex; flex-wrap: wrap; gap: var(--cg-space-2); margin-top: var(--cg-space-1); }
    .veilles__gestures button { min-height: 44px; }
    .veilles__secondary { --mdc-text-button-label-text-color: inherit; color: inherit; }
  `,
})
export class AtelierSlashVeillesComponent {
  private readonly service = inject(VeilleService);
  private readonly dialog = inject(MatDialog);

  readonly workspaceId = input<string | null>(null);
  readonly readOnly = input(false);
  /** Une veille a changé ici : la bande se relit. */
  readonly changed = output<void>();

  readonly watches = signal<JarvisWatch[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly failure = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      if (id) {
        this.load(id);
      }
    });
  }

  private load(workspaceId: string): void {
    this.loading.set(true);
    this.service.list(workspaceId).subscribe({
      next: (watches) => {
        this.watches.set(watches);
        this.loading.set(false);
      },
      error: () => {
        this.watches.set([]);
        this.loading.set(false);
        this.failure.set('Les veilles n’ont pas pu être lues. Réessayez dans un instant.');
      },
    });
  }

  schedule(watch: JarvisWatch): string {
    return watchScheduleLabel(watch);
  }

  state(watch: JarvisWatch): string {
    return watchStateLabel(watch);
  }

  when(iso: string | null): string {
    return shortDateTime(iso);
  }

  since(iso: string | null): string {
    return ago(iso);
  }

  lastRun(watch: JarvisWatch): string {
    return runStatusLabel(watch.lastRunStatus);
  }

  money(value: number | null): string {
    return formatEuros(value);
  }

  readonly notice = signal<string | null>(null);

  /** [Pause] / [Reprendre] (SF-230-04) : la reprise repart du prochain créneau, sans rafale de rattrapage. */
  setActive(watch: JarvisWatch, active: boolean): void {
    const workspaceId = this.workspaceId();
    if (workspaceId) {
      this.notice.set(null);
      this.run(this.service.update(workspaceId, watch.id, { active }));
    }
  }

  /** [Lancer maintenant] (SF-230-02/04) : un passage, tout de suite ; l'issue est dite ici. */
  runNow(watch: JarvisWatch): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    this.notice.set(null);
    this.busy.set(true);
    this.failure.set(null);
    this.service.runNow(workspaceId, watch.id).subscribe({
      next: (run) => {
        this.busy.set(false);
        this.notice.set(`« ${watch.name} » : ${runStatusLabel(run.status, run.changeCount)}.`);
        this.load(workspaceId);
        this.changed.emit();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.failure.set(error?.error?.message ?? 'Le passage a échoué. Réessayez dans un instant.');
      },
    });
  }

  remove(watch: JarvisWatch): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Supprimer la veille ?',
      message: `« ${watch.name} » ne passera plus. Sa sonde reste sur le poste.`,
      confirmLabel: 'Supprimer',
    };
    this.dialog.open(ConfirmDialogComponent, { data, width: '420px' }).afterClosed().subscribe((ok) => {
      if (ok) {
        this.run(this.service.remove(workspaceId, watch.id));
      }
    });
  }

  protected run(call: Observable<unknown>): void {
    this.busy.set(true);
    this.failure.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        const id = this.workspaceId();
        if (id) {
          this.load(id);
        }
        this.changed.emit();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.failure.set(error?.error?.message ?? 'Le geste a échoué. Réessayez dans un instant.');
      },
    });
  }
}
