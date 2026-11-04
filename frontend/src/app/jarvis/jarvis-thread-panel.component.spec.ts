import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { JarvisThreadItem, JarvisThreadService } from '../core/services/jarvis-thread.service';
import { JarvisThreadPanelComponent, linkQueryOf, speakerOf } from './jarvis-thread-panel.component';

function item(over: Partial<JarvisThreadItem>): JarvisThreadItem {
  return { id: 'i', role: 'USER', text: 'Bonjour', at: '2026-11-04T08:05:00Z', fast: false, link: null, ...over };
}

describe('JarvisThreadPanelComponent (F-245 / SF-245-04)', () => {
  let threads: jasmine.SpyObj<JarvisThreadService>;

  function create(): HTMLElement {
    const fixture = TestBed.createComponent(JarvisThreadPanelComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    threads = jasmine.createSpyObj<JarvisThreadService>('JarvisThreadService', ['today', 'open']);
    TestBed.configureTestingModule({
      imports: [JarvisThreadPanelComponent],
      providers: [provideRouter([]), { provide: JarvisThreadService, useValue: threads }],
    });
  });

  it('liste les échanges du jour et les liens, dans l’ordre ; un lien ouvre le chantier à la réponse', () => {
    threads.today.and.returnValue(of([
      item({ id: 'a', role: 'USER', text: 'Bonjour', fast: true }),
      item({ id: 'b', role: 'ASSISTANT', text: 'Bonjour Monsieur.', fast: true }),
      item({ id: 'c', role: 'ASSISTANT', text: 'Trois clients vont bien.' }),
      item({
        id: 'd', role: 'LINK', text: '↗ Helios · chantier 3 — « relancer » · fait\n🎙 Relancé.',
        link: { workspaceId: 'w-helios', chantier: 3, messageId: 'm4', status: 'FAIT' },
      }),
    ]));
    const el = create();

    const rows = Array.from(el.querySelectorAll('.jt__item')).map((r) => r.textContent ?? '');
    expect(rows.length).toBe(4);
    expect(rows[0]).toContain('Vous');
    expect(rows[1]).toContain('Jarvis');
    expect(rows[2]).toContain('Opus');
    const link = el.querySelector('a.jt__link') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/atelier/w-helios?chantier=3&message=m4');
    expect(link.textContent).toContain('↗ Helios · chantier 3 — « relancer » · fait — 🎙 Relancé.');
  });

  it('dit qu’il n’y a rien encore, ou que le fil est illisible', () => {
    threads.today.and.returnValue(of([]));
    expect(create().textContent).toContain('Rien encore aujourd’hui.');

    threads.today.and.returnValue(throwError(() => new Error('panne')));
    expect(create().textContent).toContain('Fil illisible');
  });

  it('qui parle, et l’adresse d’un lien sans chantier ni réponse', () => {
    expect(speakerOf(item({ role: 'USER' }))).toBe('Vous');
    expect(speakerOf(item({ role: 'ASSISTANT', fast: true }))).toBe('Jarvis');
    expect(speakerOf(item({ role: 'ASSISTANT', fast: false }))).toBe('Opus');
    expect(linkQueryOf({ workspaceId: 'w', chantier: null, messageId: null, status: 'EN_COURS' })).toEqual({});
  });
});
