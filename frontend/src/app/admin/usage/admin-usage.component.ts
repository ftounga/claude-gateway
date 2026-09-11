import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminUsageService } from '../admin-usage.service';
import { AdminMonthUsage, AdminUsageView } from '../admin-usage.models';

/** Noms de mois FR abrégés (index 0 = janvier), pour un libellé sans dépendance de locale. */
const MONTHS_FR_SHORT = [
  'janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin',
  'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.',
];

/**
 * Section **Consommation** de l'administration (F-61 / SF-61-05) : qui consomme, combien, à quel
 * coût, et comment cela évolue sur la période choisie.
 *
 * <p><b>L'entrée et la sortie ne sont jamais additionnées</b> dans une même carte : leurs coûts
 * unitaires n'ont rien à voir, et un chiffre unique de « tokens » cacherait l'essentiel de la
 * dépense.</p>
 *
 * <p><b>Des volumes et des coûts, jamais des contenus</b> : ni message, ni commande, ni chemin, ni
 * nom de projet ou de poste. L'API n'en rend aucun, et l'écran n'en invente pas. Un administrateur
 * qui pourrait lire les conversations de ses utilisateurs ferait de la gateway un outil de
 * surveillance.</p>
 *
 * <p>Aucune action : cette section <b>lit</b>. Agir sur les quotas, exporter, facturer sont hors du
 * périmètre de la feature.</p>
 */
@Component({
  selector: 'app-admin-usage',
  imports: [MatButtonToggleModule, MatCardModule, MatIconModule, MatProgressBarModule],
  templateUrl: './admin-usage.component.html',
  styleUrl: './admin-usage.component.scss',
})
export class AdminUsageComponent implements OnInit {
  private readonly service = inject(AdminUsageService);
  private readonly snackBar = inject(MatSnackBar);

  /** Fenêtres proposées, en mois — le grain réel des compteurs de période. */
  readonly windows = [3, 6, 12];

  readonly months = signal(12);
  readonly loading = signal(true);
  readonly usage = signal<AdminUsageView | null>(null);

  /** `true` quand la plateforme n'a rien consommé sur la fenêtre (ou que l'appel a échoué). */
  readonly isEmpty = computed(() => {
    const usage = this.usage();
    return !this.loading() && (usage === null || usage.users.length === 0);
  });

  /** Plus gros total mensuel de la fenêtre : base commune des barres d'évolution. */
  private readonly maxMonthTotal = computed(() => {
    const users = this.usage()?.users ?? [];
    return users.reduce(
      (max, user) => user.periods.reduce((inner, period) => Math.max(inner, period.totalTokens), max),
      0,
    );
  });

  ngOnInit(): void {
    this.load();
  }

  /** Recharge la section sur la fenêtre courante. */
  load(): void {
    this.loading.set(true);
    this.service.getUsage(this.months()).subscribe({
      next: (usage) => {
        this.usage.set(usage);
        this.loading.set(false);
      },
      error: () => {
        // Aucune donnée partielle ne reste à l'écran : un refus doit se voir comme un refus.
        this.usage.set(null);
        this.loading.set(false);
        this.snackBar.open('Impossible de charger la consommation.', 'Fermer', { duration: 4000 });
      },
    });
  }

  /** Change la fenêtre et recharge. */
  selectWindow(months: number): void {
    if (months === this.months()) {
      return;
    }
    this.months.set(months);
    this.load();
  }

  /** Part exprimée en pourcentage entier (0–100). */
  sharePercent(share: number): number {
    return Math.round((share ?? 0) * 100);
  }

  /** Hauteur relative d'une barre d'évolution (0–100), à échelle commune à tous les comptes. */
  monthHeight(period: AdminMonthUsage): number {
    const max = this.maxMonthTotal();
    if (max <= 0) {
      return 0;
    }
    return Math.max(4, Math.round((period.totalTokens / max) * 100));
  }

  /** Libellé court d'un mois (`2026-09-01` → « sept. 2026 »). */
  monthLabel(periodStart: string): string {
    const [year, month] = periodStart.split('-');
    const name = MONTHS_FR_SHORT[Number(month) - 1] ?? month;
    return `${name} ${year}`;
  }

  /** Formate un coût dans la devise de la réponse. */
  formatCost(amount: number): string {
    const currency = this.usage()?.currency ?? 'EUR';
    try {
      return new Intl.NumberFormat('fr-FR', { style: 'currency', currency }).format(amount);
    } catch {
      return `${amount.toFixed(2)} ${currency}`;
    }
  }

  /** Formate un nombre de tokens avec séparateurs de milliers. */
  formatTokens(tokens: number): string {
    return new Intl.NumberFormat('fr-FR').format(tokens);
  }
}
