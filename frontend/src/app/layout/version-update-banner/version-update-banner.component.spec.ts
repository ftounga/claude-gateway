import { TestBed } from '@angular/core/testing';
import { SwUpdate, VersionEvent } from '@angular/service-worker';
import { Subject } from 'rxjs';

import { VersionUpdateBannerComponent } from './version-update-banner.component';

/** Faux `SwUpdate` : un `Subject` pour piloter `versionUpdates`, des spies pour les appels du SW. */
class FakeSwUpdate {
  isEnabled = true;
  versionUpdates = new Subject<VersionEvent>();
  checkForUpdate = jasmine.createSpy('checkForUpdate').and.returnValue(Promise.resolve(false));
  activateUpdate = jasmine.createSpy('activateUpdate').and.returnValue(Promise.resolve(true));
}

describe('VersionUpdateBannerComponent', () => {
  let swUpdate: FakeSwUpdate;

  function build(enabled = true): VersionUpdateBannerComponent {
    swUpdate = new FakeSwUpdate();
    swUpdate.isEnabled = enabled;
    TestBed.configureTestingModule({
      imports: [VersionUpdateBannerComponent],
      providers: [{ provide: SwUpdate, useValue: swUpdate }],
    });
    const fixture = TestBed.createComponent(VersionUpdateBannerComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('affiche la bannière sur un événement VERSION_READY', () => {
    const banner = build();
    expect(banner.visible()).toBeFalse();

    swUpdate.versionUpdates.next({
      type: 'VERSION_READY',
      currentVersion: { hash: 'old' },
      latestVersion: { hash: 'new' },
    });

    expect(banner.visible()).toBeTrue();
  });

  it('n\'affiche pas la bannière sur un autre type d\'événement', () => {
    const banner = build();

    swUpdate.versionUpdates.next({ type: 'VERSION_DETECTED', version: { hash: 'new' } });
    expect(banner.visible()).toBeFalse();

    swUpdate.versionUpdates.next({
      type: 'NO_NEW_VERSION_DETECTED',
      version: { hash: 'old' },
    });
    expect(banner.visible()).toBeFalse();
  });

  it('« Recharger » active la nouvelle version puis recharge la page', async () => {
    const banner = build();
    const doReload = spyOn(banner as unknown as { doReload: () => void }, 'doReload');

    await banner.reload();

    expect(swUpdate.activateUpdate).toHaveBeenCalled();
    expect(doReload).toHaveBeenCalled();
  });

  it('« Recharger » recharge même si l\'activation échoue et ne double-clique pas', async () => {
    const banner = build();
    swUpdate.activateUpdate.and.returnValue(Promise.reject(new Error('boom')));
    const doReload = spyOn(banner as unknown as { doReload: () => void }, 'doReload');

    await banner.reload();
    expect(doReload).toHaveBeenCalledTimes(1);

    // reloading reste vrai (la recharge va emporter la page) → un second clic ne relance rien.
    swUpdate.activateUpdate.calls.reset();
    await banner.reload();
    expect(swUpdate.activateUpdate).not.toHaveBeenCalled();
  });

  it('« Plus tard » masque la bannière', () => {
    const banner = build();
    swUpdate.versionUpdates.next({
      type: 'VERSION_READY',
      currentVersion: { hash: 'old' },
      latestVersion: { hash: 'new' },
    });
    expect(banner.visible()).toBeTrue();

    banner.dismiss();

    expect(banner.visible()).toBeFalse();
  });

  it('détection proactive : vérifie une nouvelle version au démarrage', () => {
    build();
    expect(swUpdate.checkForUpdate).toHaveBeenCalledTimes(1);
  });

  it('détection proactive : vérifie une nouvelle version au retour de focus de l\'onglet', () => {
    build();
    expect(swUpdate.checkForUpdate).toHaveBeenCalledTimes(1);

    document.dispatchEvent(new Event('visibilitychange'));

    // document.visibilityState vaut 'visible' dans le navigateur de test (onglet actif).
    expect(swUpdate.checkForUpdate).toHaveBeenCalledTimes(2);
  });

  it('garde : quand le service worker n\'est pas activé, rien ne se passe', () => {
    const banner = build(false);

    expect(swUpdate.checkForUpdate).not.toHaveBeenCalled();

    // Même un VERSION_READY resté branché ne doit rien afficher (aucun abonnement pris).
    swUpdate.versionUpdates.next({
      type: 'VERSION_READY',
      currentVersion: { hash: 'old' },
      latestVersion: { hash: 'new' },
    });
    expect(banner.visible()).toBeFalse();
  });
});
