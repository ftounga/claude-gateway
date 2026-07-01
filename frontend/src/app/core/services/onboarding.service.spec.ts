import { TestBed } from '@angular/core/testing';

import { OnboardingService } from './onboarding.service';

describe('OnboardingService', () => {
  let service: OnboardingService;

  beforeEach(() => {
    localStorage.removeItem('cg_onboarding');
    TestBed.configureTestingModule({});
    service = TestBed.inject(OnboardingService);
  });

  afterEach(() => localStorage.removeItem('cg_onboarding'));

  it("n'est pas terminé par défaut et route vers /onboarding", () => {
    expect(service.isCompleted()).toBeFalse();
    expect(service.providerMode()).toBeNull();
    expect(service.postLoginPath()).toBe('/onboarding');
  });

  it('complete(HOSTED) mémorise le mode et route vers /profile', () => {
    service.complete('HOSTED');
    expect(service.isCompleted()).toBeTrue();
    expect(service.providerMode()).toBe('HOSTED');
    expect(service.postLoginPath()).toBe('/profile');
  });

  it('complete(BYOK) mémorise le mode BYOK', () => {
    service.complete('BYOK');
    expect(service.providerMode()).toBe('BYOK');
    expect(service.isCompleted()).toBeTrue();
  });

  it('tolère un contenu localStorage corrompu', () => {
    localStorage.setItem('cg_onboarding', '{not-json');
    expect(service.isCompleted()).toBeFalse();
    expect(service.providerMode()).toBeNull();
  });
});
