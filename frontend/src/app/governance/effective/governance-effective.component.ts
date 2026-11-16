import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import {
  AccountGovernanceRule,
  GovernanceAgentView,
  GovernanceEffective,
  GovernancePackageLag,
  GovernanceRulesFile,
} from '../../core/models/governance.models';
import { GovernanceService } from '../../core/services/governance.service';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';

/** Ce que dit l'état d'un `GOUVERNANCE.md`, en toutes lettres. */
export function rulesStateLabel(rules: GovernanceRulesFile): string {
  switch (rules.state) {
    case 'PRESENT':
      return 'Présent — injecté à chaque tour';
    case 'ABSENT':
      return 'Aucun fichier GOUVERNANCE.md';
    case 'INJOIGNABLE':
      return 'Poste injoignable — non lu';
    case 'SANS_MACHINE':
      return 'Pas de machine';
    default:
      return 'Pas encore lu (aucun tour récent)';
  }
}

/** Clé de titre, comme la passerelle (casse et espaces ignorés). */
function titleKey(title: string): string {
  return title.trim().replace(/\s+/g, ' ').toLowerCase();
}

/** Ce qu'un agent nommé peut faire et sur quel modèle il tourne, en toutes lettres (F-182 / SF-182-01). */
export function agentCapabilityLabel(agent: GovernanceAgentView): string {
  const access = agent.access === 'ECRITURE' ? 'lit, écrit et exécute' : 'lecture seule';
  const model = agent.model ? `modèle ${agent.model}` : 'modèle de la Forge';
  return `${access} · ${model}`;
}

/**
 * **Ce qui s'applique vraiment** sur un poste (F-177 / SF-177-04, décision D6) : les règles du client
 * (`GOUVERNANCE.md` du poste et des sujets), les skills et leur origine, et les paquets activés **avec
 * leur retard de dépôt** — « fichiers en v6, paquet en v17 — [Remettre à jour] », « jamais déposé ».
 * [Remettre à jour] rejoue le dépôt existant (`apply`) ; rien d'autre n'écrit.
 */
@Component({
  selector: 'app-governance-effective',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <section class="effective" aria-label="Ce qui s'applique vraiment">
      <h3 class="effective__title">Ce qui s'applique vraiment</h3>
      @if (loading()) {
        <mat-spinner diameter="24"></mat-spinner>
      } @else if (error()) {
        <p class="effective__muted">{{ error() }}</p>
      } @else {
        @if (view(); as v) {
        <!-- Paquets : le retard de dépôt d'abord, c'est ce que personne ne voyait. -->
        <h4 class="effective__subtitle">Paquets activés</h4>
        @if (v.packages.length === 0) {
          <p class="effective__muted">Aucun paquet activé sur ce poste.</p>
        } @else {
          <ul class="effective__list">
            @for (pkg of v.packages; track pkg.packageId) {
              <li class="effective__row" [class]="'effective__row effective__row--' + pkg.state.toLowerCase()">
                <mat-icon aria-hidden="true">{{ lagIcon(pkg) }}</mat-icon>
                <div class="effective__row-main">
                  <strong>{{ pkg.name }}</strong>
                  <span class="effective__muted">{{ pkg.message }}</span>
                </div>
                @if (pkg.state !== 'A_JOUR') {
                  <button mat-stroked-button type="button" [disabled]="busy() === pkg.packageId" (click)="reapply(pkg)">
                    {{ pkg.state === 'JAMAIS_DEPOSE' ? 'Déposer' : 'Remettre à jour' }}
                  </button>
                }
              </li>
            }
          </ul>
        }

        <h4 class="effective__subtitle">Règles du client</h4>
        <div class="effective__rules">
          <!-- F-266 : les règles de tous mes postes, rangées côté passerelle, valent ici aussi. -->
          <p><strong>Tous mes postes</strong> ·
            <span class="effective__muted">{{ accountRules().length === 0 ? 'Aucune règle sur tous vos postes' : 'Injectées à chaque tour, sur chaque poste' }}</span>
          </p>
          @if (accountRules().length > 0) {
            <ul class="effective__list effective__account">
              @for (rule of accountRules(); track rule.id) {
                <li class="effective__row">
                  <mat-icon aria-hidden="true">public</mat-icon>
                  <div class="effective__row-main">
                    <strong>{{ rule.title }}</strong>
                    <span class="effective__muted effective__clip">{{ rule.content }}</span>
                  </div>
                  <button mat-stroked-button type="button" [disabled]="busy() === rule.id" (click)="removeAccountRule(rule)">
                    Retirer
                  </button>
                </li>
              }
            </ul>
          }
          <p><strong>Poste</strong> · <span class="effective__muted">{{ label(v.hostRules) }}</span></p>
          @if (v.hostRules.excerpt) {
            <pre class="effective__excerpt">{{ v.hostRules.excerpt }}@if (v.hostRules.truncated) {
…}</pre>
          }
          @if (hostRuleTitles().length > 0) {
            <ul class="effective__list effective__host-titles" aria-label="Règles du poste">
              @for (title of hostRuleTitles(); track title) {
                <li class="effective__row">
                  <div class="effective__row-main">
                    <span>{{ title }}</span>
                    @if (isOnAllHosts(title)) {
                      <span class="effective__muted">Déjà sur tous mes postes</span>
                    }
                  </div>
                  <button mat-stroked-button type="button" [disabled]="busy() === 'promote:' + title" (click)="promote(title)">
                    {{ isOnAllHosts(title) ? 'Mettre à jour' : 'Valable sur tous mes postes' }}
                  </button>
                </li>
              }
            </ul>
          }
          @for (subject of v.subjects; track subject.workspaceId) {
            <p><strong>{{ subject.name }}</strong> · <span class="effective__muted">{{ label(subject.rules) }}</span></p>
            @if (subject.rules.excerpt) {
              <pre class="effective__excerpt">{{ subject.rules.excerpt }}@if (subject.rules.truncated) {
…}</pre>
            }
          }
        </div>

        <h4 class="effective__subtitle">Skills ({{ v.skills.length }})</h4>
        @if (v.skills.length === 0) {
          <p class="effective__muted">Aucun skill connu sur ce poste.</p>
        } @else {
          <ul class="effective__skills">
            @for (skill of v.skills; track skill.origin + skill.path + (skill.subjectName ?? '')) {
              <li>
                <code>/{{ skill.name }}</code>
                <span class="effective__muted">
                  · {{ skill.origin === 'POSTE' ? 'poste' : skill.subjectName }}
                  · {{ skill.source === 'PAQUET' ? 'paquet ' + (skill.packageName ?? '') : 'client' }}
                </span>
              </li>
            }
          </ul>
        }
        <!-- F-182 / SF-182-01 : les agents nommés, avec ce qui les rend inutilisables en clair. -->
        <h4 class="effective__subtitle">Agents nommés ({{ agents().length }})</h4>
        @if (agents().length === 0) {
          <p class="effective__muted">
            Aucun agent nommé. Un agent se déclare dans <code>.claude/agents/&lt;nom&gt;.md</code> (poste ou sujet).
          </p>
        } @else {
          <ul class="effective__skills effective__agents">
            @for (agent of agents(); track agent.origin + agent.path + (agent.subjectName ?? '')) {
              <li [class.effective__agent--unusable]="!agent.usable">
                <code>/agent {{ agent.name }}</code>
                <span class="effective__muted">
                  · {{ agent.origin === 'POSTE' ? 'poste' : agent.subjectName }}
                  · {{ agent.source === 'PAQUET' ? 'paquet ' + (agent.packageName ?? '') : 'client' }}
                  · {{ capability(agent) }}
                </span>
                @if (agent.description) {
                  <span class="effective__agent-description">{{ agent.description }}</span>
                }
                @for (error of agent.errors; track error) {
                  <span class="effective__agent-problem effective__agent-problem--error">
                    <mat-icon aria-hidden="true">error</mat-icon>{{ error }}
                  </span>
                }
                @for (warning of agent.warnings; track warning) {
                  <span class="effective__agent-problem">
                    <mat-icon aria-hidden="true">warning</mat-icon>{{ warning }}
                  </span>
                }
              </li>
            }
          </ul>
        }
        <p class="effective__muted effective__note">
          Les règles, skills et agents des sujets sont ceux lus au dernier tour de chaque sujet.
        </p>
        @if (lateCount() > 0) {
          <p class="effective__sr" role="status">{{ lateCount() }} paquet(s) à remettre à jour.</p>
        }
        }
      }
    </section>
  `,
  styles: `
    .effective {
      margin-top: var(--cg-space-4);
      padding-top: var(--cg-space-3);
      border-top: 1px solid var(--cg-divider);
    }

    .effective__title {
      margin: 0 0 var(--cg-space-2);
      font-family: var(--cg-font-heading);
    }

    .effective__subtitle {
      margin: var(--cg-space-3) 0 var(--cg-space-1);
      font-size: 14px;
    }

    .effective__muted {
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .effective__list,
    .effective__skills {
      list-style: none;
      margin: 0;
      padding: 0;
    }

    .effective__row {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) 0;
      flex-wrap: wrap;
    }

    .effective__row button {
      min-height: 44px;
    }

    .effective__row-main {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-width: 0;
    }

    .effective__row--a_jour mat-icon {
      color: var(--cg-success);
    }

    .effective__row--en_retard mat-icon,
    .effective__row--jamais_depose mat-icon {
      color: var(--cg-orange-2);
    }

    .effective__clip {
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      overflow-wrap: anywhere;
    }

    .effective__account mat-icon {
      color: var(--cg-orange-2);
    }

    .effective__rules p {
      margin: var(--cg-space-1) 0;
    }

    .effective__excerpt {
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-2);
      max-height: 200px;
      overflow: auto;
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      font-family: var(--cg-font-mono);
      font-size: 12px;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .effective__skills li {
      padding: var(--cg-space-1) 0;
      overflow-wrap: anywhere;
    }

    .effective__note {
      margin-top: var(--cg-space-2);
    }

    .effective__agents li {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-1);
    }

    .effective__agent-description {
      font-size: 13px;
    }

    .effective__agent-problem {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      font-size: 13px;
      color: var(--cg-text-secondary);
    }

    .effective__agent-problem mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      color: var(--cg-orange-2);
    }

    .effective__agent-problem--error mat-icon {
      color: var(--cg-error);
    }

    .effective__agent--unusable code {
      text-decoration: line-through;
    }

    .effective__sr {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class GovernanceEffectiveComponent {
  private readonly governance = inject(GovernanceService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly hostRef = input.required<string>();
  /** Un paquet a été redéposé : le parent relit l'état du poste. */
  readonly changed = output<void>();

  readonly view = signal<GovernanceEffective | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly busy = signal<string | null>(null);
  readonly lateCount = computed(() => (this.view()?.packages ?? []).filter((p) => p.state !== 'A_JOUR').length);
  /** F-182 / SF-182-01 : les agents nommés (vide pour un serveur antérieur). */
  readonly agents = computed(() => this.view()?.agents ?? []);
  /** F-266 : les règles de tous mes postes. */
  readonly accountRules = signal<AccountGovernanceRule[]>([]);
  /** F-266 : les titres des règles du poste (vide pour un serveur antérieur). */
  readonly hostRuleTitles = computed(() => this.view()?.hostRuleTitles ?? []);

  readonly label = rulesStateLabel;
  readonly capability = agentCapabilityLabel;

  constructor() {
    effect(() => this.load(this.hostRef()));
  }

  lagIcon(pkg: GovernancePackageLag): string {
    return pkg.state === 'A_JOUR' ? 'check_circle' : pkg.state === 'EN_RETARD' ? 'update' : 'report';
  }

  reapply(pkg: GovernancePackageLag): void {
    const ref = this.hostRef();
    this.busy.set(pkg.packageId);
    this.governance.apply(ref, pkg.packageId).subscribe({
      next: () => {
        this.busy.set(null);
        this.snackBar.open(`${pkg.name} : dépôt rejoué.`, 'Fermer', { duration: 6000 });
        this.changed.emit();
        this.load(ref);
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(null);
        this.snackBar.open(err?.error?.message ?? "Le dépôt n'a pas pu être rejoué.", 'Fermer', {
          duration: 8000,
        });
      },
    });
  }

  isOnAllHosts(title: string): boolean {
    const key = titleKey(title);
    return this.accountRules().some((rule) => titleKey(rule.title) === key);
  }

  /** F-266 : « Valable sur tous mes postes » — recopie la règle du poste. */
  promote(title: string): void {
    this.busy.set('promote:' + title);
    this.governance.promoteHostRule(this.hostRef(), title).subscribe({
      next: () => {
        this.busy.set(null);
        this.snackBar.open(`« ${title} » vaut désormais sur tous vos postes.`, 'Fermer', { duration: 6000 });
        this.loadAccountRules();
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(null);
        this.snackBar.open(err?.error?.message ?? "La règle n'a pas pu être recopiée.", 'Fermer', { duration: 8000 });
      },
    });
  }

  /** F-266 : retirer une règle de tous mes postes (confirmation par dialogue). */
  removeAccountRule(rule: AccountGovernanceRule): void {
    const data: ConfirmDialogData = {
      title: 'Retirer la règle',
      message: `Retirer « ${rule.title} » de tous vos postes ? Les règles des postes eux-mêmes ne changent pas.`,
      confirmLabel: 'Retirer',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.performRemove(rule);
        }
      });
  }

  private performRemove(rule: AccountGovernanceRule): void {
    this.busy.set(rule.id);
    this.governance.deleteAccountRule(rule.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.accountRules.set(this.accountRules().filter((r) => r.id !== rule.id));
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(null);
        this.snackBar.open(err?.error?.message ?? "La règle n'a pas pu être retirée.", 'Fermer', { duration: 8000 });
      },
    });
  }

  private loadAccountRules(): void {
    this.governance.listAccountRules().subscribe({
      next: (rules) => this.accountRules.set(rules ?? []),
      error: () => this.accountRules.set([]),
    });
  }

  private load(ref: string): void {
    this.loadAccountRules();
    this.loading.set(true);
    this.error.set(null);
    this.governance.getEffective(ref).subscribe({
      next: (view) => {
        if (ref === this.hostRef()) {
          this.view.set(view);
          this.loading.set(false);
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set("Ce qui s'applique n'a pas pu être lu.");
      },
    });
  }
}
