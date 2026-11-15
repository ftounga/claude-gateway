import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DiscreetVeilService, VEIL_SETTINGS_KEY } from '../../core/services/discreet-veil.service';
import { DiscreetVeilComponent } from './discreet-veil.component';

@Component({
  imports: [DiscreetVeilComponent],
  template: `
    <button class="under" (click)="clicks.set(clicks() + 1)">dessous</button>
    @if (mounted()) { <app-discreet-veil /> }
  `,
})
class HostComponent {
  readonly clicks = signal(0);
  readonly mounted = signal(true);
}

describe('DiscreetVeilComponent (F-259 / SF-259-01)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let veil: DiscreetVeilService;

  beforeEach(() => {
    localStorage.setItem(VEIL_SETTINGS_KEY, JSON.stringify({ enabled: true, delayMin: 2 }));
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    veil = TestBed.inject(DiscreetVeilService);
  });

  afterEach(() => {
    fixture.componentInstance.mounted.set(false);
    fixture.detectChanges();
    localStorage.removeItem(VEIL_SETTINGS_KEY);
  });

  function layer(): HTMLElement | null {
    return fixture.nativeElement.querySelector('[data-testid="discreet-veil"]');
  }

  it('ne rend rien tant que le voile n’est pas posé', () => {
    expect(layer()).toBeNull();
  });

  it('monter le composant branche le raccourci (double Échap)', () => {
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(layer()).not.toBeNull();
  });

  it('un clic retire le voile et ne se propage pas', () => {
    veil.veil();
    fixture.detectChanges();
    const parentClick = jasmine.createSpy('parentClick');
    fixture.nativeElement.addEventListener('click', parentClick);
    layer()!.click();
    fixture.detectChanges();
    expect(layer()).toBeNull();
    expect(parentClick).not.toHaveBeenCalled();
    expect(fixture.componentInstance.clicks()).toBe(0);
  });

  it("l'appui retient le focus et le geste (pas de défaut, pas de propagation)", () => {
    veil.veil();
    fixture.detectChanges();
    const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    layer()!.dispatchEvent(down);
    expect(down.defaultPrevented).toBeTrue();
  });

  it('mouvement de souris et touches ne retirent pas le voile', () => {
    veil.veil();
    fixture.detectChanges();
    layer()!.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }));
    layer()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(layer()).not.toBeNull();
  });

  it('aucun texte du voile ne nomme le produit, un client, ni un verrou', () => {
    veil.veil();
    fixture.detectChanges();
    const text = layer()!.textContent ?? '';
    for (const banned of [/claude/i, /ng portal/i, /atelier/i, /edenred/i, /cagip/i, /verrouill/i, /jarvis/i, /forge/i]) {
      expect(text).not.toMatch(banned);
    }
    expect(layer()!.querySelector('img, svg, mat-icon')).toBeNull();
  });

  it('démonter le composant retire le voile', () => {
    veil.veil();
    fixture.componentInstance.mounted.set(false);
    fixture.detectChanges();
    expect(veil.veiled()).toBeFalse();
  });
});
