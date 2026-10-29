import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { StepActorComponent } from './step-actor.component';

/** **Qui exécute l'étape ?** (F-219 / SF-219-06) — la marque discrète d'une étape non-outil. */
describe('F-219 / SF-219-06 — StepActorComponent', () => {
  const render = (actor: string | null | undefined): HTMLElement => {
    TestBed.configureTestingModule({ providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(StepActorComponent);
    fixture.componentRef.setInput('actor', actor);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  it('MOI : « par vous », phrase complète accessible, ton orange', () => {
    const mark = render('MOI').querySelector('.actor') as HTMLElement;
    expect(mark.textContent).toContain('par vous');
    expect(mark.getAttribute('aria-label')).toBe('Exécutée par vous : l\'outil ne peut pas faire ce geste');
    expect(mark.dataset['actor']).toBe('MOI');
    expect(mark.querySelector('mat-icon')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('TIERS : « par un tiers »', () => {
    const mark = render('TIERS').querySelector('.actor') as HTMLElement;
    expect(mark.textContent).toContain('par un tiers');
    expect(mark.getAttribute('aria-label')).toContain('quelqu\'un d\'autre');
  });

  it('OUTIL ou absent : rien (le cas courant reste silencieux)', () => {
    expect(render('OUTIL').querySelector('.actor')).toBeNull();
  });

  it('absent : rien', () => {
    expect(render(null).querySelector('.actor')).toBeNull();
  });
});
