import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { UnblockerMark } from '../../core/models/client-board.models';
import { UnblockerPillComponent } from './unblocker-pill.component';

/** F-220 / SF-220-04 — la pastille « qui débloque ». */
describe('UnblockerPillComponent', () => {
  const mark: UnblockerMark = {
    who: 'AUTRUI', label: 'On attend quelqu\'un', detail: 'Étape 2 : en attente de « cert » auprès de Gino.',
    step: 2, waitingOn: 'Gino', since: null,
  };

  beforeEach(() => TestBed.configureTestingModule({ providers: [provideNoopAnimations()] }));

  const mount = (m: UnblockerMark | null, size: 'sm' | 'md' = 'md') => {
    const fixture = TestBed.createComponent(UnblockerPillComponent);
    fixture.componentRef.setInput('mark', m);
    fixture.componentRef.setInput('size', size);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  it('écrit le libellé complet, l\'icône, et la phrase pour les lecteurs d\'écran', () => {
    const el = mount(mark);
    const pill = el.querySelector('.pill') as HTMLElement;
    expect(pill.getAttribute('data-who')).toBe('AUTRUI');
    expect(pill.textContent).toContain('On attend quelqu\'un');
    expect(pill.querySelector('mat-icon')?.textContent).toContain('hourglass_top');
    expect(pill.getAttribute('aria-label')).toContain('auprès de Gino');
  });

  it('le libellé court en petite taille', () => {
    const el = mount({ ...mark, who: 'OUTIL', label: 'L\'outil peut avancer' }, 'sm');
    expect(el.querySelector('.pill')?.textContent).toContain('l\'outil avance');
    expect(el.querySelector('.pill--sm')).not.toBeNull();
  });

  it('rien sans marque', () => {
    expect(mount(null).querySelector('.pill')).toBeNull();
  });
});
