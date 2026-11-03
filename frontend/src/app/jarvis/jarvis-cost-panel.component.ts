import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

import { JarvisBudgetStatus, JarvisCostSummary, JarvisCosts } from '../core/models/jarvis.models';
import { JarvisService } from '../core/services/jarvis.service';
import { formatEuros } from '../shared/euros';

/** Une ligne de la ventilation : son libellé et la clé du poste. */
interface CostRow {
  label: string;
  key: 'fastBrain' | 'voice' | 'listening' | 'opus' | 'mail' | 'agenda';
}

/** F-244 / SF-244-04 (D10) — les six postes, dans l'ordre de l'écran. */
export const JARVIS_COST_ROWS: readonly CostRow[] = [
  { label: 'Conversation rapide', key: 'fastBrain' },
  { label: 'Voix', key: 'voice' },
  { label: 'Transcription', key: 'listening' },
  { label: 'Recherches Opus', key: 'opus' },
  { label: 'Courrier', key: 'mail' },
  { label: 'Agenda', key: 'agenda' },
];

/** La part consommée au-delà de laquelle la jauge passe à l'orange (alerte F-244 D6). */
export const JARVIS_GAUGE_ALERT = 80;

/** Le montant d'un poste, 0 s'il est absent (courrier et agenda avant F-242 / F-243). */
export function costOf(costs: JarvisCosts | null | undefined, key: CostRow['key']): number {
  const value = costs ? costs[key] : 0;
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * **Ce que coûte Jarvis, poste par poste** (F-244 / SF-244-04) : aujourd'hui et le mois en euros, la jauge du mois face
 * au plafond (orange à partir de 80 %), et le réglage du plafond (montant, « appliquer aussi en BYOK »). Volet du
 * design system §22 : aucune couleur nouvelle. Jamais affiché en présentation (le parent ne le monte pas).
 */
@Component({
  selector: 'app-jarvis-cost-panel',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <section class="jc" aria-label="Ce que coûte Jarvis">
      <header class="jc__head">
        <h2 class="jc__title">Ce que coûte Jarvis</h2>
        <button mat-icon-button type="button" class="jc__close" aria-label="Fermer" (click)="closed.emit()">
          <mat-icon aria-hidden="true">close</mat-icon>
        </button>
      </header>
      <table class="jc__table">
        <thead>
          <tr><th scope="col">Poste</th><th scope="col" class="jc__num">Aujourd’hui</th>
            <th scope="col" class="jc__num">Ce mois</th></tr>
        </thead>
        <tbody>
          @for (row of rows; track row.key) {
            <tr>
              <th scope="row">{{ row.label }}</th>
              <td class="jc__num">{{ eur(costOf(costs().today, row.key)) }}</td>
              <td class="jc__num">{{ eur(costOf(costs().month, row.key)) }}</td>
            </tr>
          }
        </tbody>
        <tfoot>
          <tr class="jc__total">
            <th scope="row">Total</th>
            <td class="jc__num">{{ eur(costs().today.total) }}</td>
            <td class="jc__num">{{ eur(costs().month.total) }}</td>
          </tr>
        </tfoot>
      </table>
      @if (budget(); as b) {
        <div class="jc__gauge-label">
          <span>Plafond du mois : {{ eur(b.monthlyCapEur) }}</span>
          <span class="jc__num" [class.jc__warn]="alert()">{{ b.percent }} %</span>
        </div>
        <div class="jc__gauge" role="meter" aria-label="Part du plafond consommée" [attr.aria-valuenow]="b.percent"
             aria-valuemin="0" aria-valuemax="100">
          <div class="jc__gauge-fill" [class.jc__gauge-fill--alert]="alert()" [style.width.%]="gaugeWidth()"></div>
        </div>
        @if (stateNote(); as note) {
          <p class="jc__note" [class.jc__warn]="alert()">{{ note }}</p>
        }
        <form class="jc__form" (ngSubmit)="save()">
          <mat-form-field appearance="outline" class="jc__cap">
            <mat-label>Plafond mensuel (€)</mat-label>
            <input matInput type="number" min="0" max="10000" step="1" name="cap" [ngModel]="capDraft() ?? b.monthlyCapEur"
                   (ngModelChange)="capDraft.set($event)" />
          </mat-form-field>
          <mat-checkbox name="byok" [ngModel]="byokDraft() ?? b.applyOnByok" (ngModelChange)="byokDraft.set($event)">
            Appliquer aussi avec ma clé personnelle
          </mat-checkbox>
          <button mat-stroked-button type="submit" class="jc__save" [disabled]="saving()">Enregistrer</button>
        </form>
        @if (error(); as message) {
          <p class="jc__warn jc__note" role="alert">{{ message }}</p>
        }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jc {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(440px, calc(100vw - 2 * var(--cg-space-2))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    @media (max-width: 767px) { .jc { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; } }
    .jc__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jc__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .jc__close { color: var(--cg-cream); }
    .jc__table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .jc__table th, .jc__table td { padding: 4px 0; text-align: left; font-weight: 400; }
    .jc__table thead th { font-size: 12px; opacity: 0.8; }
    .jc__total th, .jc__total td { font-weight: 600; border-top: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); }
    .jc__num { text-align: right !important; font-family: var(--cg-font-mono); white-space: nowrap; }
    .jc__gauge-label { display: flex; justify-content: space-between; gap: var(--cg-space-2); font-size: 14px; }
    .jc__gauge { height: 8px; border-radius: 4px; overflow: hidden;
      background: color-mix(in srgb, var(--cg-cream) 18%, transparent); }
    .jc__gauge-fill { height: 100%; background: color-mix(in srgb, var(--cg-cream) 60%, transparent); }
    .jc__gauge-fill--alert { background: var(--cg-orange-2); }
    .jc__warn { color: var(--cg-orange-2); }
    .jc__note { margin: 0; font-size: 13px; }
    .jc__form { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .jc__cap { width: 160px; }
    .jc__save { color: var(--cg-cream); }
  `],
})
export class JarvisCostPanelComponent {
  private readonly api = inject(JarvisService);

  readonly costs = input.required<JarvisCostSummary>();
  readonly closed = output<void>();
  /** Le plafond vient d'être réglé : le parent relit le compteur. */
  readonly saved = output<JarvisBudgetStatus>();

  readonly rows = JARVIS_COST_ROWS;
  readonly costOf = costOf;
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  private readonly local = signal<JarvisBudgetStatus | null>(null);
  readonly budget = computed(() => this.local() ?? this.costs().budget ?? null);
  readonly capDraft = signal<number | null>(null);
  readonly byokDraft = signal<boolean | null>(null);
  readonly alert = computed(() => (this.budget()?.percent ?? 0) >= JARVIS_GAUGE_ALERT);
  readonly gaugeWidth = computed(() => Math.max(0, Math.min(100, this.budget()?.percent ?? 0)));
  readonly stateNote = computed(() => {
    switch (this.budget()?.state) {
      case 'ALERT': return 'Plus de 80 % du plafond est consommé.';
      case 'SOFT': return 'Plafond atteint : Jarvis demandera avant chaque recherche.';
      case 'HARD': return 'Deux fois le plafond : Jarvis ne lance plus rien ce mois-ci.';
      default: return null;
    }
  });

  eur(value: number | null | undefined): string {
    return formatEuros(value);
  }

  save(): void {
    const current = this.budget();
    const cap = this.capDraft() ?? current?.monthlyCapEur ?? null;
    if (cap === null || !Number.isFinite(Number(cap)) || Number(cap) < 0 || Number(cap) > 10_000) {
      this.error.set('Le plafond doit être compris entre 0 et 10 000 €.');
      return;
    }
    this.error.set(null);
    this.saving.set(true);
    this.api.updateBudget({ monthlyCapEur: Number(cap), applyOnByok: this.byokDraft() ?? current?.applyOnByok ?? false })
      .subscribe({
        next: (status) => {
          this.saving.set(false);
          this.local.set(status);
          this.capDraft.set(null);
          this.byokDraft.set(null);
          this.saved.emit(status);
        },
        error: () => {
          this.saving.set(false);
          this.error.set('Le plafond n’a pas pu être enregistré.');
        },
      });
  }
}
