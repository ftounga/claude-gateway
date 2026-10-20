import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent, QRAIL_EDGE_MAX, QRAIL_HIDE_DELAY_MS } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **LE RAIL « VOS QUESTIONS » EN LISIÈRE** (F-207 / SF-207-02). Le rail rendait 232 px de largeur au
 * fil en permanence ; il se replie en une lisière de 32 px et s'ouvre par-dessus le fil au survol.
 * Mesures de layout réelles (ChromeHeadless, fenêtre Karma de 1440 px).
 */
describe('AtelierTerminalComponent — rail « Vos questions » en lisière (F-207 / SF-207-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let host: HTMLElement;

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({}) as never);

    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AtelierTerminalComponent);
    fixture.componentInstance.projectName = 'mon-projet';
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  });

  afterEach(() => {
    host.remove();
    fixture.destroy();
  });

  function renderQuestions(count: number): void {
    const c = fixture.componentInstance;
    c.readOnly = false;
    const messages = [];
    for (let i = 1; i <= count; i++) {
      messages.push({ id: `u${i}`, role: 'USER', content: `Question numéro ${i}`, actions: [] });
      messages.push({ id: `a${i}`, role: 'ASSISTANT', content: `réponse ${i}`, actions: [], terminal: [] });
    }
    c.messages = messages as never;
    fixture.detectChanges();
  }

  const rail = () => host.querySelector('.terminal-qrail') as HTMLElement;
  const panel = () => host.querySelector('.terminal-qrail__panel') as HTMLElement;
  const thread = () => host.querySelector('.terminal-thread') as HTMLElement;

  it('le fil ne cède plus que la lisière : 32 px au lieu de 232 (CA1)', () => {
    renderQuestions(4);
    const sb = host.querySelector('.terminal-scrollback') as HTMLElement;
    const tracks = getComputedStyle(sb).gridTemplateColumns.trim().split(/\s+/);
    expect(tracks[1]).toBe('32px');
    expect(rail().getBoundingClientRect().width).toBeLessThanOrEqual(32);
    expect(host.querySelectorAll('.terminal-qrail__edge-n').length).toBe(4);
  });

  it('fermé, le rail complet est invisible ; au survol, il apparaît par-dessus sans changer la largeur du fil (CA2)', fakeAsync(() => {
    renderQuestions(4);
    expect(getComputedStyle(panel()).visibility).toBe('hidden');
    const before = thread().getBoundingClientRect().width;

    rail().dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(rail().classList).toContain('terminal-qrail--open');
    expect(getComputedStyle(panel()).position).toBe('absolute');
    expect(thread().getBoundingClientRect().width).toBe(before);

    rail().dispatchEvent(new MouseEvent('mouseleave'));
    tick(QRAIL_HIDE_DELAY_MS - 50);
    fixture.detectChanges();
    expect(rail().classList).toContain('terminal-qrail--open');
    tick(100);
    fixture.detectChanges();
    expect(rail().classList).not.toContain('terminal-qrail--open');
  }));

  it('s’ouvre au focus clavier et se referme à Échap (CA2)', () => {
    renderQuestions(3);
    rail().dispatchEvent(new FocusEvent('focusin'));
    fixture.detectChanges();
    expect(rail().classList).toContain('terminal-qrail--open');
    rail().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(rail().classList).not.toContain('terminal-qrail--open');
  });

  it('une pastille de la lisière saute à sa question (CA3)', () => {
    renderQuestions(3);
    const spy = spyOn(fixture.componentInstance, 'scrollToQuestion');
    const second = host.querySelectorAll<HTMLButtonElement>('.terminal-qrail__edge-n')[1];
    second.click();
    expect(spy).toHaveBeenCalledWith(fixture.componentInstance.userQuestions[1].anchorId);
  });

  it(`au-delà de ${QRAIL_EDGE_MAX} questions, la lisière montre les dernières et « +N » (CA4)`, () => {
    renderQuestions(QRAIL_EDGE_MAX + 3);
    const pills = Array.from(host.querySelectorAll('.terminal-qrail__edge-n')).map((b) => b.textContent?.trim());
    expect(pills.length).toBe(QRAIL_EDGE_MAX);
    expect(pills[0]).toBe('4');
    expect(host.querySelector('.terminal-qrail__more')?.textContent?.trim()).toBe('+3');
    // Le rail ouvert, lui, montre tout.
    expect(host.querySelectorAll('.terminal-qrail__item').length).toBe(QRAIL_EDGE_MAX + 3);
  });

  it('lecture seule : ni lisière ni rail (CA5)', () => {
    renderQuestions(2);
    fixture.componentInstance.readOnly = true;
    fixture.detectChanges();
    expect(host.querySelector('.terminal-qrail')).toBeNull();
  });
});
