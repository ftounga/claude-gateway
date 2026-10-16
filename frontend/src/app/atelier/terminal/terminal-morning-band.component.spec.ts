import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { TerminalMorningBandComponent, localDay, morningDismissKey } from './terminal-morning-band.component';
import { MorningPoint, TerminalAction, dueLabel, morningSummary } from '../../core/models/terminal-actions.models';
import { PushActivationService } from '../../core/services/push-activation.service';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { mockTerminalActionsService } from '../../core/services/terminal-actions.service.testing';

function action(id: string): TerminalAction {
  return { id, workspaceId: 'w-1', subjectId: null, description: id, blocks: null, person: null, kind: 'ACTION',
    status: 'DEMANDE', closedReason: null, closedAt: null, createdAt: '2026-10-01T08:00:00Z' };
}

const POINT: MorningPoint = {
  followUps: [action('a-1'), action('a-2')],
  dueToday: [],
  overdue: [action('a-3')],
  accesExpiring: [{ id: 'r-1', system: 'VPN', account: null, expiresAt: '2026-10-20', expired: false }],
};

/** Le point du matin (F-180 / SF-180-06). */
describe('Le point du matin (F-180 / SF-180-06)', () => {
  let fixture: ComponentFixture<TerminalMorningBandComponent>;
  let service: jasmine.SpyObj<TerminalActionsService>;
  let push: { supported: boolean; enabled: ReturnType<typeof signal<boolean>>; permission: jasmine.Spy; enable: jasmine.Spy };

  function build(point: MorningPoint, pushState = { supported: true, enabled: false }): void {
    service = mockTerminalActionsService();
    service.morning.and.returnValue(of(point));
    push = {
      supported: pushState.supported,
      enabled: signal(pushState.enabled),
      permission: jasmine.createSpy('permission').and.returnValue('default'),
      enable: jasmine.createSpy('enable').and.resolveTo('enabled'),
    };
    TestBed.configureTestingModule({
      imports: [TerminalMorningBandComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: TerminalActionsService, useValue: service },
        { provide: PushActivationService, useValue: push },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']) }],
    });
    fixture = TestBed.createComponent(TerminalMorningBandComponent);
    fixture.componentRef.setInput('workspaceId', 'w-morning');
    fixture.detectChanges();
  }

  beforeEach(() => {
    try {
      localStorage.removeItem(morningDismissKey('w-morning', localDay()));
    } catch {
      // sans mémoire locale
    }
  });

  afterEach(() => TestBed.resetTestingModule());

  it('résume relances dues, échéances dépassées et accès qui expirent', () => {
    const summary = morningSummary(POINT, new Date('2026-10-16T10:00:00'));
    expect(summary).toBe('2 relances dues · 1 échéance dépassée · VPN expire dans 4 j');
  });

  it('s’affiche, [Voir] ouvre le panneau, [×] la masque pour la journée', () => {
    build(POINT);
    const band: HTMLElement = fixture.nativeElement.querySelector('.morning');
    expect(band.textContent).toContain('Point du matin');
    const opened: void[] = [];
    fixture.componentInstance.open.subscribe(() => opened.push(undefined));
    (band.querySelector('.morning__open') as HTMLButtonElement).click();
    expect(opened.length).toBe(1);

    (band.querySelector('.morning__hide') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.morning')).toBeNull();
  });

  it('rien à dire : rien d’affiché', () => {
    build({ followUps: [], dueToday: [], overdue: [], accesExpiring: [] });
    expect(fixture.nativeElement.querySelector('.morning')).toBeNull();
  });

  it('sans abonnement push : invitation, qui active sur le geste', async () => {
    build(POINT);
    const invite = fixture.nativeElement.querySelector('.morning__push') as HTMLButtonElement;
    expect(invite).not.toBeNull();
    invite.click();
    await fixture.whenStable();
    expect(push.enable).toHaveBeenCalled();
  });

  it('déjà abonné ou non supporté : pas d’invitation', () => {
    build(POINT, { supported: true, enabled: true });
    expect(fixture.nativeElement.querySelector('.morning__push')).toBeNull();
  });

  it('pastille d’échéance : dépassée, aujourd’hui, à venir', () => {
    expect(dueLabel({ dueAt: '2026-10-15', dueState: 'OVERDUE' })).toBe('échéance dépassée (15/10)');
    expect(dueLabel({ dueAt: '2026-10-16', dueState: 'TODAY' })).toBe("échéance aujourd'hui");
    expect(dueLabel({ dueAt: '2026-10-20', dueState: 'LATER' })).toBe('échéance 20/10');
    expect(dueLabel({ dueAt: null, dueState: 'NONE' })).toBe('');
  });
});
