import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HostProjectSummary } from '../../core/models/atelier.models';
import { HostPresenceService } from '../../core/services/host-presence.service';
import { ForgeProjectTileComponent } from './forge-project-tile.component';

/** La tuile d'un projet (F-98 / SF-98-03) : un seul contenu central, et « Ouvrir ». */
describe('ForgeProjectTileComponent', () => {
  let fixture: ComponentFixture<ForgeProjectTileComponent>;

  const base: HostProjectSummary = {
    id: 'w1', name: 'security-assessment', projectPath: 'security-assessment', calls: 3, active: false,
    lastActivityAt: new Date(Date.now() - 5 * 60_000).toISOString(), lastTool: 'bash',
  };

  function render(project: HostProjectSummary, hosted = false): HTMLElement {
    TestBed.configureTestingModule({ imports: [ForgeProjectTileComponent] });
    fixture = TestBed.createComponent(ForgeProjectTileComponent);
    fixture.componentRef.setInput('project', project);
    fixture.componentRef.setInput('hosted', hosted);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('écrit le nom, le chemin sous la racine, et le dernier outil daté', () => {
    const root = render(base);

    expect(root.querySelector('.projet__name')?.textContent?.trim()).toBe('security-assessment');
    expect(root.querySelector('.projet__path')?.textContent?.trim()).toBe('security-assessment');
    expect(root.querySelector('.projet__activity')?.textContent?.trim()).toBe('bash · il y a 5 min');
  });

  it('nomme la racine quand le projet n’a pas de sous-dossier, et n’a pas de chemin s’il est hébergé', () => {
    expect(render({ ...base, projectPath: null }).querySelector('.projet__path')?.textContent?.trim())
      .toBe('la racine');
    TestBed.resetTestingModule();
    expect(render(base, true).querySelector('.projet__path')).toBeNull();
  });

  it('en attente : dit « Attend votre autorisation » et la commande, filet ambre, et rien d’autre au centre', () => {
    const root = render({
      ...base,
      liveTerminal: true,
      terminalPreview: { activity: 'AWAITING_APPROVAL', activityDetail: 'aws s3 ls', lines: ['$ aws s3 ls'] },
    });

    expect(root.querySelector('.projet')?.classList).toContain('projet--awaiting');
    expect(root.textContent).toContain('Attend votre autorisation');
    expect(root.textContent).toContain('aws s3 ls');
    expect(root.querySelector('.projet__quiet')).toBeNull();
    expect(root.querySelector('app-live-badge')).not.toBeNull();
  });

  it('avec un aperçu : les dernières lignes, sans filet ambre', () => {
    const root = render({
      ...base,
      terminalPreview: { activity: 'RUNNING', activityDetail: 'terraform plan', lines: ['Plan: 4 to add'] },
    });

    expect(root.querySelector('.projet')?.classList).not.toContain('projet--awaiting');
    expect(root.textContent).toContain('Plan: 4 to add');
    expect(root.querySelector('.projet__quiet')).toBeNull();
  });

  it('au repos : dit depuis quand, et la date avance avec l’horloge partagée, sans requête', () => {
    const root = render(base);
    const presence = TestBed.inject(HostPresenceService);

    expect(root.querySelector('.projet__quiet')?.textContent?.trim()).toBe('Au repos · dernier tour il y a 5 min');

    presence.now.set(presence.now() + 60 * 60_000);
    fixture.detectChanges();
    expect(root.querySelector('.projet__quiet')?.textContent?.trim()).toBe('Au repos · dernier tour il y a 1 h');
  });

  it('sans aucune activité connue : « Au repos · aucun tour », jamais « NaN »', () => {
    const root = render({ ...base, lastActivityAt: 'pas-une-date', lastTool: null });

    expect(root.textContent).toContain('Au repos · aucun tour');
    expect(root.textContent).not.toContain('NaN');
  });

  it('« Ouvrir » émet le projet', () => {
    const root = render(base);
    const opened: string[] = [];
    fixture.componentInstance.open.subscribe((project) => opened.push(project.id));

    (root.querySelector('.projet__open') as HTMLButtonElement).click();

    expect(opened).toEqual(['w1']);
    expect(root.querySelector('.projet__open')?.getAttribute('aria-label'))
      .toBe('Ouvrir le terminal de security-assessment');
  });
});
