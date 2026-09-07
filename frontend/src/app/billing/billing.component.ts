import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';

import { ApiKeyService } from '../core/services/api-key.service';
import { BillingService } from '../core/services/billing.service';
import { UsageService } from '../core/services/usage.service';
import { ApiError } from '../core/models/auth.models';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../chat/confirm-dialog/confirm-dialog.component';
import {
  AtelierOptionView,
  BillingPeriodChoice,
  Plan,
  SubscriptionStatus,
  SubscriptionView,
  TopUpPack,
} from '../core/models/billing.models';
import { ApiKeyStatus } from '../core/models/api-key.models';
import { UsageView } from '../core/models/usage.models';

/** Métadonnées d'affichage d'un statut d'abonnement (libellé + classe de badge). */
interface StatusDisplay {
  label: string;
  badgeClass: string;
}

/**
 * Écran de facturation F-09 : abonnement courant, catalogue de plans, souscription via Stripe,
 * recharges ponctuelles (F-21), **option Atelier** (F-40) — le droit d'Atelier découplé du plan —
 * et **offre BYOK** (F-41) : la plateforme seule, sans jeton inclus.
 */
@Component({
  selector: 'app-billing',
  imports: [
    DatePipe,
    DecimalPipe,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatButtonToggleModule,
  ],
  templateUrl: './billing.component.html',
  styleUrl: './billing.component.scss',
})
export class BillingComponent implements OnInit {
  private readonly billingService = inject(BillingService);
  private readonly apiKeyService = inject(ApiKeyService);
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
  /**
   * Statut de la clé BYOK (F-03), ou null tant qu'il n'a pas pu être chargé. Lu ici uniquement pour
   * savoir s'il faut rappeler à un abonné BYOK de déposer sa clé (F-41 / SF-41-03).
   */
  readonly apiKeyStatus = signal<ApiKeyStatus | null>(null);
  /**
   * Périodicité choisie pour les achats (F-43). Mensuel par défaut : l'utilisateur choisit d'aller
   * vers l'engagement annuel, on ne l'y met pas d'office.
   */
  readonly selectedPeriod = signal<BillingPeriodChoice>('MONTHLY');

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
    this.loadApiKeyStatus();
  }

  /**
   * Statut de la clé BYOK. Échec **non bloquant** : sans lui, le rappel reste masqué et l'écran de
   * facturation demeure utilisable — le refus serveur (`byok_key_required`) reste, lui, en place.
   */
  loadApiKeyStatus(): void {
    this.apiKeyService.getStatus().subscribe({
      next: (status) => this.apiKeyStatus.set(status),
      error: () => this.apiKeyStatus.set(null),
    });
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

  subscribe(planCode: string, period?: BillingPeriodChoice): void {
    if (this.checkoutInProgress()) {
      return;
    }
    this.checkoutInProgress.set(planCode);
    this.billingService.startCheckout(planCode, period).subscribe({
      next: (res) => this.redirect(res.checkoutUrl),
      error: (error: HttpErrorResponse) => {
        this.checkoutInProgress.set(null);
        this.notify(this.purchaseErrorMessage(error, 'Impossible de démarrer le paiement.'), 'snack-error');
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
  changePlan(planCode: string, period?: BillingPeriodChoice): void {
    if (this.changeInProgress()) {
      return;
    }
    this.changeInProgress.set(planCode);
    this.billingService.changePlan(planCode, period).subscribe({
      next: (sub) => {
        this.changeInProgress.set(null);
        this.subscription.set(sub);
        this.notify('Votre plan a été mis à jour.', 'snack-success');
      },
      error: (error: HttpErrorResponse) => {
        this.changeInProgress.set(null);
        this.notify(this.purchaseErrorMessage(error, 'Impossible de changer de plan.'), 'snack-error');
      },
    });
  }

  /**
   * Traduit un refus d'achat en message actionnable. Le cas `yearly_not_available` ramène en plus la
   * bascule sur Mensuel et recharge le catalogue : l'offre annuelle a pu être dépubliée pendant que
   * l'écran était ouvert, et laisser la bascule sur une position qui ne mène nulle part enfermerait
   * l'utilisateur dans un bouton qui échoue à chaque clic.
   */
  private purchaseErrorMessage(error: HttpErrorResponse, fallback: string): string {
    const apiError = error.error as ApiError | undefined;
    switch (apiError?.error) {
      case 'yearly_not_available':
        this.selectedPeriod.set('MONTHLY');
        this.loadPlans();
        return "Cette offre n'est pas proposée à l'année.";
      case 'billing_unavailable':
        return 'La facturation est momentanément indisponible.';
      case 'no_active_subscription':
        return "Souscrivez d'abord un abonnement pour pouvoir en changer.";
      default:
        return fallback;
    }
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
    const period = this.periodFor(plan);
    if (this.hasActiveSubscription()) {
      this.changePlan(plan.code, period);
    } else {
      this.subscribe(plan.code, period);
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

  /**
   * Libellé de périodicité. `DAILY` est conservé ici, et seulement ici : le plan a été retiré du
   * catalogue (SF-09-04) mais un abonnement historique peut encore porter ce code, et l'écran doit
   * savoir le nommer plutôt que d'afficher « par mois » pour un pass.
   */
  periodLabel(period: string): string {
    return period === 'DAILY' ? 'Pass journée' : 'par mois';
  }

  // ------------------------------------------------ Engagement annuel (F-43 / SF-43-03)

  /**
   * Vrai si au moins une offre est proposée à l'année. Sans cela, aucune bascule n'est rendue :
   * un contrôle inerte vaut moins que pas de contrôle du tout.
   */
  hasYearlyOffer(): boolean {
    return this.plans().some((plan) => plan.yearlyAvailable);
  }

  /** Bascule Mensuel / Annuel. Ignore une valeur vide (désélection du groupe). */
  selectPeriod(period: BillingPeriodChoice | null): void {
    if (period) {
      this.selectedPeriod.set(period);
    }
  }

  /** Vrai si l'annuel est demandé **et** proposé pour cette offre. */
  isYearlyFor(plan: Plan): boolean {
    return this.selectedPeriod() === 'YEARLY' && plan.yearlyAvailable;
  }

  /**
   * Périodicité réellement achetée pour cette offre. Une offre sans engagement annuel reste
   * achetable au mois, même quand la bascule est sur Annuel — sinon un clic sur la bascule la
   * rendrait inachetable.
   */
  periodFor(plan: Plan): BillingPeriodChoice {
    return this.isYearlyFor(plan) ? 'YEARLY' : 'MONTHLY';
  }

  /** Montant affiché sur la carte, selon la périodicité effective de cette offre. */
  displayPrice(plan: Plan): string | null {
    return this.isYearlyFor(plan) ? plan.yearlyPriceEur : plan.priceEur;
  }

  /** Suffixe du montant : « / an », « la journée », ou « / mois ». */
  pricePeriodLabel(plan: Plan): string {
    if (this.isYearlyFor(plan)) {
      return '/ an';
    }
    // Plus de branche « la journée » : aucun plan du catalogue n'est facturé au jour (SF-09-04).
    return '/ mois';
  }

  /**
   * Équivalent mensuel d'un prix annuel, arrondi à l'entier. C'est la seule façon de comparer
   * honnêtement 240 € à 24 € : sans lui, la bascule remplacerait un petit nombre par un grand.
   */
  monthlyEquivalent(plan: Plan): number | null {
    if (!this.isYearlyFor(plan)) {
      return null;
    }
    const yearly = Number(plan.yearlyPriceEur);
    return Number.isFinite(yearly) && yearly > 0 ? Math.round(yearly / 12) : null;
  }

  /**
   * Économie réalisée, exprimée en mois offerts quand le compte tombe juste, en pourcentage sinon.
   *
   * Elle est **calculée** à partir des deux prix renvoyés par le serveur, jamais écrite en dur : le
   * taux de remise vit en configuration serveur, et le figer ici ferait mentir l'écran le jour où le
   * product owner le changerait sans redéployer le frontend.
   */
  savingsLabel(plan: Plan): string | null {
    if (!this.isYearlyFor(plan)) {
      return null;
    }
    const monthly = Number(plan.priceEur);
    const yearly = Number(plan.yearlyPriceEur);
    if (!Number.isFinite(monthly) || !Number.isFinite(yearly) || monthly <= 0 || yearly <= 0) {
      return null;
    }
    const saved = monthly * 12 - yearly;
    if (saved <= 0) {
      return null;
    }
    const freeMonths = saved / monthly;
    if (Number.isInteger(freeMonths)) {
      return `${freeMonths} mois offert${freeMonths > 1 ? 's' : ''}`;
    }
    return `−${Math.round((saved / (monthly * 12)) * 100)} %`;
  }

  /**
   * Suffixe du nombre de jetons inclus. Toujours « / mois » pour un abonnement — **y compris en
   * position Annuel**, et c'est délibéré : c'est le seul écran où l'utilisateur pourrait croire
   * qu'un engagement annuel lui donne douze mois de jetons d'avance.
   */
  tokensSuffix(plan: Plan): string {
    // Tout plan du catalogue alloue ses jetons au mois depuis le retrait du pass (SF-09-04).
    return ' / mois';
  }

  /**
   * Libellé d'engagement de l'abonnement en cours, ou `null`. Sans lui, rien à l'écran ne dirait à
   * un abonné annuel qu'il l'est.
   */
  commitmentLabel(): string | null {
    return this.subscription()?.billingPeriod === 'YEARLY' ? 'engagement annuel' : null;
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

  // ------------------------------------------------ Offre BYOK (F-41 / SF-41-03)

  /**
   * Vrai si l'offre en cours est servie par la clé du client. Vient du **serveur** : l'écran ne
   * compare jamais `planCode` à la chaîne « BYOK », il lit la décision qui gouverne réellement le
   * comportement — sans quoi les deux divergeraient le jour où le catalogue changerait.
   */
  isCustomerKeyBilled(): boolean {
    return this.subscription()?.customerKeyBilled === true;
  }

  /** Vrai si une clé BYOK **active** est enregistrée. Une clé désactivée ne sert aucun appel. */
  hasActiveApiKey(): boolean {
    const status = this.apiKeyStatus();
    return !!status && status.present && status.mode === 'BYOK';
  }

  /**
   * Vrai quand il faut rappeler à l'abonné BYOK de déposer sa clé : son offre l'exige et aucune clé
   * active n'existe. Sans elle, chacun de ses appels est refusé (`byok_key_required`, SF-41-02) —
   * le dire ici évite de le laisser le découvrir dans une conversation.
   *
   * <p>Le statut non chargé masque le rappel : mieux vaut ne rien dire qu'alarmer à tort.</p>
   */
  showByokKeyReminder(): boolean {
    return this.isCustomerKeyBilled() && this.apiKeyStatus() !== null && !this.hasActiveApiKey();
  }

  /** Vrai si le plan du catalogue est l'offre BYOK (carte d'offre : « aucun jeton inclus »). */
  isByokPlan(plan: Plan): boolean {
    return plan.providerMode === 'BYOK';
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
