import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminCostService } from './admin-cost.service';
import { CostReconciliation, ReconciliationDay, formatEuros, formatRate } from './admin-cost.models';

/**
 * **Facturé par Anthropic / calculé** (F-224 / SF-224-04) — le rapprochement, jour par jour, avec l'écart et la
 * consommation par clé API. Rien n'est calculé ici : la passerelle rend les montants, convertis en euros au taux
 * unique `app.cost.usd-to-eur` (F-224 / SF-224-05) ; la facture Anthropic, elle, est en dollars, et la carte le dit
 * une fois. Sans clé d'administration, la carte dit comment la créer.
 *
 * <p>F-224 / SF-224-06 : chaque montant dit ce qu'il couvre (facturé = toute l'organisation, calculé = la passerelle),
 * aujourd'hui est marqué partiel et sort des totaux, et la clé de la passerelle, une fois désignée, donne l'écart à
 * périmètre égal.</p>
 */
@Component({
  selector: 'app-admin-cost-reconciliation',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule],
  template: `
    <mat-card class="recon">
      <div class="recon__head">
        <div>
          <h3 class="recon__title">Facture Anthropic</h3>
          <p class="recon__subtitle">Ce que le fournisseur facture, à côté de ce que la passerelle calcule.</p>
        </div>
        @if (data()?.configured) {
          <button mat-stroked-button type="button" class="recon__refresh" [disabled]="loading()" (click)="refresh()">
            <mat-icon>refresh</mat-icon>
            Relire maintenant
          </button>
        }
      </div>
      @if (loading()) {
        <mat-progress-bar mode="indeterminate"></mat-progress-bar>
      }
      @if (error(); as message) {
        <p class="recon__error" role="status"><mat-icon aria-hidden="true">error_outline</mat-icon>{{ message }}</p>
      }
      @if (data(); as d) {
        @if (!d.configured) {
          <p class="recon__setup" role="status">
            <mat-icon aria-hidden="true">key</mat-icon>
            <span>Rapprochement inactif. {{ d.setupHint }}</span>
          </p>
        } @else {
          <p class="recon__totals num">
            Jours complets : facturé <strong>{{ euros(d.billedTotalEur) }}</strong> · calculé
            <strong>{{ euros(d.calculatedTotalEur) }}</strong>
            @if (d.gatewayApiKeyId) {
              · clé de la passerelle <strong>{{ euros(d.gatewayKeyTotalEur) }}</strong>
            }
            @if (d.lastFetchedAt) {
              <span class="recon__muted"> · lu le {{ fetchedAt(d.lastFetchedAt) }}</span>
            }
          </p>
          @if (rateNote(d.usdToEur); as note) {
            <p class="recon__muted recon__rate">{{ note }}</p>
          }
          @if (d.scopes; as scopes) {
            <ul class="recon__scopes">
              <li>{{ scopes.billed }}</li>
              <li>{{ scopes.calculated }}</li>
              @if (d.gatewayApiKeyId) {
                <li>{{ scopes.gatewayKey }}</li>
              }
              <li>{{ scopes.keys }}</li>
              <li>{{ scopes.today }}</li>
            </ul>
          }
          @if (d.gatewayKeyHint; as hint) {
            <p class="recon__muted recon__hint"><mat-icon aria-hidden="true">info_outline</mat-icon>{{ hint }}</p>
          }
          <table class="recon__table">
            <thead>
              <tr>
                <th scope="col">Jour</th><th scope="col">Facturé</th><th scope="col">Calculé</th><th scope="col">Écart</th>
                @if (d.gatewayApiKeyId) {
                  <th scope="col">Clé passerelle</th><th scope="col">Écart passerelle</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (day of d.days; track day.day) {
                <tr class="recon__row" [class.recon__row--open]="open() === day.day" [class.recon__row--partial]="day.partial">
                  <td>
                    @if (day.keys.length > 0) {
                      <button type="button" class="recon__toggle" [attr.aria-expanded]="open() === day.day"
                        (click)="toggle(day)">{{ day.day }}</button>
                    } @else {
                      {{ day.day }}
                    }
                    @if (day.partial) {
                      <span class="recon__partial">(partiel)</span>
                    }
                  </td>
                  <td class="num">{{ euros(day.billedEur) }}</td>
                  <td class="num">{{ euros(day.calculatedEur) }}</td>
                  <td class="num" [class.recon__gap]="gapNotable(day.gapEur)">{{ euros(day.gapEur) }}</td>
                  @if (d.gatewayApiKeyId) {
                    <td class="num">{{ euros(day.gatewayKeyEur) }}</td>
                    <td class="num" [class.recon__gap]="gapNotable(day.gatewayGapEur)">{{ euros(day.gatewayGapEur) }}</td>
                  }
                </tr>
                @if (open() === day.day) {
                  @for (key of day.keys; track key.apiKeyId) {
                    <tr class="recon__key">
                      <td>{{ key.label || key.apiKeyId }}@if (key.gateway) { <span class="recon__badge">passerelle</span>}</td>
                      <td class="num" [attr.colspan]="d.gatewayApiKeyId ? 5 : 3">≈ {{ euros(key.amountEur) }} (estimé à la grille)</td>
                    </tr>
                  }
                }
              }
            </tbody>
          </table>
        }
      }
    </mat-card>
  `,
  styles: `
    :host { display: block; margin-top: var(--cg-space-4); }
    .recon { padding: var(--cg-space-4); }
    .recon__head { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: var(--cg-space-2); }
    .recon__title { margin: 0; }
    .recon__subtitle, .recon__muted { margin: var(--cg-space-1) 0 0; color: var(--cg-text-secondary); font-size: 13px; }
    .recon__setup, .recon__error { display: flex; gap: var(--cg-space-2); align-items: flex-start; margin: var(--cg-space-3) 0 0; }
    .recon__totals { margin: var(--cg-space-3) 0 0; }
    .recon__rate { margin: var(--cg-space-1) 0 var(--cg-space-3); }
    .recon__table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .recon__table th, .recon__table td { padding: var(--cg-space-1) var(--cg-space-2); text-align: left; border-bottom: 1px solid var(--cg-divider); }
    .recon__table .num { text-align: right; font-variant-numeric: tabular-nums; }
    .recon__toggle { padding: 0; border: 0; background: none; color: var(--cg-primary); font: inherit; text-decoration: underline; cursor: pointer; }
    .recon__key td { color: var(--cg-text-secondary); padding-left: var(--cg-space-4); }
    .recon__gap { color: var(--cg-gold-ink); font-weight: 600; }
    .recon__scopes { margin: 0 0 var(--cg-space-3); padding-left: var(--cg-space-4); color: var(--cg-text-secondary); font-size: 13px; }
    .recon__hint { display: flex; gap: var(--cg-space-2); align-items: flex-start; margin-bottom: var(--cg-space-3); }
    .recon__partial { margin-left: var(--cg-space-1); color: var(--cg-text-secondary); font-size: 12px; }
    .recon__row--partial td { color: var(--cg-text-secondary); }
    .recon__badge { margin-left: var(--cg-space-1); color: var(--cg-primary); font-size: 12px; font-weight: 600; }
    @media (max-width: 599px) { .recon__table { font-size: 12px; } }
  `,
})
export class AdminCostReconciliationComponent implements OnInit {
  private readonly service = inject(AdminCostService);
  private readonly snackBar = inject(MatSnackBar);

  readonly data = signal<CostReconciliation | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  /** Le jour dont les clés sont dépliées. */
  readonly open = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.reconciliation().subscribe({
      next: (data) => {
        this.data.set(data);
        this.loading.set(false);
      },
      error: (failure: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(failure.status === 403 ? 'Cette section est réservée à l’administration.'
          : 'Le rapprochement n’a pas pu être lu.');
      },
    });
  }

  /** [Relire maintenant] : la passerelle relit la facture chez le fournisseur. */
  refresh(): void {
    this.loading.set(true);
    this.service.refreshReconciliation().subscribe({
      next: (data) => {
        this.data.set(data);
        this.loading.set(false);
        this.snackBar.open('Facture relue.', 'OK', { duration: 3000 });
      },
      error: () => {
        this.loading.set(false);
        this.snackBar.open('La facture n’a pas pu être relue chez le fournisseur.', 'Fermer', { duration: 5000 });
      },
    });
  }

  toggle(day: ReconciliationDay): void {
    this.open.set(this.open() === day.day ? null : day.day);
  }

  /** Un écart d'un euro ou plus se voit (F-224 / SF-224-05 : le seuil suit la devise de l'écran). */
  gapNotable(gapEur: number | null | undefined): boolean {
    return gapEur !== null && gapEur !== undefined && Math.abs(gapEur) >= 1;
  }

  euros(amount: number | null | undefined): string {
    return formatEuros(amount);
  }

  /** La mention unique : les montants sont convertis, la facture du fournisseur est en dollars. */
  rateNote(value: number | null | undefined): string | null {
    const rate = formatRate(value);
    return rate === null ? null
      : `Montants convertis au taux 1 $ = ${rate} € ; la facture Anthropic est en dollars.`;
  }

  fetchedAt(iso: string): string {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
  }
}
