import { TestBed, discardPeriodicTasks, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { Injectable } from '@angular/core';
import { SwUpdate, VersionEvent } from '@angular/service-worker';
import { Subject } from 'rxjs';

import { JARVIS_UPDATE_CHECK_MS, JarvisAppUpdate } from './jarvis-app-update';

class FakeSwUpdate {
  isEnabled = true;
  readonly versionUpdates = new Subject<VersionEvent>();
  checkForUpdate = jasmine.createSpy('checkForUpdate').and.resolveTo(false);
  activateUpdate = jasmine.createSpy('activateUpdate').and.resolveTo(true);
}

/** Expose le rechargement pour le compter. */
@Injectable()
class TestableUpdate extends JarvisAppUpdate {
  reloads = 0;
  protected override reload(): void {
    this.reloads++;
  }
}

const READY: VersionEvent = {
  type: 'VERSION_READY',
  currentVersion: { hash: 'abcdef0123456789' },
  latestVersion: { hash: '9876543210fedcba' },
};

/** **La mise à jour automatique de Jarvis** (F-233 / SF-233-12). */
describe('La mise à jour automatique de Jarvis (F-233 / SF-233-12)', () => {
  let sw: FakeSwUpdate;
  let update: TestableUpdate;

  function create(): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [TestableUpdate, { provide: SwUpdate, useValue: sw }] });
    update = TestBed.inject(TestableUpdate);
  }

  beforeEach(() => {
    sw = new FakeSwUpdate();
  });

  it('service worker absent : ni vérification, ni version, ni rechargement', fakeAsync(() => {
    sw.isEnabled = false;
    create();
    update.start(() => true);
    sw.versionUpdates.next(READY);
    tick(JARVIS_UPDATE_CHECK_MS);
    expect(sw.checkForUpdate).not.toHaveBeenCalled();
    expect(update.version()).toBeNull();
    expect(update.reloads).toBe(0);
    update.ngOnDestroy();
  }));

  it('vérifie à l’ouverture puis toutes les 10 minutes, et s’arrête à la fermeture', fakeAsync(() => {
    create();
    update.start(() => true);
    expect(sw.checkForUpdate).toHaveBeenCalledTimes(1);
    tick(JARVIS_UPDATE_CHECK_MS);
    expect(sw.checkForUpdate).toHaveBeenCalledTimes(2);
    update.ngOnDestroy();
    tick(JARVIS_UPDATE_CHECK_MS * 2);
    expect(sw.checkForUpdate).toHaveBeenCalledTimes(2);
    discardPeriodicTasks();
  }));

  it('affiche le hash court de la version servie', fakeAsync(() => {
    create();
    update.start(() => true);
    sw.versionUpdates.next({ type: 'NO_NEW_VERSION_DETECTED', version: { hash: '1234567890abcdef' } });
    expect(update.version()).toBe('1234567');
    update.ngOnDestroy();
  }));

  it('version prête au repos : activée puis page rechargée', fakeAsync(() => {
    create();
    update.start(() => true);
    sw.versionUpdates.next(READY);
    flushMicrotasks();
    expect(update.version()).toBe('abcdef0');
    expect(sw.activateUpdate).toHaveBeenCalledTimes(1);
    expect(update.reloads).toBe(1);
    update.ngOnDestroy();
  }));

  it('version prête pendant un tour : différée, puis appliquée au retour au repos', fakeAsync(() => {
    let quiet = false;
    create();
    update.start(() => quiet);
    sw.versionUpdates.next(READY);
    flushMicrotasks();
    expect(update.pending()).toBeTrue();
    expect(sw.activateUpdate).not.toHaveBeenCalled();
    expect(update.reloads).toBe(0);

    update.settle();
    flushMicrotasks();
    expect(update.reloads).toBe(0);

    quiet = true;
    update.settle();
    flushMicrotasks();
    expect(sw.activateUpdate).toHaveBeenCalledTimes(1);
    expect(update.reloads).toBe(1);
    update.settle();
    flushMicrotasks();
    expect(update.reloads).toBe(1);
    update.ngOnDestroy();
  }));

  it('activation en échec : rechargement quand même', fakeAsync(() => {
    sw.activateUpdate.and.rejectWith(new Error('sw'));
    create();
    update.start(() => true);
    sw.versionUpdates.next(READY);
    flushMicrotasks();
    expect(update.reloads).toBe(1);
    update.ngOnDestroy();
  }));
});
