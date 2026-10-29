import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { AutonomyBoardEntry } from '../../core/models/autonomy.models';
import { parallelChoice } from './parallel-launch';
import { ParallelLaunchDialogComponent, ParallelLaunchDialogData } from './parallel-launch-dialog.component';

const entry = (ready: boolean, canAdvance: boolean, reasons: string[] = []): AutonomyBoardEntry => ({
  number: 8, title: 'Bascule DNS', run: null, estimate: null,
  preflight: { number: 8, ready, reasons: reasons.map((label) => ({ code: 'X', label, step: null })),
    advance: { possible: canAdvance, toolSteps: canAdvance ? [1] : [], remaining: [], label: canAdvance ? '2 étapes à ta portée' : null } },
});

/** F-221 / SF-221-04 — le mode du départ en parallèle, et l'overlay. */
describe('parallelChoice (F-221)', () => {
  it('avancer jusqu’au blocage d’abord, sinon strict si prêt, sinon les raisons', () => {
    expect(parallelChoice(entry(true, true), null).mode).toBe('UNTIL_BLOCKED');
    expect(parallelChoice(entry(true, false), null).mode).toBe('STRICT');
    const none = parallelChoice(entry(false, false, ['Le plan n’est pas validé.']), null);
    expect(none.mode).toBeNull();
    expect(none.reasons).toEqual(['Le plan n’est pas validé.']);
    expect(parallelChoice(null, null).mode).toBeNull();
  });

  it('le mode demandé par le bouton est respecté quand il est possible', () => {
    expect(parallelChoice(entry(true, true), 'STRICT').mode).toBe('STRICT');
    expect(parallelChoice(entry(false, true), 'STRICT').mode).toBe('UNTIL_BLOCKED');
  });
});

describe('ParallelLaunchDialogComponent (F-221)', () => {
  function mount(data: ParallelLaunchDialogData) {
    const ref = jasmine.createSpyObj<MatDialogRef<ParallelLaunchDialogComponent>>('ref', ['close']);
    TestBed.configureTestingModule({
      imports: [ParallelLaunchDialogComponent],
      providers: [provideNoopAnimations(), { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref }],
    });
    const fixture = TestBed.createComponent(ParallelLaunchDialogComponent);
    fixture.detectChanges();
    return { el: fixture.nativeElement as HTMLElement, ref };
  }

  it('peut partir : « Lancer en parallèle » rend launch', () => {
    const { el, ref } = mount({ number: 8, title: 'Bascule DNS', offerSwitch: false,
      choice: { mode: 'UNTIL_BLOCKED', reasons: [] } });
    expect(el.textContent).toContain('Lancer le chantier 8 en parallèle ?');
    expect(el.textContent).toContain('jusqu’au blocage');
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Lancer en parallèle')) as HTMLElement).click();
    expect(ref.close).toHaveBeenCalledWith('launch');
  });

  it('ne peut pas partir : les raisons, « Ouvrir à côté », et « Basculer ici » pour une suite', () => {
    const { el, ref } = mount({ number: 8, title: null, offerSwitch: true,
      choice: { mode: null, reasons: ['Le plan n’est pas validé.'] } });
    expect(el.textContent).toContain('ne peut pas partir maintenant');
    expect(el.textContent).toContain('Le plan n’est pas validé.');
    const buttons = Array.from(el.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Lancer en parallèle'))).toBeFalse();
    (buttons.find((b) => b.textContent?.includes('Basculer ici')) as HTMLElement).click();
    expect(ref.close).toHaveBeenCalledWith('switch');
    (buttons.find((b) => b.textContent?.includes('Ouvrir à côté')) as HTMLElement).click();
    expect(ref.close).toHaveBeenCalledWith('open');
  });
});
