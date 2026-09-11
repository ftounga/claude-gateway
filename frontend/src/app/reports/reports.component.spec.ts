import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { ReportsComponent } from './reports.component';
import { UsageReportService } from '../core/services/usage-report.service';
import { UsageReportView } from '../core/models/usage-report.models';
import { UsageByClientService } from '../core/services/usage-by-client.service';
import { UsageService } from '../core/services/usage.service';
import { UsageView } from '../core/models/usage.models';
import { UsageByClientView } from '../core/models/usage-by-client.models';

describe('ReportsComponent', () => {
  let fixture: ComponentFixture<ReportsComponent>;
  let component: ReportsComponent;
  let service: jasmine.SpyObj<UsageReportService>;
  let byClientService: jasmine.SpyObj<UsageByClientService>;
  let usageService: jasmine.SpyObj<UsageService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const report: UsageReportView = {
    currency: 'EUR',
    periods: [
      {
        periodStart: '2026-07-01',
        periodEnd: '2026-08-01',
        inputTokens: 12000,
        outputTokens: 8000,
        totalTokens: 20000,
        estimatedCost: 0.156,
        current: true,
      },
      {
        periodStart: '2026-06-01',
        periodEnd: '2026-07-01',
        inputTokens: 3000,
        outputTokens: 0,
        totalTokens: 3000,
        estimatedCost: 0.009,
        current: false,
      },
    ],
    totalInputTokens: 15000,
    totalOutputTokens: 8000,
    totalTokens: 23000,
    totalEstimatedCost: 0.165,
  };

  /** Consommation par client (F-61 / SF-61-04) : deux clients, dont le seau « hors client ». */
  const byClient: UsageByClientView = {
    currency: 'EUR',
    from: '2025-10-01',
    to: '2026-09-01',
    inputTokens: 1_040_000,
    outputTokens: 200_000,
    totalTokens: 1_240_000,
    estimatedCost: 6.12,
    clients: [
      {
        hostId: 'h1',
        hostName: 'poste-groupe-x',
        inputTokens: 1_000_000,
        outputTokens: 200_000,
        totalTokens: 1_200_000,
        estimatedCost: 6.0,
        share: 0.9677,
        projects: [
          {
            workspaceId: 'w1',
            name: 'refonte-paie',
            inputTokens: 1_000_000,
            outputTokens: 200_000,
            totalTokens: 1_200_000,
            estimatedCost: 6.0,
            share: 0.9677,
          },
        ],
      },
      {
        hostId: null,
        hostName: null,
        inputTokens: 40_000,
        outputTokens: 0,
        totalTokens: 40_000,
        estimatedCost: 0.12,
        share: 0.0323,
        projects: [
          {
            workspaceId: null,
            name: null,
            inputTokens: 40_000,
            outputTokens: 0,
            totalTokens: 40_000,
            estimatedCost: 0.12,
            share: 0.0323,
          },
        ],
      },
    ],
  };

  /** Consommation de la période : volume traité et décompte pondéré diffèrent (F-63). */
  const usage: UsageView = {
    usedTokens: 5200,
    quotaTokens: 200000,
    remainingTokens: 194800,
    processedTokens: 12000,
    periodStart: '2026-09-01',
    periodEnd: '2026-10-01',
  };

  function setup(value: UsageReportView = report, clients: UsageByClientView = byClient,
    currentUsage: UsageView | null = usage): void {
    service = jasmine.createSpyObj<UsageReportService>('UsageReportService', ['getReport']);
    service.getReport.and.returnValue(of(value));
    byClientService = jasmine.createSpyObj<UsageByClientService>('UsageByClientService',
      ['getByClient']);
    byClientService.getByClient.and.returnValue(of(clients));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    usageService = jasmine.createSpyObj<UsageService>('UsageService', ['getUsage']);
    usageService.getUsage.and.returnValue(
      currentUsage === null ? throwError(() => new Error('indisponible')) : of(currentUsage),
    );

    TestBed.configureTestingModule({
      imports: [ReportsComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: UsageReportService, useValue: service },
        { provide: UsageByClientService, useValue: byClientService },
        { provide: UsageService, useValue: usageService },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });

    fixture = TestBed.createComponent(ReportsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the report on init and fills the table', () => {
    setup();
    expect(service.getReport).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
    expect(component.dataSource.data.length).toBe(2);
    expect(component.report()?.totalTokens).toBe(23000);
  });

  it('exposes the current period from the report', () => {
    setup();
    expect(component.currentPeriod()?.periodStart).toBe('2026-07-01');
    expect(component.isEmpty()).toBeFalse();
  });

  it('flags an empty state when there are no periods', () => {
    setup({
      currency: 'EUR',
      periods: [],
      totalInputTokens: 0,
      totalOutputTokens: 0,
      totalTokens: 0,
      totalEstimatedCost: 0,
    });
    expect(component.isEmpty()).toBeTrue();
    expect(component.currentPeriod()).toBeNull();
  });

  it('shows a snackbar and stops loading when the report fails', () => {
    setup();
    service.getReport.and.returnValue(throwError(() => new Error('boom')));
    component.refresh();
    expect(component.loading()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('builds a French period label from the ISO start date', () => {
    setup();
    expect(component.periodLabel('2026-07-01')).toBe('juillet 2026');
  });

  it('computes bar width relative to the largest period', () => {
    setup();
    // Plus grande période = 20000 tokens → 100 % ; 3000 → 15 %.
    expect(component.barWidth(20000)).toBe(100);
    expect(component.barWidth(3000)).toBe(15);
  });

  // ------------------------------------------------------ Par client (F-61 / SF-61-04)

  it('charge la consommation par client et rend chaque poste avec ses projets', () => {
    setup();

    expect(byClientService.getByClient).toHaveBeenCalledWith(12);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('poste-groupe-x');
    expect(text).toContain('refonte-paie');
    expect(text).toContain('Hors client');
  });

  it('écrit le nom du poste : la couleur ne porte jamais seule l’information', () => {
    setup();

    const badge = (fixture.nativeElement as HTMLElement).querySelector('app-host-badge');
    expect(badge).not.toBeNull();
    expect(badge?.textContent).toContain('poste-groupe-x');
  });

  it('recharge la section sur changement de période, sans toucher au rapport mensuel', () => {
    setup();
    byClientService.getByClient.calls.reset();
    service.getReport.calls.reset();

    component.selectClientWindow(3);

    expect(byClientService.getByClient).toHaveBeenCalledWith(3);
    expect(service.getReport).not.toHaveBeenCalled();
  });

  it('ne recharge pas quand la période ne change pas', () => {
    setup();
    byClientService.getByClient.calls.reset();

    component.selectClientWindow(12);

    expect(byClientService.getByClient).not.toHaveBeenCalled();
  });

  it('affiche un état vide quand rien n’est attribué', () => {
    setup(report, { ...byClient, clients: [], totalTokens: 0 });

    expect(component.noClientUsage()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Aucune consommation attribuée sur cette période.');
  });

  it('sur erreur, prévient sans emporter l’historique mensuel', () => {
    setup();
    byClientService.getByClient.and.returnValue(throwError(() => new Error('boom')));

    component.refreshByClient();

    expect(component.clientLoading()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
    // L'historique mensuel est toujours là : les deux sections échouent séparément.
    expect(component.report()?.totalTokens).toBe(23000);
  });

  it('nomme explicitement un poste ou un projet supprimé', () => {
    setup();

    expect(component.clientLabel({ ...byClient.clients[0], hostName: null })).toBe('Poste supprimé');
    expect(component.projectLabel(null, 'w9')).toBe('Projet supprimé');
    expect(component.projectLabel(null, null)).toBe('Conversations et questions');
  });

  it('arrondit les parts en pourcentage entier', () => {
    setup();

    expect(component.sharePercent(0.9677)).toBe(97);
    expect(component.sharePercent(0)).toBe(0);
  });

  // ----------------------------------------------------- F-63 / SF-63-03 : dire comment on compte

  it('names the weighted count next to the volumes it displays', () => {
    setup();

    const text: string = fixture.nativeElement.textContent;
    // La page montre des VOLUMES ; le quota, lui, se décompte au coût réel. Taire la différence
    // ferait passer deux chiffres justes pour une contradiction.
    expect(text).toContain('volumes traités');
    expect(text).toContain('coût réel');
  });

  it('shows both figures of the current period side by side', () => {
    setup();

    expect(component.processedThisPeriod()).toBe(12000);
    expect(component.billedThisPeriod()).toBe(5200);
  });

  it('stays silent and complete when the current consumption cannot be loaded', () => {
    setup(report, byClient, null);

    // Une note de lecture absente n'est pas une erreur : ni message, ni chiffres inventés.
    expect(component.processedThisPeriod()).toBeNull();
    expect(component.billedThisPeriod()).toBeNull();
    expect(component.dataSource.data.length).toBe(2);
  });
});
