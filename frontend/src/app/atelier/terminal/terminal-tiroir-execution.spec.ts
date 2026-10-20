import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent, QRAIL_HIDE_DELAY_MS, TOP_DRAWER_INTENT_MS } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **LE RÉGLAGE D'EXÉCUTION DANS UN TIROIR D'EN-TÊTE** (F-207 / SF-207-04). La rangée « Où s'exécutent
 * les outils » (59 px) et la ligne « poste connecté » (57 px) quittent le flux : elles pendent sous
 * l'en-tête, par-dessus le fil. Un poste en difficulté reste affiché. Layout réel (Karma, 1440 px).
 */
describe('AtelierTerminalComponent — réglage d’exécution en tiroir (F-207 / SF-207-04)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let c: AtelierTerminalComponent;
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
    c = fixture.componentInstance;
    c.projectName = 'mon-projet';
    c.readOnly = false;
    c.executionTarget = 'RUNNER';
    c.hostName = 'PC-FTOUNGA';
    c.runnerStatus = { connected: true, paired: true } as never;
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  });

  afterEach(() => {
    host.remove();
    fixture.destroy();
  });

  const drawer = () => host.querySelector('.terminal-topdrawer') as HTMLElement;
  const panel = () => host.querySelector('.terminal-topdrawer__panel') as HTMLElement;
  const bar = () => host.querySelector('.terminal-bar') as HTMLElement;
  const chip = () => host.querySelector('button.engine-chip') as HTMLButtonElement;

  it('au repos, poste connecté : ni la rangée ni la ligne d’état ne prennent de hauteur (CA1)', () => {
    expect(drawer().getBoundingClientRect().height).toBe(0);
    expect(panel().querySelector('.terminal-target')).not.toBeNull();
    expect(panel().querySelector('.terminal-host-state')).not.toBeNull();
    expect(getComputedStyle(panel()).visibility).toBe('hidden');
  });

  it('poste déconnecté ou reprise disponible : la ligne d’état reste dans le flux (CA1b)', () => {
    c.runnerStatus = { connected: false, paired: true } as never;
    fixture.detectChanges();
    const row = host.querySelector('.terminal-host-state') as HTMLElement;
    expect(panel().contains(row)).toBeFalse();
    expect(row.getBoundingClientRect().height).toBeGreaterThan(0);
    expect(host.querySelector('.terminal-host-state-command')).not.toBeNull();
  });

  it('survol de l’en-tête → tiroir ouvert après le délai d’intention, refermé à la sortie (CA2)', fakeAsync(() => {
    bar().dispatchEvent(new MouseEvent('mouseenter'));
    tick(TOP_DRAWER_INTENT_MS - 50);
    fixture.detectChanges();
    expect(drawer().classList).not.toContain('terminal-topdrawer--open');
    tick(100);
    fixture.detectChanges();
    expect(drawer().classList).toContain('terminal-topdrawer--open');
    expect(drawer().getBoundingClientRect().height).toBe(0); // toujours en surimpression

    bar().dispatchEvent(new MouseEvent('mouseleave'));
    drawer().dispatchEvent(new MouseEvent('mouseenter')); // on passe de l'en-tête au tiroir
    tick(QRAIL_HIDE_DELAY_MS + 50);
    fixture.detectChanges();
    expect(drawer().classList).toContain('terminal-topdrawer--open');

    drawer().dispatchEvent(new MouseEvent('mouseleave'));
    tick(QRAIL_HIDE_DELAY_MS + 50);
    fixture.detectChanges();
    expect(drawer().classList).not.toContain('terminal-topdrawer--open');
  }));

  it('la pastille moteur ouvre et ferme le tiroir, aria-expanded suit (CA3)', () => {
    expect(chip().getAttribute('aria-expanded')).toBe('false');
    chip().click();
    fixture.detectChanges();
    expect(drawer().classList).toContain('terminal-topdrawer--open');
    expect(chip().getAttribute('aria-expanded')).toBe('true');
    chip().click();
    fixture.detectChanges();
    expect(drawer().classList).not.toContain('terminal-topdrawer--open');
  });

  it('pendant une bascule de cible, le tiroir reste ouvert (CA4)', () => {
    c.switchingTarget = true;
    fixture.detectChanges();
    expect(drawer().classList).toContain('terminal-topdrawer--open');
  });

  it('Échap referme le tiroir', () => {
    chip().click();
    fixture.detectChanges();
    drawer().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(drawer().classList).not.toContain('terminal-topdrawer--open');
  });

  it('les gestes du tiroir émettent comme avant (CA5)', () => {
    const audit = spyOn(c.openRunnerAudit, 'emit');
    const kill = spyOn(c.killRunner, 'emit');
    const refresh = spyOn(c.refreshRunner, 'emit');
    (host.querySelector('.terminal-runner-audit') as HTMLButtonElement).click();
    (host.querySelector('.terminal-runner-kill') as HTMLButtonElement).click();
    (host.querySelector('.terminal-runner-refresh') as HTMLButtonElement).click();
    expect(audit).toHaveBeenCalled();
    expect(kill).toHaveBeenCalled();
    expect(refresh).toHaveBeenCalled();
  });
});
