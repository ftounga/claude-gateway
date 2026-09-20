import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ForgeCostAlert } from '../../core/services/cost-alerts.service';
import { ForgeCostAlertComponent } from './forge-cost-alert.component';

/**
 * Le bandeau d'alerte de dépense dans la Forge (F-133 / SF-133-12).
 *
 * <p>Ce qui compte ici : le bandeau ne s'affiche que s'il y a quelque chose à dire, il se masque
 * pour la session sans disparaître pour toujours, et <b>il n'écrit jamais de montant</b> à qui n'en
 * a pas reçu — la passerelle les retire, l'écran ne doit pas les réinventer.</p>
 */
describe('ForgeCostAlertComponent', () => {
  let fixture: ComponentFixture<ForgeCostAlertComponent>;
  let http: HttpTestingController;

  const URL = '/api/cost/alerts/mine';

  function alert(overrides: Partial<ForgeCostAlert> = {}): ForgeCostAlert {
    return {
      scope: 'HOST',
      hostId: 'h-1',
      hostName: 'CAGIP',
      spentEur: 12,
      budgetEur: 10,
      percent: 120,
      level: 'EXCEEDED',
      weekStart: '2026-09-14',
      ...overrides,
    };
  }

  function setup(alerts: ForgeCostAlert[]): void {
    fixture.detectChanges();
    http.expectOne(URL).flush(alerts);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function banner(): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('.cost-alert');
  }

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [ForgeCostAlertComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(ForgeCostAlertComponent);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('écrit le dépassement dans la Forge', () => {
    setup([alert()]);

    expect(banner()).not.toBeNull();
    expect(text()).toContain('CAGIP a dépassé son budget hebdomadaire');
    expect(text()).toContain('120 % du budget');
  });

  it("n'affiche rien quand aucun budget n'est dépassé", () => {
    setup([]);

    expect(banner()).toBeNull();
  });

  it("n'affiche rien et ne casse pas l'écran quand la gateway échoue", () => {
    // Une alerte est un confort : elle ne doit jamais abîmer l'écran de travail, ni y écrire un
    // message d'échec que personne ne peut traiter.
    fixture.detectChanges();
    http.expectOne(URL).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(banner()).toBeNull();
  });

  it("n'écrit aucun montant à qui n'en a pas reçu", () => {
    // Le consultant reçoit `spentEur: null` — la part reste, l'argent disparaît.
    setup([alert({ spentEur: null, budgetEur: null })]);

    expect(text()).toContain('120 % du budget');
    expect(text()).not.toContain('€');
  });

  it("écrit les montants à l'administrateur", () => {
    setup([alert({ spentEur: 12, budgetEur: 10 })]);

    expect(text()).toContain('12,00 € sur 10,00 €');
  });

  it('se masque au clic, et reste masqué pour la session', () => {
    setup([alert()]);

    (banner()!.querySelector('.cost-alert__dismiss') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(banner()).toBeNull();

    // Un nouvel affichage dans la même session (changement d'écran, retour) ne le ramène pas…
    const second = TestBed.createComponent(ForgeCostAlertComponent);
    second.detectChanges();
    http.expectOne(URL).flush([alert()]);
    second.detectChanges();

    expect((second.nativeElement as HTMLElement).querySelector('.cost-alert')).toBeNull();
  });

  it('revient quand la situation change, même masqué', () => {
    // Masquer « on approche » ne doit pas masquer « on a dépassé » : ce n'est plus la même alerte.
    setup([alert({ level: 'NEAR', percent: 85 })]);
    (banner()!.querySelector('.cost-alert__dismiss') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(banner()).toBeNull();

    const second = TestBed.createComponent(ForgeCostAlertComponent);
    second.detectChanges();
    http.expectOne(URL).flush([alert({ level: 'EXCEEDED', percent: 120 })]);
    second.detectChanges();

    expect((second.nativeElement as HTMLElement).querySelector('.cost-alert')).not.toBeNull();
  });

  it("distingue l'approche du dépassement à l'œil", () => {
    setup([alert({ level: 'NEAR', percent: 85 })]);

    expect(banner()!.classList).not.toContain('cost-alert--exceeded');
    expect(text()).toContain('CAGIP approche de son budget hebdomadaire');
  });

  it('nomme le budget global quand il déborde', () => {
    setup([alert({ scope: 'TOTAL', hostId: null, hostName: null })]);

    expect(text()).toContain('Le budget hebdomadaire est dépassé');
  });
});
