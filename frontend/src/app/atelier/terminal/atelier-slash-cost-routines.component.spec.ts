import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AtelierSlashCostRoutinesComponent } from './atelier-slash-cost-routines.component';
import { Routine, monthPercent } from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';

function routine(over: Partial<Routine> = {}): Routine {
  return {
    id: 'r-1', workspaceId: 'w-1', name: 'Point du matin', instruction: 'CR', cadence: 'WEEKDAYS',
    dayOfWeek: null, runTime: '08:00', timeZone: 'Europe/Paris', maxRunUsd: 5, active: true, proposed: false,
    origin: 'USER', nextRunAt: null, createdAt: '', updatedAt: '', monthlyBudgetUsd: 100, monthSpentUsd: 3.42,
    maxRunEur: 4.6, monthlyBudgetEur: 92, monthSpentEur: 3.15,
    lastRun: {
      id: 'x', routineId: 'r-1', slotAt: null, trigger: 'SCHEDULED', status: 'DONE', startedAt: '',
      finishedAt: '', costUsd: 0.84, capUsd: 5, errorCode: null, summary: null, costEur: 0.77, capEur: 4.6,
    },
    ...over,
  };
}

/** **Les routines dans `/cout`** (F-183 / SF-183-03) : budget par exécution, dépensé du mois, dernier coût. */
describe('Les routines dans /cout (F-183 / SF-183-03)', () => {
  let fixture: ComponentFixture<AtelierSlashCostRoutinesComponent>;
  let service: jasmine.SpyObj<RoutineService>;

  function create(result: ReturnType<RoutineService['list']>): void {
    service.list.and.returnValue(result);
    fixture = TestBed.createComponent(AtelierSlashCostRoutinesComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<RoutineService>('RoutineService', ['list']);
    TestBed.configureTestingModule({
      imports: [AtelierSlashCostRoutinesComponent],
      providers: [{ provide: RoutineService, useValue: service }],
    });
  });

  it('montre budget par exécution, dépensé du mois et dernier passage', () => {
    create(of([routine()]));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Routines de ce sujet');
    // F-224 / SF-224-06 : en euros, convertis par la passerelle.
    expect(text).toContain('3,15 € / 92,00 € ce mois');
    expect(text).toContain('4,60 € max par exécution');
    expect(text).toContain('dernier passage 0,77 €');
    expect(text).not.toContain('$');
  });

  it('ignore les propositions et reste absente sans routine', () => {
    create(of([routine({ proposed: true })]));
    expect((fixture.nativeElement as HTMLElement).querySelector('.cost-routines')).toBeNull();
  });

  it('une lecture en échec n’abîme pas /cout', () => {
    create(throwError(() => new Error('x')));
    expect((fixture.nativeElement as HTMLElement).querySelector('.cost-routines')).toBeNull();
  });

  it('la barre est bornée à 100 %', () => {
    expect(monthPercent({ monthlyBudgetUsd: 10, monthSpentUsd: 25 })).toBe(100);
    expect(monthPercent({ monthlyBudgetUsd: 100, monthSpentUsd: 3.42 })).toBe(3);
    expect(monthPercent({ monthlyBudgetUsd: 0, monthSpentUsd: 1 })).toBe(0);
  });
});
