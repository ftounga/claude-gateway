import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { SwPush } from '@angular/service-worker';
import { Subject, of } from 'rxjs';

import { PushActivationService } from './push-activation.service';

/** Fausse souscription navigateur : seul `toJSON()` compte pour l'enregistrement. */
function fakeBrowserSubscription(endpoint: string) {
  return {
    endpoint,
    toJSON: () => ({ endpoint, keys: { p256dh: 'PUB-KEY', auth: 'AUTH-SECRET' } }),
  } as unknown as PushSubscription;
}

class FakeSwPush {
  isEnabled = true;
  subscription = new Subject<PushSubscription | null>();
  notificationClicks = new Subject<{ action: string; notification: { data?: { url?: string } } }>();
  requestSubscription = jasmine.createSpy('requestSubscription');
  unsubscribe = jasmine.createSpy('unsubscribe').and.returnValue(Promise.resolve());
}

describe('PushActivationService', () => {
  let swPush: FakeSwPush;
  let http: jasmine.SpyObj<HttpClient>;
  let router: jasmine.SpyObj<Router>;

  function build(enabledSw = true): PushActivationService {
    swPush = new FakeSwPush();
    swPush.isEnabled = enabledSw;
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post', 'delete']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    TestBed.configureTestingModule({
      providers: [
        PushActivationService,
        { provide: SwPush, useValue: swPush },
        { provide: HttpClient, useValue: http },
        { provide: Router, useValue: router },
      ],
    });
    return TestBed.inject(PushActivationService);
  }

  it('signale non supporté quand le service worker n\'est pas enregistré', async () => {
    const service = build(false);
    expect(service.supported).toBeFalse();
    expect(await service.enable()).toBe('unsupported');
    expect(http.get).not.toHaveBeenCalled();
  });

  it('active : demande la permission, s\'abonne et enregistre l\'appareil', async () => {
    const service = build();
    http.get.and.returnValue(of({ key: 'VAPID-PUB' }));
    http.post.and.returnValue(of({}));
    swPush.requestSubscription.and.returnValue(
      Promise.resolve(fakeBrowserSubscription('https://push.example/x')));

    const result = await service.enable();

    expect(result).toBe('enabled');
    expect(swPush.requestSubscription).toHaveBeenCalledWith({ serverPublicKey: 'VAPID-PUB' });
    expect(http.post).toHaveBeenCalledWith('/api/push/subscriptions', {
      endpoint: 'https://push.example/x',
      keys: { p256dh: 'PUB-KEY', auth: 'AUTH-SECRET' },
    });
    expect(service.enabled()).toBeTrue();
  });

  it('signale « non configuré » quand la gateway ne sert pas de clé VAPID', async () => {
    const service = build();
    http.get.and.returnValue(of({ key: null }));

    expect(await service.enable()).toBe('not-configured');
    expect(swPush.requestSubscription).not.toHaveBeenCalled();
    expect(http.post).not.toHaveBeenCalled();
  });

  it('signale « refusé » quand la permission est refusée', async () => {
    const service = build();
    http.get.and.returnValue(of({ key: 'VAPID-PUB' }));
    swPush.requestSubscription.and.returnValue(Promise.reject(new Error('denied')));

    expect(await service.enable()).toBe('denied');
    expect(http.post).not.toHaveBeenCalled();
    expect(service.enabled()).toBeFalse();
  });

  it('désactive : désabonne et retire l\'abonnement côté gateway', async () => {
    const service = build();
    http.get.and.returnValue(of({ key: 'VAPID-PUB' }));
    http.post.and.returnValue(of({}));
    http.delete.and.returnValue(of({}));
    swPush.requestSubscription.and.returnValue(
      Promise.resolve(fakeBrowserSubscription('https://push.example/x')));
    await service.enable();

    await service.disable();

    expect(swPush.unsubscribe).toHaveBeenCalled();
    expect(http.delete).toHaveBeenCalledWith('/api/push/subscriptions',
      { body: { endpoint: 'https://push.example/x' } });
    expect(service.enabled()).toBeFalse();
  });

  it('route vers le bon terminal au clic sur la notification (app ouverte)', () => {
    const service = build();
    expect(service).toBeTruthy();

    swPush.notificationClicks.next({ action: '', notification: { data: { url: '/atelier/w-42' } } });

    expect(router.navigateByUrl).toHaveBeenCalledWith('/atelier/w-42');
  });

  it('reflète l\'état d\'abonnement réel de l\'appareil', () => {
    const service = build();

    swPush.subscription.next(fakeBrowserSubscription('https://push.example/y'));
    expect(service.enabled()).toBeTrue();

    swPush.subscription.next(null);
    expect(service.enabled()).toBeFalse();
  });
});
