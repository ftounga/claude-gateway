import { AfterViewInit, Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';

import { MatButtonToggleModule } from '@angular/material/button-toggle';

import { UsageReportService } from '../core/services/usage-report.service';
import { UsagePeriodView, UsageReportView } from '../core/models/usage-report.models';
import { UsageByClientService } from '../core/services/usage-by-client.service';
import { UsageService } from '../core/services/usage.service';
import { UsageView } from '../core/models/usage.models';
import { ClientUsageView, UsageByClientView } from '../core/models/usage-by-client.models';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';

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
 *
 * <p>Depuis F-61 / SF-61-04, il porte aussi la section <b>« Par client »</b> : ce que chaque poste —
 * et chaque projet dessous — a consommé sur une fenêtre choisie. C'est la matière d'une
 * refacturation, et c'est pour cela qu'elle vit ici : qui se demande ce que coûte un client se
 * demande d'abord ce qu'il a consommé. La section se charge et échoue <b>seule</b> — une panne de
 * l'agrégation ne doit pas emporter l'historique mensuel.</p>
 *
 * <p>Des volumes et des coûts, <b>jamais</b> des contenus.</p>
 *
 * <p>Depuis F-63 / SF-63-03, il dit aussi <b>comment le quota compte</b> : les tokens de cette page
 * sont des <b>volumes traités</b>, tandis que le quota se décompte au coût réel — un token de
 * sortie y pèse davantage qu'un token d'entrée, une relecture mise en cache beaucoup moins. Les
 * deux chiffres sont justes et ne sont pas égaux ; taire la différence ferait passer l'un pour une
 * erreur de l'autre.</p>
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
    MatButtonToggleModule,
    HostBadgeComponent,
  ],
  templateUrl: './reports.component.html',
  styleUrl: './reports.component.scss',
})
export class ReportsComponent implements OnInit, AfterViewInit {
  private readonly usageReportService = inject(UsageReportService);
  private readonly usageByClientService = inject(UsageByClientService);
  private readonly usageService = inject(UsageService);
  private readonly snackBar = inject(MatSnackBar);

  /** Fenêtres proposées pour la consommation par client. Le mois est le grain réel de la donnée. */
  readonly clientWindows = [3, 6, 12];

  /** Fenêtre retenue pour la section « Par client ». Ne touche pas l'historique mensuel. */
  readonly clientMonths = signal(12);
  readonly clientLoading = signal(true);
  readonly byClient = signal<UsageByClientView | null>(null);

  /** `true` lorsque rien n'est attribué sur la fenêtre (compte neuf, ou relevé encore vide). */
  readonly noClientUsage = computed(() => {
    const usage = this.byClient();
    return !this.clientLoading() && (usage === null || usage.clients.length === 0);
  });

  /**
   * Consommation de la période courante (F-63 / SF-63-03), pour montrer côte à côte le volume
   * traité et le décompte pondéré. Chargée à part et <b>en silence</b> : c'est une note de
   * lecture, son absence ne vaut pas un message d'erreur.
   */
  private readonly usage = signal<UsageView | null>(null);

  /** Tokens décomptés du quota ce mois-ci, ou `null` tant qu'on ne les connaît pas. */
  readonly billedThisPeriod = computed<number | null>(() => this.usage()?.usedTokens ?? null);

  /** Volume traité ce mois-ci, ou `null` si le backend ne le rapporte pas (antérieur à F-63). */
  readonly processedThisPeriod = computed<number | null>(
    () => this.usage()?.processedTokens ?? null,
  );

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
    this.refreshByClient();
    this.usageService.getUsage().subscribe({
      next: (usage) => this.usage.set(usage),
      // Silence volontaire : la page reste complète sans cette note.
      error: () => this.usage.set(null),
    });
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

  /** Recharge la section « Par client » sur la fenêtre courante. */
  refreshByClient(): void {
    this.clientLoading.set(true);
    this.usageByClientService.getByClient(this.clientMonths()).subscribe({
      next: (usage) => {
        this.byClient.set(usage);
        this.clientLoading.set(false);
      },
      error: () => {
        this.clientLoading.set(false);
        // La section échoue seule : l'historique mensuel reste à l'écran.
        this.notify('Impossible de charger la consommation par client.');
      },
    });
  }

  /** Change la fenêtre de la section « Par client » et recharge. */
  selectClientWindow(months: number): void {
    if (months === this.clientMonths()) {
      return;
    }
    this.clientMonths.set(months);
    this.refreshByClient();
  }

  /**
   * Libellé d'un client. Le seau « hors client » n'est pas un poste : le nommer « aucun poste » se
   * lirait comme un défaut, alors qu'il décrit un usage parfaitement normal.
   */
  clientLabel(client: ClientUsageView): string {
    if (client.hostId === null) {
      return 'Hors client';
    }
    return client.hostName ?? 'Poste supprimé';
  }

  /** Libellé d'un projet, avec le même repli explicite qu'un poste disparu. */
  projectLabel(name: string | null, workspaceId: string | null): string {
    if (workspaceId === null) {
      return 'Conversations et questions';
    }
    return name ?? 'Projet supprimé';
  }

  /** Part exprimée en pourcentage entier (0–100), pour la barre comme pour le texte. */
  sharePercent(share: number): number {
    return Math.round((share ?? 0) * 100);
  }

  /** Formate un coût de la section « Par client » (devise propre à cette réponse). */
  formatClientCost(amount: number): string {
    const currency = this.byClient()?.currency ?? 'EUR';
    try {
      return new Intl.NumberFormat('fr-FR', { style: 'currency', currency }).format(amount);
    } catch {
      return `${amount.toFixed(2)} ${currency}`;
    }
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
