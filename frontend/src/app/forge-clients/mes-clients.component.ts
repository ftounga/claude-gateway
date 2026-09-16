import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierService } from '../core/services/atelier.service';
import { HostPresenceService } from '../core/services/host-presence.service';
import { PosteBillingService, RevenueSummary } from '../core/services/poste-billing.service';
import { RunnerHostOverview } from '../core/models/atelier.models';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import { CraDialogComponent } from '../postes/cra-dialog/cra-dialog.component';
import { euros, tjmLabel } from '../shared/money';
import { ClientCard, Showcase, buildShowcase } from './client-showcase';

/** Ce qui empêche la Vitrine d'exister — distinct d'un simple cumul vide. */
export type ClientsError = 'none' | 'network';

/**
 * **Mes clients — la Vitrine de la Forge** (F-124 / SF-124-05, maquette A validée par le PO).
 *
 * <p>Le revenu par client (SF-124-02) vivait, discret, dans une pastille de la colonne et un chiffre
 * de l'en-tête. Le PO l'a voulu <b>mis en valeur</b> : un bandeau « fierté » avec le total tous
 * clients en très gros, et de grandes cartes clients. Cet écran ne fait que <b>présenter</b> — il lit
 * les mêmes endpoints (`/api/activity/revenue`, l'overview des postes) et ne recalcule rien.</p>
 *
 * <p><b>Écran autonome</b>, sur le patron de « Voir travailler » (F-98 / SF-98-04) : `/forge/clients`.
 * La colonne maître–détail de la Forge n'est pas touchée. L'isolation `user_id` est garantie côté
 * gateway — l'appel ne porte aucun identifiant, la vue part du JWT.</p>
 */
@Component({
  selector: 'app-mes-clients',
  imports: [
    RouterLink,
    HostBadgeComponent,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './mes-clients.component.html',
  styleUrl: './mes-clients.component.scss',
})
export class MesClientsComponent implements OnInit {
  private readonly billing = inject(PosteBillingService);
  private readonly atelier = inject(AtelierService);
  private readonly presence = inject(HostPresenceService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly error = signal<ClientsError>('none');

  /** Le résumé de revenu réel (SF-124-02), ou `null` tant qu'il n'a pas été lu. */
  private readonly summary = signal<RevenueSummary | null>(null);

  /** Les postes de l'utilisateur, indexés par identifiant : pour le nom et l'état de présence (F-97). */
  private readonly hostsById = signal<Record<string, RunnerHostOverview>>({});

  /** Le modèle d'affichage — bandeau et cartes — dérivé du revenu et des noms. */
  readonly showcase = computed<Showcase | null>(() => {
    const summary = this.summary();
    if (!summary) {
      return null;
    }
    const hosts = this.hostsById();
    return buildShowcase(summary, (hostId) => hosts[hostId]?.name);
  });

  /** Vrai quand la lecture a réussi mais qu'aucun poste n'a de TJM : la Vitrine est vide, et le dit. */
  readonly isEmpty = computed(() => {
    const showcase = this.showcase();
    return showcase !== null && showcase.cards.length === 0;
  });

  ngOnInit(): void {
    this.load();
    // Les libellés datés (« vu il y a 12 s ») avancent à la seconde, sans appel (F-97 / SF-97-02).
    const releaseClock = this.presence.watchClock();
    this.destroyRef.onDestroy(releaseClock);
  }

  /** Lit le revenu réel et l'overview des postes. Une erreur sur l'un ou l'autre → message + réessai. */
  load(): void {
    this.loading.set(true);
    this.error.set('none');
    forkJoin({
      revenue: this.billing.revenue(),
      hosts: this.atelier.runnerHostsOverview(),
    }).subscribe({
      next: ({ revenue, hosts }) => {
        const byId: Record<string, RunnerHostOverview> = {};
        for (const host of hosts ?? []) {
          if (host.id) {
            byId[host.id] = host;
          }
        }
        this.hostsById.set(byId);
        this.summary.set(revenue);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('network');
        this.loading.set(false);
      },
    });
  }

  /** Le total tous clients sans le symbole, « 49 900 » — le « € » est un span or à part (charte). */
  totalAmount(): string {
    return euros(this.showcase()?.totalCents ?? 0);
  }

  /** « dont 4 760 € estimés », ou `null` quand rien n'est supposé. */
  estimatedLabel(): string | null {
    const supposed = this.showcase()?.supposedCents ?? 0;
    return supposed > 0 ? `dont ${euros(supposed)} € estimés` : null;
  }

  /** Le revenu cumulé d'une carte sans le symbole, « 14 300 » — le « € » est un span or à part. */
  cumulAmount(card: ClientCard): string {
    return euros(card.cumulCents);
  }

  /** Le TJM valorisé d'une carte, « 650 €/j ». */
  tjmLabel(card: ClientCard): string {
    return tjmLabel(card.tjmCents);
  }

  /** « 22 jours travaillés », « 18 jours · dont 7 estimés », ou « — » sans TJM (garde). */
  daysLabel(card: ClientCard): string {
    if (card.days === null) {
      return '—';
    }
    const days = this.formatDays(card.days);
    if (card.supposedDays && card.supposedDays > 0) {
      return `${days} jours · dont ${this.formatDays(card.supposedDays)} estimés`;
    }
    return `${days} jours travaillés`;
  }

  /** « 73 j cumulés » pour la puce du bandeau. */
  totalDaysLabel(): string {
    return `${this.formatDays(this.showcase()?.totalDays ?? 0)} j cumulés`;
  }

  /** L'état de présence daté d'un poste (F-97), jamais « Connecté » seul. */
  stateLabel(card: ClientCard): string {
    const host = this.hostsById()[card.hostId];
    const label = this.presence.label(card.hostId, host?.connected ?? false, host?.lastSeenAt);
    return label.charAt(0).toUpperCase() + label.slice(1);
  }

  /** Vrai si le poste est en ligne — pilote la pastille de statut. */
  online(card: ClientCard): boolean {
    return this.presence.isOnline(card.hostId, this.hostsById()[card.hostId]?.connected ?? false);
  }

  /** Ouvre le poste dans le maître–détail de la Forge. */
  openHost(card: ClientCard): void {
    void this.router.navigate(['/forge', card.hostId]);
  }

  /**
   * Ouvre le dialogue **CRA** (SF-124-03) — le même que la Forge. À la fermeture avec écriture, on
   * relit le revenu pour que la Vitrine reflète les jours déclarés.
   */
  declareCra(): void {
    this.dialog.open(CraDialogComponent, { width: CraDialogComponent.DIALOG_WIDTH })
      .afterClosed()
      .subscribe((changed) => {
        if (changed) {
          this.load();
        }
      });
  }

  private formatDays(days: number): string {
    return days.toLocaleString('fr-FR', { maximumFractionDigits: 1 });
  }
}
