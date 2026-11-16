import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';

import { GovernanceEffective } from '../../core/models/governance.models';
import { GovernanceService } from '../../core/services/governance.service';
import { GovernanceEffectiveComponent, agentCapabilityLabel, rulesStateLabel } from './governance-effective.component';

describe('GovernanceEffectiveComponent (F-177 / SF-177-04)', () => {
  const view: GovernanceEffective = {
    hostRef: 'h-1',
    hostRules: { state: 'PRESENT', excerpt: '## Jira\n\nCommentaires Jira courts.', truncated: false },
    subjects: [{ workspaceId: 'w-1', name: 'data-platform', rules: { state: 'ABSENT', excerpt: null, truncated: false } }],
    skills: [
      { name: 'ticket-jira', path: '.claude/skills/ticket-jira.md', origin: 'POSTE', subjectName: null, source: 'CLIENT', packageName: null },
      { name: 'explique', path: '.claude/skills/explique.md', origin: 'SUJET', subjectName: 'data-platform', source: 'PAQUET', packageName: 'Le savoir durable' },
    ],
    packages: [
      { packageId: 'p-1', name: 'Le savoir durable', packageVersion: 17, filesMinVersion: 6, filesMaxVersion: 6, depositedFiles: 4, state: 'EN_RETARD', message: 'En retard : fichiers en v6, paquet en v17.' },
      { packageId: 'p-2', name: 'Livrables', packageVersion: 3, filesMinVersion: null, filesMaxVersion: null, depositedFiles: 0, state: 'JAMAIS_DEPOSE', message: 'Activé, jamais déposé sur ce poste : aucun fichier n’y a été écrit.' },
      { packageId: 'p-3', name: 'Commits', packageVersion: 2, filesMinVersion: 2, filesMaxVersion: 2, depositedFiles: 1, state: 'A_JOUR', message: 'À jour : fichiers en v2.' },
    ],
    agents: [
      { name: 'relecteur', path: '.claude/agents/relecteur.md', origin: 'POSTE', subjectName: null, source: 'PAQUET', packageName: 'Le savoir durable', description: 'Relit un diff avant une MR.', access: 'LECTURE', model: null, warnings: [], errors: [], usable: true },
      { name: 'testeur', path: '.claude/agents/testeur.md', origin: 'SUJET', subjectName: 'data-platform', source: 'CLIENT', packageName: null, description: '', access: 'ECRITURE', model: null, warnings: ['Modèle « claude-haiku-5 » ignoré faute de « justification »'], errors: [], usable: true },
      { name: 'vide', path: '.claude/agents/vide.md', origin: 'SUJET', subjectName: 'data-platform', source: 'CLIENT', packageName: null, description: '', access: 'LECTURE', model: null, warnings: [], errors: ['Consigne vide'], usable: false },
    ],
  };

  let fixture: ComponentFixture<GovernanceEffectiveComponent>;
  let service: jasmine.SpyObj<GovernanceService>;
  let confirmed = true;
  const dialog = { open: jasmine.createSpy('open').and.callFake(() => ({ afterClosed: () => of(confirmed) })) };

  beforeEach(() => {
    service = jasmine.createSpyObj('GovernanceService', [
      'getEffective',
      'apply',
      'listAccountRules',
      'deleteAccountRule',
      'promoteHostRule',
    ]);
    service.getEffective.and.returnValue(of(view));
    service.listAccountRules.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [GovernanceEffectiveComponent],
      providers: [
        { provide: GovernanceService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        provideNoopAnimations(),
      ],
    });
    fixture = TestBed.createComponent(GovernanceEffectiveComponent);
    fixture.componentRef.setInput('hostRef', 'h-1');
    fixture.detectChanges();
    fixture.detectChanges();
  });

  it('dit le retard de dépôt et le « jamais déposé »', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('fichiers en v6, paquet en v17');
    expect(text).toContain('jamais déposé');
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['Remettre à jour', 'Déposer']);
  });

  it('montre les règles du poste et les skills avec leur origine', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Commentaires Jira courts.');
    expect(text).toContain('Aucun fichier GOUVERNANCE.md');
    expect(text).toContain('/ticket-jira');
    expect(text).toContain('paquet Le savoir durable');
    expect(text).toContain('client');
  });

  it('[Remettre à jour] rejoue le dépôt existant et prévient le parent', () => {
    service.apply.and.returnValue(of({} as unknown as import("../../core/models/governance.models").GovernanceDepositPlan));
    let changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);
    ((fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement).click();
    expect(service.apply).toHaveBeenCalledWith('h-1', 'p-1');
    expect(changed).toBe(1);
    expect(service.getEffective).toHaveBeenCalledTimes(2);
  });

  it('F-182 : montre les agents nommés, leur capacité et leurs problèmes', () => {
    const element = fixture.nativeElement as HTMLElement;
    const text = element.textContent ?? '';
    expect(text).toContain('Agents nommés (3)');
    expect(text).toContain('/agent relecteur');
    expect(text).toContain('Relit un diff avant une MR.');
    expect(text).toContain('lecture seule · modèle de la Forge');
    expect(text).toContain('lit, écrit et exécute');
    expect(text).toContain('ignoré faute de « justification »');
    expect(text).toContain('Consigne vide');
    expect(element.querySelectorAll('.effective__agent--unusable').length).toBe(1);
  });

  it('F-182 : sans agent, dit où en déclarer un', () => {
    service.getEffective.and.returnValue(of({ ...view, agents: undefined }));
    fixture.componentRef.setInput('hostRef', 'h-2');
    fixture.detectChanges();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Agents nommés (0)');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('.claude/agents/<nom>.md');
  });

  it('F-182 : le libellé de capacité dit le modèle écrit', () => {
    expect(agentCapabilityLabel({ ...view.agents![0], model: 'claude-sonnet-5' })).toBe('lecture seule · modèle claude-sonnet-5');
  });

  it('libellés des états', () => {
    expect(rulesStateLabel({ state: 'INJOIGNABLE', excerpt: null, truncated: false })).toContain('injoignable');
    expect(rulesStateLabel({ state: 'INCONNU', excerpt: null, truncated: false })).toContain('Pas encore lu');
  });

  describe('F-266 : tous mes postes', () => {
    const rule = { id: 'r-1', title: 'Style des preuves', content: 'Fond navy #0B1020.' };

    function reload(effective: GovernanceEffective, rules: { id: string; title: string; content: string }[]): HTMLElement {
      service.getEffective.and.returnValue(of(effective));
      service.listAccountRules.and.returnValue(of(rules));
      fixture.componentRef.setInput('hostRef', 'h-3');
      fixture.detectChanges();
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    function button(el: HTMLElement, label: string): HTMLButtonElement {
      return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
    }

    it('sans règle, le dit', () => {
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Aucune règle sur tous vos postes');
    });

    it('liste les règles et [Retirer] la retire après confirmation', () => {
      const el = reload(view, [rule]);
      expect(el.textContent).toContain('Style des preuves');
      expect(el.textContent).toContain('Fond navy #0B1020.');
      confirmed = true;
      service.deleteAccountRule.and.returnValue(of(undefined));
      button(el, 'Retirer').click();
      fixture.detectChanges();
      expect(service.deleteAccountRule).toHaveBeenCalledWith('r-1');
      expect(el.textContent).toContain('Aucune règle sur tous vos postes');
    });

    it('sans confirmation, ne retire rien', () => {
      const el = reload(view, [rule]);
      confirmed = false;
      button(el, 'Retirer').click();
      expect(dialog.open).toHaveBeenCalled();
      expect(service.deleteAccountRule).not.toHaveBeenCalled();
    });

    it('[Valable sur tous mes postes] recopie la règle du poste et relit la liste', () => {
      const el = reload({ ...view, hostRuleTitles: ['Jira', 'Style des preuves'] }, []);
      service.promoteHostRule.and.returnValue(of({ id: 'r-2', title: 'Jira', content: 'x' }));
      service.listAccountRules.and.returnValue(of([{ id: 'r-2', title: 'Jira', content: 'x' }]));
      button(el, 'Valable sur tous mes postes').click();
      fixture.detectChanges();
      expect(service.promoteHostRule).toHaveBeenCalledWith('h-3', 'Jira');
      expect(el.textContent).toContain('Déjà sur tous mes postes');
    });

    it('une règle déjà recopiée se met à jour', () => {
      const el = reload({ ...view, hostRuleTitles: ['style des PREUVES'] }, [rule]);
      expect(el.textContent).toContain('Déjà sur tous mes postes');
      expect(button(el, 'Mettre à jour')).toBeTruthy();
    });
  });
});
