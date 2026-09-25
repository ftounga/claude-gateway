import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ClientSpace } from '../../core/models/atelier.models';
import { SpacePitchComponent } from './space-pitch.component';

/** La page d'un espace non souscrit (F-106 / SF-106-05). */
describe('SpacePitchComponent', () => {
  let fixture: ComponentFixture<SpacePitchComponent>;

  function render(space: ClientSpace): HTMLElement {
    TestBed.configureTestingModule({ imports: [SpacePitchComponent], providers: [provideRouter([])] });
    fixture = TestBed.createComponent(SpacePitchComponent);
    fixture.componentRef.setInput('space', space);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it("présente la Vigie, son essai de deux semaines, et renvoie au code et aux formules", () => {
    const root = render('VIGIE');

    expect(root.querySelector('.space-pitch__title')?.textContent).toContain('La Vigie');
    expect(root.querySelectorAll('.space-pitch__point').length).toBe(3);
    expect(root.textContent).toContain('Essai de deux semaines');
    expect(root.textContent).toContain('dans la Forge');
    expect(root.querySelector('.space-pitch__code')?.getAttribute('href')).toBe('/billing#code-acces');
    expect(root.querySelector('.space-pitch__plans')?.getAttribute('href')).toBe('/billing');
  });

  it('présente la Forge, et ne montre aucun montant', () => {
    const root = render('FORGE');

    expect(root.querySelector('.space-pitch__title')?.textContent).toContain('La Forge');
    expect(root.textContent).toContain('dans la Vigie');
    expect(root.textContent).not.toMatch(/€|EUR/);
  });

  // SF-158-08 — garde-fou markup responsive : la grille de points et le groupe d'actions
  // portent bien les classes ciblées par le bloc `@media (max-width: 819px)`.
  it('porte la grille de points et le groupe d’actions ciblés par le patron responsive', () => {
    const root = render('VIGIE');

    const points = root.querySelector('.space-pitch__points');
    expect(points).not.toBeNull();
    expect(points!.querySelectorAll('.space-pitch__point').length).toBeGreaterThan(0);

    const actions = root.querySelector('.space-pitch__actions');
    expect(actions).not.toBeNull();
    expect(actions!.querySelectorAll('a').length).toBe(2);
  });
});
