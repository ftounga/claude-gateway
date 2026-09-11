import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ApiError } from '../../core/models/auth.models';
import { TopUpPack } from '../../core/models/billing.models';
import { QuotaAlertView } from '../../core/models/quota-alert.models';
import { BillingService } from '../../core/services/billing.service';
import { QuotaAlertService } from '../../core/services/quota-alert.service';

/**
 * Bannière d'alerte de consommation (F-42 / SF-42-02). Montre à l'utilisateur qu'il approche de sa
 * limite — <b>avant</b> que le quota ne l'arrête — et met la recharge à un seul clic.
 *
 * <p>Elle vit dans la coquille applicative, donc sur tous les écrans authentifiés, et pas sur la
 * page de facturation : un utilisateur qui consomme son quota est en train de travailler dans le
 * chat ou l'Atelier, il ne visite pas sa facturation à ce moment-là. Mettre l'alerte ailleurs que
 * sur son chemin reviendrait à ne pas la donner.</p>
 *
 * <p>L'unicité de l'alerte est portée par le serveur (SF-42-01) : ce composant n'affiche que ce que
 * `GET /api/usage/alert` lui dit d'afficher, et ne décide jamais lui-même de prévenir.</p>
 */
@Component({
  selector: 'app-quota-alert-banner',
  imports: [DatePipe, DecimalPipe, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './quota-alert-banner.component.html',
  styleUrl: './quota-alert-banner.component.scss',
})
export class QuotaAlertBannerComponent implements OnInit {
  private readonly quotaAlertService = inject(QuotaAlertService);
  private readonly billingService = inject(BillingService);
  private readonly snackBar = inject(MatSnackBar);

  /** Alerte courante, ou null tant qu'elle n'a pas été chargée (ou si le chargement a échoué). */
  readonly alert = signal<QuotaAlertView | null>(null);
  /** Vrai dès que l'utilisateur a écarté la bannière : masquage immédiat, sans attendre le serveur. */
  readonly dismissed = signal(false);
  /** Vrai pendant la création de la session de paiement (garde anti-double-clic). */
  readonly redirecting = signal(false);

  ngOnInit(): void {
    this.quotaAlertService.getAlert().subscribe({
      next: (alert) => this.alert.set(alert),
      // Silencieux à dessein : l'absence d'alerte ne doit jamais parasiter l'écran de travail.
      error: () => this.alert.set(null),
    });
  }

  /**
   * Libellé du bouton de recharge. Il porte le **montant** du pack quand le serveur en envoie un
   * (F-67) : ce bouton mène droit au paiement, et faire cliquer sans dire ce qu'on engage n'est pas
   * acceptable en vente en ligne.
   *
   * <p>Sans montant configuré, le libellé reste celui d'avant — le prix sera indiqué sur la page de
   * paiement. Aucun montant de repli n'est inventé ici, jamais.</p>
   */
  rechargeLabel(pack: TopUpPack): string {
    const price = pack.priceEur?.trim();
    return price ? `Recharger — ${pack.label} · ${price} €` : `Recharger — ${pack.label}`;
  }

  /** Vrai si la bannière doit être rendue. Aucun élément DOM n'est produit sinon. */
  visible(): boolean {
    return !!this.alert()?.raised && !this.dismissed();
  }

  /**
   * Recharge en un clic : crée la session de paiement du pack porté par l'alerte et redirige. Aucune
   * étape de sélection — l'écran de facturation reste disponible pour qui veut un autre pack.
   */
  recharge(): void {
    const pack = this.alert()?.topUp;
    if (!pack || this.redirecting()) {
      return;
    }
    this.redirecting.set(true);
    this.billingService.startTopUpCheckout(pack.code).subscribe({
      next: (res) => this.redirect(res.checkoutUrl),
      error: (error: HttpErrorResponse) => {
        this.redirecting.set(false);
        const apiError = error.error as ApiError | undefined;
        this.snackBar.open(
          apiError?.error === 'billing_unavailable'
            ? 'La facturation est momentanément indisponible.'
            : 'Impossible de démarrer le rachat de jetons.',
          'Fermer',
          { duration: 5000, panelClass: 'snack-error' },
        );
      },
    });
  }

  /**
   * Écarte l'alerte pour la période. La bannière est masquée <b>localement dans tous les cas</b> :
   * l'utilisateur a demandé à ne plus la voir, la lui réimposer parce que le serveur a hoqueté
   * serait le punir d'une panne. Au pire elle réapparaîtra au prochain chargement.
   */
  dismiss(): void {
    this.dismissed.set(true);
    this.quotaAlertService.dismissAlert().subscribe({ error: () => undefined });
  }

  /** Redirection vers l'URL de paiement hébergée Stripe. Isolée pour être testable. */
  protected redirect(url: string): void {
    window.location.href = url;
  }
}
