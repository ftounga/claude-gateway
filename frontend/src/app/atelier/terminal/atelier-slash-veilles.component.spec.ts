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
    service = jasmine.createSpyObj<VeilleService>('VeilleService', ['list', 'remove']);
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
