import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { TerminalRoutinesBandComponent } from './terminal-routines-band.component';
import { Routine } from '../../core/models/routine.models';
import { RoutineService } from '../../core/services/routine.service';

function routine(over: Partial<Routine> = {}): Routine {
  return {
    id: 'r-1', workspaceId: 'w-1', name: 'Point du matin', instruction: 'CR du daily', cadence: 'WEEKDAYS',
    dayOfWeek: null, runTime: '08:00', timeZone: 'Europe/Paris', maxRunUsd: 5, active: false, proposed: true,
    origin: 'AGENT', nextRunAt: null, createdAt: '', updatedAt: '', ...over,
  };
}

/** **La carte de validation en tête du fil** (F-183 / SF-183-01) : rien n'est programmé sans [Activer]. */
describe('La bande des routines (F-183 / SF-183-01)', () => {
  let fixture: ComponentFixture<TerminalRoutinesBandComponent>;
  let service: jasmine.SpyObj<RoutineService>;

  function create(routines: Routine[]): void {
    service.list.and.returnValue(of(routines));
    fixture = TestBed.createComponent(TerminalRoutinesBandComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<RoutineService>('RoutineService', ['list', 'accept', 'remove']);
    TestBed.configureTestingModule({
      imports: [TerminalRoutinesBandComponent, NoopAnimationsModule],
      providers: [{ provide: RoutineService, useValue: service }],
    });
  });

  it('montre la carte d’une routine proposée, et rien pour une routine active', () => {
    create([routine(), routine({ id: 'r-2', name: 'Active', proposed: false, active: true })]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Routine proposée · Point du matin');
    expect(text).toContain('Chaque jour ouvré à 08:00');
    expect(text).not.toContain('Routine proposée · Active');
  });

  it('absente quand rien n’est proposé', () => {
    create([routine({ proposed: false, active: true })]);
    expect((fixture.nativeElement as HTMLElement).querySelector('.routine-card')).toBeNull();
  });

  it('[Activer] valide, [Refuser] supprime, [Modifier] ouvre le panneau', () => {
    create([routine()]);
    service.accept.and.returnValue(of(routine({ proposed: false, active: true })));
    service.remove.and.returnValue(of(void 0));
    let opened = 0;
    fixture.componentInstance.openPanel.subscribe(() => opened++);
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');
    (buttons[1] as HTMLButtonElement).click();
    expect(opened).toBe(1);
    fixture.componentInstance.accept(fixture.componentInstance.proposals()[0]);
    expect(service.accept).toHaveBeenCalledWith('w-1', 'r-1');
    fixture.componentInstance.refuse(routine());
    expect(service.remove).toHaveBeenCalledWith('w-1', 'r-1');
  });

  it('se relit quand le compteur change', () => {
    create([]);
    expect(service.list).toHaveBeenCalledTimes(1);
    fixture.componentRef.setInput('refresh', 1);
    fixture.detectChanges();
    expect(service.list).toHaveBeenCalledTimes(2);
  });
});
