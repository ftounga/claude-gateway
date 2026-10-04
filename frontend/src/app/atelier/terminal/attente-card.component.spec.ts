import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AttenteCardComponent, attenteBlock, attenteDetail, attenteHeadline } from './attente-card.component';
import { AtelierService } from '../../core/services/atelier.service';
import { AtelierTerminalAttente } from '../../core/models/atelier.models';
import { TerminalAction } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

function attente(over: Partial<AtelierTerminalAttente> = {}): AtelierTerminalAttente {
  return {
    actionId: 'a-1', workspaceId: 'w-2', kind: 'ADDED', match: 'NONE', description: 'Demander le VPN',
    status: 'A_FAIRE', requestedTo: null, requestedAt: null, channel: null, proposedStatus: null,
    proposedReason: null, createdAt: '2026-10-01T08:00:00Z', ...over,
  };
}

function live(over: Partial<TerminalAction> = {}): TerminalAction {
  return {
    id: 'a-1', workspaceId: 'w-2', subjectId: null, description: 'Demander le VPN', blocks: null, person: null,
    kind: 'ACTION', status: 'A_FAIRE', closedReason: null, closedAt: null, createdAt: '2026-10-01T08:00:00Z',
    ...over,
  };
}

/**
 * **Les cartes du fil** (F-175 / SF-175-05, décision D7) : inscription, doublon, demandé, et la
 * fermeture proposée [Confirmer] [Pas encore] — qui ne s'offre que tant qu'elle attend un geste.
 */
describe('Les cartes d’attente du fil (F-175 / SF-175-05)', () => {

  describe('les textes', () => {
    it('disent ce qui s’est passé', () => {
      expect(attenteHeadline(attente())).toBe('Ajouté à tes attentes');
      expect(attenteHeadline(attente({ kind: 'ALREADY', status: 'DEMANDE' }))).toBe('Déjà demandé');
      expect(attenteHeadline(attente({ kind: 'ALREADY', match: 'MEANING' })))
        .toBe('Une attente dit déjà la même chose');
      expect(attenteHeadline(attente({ kind: 'PROPOSED', proposedStatus: 'FAIT' }))).toBe('Je pense que c\'est réglé');
      expect(attenteHeadline(attente({ kind: 'REQUESTED', status: 'DEMANDE' }))).toBe('Marqué demandé');
    });

    it('détaillent à qui, quand et par où la demande est partie', () => {
      expect(attenteDetail(attente({ kind: 'ALREADY', status: 'DEMANDE', requestedTo: 'Zahi',
        requestedAt: '2026-09-30T10:00:00Z', channel: 'Teams' }))).toBe('demandé à Zahi le 30/09 par Teams');
      expect(attenteDetail(attente({ kind: 'ALREADY', match: 'KEY_ON_HOST' })))
        .toBe('née dans un autre terminal du poste');
    });

    it('le bloc de transcription porte la carte', () => {
      expect(attenteBlock('tu_1', attente()).attente?.actionId).toBe('a-1');
    });
  });

  describe('la carte', () => {
    let fixture: ComponentFixture<AttenteCardComponent>;
    let service: jasmine.SpyObj<TerminalActionsService>;
    let snackBar: jasmine.SpyObj<MatSnackBar>;

    function build(a: AtelierTerminalAttente, l: TerminalAction | null | undefined, readOnly = false): void {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService',
        ['confirmProposal', 'dismissProposal']);
      snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
      TestBed.configureTestingModule({
        imports: [AttenteCardComponent, NoopAnimationsModule],
        providers: [{ provide: TerminalActionsService, useValue: service },
          { provide: MatSnackBar, useValue: snackBar }],
      });
      fixture = TestBed.createComponent(AttenteCardComponent);
      fixture.componentRef.setInput('attente', a);
      fixture.componentRef.setInput('live', l);
      fixture.componentRef.setInput('readOnly', readOnly);
      fixture.detectChanges();
    }

    afterEach(() => TestBed.resetTestingModule());

    const proposed = attente({ kind: 'PROPOSED', proposedStatus: 'FAIT', proposedReason: 'Karim a ouvert l’accès' });

    it('une proposition encore en attente offre [Confirmer] [Pas encore], avec la parole citée', () => {
      build(proposed, live({ proposedStatus: 'FAIT' }));
      const text = fixture.nativeElement.textContent;
      expect(text).toContain('Je pense que c\'est réglé');
      expect(text).toContain('Karim a ouvert l’accès');
      expect(fixture.nativeElement.querySelector('.attente-card__confirm')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.attente-card__dismiss')).not.toBeNull();
    });

    it('[Confirmer] passe par la route du terminal de l’attente, et prévient le terminal', () => {
      build(proposed, live({ proposedStatus: 'FAIT' }));
      let changed = 0;
      fixture.componentInstance.changed.subscribe(() => changed++);
      service.confirmProposal.and.returnValue(of(live({ status: 'FAIT' })));

      fixture.nativeElement.querySelector('.attente-card__confirm').click();
      fixture.detectChanges();

      expect(service.confirmProposal).toHaveBeenCalledWith('w-2', 'a-1');
      expect(changed).toBe(1);
      expect(fixture.nativeElement.textContent).toContain('Confirmé');
      expect(fixture.nativeElement.querySelector('.attente-card__confirm')).toBeNull();
    });

    it('[Pas encore] laisse l’attente ouverte', () => {
      build(proposed, live({ proposedStatus: 'FAIT' }));
      service.dismissProposal.and.returnValue(of(live()));
      fixture.componentInstance.dismiss();
      fixture.detectChanges();
      expect(service.dismissProposal).toHaveBeenCalledWith('w-2', 'a-1');
      expect(fixture.nativeElement.textContent).toContain('Laissée ouverte');
    });

    it('une proposition déjà traitée (ou une attente disparue) n’offre plus de boutons', () => {
      build(proposed, live({ status: 'FAIT', proposedStatus: null }));
      expect(fixture.nativeElement.querySelector('.attente-card__confirm')).toBeNull();
      TestBed.resetTestingModule();
      build(proposed, null);
      expect(fixture.nativeElement.querySelector('.attente-card__confirm')).toBeNull();
    });

    it('en lecture seule, aucun geste', () => {
      build(proposed, live({ proposedStatus: 'FAIT' }), true);
      expect(fixture.nativeElement.querySelector('.attente-card__confirm')).toBeNull();
    });

    it('un échec le dit, et rend les boutons', () => {
      build(proposed, live({ proposedStatus: 'FAIT' }));
      service.confirmProposal.and.returnValue(throwError(() => new Error('réseau')));
      fixture.componentInstance.confirm();
      fixture.detectChanges();
      expect(snackBar.open).toHaveBeenCalled();
      expect(fixture.nativeElement.querySelector('.attente-card__confirm')).not.toBeNull();
    });

    it('une inscription n’a aucun bouton', () => {
      build(attente(), undefined);
      expect(fixture.nativeElement.textContent).toContain('Ajouté à tes attentes');
      expect(fixture.nativeElement.querySelector('button')).toBeNull();
    });
  });

  it("le flux relaie l'événement attente, et ignore un événement sans attente", () => {
    TestBed.configureTestingModule({ providers: [AtelierService, provideHttpClient(), provideHttpClientTesting()] });
    const atelier = TestBed.inject(AtelierService);
    const dispatch = (atelier as unknown as {
      dispatchSseEvent: (raw: string, handlers: object) => void;
    }).dispatchSseEvent.bind(atelier);
    const got: unknown[] = [];
    const handlers = {
      onAction: () => undefined, onText: () => undefined, onDone: () => undefined, onError: () => undefined,
      onAttente: (event: unknown) => got.push(event),
    };

    dispatch('event: attente\ndata: {"toolUseId":"tu_1","attente":{"actionId":"a-1","kind":"ADDED"}}', handlers);
    dispatch('event: attente\ndata: {"toolUseId":"tu_2"}', handlers);

    expect(got).toEqual([{ toolUseId: 'tu_1', attente: { actionId: 'a-1', kind: 'ADDED' } }]);
    TestBed.resetTestingModule();
  });
});
