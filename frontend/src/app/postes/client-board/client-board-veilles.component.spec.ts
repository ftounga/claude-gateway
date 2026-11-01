import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ClientBoardVeillesComponent } from './client-board-veilles.component';
import { watch } from '../../atelier/terminal/terminal-veilles-band.component.spec';
import { VeilleService } from '../../core/services/veille.service';

/** **Les veilles dans le tableau du client** (F-230 / SF-230-04). */
describe('Les veilles Jarvis du tableau du client (F-230 / SF-230-04)', () => {
  let service: jasmine.SpyObj<VeilleService>;

  beforeEach(() => {
    service = jasmine.createSpyObj<VeilleService>('VeilleService', ['listForAccount']);
    TestBed.configureTestingModule({
      imports: [ClientBoardVeillesComponent],
      providers: [provideRouter([]), { provide: VeilleService, useValue: service }],
    });
  });

  it('liste les veilles des sujets de ce poste', () => {
    service.listForAccount.and.returnValue(of([
      { watch: watch({ proposed: false, active: true }), workspaceName: 'cagip' },
      { watch: watch({ id: 'v-2', name: 'Alarmes', proposed: false, active: true, broken: true }), workspaceName: 'edenred' },
    ]));
    const fixture = TestBed.createComponent(ClientBoardVeillesComponent);
    fixture.componentRef.setInput('hostId', 'h-1');
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(service.listForAccount).toHaveBeenCalledWith('h-1');
    expect(text).toContain('Veilles Jarvis');
    expect(text).toContain('Agenor');
    expect(text).toContain('cagip');
    expect(text).toContain('En panne — à faire réparer');
  });

  it('reste absente quand il n’y a aucune veille', () => {
    service.listForAccount.and.returnValue(of([]));
    const fixture = TestBed.createComponent(ClientBoardVeillesComponent);
    fixture.componentRef.setInput('hostId', 'h-1');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.cbv')).toBeNull();
  });
});
