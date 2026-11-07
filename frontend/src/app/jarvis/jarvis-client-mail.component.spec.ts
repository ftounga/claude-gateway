import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ClientMailOverview } from './jarvis-client-mail';
import { JarvisClientMailComponent } from './jarvis-client-mail.component';

function overview(extra: Partial<ClientMailOverview> = {}): ClientMailOverview {
  return {
    settings: { reminderTime: '18:30', reminderMax: 3, reminderEveryMinutes: 45, weekdaysOnly: true, meetingAlertMinutes: 10 },
    clients: [
      { hostId: 'h1', name: 'EDENRED', active: true, upToDate: true, folder: 'dev/jarvis/mails/',
        lastDepositAt: '2026-11-06T17:00:00Z', mails: 12, depositedToday: false },
      { hostId: 'h2', name: 'CAGIP', active: false, upToDate: false, folder: 'work/jarvis/mails/',
        lastDepositAt: null, mails: 0, depositedToday: false },
    ],
    offers: [{ id: 'o1', hostId: 'h1', kind: 'SENDER', sample: 'alertes@monitoring.edenred.com', occurrences: 4 }],
    rules: [{ id: 'r1', hostId: 'h1', kind: 'SUBJECT', sample: '[PROD] Job 1234 KO' }],
    ...extra,
  };
}

describe('JarvisClientMailComponent (F-249 / SF-249-07)', () => {
  let fixture: ComponentFixture<JarvisClientMailComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [JarvisClientMailComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(JarvisClientMailComponent);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function load(o = overview()): void {
    fixture.detectChanges();
    http.expectOne('/api/jarvis/client-mail').flush(o);
    fixture.detectChanges();
  }

  it('liste les postes, leur dossier, l’état du runner, les propositions et les règles', () => {
    load();
    const text = el().textContent ?? '';
    expect(text).toContain('EDENRED');
    expect(text).toContain('dev/jarvis/mails/');
    expect(text).toContain('runner à jour');
    expect(text).toContain('runner à mettre à jour');
    expect(text).toContain('Expéditeur répétitif (4) : alertes@monitoring.edenred.com');
    expect(text).toContain('Ignoré — Objet : [PROD] Job 1234 KO');
  });

  it('Ignorer, Garder, Retirer appellent les bonnes routes', () => {
    load();
    (el().querySelector('.jc__ignore') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/jarvis/client-mail/offers/o1/accept' })
      .flush({ id: 'r2', hostId: 'h1', kind: 'SENDER', sample: 'alertes@monitoring.edenred.com' });
    fixture.detectChanges();
    expect(el().querySelectorAll('.jc__rule').length).toBe(2);
    (el().querySelector('.jc__remove') as HTMLButtonElement).click();
    http.expectOne({ method: 'DELETE', url: '/api/jarvis/client-mail/rules/r2' }).flush(null);
    fixture.detectChanges();
    expect(el().querySelectorAll('.jc__rule').length).toBe(1);
  });

  it('Garder retire la proposition', () => {
    load();
    (el().querySelector('.jc__keep') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/jarvis/client-mail/offers/o1/decline' }).flush(null);
    fixture.detectChanges();
    expect(el().querySelector('.jc__offer')).toBeNull();
  });

  it('l’interrupteur du rappel règle le poste', () => {
    load();
    fixture.componentInstance.setActive(overview().clients[1], true);
    http.expectOne({ method: 'PUT', url: '/api/jarvis/client-mail/hosts/h2' }).flush({ ...overview().clients[1], active: true });
    expect(fixture.componentInstance.clients()[1].active).toBeTrue();
  });

  it('enregistre les réglages ; un refus affiche le message de la passerelle', () => {
    load();
    fixture.componentInstance.save();
    const req = http.expectOne({ method: 'PUT', url: '/api/jarvis/client-mail/settings' });
    expect(req.request.body.reminderMax).toBe(3);
    req.flush({ message: 'De 1 à 3 rappels.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(el().textContent).toContain('De 1 à 3 rappels.');
  });

  it('sans poste : l’invite à appairer', () => {
    load(overview({ clients: [], offers: [], rules: [] }));
    expect(el().textContent).toContain('Aucun poste');
  });
});
