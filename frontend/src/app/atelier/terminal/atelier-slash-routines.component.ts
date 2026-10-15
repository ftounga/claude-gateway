import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { Observable } from 'rxjs';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import {
  ROUTINE_DAYS, Routine, RoutineCadence, RoutineRequest, RoutineRun, browserTimeZone, cadenceLabel, runStatusLabel,
  shortDateTime, usd,
} from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';

/** Le brouillon du formulaire (création ou modification). */
export interface RoutineDraft {
  name: string;
  instruction: string;
  cadence: RoutineCadence;
  dayOfWeek: number;
  runTime: string;
  maxRunUsd: number;
}

/** Brouillon vierge : jours ouvrés à 8 h, 5 $ par exécution (défauts serveur). */
export function emptyDraft(): RoutineDraft {
  return { name: '', instruction: '', cadence: 'WEEKDAYS', dayOfWeek: 1, runTime: '08:00', maxRunUsd: 5 };
}

/** Le brouillon d'une routine existante. */
export function draftOf(routine: Routine): RoutineDraft {
  return {
    name: routine.name,
    instruction: routine.instruction,
    cadence: routine.cadence,
    dayOfWeek: routine.dayOfWeek ?? 1,
    runTime: routine.runTime,
    maxRunUsd: routine.maxRunUsd,
  };
}

/** Les erreurs lisibles du brouillon (mêmes règles que la gateway). Vide = envoyable. */
export function draftErrors(draft: RoutineDraft): string[] {
  const errors: string[] = [];
  if (!draft.name.trim()) {
    errors.push('Le nom est obligatoire.');
  } else if (draft.name.trim().length > 80) {
    errors.push('Le nom dépasse 80 caractères.');
  }
  if (!draft.instruction.trim()) {
    errors.push('La consigne est obligatoire.');
  } else if (draft.instruction.trim().length > 4000) {
    errors.push('La consigne dépasse 4000 caractères.');
  }
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(draft.runTime ?? '')) {
    errors.push("L'heure s'écrit HH:mm.");
  }
  if (!(draft.maxRunUsd >= 0.1 && draft.maxRunUsd <= 50)) {
    errors.push('Le budget par exécution va de 0,10 $ à 50,00 $.');
  }
  return errors;
}

/** La requête d'un brouillon (le fuseau est celui du navigateur, donc du poste). */
export function requestOf(draft: RoutineDraft): RoutineRequest {
  return {
    name: draft.name.trim(),
    instruction: draft.instruction.trim(),
    cadence: draft.cadence,
    dayOfWeek: draft.cadence === 'WEEKLY' ? draft.dayOfWeek : null,
    runTime: draft.runTime,
    timeZone: browserTimeZone(),
    maxRunUsd: Math.round(draft.maxRunUsd * 100) / 100,
  };
}

/**
 * **Le panneau `/routines`** (F-183 / SF-183-01) : déclarer, modifier, mettre en pause, supprimer les
 * routines du sujet, et valider les propositions de l'agent. Une routine tourne seule, sans écran, dans
 * ce terminal — la commande, elle, ne déclenche **aucun tour**.
 *
 * <p>Corps de panneau du fil (cadre sombre) ; le formulaire vit dans une carte claire pour que les
 * champs `mat-form-field` outline restent lisibles. Jetons `--cg-*` uniquement.</p>
 */
@Component({
  selector: 'app-atelier-slash-routines',
  imports: [
    FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressSpinnerModule,
    MatSelectModule,
  ],
  template: `
    @switch (state()) {
      @case ('loading') {
        <p class="routines-note">
          <mat-spinner diameter="18" strokeWidth="2" aria-hidden="true"></mat-spinner>
          Lecture des routines…
        </p>
      }
      @case ('error') {
        <p class="routines-note routines-note--error">Routines indisponibles pour l'instant.</p>
      }
      @default {
        @if (routines().length === 0 && !editing()) {
          <p class="routines-note">
            Aucune routine. Une routine exécute une consigne à heure fixe dans ce terminal, sans écran ouvert,
            sur le poste.
          </p>
        }
        <ul class="routines-list">
          @for (routine of routines(); track routine.id) {
            <li class="routine" [class.routine--proposed]="routine.proposed" [class.routine--paused]="!routine.active && !routine.proposed">
              <div class="routine__head">
                <mat-icon class="routine__icon" aria-hidden="true">{{ routine.proposed ? 'lightbulb' : 'schedule' }}</mat-icon>
                <span class="routine__name">{{ routine.name }}</span>
                <span class="routine__badge">{{ statusLabel(routine) }}</span>
              </div>
              <p class="routine__when">
                {{ cadence(routine) }} · {{ money(routine.maxRunUsd) }} max par exécution
                @if (routine.nextRunAt) {
                  · prochain passage {{ when(routine.nextRunAt) }}
                }
              </p>
              <p class="routine__instruction">{{ routine.instruction }}</p>
              @if (routine.lastRun; as last) {
                <p class="routine__last">
                  Dernier passage {{ when(last.startedAt) }} · {{ runLabel(last) }}
                  @if (last.costUsd) {
                    · {{ money(last.costUsd) }}
                  }
                </p>
              }
              @if (historyOf() === routine.id) {
                <ul class="routine__history" aria-label="Historique des exécutions">
                  @for (run of history(); track run.id) {
                    <li>{{ when(run.startedAt) }} · {{ runLabel(run) }}@if (run.costUsd) { · {{ money(run.costUsd) }}}</li>
                  } @empty {
                    <li>Aucune exécution pour l'instant.</li>
                  }
                </ul>
              }
              @if (!readOnly()) {
                <div class="routine__gestures">
                  @if (routine.proposed) {
                    <button mat-flat-button type="button" class="routine__primary" [disabled]="busy()" (click)="accept(routine)">Activer</button>
                    <button mat-stroked-button type="button" class="routine__secondary" [disabled]="busy()" (click)="edit(routine)">Modifier</button>
                    <button mat-button type="button" class="routine__secondary" [disabled]="busy()" (click)="remove(routine, true)">Refuser</button>
                  } @else {
                    <button mat-flat-button type="button" class="routine__primary" [disabled]="busy()" (click)="runNow(routine)">Lancer maintenant</button>
                    <button mat-stroked-button type="button" class="routine__secondary" [disabled]="busy()" (click)="toggleHistory(routine)">
                      {{ historyOf() === routine.id ? 'Masquer l’historique' : 'Historique' }}
                    </button>
                    <button mat-stroked-button type="button" class="routine__secondary" [disabled]="busy()" (click)="edit(routine)">Modifier</button>
                    <button mat-stroked-button type="button" class="routine__secondary" [disabled]="busy()" (click)="toggle(routine)">
                      {{ routine.active ? 'Mettre en pause' : 'Activer' }}
                    </button>
                    <button mat-button type="button" class="routine__secondary" [disabled]="busy()" (click)="remove(routine, false)">Supprimer</button>
                  }
                </div>
              }
            </li>
          }
        </ul>

        @if (!readOnly()) {
          @if (editing()) {
            <form class="routine-form" (submit)="$event.preventDefault(); save()">
              <p class="routine-form__title">{{ editingId() ? 'Modifier la routine' : 'Nouvelle routine' }}</p>
              <mat-form-field appearance="outline" class="routine-form__field">
                <mat-label>Nom</mat-label>
                <input matInput name="name" [(ngModel)]="draft.name" maxlength="80" required placeholder="Point du matin">
              </mat-form-field>
              <mat-form-field appearance="outline" class="routine-form__field">
                <mat-label>Consigne envoyée à chaque exécution</mat-label>
                <textarea matInput name="instruction" [(ngModel)]="draft.instruction" maxlength="4000" rows="3" required
                  placeholder="le CR du daily d'aujourd'hui, version scriptée"></textarea>
              </mat-form-field>
              <div class="routine-form__row">
                <mat-form-field appearance="outline" class="routine-form__field">
                  <mat-label>Rythme</mat-label>
                  <mat-select name="cadence" [(ngModel)]="draft.cadence">
                    <mat-option value="DAILY">Chaque jour</mat-option>
                    <mat-option value="WEEKDAYS">Jours ouvrés (lundi → vendredi)</mat-option>
                    <mat-option value="WEEKLY">Une fois par semaine</mat-option>
                  </mat-select>
                </mat-form-field>
                @if (draft.cadence === 'WEEKLY') {
                  <mat-form-field appearance="outline" class="routine-form__field">
                    <mat-label>Jour</mat-label>
                    <mat-select name="dayOfWeek" [(ngModel)]="draft.dayOfWeek">
                      @for (day of days; track day.value) {
                        <mat-option [value]="day.value">{{ day.label }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                }
                <mat-form-field appearance="outline" class="routine-form__field routine-form__field--short">
                  <mat-label>Heure (poste)</mat-label>
                  <input matInput type="time" name="runTime" [(ngModel)]="draft.runTime" required>
                </mat-form-field>
                <mat-form-field appearance="outline" class="routine-form__field routine-form__field--short">
                  <mat-label>Budget par exécution ($)</mat-label>
                  <input matInput type="number" name="maxRunUsd" [(ngModel)]="draft.maxRunUsd" min="0.1" max="50" step="0.5" required>
                </mat-form-field>
              </div>
              @for (message of errors(); track message) {
                <p class="routine-form__error" role="alert">{{ message }}</p>
              }
              <div class="routine-form__gestures">
                <button mat-flat-button type="submit" class="routine__primary" [disabled]="busy()">
                  {{ editingId() ? 'Enregistrer' : 'Créer la routine' }}
                </button>
                <button mat-button type="button" (click)="cancel()">Annuler</button>
              </div>
            </form>
          } @else {
            <button mat-stroked-button type="button" class="routines-add" (click)="startCreate()">
              <mat-icon>add</mat-icon>
              Nouvelle routine
            </button>
          }
        }
        @if (notice()) {
          <p class="routines-note routines-note--ok" role="status">{{ notice() }}</p>
        }
        @if (failure()) {
          <p class="routines-note routines-note--error" role="alert">{{ failure() }}</p>
        }
      }
    }
  `,
  styles: `
    :host { display: block; font-family: var(--cg-font-body); }
    .routines-note {
      display: flex; align-items: center; gap: var(--cg-space-2); margin: 0; min-height: 44px;
      font-size: 13px; color: var(--cg-text-secondary);
    }
    .routines-note--error { color: var(--cg-orange-2); }
    .routines-list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--cg-space-2); }
    .routine {
      padding: var(--cg-space-2) var(--cg-space-3); border: 1px solid var(--cg-divider); border-radius: 8px;
    }
    .routine--proposed { border-color: var(--cg-orange-2); border-left-width: 4px; }
    .routine--paused { opacity: 0.75; }
    .routine__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .routine__icon { color: var(--cg-orange-2); flex: none; }
    .routine__name { font-weight: 600; overflow-wrap: anywhere; }
    .routine__badge {
      margin-left: auto; padding: 0 var(--cg-space-2); border: 1px solid var(--cg-divider); border-radius: 12px;
      font-size: 11px; white-space: nowrap;
    }
    .routine__when, .routine__instruction { margin: var(--cg-space-1) 0 0; font-size: 13px; overflow-wrap: anywhere; }
    .routine__when { font-variant-numeric: tabular-nums; color: var(--cg-text-secondary); }
    .routine__instruction { white-space: pre-wrap; }
    .routine__last { margin: var(--cg-space-1) 0 0; font-size: 12px; color: var(--cg-text-secondary); }
    .routine__history {
      margin: var(--cg-space-1) 0 0; padding-left: var(--cg-space-4); font-size: 12px;
      font-variant-numeric: tabular-nums;
    }
    .routines-note--ok { color: inherit; }
    .routine__gestures, .routine-form__gestures {
      display: flex; flex-wrap: wrap; gap: var(--cg-space-2); margin-top: var(--cg-space-2);
    }
    .routine__gestures button, .routine-form__gestures button, .routines-add { min-height: 44px; }
    .routine__primary {
      --mdc-filled-button-container-color: var(--cg-accent);
      --mdc-filled-button-label-text-color: var(--cg-surface);
    }
    .routine__secondary, .routines-add {
      --mdc-outlined-button-label-text-color: inherit;
      --mdc-text-button-label-text-color: inherit;
      color: inherit;
    }
    .routines-add { margin-top: var(--cg-space-3); }
    .routine-form {
      margin-top: var(--cg-space-3); padding: var(--cg-space-3); border-radius: 8px;
      background: var(--cg-surface); color: var(--cg-text-primary);
    }
    .routine-form__title { margin: 0 0 var(--cg-space-2); font-weight: 600; }
    .routine-form__row { display: flex; flex-wrap: wrap; gap: 0 var(--cg-space-2); }
    .routine-form__field { width: 100%; }
    .routine-form__row .routine-form__field { flex: 1 1 200px; width: auto; }
    .routine-form__row .routine-form__field--short { flex: 1 1 140px; }
    .routine-form__error { margin: 0 0 var(--cg-space-1); font-size: 13px; color: var(--cg-error); }
  `,
})
export class AtelierSlashRoutinesComponent {
  private readonly service = inject(RoutineService);
  private readonly dialog = inject(MatDialog);

  /** Le sujet du terminal. */
  readonly workspaceId = input<string | null>(null);
  /** Lecture seule (mosaïque) : aucun geste. */
  readonly readOnly = input(false);
  /** Une routine a changé : la bande au-dessus du fil se relit. */
  readonly changed = output<void>();
  /** Une routine vient d'être lancée : le terminal se rebranche sur son tour (F-84). */
  readonly started = output<void>();

  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly routines = signal<Routine[]>([]);
  readonly editing = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly busy = signal(false);
  readonly errors = signal<string[]>([]);
  readonly failure = signal<string | null>(null);
  /** Message d'accusé (« La routine est partie… »). */
  readonly notice = signal<string | null>(null);
  /** La routine dont l'historique est déplié. */
  readonly historyOf = signal<string | null>(null);
  readonly history = signal<RoutineRun[]>([]);
  readonly days = ROUTINE_DAYS;
  draft: RoutineDraft = emptyDraft();

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      if (id) {
        this.reload(id);
      } else {
        this.state.set('error');
      }
    });
  }

  reload(workspaceId = this.workspaceId()): void {
    if (!workspaceId) {
      return;
    }
    this.service.list(workspaceId).subscribe({
      next: (routines) => {
        this.routines.set(routines);
        this.state.set('ready');
      },
      error: () => this.state.set('error'),
    });
  }

  statusLabel(routine: Routine): string {
    if (routine.proposed) {
      return 'Proposée par l’agent';
    }
    return routine.active ? 'Active' : 'En pause';
  }

  cadence(routine: Routine): string {
    return cadenceLabel(routine);
  }

  money(value: number): string {
    return usd(value);
  }

  when(iso: string | null): string {
    return shortDateTime(iso);
  }

  runLabel(run: RoutineRun): string {
    return runStatusLabel(run);
  }

  /** « Lancer maintenant » : la routine part sans attendre son heure (poste en ligne, sujet libre). */
  runNow(routine: Routine): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    this.run(this.service.runNow(workspaceId, routine.id), () => {
      this.notice.set(`« ${routine.name} » est partie : le tour se déroule dans ce terminal.`);
      this.started.emit();
    });
  }

  /** Déplie ou replie l'historique d'une routine. */
  toggleHistory(routine: Routine): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    if (this.historyOf() === routine.id) {
      this.historyOf.set(null);
      return;
    }
    this.historyOf.set(routine.id);
    this.history.set([]);
    this.service.history(workspaceId, routine.id).subscribe({
      next: (runs) => this.history.set(runs),
      error: () => this.history.set([]),
    });
  }

  startCreate(): void {
    this.draft = emptyDraft();
    this.editingId.set(null);
    this.errors.set([]);
    this.editing.set(true);
  }

  edit(routine: Routine): void {
    this.draft = draftOf(routine);
    this.editingId.set(routine.id);
    this.errors.set([]);
    this.editing.set(true);
  }

  cancel(): void {
    this.editing.set(false);
    this.editingId.set(null);
    this.errors.set([]);
  }

  save(): void {
    const workspaceId = this.workspaceId();
    const problems = draftErrors(this.draft);
    this.errors.set(problems);
    if (!workspaceId || problems.length > 0) {
      return;
    }
    const request = requestOf(this.draft);
    const id = this.editingId();
    this.run(id ? this.service.update(workspaceId, id, request) : this.service.create(workspaceId, request),
      () => this.cancel());
  }

  accept(routine: Routine): void {
    const workspaceId = this.workspaceId();
    if (workspaceId) {
      this.run(this.service.accept(workspaceId, routine.id));
    }
  }

  toggle(routine: Routine): void {
    const workspaceId = this.workspaceId();
    if (workspaceId) {
      this.run(this.service.update(workspaceId, routine.id, { active: !routine.active }));
    }
  }

  remove(routine: Routine, refusal: boolean): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId) {
      return;
    }
    if (refusal) {
      this.run(this.service.remove(workspaceId, routine.id));
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Supprimer la routine ?',
      message: `« ${routine.name} » ne s'exécutera plus. Son historique est effacé.`,
      confirmLabel: 'Supprimer',
    };
    this.dialog.open(ConfirmDialogComponent, { data, width: '420px' }).afterClosed().subscribe((ok) => {
      if (ok) {
        this.run(this.service.remove(workspaceId, routine.id));
      }
    });
  }

  private run(call: Observable<unknown>, after?: () => void): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        after?.();
        this.reload();
        this.changed.emit();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.failure.set(error?.error?.message ?? 'Le geste a échoué. Réessayez dans un instant.');
      },
    });
  }
}
