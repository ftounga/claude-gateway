import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { BillingService } from '../core/services/billing.service';
import { UsageService } from '../core/services/usage.service';
import { ApiError } from '../core/models/auth.models';
import {
  Plan,
  SubscriptionStatus,
  SubscriptionView,
  TopUpPack,
} from '../core/models/billing.models';
import { UsageView } from '../core/models/usage.models';

/** Métadonnées d'affichage d'un statut d'abonnement (libellé + classe de badge). */
interface StatusDisplay {
  label: string;
  badgeClass: string;
}

/** Écran de facturation F-09 : abonnement courant, catalogue de plans, souscription via Stripe. */
@Component({
  selector: 'app-billing',
  imports: [
    DatePipe,
    DecimalPipe,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './billing.component.html',
  styleUrl: './billing.component.scss',
})
export class BillingComponent implements OnInit {
  private readonly billingService = inject(BillingService);
  private readonly usageService = inject(UsageService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly route = inject(ActivatedRoute);

  readonly subscription = signal<SubscriptionView | null>(null);
  readonly plans = signal<Plan[]>([]);
  readonly usage = signal<UsageView | null>(null);
  readonly loading = signal(true);
  /** Code du plan dont le checkout est en cours (désactive le bouton correspondant). */
  readonly checkoutInProgress = signal<string | null>(null);
  /** Packs de tokens rachetables (top-up F-21). */
  readonly topUpPacks = signal<TopUpPack[]>([]);
  /** Code du pack dont le rachat est en cours (désactive le bouton correspondant). */
  readonly topUpInProgress = signal<string | null>(null);
  /** Code du plan dont le changement (upgrade/downgrade) est en cours. */
  readonly changeInProgress = signal<string | null>(null);

  ngOnInit(): void {
    const checkout = this.route.snapshot.queryParamMap.get('checkout');
    if (checkout === 'success') {
      this.notify('Paiement confirmé. Votre abonnement est en cours de mise à jour.', 'snack-success');
    } else if (checkout === 'cancel') {
      this.notify('Paiement annulé.', 'snack-info');
    }
    this.loadSubscription();
    this.loadUsage();
    this.loadPlans();
    this.loadTopUps();
  }

  loadTopUps(): void {
    this.billingService.getTopUps().subscribe({
      next: (res) => this.topUpPacks.set(res.packs),
      // Échec non bloquant : la section « Racheter des tokens » reste simplement masquée.
      error: () => this.topUpPacks.set([]),
    });
  }

  loadUsage(): void {
    this.usageService.getUsage().subscribe({
      next: (usage) => this.usage.set(usage),
      error: () => this.notify('Impossible de charger votre consommation.', 'snack-error'),
    });
  }

  loadSubscription(): void {
    this.billingService.getSubscription().subscribe({
      next: (sub) => this.subscription.set(sub),
      error: () => this.notify("Impossible de charger votre abonnement.", 'snack-error'),
    });
  }

  loadPlans(): void {
    this.billingService.getPlans().subscribe({
      next: (res) => {
        this.plans.set(res.plans);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.notify('Impossible de charger les offres.', 'snack-error');
      },
    });
  }

  subscribe(planCode: string): void {
    if (this.checkoutInProgress()) {
      return;
    }
    this.checkoutInProgress.set(planCode);
    this.billingService.startCheckout(planCode).subscribe({
      next: (res) => this.redirect(res.checkoutUrl),
      error: (error: HttpErrorResponse) => {
        this.checkoutInProgress.set(null);
        const apiError = error.error as ApiError | undefined;
        const message =
          apiError?.error === 'billing_unavailable'
            ? 'La facturation est momentanément indisponible.'
            : 'Impossible de démarrer le paiement.';
        this.notify(message, 'snack-error');
      },
    });
  }

  /** Lance le rachat d'un pack de tokens (top-up F-21) et redirige vers le paiement Stripe. */
  buyTopUp(packCode: string): void {
    if (this.topUpInProgress()) {
      return;
    }
    this.topUpInProgress.set(packCode);
    this.billingService.startTopUpCheckout(packCode).subscribe({
      next: (res) => this.redirect(res.checkoutUrl),
      error: (error: HttpErrorResponse) => {
        this.topUpInProgress.set(null);
        const apiError = error.error as ApiError | undefined;
        const message =
          apiError?.error === 'billing_unavailable'
            ? 'La facturation est momentanément indisponible.'
            : 'Impossible de démarrer le rachat de tokens.';
        this.notify(message, 'snack-error');
      },
    });
  }

  /**
   * Change le plan de l'abonnement existant (upgrade/downgrade, SF-21-05). Ne redirige pas : Stripe
   * met à jour l'abonnement avec proratisation ; on rafraîchit l'abonnement affiché.
   */
  changePlan(planCode: string): void {
    if (this.changeInProgress()) {
      return;
    }
    this.changeInProgress.set(planCode);
    this.billingService.changePlan(planCode).subscribe({
      next: (sub) => {
        this.changeInProgress.set(null);
        this.subscription.set(sub);
        this.notify('Votre plan a été mis à jour.', 'snack-success');
      },
      error: (error: HttpErrorResponse) => {
        this.changeInProgress.set(null);
        const apiError = error.error as ApiError | undefined;
        const message =
          apiError?.error === 'no_active_subscription'
            ? "Souscrivez d'abord un abonnement pour pouvoir en changer."
            : 'Impossible de changer de plan.';
        this.notify(message, 'snack-error');
      },
    });
  }

  /** Vrai si l'utilisateur a un abonnement payant actif (peut donc upgrader/downgrader). */
  hasActiveSubscription(): boolean {
    const sub = this.subscription();
    return !!sub && !!sub.planCode && (sub.status === 'ACTIVE' || sub.status === 'PAST_DUE');
  }

  /** Vrai si le plan donné est le plan courant de l'utilisateur. */
  isCurrentPlan(plan: Plan): boolean {
    return this.subscription()?.planCode === plan.code;
  }

  /** Tokens du plan courant (0 si aucun / introuvable), pour comparer upgrade vs downgrade. */
  private currentPlanTokens(): number {
    const code = this.subscription()?.planCode;
    return this.plans().find((p) => p.code === code)?.tokens ?? 0;
  }

  /** Libellé contextuel du bouton d'une offre selon l'état de l'abonnement. */
  planActionLabel(plan: Plan): string {
    if (this.isCurrentPlan(plan)) {
      return 'Plan actuel';
    }
    if (!this.hasActiveSubscription()) {
      return 'Souscrire';
    }
    return plan.tokens > this.currentPlanTokens() ? 'Passer à' : 'Revenir à';
  }

  /** Action du bouton d'une offre : souscription (nouveau) ou changement de plan (existant). */
  onPlanAction(plan: Plan): void {
    if (this.isCurrentPlan(plan)) {
      return;
    }
    if (this.hasActiveSubscription()) {
      this.changePlan(plan.code);
    } else {
      this.subscribe(plan.code);
    }
  }

  /** Vrai si une action est en cours pour ce plan (bouton en « Traitement… »). */
  planBusy(plan: Plan): boolean {
    return this.checkoutInProgress() === plan.code || this.changeInProgress() === plan.code;
  }

  /** Redirection vers l'URL de paiement hébergée Stripe. Isolée pour être testable. */
  protected redirect(url: string): void {
    window.location.href = url;
  }

  /** Libellé + badge pour un statut d'abonnement (conforme au design system). */
  statusDisplay(status: SubscriptionStatus): StatusDisplay {
    switch (status) {
      case 'ACTIVE':
        return { label: 'Actif', badgeClass: 'badge--success' };
      case 'TRIALING':
        return { label: 'Essai', badgeClass: 'badge--info' };
      case 'PAST_DUE':
        return { label: 'Paiement en attente', badgeClass: 'badge--warning' };
      case 'CANCELED':
        return { label: 'Annulé', badgeClass: 'badge--neutral' };
      default:
        return { label: 'Incomplet', badgeClass: 'badge--neutral' };
    }
  }

  /** Libellé de périodicité d'un plan. */
  periodLabel(period: string): string {
    return period === 'DAILY' ? 'Pass journée' : 'par mois';
  }

  /** Part consommée du quota, bornée 0–100 % (quota nul ⇒ 100 % : accès bloqué). */
  usagePercent(usage: UsageView): number {
    if (usage.quotaTokens <= 0) {
      return 100;
    }
    return Math.min(100, Math.round((usage.usedTokens / usage.quotaTokens) * 100));
  }

  /** Vrai quand le quota de la période est atteint ou dépassé. */
  quotaReached(usage: UsageView): boolean {
    return usage.usedTokens >= usage.quotaTokens;
  }

  private notify(message: string, panelClass: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass });
  }
}
