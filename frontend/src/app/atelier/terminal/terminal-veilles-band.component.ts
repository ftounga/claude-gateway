import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute } from '@angular/router';

import { ago } from '../../core/models/routine.models';
import {
  JarvisAlert, JarvisWatch, JarvisWatchChange, JarvisWatchTest, changeLabel, watchScheduleLabel,
} from '../../core/models/veille.models';
import { formatEuros } from '../../shared/euros';
import { VeilleService } from '../../core/services/veille.service';

/**
 * **La bande des veilles Jarvis, en tête du fil du sujet** (F-230).
 *
 * <p>SF-230-01 : la **carte de validation** d'une veille proposée par l'agent — la commande en clair, puis
 * [Tester la sonde] (une exécution sur le poste, au clic du PO), le résultat (« 12 éléments : 5 en cours… »),
 * et seulement alors [Activer]. [Refuser] la supprime. Rien ne tourne avant [Activer] (D1, D5).</p>
 *
 * <p>Se relit à l'ouverture du sujet et quand `refresh` change (fin de tour, geste du panneau). Absente quand il
 * n'y a rien à montrer.</p>
 */
@Component({
  selector: 'app-terminal-veilles-band',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    @if (visibleAlert(); as a) {
      <div class="veille-alert" role="status" aria-live="polite">
        <mat-icon class="veille-alert__icon" aria-hidden="true">notifications_active</mat-icon>
        <div class="veille-alert__body">
          <p class="veille-alert__headline">{{ a.sentence }}</p>
          @if (a.changes.length) {
            <ul class="veille-alert__changes">
              @for (change of a.changes.slice(0, maxChanges); track $index) {
                <li>{{ label(change) }}</li>
              }
            </ul>
            @if (a.changes.length > maxChanges) {
              <p class="veille-alert__meta">… et {{ a.changes.length - maxChanges }} autre(s).</p>
            }
          }
          <p class="veille-alert__meta">
            @if (a.platform) { <span>{{ a.platform }} · </span> }<span>{{ since(a.createdAt) }}</span>
            @if (a.costEur !== null) { <span> · {{ money(a.costEur) }}</span> }
          </p>
        </div>
        <button mat-icon-button type="button" class="veille-alert__close" aria-label="Fermer l'alerte"
                (click)="alert.set(null)">
          <mat-icon>close</mat-icon>
        </button>
      </div>
    }
    @for (watch of brokenWatches(); track watch.id) {
      <div class="veille-broken" role="status">
        <mat-icon class="veille-broken__icon" aria-hidden="true">sensors_off</mat-icon>
        <span class="veille-broken__text">
          Veille « {{ watch.name }} » en panne : sa sonde a échoué {{ watch.consecutiveFailures }} fois de suite.
          Demandez à l'agent de la réparer.
        </span>
      </div>
    }
    @for (watch of proposals(); track watch.id) {
      <div class="veille-card" role="group" [attr.aria-label]="'Veille proposée : ' + watch.name">
        <mat-icon class="veille-card__icon" aria-hidden="true">radar</mat-icon>
        <div class="veille-card__body">
          <p class="veille-card__headline">Veille proposée · {{ watch.name }}</p>
          <p class="veille-card__line">{{ watch.platform }} · {{ schedule(watch) }}@if (watch.chantierNumber) { · chantier {{ watch.chantierNumber }}}</p>
          <p class="veille-card__scope">{{ watch.scope }}</p>
          <p class="veille-card__label">Commande lancée sur le poste, sans IA :</p>
          <code class="veille-card__command">{{ watch.command }}</code>
          @if (watch.scriptPath) {
            <p class="veille-card__line">Script relu et épinglé : <code>{{ watch.scriptPath }}</code></p>
          }
          @if (resultFor(watch.id); as result) {
            @if (result.ok) {
              <p class="veille-card__result" role="status">Je vois {{ result.summary }}</p>
              @if (result.sample.length) {
                <ul class="veille-card__sample">
                  @for (line of result.sample; track $index) {
                    <li>{{ line }}</li>
                  }
                </ul>
              }
            } @else {
              <p class="veille-card__error" role="alert">La sonde a échoué : {{ result.errorMessage }}</p>
            }
          } @else if (watch.testSummary) {
            <p class="veille-card__result" role="status">Dernier test : {{ watch.testSummary }}</p>
          }
          @if (!readOnly()) {
            <div class="veille-card__gestures">
              <button mat-stroked-button type="button" class="veille-card__secondary" [disabled]="busy()"
                      (click)="test(watch)">
                @if (testing() === watch.id) {
                  <mat-progress-spinner mode="indeterminate" diameter="16" aria-hidden="true"></mat-progress-spinner>
                }
                Tester la sonde
              </button>
              <button mat-flat-button type="button" class="veille-card__apply" [disabled]="busy() || !canAccept(watch)"
                      (click)="accept(watch)">Activer</button>
              <button mat-button type="button" class="veille-card__secondary" [disabled]="busy()"
                      (click)="refuse(watch)">Refuser</button>
            </div>
            @if (!canAccept(watch)) {
              <p class="veille-card__hint">Testez la sonde d'abord : vous activez ce que vous avez vu.</p>
            }
          } @else {
            <p class="veille-card__line">En attente de validation.</p>
          }
          @if (error()) {
            <p class="veille-card__error" role="alert">{{ error() }}</p>
          }
        </div>
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .veille-alert {
      display: flex; gap: var(--cg-space-2); margin: var(--cg-space-2) 0;
      padding: var(--cg-space-2) var(--cg-space-1) var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider); border-left: 4px solid var(--cg-accent); border-radius: 8px;
      font-family: var(--cg-font-body); color: inherit;
    }
    .veille-alert__icon { flex: none; color: var(--cg-accent); }
    .veille-alert__body { min-width: 0; flex: 1; }
    .veille-alert__headline { margin: 0; font-weight: 600; overflow-wrap: anywhere; }
    .veille-alert__changes { margin: var(--cg-space-1) 0 0; padding-left: var(--cg-space-4); font-size: 13px; }
    .veille-alert__meta { margin: var(--cg-space-1) 0 0; font-size: 12px; opacity: 0.8; }
    .veille-alert__close { --mdc-icon-button-icon-color: inherit; color: inherit; flex: none; }
    .veille-broken {
      display: flex; align-items: center; gap: var(--cg-space-2); margin: var(--cg-space-2) 0;
      padding: var(--cg-space-1) var(--cg-space-3); border: 1px solid var(--cg-divider);
      border-left: 4px solid var(--cg-orange-2); border-radius: 8px; font-family: var(--cg-font-body);
      font-size: 13px; color: inherit;
    }
    .veille-broken__icon { flex: none; color: var(--cg-orange-2); }
    .veille-broken__text { min-width: 0; overflow-wrap: anywhere; }
    .veille-card {
      display: flex; gap: var(--cg-space-2); margin: var(--cg-space-2) 0; padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-orange-2); border-left-width: 4px; border-radius: 8px; color: inherit;
      font-family: var(--cg-font-body);
    }
    .veille-card__icon { color: var(--cg-orange-2); flex: none; }
    .veille-card__body { min-width: 0; flex: 1; }
    .veille-card__headline { margin: 0; font-weight: 600; color: var(--cg-orange-2); overflow-wrap: anywhere; }
    .veille-card__line, .veille-card__scope, .veille-card__label, .veille-card__result, .veille-card__error,
    .veille-card__hint {
      margin: var(--cg-space-1) 0 0; font-size: 13px; overflow-wrap: anywhere;
    }
    .veille-card__scope { white-space: pre-wrap; opacity: 0.85; }
    .veille-card__label, .veille-card__hint { opacity: 0.8; }
    .veille-card__command {
      display: block; margin-top: var(--cg-space-1); padding: var(--cg-space-1) var(--cg-space-2);
      border: 1px solid var(--cg-divider); border-radius: 4px; font-family: var(--cg-font-mono); font-size: 12px;
      white-space: pre-wrap; overflow-wrap: anywhere;
    }
    .veille-card__result { font-weight: 600; }
    .veille-card__sample { margin: var(--cg-space-1) 0 0; padding-left: var(--cg-space-4); font-size: 13px; }
    .veille-card__error { color: var(--cg-orange-2); }
    .veille-card__gestures { display: flex; flex-wrap: wrap; gap: var(--cg-space-2); margin-top: var(--cg-space-2); }
    .veille-card__gestures button { min-height: 44px; }
    .veille-card__gestures mat-progress-spinner { display: inline-block; margin-right: var(--cg-space-1); }
    .veille-card__apply {
      --mdc-filled-button-container-color: var(--cg-accent);
      --mdc-filled-button-label-text-color: var(--cg-surface);
    }
    .veille-card__secondary {
      --mdc-outlined-button-label-text-color: inherit;
      --mdc-text-button-label-text-color: inherit;
      color: inherit;
    }
  `,
})
export class TerminalVeillesBandComponent {
  private readonly service = inject(VeilleService);

  /** Le sujet du terminal. */
  readonly workspaceId = input<string | null>(null);
  /** Change à chaque fin de tour ou geste du panneau : la bande se relit. */
  readonly refresh = input(0);
  /** Lecture seule (mosaïque) : aucun geste. */
  readonly readOnly = input(false);
  /** Une veille a changé ici (activée, refusée). */
  readonly changed = output<void>();

  readonly watches = signal<JarvisWatch[]>([]);
  readonly proposals = computed(() => this.watches().filter((w) => w.proposed));
  /** SF-230-02 — les veilles en panne : une veille muette ne doit pas passer pour « rien de neuf » (D6). */
  readonly brokenWatches = computed(() => this.watches().filter((w) => w.broken && !w.proposed));
  /** Les tests de cette visite, par veille. */
  readonly results = signal<Record<string, JarvisWatchTest>>({});
  readonly testing = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  /**
   * SF-230-03 — l'alerte demandée par l'adresse (`?alerte=…`, le lien de la notification tant que Jarvis — F-231 —
   * n'est pas là) : affichée en tête, marquée ouverte (mesure D8).
   */
  readonly alert = signal<JarvisAlert | null>(null);
  readonly maxChanges = 8;
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly destroyRef = inject(DestroyRef);
  private shownAlertId: string | null = null;

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      this.refresh();
      if (id) {
        this.load(id);
      } else {
        this.watches.set([]);
      }
    });
    this.route?.queryParamMap?.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const alertId = params.get('alerte');
      if (alertId && alertId !== this.shownAlertId) {
        this.showAlert(alertId);
      }
    });
  }

  private showAlert(alertId: string): void {
    this.shownAlertId = alertId;
    this.service.alert(alertId).subscribe({
      next: (alert) => this.alert.set(alert),
      // Une alerte introuvable (supprimée, d'un autre compte) n'est jamais une raison de casser le terminal.
      error: () => this.alert.set(null),
    });
  }

  /** L'alerte n'est montrée que dans le fil de SON sujet : chaque terminal (mosaïque comprise) ne montre que les siennes. */
  readonly visibleAlert = computed(() => {
    const alert = this.alert();
    const workspaceId = this.workspaceId();
    return alert && workspaceId && alert.workspaceId === workspaceId ? alert : null;
  });

  private openedAlertId: string | null = null;

  /** Montrée = ouverte (mesure D8), une fois. */
  private readonly markOpened = effect(() => {
    const alert = this.visibleAlert();
    if (alert && !alert.openedAt && alert.id !== this.openedAlertId) {
      this.openedAlertId = alert.id;
      untracked(() => this.service.openAlert(alert.id).subscribe({ error: () => undefined }));
    }
  });

  label(change: JarvisWatchChange): string {
    return changeLabel(change);
  }

  since(iso: string): string {
    return ago(iso);
  }

  money(value: number | null): string {
    return formatEuros(value);
  }

  private load(workspaceId: string): void {
    this.service.list(workspaceId).subscribe({
      next: (watches) => this.watches.set(watches),
      // Une veille n'est jamais une raison de casser le terminal : la bande reste vide.
      error: () => this.watches.set([]),
    });
  }

  schedule(watch: JarvisWatch): string {
    return watchScheduleLabel(watch);
  }

  resultFor(id: string): JarvisWatchTest | null {
    return this.results()[id] ?? null;
  }

  /** [Activer] n'est permis qu'après un test réussi depuis la proposition. */
  canAccept(watch: JarvisWatch): boolean {
    const result = this.resultFor(watch.id);
    return result ? result.ok : watch.testedAt !== null;
  }

  test(watch: JarvisWatch): void {
    this.busy.set(true);
    this.testing.set(watch.id);
    this.error.set(null);
    this.service.test(watch.workspaceId, watch.id).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.testing.set(null);
        this.results.update((all) => ({ ...all, [watch.id]: result }));
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.testing.set(null);
        this.error.set(error?.error?.message ?? 'Le test a échoué. Réessayez dans un instant.');
      },
    });
  }

  accept(watch: JarvisWatch): void {
    this.gesture(() => this.service.accept(watch.workspaceId, watch.id));
  }

  refuse(watch: JarvisWatch): void {
    this.gesture(() => this.service.remove(watch.workspaceId, watch.id));
  }

  private gesture(call: () => import('rxjs').Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    call().subscribe({
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
        this.error.set(error?.error?.message ?? 'Le geste a échoué. Réessayez dans un instant.');
      },
    });
  }
}
