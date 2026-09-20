import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { CostAlertsService, ForgeCostAlert } from '../../core/services/cost-alerts.service';

/** Une alerte préparée pour l'écran : le texte est déjà écrit, le gabarit n'en fabrique pas. */
export interface AlertLine {
  key: string;
  level: 'NEAR' | 'EXCEEDED';
  /** « CAGIP a dépassé son budget hebdomadaire » — jamais de montant ici. */
  title: string;
  /** « 1 200 % du budget » pour tous ; « 12,00 € sur 1,00 € » en plus pour l'administrateur. */
  detail: string;
}

/**
 * **Le bandeau d'alerte de dépense, dans la Forge** (F-133 / SF-133-12).
 *
 * <p>SF-133-06 avait placé ces alertes dans la console d'administration, et le cadrage l'assumait :
 * *« un bandeau dans la console d'admin, et rien ailleurs »*. C'était le mauvais choix — on travaille
 * dans la Forge, et une alerte qu'il faut aller chercher n'alerte personne.</p>
 *
 * <p><b>Visible de tous, chiffré pour l'administrateur seul.</b> Le filtrage des montants est fait
 * par la passerelle, qui les retire de la réponse ; ici on se contente de ne pas écrire ce qui
 * vaut `null`. Une garde côté navigateur ne protégerait rien.</p>
 *
 * <p><b>Une erreur d'API n'affiche rien.</b> Une alerte est un confort ; elle ne doit jamais abîmer
 * l'écran de travail ni y écrire un message d'échec.</p>
 */
@Component({
  selector: 'app-forge-cost-alert',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './forge-cost-alert.component.html',
  styleUrl: './forge-cost-alert.component.scss',
})
export class ForgeCostAlertComponent implements OnInit {
  private readonly alerts = inject(CostAlertsService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly received = signal<ForgeCostAlert[]>([]);
  private readonly dismissedSignature = signal<string | null>(readDismissed());

  /** Les lignes à écrire — vide si rien à dire, ou si l'on a masqué CE dépassement-là. */
  readonly lines = computed<AlertLine[]>(() => {
    const alerts = this.received();
    if (alerts.length === 0 || signatureOf(alerts) === this.dismissedSignature()) {
      return [];
    }
    return alerts.map(toLine);
  });

  /** Rouge dès qu'un budget est dépassé, ambre tant qu'on s'en approche seulement. */
  readonly severity = computed<'NEAR' | 'EXCEEDED'>(() =>
    this.lines().some((line) => line.level === 'EXCEEDED') ? 'EXCEEDED' : 'NEAR',
  );

  ngOnInit(): void {
    this.alerts
      .mine()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (alerts) => this.received.set(alerts),
        // Silence volontaire : pas de bandeau, pas d'erreur à l'écran.
        error: () => this.received.set([]),
      });
  }

  /**
   * Masque le bandeau **pour la session**, et pour ce dépassement précis : il revient à la
   * connexion suivante, et aussi plus tôt si la situation s'aggrave (on approchait, on a dépassé).
   */
  dismiss(): void {
    const signature = signatureOf(this.received());
    this.dismissedSignature.set(signature);
    try {
      sessionStorage.setItem(DISMISS_KEY, signature);
    } catch {
      // Stockage indisponible (navigation privée, site bloqué) : le bandeau se masque quand même
      // pour l'affichage en cours. Rien d'autre à faire.
    }
  }
}

const DISMISS_KEY = 'cg.forge.cost-alert.dismissed';

function readDismissed(): string | null {
  try {
    return sessionStorage.getItem(DISMISS_KEY);
  } catch {
    return null;
  }
}

/**
 * Ce qui identifie « ce dépassement-là » : la semaine, et l'état de chaque poste. Masquer un
 * avertissement ne doit pas masquer le dépassement qui le suit.
 */
function signatureOf(alerts: ForgeCostAlert[]): string {
  return alerts
    .map((alert) => `${alert.weekStart}:${alert.scope}:${alert.hostId ?? ''}:${alert.level}`)
    .sort()
    .join('|');
}

function toLine(alert: ForgeCostAlert): AlertLine {
  const who = alert.scope === 'TOTAL' ? 'Le budget hebdomadaire' : (alert.hostName ?? 'Un poste');
  const verb =
    alert.level === 'EXCEEDED'
      ? alert.scope === 'TOTAL'
        ? 'est dépassé'
        : 'a dépassé son budget hebdomadaire'
      : alert.scope === 'TOTAL'
        ? 'est presque atteint'
        : 'approche de son budget hebdomadaire';

  const parts = [`${alert.percent} % du budget`];
  // Les montants n'arrivent que pour l'administrateur (ils valent `null` sinon).
  if (alert.spentEur !== null && alert.budgetEur !== null) {
    parts.push(`${money(alert.spentEur)} sur ${money(alert.budgetEur)}`);
  }

  return {
    key: `${alert.scope}:${alert.hostId ?? 'total'}`,
    level: alert.level,
    title: `${who} ${verb}`,
    detail: parts.join(' · '),
  };
}

function money(amount: number): string {
  return `${amount.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €`;
}
