import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { TerminalActionsPanelComponent } from './terminal-actions-panel.component';
import {
  TerminalAction, TerminalActionBoard, TerminalActionDuplicate,
} from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { mockTerminalActionsService } from '../../core/services/terminal-actions.service.testing';

function f180Action(id: string, description: string, over: Partial<TerminalAction> = {}): TerminalAction {
  return {
    id, workspaceId: 'w-1', subjectId: null, description, blocks: null, person: null,
    kind: 'ACTION', status: 'A_FAIRE', closedReason: null, closedAt: null,
    createdAt: '2026-10-10T08:00:00Z', ...over,
  };
}

function f180Board(here: TerminalAction[], host: TerminalAction[] = []): TerminalActionBoard {
  const all = [...here, ...host];
  return {
    hostId: 'h-1', here, host,
    aFaire: all.filter(a => a.status === 'A_FAIRE').length,
    demande: all.filter(a => a.status === 'DEMANDE').length,
    oldestOpenAt: all.length ? '2026-10-10T08:00:00Z' : null,
  };
}

/** Le panneau des attentes, ce que F-180 y ajoute. */
describe('Le panneau des attentes (F-180)', () => {
  let fixture: ComponentFixture<TerminalActionsPanelComponent>;
  let component: TerminalActionsPanelComponent;
  let service: jasmine.SpyObj<TerminalActionsService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function build(board: TerminalActionBoard, setup: (s: jasmine.SpyObj<TerminalActionsService>) => void = () => {}): void {
    service = mockTerminalActionsService();
    service.board.and.returnValue(of(board));
    setup(service);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [TerminalActionsPanelComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: TerminalActionsService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar }],
    });
    fixture = TestBed.createComponent(TerminalActionsPanelComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  describe('SF-180-01 : les doublons probables', () => {
    const keep = f180Action('a-1', 'Demander l’accès VPN Karim');
    const dup = f180Action('a-2', 'Obtenir accès VPN pour Karim', { workspaceId: 'w-2' });
    const pair: TerminalActionDuplicate = { keep, duplicate: dup, reason: 'WORDS' };

    it('sont proposés avec leur raison, [Fusionner] et [Ce n’est pas pareil]', () => {
      build(f180Board([keep], [dup]), s => s.duplicates.and.returnValue(of([pair])));
      const section: HTMLElement = fixture.nativeElement.querySelector('.dups');
      expect(section.textContent).toContain('Doublons probables (1)');
      expect(section.textContent).toContain('Garder : Demander l’accès VPN Karim');
      expect(section.textContent).toContain('mêmes mots');
      expect(section.querySelector('.dups__merge')).not.toBeNull();
      expect(section.querySelector('.dups__dismiss')).not.toBeNull();
    });

    it('rien à proposer : aucune section', () => {
      build(f180Board([keep]));
      expect(fixture.nativeElement.querySelector('.dups')).toBeNull();
    });

    it('[Fusionner] appelle la fusion sur le terminal de la doublonne, puis relit', () => {
      build(f180Board([keep], [dup]), s => {
        s.duplicates.and.returnValue(of([pair]));
        s.merge.and.returnValue(of({ ...dup, status: 'ANNULE' }));
      });
      service.duplicates.and.returnValue(of([]));
      component.mergePair(pair);
      fixture.detectChanges();
      expect(service.merge).toHaveBeenCalledWith('w-2', 'a-2', 'a-1');
      expect(fixture.nativeElement.querySelector('.dups')).toBeNull();
    });

    it('un échec remet la paire et le dit', () => {
      build(f180Board([keep], [dup]), s => {
        s.duplicates.and.returnValue(of([pair]));
        s.notDuplicate.and.returnValue(throwError(() => new Error('boom')));
      });
      component.notDuplicate(pair);
      fixture.detectChanges();
      expect(component.duplicates().length).toBe(1);
      expect(snackBar.open).toHaveBeenCalled();
    });
  });
});
