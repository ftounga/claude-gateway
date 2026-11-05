import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { JarvisStatePanelComponent } from './jarvis-state-panel.component';
import { StateClient } from './jarvis-state';

/** F-247 / SF-247-15 — le volet « Ce que je sais ». */
describe('JarvisStatePanelComponent (F-247 / SF-247-15)', () => {
  let http: HttpTestingController;
  let confirm = true;

  const client = (over: Partial<StateClient> = {}): StateClient => ({
    hostId: 'h1', name: 'CAGIP', enabled: true, disabledFamilies: [], extractFromTurns: true, notesEnabled: true,
    releveEnabled: true, capturesEnabled: true, factRetentionDays: 30, captureRetentionDays: 90, facts: 2, ...over,
  });

  beforeEach(() => {
    confirm = true;
    TestBed.configureTestingModule({
      imports: [JarvisStatePanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(confirm) }) } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render() {
    const fixture = TestBed.createComponent(JarvisStatePanelComponent);
    fixture.detectChanges();
    http.expectOne('/api/jarvis/state/clients').flush([client()]);
    fixture.detectChanges();
    return fixture;
  }

  it('montre chaque client, activé d’office, et ses faits à la demande', () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.js__name')?.textContent).toContain('CAGIP');
    expect(el.textContent).toContain('2 faits');
    (el.querySelector('.js__see') as HTMLButtonElement).click();
    http.expectOne('/api/jarvis/state/clients/h1/facts').flush([{ id: 'f1', objectKind: 'TICKET',
      objectKey: 'JIRA:PROJ-1', attribute: 'status', value: 'En revue', previousValue: null,
      observedAt: '2026-11-04T16:42:00Z', source: 'TOUR', confidence: 'HAUTE' }]);
    fixture.detectChanges();
    expect(el.querySelector('.js__key')?.textContent).toContain('JIRA:PROJ-1');
    expect(el.textContent).toContain('tour');
  });

  it('couper demande confirmation puis enregistre « enabled: false »', () => {
    const fixture = render();
    fixture.componentInstance.toggleEnabled(client(), false);
    const req = http.expectOne('/api/jarvis/state/clients/h1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush(client({ enabled: false, facts: 0 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.js__sources')).toBeNull();
  });

  it('couper refusé : rien n’est envoyé ; une famille se coupe d’un clic', () => {
    const fixture = render();
    confirm = false;
    fixture.componentInstance.toggleEnabled(client(), false);
    http.expectNone('/api/jarvis/state/clients/h1');
    fixture.componentInstance.toggleFamily(client(), 'MR');
    const req = http.expectOne('/api/jarvis/state/clients/h1');
    expect(req.request.body).toEqual({ disabledFamilies: ['MR'] });
    req.flush(client({ disabledFamilies: ['MR'] }));
  });

  it('effacer tout appelle DELETE après confirmation', () => {
    const fixture = render();
    fixture.componentInstance.eraseAll(client());
    const req = http.expectOne('/api/jarvis/state/clients/h1/facts');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
