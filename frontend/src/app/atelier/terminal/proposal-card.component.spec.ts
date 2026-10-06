import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AtelierGovernanceProposal } from '../../core/models/atelier.models';
import { GovernanceProposalService } from '../../core/services/governance-proposal.service';
import { ProposalCardComponent, proposalBlock, proposalKindLabel } from './proposal-card.component';

describe('ProposalCardComponent (F-177 / SF-177-02)', () => {
  const proposal: AtelierGovernanceProposal = {
    proposalId: 'p-1',
    type: 'REGLE',
    scope: 'POSTE',
    name: 'Jira',
    path: 'GOUVERNANCE.md',
    reason: 'Demandé par le client',
    creates: false,
    diff: [
      { kind: 'CTX', text: 'Français.' },
      { kind: 'ADD', text: '## Jira' },
      { kind: 'ADD', text: 'Commentaires Jira courts.' },
    ],
    omittedLines: 0,
  };

  let fixture: ComponentFixture<ProposalCardComponent>;
  let service: jasmine.SpyObj<GovernanceProposalService>;

  function create(status: 'PENDING' | 'APPLIED' | 'REFUSED' = 'PENDING', readOnly = false): HTMLElement {
    service.get.and.returnValue(of({ id: 'p-1', status, type: 'REGLE', scope: 'POSTE', name: 'Jira', path: 'GOUVERNANCE.md' }));
    fixture = TestBed.createComponent(ProposalCardComponent);
    fixture.componentRef.setInput('proposal', proposal);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function buttons(el: HTMLElement): HTMLButtonElement[] {
    return Array.from(el.querySelectorAll('button'));
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('GovernanceProposalService', ['get', 'apply', 'refuse']);
    TestBed.configureTestingModule({
      imports: [ProposalCardComponent],
      providers: [{ provide: GovernanceProposalService, useValue: service }],
    });
  });

  it('montre le diff, la portée et le fichier, sans rien écrire', () => {
    const el = create();
    expect(el.textContent).toContain('Règle proposée · Jira');
    expect(el.textContent).toContain('Pour tout le poste');
    expect(el.textContent).toContain('GOUVERNANCE.md');
    expect(el.querySelectorAll('.proposal-card__line--add').length).toBe(2);
    expect(service.apply).not.toHaveBeenCalled();
    expect(buttons(el).map((b) => b.textContent?.trim())).toEqual(['Appliquer', 'Modifier', 'Refuser']);
  });

  it('[Appliquer] écrit via la gateway et montre « Appliqué »', () => {
    const el = create();
    service.apply.and.returnValue(of({ id: 'p-1', status: 'APPLIED', type: 'REGLE', scope: 'POSTE', name: 'Jira', path: 'GOUVERNANCE.md' }));
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(service.apply).toHaveBeenCalledWith('ws-1', 'p-1');
    expect(el.textContent).toContain('Appliqué');
    expect(buttons(el).length).toBe(0);
  });

  it('[Refuser] écarte sans écrire', () => {
    const el = create();
    service.refuse.and.returnValue(of({ id: 'p-1', status: 'REFUSED', type: 'REGLE', scope: 'POSTE', name: 'Jira', path: 'GOUVERNANCE.md' }));
    buttons(el)[2].click();
    fixture.detectChanges();
    expect(service.refuse).toHaveBeenCalledWith('ws-1', 'p-1');
    expect(service.apply).not.toHaveBeenCalled();
    expect(el.textContent).toContain('Refusé');
  });

  it('[Modifier] émet le nom pour l’amorce de saisie, sans appel', () => {
    const el = create();
    let emitted: string | null = null;
    fixture.componentInstance.modify.subscribe((name) => (emitted = name));
    buttons(el)[1].click();
    expect(emitted as string | null).toBe('Jira');
    expect(service.apply).not.toHaveBeenCalled();
    expect(service.refuse).not.toHaveBeenCalled();
  });

  it('un conflit dit pourquoi et relit le statut', () => {
    const el = create();
    service.apply.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'GOUVERNANCE.md a changé depuis la proposition' } })),
    );
    buttons(el)[0].click();
    fixture.detectChanges();
    expect(el.textContent).toContain('a changé depuis la proposition');
    expect(service.get).toHaveBeenCalledTimes(2);
  });

  it('une proposition déjà appliquée ne montre plus de bouton', () => {
    const el = create('APPLIED');
    expect(buttons(el).length).toBe(0);
    expect(el.textContent).toContain('Appliqué');
  });

  it('lecture seule : aucun bouton', () => {
    const el = create('PENDING', true);
    expect(buttons(el).length).toBe(0);
  });

  it('bloc et libellés', () => {
    expect(proposalBlock('t-1', proposal).proposal).toBe(proposal);
    expect(proposalKindLabel('SKILL')).toBe('Skill');
    expect(proposalKindLabel('GABARIT')).toBe('Gabarit');
  });
});
