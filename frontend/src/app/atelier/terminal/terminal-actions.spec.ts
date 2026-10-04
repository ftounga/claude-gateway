import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { TerminalActionsPanelComponent, ageLabel } from './terminal-actions-panel.component';
import {
  TerminalAttentesBandComponent, bandSummary, oldestLabel, pendingProposals,
} from './terminal-attentes-band.component';
import { TerminalAction, TerminalActionBoard } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

function action(id: string, description: string, over: Partial<TerminalAction> = {}): TerminalAction {
  return {
    id, workspaceId: 'w-1', subjectId: null, description, blocks: null, person: null,
    kind: 'ACTION', status: 'A_FAIRE', closedReason: null, closedAt: null,
    createdAt: '2026-09-20T08:00:00Z', ...over,
  };
}

function board(here: TerminalAction[], host: TerminalAction[] = [], hostId: string | null = 'h-1'): TerminalActionBoard {
  const all = [...here, ...host];
  return {
    hostId, here, host,
    aFaire: all.filter(a => a.status === 'A_FAIRE').length,
    demande: all.filter(a => a.status === 'DEMANDE').length,
    oldestOpenAt: all.length ? '2026-09-25T08:00:00Z' : null,
  };
}

const METHODS: (keyof TerminalActionsService)[] = [
  'list', 'elsewhere', 'board', 'create', 'changeStatus', 'edit', 'confirmProposal',
  'dismissProposal', 'close', 'cancel', 'reopen',
];

/**
 * **Le fil des attentes à l'écran** (F-175 / SF-175-04, évolution de F-154 / SF-154-03) : la bande
 * au-dessus de la saisie, relue à chaque fin de tour ; le panneau trois colonnes, ses gestes, et un
 * écran qui ne ment jamais quand un appel échoue.
 */
describe('Les attentes du terminal (F-175 / SF-175-04)', () => {

  describe('la bande et la pastille dans le terminal', () => {
    let fixture: ComponentFixture<AtelierTerminalComponent>;
    let service: jasmine.SpyObj<TerminalActionsService>;

    function build(b: TerminalActionBoard | 'error'): void {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService', METHODS);
      service.board.and.returnValue(b === 'error' ? throwError(() => new Error('réseau')) : of(b));
      TestBed.configureTestingModule({
        imports: [AtelierTerminalComponent, NoopAnimationsModule],
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
          { provide: TerminalActionsService, useValue: service }],
      });
      fixture = TestBed.createComponent(AtelierTerminalComponent);
      fixture.componentInstance.projectName = 'mon-projet';
      fixture.componentInstance.projectId = 'w-1';
      fixture.detectChanges();
    }

    afterEach(() => TestBed.resetTestingModule());

    it("n'affiche RIEN quand il n'y a rien en attente", () => {
      build(board([]));
      expect(fixture.componentInstance.pendingActions()).toBe(0);
      expect(fixture.nativeElement.querySelector('.terminal-todo')).toBeNull();
      expect(fixture.nativeElement.querySelector('.attentes-band')).toBeNull();
    });

    it('la bande annonce le poste au-dessus de la saisie, et ouvre le panneau au clic', () => {
      build(board([action('a-1', 'Demander l’accès VPN')],
        [action('a-2', 'Relancer Zahi', { workspaceId: 'w-2', status: 'DEMANDE' })]));

      const band: HTMLButtonElement = fixture.nativeElement.querySelector('.attentes-band');
      expect(band).not.toBeNull();
      expect(band.textContent).toContain('1 à faire');
      expect(band.textContent).toContain('1 demandée');
      expect(fixture.nativeElement.querySelector('.terminal-todo').textContent).toContain('2 à faire');

      band.click();
      fixture.detectChanges();
      expect(fixture.componentInstance.actionsOpen()).toBeTrue();
      expect(fixture.nativeElement.querySelector('app-terminal-actions-panel')).not.toBeNull();
    });

    it('le tableau est RELU à chaque fin de tour — une attente inscrite pendant le tour apparaît', fakeAsync(() => {
      build(board([]));
      expect(service.board).toHaveBeenCalledTimes(1);

      service.board.and.returnValue(of(board([action('a-1', 'Obtenir la validation du RSSI')])));
      fixture.componentInstance.submitting = true;
      fixture.componentInstance.submitting = false;
      flushMicrotasks();
      fixture.detectChanges();

      expect(service.board).toHaveBeenCalledTimes(2);
      expect(fixture.nativeElement.querySelector('.attentes-band').textContent).toContain('1 à faire');
    }));

    it('SF-175-06 : « Relancer » dépose le brouillon dans la saisie et ferme le panneau, sans envoyer', () => {
      build(board([action('a-1', 'x')]));
      const drafts: string[] = [];
      fixture.componentInstance.draftChange.subscribe((d: string) => drafts.push(d));
      fixture.componentInstance.openActions();

      fixture.componentInstance.onFollowUp('Relance Zahi au sujet de « x »');

      expect(drafts).toEqual(['Relance Zahi au sujet de « x »']);
      expect(fixture.componentInstance.actionsOpen()).toBeFalse();
    });

    it("un chargement en échec n'annonce rien — mieux vaut rien qu'un chiffre faux", () => {
      build('error');
      expect(fixture.componentInstance.pendingActions()).toBe(0);
      expect(fixture.nativeElement.querySelector('.attentes-band')).toBeNull();
    });
  });

  describe('le résumé de la bande', () => {
    const now = Date.parse('2026-10-04T12:00:00Z');

    it('dit à faire, demandées et la plus ancienne', () => {
      const b = { ...board([]), aFaire: 2, demande: 4, oldestOpenAt: '2026-09-25T10:00:00Z' };
      expect(bandSummary(b, now)).toBe('2 à faire · 4 demandées · la plus ancienne 9 j');
      expect(bandSummary({ ...b, aFaire: 0, demande: 1 }, now)).toBe('1 demandée · la plus ancienne 9 j');
      expect(bandSummary({ ...b, aRelancer: 1 }, now))
        .toBe('2 à faire · 4 demandées · 1 à relancer · la plus ancienne 9 j');
      expect(oldestLabel('2026-10-04T08:00:00Z', now)).toBe("aujourd'hui");
      expect(bandSummary(null)).toBe('');
    });

    it('compte les fermetures proposées encore ouvertes', () => {
      const b = board([action('a-1', 'x', { proposedStatus: 'FAIT' }),
        action('a-2', 'y', { proposedStatus: 'FAIT', status: 'FAIT' })]);
      expect(pendingProposals(b)).toBe(1);
    });

    it('la bande montre « à confirmer » quand l’agent a proposé une fermeture', () => {
      TestBed.configureTestingModule({ imports: [TerminalAttentesBandComponent] });
      const f = TestBed.createComponent(TerminalAttentesBandComponent);
      f.componentRef.setInput('board', board([action('a-1', 'x', { proposedStatus: 'FAIT' })]));
      f.detectChanges();
      expect(f.nativeElement.textContent).toContain('1 à confirmer');
      TestBed.resetTestingModule();
    });
  });

  describe('le panneau', () => {
    let fixture: ComponentFixture<TerminalActionsPanelComponent>;
    let component: TerminalActionsPanelComponent;
    let service: jasmine.SpyObj<TerminalActionsService>;
    let snackBar: jasmine.SpyObj<MatSnackBar>;

    function build(b: TerminalActionBoard | 'error'): void {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService', METHODS);
      service.board.and.returnValue(b === 'error' ? throwError(() => new Error('boom')) : of(b));
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

    it('trois colonnes : À faire · Demandé · Fait récemment', () => {
      build(board([
        action('a-1', 'Demander l’accès VPN à Karim', { blocks: 'le déploiement', person: 'Karim' }),
        action('a-2', 'Obtenir le compte forge', { status: 'DEMANDE', requestedTo: 'Zahi', channel: 'Teams',
          requestedAt: '2026-09-30T08:00:00Z' }),
        action('a-3', 'Valider le budget', { status: 'FAIT', closedReason: 'Habib a signé',
          closedAt: '2026-10-03T08:00:00Z' }),
      ]));

      const columns = fixture.nativeElement.querySelectorAll('.column');
      expect(columns.length).toBe(3);
      expect(columns[0].textContent).toContain('À faire');
      expect(columns[0].textContent).toContain('bloque : le déploiement');
      expect(columns[1].textContent).toContain('demandé à Zahi par Teams');
      expect(columns[2].textContent).toContain('Habib a signé');
      expect(columns[2].textContent).toContain('Rétablir');
    });

    it('« Tout le poste » montre aussi les attentes nées ailleurs, avec leur terminal', () => {
      build(board([action('a-1', 'Ici')],
        [action('a-9', 'Valider le RSSI', { workspaceId: 'w-2', workspaceName: 'AGENOR' })]));
      expect(fixture.nativeElement.textContent).not.toContain('Valider le RSSI');

      component.scope.set('host');
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Valider le RSSI');
      expect(fixture.nativeElement.textContent).toContain('né dans « AGENOR »');
    });

    it('« Demandé » passe par la route du terminal de l’attente, puis relit le tableau', () => {
      const elsewhere = action('a-9', 'Valider le RSSI', { workspaceId: 'w-2', workspaceName: 'AGENOR' });
      build(board([], [elsewhere]));
      service.changeStatus.and.returnValue(of({ ...elsewhere, status: 'DEMANDE' }));

      component.move(elsewhere, 'DEMANDE');

      expect(service.changeStatus).toHaveBeenCalledWith('w-2', 'a-9', { status: 'DEMANDE' });
      expect(service.board).toHaveBeenCalledTimes(2);
    });

    it("un échec REMET l'écran dans l'état d'avant, et le dit", () => {
      const a = action('a-1', 'Demander l’accès VPN');
      build(board([a]));
      service.changeStatus.and.returnValue(throwError(() => new Error('réseau')));

      component.move(a, 'FAIT');
      fixture.detectChanges();

      expect(component.columns()[0].items.length).toBe(1);
      expect(component.columns()[2].items.length).toBe(0);
      expect(snackBar.open).toHaveBeenCalled();
    });

    it('une fermeture proposée se confirme ou s’écarte — rien ne sort sans le geste', () => {
      const a = action('a-1', 'Obtenir l’accès VPN',
        { proposedStatus: 'FAIT', proposedReason: 'Karim a ouvert l’accès' });
      build(board([a]));
      expect(fixture.nativeElement.textContent).toContain('L\'agent pense que c\'est réglé');
      expect(fixture.nativeElement.textContent).toContain('Karim a ouvert l’accès');

      service.dismissProposal.and.returnValue(of({ ...a, proposedStatus: null }));
      component.dismiss(a);
      expect(service.dismissProposal).toHaveBeenCalledWith('w-1', 'a-1');

      service.confirmProposal.and.returnValue(of({ ...a, status: 'FAIT', proposedStatus: null }));
      component.confirm(a);
      expect(service.confirmProposal).toHaveBeenCalledWith('w-1', 'a-1');
    });

    it('Rétablir, ajouter, éditer', () => {
      const closed = action('a-1', 'Relancer le support', { status: 'ANNULE', closedAt: '2026-10-03T08:00:00Z' });
      build(board([closed]));
      service.reopen.and.returnValue(of({ ...closed, status: 'A_FAIRE' }));
      component.reopen(closed);
      expect(service.reopen).toHaveBeenCalledWith('w-1', 'a-1');

      service.create.and.returnValue(of(action('a-2', 'Obtenir la dérogation')));
      component.newDescription = 'Obtenir la dérogation';
      component.add();
      expect(service.create).toHaveBeenCalledWith('w-1', { description: 'Obtenir la dérogation', person: null });

      const open = action('a-3', 'Demander');
      service.edit.and.returnValue(of({ ...open, description: 'Demander le VPN' }));
      component.startEdit(open);
      component.editDescription = 'Demander le VPN';
      component.saveEdit(open);
      expect(service.edit).toHaveBeenCalledWith('w-1', 'a-3', { description: 'Demander le VPN' });
    });

    it('SF-175-06 : « Relancer » n’apparaît que si la relance est due, et émet un brouillon — rien n’est envoyé', () => {
      const late = action('a-1', 'Obtenir le compte forge', { status: 'DEMANDE', requestedTo: 'Zahi',
        channel: 'Teams', requestedAt: '2026-09-30T08:00:00Z', followUpDue: true });
      const fresh = action('a-2', 'Obtenir la dérogation', { status: 'DEMANDE' });
      build(board([late, fresh]));

      const buttons = fixture.nativeElement.querySelectorAll('.action__followup');
      expect(buttons.length).toBe(1);
      expect(fixture.nativeElement.textContent).toContain('à relancer');

      let draft = '';
      component.followUp.subscribe((text: string) => (draft = text));
      (buttons[0] as HTMLButtonElement).click();
      expect(draft).toContain('Relance Zahi au sujet de « Obtenir le compte forge »');
      expect(draft).toContain('le 30/09 par Teams');
      expect(service.changeStatus).not.toHaveBeenCalled();
    });

    it('le chargement en échec le dit, sans détail technique', () => {
      build('error');
      expect(fixture.nativeElement.textContent).toContain("n'ont pas pu être chargées");
      expect(fixture.nativeElement.textContent).not.toContain('boom');
    });
  });

  describe("l'âge, qui est le signal qui fait agir", () => {
    const now = Date.parse('2026-09-24T12:00:00Z');

    it('dit les minutes, les heures, hier, puis les jours', () => {
      expect(ageLabel('2026-09-24T11:58:00Z', now)).toBe('il y a 2 min');
      expect(ageLabel('2026-09-24T08:00:00Z', now)).toBe('il y a 4 h');
      expect(ageLabel('2026-09-23T12:00:00Z', now)).toBe('hier');
      expect(ageLabel('2026-09-20T12:00:00Z', now)).toBe('il y a 4 jours');
      expect(ageLabel('pas une date', now)).toBe('');
    });
  });

});
