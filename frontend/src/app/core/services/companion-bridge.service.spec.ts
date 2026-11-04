import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService, COMPANION_RENEW_BEFORE_MS } from './auth.service';
import { COMPANION_SESSION_WAIT_MS, CompanionBridgeService, CompanionNative, CompanionSession } from './companion-bridge.service';

/** F-232 / SF-232-07 — le pont du compagnon Android et la session qu'il remet. */
describe('CompanionBridgeService (F-232 / SF-232-07)', () => {
  let native: jasmine.SpyObj<CompanionNative>;
  let locked: boolean;

  function session(token: string, isLocked: boolean, inMs = 30 * 60_000): CompanionSession {
    return { token, expiresAt: new Date(Date.now() + inMs).toISOString(), scope: 'jarvis-compagnon', locked: isLocked };
  }

  function setup(withNative: boolean): void {
    locked = true;
    native = jasmine.createSpyObj<CompanionNative>('native',
      ['requestSession', 'isLocked', 'requestUnlock', 'keepAlive', 'close']);
    native.isLocked.and.callFake(() => locked);
    if (withNative) {
      window.JarvisCompanion = native;
    } else {
      delete window.JarvisCompanion;
    }
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  }

  afterEach(() => {
    delete window.JarvisCompanion;
    delete window.jarvisCompanionSession;
    delete window.jarvisCompanionUnlocked;
    localStorage.removeItem('cg_token');
  });

  it('hors compagnon : absent, rien ne change (D10)', async () => {
    setup(false);
    localStorage.setItem('cg_token', 'jeton-pwa');
    const bridge = TestBed.inject(CompanionBridgeService);
    const auth = TestBed.inject(AuthService);
    expect(bridge.available).toBeFalse();
    expect(auth.inCompanion).toBeFalse();
    expect(auth.token()).toBe('jeton-pwa');
    expect(await bridge.unlock()).toBeTrue();
    expect(await auth.openCompanionSession('OPEN')).toBeFalse();
  });

  it('dans le compagnon : la session vient du pont, jamais du stockage, et n’y est jamais écrite', async () => {
    setup(true);
    localStorage.setItem('cg_token', 'jeton-pwa');
    native.requestSession.and.callFake(() => window.jarvisCompanionSession?.(session('jwt-compagnon', true)));
    const auth = TestBed.inject(AuthService);
    expect(auth.inCompanion).toBeTrue();
    expect(auth.token()).toBeNull();

    expect(await auth.openCompanionSession('OPEN')).toBeTrue();

    expect(native.requestSession).toHaveBeenCalledWith('OPEN');
    expect(auth.token()).toBe('jwt-compagnon');
    expect(localStorage.getItem('cg_token')).toBe('jeton-pwa');
    auth.clearToken();
    expect(localStorage.getItem('cg_token')).toBe('jeton-pwa');
  });

  it('session refusée (null) : pas de session', async () => {
    setup(true);
    native.requestSession.and.callFake(() => window.jarvisCompanionSession?.(null));
    const auth = TestBed.inject(AuthService);
    expect(await auth.openCompanionSession('OPEN')).toBeFalse();
    expect(auth.token()).toBeNull();
  });

  it('le natif ne répond pas : échec au bout du délai', fakeAsync(() => {
    setup(true);
    const bridge = TestBed.inject(CompanionBridgeService);
    let result: CompanionSession | null | undefined;
    void bridge.session('OPEN').then((s) => (result = s));
    tick(COMPANION_SESSION_WAIT_MS);
    expect(result).toBeNull();
  }));

  it('renouvelle la session avant son échéance', fakeAsync(() => {
    setup(true);
    const auth = TestBed.inject(AuthService);
    window.jarvisCompanionSession?.(session('jwt-1', true, COMPANION_RENEW_BEFORE_MS + 60_000));
    expect(auth.token()).toBe('jwt-1');
    tick(59_000);
    expect(native.requestSession).not.toHaveBeenCalled();
    tick(2_000);
    expect(native.requestSession).toHaveBeenCalledWith('RENEW');
    auth.clearToken();
  }));

  it('déverrouiller : vrai quand le natif répond oui, après la session déverrouillée', async () => {
    setup(true);
    native.requestUnlock.and.callFake(() => {
      locked = false;
      window.jarvisCompanionSession?.(session('jwt-ouvert', false));
      window.jarvisCompanionUnlocked?.(true);
    });
    const bridge = TestBed.inject(CompanionBridgeService);
    const auth = TestBed.inject(AuthService);

    expect(await bridge.unlock()).toBeTrue();

    expect(auth.token()).toBe('jwt-ouvert');
    expect(bridge.locked()).toBeFalse();
  });

  it('déverrouillage annulé : faux', async () => {
    setup(true);
    native.requestUnlock.and.callFake(() => window.jarvisCompanionUnlocked?.(false));
    const bridge = TestBed.inject(CompanionBridgeService);
    expect(await bridge.unlock()).toBeFalse();
  });

  it('déjà déverrouillé : vrai sans rien demander', async () => {
    setup(true);
    locked = false;
    const bridge = TestBed.inject(CompanionBridgeService);
    expect(await bridge.unlock()).toBeTrue();
    expect(native.requestUnlock).not.toHaveBeenCalled();
  });

  it('signe de vie et fermeture passent au natif', () => {
    setup(true);
    const bridge = TestBed.inject(CompanionBridgeService);
    bridge.keepAlive();
    bridge.close();
    expect(native.keepAlive).toHaveBeenCalled();
    expect(native.close).toHaveBeenCalled();
  });
});
