import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import {
  NamedAgentEntry,
  agentCommandSuggested,
  agentMenuDescription,
  agentSuggestions,
} from '../../core/services/named-agent-catalog.service';

/** **Déléguer à un agent nommé** depuis la saisie : `/agent <nom>` (F-182 / SF-182-02). */
describe('AtelierTerminalComponent — /agent <nom> (F-182 / SF-182-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;

  const WORKSPACE = 'ws-1';
  const AGENTS_URL = `/api/workspaces/${WORKSPACE}/agents`;
  const base: Omit<NamedAgentEntry, 'name' | 'path'> = {
    origin: 'POSTE',
    description: '',
    access: 'LECTURE',
    tools: ['read_file'],
    model: null,
    warnings: [],
    errors: [],
    usable: true,
  };
  const catalog: NamedAgentEntry[] = [
    { ...base, name: 'relecteur', path: '.claude/agents/relecteur.md', description: 'Relit un diff' },
    { ...base, name: 'testeur', path: '.claude/agents/testeur.md', access: 'ECRITURE', origin: 'SUJET' },
    { ...base, name: 'casse', path: '.claude/agents/casse.md', usable: false, errors: ['Consigne vide'] },
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
    http.expectOne(AGENTS_URL).flush(catalog);
  });

  afterEach(() => {
    http.match((r) => r.url !== AGENTS_URL);
    http.verify();
  });

  it('le / propose la commande /agent quand un agent existe', () => {
    component.draft = '/ag';
    const entries = component.slashMenu.filter((entry) => entry.family === 'agent');
    expect(entries.map((e) => e.title)).toEqual(['/agent <nom>']);
  });

  it('après /agent , propose les agents utilisables seulement', () => {
    component.draft = '/agent ';
    const entries = component.slashMenu.filter((entry) => entry.family === 'agent');
    expect(entries.map((e) => e.name)).toEqual(['agent relecteur', 'agent testeur']);
    expect(entries[0].description).toContain('Relit un diff');
    expect(entries[1].description).toContain('worktree isolé');
  });

  it('Tab sur la commande écrit /agent  et rouvre le menu des agents', () => {
    component.draft = '/age';
    const field = document.createElement('input');
    component.onComposerKeydown(new KeyboardEvent('keydown', { key: 'Tab' }), field);
    expect(drafts.at(-1)).toBe('/agent ');
    expect(component.slashMenu.filter((e) => e.family === 'agent').length).toBe(2);
    expect(sent).toBe(0);
  });

  it('Tab sur un agent complète /agent <nom>  sans envoyer', () => {
    component.draft = '/agent rel';
    const field = document.createElement('input');
    component.onComposerKeydown(new KeyboardEvent('keydown', { key: 'Tab' }), field);
    expect(drafts.at(-1)).toBe('/agent relecteur ');
    expect(sent).toBe(0);
  });

  it('fonctions pures', () => {
    expect(agentCommandSuggested('/', catalog)).toBeTrue();
    expect(agentCommandSuggested('/x', catalog)).toBeFalse();
    expect(agentCommandSuggested('/a', [])).toBeFalse();
    expect(agentSuggestions('/agent t', catalog).map((a) => a.name)).toEqual(['testeur']);
    expect(agentSuggestions('/agent testeur fais', catalog)).toEqual([]);
    expect(agentMenuDescription(catalog[1])).toBe('écrit dans un worktree isolé · agent du sujet');
  });
});
