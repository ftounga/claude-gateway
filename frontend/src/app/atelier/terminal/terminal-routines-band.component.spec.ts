import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { TerminalRoutinesBandComponent } from './terminal-routines-band.component';
import { Routine, RoutineRun, ago } from '../../core/models/routine.models';
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

  function run(over: Partial<RoutineRun> = {}): RoutineRun {
    return {
      id: 'run-1', routineId: 'r-1', slotAt: null, trigger: 'SCHEDULED', status: 'DONE',
      startedAt: new Date(Date.now() - 2 * 3_600_000).toISOString(),
      finishedAt: new Date(Date.now() - 2 * 3_600_000).toISOString(), costUsd: 0.84, capUsd: 5, errorCode: null,
      summary: 'Routine du matin — 3 attentes mises à jour', attentesUpdated: 3, routineName: 'Routine du matin',
      acknowledgedAt: null, ...over,
    };
  }

  function create(routines: Routine[], runs: RoutineRun[] = []): void {
    service.list.and.returnValue(of(routines));
    service.unseen.and.returnValue(of(runs));
    fixture = TestBed.createComponent(TerminalRoutinesBandComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<RoutineService>('RoutineService',
      ['list', 'accept', 'remove', 'unseen', 'acknowledge']);
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

  it('montre le compte rendu en tête du fil (SF-183-04)', () => {
    create([], [run()]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Routine du matin — 3 attentes mises à jour');
    expect(text).toContain('il y a 2 h');
    expect(text).toContain('0,84 $');
  });

  it('[Voir] amène la demande ; [×] ferme la ligne (SF-183-04)', () => {
    create([], [run()]);
    service.acknowledge.and.returnValue(of(void 0));
    let revealed = '';
    fixture.componentInstance.reveal.subscribe((name) => (revealed = name));
    const see = (fixture.nativeElement as HTMLElement).querySelector('.routine-run__see') as HTMLButtonElement;
    see.click();
    expect(revealed).toBe('Routine du matin');
    fixture.componentInstance.acknowledge(fixture.componentInstance.runs()[0]);
    fixture.detectChanges();
    expect(service.acknowledge).toHaveBeenCalledWith('w-1', 'run-1');
    expect((fixture.nativeElement as HTMLElement).querySelector('.routine-run')).toBeNull();
  });

  it('une exécution manquée n’offre pas [Voir] (SF-183-04)', () => {
    create([], [run({ status: 'MISSED_OFFLINE', summary: 'Routine du matin — manquée — poste hors ligne' })]);
    expect((fixture.nativeElement as HTMLElement).querySelector('.routine-run__see')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('.routine-run--stopped')).not.toBeNull();
  });

  it('dit l’ancienneté en clair', () => {
    const now = Date.parse('2026-10-19T10:00:00Z');
    expect(ago('2026-10-19T09:59:40Z', now)).toBe('à l’instant');
    expect(ago('2026-10-19T09:45:00Z', now)).toBe('il y a 15 min');
    expect(ago('2026-10-19T08:00:00Z', now)).toBe('il y a 2 h');
    expect(ago('2026-10-18T08:00:00Z', now)).toBe('hier');
    expect(ago('2026-10-15T08:00:00Z', now)).toBe('il y a 4 j');
  });

  it('se relit quand le compteur change', () => {
    create([]);
    expect(service.list).toHaveBeenCalledTimes(1);
    fixture.componentRef.setInput('refresh', 1);
    fixture.detectChanges();
    expect(service.list).toHaveBeenCalledTimes(2);
  });
});
