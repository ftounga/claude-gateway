import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { SkillEntry, skillSuggestions } from '../../core/services/skill-catalog.service';

/** **Mes skills au bout du `/`** (F-177 / SF-177-03). */
describe('AtelierTerminalComponent — les skills au bout du / (F-177 / SF-177-03)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;

  const WORKSPACE = 'ws-1';
  const URL = `/api/workspaces/${WORKSPACE}/skills`;
  const catalog: SkillEntry[] = [
    { name: 'ticket-jira', path: '.claude/skills/ticket-jira.md', description: 'Rédige un ticket', origin: 'POSTE' },
    { name: 'deploy', path: '.claude/skills/deploy.md', description: '', origin: 'SUJET' },
    { name: 'revue', path: '.claude/skills/revue.md', description: 'doublon de macro', origin: 'SUJET' },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.projectId = WORKSPACE;
    component.isNarrow.set(false);
    drafts = [];
    sent = 0;
    component.draftChange.subscribe((value) => {
      drafts.push(value);
      component.draft = value;
    });
    component.send.subscribe(() => sent++);
    fixture.detectChanges();
    http.expectOne(URL).flush(catalog);
  });

  afterEach(() => {
    http.match((r) => r.url !== URL);
    http.verify();
  });

  it('le / propose les skills du sujet et du poste, avec leur origine', () => {
    component.draft = '/';
    const skills = component.slashMenu.filter((entry) => entry.family === 'skill');
    expect(skills.map((s) => s.name)).toEqual(['ticket-jira', 'deploy']);
    expect(skills[0].description).toContain('skill du poste');
    expect(skills[1].description).toBe('skill du sujet');
  });

  it('un skill homonyme d’une macro laisse la place à la macro', () => {
    component.draft = '/rev';
    const menu = component.slashMenu;
    expect(menu.filter((e) => e.name === 'revue').map((e) => e.family)).toEqual(['macro']);
  });

  it('accepter un skill complète la saisie sans rien envoyer', () => {
    component.draft = '/tic';
    const entry = component.slashMenu.find((e) => e.family === 'skill')!;
    component.acceptSlash(entry);
    expect(drafts[drafts.length - 1]).toBe('/ticket-jira ');
    expect(sent).toBe(0);
  });

  it('skillSuggestions filtre en préfixe et se tait hors jeton', () => {
    expect(skillSuggestions('/de', catalog).map((s) => s.name)).toEqual(['deploy']);
    expect(skillSuggestions('/deploy maintenant', catalog)).toEqual([]);
    expect(skillSuggestions('bonjour', catalog)).toEqual([]);
  });
});
