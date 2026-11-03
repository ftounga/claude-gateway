import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { JarvisBudgetStatus, JarvisCostSummary, JarvisCosts } from '../core/models/jarvis.models';
import { JarvisService } from '../core/services/jarvis.service';
import { JARVIS_COST_ROWS, JarvisCostPanelComponent, costOf } from './jarvis-cost-panel.component';

function costs(over: Partial<JarvisCosts> = {}): JarvisCosts {
  return { fastBrain: 0.12, voice: 1.5, listening: 0.33, opus: 4.05, mail: 0, agenda: 0, total: 6, opusTurns: 3, ...over };
}

function budget(over: Partial<JarvisBudgetStatus> = {}): JarvisBudgetStatus {
  return { monthlyCapEur: 150, applyOnByok: false, monthSpentEur: 60, percent: 40, state: 'NONE', applies: true, ...over };
}

describe('JarvisCostPanelComponent (F-244 / SF-244-04)', () => {
  let fixture: ComponentFixture<JarvisCostPanelComponent>;
  let api: jasmine.SpyObj<JarvisService>;

  function mount(summary: JarvisCostSummary): HTMLElement {
    fixture = TestBed.createComponent(JarvisCostPanelComponent);
    fixture.componentRef.setInput('costs', summary);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    api = jasmine.createSpyObj<JarvisService>('JarvisService', ['updateBudget']);
    TestBed.configureTestingModule({
      imports: [JarvisCostPanelComponent],
      providers: [provideNoopAnimations(), { provide: JarvisService, useValue: api }],
    });
  });

  it('les six postes, le total du jour et du mois, en euros à deux décimales, jamais en dollars', () => {
    const el = mount({ today: costs({ fastBrain: 0.1, voice: 0.2, listening: 0.03, opus: 0, total: 0.33 }),
      month: costs(), budget: budget() });
    const rows = Array.from(el.querySelectorAll('tbody tr'));
    expect(rows.map((r) => r.querySelector('th')!.textContent!.trim())).toEqual(
      ['Conversation rapide', 'Voix', 'Transcription', 'Recherches Opus', 'Courrier', 'Agenda']);
    expect(rows[3].textContent).toContain('4,05 €');
    expect(el.querySelector('.jc__total')!.textContent).toContain('0,33 €');
    expect(el.querySelector('.jc__total')!.textContent).toContain('6,00 €');
    expect(el.textContent).not.toContain('$');
  });

  it('le total égale la somme des postes (contrat de la passerelle)', () => {
    const month = costs();
    const sum = JARVIS_COST_ROWS.reduce((acc, row) => acc + costOf(month, row.key), 0);
    expect(Math.round(sum * 100) / 100).toBe(month.total);
  });

  it('la jauge reste crème sous 80 % et passe à l’orange à 80 %', () => {
    let el = mount({ today: costs(), month: costs(), budget: budget({ percent: 79 }) });
    expect(el.querySelector('.jc__gauge-fill--alert')).toBeNull();
    el = mount({ today: costs(), month: costs(), budget: budget({ percent: 80, state: 'ALERT' }) });
    expect(el.querySelector('.jc__gauge-fill--alert')).not.toBeNull();
    expect(el.querySelector('.jc__note')!.textContent).toContain('80 %');
  });

  it('enregistre le plafond, refuse un montant hors bornes, et dit l’échec', () => {
    api.updateBudget.and.returnValue(of(budget({ monthlyCapEur: 300, percent: 20 })));
    mount({ today: costs(), month: costs(), budget: budget() });
    const panel = fixture.componentInstance;
    let saved = 0;
    panel.saved.subscribe(() => saved++);

    panel.capDraft.set(-1);
    panel.save();
    expect(api.updateBudget).not.toHaveBeenCalled();
    expect(panel.error()).toContain('entre 0 et 10 000');

    panel.capDraft.set(300);
    panel.save();
    expect(api.updateBudget).toHaveBeenCalledWith({ monthlyCapEur: 300, applyOnByok: false });
    expect(saved).toBe(1);
    expect(panel.budget()!.monthlyCapEur).toBe(300);

    api.updateBudget.and.returnValue(throwError(() => new Error('500')));
    panel.save();
    expect(panel.error()).toContain('pas pu');
  });

  it('pas de débordement horizontal à 360 px', () => {
    const el = mount({ today: costs(), month: costs(), budget: budget() });
    const panel = el.querySelector('.jc') as HTMLElement;
    panel.style.position = 'static';
    panel.style.width = '344px';
    expect(panel.scrollWidth).toBeLessThanOrEqual(panel.clientWidth + 1);
  });
});
