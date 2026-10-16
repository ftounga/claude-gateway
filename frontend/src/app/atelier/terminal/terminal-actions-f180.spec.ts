import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { TerminalActionsPanelComponent } from './terminal-actions-panel.component';
import { bandSummary } from './terminal-attentes-band.component';
import { attenteNature } from './attente-card.component';
import {
  AccesObtenu, TerminalAction, TerminalActionBoard, TerminalActionDuplicate,
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

  describe('SF-180-04 : le registre des accès', () => {
    const done = f180Action('a-1', 'Demander l’accès VPN à Karim', { status: 'FAIT', categorie: 'ACCES' });
    const proposal = { action: done, system: 'VPN', grantedBy: 'Karim', obtainedAt: '2026-10-14' };

    it('« Ajouter au registre ? » propose l’accès obtenu ; [Ajouter] l’envoie avec son attente', () => {
      build(f180Board([done]), s => {
        s.accesProposals.and.returnValue(of([proposal]));
        s.addAcces.and.returnValue(of({ id: 'r-1', system: 'VPN' } as unknown as AccesObtenu));
      });
      const section: HTMLElement = fixture.nativeElement.querySelector('.registry');
      expect(section.textContent).toContain('Ajouter au registre ? (1)');
      expect(section.textContent).toContain('obtenu le 14/10 par Karim');
      component.addToRegistry(proposal);
      fixture.detectChanges();
      expect(service.addAcces).toHaveBeenCalledWith('w-1', {
        actionId: 'a-1', system: 'VPN', grantedBy: 'Karim', obtainedAt: '2026-10-14',
      });
      expect(fixture.nativeElement.querySelector('.registry')).toBeNull();
    });

    it('[Pas besoin] : la proposition disparaît ; la vue Accès s’ouvre depuis le panneau', () => {
      build(f180Board([done]), s => {
        s.accesProposals.and.returnValue(of([proposal]));
        s.dismissAccesProposal.and.returnValue(of(undefined));
      });
      component.dismissRegistry(proposal);
      expect(service.dismissAccesProposal).toHaveBeenCalledWith('w-1', 'a-1');
      component.view.set('acces');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-terminal-acces-view')).not.toBeNull();
    });
  });

  describe('SF-180-05 : la relance groupée passe par le panneau', () => {
    it('le brouillon de la vue Personnes ressort du panneau tel quel (le terminal le dépose, sans envoyer)', () => {
      build(f180Board([f180Action('a-1', 'x')]));
      const drafts: string[] = [];
      component.followUp.subscribe(d => drafts.push(d));
      component.view.set('people');
      fixture.detectChanges();
      const people = fixture.debugElement.query(By.css('app-terminal-people-view'));
      people.triggerEventHandler('followUp', 'Relance groupée pour Rémi — 2 attentes');
      expect(drafts).toEqual(['Relance groupée pour Rémi — 2 attentes']);
    });
  });

  describe('SF-180-03 : la vue Personnes', () => {
    it('le panneau d’un poste bascule sur les personnes ; la carte montre le nom normalisé', () => {
      build(f180Board([f180Action('a-1', 'Demander le VPN', { person: 'remi', interlocuteurName: 'Rémi Dupont' })]));
      expect(fixture.nativeElement.querySelector('.action__person').textContent).toContain('Rémi Dupont');
      component.view.set('people');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-terminal-people-view')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.columns')).toBeNull();
      expect(service.interlocuteurs).toHaveBeenCalledWith('w-1');
    });
  });

  describe('SF-180-02 : nature, qui agit, étape bloquée', () => {
    it('la carte porte sa nature, « à toi », et l’étape qu’elle bloque', () => {
      build(f180Board([f180Action('a-1', 'Se reconnecter en SSO', {
        categorie: 'ACCES', qui: 'MOI',
        blockedStep: { number: 3, title: 'Déployer', terminal: 'data-platform' },
      })]));
      const card: HTMLElement = fixture.nativeElement.querySelector('.action');
      expect(card.textContent).toContain('Accès');
      expect(card.querySelector('.tag--moi')?.textContent).toContain('à toi');
      expect(card.querySelector('.action__step')?.textContent)
        .toContain('bloque : étape 3 « Déployer » de data-platform');
    });

    it('la reprise propose une nature et ne l’écrit qu’à la validation', () => {
      const item = { action: f180Action('a-1', 'Demander le VPN'), suggestedCategorie: 'ACCES' as const,
        suggestedQui: 'CLIENT' as const };
      build(f180Board([item.action]), s => {
        s.qualify.and.returnValue(of([item]));
        s.applyQualify.and.returnValue(of({ applied: 1, ignored: 0 }));
      });
      expect(fixture.nativeElement.querySelector('.qualify').textContent).toContain('1 attente à qualifier');
      expect(service.applyQualify).not.toHaveBeenCalled();

      component.chooseNature('a-1', 'VALIDATION', 'TIERS');
      component.validateNature(component.qualifyRows());
      fixture.detectChanges();
      expect(service.applyQualify).toHaveBeenCalledWith([{ id: 'a-1', categorie: 'VALIDATION', qui: 'TIERS' }]);
      expect(fixture.nativeElement.querySelector('.qualify')).toBeNull();
    });

    it('la bande dit les gestes à toi ; la carte du fil dit la nature', () => {
      const b = { ...f180Board([f180Action('a-1', 'x')]), moi: 2 };
      expect(bandSummary(b)).toContain('2 gestes à toi');
      expect(attenteNature({ actionId: 'a', workspaceId: 'w', kind: 'ADDED', match: 'NONE', description: 'x',
        status: 'A_FAIRE', requestedTo: null, requestedAt: null, channel: null, proposedStatus: null,
        proposedReason: null, createdAt: null, categorie: 'ACCES', qui: 'MOI' })).toBe('Accès · geste à toi');
    });

    it('l’ajout à la main transmet la nature et qui agit', () => {
      build(f180Board([]), s => s.create.and.returnValue(of(f180Action('n-1', 'x'))));
      component.newDescription = 'Obtenir la signature';
      component.newCategorie = 'VALIDATION';
      component.newQui = 'CLIENT';
      component.add();
      expect(service.create).toHaveBeenCalledWith('w-1', jasmine.objectContaining({
        categorie: 'VALIDATION', qui: 'CLIENT',
      }));
    });
  });
});
