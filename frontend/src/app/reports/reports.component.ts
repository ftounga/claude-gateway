import { AfterViewInit, Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';

import { UsageReportService } from '../core/services/usage-report.service';
import { UsagePeriodView, UsageReportView } from '../core/models/usage-report.models';

/** Noms de mois FR (index 0 = janvier) pour un libellé de période sans dépendance de locale. */
const MONTHS_FR = [
  'janvier', 'février', 'mars', 'avril', 'mai', 'juin',
  'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre',
];

/**
 * Écran « Rapports d'usage & coût » (F-16) : tableau de bord de consommation et de coût estimé de
 * l'utilisateur courant — cartes de synthèse, historique mensuel paginé et visualisation en barres.
 * Ne parle qu'à Claude Gateway (`/api/usage/report`) ; l'isolation est garantie côté backend via le
 * JWT. Le coût affiché est une estimation (tarif configuré côté backend), pas un montant facturé.
 */
@Component({
  selector: 'app-reports',
  imports: [
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './reports.component.html',
  styleUrl: './reports.component.scss',
})
export class ReportsComponent implements OnInit, AfterViewInit {
  private readonly usageReportService = inject(UsageReportService);
  private readonly snackBar = inject(MatSnackBar);

  readonly displayedColumns = ['period', 'inputTokens', 'outputTokens', 'totalTokens', 'cost'];
  readonly dataSource = new MatTableDataSource<UsagePeriodView>([]);

  readonly loading = signal(true);
  readonly report = signal<UsageReportView | null>(null);

  /** Période marquée « courante » par le backend, ou `null` si absente (aucune consommation ce mois). */
  readonly currentPeriod = computed<UsagePeriodView | null>(
    () => this.report()?.periods.find((p) => p.current) ?? null,
  );

  /** `true` lorsqu'aucune période n'est disponible (nouvel utilisateur / aucune consommation). */
  readonly isEmpty = computed(() => {
    const report = this.report();
    return !this.loading() && (report === null || report.periods.length === 0);
  });

  /** Total de tokens le plus élevé de la fenêtre (base de largeur des barres). */
  private readonly maxTotalTokens = computed(() => {
    const periods = this.report()?.periods ?? [];
    return periods.reduce((max, p) => Math.max(max, p.totalTokens), 0);
  });

  @ViewChild(MatPaginator) paginator?: MatPaginator;

  ngOnInit(): void {
    this.refresh();
  }

  ngAfterViewInit(): void {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
    }
  }

  refresh(): void {
    this.loading.set(true);
    this.usageReportService.getReport().subscribe({
      next: (report) => {
        this.report.set(report);
        this.dataSource.data = report.periods;
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.notify('Impossible de charger votre rapport d’usage.');
      },
    });
  }

  /** Libellé FR d'une période à partir de son premier jour ISO (ex. `2026-07-01` → « juillet 2026 »). */
  periodLabel(periodStart: string): string {
    const [year, month] = periodStart.split('-');
    const monthIndex = Number(month) - 1;
    const name = MONTHS_FR[monthIndex] ?? month;
    return `${name} ${year}`;
  }

  /** Largeur de barre (%) proportionnelle au total de tokens de la période (0–100). */
  barWidth(totalTokens: number): number {
    const max = this.maxTotalTokens();
    if (max <= 0) {
      return 0;
    }
    return Math.round((totalTokens / max) * 100);
  }

  /** Formate un coût estimé dans la devise du rapport (repli sur un suffixe si devise inconnue). */
  formatCost(amount: number): string {
    const currency = this.report()?.currency ?? 'EUR';
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

  private notify(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' });
  }
}
