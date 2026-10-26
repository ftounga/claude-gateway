import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { NotificationSoundComponent, testSoundMessage } from './notification-sound.component';
import {
  DEFAULT_SOUND_SETTINGS,
  NotificationSoundService,
  SoundSettings,
} from '../../core/services/notification-sound.service';
import { PushActivationService, PushTestResult } from '../../core/services/push-activation.service';

describe('NotificationSoundComponent (F-217 / SF-217-02)', () => {
  let fixture: ComponentFixture<NotificationSoundComponent>;
  let settings: ReturnType<typeof signal<SoundSettings>>;
  let sound: { settings: typeof settings; update: jasmine.Spy; test: jasmine.Spy };
  let push: { sendTest: jasmine.Spy; enabled: ReturnType<typeof signal<boolean>> };
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function build(result: PushTestResult | null = { configured: true, devices: 2 }): void {
    settings = signal<SoundSettings>({ ...DEFAULT_SOUND_SETTINGS });
    sound = {
      settings,
      update: jasmine.createSpy('update').and.callFake((change: Partial<SoundSettings>) =>
        settings.set({ ...settings(), ...change })),
      test: jasmine.createSpy('test'),
    };
    push = { sendTest: jasmine.createSpy('sendTest').and.resolveTo(result), enabled: signal(true) };
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [NotificationSoundComponent],
      providers: [
        provideNoopAnimations(),
        { provide: NotificationSoundService, useValue: sound },
        { provide: PushActivationService, useValue: push },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(NotificationSoundComponent);
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  it('montre le son activé par défaut, le volume et les deux catégories cochées', () => {
    build();
    expect(el<HTMLInputElement>('.sound__enabled input').checked).toBeTrue();
    expect(el<HTMLInputElement>('.sound__range').value).toBe('70');
    expect(el<HTMLElement>('.sound__percent').textContent).toContain('70 %');
    expect(el<HTMLInputElement>('.sound__action input').checked).toBeTrue();
    expect(el<HTMLInputElement>('.sound__info input').checked).toBeTrue();
  });

  it('couper le son grise le volume et les catégories', () => {
    build();
    el<HTMLInputElement>('.sound__enabled input').click();
    fixture.detectChanges();
    expect(sound.update).toHaveBeenCalledWith({ enabled: false });
    expect(el<HTMLInputElement>('.sound__range').disabled).toBeTrue();
    expect(el<HTMLInputElement>('.sound__action input').disabled).toBeTrue();
  });

  it('le curseur règle le volume, et le relâcher fait entendre le niveau', () => {
    build();
    const range = el<HTMLInputElement>('.sound__range');
    range.value = '40';
    range.dispatchEvent(new Event('input'));
    expect(sound.update).toHaveBeenCalledWith({ volume: 0.4 });
    expect(sound.test).not.toHaveBeenCalled();
    range.dispatchEvent(new Event('change'));
    expect(sound.test).toHaveBeenCalledWith('info');
  });

  it('décocher une catégorie la coupe seule', () => {
    build();
    el<HTMLInputElement>('.sound__info input').click();
    expect(sound.update).toHaveBeenCalledWith({ info: false });
  });

  it('« Tester le son » joue le carillon ET envoie la notification système de test', async () => {
    build();
    el<HTMLButtonElement>('.sound__test').click();
    await fixture.whenStable();
    expect(sound.test).toHaveBeenCalledWith('action');
    expect(push.sendTest).toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      jasmine.stringMatching('Une notification de test part vers 2 appareils'), 'Fermer', jasmine.any(Object));
  });

  it('dit honnêtement ce qui est parti, ou pas', () => {
    expect(testSoundMessage(null, true)).toContain("n'a pas pu partir");
    expect(testSoundMessage({ configured: false, devices: 1 }, true)).toContain('pas configurées');
    expect(testSoundMessage({ configured: true, devices: 0 }, false)).toContain("Aucun appareil n'est abonné");
    expect(testSoundMessage({ configured: true, devices: 1 }, true)).toContain('vers 1 appareil.');
    expect(testSoundMessage({ configured: true, devices: 1 }, false)).toContain("pas celui-ci");
  });

  it('propose le guide par plateforme, dont l\'iPhone avec l\'application installée', () => {
    build();
    const guide = el<HTMLElement>('.sound__guide');
    const text = guide.textContent ?? '';
    expect(guide.tagName).toBe('DETAILS');
    for (const platform of ['Mac', 'Windows', 'Android', 'iPhone']) {
      expect(text).toContain(platform);
    }
    expect(text).toContain("installée sur l'écran d'accueil");
    expect(text).toContain('Réglages › Notifications');
    expect(text).toContain("ne peut pas choisir le son");
  });
});
