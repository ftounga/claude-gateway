import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminCostService } from './admin-cost.service';
import {
  AdminCostAlert,
  AdminCostClient,
  AdminCostPeriod,
  AdminCostSummary,
} from './admin-cost.models';

/**
 * Section **Coût réel** de l'administration (F-133 / SF-133-07) : ce que chaque client coûte
 * vraiment cette semaine, opposé au budget qu'on lui a fixé.
 *
 * <p>Elle ne remplace pas la section **Consommation** (F-61), qui compte des <b>tokens</b> et
 * répond à « qui consomme ». Celle-ci compte des <b>euros</b> et répond à « combien ça me coûte ».
 * Les deux coexistent, et leurs intitulés le disent.</p>
 *
 * <p><b>Rien n'est calculé ici</b> : ni la conversion en euros, ni la part consommée. Les deux sont
 * des règles serveur, et les refaire dans le navigateur en ferait une seconde définition — qui
 * finirait par afficher un chiffre différent de celui des alertes.</p>
 *
 * <p><b>Un budget n'arrête rien</b> : cet écran pilote, il ne coupe pas. Le refus de service reste
 * l'affaire du quota commercial.</p>
 */
@Component({
  selector: 'app-admin-cost',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './admin-cost.component.html',
  styleUrl: './admin-cost.component.scss',
})
export class AdminCostComponent implements OnInit {
  private readonly service = inject(AdminCostService);
  private readonly snackBar = inject(MatSnackBar);

  readonly period = signal<AdminCostPeriod>('week');
  readonly summary = signal<AdminCostSummary | null>(null);
  readonly alerts = signal<AdminCostAlert[]>([]);
  readonly loading = signal(false);
  /** Message d'erreur **de la section** : le reste de la page d'administration reste utilisable. */
  readonly error = signal<string | null>(null);
  /** Saisie en cours du budget par défaut, en euros. */
  readonly defaultBudgetDraft = signal<string>('');

  ngOnInit(): void {
    this.reload();
  }

  /** Change de période et recharge. */
  selectPeriod(period: AdminCostPeriod): void {
    if (period === this.period()) {
      return;
    }
    this.period.set(period);
    this.reload();
  }

  /** (Re)lit la synthèse et les alertes. */
  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.summary(this.period()).subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: (failure: HttpErrorResponse) => {
        this.summary.set(null);
        this.loading.set(false);
        this.error.set(
          failure.status === 403
            ? 'Cette section est réservée à l’administration.'
            : 'La dépense n’a pas pu être lue.',
        );
      },
    });
    // Les alertes ne concernent que la semaine : elles ne suivent pas le sélecteur de période.
    this.service.alerts().subscribe({
      next: (alerts) => this.alerts.set(alerts),
      error: () => this.alerts.set([]),
    });
  }

  /** Fixe le budget hebdomadaire par défaut. */
  saveDefaultBudget(): void {
    const amount = parseAmount(this.defaultBudgetDraft());
    if (amount === null) {
      this.snackBar.open('Le budget doit être un montant positif.', 'Fermer', { duration: 4000 });
      return;
    }
    this.service.setDefaultBudget(amount).subscribe({
      next: () => {
        this.snackBar.open('Budget par défaut enregistré.', 'Fermer', { duration: 3000 });
        this.reload();
      },
      error: () => this.snackBar.open('Le budget n’a pas pu être enregistré.', 'Fermer', {
        duration: 4000,
      }),
    });
  }

  /** Fixe le budget d'un client, depuis sa ligne. */
  saveHostBudget(client: AdminCostClient, raw: string): void {
    const amount = parseAmount(raw);
    if (client.hostId === null || amount === null) {
      this.snackBar.open('Le budget doit être un montant positif.', 'Fermer', { duration: 4000 });
      return;
    }
    this.service.setHostBudget(client.hostId, amount).subscribe({
      next: () => {
        this.snackBar.open('Budget enregistré.', 'Fermer', { duration: 3000 });
        this.reload();
      },
      error: () => this.snackBar.open('Le budget n’a pas pu être enregistré.', 'Fermer', {
        duration: 4000,
      }),
    });
  }

  /** Retire le budget propre d'un client : il retombe sur le défaut. */
  clearHostBudget(client: AdminCostClient): void {
    if (client.hostId === null) {
      return;
    }
    this.service.clearHostBudget(client.hostId).subscribe({
      next: () => {
        this.snackBar.open('Budget retiré.', 'Fermer', { duration: 3000 });
        this.reload();
      },
      error: () => this.snackBar.open('Le budget n’a pas pu être retiré.', 'Fermer', {
        duration: 4000,
      }),
    });
  }

  /** Montant en euros, à la française. */
  euros(amount: number | null): string {
    if (amount === null || amount === undefined) {
      return '—';
    }
    return `${amount.toFixed(2).replace('.', ',')} €`;
  }

  /** Nom affiché d'un client. « Hors client » n'est pas un client : il se nomme comme tel. */
  clientName(client: AdminCostClient): string {
    if (client.hostId === null) {
      return 'Hors client';
    }
    return client.hostName ?? 'Poste supprimé';
  }

  /** Intitulé d'une alerte. */
  alertLabel(alert: AdminCostAlert): string {
    const who = alert.scope === 'TOTAL' ? 'Total' : (alert.hostName ?? 'Poste supprimé');
    const verb = alert.level === 'EXCEEDED' ? 'a dépassé son budget' : 'approche de son budget';
    return `${who} ${verb} — ${this.euros(alert.spentEur)} sur ${this.euros(alert.budgetEur)} (${alert.percent} %)`;
  }

  /** Part consommée bornée à 100 pour la barre : au-delà, c'est l'alerte qui le dit. */
  barValue(percent: number | null): number {
    return Math.min(100, Math.max(0, percent ?? 0));
  }
}

/**
 * Lit un montant saisi. Rend `null` pour tout ce qui n'est pas un nombre positif — la virgule
 * française est acceptée, parce que c'est ce que l'écran affiche.
 */
function parseAmount(raw: string): number | null {
  const parsed = Number.parseFloat((raw ?? '').trim().replace(',', '.'));
  if (!Number.isFinite(parsed) || parsed < 0) {
    return null;
  }
  return Math.round(parsed * 100) / 100;
}
