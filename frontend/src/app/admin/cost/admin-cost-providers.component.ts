import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ProviderSpendService } from '../../core/services/provider-spend.service';
import { ProviderGauge, formatEuros, formatRate } from './admin-cost.models';

/**
 * **Les deux pots — ce mois** (F-279 / SF-279-02) : ce qui part chez Anthropic (crédit prépayé) et chez AWS Bedrock
 * (facturé en fin de mois), en euros, côte à côte ; la part recalée sur la facture et la part estimée ; le canal en
 * service ; et ce que ces chiffres ont d'inexact, écrit en clair. Rien n'est calculé ici.
 */
@Component({
  selector: 'app-admin-cost-providers',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule],
  template: `
    <mat-card class="pots">
      <div class="pots__head">
        <div>
          <h3 class="pots__title">Les deux pots — ce mois</h3>
          <p class="pots__muted">Ce que la plateforme a consommé chez chaque fournisseur, hors TVA.</p>
        </div>
        @if (data()) {
          <button mat-stroked-button type="button" [disabled]="loading()" (click)="refresh()">
            <mat-icon>refresh</mat-icon>
            Relire maintenant
          </button>
        }
      </div>
      @if (loading()) {
        <mat-progress-bar mode="indeterminate"></mat-progress-bar>
      }
      @if (error(); as message) {
        <p class="pots__error" role="status"><mat-icon aria-hidden="true">error_outline</mat-icon>{{ message }}</p>
      }
      @if (data(); as d) {
        <p class="pots__channel" role="status">
          <span class="pots__dot" [class.pots__dot--bedrock]="d.channel.current === 'BEDROCK'" aria-hidden="true"></span>
          En service : <strong>{{ d.channel.current === 'BEDROCK' ? 'AWS Bedrock' : 'Anthropic' }}</strong>
          @if (d.channel.relay) {
            <span class="pots__warn">
              — {{ d.channel.creditExhausted ? 'crédit Anthropic épuisé, relais Bedrock' : 'relais d’Anthropic' }}
            </span>
          }
        </p>
        <div class="pots__grid">
          @for (gauge of d.gauges; track gauge.provider) {
            <section class="pots__pot" [class.pots__pot--bedrock]="gauge.provider === 'BEDROCK'">
              <h4 class="pots__name">{{ gauge.label }}</h4>
              <p class="pots__amount num">{{ euros(gauge.spentEur) }}</p>
              <p class="pots__muted">{{ billing(gauge) }}</p>
              <ul class="pots__detail num">
                <li>Recalé sur la facture : {{ euros(gauge.billedEur) }}{{ through(gauge) }}</li>
                <li>Estimé par la passerelle : {{ euros(gauge.estimatedEur) }}</li>
                @if (gauge.gapEur !== null) {
                  <li>Écart facture − calcul (jours recalés) : {{ euros(gauge.gapEur) }}</li>
                }
              </ul>
              <p class="pots__note" [class.pots__note--estimated]="gauge.status === 'ESTIME'">
                <mat-icon aria-hidden="true">{{ gauge.status === 'RECALE' ? 'task_alt' : 'info_outline' }}</mat-icon>
                <span>{{ gauge.statusNote }}</span>
              </p>
            </section>
          }
        </div>
        <p class="pots__total num">Total du mois : <strong>{{ euros(d.totalEur) }}</strong>
          <span class="pots__muted"> — le budget de la semaine reste le seul plafond, somme des deux.</span>
        </p>
        <details class="pots__caveats">
          <summary>Ce qui reste inexact</summary>
          <ul>
            @for (caveat of d.caveats; track caveat) {
              <li>{{ caveat }}</li>
            }
            @if (rate(d.usdToEur); as r) {
              <li>Taux appliqué : 1 $ = {{ r }} €.</li>
            }
          </ul>
        </details>
      }
    </mat-card>
  `,
  styles: `
    :host { display: block; margin-top: var(--cg-space-4); }
    .pots { padding: var(--cg-space-4); }
    .pots__head { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: var(--cg-space-2); }
    .pots__title { margin: 0; }
    .pots__muted { margin: var(--cg-space-1) 0 0; color: var(--cg-text-secondary); font-size: 13px; }
    .pots__error { display: flex; gap: var(--cg-space-2); align-items: flex-start; margin: var(--cg-space-3) 0 0; }
    .pots__channel { display: flex; align-items: center; gap: var(--cg-space-2); margin: var(--cg-space-3) 0; }
    .pots__dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: var(--cg-navy); }
    .pots__dot--bedrock { background: var(--cg-orange); }
    .pots__warn { color: var(--cg-gold-ink); }
    .pots__grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--cg-space-3); }
    .pots__pot { padding: var(--cg-space-3); border: 1px solid var(--cg-divider); border-left: 4px solid var(--cg-navy); border-radius: 4px; }
    .pots__pot--bedrock { border-left-color: var(--cg-orange); }
    .pots__name { margin: 0; font-size: 14px; }
    .pots__amount { margin: var(--cg-space-1) 0 0; font-size: 24px; font-weight: 600; font-variant-numeric: tabular-nums; }
    .pots__detail { margin: var(--cg-space-2) 0 0; padding-left: var(--cg-space-4); font-size: 13px; }
    .pots__note { display: flex; gap: var(--cg-space-1); align-items: flex-start; margin: var(--cg-space-2) 0 0; font-size: 13px; color: var(--cg-text-secondary); }
    .pots__note mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--cg-success); }
    .pots__note--estimated mat-icon { color: var(--cg-gold-ink); }
    .pots__total { margin: var(--cg-space-3) 0 0; }
    .pots__caveats { margin-top: var(--cg-space-3); font-size: 13px; color: var(--cg-text-secondary); }
    .pots__caveats summary { cursor: pointer; color: var(--cg-primary); }
    .pots__caveats ul { margin: var(--cg-space-2) 0 0; padding-left: var(--cg-space-4); }
  `,
})
export class AdminCostProvidersComponent implements OnInit {
  private readonly pots = inject(ProviderSpendService);
  private readonly snackBar = inject(MatSnackBar);

  readonly data = this.pots.spend;
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.loading.set(true);
    this.pots.read().subscribe({
      next: () => this.loading.set(false),
      error: (failure: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(failure.status === 403 ? 'Cette section est réservée à l’administration.'
          : 'Les deux pots n’ont pas pu être lus.');
      },
    });
  }

  /** [Relire maintenant] : la passerelle relit la facture Anthropic et la facture AWS. */
  refresh(): void {
    this.loading.set(true);
    this.pots.refresh().subscribe({
      next: () => {
        this.loading.set(false);
        this.snackBar.open('Factures relues.', 'OK', { duration: 3000 });
      },
      error: () => {
        this.loading.set(false);
        this.snackBar.open('La facture n’a pas pu être relue.', 'Fermer', { duration: 5000 });
      },
    });
  }

  euros(amount: number | null | undefined): string {
    return formatEuros(amount);
  }

  rate(value: number | null | undefined): string | null {
    return formatRate(value);
  }

  billing(gauge: ProviderGauge): string {
    return gauge.billing === 'PREPAID'
      ? 'Crédit prépayé : consommé sur le crédit acheté d’avance.'
      : 'Facturé par AWS en fin de mois.';
  }

  through(gauge: ProviderGauge): string {
    if (!gauge.billedThrough) {
      return '';
    }
    const [, month, day] = gauge.billedThrough.split('-');
    return ` (jusqu’au ${day}/${month})`;
  }
}
