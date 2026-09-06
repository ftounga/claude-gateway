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
import { MatDialog } from '@angular/material/dialog';

import { BillingService } from '../core/services/billing.service';
import { UsageService } from '../core/services/usage.service';
import { ApiError } from '../core/models/auth.models';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../chat/confirm-dialog/confirm-dialog.component';
import {
  AtelierOptionView,
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

/**
 * Écran de facturation F-09 : abonnement courant, catalogue de plans, souscription via Stripe,
 * recharges ponctuelles (F-21) et **option Atelier** (F-40) — le droit d'Atelier découplé du plan.
 */
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
  private readonly dialog = inject(MatDialog);
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
  /** État de l'option Atelier (F-40), ou null tant qu'il n'a pas pu être chargé. */
  readonly atelierOption = signal<AtelierOptionView | null>(null);
  /** Vrai pendant un appel de souscription ou de résiliation de l'option (bouton désactivé). */
  readonly atelierOptionInProgress = signal(false);

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
    this.loadAtelierOption();
  }

  /**
   * État de l'option Atelier (F-40). Échec **non bloquant** : la section reste masquée et l'écran
   * de facturation demeure utilisable, comme pour les recharges.
   */
  loadAtelierOption(): void {
    this.billingService.getAtelierOption().subscribe({
      next: (option) => this.atelierOption.set(option),
      error: () => this.atelierOption.set(null),
    });
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

  /** Nombre de jours restants d'essai (null hors essai), pour l'affichage du statut courant. */
  trialDaysLeft(): number | null {
    const sub = this.subscription();
    if (!sub || sub.status !== 'TRIALING' || !sub.trialEndsAt) {
      return null;
    }
    const days = Math.ceil((new Date(sub.trialEndsAt).getTime() - Date.now()) / 86_400_000);
    return Math.max(0, days);
  }

  /** Vrai si l'utilisateur n'a aucune offre payante active (essai ou aucun abonnement). */
  hasNoPaidPlan(): boolean {
    return !this.subscription()?.planCode;
  }

  /** Offre mise en avant (« Recommandé »). */
  isRecommended(plan: Plan): boolean {
    return plan.code === 'PRO';
  }

  /**
   * Prix d'affichage d'un pack de tokens (EUR). Affichage uniquement ; le débit réel reste porté par
   * le price Stripe côté serveur. TODO : exposer ce prix via l'API (comme `priceEur` des plans).
   */
  packPrice(code: string): string | null {
    const prices: Record<string, string> = { DAY: '4,99', STANDARD: '29' };
    return prices[code] ?? null;
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

  // ------------------------------------------------ Option Atelier (F-40 / SF-40-03)

  /** Vrai quand un bouton d'achat de l'option a un sens (droit absent et paiement configuré). */
  canSubscribeAtelierOption(): boolean {
    const option = this.atelierOption();
    return (
      !!option &&
      !option.includedInPlan &&
      !this.atelierOptionActive() &&
      option.available
    );
  }

  /** Vrai si l'option est en cours (souscrite et pas encore fermée). */
  atelierOptionActive(): boolean {
    const status = this.atelierOption()?.status;
    return status === 'ACTIVE' || status === 'PAST_DUE';
  }

  /** Vrai si une résiliation est déjà programmée : plus rien à cliquer, une date à lire. */
  atelierOptionEnding(): boolean {
    return this.atelierOptionActive() && !!this.atelierOption()?.cancelAt;
  }

  /** Lance la souscription de l'option et redirige vers le paiement. */
  subscribeAtelierOption(): void {
    if (this.atelierOptionInProgress()) {
      return;
    }
    this.atelierOptionInProgress.set(true);
    this.billingService.startAtelierOptionCheckout().subscribe({
      next: (res) => this.redirect(res.checkoutUrl),
      error: (error: HttpErrorResponse) => {
        this.atelierOptionInProgress.set(false);
        this.notify(this.atelierOptionErrorMessage(error), 'snack-error');
        // L'état a pu changer sous nos pieds (souscription faite ailleurs) : on le relit.
        this.loadAtelierOption();
      },
    });
  }

  /**
   * Résilie l'option, après confirmation explicite (`MatDialog`, jamais `window.confirm`). Le
   * message dit ce qui se passe vraiment : l'accès reste ouvert jusqu'à la fin de la période payée.
   */
  cancelAtelierOption(): void {
    if (this.atelierOptionInProgress()) {
      return;
    }
    const data: ConfirmDialogData = {
      title: "Résilier l'option Atelier",
      message:
        "Votre accès à l'Atelier reste ouvert jusqu'à la fin de la période déjà payée, " +
        'puis ne sera pas reconduit. Votre offre et votre quota de tokens ne changent pas.',
      confirmLabel: 'Résilier',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.performAtelierOptionCancel();
        }
      });
  }

  private performAtelierOptionCancel(): void {
    this.atelierOptionInProgress.set(true);
    this.billingService.cancelAtelierOption().subscribe({
      next: (option) => {
        this.atelierOptionInProgress.set(false);
        this.atelierOption.set(option);
        this.notify(
          "Option Atelier résiliée. Votre accès reste ouvert jusqu'à la fin de la période.",
          'snack-success',
        );
      },
      error: (error: HttpErrorResponse) => {
        this.atelierOptionInProgress.set(false);
        this.notify(this.atelierOptionErrorMessage(error), 'snack-error');
        this.loadAtelierOption();
      },
    });
  }

  /** Traduit un refus de l'API d'option en message actionnable (jamais un code brut). */
  private atelierOptionErrorMessage(error: HttpErrorResponse): string {
    const apiError = error.error as ApiError | undefined;
    switch (apiError?.error) {
      case 'no_active_subscription':
        return "Souscrivez d'abord une offre Solo ou Pro pour ajouter l'option Atelier.";
      case 'atelier_option_included':
        return "L'Atelier est déjà inclus dans votre offre.";
      case 'atelier_option_already_active':
        return "L'option Atelier est déjà active sur votre compte.";
      case 'atelier_option_not_active':
        return "Aucune option Atelier à résilier.";
      case 'billing_unavailable':
        return 'La facturation est momentanément indisponible.';
      default:
        return "Impossible de mettre à jour l'option Atelier.";
    }
  }

  private notify(message: string, panelClass: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass });
  }
}
