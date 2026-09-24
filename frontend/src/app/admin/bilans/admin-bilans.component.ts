import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminBilansService } from './admin-bilans.service';
import { BilanDetail, BilanSummary } from './admin-bilans.models';

/** Une durée ISO-8601 (`PT4M30S`) en français lisible. */
export function durationLabel(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const match = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/.exec(iso);
  if (!match) {
    return '—';
  }
  const [, d, h, m, s] = match;
  const parts: string[] = [];
  if (d) { parts.push(`${d} j`); }
  if (h) { parts.push(`${h} h`); }
  if (m) { parts.push(`${m} min`); }
  if (!parts.length) { parts.push(`${Math.round(Number(s ?? 0))} s`); }
  return parts.join(' ');
}

/** L'axe en toutes lettres — le PO en a nommé trois, et trois seulement. */
export function axisLabel(axis: string): string {
  switch (axis) {
    case 'COUT': return 'Coût';
    case 'TEMPS': return 'Temps';
    case 'RAISONNEMENT': return 'Raisonnement';
    default: return axis;
  }
}

/**
 * Section **Bilans de session** de l'administration (F-155 / SF-155-04).
 *
 * <p>Ce qu'elle sert : <b>comparer</b>. Un bilan seul dit « le cache est à 17 % » ; deux bilans
 * disent « il remonte ». C'est pour cela que les cinq chiffres de tête sont en colonnes.</p>
 *
 * <p><b>Rien n'est calculé ici.</b> Le gain, la part du cache, la conversion en euros sont des
 * règles serveur ; les refaire dans le navigateur en ferait une seconde définition, qui finirait
 * par afficher un chiffre différent de celui du bilan lui-même.</p>
 */
@Component({
  selector: 'app-admin-bilans',
  imports: [DatePipe, DecimalPipe, MatButtonModule, MatCardModule, MatIconModule],
  template: `
    <mat-card class="bilans">
      <mat-card-header>
        <mat-card-title>Bilans de session</mat-card-title>
        <mat-card-subtitle>
          Ce qui a été fait, ce que ça a coûté, ce qui aurait mieux valu — coût, temps, raisonnement.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        @if (loading()) {
          <p class="bilans__note">Chargement…</p>
        } @else if (failed()) {
          <p class="bilans__note">Les bilans n'ont pas pu être chargés.</p>
        } @else if (!bilans().length) {
          <p class="bilans__note">
            Aucun bilan pour l'instant. Il s'en produit un à la fermeture d'une session qui dépasse
            le seuil — en euros ou en tours.
          </p>
        } @else {
          <div class="bilans__table-wrap">
            <table class="bilans__table">
              <thead>
                <tr>
                  <th scope="col">Projet</th>
                  <th scope="col">Session</th>
                  <th scope="col" class="num">Tours</th>
                  <th scope="col" class="num">Coût</th>
                  <th scope="col" class="num">Cache</th>
                  <th scope="col" class="num">Suggestions</th>
                  <th scope="col"></th>
                </tr>
              </thead>
              <tbody>
                @for (bilan of bilans(); track bilan.id) {
                  <tr [class.bilans__row--open]="opened()?.headline?.id === bilan.id">
                    <td>{{ bilan.workspaceName ?? 'Projet supprimé' }}</td>
                    <td>{{ bilan.createdAt | date: 'short' }}</td>
                    <td class="num">{{ bilan.turns }}</td>
                    <td class="num">{{ bilan.costEur | number:'1.2-2' }} €</td>
                    <td class="num">{{ bilan.cacheShare }} %</td>
                    <td class="num">
                      {{ bilan.suggestionCount }}
                      @if (bilan.discardedCount > 0) {
                        <span class="bilans__discarded"
                          [title]="bilan.discardedCount + ' écartée(s) faute d’impact'">
                          (+{{ bilan.discardedCount }})
                        </span>
                      }
                    </td>
                    <td>
                      <button mat-button type="button" (click)="open(bilan)">Ouvrir</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (opened(); as detail) {
          <section class="detail">
            <h3 class="detail__title">
              {{ detail.headline.workspaceName ?? 'Projet supprimé' }} —
              {{ detail.headline.createdAt | date: 'medium' }}
            </h3>

            @if (detail.ledger; as ledger) {
              <p class="detail__line">
                <strong>Ce qui a été fait</strong> — {{ ledger.turns }} tours en
                {{ elapsed(ledger.elapsed) }}, {{ ledger.toolCalls }} appels d'outils
                @if (ledger.failedTools > 0) { <span>({{ ledger.failedTools }} en échec)</span> },
                {{ ledger.filesWritten }} fichiers écrits.
              </p>
              <p class="detail__line">
                <strong>Ce que ça a coûté</strong> — {{ ledger.costEur | number:'1.2-2' }} €,
                cache lu {{ ledger.cacheShare }} %
                @if (ledger.turnsWithoutCost > 0) {
                  <span class="detail__caveat">
                    · {{ ledger.turnsWithoutCost }} tour(s) sans coût connu
                  </span>
                }
              </p>

              @if (ledger.costliestTurns.length) {
                <p class="detail__line"><strong>Où c'est parti</strong></p>
                <ul class="detail__list">
                  @for (turn of ledger.costliestTurns; track turn.occurredAt) {
                    <li>
                      {{ turn.occurredAt | date: 'short' }} —
                      {{ turn.costEur | number:'1.2-2' }} € ({{ turn.model ?? 'modèle inconnu' }})
                    </li>
                  }
                  @for (tool of ledger.heaviestTools; track tool.tool) {
                    <li>
                      « {{ tool.tool }} » — {{ tool.calls }} appels, {{ elapsed(tool.total) }}
                      @if (tool.failures > 0) { <span>, {{ tool.failures }} en échec</span> }
                    </li>
                  }
                </ul>
              }
            }

            <p class="detail__line"><strong>Ce qui aurait mieux valu</strong></p>
            @if (!detail.suggestions.length) {
              <p class="detail__clean">
                Rien à signaler — cette session était bien menée.
                @if (detail.headline.discardedCount > 0) {
                  <span class="detail__caveat">
                    ({{ detail.headline.discardedCount }} piste(s) écartée(s) : gain sous le seuil.)
                  </span>
                }
              </p>
            } @else {
              <ul class="detail__list">
                @for (suggestion of detail.suggestions; track suggestion.advice) {
                  <li class="suggestion">
                    <span class="suggestion__gain">
                      {{ axis(suggestion.axis) }} · +{{ suggestion.gainPct }} %
                      @if (suggestion.gainEur !== null) {
                        <span>({{ suggestion.gainEur | number:'1.2-2' }} €)</span>
                      }
                    </span>
                    <span class="suggestion__advice">{{ suggestion.advice }}</span>
                    <span class="suggestion__measure">mesuré : {{ suggestion.measure }}</span>
                  </li>
                }
              </ul>
              @if (detail.headline.discardedCount > 0) {
                <p class="detail__caveat">
                  {{ detail.headline.discardedCount }} autre(s) piste(s) écartée(s) : gain sous le
                  seuil d'impact.
                </p>
              }
            }

            @if (detail.patterns.length) {
              <div class="patterns">
                <p class="detail__line"><strong>Ce motif revient</strong></p>
                @for (pattern of detail.patterns; track pattern.kind) {
                  <p class="pattern">
                    <span class="pattern__count">
                      {{ pattern.seen }}ᵉ fois sur {{ pattern.window }} sessions
                    </span>
                    <span class="pattern__lead">
                      Ce n'est plus une habitude à corriger. Un diagnostic du produit irait chercher
                      {{ pattern.lead }}.
                    </span>
                  </p>
                }
              </div>
            }
          </section>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .bilans__note {
      color: var(--cg-text-secondary);
      margin: var(--cg-space-3) 0;
    }

    .bilans__table-wrap {
      overflow-x: auto;
    }

    .bilans__table {
      width: 100%;
      border-collapse: collapse;
      font-variant-numeric: tabular-nums;
    }

    .bilans__table th,
    .bilans__table td {
      text-align: left;
      padding: var(--cg-space-1) var(--cg-space-2);
      border-bottom: 1px solid var(--cg-divider);
    }

    .bilans__table th {
      font-size: 12px;
      color: var(--cg-text-secondary);
      font-weight: 600;
    }

    .num {
      text-align: right;
    }

    .bilans__row--open {
      background: var(--cg-bg);
    }

    .bilans__discarded {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .detail {
      margin-top: var(--cg-space-4);
      padding-top: var(--cg-space-3);
      border-top: 1px solid var(--cg-divider);
    }

    .detail__title {
      margin: 0 0 var(--cg-space-2);
      font-size: 16px;
    }

    .detail__line {
      margin: var(--cg-space-1) 0;
    }

    .detail__list {
      margin: var(--cg-space-1) 0 var(--cg-space-3);
      padding-left: var(--cg-space-4);
    }

    .detail__clean,
    .detail__caveat {
      color: var(--cg-text-secondary);
    }

    .suggestion {
      margin-bottom: var(--cg-space-2);
    }

    .patterns {
      margin-top: var(--cg-space-3);
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      background: var(--cg-bg);
    }

    .pattern {
      margin: 0 0 var(--cg-space-1);
    }

    .pattern__count {
      display: block;
      font-weight: 600;
    }

    .pattern__lead {
      display: block;
      color: var(--cg-text-secondary);
    }

    .suggestion__gain {
      display: block;
      font-weight: 600;
      color: var(--cg-orange-2);
    }

    .suggestion__advice {
      display: block;
    }

    .suggestion__measure {
      display: block;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }
  `,
})
export class AdminBilansComponent implements OnInit {

  private readonly service = inject(AdminBilansService);
  private readonly snackBar = inject(MatSnackBar);

  readonly bilans = signal<BilanSummary[]>([]);
  readonly opened = signal<BilanDetail | null>(null);
  readonly loading = signal(true);
  readonly failed = signal(false);

  ngOnInit(): void {
    this.service.list().subscribe({
      next: bilans => {
        this.bilans.set(bilans);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  open(bilan: BilanSummary): void {
    this.service.open(bilan.id).subscribe({
      next: detail => this.opened.set(detail),
      error: () => this.snackBar.open("Ce bilan n'a pas pu être ouvert.", 'Fermer',
        { duration: 5000 }),
    });
  }

  elapsed(iso: string | null): string {
    return durationLabel(iso);
  }

  axis(value: string): string {
    return axisLabel(value);
  }
}
