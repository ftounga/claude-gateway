import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminDiagnosticService } from './admin-diagnostic.service';
import { CapabilityFinding, DiagnosticReport, ParityState, SourceHypothesis }
  from './admin-diagnostic.models';

/**
 * Le rappel qui accompagne toute hypothèse — **il ne doit jamais manquer**. Une hypothèse qui se
 * déguise en constat est pire qu'un silence : on développerait sur une supposition.
 */
export const SourceHypothesis_CAVEAT =
  "Hypothèse tirée de la lecture du code — à vérifier, ce n'est pas un constat mesuré.";

/** L'état de parité en toutes lettres. */
export function parityLabel(state: ParityState): string {
  switch (state) {
    case 'TENUE': return 'Tenue';
    case 'DORMANTE': return 'Dormante';
    case 'ABSENTE': return 'Absente';
    case 'ECARTEE': return 'Écartée';
    default: return 'Non observée';
  }
}

/** Le verdict en toutes lettres. */
export function verdictLabel(verdict: string): string {
  switch (verdict) {
    case 'DORMANTE': return 'Dormante';
    case 'DEBRANCHEE': return 'Débranchée';
    case 'INDETERMINEE': return 'Indéterminée';
    default: return 'Active';
  }
}

/**
 * Section **Diagnostic du produit** de l'administration (F-156 / SF-156-05).
 *
 * <p>Il se lance **à la demande** et ne consomme **aucun jeton** : il lit des mesures.</p>
 *
 * <p><b>L'auto-modification est écartée</b> : l'écran écrit la ligne de spec, l'administrateur la
 * colle. Une machine qui modifie son propre code sans décision humaine n'est pas un gain de
 * productivité, c'est une perte de contrôle.</p>
 */
@Component({
  selector: 'app-admin-diagnostic',
  imports: [DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatCardModule, MatIconModule],
  template: `
    <mat-card class="diag">
      <mat-card-header>
        <mat-card-title>Diagnostic du produit</mat-card-title>
        <mat-card-subtitle>
          Une capacité absente demande une feature ; une capacité dormante ne demande qu'un
          branchement. Le diagnostic ne consomme aucun jeton.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <div class="diag__run">
          <label class="diag__days">
            Sur
            <input type="number" min="1" max="31" [(ngModel)]="days" name="days" />
            jours
          </label>
          <label class="diag__repo">
            Lire le code dans ce projet
            <input type="text" [(ngModel)]="repositoryId" name="repositoryId"
              placeholder="identifiant du terminal ouvert sur le dépôt (facultatif)" />
          </label>
          <button mat-flat-button color="primary" type="button" [disabled]="running()"
            (click)="run()">
            <mat-icon>troubleshoot</mat-icon>
            {{ running() ? 'Analyse…' : 'Lancer le diagnostic' }}
          </button>
        </div>
        <p class="diag__note">
          Sans projet désigné, le diagnostic reste gratuit. La lecture du code l'est aussi ; seule
          une hypothèse consomme des jetons.
        </p>

        @if (report(); as r) {
          <p class="diag__denominator">
            Du {{ r.from | date: 'shortDate' }} au {{ r.to | date: 'shortDate' }} —
            {{ r.turns }} tours, {{ r.projects }} projets, {{ r.costEur | number:'1.2-2' }} €.
            @if (r.truncated) {
              <span class="diag__caveat">Période ramenée aux bornes.</span>
            }
          </p>

          @if (r.sourceNote) {
            <p class="diag__source" [class.diag__source--refused]="!r.sourceRead">
              {{ r.sourceNote }}
            </p>
          }

          @if (!r.turns) {
            <p class="diag__clean">Rien à observer sur cette période.</p>
          } @else if (!r.findings.length) {
            <p class="diag__clean">
              Rien à signaler — les {{ r.active }} capacités observées se sont déclenchées.
              @if (r.discarded > 0) {
                <span class="diag__caveat">
                  ({{ r.discarded }} piste(s) écartée(s) : gain sous le seuil.)
                </span>
              }
            </p>
          } @else {
            <h3 class="diag__title">Constats</h3>
            @for (finding of r.findings; track finding.capabilityId) {
              <article class="finding">
                <p class="finding__head">
                  <span class="finding__verdict">{{ verdict(finding.verdict) }}</span>
                  {{ finding.name }}
                  @if (finding.gainEur !== null) {
                    <span class="finding__gain">{{ finding.gainEur | number:'1.2-2' }} €</span>
                  }
                </p>
                <p class="finding__why">{{ finding.why }}</p>
                @if (finding.check) {
                  <p class="finding__check">À vérifier : {{ finding.check }}</p>
                }
                @for (path of finding.where; track path) {
                  <p class="finding__where">{{ path }}</p>
                }
                @if (r.sourceRead && repositoryId) {
                  <button mat-button type="button" class="finding__explain"
                    [disabled]="explaining() === finding.capabilityId"
                    (click)="explain(finding)">
                    <mat-icon>psychology</mat-icon>
                    {{ explaining() === finding.capabilityId
                        ? 'Lecture…'
                        : 'Comprendre (lit le code — consomme des jetons)' }}
                  </button>
                }
                @if (hypothesis()?.capabilityId === finding.capabilityId) {
                  <aside class="hypothesis">
                    <p class="hypothesis__caveat">{{ caveat }}</p>
                    <p class="hypothesis__text">{{ hypothesis()!.text }}</p>
                    <p class="hypothesis__cost">
                      {{ hypothesis()!.model }} —
                      {{ hypothesis()!.inputTokens }} jetons d'entrée,
                      {{ hypothesis()!.outputTokens }} de sortie.
                      @if (hypothesis()!.truncated) {
                        <span>Code tronqué : la lecture n'a pas tout vu.</span>
                      }
                    </p>
                  </aside>
                }
              </article>
            }
            @if (r.discarded > 0) {
              <p class="diag__caveat">
                {{ r.discarded }} autre(s) piste(s) écartée(s) : gain calculé sous le seuil.
              </p>
            }
          }

          <h3 class="diag__title">Parité</h3>
          <div class="diag__table-wrap">
            <table class="diag__table">
              <thead>
                <tr>
                  <th scope="col">Capacité de référence</th>
                  <th scope="col">État</th>
                  <th scope="col">Ce que ça veut dire</th>
                </tr>
              </thead>
              <tbody>
                @for (row of r.parity; track row.referenceId) {
                  <tr>
                    <td>{{ row.name }}</td>
                    <td class="parity-{{ row.state }}">{{ parity(row.state) }}</td>
                    <td class="diag__note">{{ row.note }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          @if (r.specLines.length) {
            <h3 class="diag__title">À ajouter dans PRODUCT_SPEC.md</h3>
            <p class="diag__note">
              L'application propose ; vous décidez. Rien n'est écrit dans la spec.
            </p>
            @for (line of r.specLines; track line) {
              <pre class="spec-line">{{ line }}</pre>
            }
          }
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .diag__run {
      display: flex;
      align-items: center;
      gap: var(--cg-space-3);
      flex-wrap: wrap;
      margin-bottom: var(--cg-space-3);
    }

    .diag__days input {
      width: 64px;
      margin: 0 var(--cg-space-1);
      padding: var(--cg-space-1);
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      background: var(--cg-bg);
      color: var(--cg-text);
    }

    .diag__denominator,
    .diag__clean,
    .diag__note,
    .diag__caveat {
      color: var(--cg-text-secondary);
    }

    .diag__title {
      margin: var(--cg-space-4) 0 var(--cg-space-2);
      font-size: 16px;
    }

    .finding {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
    }

    .finding__head {
      margin: 0;
      font-weight: 600;
    }

    .finding__verdict {
      display: inline-block;
      margin-right: var(--cg-space-2);
      color: var(--cg-orange-2);
    }

    .finding__gain {
      margin-left: var(--cg-space-2);
      color: var(--cg-orange-2);
    }

    .finding__why {
      margin: var(--cg-space-1) 0 0;
    }

    .finding__check,
    .finding__where {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .diag__table-wrap {
      overflow-x: auto;
    }

    .diag__table {
      width: 100%;
      border-collapse: collapse;
    }

    .diag__table th,
    .diag__table td {
      text-align: left;
      padding: var(--cg-space-1) var(--cg-space-2);
      border-bottom: 1px solid var(--cg-divider);
      vertical-align: top;
    }

    .diag__table th {
      font-size: 12px;
      color: var(--cg-text-secondary);
      font-weight: 600;
    }

    .parity-DORMANTE,
    .parity-ABSENTE {
      color: var(--cg-orange-2);
      font-weight: 600;
    }

    .diag__repo input {
      width: 340px;
      max-width: 100%;
      margin-left: var(--cg-space-1);
      padding: var(--cg-space-1);
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      background: var(--cg-bg);
      color: var(--cg-text);
    }

    .diag__source {
      color: var(--cg-text-secondary);
    }

    .diag__source--refused {
      color: var(--cg-orange-2);
    }

    /* UNE HYPOTHÈSE NE DOIT PAS RESSEMBLER À UN CONSTAT. Le constat porte un chiffre et une preuve ;
       l'hypothèse porte un avertissement et un coût. Les afficher pareil ferait développer sur une
       supposition — le défaut que tout F-157 s'emploie à éviter. */
    .hypothesis {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2);
      border: 1px dashed var(--cg-orange-2);
      border-radius: 8px;
      background: var(--cg-bg);
    }

    .hypothesis__caveat {
      margin: 0 0 var(--cg-space-1);
      font-weight: 600;
      color: var(--cg-orange-2);
    }

    .hypothesis__text {
      margin: 0;
      white-space: pre-wrap;
    }

    .hypothesis__cost {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .spec-line {
      white-space: pre-wrap;
      word-break: break-word;
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      background: var(--cg-bg);
      font-size: 12px;
    }
  `,
})
export class AdminDiagnosticComponent {

  private readonly service = inject(AdminDiagnosticService);
  private readonly snackBar = inject(MatSnackBar);

  /** La fenêtre observée. Une semaine par défaut — l'unité dans laquelle le PO raisonne. */
  days = 7;

  /** Le terminal ouvert sur le dépôt de l'application. Vide : aucune lecture de code. */
  repositoryId = '';

  /** Le rappel qui accompagne toute hypothèse — il ne doit jamais manquer. */
  readonly caveat = SourceHypothesis_CAVEAT;

  readonly report = signal<DiagnosticReport | null>(null);
  readonly running = signal(false);
  readonly hypothesis = signal<SourceHypothesis | null>(null);
  readonly explaining = signal<string | null>(null);

  run(): void {
    this.running.set(true);
    this.hypothesis.set(null); // un nouveau diagnostic périme l'hypothèse précédente
    this.service.run(this.days, this.repositoryId.trim() || null).subscribe({
      next: report => {
        this.report.set(report);
        this.running.set(false);
      },
      error: () => {
        this.running.set(false);
        this.snackBar.open("Le diagnostic n'a pas pu être lancé.", 'Fermer', { duration: 5000 });
      },
    });
  }

  /**
   * Demande une hypothèse sur une capacité. **Seule opération qui coûte des jetons** — le libellé
   * du bouton le dit avant le clic.
   */
  explain(finding: CapabilityFinding): void {
    const repo = this.repositoryId.trim();
    if (!repo) {
      return;
    }
    this.explaining.set(finding.capabilityId);
    this.service.explain(repo, finding.capabilityId).subscribe({
      next: hypothesis => {
        this.explaining.set(null);
        if (!hypothesis) {
          this.snackBar.open("Rien à tirer du code pour cette capacité.", 'Fermer',
            { duration: 5000 });
          return;
        }
        this.hypothesis.set(hypothesis);
      },
      error: () => {
        this.explaining.set(null);
        this.snackBar.open("La lecture du code n'a pas abouti.", 'Fermer', { duration: 5000 });
      },
    });
  }

  parity(state: ParityState): string {
    return parityLabel(state);
  }

  verdict(value: string): string {
    return verdictLabel(value);
  }
}
