import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { ReportsComponent } from './reports.component';
import { UsageReportService } from '../core/services/usage-report.service';
import { UsageReportView } from '../core/models/usage-report.models';

describe('ReportsComponent', () => {
  let fixture: ComponentFixture<ReportsComponent>;
  let component: ReportsComponent;
  let service: jasmine.SpyObj<UsageReportService>;
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

  function setup(value: UsageReportView = report): void {
    service = jasmine.createSpyObj<UsageReportService>('UsageReportService', ['getReport']);
    service.getReport.and.returnValue(of(value));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      imports: [ReportsComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: UsageReportService, useValue: service },
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
});
