import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';

import { JarvisNotice, NOTICE_POLL_MS } from '../../jarvis/jarvis-notices';
import { NoticeAnnouncerComponent } from './notice-announcer.component';

function notice(id: string, extra: Partial<JarvisNotice> = {}): JarvisNotice {
  return {
    id, kind: 'MAILS_ON_SUBJECT', text: `2 mails sur « Alarme Fortec » (EDENRED) ${id}`, speech: 'Deux nouveaux mails.',
    link: '/atelier/w1', count: 2, hostId: 'h1', workspaceId: 'w1', updatedAt: '2026-11-07T10:00:00Z',
    expiresAt: '2026-11-10T10:00:00Z', spoken: false, ...extra,
  };
}

describe('NoticeAnnouncerComponent (F-249 / SF-249-08)', () => {
  let fixture: ComponentFixture<NoticeAnnouncerComponent>;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NoticeAnnouncerComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(NoticeAnnouncerComponent);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    // Le sondage périodique peut avoir relancé une lecture à la fin du test : seules les autres requêtes comptent.
    http.match('/api/jarvis/notices');
    http.verify();
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('montre les annonces (3 au plus), « Voir » navigue et ferme, « OK » ferme partout', fakeAsync(() => {
    const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture.detectChanges();
    tick();
    http.expectOne('/api/jarvis/notices').flush([notice('a'), notice('b'), notice('c'), notice('d')]);
    fixture.detectChanges();
    const items = (fixture.nativeElement as HTMLElement).querySelectorAll('.na__item');
    expect(items.length).toBe(3);
    expect(text()).toContain('2 mails sur « Alarme Fortec » (EDENRED) a');
    expect(text()).not.toContain('EDENRED) d');

    (items[0].querySelector('.na__open') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/jarvis/notices/a/seen' }).flush(notice('a'));
    expect(navigate).toHaveBeenCalledWith('/atelier/w1');

    fixture.detectChanges();
    ((fixture.nativeElement as HTMLElement).querySelector('.na__ok') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/jarvis/notices/b/seen' }).flush(notice('b'));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.na__item').length).toBe(1);
    discardPeriodicTasks();
  }));

  it('sonde toutes les 60 s et se tait sur une erreur', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    http.expectOne('/api/jarvis/notices').flush('boom', { status: 500, statusText: 'KO' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.na')).toBeNull();
    tick(NOTICE_POLL_MS);
    http.expectOne('/api/jarvis/notices').flush([notice('z', { kind: 'DEPOSIT_REMINDER', text: 'Déposez vos mails EDENRED', link: null })]);
    fixture.detectChanges();
    expect(text()).toContain('Déposez vos mails EDENRED');
    expect((fixture.nativeElement as HTMLElement).querySelector('.na__open')).toBeNull();
    discardPeriodicTasks();
  }));
});
