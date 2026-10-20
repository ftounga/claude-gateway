import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent, QRAIL_HIDE_DELAY_MS } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';
import { SubjectJourney } from '../../core/models/journey.models';
import { TerminalActionBoard } from '../../core/models/terminal-actions.models';
import { DOCK_HIDDEN_KEY, attentesChip, journeyAwaitsGesture, journeyChip, planChip } from './terminal-dock';

/**
 * **UNE SEULE BARRE D'ÉTAT EN BAS DU TERMINAL** (F-207 / SF-207-03). Les bandeaux empilés au-dessus de
 * la saisie (parcours, plan reporté, point du matin, attentes) se résument en puces ; leur contenu
 * s'ouvre en tiroir par-dessus le fil ; une décision attendue reste dans le flux. Layout réel (1440 px).
 */
describe('AtelierTerminalComponent — barre d’état du bas (F-207 / SF-207-03)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let c: AtelierTerminalComponent;
  let host: HTMLElement;

  const guided = (extra: Partial<SubjectJourney> = {}): SubjectJourney => ({
    mode: 'GUIDE',
    phase: 'EXECUTION',
    phaseLabel: 'Exécution',
    phaseChangedAt: '2026-10-20T10:00:00Z',
    plan: { version: 2, validatedVersion: 2, validatedAt: null, awaitingValidation: false, amendment: false, waitingInputs: 0, steps: [] },
    chantier: { number: 1 } as never,
    chantiers: [],
    ...extra,
  }) as SubjectJourney;

  const board = (extra: Partial<TerminalActionBoard> = {}): TerminalActionBoard => ({
    hostId: 'h1', here: [], host: [], aFaire: 2, demande: 2, aRelancer: 2, oldestOpenAt: null, ...extra,
  });

  beforeEach(async () => {
    localStorage.removeItem(DOCK_HIDDEN_KEY);
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
    c = fixture.componentInstance;
    c.projectName = 'mon-projet';
    c.readOnly = false;
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  });

  afterEach(() => {
    localStorage.removeItem(DOCK_HIDDEN_KEY);
    host.remove();
    fixture.destroy();
  });

  const dock = () => host.querySelector('.terminal-dock') as HTMLElement;
  const drawer = () => host.querySelector('.terminal-dock__drawer') as HTMLElement;
  const bar = () => host.querySelector('.terminal-dock__bar') as HTMLElement | null;
  const chips = () => Array.from(host.querySelectorAll<HTMLElement>('.terminal-dock__chip'));

  // ---- fonctions pures
  it('puces : parcours guidé, chantier proposé, plan, attentes (pures)', () => {
    expect(journeyChip(null)).toBeNull();
    expect(journeyChip({ mode: 'LIBRE', phase: null } as never)).toBeNull();
    expect(journeyChip(guided())?.label).toBe('Guidé · Exécution');
    expect(journeyChip(guided({ chantiers: [{} as never, {} as never] }))?.label).toBe('Chantier 1 · Exécution · 2 chantiers');
    expect(journeyChip({ mode: 'LIBRE', phase: null, guidedProposal: { reason: null, proposedAt: 'x' } } as never))
      .toEqual(jasmine.objectContaining({ label: 'Chantier proposé', await: true }));
    expect(planChip([{ title: 'a', status: 'done' }, { title: 'b', status: 'active' }], false)?.label).toBe('Plan · 1/2');
    expect(planChip([{ title: 'a', status: 'done' }], true)).toBeNull();
    expect(attentesChip(board(), 0)).toEqual(jasmine.objectContaining({ label: 'Attentes · 4 · 2 à relancer', warn: true }));
    expect(attentesChip(board({ aFaire: 0, demande: 0, aRelancer: 0 }), 0)).toBeNull();
  });

  it('décision attendue : plan à valider, diagnostic, clôture, proposition (pure)', () => {
    expect(journeyAwaitsGesture(guided())).toBeFalse();
    expect(journeyAwaitsGesture(guided({ plan: { ...guided().plan!, awaitingValidation: true } }))).toBeTrue();
    expect(journeyAwaitsGesture(guided({ phase: 'INVESTIGATION', diagnosis: { pending: true } as never }))).toBeTrue();
    expect(journeyAwaitsGesture(guided({ closeProposed: true }))).toBeTrue();
  });

  // ---- rendu
  it('sans rien à dire : ni barre ni hauteur (CA1)', () => {
    expect(bar()).toBeNull();
    expect(dock().getBoundingClientRect().height).toBe(0);
  });

  it('parcours + attentes : une seule barre fine, les bandeaux d’origine repliés (CA1)', () => {
    c.journey.set(guided());
    c.attentesBoard.set(board());
    fixture.detectChanges();
    expect(chips().map((chip) => chip.dataset['kind'])).toEqual(['chantier', 'attentes']);
    expect(bar()!.getBoundingClientRect().height).toBeLessThanOrEqual(32);
    expect(dock().getBoundingClientRect().height).toBeLessThanOrEqual(32);
    expect(drawer().querySelector('app-terminal-journey-strip')).not.toBeNull();
    expect(drawer().querySelector('app-terminal-attentes-band')).not.toBeNull();
    expect(getComputedStyle(drawer()).visibility).toBe('hidden');
    expect(getComputedStyle(drawer()).position).toBe('absolute');
  });

  it('survol → tiroir ouvert par-dessus, la hauteur du fil ne bouge pas ; repli différé (CA2)', fakeAsync(() => {
    c.journey.set(guided());
    fixture.detectChanges();
    const sb = host.querySelector('.terminal-scrollback') as HTMLElement;
    const before = sb.getBoundingClientRect().height;
    dock().dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(dock().classList).toContain('terminal-dock--open');
    expect(sb.getBoundingClientRect().height).toBe(before);
    dock().dispatchEvent(new MouseEvent('mouseleave'));
    tick(QRAIL_HIDE_DELAY_MS + 50);
    fixture.detectChanges();
    expect(dock().classList).not.toContain('terminal-dock--open');
  }));

  it('clic sur une puce : bascule, Échap referme (CA2)', () => {
    c.attentesBoard.set(board());
    fixture.detectChanges();
    chips()[0].click();
    fixture.detectChanges();
    expect(dock().classList).toContain('terminal-dock--open');
    expect(chips()[0].getAttribute('aria-expanded')).toBe('true');
    dock().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(dock().classList).not.toContain('terminal-dock--open');
  });

  it('plan à valider : la bande du parcours reste dans le flux, puce « à vous » (CA3)', () => {
    c.journey.set(guided({ plan: { ...guided().plan!, awaitingValidation: true } }));
    fixture.detectChanges();
    const decision = host.querySelector('.terminal-dock__decision app-terminal-journey-strip') as HTMLElement;
    expect(decision).not.toBeNull();
    expect(decision.getBoundingClientRect().height).toBeGreaterThan(0);
    expect(drawer().querySelector('app-terminal-journey-strip')).toBeNull();
    expect(host.querySelector('.terminal-dock__chip--await .terminal-dock__await')?.textContent?.trim()).toBe('à vous');
  });

  it('retirer « Attentes » : puce et bandeau disparaissent, préférence gardée (CA4)', () => {
    c.journey.set(guided());
    c.attentesBoard.set(board());
    fixture.detectChanges();
    c.toggleDockItem('attentes');
    fixture.detectChanges();
    expect(chips().map((chip) => chip.dataset['kind'])).toEqual(['chantier']);
    expect(drawer().querySelector('app-terminal-attentes-band')).toBeNull();
    expect(JSON.parse(localStorage.getItem(DOCK_HIDDEN_KEY)!)).toEqual(['attentes']);
    c.toggleDockItem('attentes');
    expect(localStorage.getItem(DOCK_HIDDEN_KEY)).toBeNull();
  });

  it('une décision attendue passe outre une puce « Chantier » retirée (CA4)', () => {
    c.toggleDockItem('chantier');
    c.journey.set(guided({ closeProposed: true }));
    fixture.detectChanges();
    expect(host.querySelector('.terminal-dock__decision app-terminal-journey-strip')).not.toBeNull();
    expect(chips().map((chip) => chip.dataset['kind'])).toContain('chantier');
  });

  it('toutes les puces retirées : la barre reste pour qu’on les remette, sans puce', () => {
    c.attentesBoard.set(board());
    c.toggleDockItem('attentes');
    fixture.detectChanges();
    expect(bar()).not.toBeNull();
    expect(chips().length).toBe(0);
    expect(host.querySelector('.terminal-dock__menu')).not.toBeNull();
  });

  it('le sélecteur de mode est dans la ligne de saisie, icônes seules en desktop (CA5)', () => {
    const form = host.querySelector('form.terminal-input') as HTMLElement;
    const group = form.querySelector('.terminal-mode-toggle');
    expect(group).not.toBeNull();
    const text = form.querySelector('.terminal-mode-text') as HTMLElement;
    expect(getComputedStyle(text).display).toBe('none');
    c.planAwaitingApproval = true;
    fixture.detectChanges();
    const seen: number[] = [];
    c.switchToAct.subscribe(() => seen.push(1));
    (form.querySelector('.terminal-mode-exec') as HTMLButtonElement).click();
    expect(seen.length).toBe(1);
  });

  it('le fil des tâches reste hors du tiroir (CA6)', () => {
    c.journey.set(guided());
    fixture.detectChanges();
    const fil = host.querySelector('app-terminal-fil') as HTMLElement;
    expect(fil).not.toBeNull();
    expect(drawer().contains(fil)).toBeFalse();
  });

  it('lecture seule : ni barre ni mode', () => {
    c.journey.set(guided());
    c.readOnly = true;
    fixture.detectChanges();
    expect(host.querySelector('.terminal-dock')).toBeNull();
    expect(host.querySelector('.terminal-mode-toggle')).toBeNull();
  });
});
