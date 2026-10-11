import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';

import { FilTask, PosteTurns } from '../../core/models/terminal-fil.models';
import {
  FIL_POLL_MS,
  TerminalFilComponent,
  barPercent,
  durationLabel,
  filCounts,
  stateLabel,
  toolLabel,
} from './terminal-fil.component';

function task(over: Partial<FilTask> = {}): FilTask {
  return {
    turnId: over.turnId ?? Math.random().toString(36).slice(2),
    workspaceId: 'w2',
    workspaceName: 'Forge CAPFM',
    root: false,
    here: false,
    state: 'running',
    waiting: null,
    demand: 'corrige le build',
    startedAt: 1_000_000,
    endedAt: null,
    planDone: 2,
    planTotal: 4,
    planStep: 'Tester',
    tool: 'bash',
    subagents: [],
    ...over,
  };
}

describe('Le fil des tâches — fonctions (F-191 / SF-191-02)', () => {
  it('compte attente, en cours et terminées', () => {
    expect(filCounts([
      task({ state: 'waiting' }), task(), task(), task({ state: 'done' }), task({ state: 'failed' }),
    ])).toEqual({ waiting: 1, running: 2, done: 2 });
  });

  it('dit l\'outil en français, et garde un nom inconnu tel quel', () => {
    expect(toolLabel('bash')).toBe('Commande');
    expect(toolLabel('read')).toBe('Lecture');
    expect(toolLabel('teams_lire')).toBe('teams_lire');
    expect(toolLabel(null)).toBeNull();
  });

  it('formate les durées', () => {
    expect(durationLabel(45_000)).toBe('45 s');
    expect(durationLabel(3 * 60_000 + 5_000)).toBe('3 min');
    expect(durationLabel(65 * 60_000)).toBe('1 h 05');
    expect(durationLabel(-5)).toBe('0 s');
  });

  it('la barre suit le plan, indéterminée sans plan, pleine une fois fini', () => {
    expect(barPercent(task({ planDone: 1, planTotal: 4 }))).toBe(25);
    expect(barPercent(task({ planDone: 0, planTotal: 0 }))).toBeNull();
    expect(barPercent(task({ state: 'done', planTotal: 0 }))).toBe(100);
  });

  it('l\'état dit l\'attente, la fin, ou l\'outil courant', () => {
    expect(stateLabel(task({ state: 'waiting', waiting: 'approval' }))).toBe('attend votre accord');
    expect(stateLabel(task({ state: 'waiting', waiting: 'question' }))).toBe('attend votre réponse');
    expect(stateLabel(task({ state: 'done' }))).toBe('✓ terminé');
    expect(stateLabel(task({ state: 'failed' }))).toBe('échec');
    expect(stateLabel(task({ tool: 'write' }))).toBe('Écriture');
    expect(stateLabel(task({ tool: null }))).toBe('en cours');
  });
});

describe('TerminalFilComponent (F-191 / SF-191-02)', () => {
  let fixture: ComponentFixture<TerminalFilComponent>;
  let http: HttpTestingController;
  let router: Router;
  let visibility: jasmine.Spy;

  function build(id: string | null = 'w1'): void {
    TestBed.configureTestingModule({
      imports: [TerminalFilComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    // Le navigateur de test peut se croire caché pendant la suite complète : on fixe l'onglet visible.
    visibility = spyOnProperty(document, 'visibilityState', 'get').and.returnValue('visible');
    fixture = TestBed.createComponent(TerminalFilComponent);
    fixture.componentRef.setInput('workspaceId', id);
    fixture.detectChanges();
  }

  function answer(turns: FilTask[], serverTime = 1_120_000): void {
    const body: PosteTurns = { hostId: 'h1', serverTime, turns };
    http.expectOne('/api/workspaces/w1/fil').flush(body);
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => fixture?.destroy());

  it('absente quand rien ne tourne', () => {
    build();
    answer([]);

    expect(el().querySelector('.fil')).toBeNull();
  });

  it('aucune lecture sans terminal', () => {
    build(null);

    http.expectNone(() => true);
    expect(el().querySelector('.fil')).toBeNull();
  });

  it('repliée par défaut, compteurs justes, puis dépliée au clic', () => {
    build();
    answer([
      task({ state: 'waiting', waiting: 'approval', workspaceName: 'SCP' }),
      task({ workspaceName: 'Forge CAPFM' }),
      task({ state: 'done', endedAt: 1_100_000, workspaceName: 'LZI' }),
    ]);

    const head = el().querySelector('.fil__head') as HTMLButtonElement;
    expect(head.getAttribute('aria-expanded')).toBe('false');
    expect(head.textContent).toContain('1 attend votre accord');
    expect(head.textContent).toContain('1 en cours');
    expect(head.textContent).toContain('✓ 1 terminée');
    expect(el().querySelector('.rows')).toBeNull();
    expect(el().querySelectorAll('.mini span').length).toBe(3);

    head.click();
    fixture.detectChanges();

    const rows = el().querySelectorAll('.row');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('attend votre accord');
    expect(rows[1].textContent).toContain('Commande');
    expect(rows[1].textContent).toContain('2/4');
    expect(rows[1].textContent).toContain('2 min');
    expect(rows[2].textContent).toContain('✓ terminé');
  });

  it('marque « ici » et « racine », sous-agents listés', () => {
    build();
    answer([
      task({ here: true, workspaceId: 'w1', workspaceName: 'Ici' }),
      task({ root: true, workspaceName: 'Poste', planTotal: 0, planDone: 0,
        subagents: [{ kind: 'task', label: 'relire la facturation', state: 'running', startedAt: 1_100_000 }] }),
    ]);
    (el().querySelector('.fil__head') as HTMLButtonElement).click();
    fixture.detectChanges();

    const rows = el().querySelectorAll('.row');
    expect(rows[0].classList).toContain('row--here');
    expect(rows[0].textContent).toContain('ici');
    expect(rows[1].textContent).toContain('racine');
    expect(rows[1].querySelector('.bar.indet')).not.toBeNull();
    expect(rows[1].querySelector('.sub')?.textContent).toContain('relire la facturation');
    expect(rows[1].querySelector('.sub')?.textContent).toContain('20 s');
  });

  it('un clic ouvre le sujet ; la tâche d\'ici ne navigue pas', () => {
    build();
    answer([task({ here: true, workspaceId: 'w1' }), task({ workspaceId: 'w9' })]);
    (el().querySelector('.fil__head') as HTMLButtonElement).click();
    fixture.detectChanges();

    const rows = el().querySelectorAll<HTMLButtonElement>('.row');
    rows[0].click();
    expect(router.navigate).not.toHaveBeenCalled();
    rows[1].click();
    expect(router.navigate).toHaveBeenCalledWith(['/atelier', 'w9']);
  });

  it('relit 5 s après la réponse, jamais avant', fakeAsync(() => {
    build();
    answer([task()]);

    tick(FIL_POLL_MS - 1);
    http.expectNone('/api/workspaces/w1/fil');
    tick(1);
    answer([]);
    expect(el().querySelector('.fil')).toBeNull();

    fixture.destroy();
    tick(FIL_POLL_MS * 2);
    http.expectNone('/api/workspaces/w1/fil');
  }));

  it('404 : se tait et ne relit plus', fakeAsync(() => {
    build();
    http.expectOne('/api/workspaces/w1/fil').flush('', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    tick(FIL_POLL_MS * 3);
    http.expectNone('/api/workspaces/w1/fil');
    expect(el().querySelector('.fil')).toBeNull();
  }));

  it('panne passagère : garde le dernier état et relit au cycle suivant', fakeAsync(() => {
    build();
    answer([task()]);
    tick(FIL_POLL_MS);
    http.expectOne('/api/workspaces/w1/fil').flush('', { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    expect(el().querySelector('.fil')).not.toBeNull();
    tick(FIL_POLL_MS);
    answer([]);
    fixture.destroy();
  }));

  it('onglet caché : aucune lecture, reprise une fois visible', fakeAsync(() => {
    build();
    answer([task()]);
    visibility.and.returnValue('hidden');

    tick(FIL_POLL_MS);
    http.expectNone('/api/workspaces/w1/fil');

    visibility.and.returnValue('visible');
    tick(15_000);
    answer([]);
    fixture.destroy();
  }));
});
