import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { TerminalPeopleViewComponent } from './terminal-people-view.component';
import {
  Interlocuteur, InterlocuteurBoardRow, InterlocuteurMergeSuggestion, TerminalAction, daysLabel, groupedFollowUpDraft,
} from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { mockTerminalActionsService } from '../../core/services/terminal-actions.service.testing';

function person(id: string, displayName: string, over: Partial<Interlocuteur> = {}): Interlocuteur {
  return { id, displayName, role: null, team: null, radarPersonId: null, radarName: null, variants: [],
    openCount: 0, ...over };
}

/** La vue « Personnes » (F-180 / SF-180-03). */
describe('La vue Personnes (F-180 / SF-180-03)', () => {
  let fixture: ComponentFixture<TerminalPeopleViewComponent>;
  let component: TerminalPeopleViewComponent;
  let service: jasmine.SpyObj<TerminalActionsService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function build(setup: (s: jasmine.SpyObj<TerminalActionsService>) => void): void {
    service = mockTerminalActionsService();
    setup(service);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [TerminalPeopleViewComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: TerminalActionsService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar }],
    });
    fixture = TestBed.createComponent(TerminalPeopleViewComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('liste les interlocuteurs, leur rôle, le Radar et leurs attentes', () => {
    build(s => s.interlocuteurs.and.returnValue(of([
      person('i-1', 'Rémi Dupont', { role: 'Réseau', team: 'Infra', radarPersonId: 'r-1', radarName: 'Rémi D.',
        openCount: 11 }),
    ])));
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Rémi Dupont');
    expect(text).toContain('11 attentes');
    expect(text).toContain('Réseau · Infra');
    expect(text).toContain('dans le Radar (Rémi D.)');
  });

  it('propose les fusions ; [Fusionner] garde le plus complet et prévient le panneau', () => {
    const s: InterlocuteurMergeSuggestion = {
      keep: person('i-1', 'Rémi Dupont'), merge: person('i-2', 'Rémi'), reason: 'PREFIX',
    };
    build(sv => {
      sv.interlocuteurs.and.returnValue(of([s.keep, s.merge]));
      sv.interlocuteurSuggestions.and.returnValue(of([s]));
      sv.mergeInterlocuteur.and.returnValue(of(s.keep));
    });
    expect(fixture.nativeElement.querySelector('.people-merge').textContent).toContain('un nom prolonge l’autre');
    const changed: void[] = [];
    component.changed.subscribe(() => changed.push(undefined));
    sv_merge();
    expect(service.mergeInterlocuteur).toHaveBeenCalledWith('w-1', 'i-2', 'i-1');
    expect(changed.length).toBe(1);

    function sv_merge(): void {
      component.merge(s);
      fixture.detectChanges();
    }
  });

  it('« deux personnes » : la proposition disparaît ; un échec la remet et le dit', () => {
    const s: InterlocuteurMergeSuggestion = {
      keep: person('i-1', 'Rémi Dupont'), merge: person('i-2', 'Rémi Martin'), reason: 'TYPO',
    };
    build(sv => {
      sv.interlocuteurSuggestions.and.returnValue(of([s]));
      sv.distinctInterlocuteurs.and.returnValue(throwError(() => new Error('boom')));
    });
    component.distinct(s);
    expect(component.suggestions().length).toBe(1);
    expect(snackBar.open).toHaveBeenCalled();
  });

  describe('SF-180-05 : qui bloque quoi', () => {
    const open: TerminalAction[] = Array.from({ length: 11 }, (_, i) => ({
      id: `a-${i}`, workspaceId: 'w-1', workspaceName: 'data-platform', subjectId: null,
      description: `Attente ${i}`, blocks: i === 0 ? 'le déploiement' : null, person: 'Rémi', kind: 'ACTION',
      status: i < 3 ? 'DEMANDE' : 'A_FAIRE', requestedAt: i < 3 ? '2026-10-10T08:00:00Z' : null,
      closedReason: null, closedAt: null, createdAt: '2026-10-07T08:00:00Z',
    } as TerminalAction));
    const row: InterlocuteurBoardRow = {
      interlocuteurId: 'i-1', name: 'Rémi Dupont', role: 'Réseau', openCount: 11, demandeCount: 3,
      oldestOpenAt: '2026-10-07T08:00:00Z', subjects: ['data-platform', 'connecteur'], avgResponseDays: 4,
      responseCount: 6, avgByCategorie: { ACCES: 5.1 }, open,
    };

    it('Rémi : 11 attentes, la plus ancienne, les sujets, son délai moyen ; le délai par nature', () => {
      build(s => s.interlocuteurBoard.and.returnValue(of({
        people: [row], byCategorie: [{ categorie: 'ACCES', avgResponseDays: 4.3, responseCount: 12, openCount: 12 }],
      })));
      const section: HTMLElement = fixture.nativeElement.querySelector('.board');
      expect(section.textContent).toContain('Rémi Dupont');
      expect(section.textContent).toContain('11 attentes');
      expect(section.textContent).toContain('bloque data-platform, connecteur');
      expect(section.textContent).toContain('Rémi répond en 4 j en moyenne');
      expect(section.textContent).toContain('Relancer Rémi (11)');
      expect(fixture.nativeElement.querySelector('.people__measure').textContent).toContain('Accès : 4,3 j');
    });

    it('[Relancer] émet UN brouillon groupé, sans aucun appel au serveur', () => {
      build(s => s.interlocuteurBoard.and.returnValue(of({ people: [row], byCategorie: [] })));
      const drafts: string[] = [];
      component.followUp.subscribe(d => drafts.push(d));
      fixture.nativeElement.querySelector('.board__followup').click();
      expect(drafts.length).toBe(1);
      expect(drafts[0]).toContain('Relance groupée pour Rémi Dupont — 11 attentes');
      expect(drafts[0]).toContain('- Attente 0 (demandé il y a');
      expect(drafts[0]).toContain('débloque : le déploiement');
      expect(drafts[0]).toContain('UN seul message');
      expect(service.changeStatus).not.toHaveBeenCalled();
    });

    it('le brouillon par courriel demande à l’agent de me l’envoyer ; au-delà de 30, « et N autres »', () => {
      const many = { name: 'Rémi', open: Array.from({ length: 33 }, (_, i) => ({ ...open[0], id: `x-${i}` })) };
      const draft = groupedFollowUpDraft(many, true, Date.parse('2026-10-16T08:00:00Z'));
      expect(draft).toContain('email_me');
      expect(draft).toContain('… et 3 autre(s)');
      expect(draft.split('\n').filter(l => l.startsWith('- Attente')).length).toBe(30);
      expect(daysLabel(1.5)).toBe('1,5 j');
      expect(daysLabel(0.2)).toBe("moins d'un jour");
    });
  });

  it('modifier : rôle et équipe enregistrés', () => {
    const p = person('i-1', 'Rémi');
    build(sv => {
      sv.interlocuteurs.and.returnValue(of([p]));
      sv.updateInterlocuteur.and.returnValue(of({ ...p, role: 'Réseau' }));
    });
    component.startEdit(p);
    component.editRole = 'Réseau';
    component.save(p);
    expect(service.updateInterlocuteur).toHaveBeenCalledWith('w-1', 'i-1',
      { displayName: 'Rémi', role: 'Réseau', team: '' });
    expect(component.people()[0].role).toBe('Réseau');
  });
});
