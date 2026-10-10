import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { signal } from '@angular/core';

import { PushActivationResult, PushActivationService } from '../../core/services/push-activation.service';
import {
  CONFIRMATION_MS,
  LONG_TURN_MS,
  PUSH_INVITE_NEVER_KEY,
  TerminalPushInviteComponent,
} from './terminal-push-invite.component';

describe('TerminalPushInviteComponent (F-185 / SF-185-01)', () => {
  let fixture: ComponentFixture<TerminalPushInviteComponent>;
  let push: {
    supported: boolean;
    enabled: ReturnType<typeof signal<boolean>>;
    enable: jasmine.Spy;
    permission: jasmine.Spy;
  };

  function build(options: {
    supported?: boolean;
    enabled?: boolean;
    permission?: NotificationPermission;
    result?: PushActivationResult;
  } = {}): void {
    push = {
      supported: options.supported ?? true,
      enabled: signal(options.enabled ?? false),
      enable: jasmine.createSpy('enable').and.callFake(async () => {
        const result = options.result ?? 'enabled';
        if (result === 'enabled') {
          push.enabled.set(true);
        }
        return result;
      }),
      permission: jasmine.createSpy('permission').and.returnValue(options.permission ?? 'default'),
    };
    TestBed.configureTestingModule({
      imports: [TerminalPushInviteComponent],
      providers: [{ provide: PushActivationService, useValue: push }],
    });
    fixture = TestBed.createComponent(TerminalPushInviteComponent);
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function click(selector: string): void {
    (el().querySelector(selector) as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  beforeEach(() => localStorage.removeItem(PUSH_INVITE_NEVER_KEY));
  afterEach(() => localStorage.removeItem(PUSH_INVITE_NEVER_KEY));

  it('propose l\'activation quand le push est supporté et l\'appareil non abonné', () => {
    build();
    expect(el().querySelector('.push-invite__enable')).not.toBeNull();
    expect(el().textContent).toContain('Soyez prévenu');
  });

  it('ne propose rien si non supporté, déjà abonné, bloqué ou écarté', () => {
    build({ supported: false });
    expect(el().querySelector('.push-invite')).toBeNull();
    TestBed.resetTestingModule();
    build({ enabled: true });
    expect(el().querySelector('.push-invite')).toBeNull();
    TestBed.resetTestingModule();
    build({ permission: 'denied' });
    expect(el().querySelector('.push-invite')).toBeNull();
    TestBed.resetTestingModule();
    localStorage.setItem(PUSH_INVITE_NEVER_KEY, '1');
    build();
    expect(el().querySelector('.push-invite')).toBeNull();
  });

  it('« Activer » : confirme sur cet appareil, puis s\'efface', fakeAsync(() => {
    build();
    click('.push-invite__enable');
    flushMicrotasks();
    fixture.detectChanges();
    expect(push.enable).toHaveBeenCalledTimes(1);
    expect(el().textContent).toContain('Notifications actives sur cet appareil.');
    tick(CONFIRMATION_MS);
    fixture.detectChanges();
    expect(el().querySelector('.push-invite')).toBeNull();
  }));

  it('« Activer » refusé : donne la marche à suivre et ne repropose plus', fakeAsync(() => {
    build({ result: 'denied' });
    click('.push-invite__enable');
    flushMicrotasks();
    fixture.detectChanges();
    expect(el().textContent).toContain('bloque les notifications');
    click('.push-invite__close');
    expect(el().querySelector('.push-invite')).toBeNull();
  }));

  it('« Plus tard » : masque, puis rappelle une seule fois au premier tour long', fakeAsync(() => {
    build();
    click('.push-invite__later');
    expect(el().querySelector('.push-invite')).toBeNull();

    fixture.componentRef.setInput('running', true);
    fixture.detectChanges();
    tick(LONG_TURN_MS - 1);
    fixture.detectChanges();
    expect(el().querySelector('.push-invite')).toBeNull();
    tick(1);
    fixture.detectChanges();
    expect(el().textContent).toContain('Ce travail prend du temps');

    click('.push-invite__later');
    fixture.componentRef.setInput('running', false);
    fixture.detectChanges();
    fixture.componentRef.setInput('running', true);
    fixture.detectChanges();
    tick(LONG_TURN_MS * 2);
    fixture.detectChanges();
    expect(el().querySelector('.push-invite')).toBeNull();
  }));

  it('un tour court ne rappelle rien', fakeAsync(() => {
    build();
    click('.push-invite__later');
    fixture.componentRef.setInput('running', true);
    fixture.detectChanges();
    tick(LONG_TURN_MS / 2);
    fixture.componentRef.setInput('running', false);
    fixture.detectChanges();
    tick(LONG_TURN_MS);
    fixture.detectChanges();
    expect(el().querySelector('.push-invite')).toBeNull();
  }));

  it('« Ne plus proposer » : retenu sur l\'appareil', () => {
    build();
    click('.push-invite__never');
    expect(el().querySelector('.push-invite')).toBeNull();
    expect(localStorage.getItem(PUSH_INVITE_NEVER_KEY)).toBe('1');
    TestBed.resetTestingModule();
    build();
    expect(el().querySelector('.push-invite')).toBeNull();
  });

  it('un stockage indisponible ne fait rien échouer', () => {
    spyOn(localStorage, 'getItem').and.throwError('private');
    spyOn(localStorage, 'setItem').and.throwError('private');
    build();
    expect(el().querySelector('.push-invite')).not.toBeNull();
    expect(() => click('.push-invite__never')).not.toThrow();
    expect(el().querySelector('.push-invite')).toBeNull();
  });
});
