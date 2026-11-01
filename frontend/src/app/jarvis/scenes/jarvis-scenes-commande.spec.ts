import { TestBed } from '@angular/core/testing';

import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { JarvisSceneReader, baseName, outputTail, ticketKeys, ticketSource } from './jarvis-scene-reader';
import { resolveScene } from './jarvis-scene-registry';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-05 — S3 commande, S4 tickets, S5 feuillets', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('le registre donne S3 aux commandes et S5 aux fichiers', () => {
    expect(resolveScene('bash').scene).toBe('S3');
    expect(resolveScene('kill_shell').scene).toBe('S3');
    expect(resolveScene('read').scene).toBe('S5');
    expect(resolveScene('write').scene).toBe('S5');
    expect(resolveScene('search').scene).toBe('S5');
  });

  it('S3 : la commande, puis les 2 dernières lignes de sa sortie, secrets masqués', () => {
    reader.action({ type: 'bash', path: 'kubectl get pods -n forge' }, 0);
    reader.commandOutput('NAME   READY\nforge-api   Running\n', 10);
    reader.commandOutput('forge-web   Running\ncurl -H "Authorization: Bearer abc.def" x\n', 20);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S3');
    expect(scene.detail).toBe('kubectl get pods -n forge');
    expect(scene.data?.lines?.length).toBe(2);
    expect(scene.data?.lines?.[0]).toBe('forge-web   Running');
    expect(scene.data?.lines?.join(' ')).not.toContain('abc.def');
    expect(scene.data?.lines?.[1]).toContain('•••');
  });

  it('S3 : une ligne trop longue est coupée à 80 caractères ; une nouvelle commande repart de zéro', () => {
    expect(outputTail('x'.repeat(200))[0].length).toBe(80);
    reader.action({ type: 'bash', path: 'ls' }, 0);
    reader.commandOutput('a\nb\n', 10);
    reader.action({ type: 'bash', path: 'pwd' }, 100);
    expect(reader.state().scene?.data?.lines).toEqual([]);
  });

  it('S4 : une commande Jira donne une carte par clé, Confluence est reconnu', () => {
    expect(ticketSource('curl https://x.atlassian.net/rest/api/2/issue/FORGE-41')).toBe('Jira');
    expect(ticketSource('curl https://x.atlassian.net/wiki/rest/api/content/123')).toBe('Confluence');
    expect(ticketSource('kubectl get pods')).toBeNull();
    expect(ticketKeys('FORGE-41 et OPS-7, encore FORGE-41')).toEqual(['FORGE-41', 'OPS-7']);
    reader.action({ type: 'bash', path: 'curl -s https://x.atlassian.net/rest/api/2/issue/FORGE-41' }, 0);
    reader.action({ type: 'bash', path: 'acli jira workitem view FORGE-42' }, 500);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S4');
    expect(scene.libelle).toBe('Jira · consultation');
    expect(scene.data?.tickets).toEqual(['FORGE-41', 'FORGE-42']);
    reader.text('Le ticket OPS-7 est lié.\n', 600);
    expect(reader.state().scene?.data?.tickets).toEqual(['FORGE-41', 'FORGE-42', 'OPS-7']);
  });

  it('S4 : URL …/wiki/… ⇒ « Confluence · page »', () => {
    reader.action({ type: 'bash', path: 'curl https://x.atlassian.net/wiki/spaces/OPS/pages/1' }, 0);
    expect(reader.state().scene?.libelle).toBe('Confluence · page');
  });

  it('S5 : les fichiers lus s\'empilent (« 3 fichiers lus »), la recherche a sa loupe', () => {
    expect(baseName('src/app/jarvis/x.ts')).toBe('x.ts');
    reader.action({ type: 'read', path: 'src/a.ts' }, 0);
    reader.action({ type: 'read', path: 'src/b.ts' }, 100);
    reader.action({ type: 'read', path: 'src/c.ts' }, 200);
    let scene = reader.state().scene!;
    expect(scene.id).toBe('S5');
    expect(scene.libelle).toBe('3 fichiers lus');
    expect(scene.data?.files).toEqual(['a.ts', 'b.ts', 'c.ts']);
    reader.action({ type: 'search', path: 'TODO' }, 5000);
    reader.tick(5000);
    scene = reader.state().scene!;
    expect(scene.data?.mode).toBe('search');
    expect(scene.data?.pattern).toBe('TODO');
  });
});

describe('F-234 / SF-234-05 — rendu des scènes', () => {
  let bus: JarvisSceneBus;
  let now = 0;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [JarvisSceneComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.useClock(() => now);
    now = 0;
  });

  afterEach(() => bus.ngOnDestroy());

  it('S3 en filigrane, S4 en cartes, S5 en feuillets', () => {
    const fixture = TestBed.createComponent(JarvisSceneComponent);
    bus.turnStarted();
    bus.action({ type: 'bash', path: 'kubectl get pods' });
    bus.output('forge-api Running\npassword=hunter2\n');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-scene="S3"] .term__cmd')?.textContent).toContain('kubectl get pods');
    expect(el.querySelectorAll('.term__line').length).toBe(2);
    expect(el.textContent).not.toContain('hunter2');

    now = 5000;
    bus.action({ type: 'bash', path: 'acli jira workitem view FORGE-41' });
    bus.tick();
    fixture.detectChanges();
    expect(el.querySelector('[data-scene="S4"] .ticket')?.textContent).toBe('FORGE-41');

    now = 10000;
    bus.action({ type: 'write', path: 'src/app/x.ts' });
    bus.tick();
    fixture.detectChanges();
    expect(el.querySelector('[data-scene="S5"] .sheet.is-last')?.textContent).toBe('x.ts');
    bus.aborted();
  });
});
