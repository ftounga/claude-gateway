import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { DiscreetVeilService, VEIL_SETTINGS_KEY } from '../../core/services/discreet-veil.service';
import { DiscreetVeilSettingsComponent } from './discreet-veil-settings.component';

describe('DiscreetVeilSettingsComponent (F-259 / SF-259-01)', async () => {
  let fixture: ComponentFixture<DiscreetVeilSettingsComponent>;

  async function build(): Promise<void> {
    TestBed.configureTestingModule({ imports: [DiscreetVeilSettingsComponent], providers: [provideNoopAnimations()] });
    fixture = TestBed.createComponent(DiscreetVeilSettingsComponent);
    fixture.detectChanges();
    // mat-select n'affiche la valeur choisie qu'après l'initialisation (asynchrone) de ses options.
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  afterEach(() => localStorage.removeItem(VEIL_SETTINGS_KEY));

  it('montre le voile activé par défaut, délai 2 min réglable (SF-259-02)', async () => {
    localStorage.removeItem(VEIL_SETTINGS_KEY);
    await build();
    expect(el<HTMLInputElement>('.veil-settings__enabled input').checked).toBeTrue();
    expect(el<HTMLElement>('.veil-settings__delay').textContent).toContain('2 min');
    expect(el<HTMLElement>('mat-select').getAttribute('aria-disabled')).toBe('false');
  });

  it('décocher désactive le voile et l’écrit sur l’appareil', async () => {
    localStorage.removeItem(VEIL_SETTINGS_KEY);
    await build();
    el<HTMLInputElement>('.veil-settings__enabled input').click();
    fixture.detectChanges();
    expect(TestBed.inject(DiscreetVeilService).settings().enabled).toBeFalse();
    expect(JSON.parse(localStorage.getItem(VEIL_SETTINGS_KEY)!)).toEqual({ enabled: false, delayMin: 2 });
  });

  it('lit le réglage enregistré (activé, 5 min)', async () => {
    localStorage.setItem(VEIL_SETTINGS_KEY, JSON.stringify({ enabled: true, delayMin: 5 }));
    await build();
    expect(el<HTMLInputElement>('.veil-settings__enabled input').checked).toBeTrue();
    expect(el<HTMLElement>('.veil-settings__delay').textContent).toContain('5 min');
  });

  it('stockage indisponible : le signale et laisse le voile désactivé', async () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('SecurityError');
    await build();
    expect(el<HTMLElement>('.veil-settings__warning').textContent).toContain('le voile reste désactivé');
    expect(el<HTMLInputElement>('.veil-settings__enabled input').disabled).toBeTrue();
  });
});
