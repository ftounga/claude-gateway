import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';

/**
 * Vue terminal immersive (F-30 SF-30-07) : composant de présentation. On vérifie le rendu des lignes
 * (`>` demande, `$` commande, sortie, coût) et les événements émis — aucun appel réseau n'est en jeu.
 */
describe('AtelierTerminalComponent', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('affiche le nom du projet et une invite d\'accueil quand rien n\'a encore été exécuté', () => {
    fixture.detectChanges();

    expect(text()).toContain('mon-projet');
    expect(fixture.nativeElement.querySelector('.terminal-hint')).not.toBeNull();
  });

  it('rend la demande en ligne d\'invite et les commandes avec leur sortie', () => {
    const turn: AtelierThreadItem[] = [
      { id: 'u1', role: 'USER', content: 'lance les tests', actions: [] },
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: 'Tests verts.',
        actions: [],
        terminal: [
          {
            tool: 'bash',
            command: 'npm test',
            toolUseId: 'tu_1',
            output: '12 passing',
            hasOutput: true,
            error: false,
            expanded: false,
            threadId: null,
          },
        ],
        cost: { elapsedSeconds: 65, tokens: 12_400 },
      },
    ];
    component.messages = turn;
    fixture.detectChanges();

    const prompt = fixture.nativeElement.querySelector('.terminal-prompt-line');
    expect(prompt.textContent).toContain('lance les tests');
    const command = fixture.nativeElement.querySelector('.terminal-command');
    expect(command.textContent).toContain('npm test');
    expect(fixture.nativeElement.querySelector('.terminal-output').textContent).toContain('12 passing');
    expect(fixture.nativeElement.querySelector('.terminal-cost').textContent).toContain('1:05');
  });

  it('marque en erreur le bloc d\'une commande en échec', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '',
        actions: [],
        terminal: [
          {
            tool: 'bash',
            command: 'npm run build',
            toolUseId: null,
            output: 'command not found',
            hasOutput: true,
            error: true,
            expanded: false,
            threadId: null,
          },
        ],
      },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-block.failed')).not.toBeNull();
  });

  it('n\'affiche aucun coût quand le tour n\'en porte pas', () => {
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: 'Fait.', actions: [], terminal: [] },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-cost')).toBeNull();
  });

  it('rend le tour en cours au fil de l\'eau', () => {
    component.streaming = {
      status: 'running',
      blocks: [
        {
          tool: 'bash',
          command: 'npm install',
          toolUseId: 'tu_1',
          output: '',
          hasOutput: false,
          error: false,
          expanded: false,
          threadId: null,
        },
      ],
      text: 'Installation en cours.',
    };
    component.submitting = true;
    component.elapsedLabel = '0:12';
    fixture.detectChanges();

    expect(text()).toContain('npm install');
    expect(text()).toContain('Installation en cours.');
    expect(text()).toContain('0:12');
  });

  it('émet quit, resetSandbox et openFiles depuis la barre d\'en-tête', () => {
    const emitted: string[] = [];
    component.quit.subscribe(() => emitted.push('quit'));
    component.resetSandbox.subscribe(() => emitted.push('reset'));
    component.openFiles.subscribe(() => emitted.push('files'));
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.terminal-bar-actions button'),
    );
    buttons.forEach((b) => b.click());

    expect(emitted).toEqual(['files', 'reset', 'quit']);
  });

  it('n\'émet pas send sur une saisie vide, ni pendant un envoi', () => {
    let sent = 0;
    component.send.subscribe(() => (sent += 1));

    component.draft = '   ';
    component.submit();
    expect(sent).toBe(0);

    component.draft = 'lance les tests';
    component.submitting = true;
    component.submit();
    expect(sent).toBe(0);

    component.submitting = false;
    component.submit();
    expect(sent).toBe(1);
  });

  it('replie une sortie longue et la déplie à la demande', () => {
    const block = {
      tool: 'bash',
      command: 'npm install',
      toolUseId: null,
      output: Array.from({ length: 30 }, (_, i) => `ligne ${i + 1}`).join('\n'),
      hasOutput: true,
      error: false,
      expanded: false,
      threadId: null,
    };
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: '', actions: [], terminal: [block] },
    ];
    fixture.detectChanges();

    expect(component.hiddenLineCount(block)).toBe(10);
    expect(component.visibleOutput(block).split('\n').length).toBe(20);

    component.toggleBlock(block);

    expect(component.visibleOutput(block)).toBe(block.output);
  });
  // ---- F-32 / SF-32-02 : interrompre un run en cours ----

  it('n\'affiche le bouton Interrompre que pendant une exécution', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.terminal-interrupt')).toBeNull();

    component.submitting = true;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-interrupt')).not.toBeNull();
  });

  it('émet la demande d\'interruption au clic', () => {
    component.submitting = true;
    fixture.detectChanges();
    let emitted = 0;
    component.interrupt.subscribe(() => (emitted += 1));

    fixture.nativeElement.querySelector('.terminal-interrupt').click();

    expect(emitted).toBe(1);
  });

  it('rend le bouton inerte et annonce l\'attente tant que la demande est en vol', () => {
    // L'arrêt vient à une frontière sûre : annoncer un arrêt immédiat serait faux.
    component.submitting = true;
    component.interrupting = true;
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.terminal-interrupt') as HTMLButtonElement;
    expect(button.disabled).toBeTrue();
    expect(button.textContent).toContain('Interruption');
  });

  it('marque « Exécution interrompue » le tour arrêté, sans le retirer du fil', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: 'Arrêté.',
        actions: [],
        terminal: [
          {
            tool: 'bash',
            command: 'npm install',
            toolUseId: 'tu_1',
            output: 'installing…',
            hasOutput: true,
            error: false,
            expanded: false,
            threadId: null,
          },
        ],
        cost: { elapsedSeconds: 42, tokens: 1_000 },
        interrupted: true,
      },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-interrupted').textContent)
      .toContain('Exécution interrompue');
    // Le tour reste entier : il a réellement eu lieu et il est facturé.
    expect(fixture.nativeElement.querySelector('.terminal-command').textContent).toContain('npm install');
    expect(fixture.nativeElement.querySelector('.terminal-cost')).not.toBeNull();
  });

  it('n\'affiche aucune mention d\'interruption sur un tour mené à son terme', () => {
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: 'Terminé.', actions: [] },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-interrupted')).toBeNull();
  });

  // ---- F-36 / SF-36-04 : plafond de dépense du run atteint ----

  it('annonce le plafond de dépense du run, sans parler du quota mensuel', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: 'J\'ai commencé…',
        actions: [],
        cost: { elapsedSeconds: 42, tokens: 1_000 },
        budgetReached: true,
      },
    ];
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('.terminal-budget') as HTMLElement;
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('Plafond de dépense de ce run');
    // Ce n'est pas le quota mensuel : dire l'un pour l'autre enverrait l'utilisateur au mauvais geste.
    expect(banner.textContent).not.toContain('quota');
    // Le tour reste entier : réponse et coût conservés.
    expect(fixture.nativeElement.querySelector('.terminal-cost')).not.toBeNull();
  });

  it('propose le rachat de tokens et émet l\'ouverture de la facturation', () => {
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: 'J\'ai commencé…', actions: [], budgetReached: true },
    ];
    fixture.detectChanges();
    const emitted: boolean[] = [];
    component.openBilling.subscribe(() => emitted.push(true));

    const action = fixture.nativeElement.querySelector('.terminal-budget-action') as HTMLButtonElement;
    action.click();

    expect(emitted.length).toBe(1);
  });

  it('n\'affiche aucune mention de plafond sur un tour mené à son terme', () => {
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: 'Terminé.', actions: [] },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-budget')).toBeNull();
  });

  // ---- F-34 / SF-34-02 : instructions portées par le projet ----

  it('annonce le fichier d\'instructions du projet et émet son ouverture', () => {
    component.instructionsPath = 'CLAUDE.md';
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.terminal-instructions') as HTMLButtonElement;
    expect(button).not.toBeNull();
    expect(button.textContent).toContain('CLAUDE.md');

    let opened = false;
    component.openInstructions.subscribe(() => (opened = true));
    button.click();

    expect(opened).toBeTrue();
  });

  it('n\'affiche rien quand le projet ne porte pas d\'instructions', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-instructions')).toBeNull();
  });

  // ---- F-33 / SF-33-03 : invite d'autorisation ----

  it("affiche la commande soumise à autorisation et émet la décision", () => {
    component.pendingConfirmation = {
      toolUseId: 'sevt_1',
      tool: 'bash',
      detail: 'rm -rf build',
      answering: false,
      denying: false,
      reason: '',
    };
    fixture.detectChanges();

    const ask = fixture.nativeElement.querySelector('.terminal-ask') as HTMLElement;
    expect(ask).not.toBeNull();
    expect(ask.textContent).toContain('rm -rf build');

    let decision: boolean | undefined;
    component.confirmDecision.subscribe((allow: boolean) => (decision = allow));
    (ask.querySelector('.terminal-ask-allow') as HTMLButtonElement).click();

    expect(decision).toBeTrue();
  });

  it("n'affiche aucune invite tant qu'aucune autorisation n'est demandée", () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-ask')).toBeNull();
  });

  it("propose le champ de motif au refus, puis émet le refus", () => {
    component.pendingConfirmation = {
      toolUseId: 'sevt_1',
      tool: 'bash',
      detail: 'rm -rf build',
      answering: false,
      denying: true,
      reason: '',
    };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-ask-reason')).not.toBeNull();

    let decision: boolean | undefined;
    component.confirmDecision.subscribe((allow: boolean) => (decision = allow));
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.terminal-ask-actions button'),
    ) as HTMLButtonElement[];
    buttons[buttons.length - 1].click();

    expect(decision).toBeFalse();
  });

  it("laisse les actions inertes pendant l'envoi d'une décision", () => {
    component.pendingConfirmation = {
      toolUseId: 'sevt_1',
      tool: 'bash',
      detail: 'ls',
      answering: true,
      denying: false,
      reason: '',
    };
    fixture.detectChanges();

    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.terminal-ask-actions button'),
    ) as HTMLButtonElement[];
    expect(buttons.every((b) => b.disabled)).toBeTrue();
  });

  it("dit l'état de l'option de validation et émet sa bascule", () => {
    component.askBeforeBash = true;
    fixture.detectChanges();

    const guard = fixture.nativeElement.querySelector('.terminal-guard') as HTMLButtonElement;
    expect(guard.textContent).toContain('Validation activée');

    let toggled = false;
    component.toggleAskBeforeBash.subscribe(() => (toggled = true));
    guard.click();

    expect(toggled).toBeTrue();
  });

  // ---- F-31 SF-31-05 : pull request dans le bandeau de publication ----

  /** Publication réussie : le seul état où l'ouverture d'une pull request a un sens. */
  function pushed(): void {
    component.pushResult = {
      branch: 'feat/atelier',
      pushed: true,
      compareUrl: 'https://github.com/octocat/hello/compare/x?expand=1',
      reply: 'Branche poussée.',
    };
  }

  it('propose de créer la pull request après une publication réussie et émet la demande', () => {
    pushed();
    fixture.detectChanges();

    const button = Array.from(
      fixture.nativeElement.querySelectorAll('.terminal-push button'),
    ).find((b) => (b as HTMLElement).textContent?.includes('Créer la pull request')) as
      | HTMLButtonElement
      | undefined;
    expect(button).toBeDefined();

    let asked = false;
    component.openPullRequest.subscribe(() => (asked = true));
    button!.click();

    expect(asked).toBeTrue();
  });

  it("affiche l'URL de la pull request une fois ouverte, à la place du bouton", () => {
    pushed();
    component.pullRequest = {
      branch: 'feat/atelier',
      created: true,
      url: 'https://github.com/octocat/hello/pull/7',
      number: 7,
      reply: 'ok',
    };
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('.terminal-push a') as HTMLAnchorElement;
    expect(link.href).toContain('/pull/7');
    expect(text()).toContain('Pull request #7');
    expect(text()).not.toContain('Créer la pull request');
  });

  it("ne prétend rien quand aucune pull request n'a été ouverte, et garde le repli GitHub", () => {
    pushed();
    component.pullRequest = {
      branch: 'feat/atelier',
      created: false,
      url: null,
      number: null,
      reply: 'permission denied',
    };
    fixture.detectChanges();

    expect(text()).toContain("Aucune pull request n'a été ouverte");
    expect(text()).toContain('permission denied');
    expect(text()).toContain('Ouvrir sur GitHub');
  });

  it('rend le bouton inerte pendant que la demande est en vol', () => {
    pushed();
    component.openingPullRequest = true;
    fixture.detectChanges();

    const button = Array.from(
      fixture.nativeElement.querySelectorAll('.terminal-push button'),
    ).find((b) => (b as HTMLElement).textContent?.includes('Créer la pull request')) as
      | HTMLButtonElement
      | undefined;
    expect(button!.disabled).toBeTrue();
  });

  it("n'offre aucune pull request quand rien n'a été publié", () => {
    component.pushResult = {
      branch: 'feat/atelier',
      pushed: false,
      compareUrl: null,
      reply: 'rien à commiter',
    };
    fixture.detectChanges();

    expect(text()).not.toContain('Créer la pull request');
  });

  // ---- F-35 SF-35-03 : visibilité des sous-tâches déléguées ----

  function terminalBlock(threadId: string | null, command: string, error = false) {
    return {
      tool: 'bash',
      command,
      toolUseId: null,
      output: '',
      hasOutput: false,
      error,
      expanded: false,
      threadId,
    };
  }

  it('marque les sous-tâches d\'un tour délégué et les numérote (F-35)', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '',
        actions: [],
        terminal: [
          terminalBlock(null, 'ls'),
          terminalBlock('th_a', 'npm test'),
          terminalBlock('th_b', 'npm run lint'),
        ],
      },
    ];
    fixture.detectChanges();

    // `textContent` porte aussi la ligature de l'icône : on vérifie le libellé, pas l'égalité.
    const threads = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.terminal-thread'),
    ).map((el) => el.textContent ?? '');
    expect(threads.length).toBe(3);
    expect(threads[0]).toContain('Fil principal');
    expect(threads[1]).toContain('Sous-tâche 1');
    expect(threads[2]).toContain('Sous-tâche 2');
    // Le rail d'accent ne marque QUE les blocs délégués.
    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.terminal-block.subtask').length,
    ).toBe(2);
    expect(text()).toContain('2 sous-tâches');
  });

  it('n\'affiche aucun marqueur de fil sur un tour sans délégation (non-régression F-35)', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '',
        actions: [],
        terminal: [terminalBlock(null, 'npm test'), terminalBlock(null, 'npm run build')],
      },
    ];
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.terminal-thread')).toBeNull();
    expect(root.querySelector('.terminal-block.subtask')).toBeNull();
    expect(root.querySelector('.terminal-subtasks')).toBeNull();
    expect(text()).not.toContain('sous-tâche');
  });

  it('garde le marquage d\'échec sur un bloc délégué en erreur (l\'échec prime, F-35)', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '',
        actions: [],
        terminal: [terminalBlock('th_a', 'npm test', true)],
      },
    ];
    fixture.detectChanges();

    const block = (fixture.nativeElement as HTMLElement).querySelector('.terminal-block');
    expect(block!.classList).toContain('failed');
    expect(block!.classList).toContain('subtask');
  });

  it('marque aussi les sous-tâches du tour EN COURS (F-35)', () => {
    component.streaming = {
      status: 'running',
      blocks: [terminalBlock('th_a', 'npm test')],
      text: '',
    };
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.terminal-thread')?.textContent)
      .toContain('Sous-tâche 1');
  });

  it('accorde le décompte au singulier quand une seule sous-tâche a été ouverte (F-35)', () => {
    expect(component.subtaskLabel(1)).toBe('1 sous-tâche');
    expect(component.subtaskLabel(3)).toBe('3 sous-tâches');
  });
});
