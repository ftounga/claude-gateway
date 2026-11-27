import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { formatEuros } from '../euros';

import { ProviderGaugesComponent } from './provider-gauges.component';
import { PROVIDER_SPEND_FRESH_MS, ProviderSpendService } from '../../core/services/provider-spend.service';
import { providerSpend } from './provider-spend.testing';

describe('ProviderGaugesComponent (F-279 / SF-279-02)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProviderGaugesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(): HTMLElement {
    const fixture = TestBed.createComponent(ProviderGaugesComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('montre les deux montants en euros, le canal en service et le crédit épuisé', () => {
    TestBed.inject(ProviderSpendService).load();
    http.expectOne('/api/admin/cost/providers').flush(providerSpend());
    const el = render();
    const text = el.textContent ?? '';
    expect(text).toContain('Anthropic');
    expect(text).toContain(formatEuros(1060.12));
    expect(text).toContain('Bedrock');
    expect(text).toContain('66,40 €');
    expect(text).toContain('crédit épuisé');
    const active = el.querySelector('.pots__pot--active');
    expect(active?.textContent).toContain('Bedrock');
    expect(el.querySelector('.pots__dot--bedrock')).not.toBeNull();
    const title = el.querySelector('.pots')?.getAttribute('title') ?? '';
    expect(title).toContain('hors TVA');
    expect(title).toContain('recalé sur la facture jusqu’au 25/11');
    expect(title).toContain('En service : AWS Bedrock — crédit Anthropic épuisé');
    // La barre bicolore : la part d'Anthropic dans le total (1 060,12 / 1 126,52 ≈ 94 %).
    const part = el.querySelector('.pots__part--anthropic') as HTMLElement;
    expect(part.style.width).toBe('94%');
  });

  it('dit « relais » sans cause connue, rien quand Anthropic sert', () => {
    const service = TestBed.inject(ProviderSpendService);
    service.spend.set(providerSpend({ creditExhausted: false, cause: null, circuitOpen: false }));
    expect(render().textContent).toContain('relais');

    service.spend.set(providerSpend({ current: 'ANTHROPIC', relay: false, creditExhausted: false }));
    const el = render();
    expect(el.textContent).not.toContain('relais');
    expect(el.textContent).not.toContain('crédit épuisé');
    expect(el.querySelector('.pots__pot--active')?.textContent).toContain('Anthropic');
  });

  it("n'affiche rien sans droit (403) ni sur erreur, et ne relit pas avant 5 minutes", () => {
    const service = TestBed.inject(ProviderSpendService);
    service.load(1_000);
    http.expectOne('/api/admin/cost/providers').flush('interdit', { status: 403, statusText: 'Forbidden' });
    expect(render().querySelector('.pots')).toBeNull();

    service.load(2_000);
    http.expectNone('/api/admin/cost/providers');
    service.load(1_000 + PROVIDER_SPEND_FRESH_MS);
    http.expectOne('/api/admin/cost/providers').flush('panne', { status: 500, statusText: 'Error' });
    expect(render().querySelector('.pots')).toBeNull();
  });
});
