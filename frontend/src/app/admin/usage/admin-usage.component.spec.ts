import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { AdminUsageComponent } from './admin-usage.component';
import { AdminUsageService } from '../admin-usage.service';
import { AdminUsageView } from '../admin-usage.models';

describe('AdminUsageComponent', () => {
  let fixture: ComponentFixture<AdminUsageComponent>;
  let component: AdminUsageComponent;
  let service: jasmine.SpyObj<AdminUsageService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const usage: AdminUsageView = {
    currency: 'EUR',
    from: '2025-10-01',
    to: '2026-09-01',
    inputTokens: 1_500_000,
    outputTokens: 1_100_000,
    totalTokens: 2_600_000,
    estimatedCost: 32.0,
    users: [
      {
        userId: 'u1',
        email: 'alice@example.com',
        role: 'USER',
        planCode: 'PRO',
        subscriptionStatus: 'ACTIVE',
        inputTokens: 1_000_000,
        outputTokens: 1_000_000,
        totalTokens: 2_000_000,
        estimatedCost: 28.0,
        share: 0.7692,
        periods: [
          { periodStart: '2026-08-01', inputTokens: 400_000, outputTokens: 400_000, totalTokens: 800_000, estimatedCost: 11.2 },
          { periodStart: '2026-09-01', inputTokens: 600_000, outputTokens: 600_000, totalTokens: 1_200_000, estimatedCost: 16.8 },
        ],
      },
      {
        userId: 'u2',
        email: 'bob@example.com',
        role: 'USER',
        planCode: null,
        subscriptionStatus: null,
        inputTokens: 500_000,
        outputTokens: 100_000,
        totalTokens: 600_000,
        estimatedCost: 4.0,
        share: 0.2308,
        periods: [
          { periodStart: '2026-09-01', inputTokens: 500_000, outputTokens: 100_000, totalTokens: 600_000, estimatedCost: 4.0 },
        ],
      },
    ],
  };

  function setup(value: AdminUsageView | null = usage): void {
    service = jasmine.createSpyObj<AdminUsageService>('AdminUsageService', ['getUsage']);
    service.getUsage.and.returnValue(
      value === null ? throwError(() => new Error('boom')) : of(value),
    );
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      imports: [AdminUsageComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AdminUsageService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });

    fixture = TestBed.createComponent(AdminUsageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('affiche les totaux avec entrée et sortie distinguées', () => {
    setup();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain("Tokens d'entrée");
    expect(text).toContain('Tokens de sortie');
    expect(text).toContain('Coût estimé');
    // Les deux volumes ne sont jamais fondus dans un total unique.
    expect(component.usage()?.inputTokens).toBe(1_500_000);
    expect(component.usage()?.outputTokens).toBe(1_100_000);
  });

  it('liste chaque compte avec son plan et sa part', () => {
    setup();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('alice@example.com');
    expect(text).toContain('PRO');
    expect(text).toContain('77 %');
    expect(text).toContain('bob@example.com');
  });

  it("n'affiche jamais de nom de projet ni de poste", () => {
    setup();

    // On juge la ZONE DE DONNÉES, pas la prose : le sous-titre emploie légitimement les mots
    // « projet » et « poste » pour dire qu'ils n'y sont pas.
    const table = (fixture.nativeElement as HTMLElement).querySelector('.usage-admin__table');
    const headers = Array.from(table?.querySelectorAll('th') ?? []).map((th) => th.textContent?.trim());

    expect(headers).toEqual([
      'Compte', 'Plan', 'Entrée', 'Sortie', 'Coût estimé', 'Part', 'Évolution',
    ]);
    const data = table?.textContent?.toLowerCase() ?? '';
    expect(data).not.toContain('poste');
    expect(data).not.toContain('projet');
  });

  it('recharge sur changement de période', () => {
    setup();
    service.getUsage.calls.reset();

    component.selectWindow(3);

    expect(service.getUsage).toHaveBeenCalledWith(3);
    expect(component.months()).toBe(3);
  });

  it('ne recharge pas quand la période ne change pas', () => {
    setup();
    service.getUsage.calls.reset();

    component.selectWindow(12);

    expect(service.getUsage).not.toHaveBeenCalled();
  });

  it('affiche un état vide quand personne n’a consommé', () => {
    setup({ ...usage, users: [], totalTokens: 0, inputTokens: 0, outputTokens: 0, estimatedCost: 0 });

    expect(component.isEmpty()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Aucune consommation sur cette période.');
  });

  it('sur erreur, ne laisse aucune donnée partielle et prévient', () => {
    setup(null);

    expect(component.usage()).toBeNull();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('ne divise jamais par zéro sur une plateforme vide', () => {
    setup({ ...usage, users: [], totalTokens: 0 });

    expect(component.sharePercent(0)).toBe(0);
    expect(component.monthHeight({
      periodStart: '2026-09-01', inputTokens: 0, outputTokens: 0, totalTokens: 0, estimatedCost: 0,
    })).toBe(0);
  });

  it('nomme les mois en clair', () => {
    setup();

    expect(component.monthLabel('2026-09-01')).toBe('sept. 2026');
  });
});
