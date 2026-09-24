import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { AdminComponent } from './admin.component';
import { AdminService } from './admin.service';
import { AdminUser } from './admin.models';
import { AuthService } from '../core/services/auth.service';
import { AccessCodeAdminService } from './access-code-admin.service';
import { GovernanceAdminService } from './governance-admin.service';
import { AdminCostService } from './cost/admin-cost.service';
import { AdminBilansService } from './bilans/admin-bilans.service';
import { AdminUsageService } from './admin-usage.service';

describe('AdminComponent', () => {
  let fixture: ComponentFixture<AdminComponent>;
  let adminSpy: jasmine.SpyObj<AdminService>;

  const users: AdminUser[] = [
    {
      id: '1',
      email: 'a@example.com',
      role: 'ADMIN',
      createdAt: '2026-07-01T00:00:00Z',
      planCode: 'PRO',
      subscriptionStatus: 'ACTIVE',
      currentPeriodEnd: null,
      totalTokens: 1234,
    },
    {
      id: '2',
      email: 'b@example.com',
      role: 'USER',
      createdAt: '2026-07-01T00:00:00Z',
      planCode: null,
      subscriptionStatus: null,
      currentPeriodEnd: null,
      totalTokens: 0,
    },
  ];

  beforeEach(async () => {
    adminSpy = jasmine.createSpyObj<AdminService>('AdminService', ['getUsers']);
    adminSpy.getUsers.and.returnValue(of(users));

    // La section Gouvernance (F-51 / SF-51-06) vit désormais dans cet écran : on la neutralise ici,
    // elle a son propre spec. Ce test-ci porte sur la liste des utilisateurs, et rien d'autre.
    const governanceSpy = jasmine.createSpyObj<GovernanceAdminService>('GovernanceAdminService', [
      'list',
      'controls',
    ]);
    governanceSpy.list.and.returnValue(of([]));
    governanceSpy.controls.and.returnValue(of([]));

    // Même chose pour la section Codes d'accès (F-62 / SF-62-03) : elle a son propre spec.
    const accessCodeSpy = jasmine.createSpyObj<AccessCodeAdminService>('AccessCodeAdminService', [
      'list',
      'issue',
      'trials',
    ]);
    accessCodeSpy.list.and.returnValue(of([]));
    accessCodeSpy.trials.and.returnValue(of([]));

    // Et pour la section Consommation (F-61 / SF-61-05), qui a elle aussi son propre spec.
    const usageSpy = jasmine.createSpyObj<AdminUsageService>('AdminUsageService', ['getUsage']);
    usageSpy.getUsage.and.returnValue(
      of({
        currency: 'EUR',
        from: '2025-10-01',
        to: '2026-09-01',
        inputTokens: 0,
        outputTokens: 0,
        totalTokens: 0,
        estimatedCost: 0,
        users: [],
      }),
    );

    // Et pour la section Coût réel (F-133 / SF-133-07), qui a elle aussi son propre spec.
    const costSpy = jasmine.createSpyObj<AdminCostService>('AdminCostService', [
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget',
    ]);
    costSpy.summary.and.returnValue(
      of({
        period: 'week' as const,
        from: '2026-09-14',
        to: '2026-09-20',
        spentEur: 0,
        budgetEur: null,
        percent: null,
        clients: [],
      }),
    );
    costSpy.alerts.and.returnValue(of([]));

    const bilansSpy = jasmine.createSpyObj<AdminBilansService>('AdminBilansService',
      ['list', 'open', 'produce']);
    bilansSpy.list.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [AdminComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AdminService, useValue: adminSpy },
        { provide: GovernanceAdminService, useValue: governanceSpy },
        { provide: AccessCodeAdminService, useValue: accessCodeSpy },
        { provide: AdminUsageService, useValue: usageSpy },
        { provide: AdminCostService, useValue: costSpy },
        // F-155 / SF-155-04 : la section Bilans est un enfant de plus dans cette page. Comme les
        // autres, son service est doublé — sinon il réclamerait un HttpClient que ce test n'a pas.
        { provide: AdminBilansService, useValue: bilansSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
  });

  it('charge et affiche les utilisateurs', () => {
    expect(adminSpy.getUsers).toHaveBeenCalled();
    expect(fixture.componentInstance.dataSource.data.length).toBe(2);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('a@example.com');
    expect(text).toContain('ADMIN');
  });
});

describe('AuthService.isAdmin', () => {
  function tokenWithRole(role: string): string {
    return `header.${btoa(JSON.stringify({ role }))}.sig`;
  }

  it('détecte le rôle ADMIN depuis le claim du JWT', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const auth = TestBed.inject(AuthService);

    auth.storeToken(tokenWithRole('ADMIN'));
    expect(auth.isAdmin()).toBeTrue();

    auth.storeToken(tokenWithRole('USER'));
    expect(auth.isAdmin()).toBeFalse();

    auth.clearToken();
  });
});
