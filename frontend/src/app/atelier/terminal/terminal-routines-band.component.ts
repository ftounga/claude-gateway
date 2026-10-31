import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Observable } from 'rxjs';

import { Routine, RoutineRun, ago, cadenceLabel, eur, runIcon } from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';

/**
 * **La bande des routines, en tête du fil du sujet** (F-183).
 *
 * <p>SF-183-01 : la **carte de validation** d'une routine proposée par l'agent — rien n'est programmé
 * avant [Activer] ; [Modifier] ouvre le panneau `/routines` ; [Refuser] la supprime.</p>
 *
 * <p>SF-183-04 : **le compte rendu des exécutions** — « Routine du matin — 3 attentes mises à jour »,
 * il y a combien, ce qu'elle a coûté ; [Voir] amène la demande de la routine dans le fil ; [×] ferme la
 * ligne (elle ne revient pas).</p>
 *
 * <p>Se relit à l'ouverture du sujet et quand `refresh` change (fin de tour, geste du panneau).
 * Absente quand il n'y a rien à montrer.</p>
 */
@Component({
  selector: 'app-terminal-routines-band',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @for (run of runs(); track run.id) {
      <div class="routine-run" role="status" [class.routine-run--stopped]="run.status !== 'DONE'">
        <mat-icon class="routine-run__icon" aria-hidden="true">{{ icon(run) }}</mat-icon>
        <span class="routine-run__summary">{{ run.summary || run.routineName }}</span>
        <span class="routine-run__meta">{{ when(run) }}@if (run.costUsd) { · {{ money(run.costEur) }}}</span>
        @if (run.status !== 'MISSED_OFFLINE' && run.status !== 'SKIPPED_BUDGET' && run.routineName) {
          <button mat-button type="button" class="routine-run__see" (click)="reveal.emit(run.routineName)">Voir</button>
        }
        @if (!readOnly()) {
          <button mat-icon-button type="button" class="routine-run__close" [disabled]="busy()"
                  [attr.aria-label]="'Fermer : ' + (run.summary || 'compte rendu')" (click)="acknowledge(run)">
            <mat-icon>close</mat-icon>
          </button>
        }
      </div>
    }
    @for (routine of proposals(); track routine.id) {
      <div class="routine-card" role="group" [attr.aria-label]="'Routine proposée : ' + routine.name">
        <mat-icon class="routine-card__icon" aria-hidden="true">lightbulb</mat-icon>
        <div class="routine-card__body">
          <p class="routine-card__headline">Routine proposée · {{ routine.name }}</p>
          <p class="routine-card__when">{{ cadence(routine) }} · {{ money(routine.maxRunEur) }} max par exécution</p>
          <p class="routine-card__instruction">{{ routine.instruction }}</p>
          @if (!readOnly()) {
            <div class="routine-card__gestures">
              <button mat-flat-button type="button" class="routine-card__apply" [disabled]="busy()" (click)="accept(routine)">Activer</button>
              <button mat-stroked-button type="button" class="routine-card__secondary" [disabled]="busy()" (click)="openPanel.emit()">Modifier</button>
              <button mat-button type="button" class="routine-card__secondary" [disabled]="busy()" (click)="refuse(routine)">Refuser</button>
            </div>
          } @else {
            <p class="routine-card__when">En attente de validation.</p>
          }
          @if (error()) {
            <p class="routine-card__error" role="alert">{{ error() }}</p>
          }
        </div>
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .routine-run {
      display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-1) var(--cg-space-2);
      margin: var(--cg-space-2) 0; padding: var(--cg-space-1) var(--cg-space-1) var(--cg-space-1) var(--cg-space-3);
      border: 1px solid var(--cg-divider); border-left: 4px solid var(--cg-success); border-radius: 8px;
      font-family: var(--cg-font-body); color: inherit;
    }
    .routine-run--stopped { border-left-color: var(--cg-orange-2); }
    .routine-run__icon { flex: none; color: var(--cg-success); }
    .routine-run--stopped .routine-run__icon { color: var(--cg-orange-2); }
    .routine-run__summary { flex: 1 1 200px; min-width: 0; font-weight: 600; overflow-wrap: anywhere; }
    .routine-run__meta { font-size: 12px; opacity: 0.8; font-variant-numeric: tabular-nums; }
    .routine-run__see { min-height: 44px; --mdc-text-button-label-text-color: inherit; color: inherit; }
    .routine-run__close { --mdc-icon-button-icon-color: inherit; color: inherit; }
    .routine-card {
      display: flex; gap: var(--cg-space-2); margin: var(--cg-space-2) 0; padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-orange-2); border-left-width: 4px; border-radius: 8px; color: inherit;
      font-family: var(--cg-font-body);
    }
    .routine-card__icon { color: var(--cg-orange-2); flex: none; }
    .routine-card__body { min-width: 0; flex: 1; }
    .routine-card__headline { margin: 0; font-weight: 600; color: var(--cg-orange-2); overflow-wrap: anywhere; }
    .routine-card__when, .routine-card__instruction, .routine-card__error {
      margin: var(--cg-space-1) 0 0; font-size: 13px; overflow-wrap: anywhere;
    }
    .routine-card__instruction { white-space: pre-wrap; opacity: 0.85; }
    .routine-card__error { color: var(--cg-orange-2); }
    .routine-card__gestures { display: flex; flex-wrap: wrap; gap: var(--cg-space-2); margin-top: var(--cg-space-2); }
    .routine-card__gestures button { min-height: 44px; }
    .routine-card__apply {
      --mdc-filled-button-container-color: var(--cg-accent);
      --mdc-filled-button-label-text-color: var(--cg-surface);
    }
    .routine-card__secondary {
      --mdc-outlined-button-label-text-color: inherit;
      --mdc-text-button-label-text-color: inherit;
      color: inherit;
    }
  `,
})
export class TerminalRoutinesBandComponent {
  private readonly service = inject(RoutineService);

  /** Le sujet du terminal. */
  readonly workspaceId = input<string | null>(null);
  /** Change à chaque fin de tour ou geste du panneau : la bande se relit. */
  readonly refresh = input(0);
  /** Lecture seule (mosaïque) : aucun geste. */
  readonly readOnly = input(false);
  /** [Modifier] : ouvrir le panneau `/routines`. */
  readonly openPanel = output<void>();
  /** [Voir] : amener la demande de cette routine dans le fil (SF-183-04). */
  readonly reveal = output<string>();
  /** Une routine a changé ici (activée, refusée). */
  readonly changed = output<void>();

  readonly routines = signal<Routine[]>([]);
  /** Les comptes rendus non lus (SF-183-04). */
  readonly runs = signal<RoutineRun[]>([]);
  readonly proposals = computed(() => this.routines().filter((r) => r.proposed));
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      this.refresh();
      if (id) {
        this.load(id);
      } else {
        this.routines.set([]);
        this.runs.set([]);
      }
    });
  }

  private load(workspaceId: string): void {
    this.service.list(workspaceId).subscribe({
      next: (routines) => this.routines.set(routines),
      // Une routine n'est jamais une raison de casser le terminal : la bande reste vide.
      error: () => this.routines.set([]),
    });
    this.service.unseen(workspaceId).subscribe({
      next: (runs) => this.runs.set(runs),
      error: () => this.runs.set([]),
    });
  }

  icon(run: RoutineRun): string {
    return runIcon(run.status);
  }

  when(run: RoutineRun): string {
    return ago(run.finishedAt ?? run.startedAt);
  }

  /** [×] : la ligne est fermée et ne revient pas. */
  acknowledge(run: RoutineRun): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    this.runs.update((all) => all.filter((r) => r.id !== run.id));
    this.service.acknowledge(workspaceId, run.id).subscribe({
      // L'acquittement est best-effort : en échec, la ligne reviendra à la prochaine relecture.
      error: () => undefined,
    });
  }

  cadence(routine: Routine): string {
    return cadenceLabel(routine);
  }

  money(value: number | null | undefined): string {
    return eur(value);
  }

  accept(routine: Routine): void {
    this.gesture(routine, () => this.service.accept(routine.workspaceId, routine.id));
  }

  refuse(routine: Routine): void {
    this.gesture(routine, () => this.service.remove(routine.workspaceId, routine.id));
  }

  private gesture(routine: Routine, call: () => Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    call().subscribe({
      next: () => {
        this.busy.set(false);
        this.routines.update((all) => all.filter((r) => r.id !== routine.id || !r.proposed));
        const id = this.workspaceId();
        if (id) {
          this.load(id);
        }
        this.changed.emit();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(error?.error?.message ?? 'Le geste a échoué. Réessayez dans un instant.');
      },
    });
  }
}
