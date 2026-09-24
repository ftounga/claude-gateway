import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';

import { NotificationsSettingsComponent } from './notifications-settings.component';
import { PushActivationService, PushActivationResult } from '../../core/services/push-activation.service';

describe('NotificationsSettingsComponent', () => {
  let fixture: ComponentFixture<NotificationsSettingsComponent>;
  let push: {
    supported: boolean;
    enabled: ReturnType<typeof signal<boolean>>;
    enable: jasmine.Spy;
    disable: jasmine.Spy;
  };

  function build(supported: boolean, enabled: boolean): void {
    push = {
      supported,
      enabled: signal(enabled),
      enable: jasmine.createSpy('enable').and.resolveTo('enabled' as PushActivationResult),
      disable: jasmine.createSpy('disable').and.resolveTo(undefined),
    };
    TestBed.configureTestingModule({
      imports: [NotificationsSettingsComponent],
      providers: [
        provideNoopAnimations(),
        { provide: PushActivationService, useValue: push },
      ],
    });
    fixture = TestBed.createComponent(NotificationsSettingsComponent);
    fixture.detectChanges();
  }

  it('propose d\'activer quand c\'est supporté et non activé', () => {
    build(true, false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Activer les notifications');
  });

  it('dit non disponible quand ce n\'est pas supporté', () => {
    build(false, false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ne sont pas disponibles');
  });

  it('propose de désactiver quand c\'est déjà activé', () => {
    build(true, true);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Notifications activées');
    expect(text).toContain('Désactiver');
  });

  it('appelle enable() au clic sur « Activer »', () => {
    build(true, false);
    const button = (fixture.nativeElement as HTMLElement)
      .querySelector('button') as HTMLButtonElement;
    button.click();
    // Le gestionnaire appelle enable() de façon synchrone (avant d'attendre sa promesse).
    expect(push.enable).toHaveBeenCalled();
  });

  it('appelle disable() au clic sur « Désactiver »', () => {
    build(true, true);
    const button = (fixture.nativeElement as HTMLElement)
      .querySelector('button') as HTMLButtonElement;
    button.click();
    expect(push.disable).toHaveBeenCalled();
  });
});
