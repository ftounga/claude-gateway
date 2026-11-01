import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { AtelierSlashVeillesComponent } from './atelier-slash-veilles.component';
import { watch } from './terminal-veilles-band.component.spec';
import { VeilleService } from '../../core/services/veille.service';

/** **`/veilles`** (F-230 / SF-230-01) : la liste des veilles du sujet, sans tour modèle. */
describe('Le panneau /veilles (F-230 / SF-230-01)', () => {
  let fixture: ComponentFixture<AtelierSlashVeillesComponent>;
  let service: jasmine.SpyObj<VeilleService>;
  let dialog: jasmine.SpyObj<MatDialog>;

  function create(): void {
    fixture = TestBed.createComponent(AtelierSlashVeillesComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<VeilleService>('VeilleService', ['list', 'remove', 'update', 'runNow']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    TestBed.configureTestingModule({
      imports: [AtelierSlashVeillesComponent, NoopAnimationsModule],
      providers: [{ provide: VeilleService, useValue: service }, { provide: MatDialog, useValue: dialog }],
    });
  });

  it('invite à demander une veille à l’agent quand il n’y en a pas', () => {
    service.list.and.returnValue(of([]));
    create();
    expect(text()).toContain('Aucune veille sur ce sujet');
  });

  it('liste les veilles avec leur état et leurs heures', () => {
    service.list.and.returnValue(of([
      watch(), watch({ id: 'v-2', name: 'EDENRED', proposed: false, active: true, nextRunAt: '2026-11-02T08:00:00Z' }),
    ]));
    create();
    expect(text()).toContain('Agenor');
    expect(text()).toContain('Proposée — à tester puis activer');
    expect(text()).toContain('EDENRED');
    expect(text()).toContain('Active');
    expect(text()).toContain('Prochain passage');
  });

  it('dit le dernier passage et la panne (SF-230-02)', () => {
    service.list.and.returnValue(of([
      watch({ proposed: false, active: true, lastRunAt: new Date().toISOString(), lastRunStatus: 'NO_CHANGE' }),
      watch({ id: 'v-3', name: 'Muette', proposed: false, active: true, broken: true, lastRunStatus: 'FAILED',
        lastRunAt: new Date().toISOString() }),
    ]));
    create();
    expect(text()).toContain('rien de neuf');
    expect(text()).toContain('En panne — à faire réparer');
    expect(text()).toContain('sonde en échec');
  });

  it('met en pause, reprend et lance maintenant ; dit la dépense du mois en euros (SF-230-04)', () => {
    const active = watch({ proposed: false, active: true, monthlyBudgetEur: 5, monthSpentEur: 0.12 });
    service.list.and.returnValue(of([active]));
    service.update.and.returnValue(of(watch({ proposed: false, active: false })));
    service.runNow.and.returnValue(of({
      id: 'r-1', watchId: 'v-1', slotAt: null, trigger: 'MANUAL', status: 'CHANGED', changeCount: 2, errorCode: null,
      durationMs: 800, startedAt: '', finishedAt: '',
    }));
    create();
    expect(text()).toContain('Rédaction IA ce mois-ci : 0,12 € sur 5,00 €');
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    (buttons.find((b) => (b.textContent ?? '').includes('Pause')) as HTMLButtonElement).click();
    expect(service.update).toHaveBeenCalledWith('w-1', 'v-1', { active: false });
    fixture.componentInstance.runNow(active);
    fixture.detectChanges();
    expect(service.runNow).toHaveBeenCalledWith('w-1', 'v-1');
    expect(text()).toContain('« Agenor » : 2 changements.');
  });

  it('une proposition n’a ni pause ni lancement', () => {
    service.list.and.returnValue(of([watch()]));
    create();
    expect(text()).not.toContain('Pause');
    expect(text()).not.toContain('Lancer maintenant');
  });

  it('supprime après confirmation', () => {
    service.list.and.returnValue(of([watch()]));
    service.remove.and.returnValue(of(void 0));
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
    create();
    let changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);
    fixture.componentInstance.remove(watch());
    expect(service.remove).toHaveBeenCalledWith('w-1', 'v-1');
    expect(changed).toBe(1);
  });
});
