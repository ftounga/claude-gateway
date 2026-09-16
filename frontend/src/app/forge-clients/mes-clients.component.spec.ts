import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';

import { AtelierService } from '../core/services/atelier.service';
import { PosteBillingService, RevenueSummary } from '../core/services/poste-billing.service';
import { RunnerHostOverview } from '../core/models/atelier.models';
import { MesClientsComponent } from './mes-clients.component';

/** La Vitrine des clients (F-124 / SF-124-05) : ce qu'elle rend depuis les données RÉELLES du revenu. */
describe('MesClientsComponent', () => {
  let fixture: ComponentFixture<MesClientsComponent>;

  const host = (id: string, name: string, extra: Partial<RunnerHostOverview> = {}): RunnerHostOverview => ({
    id, name, connected: true, activeProjects: 0, createdAt: '',
    lastSeenAt: new Date(Date.now() - 12_000).toISOString(), projects: [], ...extra,
  });

  const summary: RevenueSummary = {
    startMonth: '2025-09', currentMonth: '2026-01',
    totalCents: 1_430_000 + 1_224_000, totalDeclaredCents: 1_430_000 + 748_000, totalSupposedCents: 476_000,
    postes: [
      { hostId: 'h1', tjmCents: 65_000, cumulCents: 1_430_000, declaredCents: 1_430_000, supposedCents: 0 },
      { hostId: 'h3', tjmCents: 68_000, cumulCents: 1_224_000, declaredCents: 748_000, supposedCents: 476_000 },
    ],
  };

  const dialogRef = { afterClosed: () => of(false) };
  let dialogOpen: jasmine.Spy;

  function build(opts: {
    revenue?: Observable<RevenueSummary>;
    hosts?: RunnerHostOverview[];
  } = {}): { root: HTMLElement; component: MesClientsComponent; router: Router } {
    dialogOpen = jasmine.createSpy('open').and.returnValue(dialogRef);
    TestBed.configureTestingModule({
      imports: [MesClientsComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: PosteBillingService, useValue: { revenue: () => opts.revenue ?? of(summary) } },
        {
          provide: AtelierService,
          useValue: { runnerHostsOverview: () => of(opts.hosts ?? [host('h1', 'Free'), host('h3', 'CAGIP', { connected: false, lastSeenAt: new Date(Date.now() - 1_080_000).toISOString() })]) },
        },
        { provide: MatDialog, useValue: { open: dialogOpen } },
      ],
    });
    fixture = TestBed.createComponent(MesClientsComponent);
    const router = TestBed.inject(Router);
    fixture.detectChanges();
    return { root: fixture.nativeElement as HTMLElement, component: fixture.componentInstance, router };
  }

  const cards = (root: HTMLElement) => Array.from(root.querySelectorAll<HTMLElement>('.vitrine-card'));
  // fr-FR sépare les milliers par une espace insécable étroite (U+202F) : on normalise pour comparer.
  const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ');

  it('rend le bandeau avec le total réel en or et le mois de départ, puis une carte par poste', () => {
    const { root } = build();
    expect(text(root.querySelector('.vitrine__eyebrow'))).toContain('septembre 2025');
    // Total = 2 654 000 centimes → « 26 540 € ».
    expect(text(root.querySelector('.vitrine__total'))).toContain('26 540');
    expect(cards(root).length).toBe(2);
    // Trié par revenu décroissant : Free (14 300) avant CAGIP (12 240).
    expect(text(cards(root)[0])).toContain('Free');
    expect(text(cards(root)[0])).toContain('14 300');
    expect(text(cards(root)[0])).toContain('22 jours travaillés');
  });

  it('écrit le badge Déclaré / Partiellement estimé selon la part supposée', () => {
    const { root } = build();
    expect(cards(root)[0].querySelector('.badge--success')?.textContent).toContain('Déclaré');
    const cagip = cards(root)[1];
    expect(cagip.querySelector('.badge--warning')?.textContent).toContain('Partiellement estimé');
    expect(cagip.textContent).toContain('dont 7 estimés');
  });

  it('montre l\'état daté d\'un poste hors ligne, sans masquer sa carte (F-97)', () => {
    const { root } = build();
    const cagip = cards(root)[1];
    expect(cagip.textContent).toContain('Hors ligne · vu il y a');
    expect(cagip.querySelector('.vitrine-card__dot--online')).toBeNull();
  });

  it('dit la Vitrine vide quand aucun poste n\'a de TJM', () => {
    const empty: RevenueSummary = { ...summary, totalCents: 0, totalSupposedCents: 0, postes: [] };
    const { root } = build({ revenue: of(empty) });
    expect(cards(root).length).toBe(0);
    expect(root.querySelector('.vitrine__empty')?.textContent).toContain('Aucun revenu à afficher');
  });

  it('affiche un message et un réessai quand le revenu ne peut être lu', () => {
    const { root } = build({ revenue: throwError(() => new Error('boom')) });
    expect(root.querySelector('.vitrine__notice')?.textContent).toContain("n'a pas pu être lu");
    expect(root.querySelector('.vitrine__notice button')).not.toBeNull();
  });

  it('ouvre le dialogue CRA au clic sur « Déclarer mon CRA »', () => {
    const { root, component } = build();
    component.declareCra();
    expect(dialogOpen).toHaveBeenCalled();
    expect(root.querySelector('.vitrine__cra')?.textContent).toContain('Déclarer mon CRA');
  });

  it('navigue vers le poste au clic sur une carte', () => {
    const { component, router } = build();
    const spy = spyOn(router, 'navigate');
    component.openHost({ hostId: 'h1', name: 'Free', cumulCents: 1_430_000, tjmCents: 65_000,
      supposedCents: 0, days: 22, supposedDays: 0, estimated: false });
    expect(spy).toHaveBeenCalledWith(['/forge', 'h1']);
  });
});
