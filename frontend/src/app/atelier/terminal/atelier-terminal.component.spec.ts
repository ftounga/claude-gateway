import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';
import { AtelierFileDiffView } from './terminal-diff';

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
            threadId: null,
            output: '12 passing',
            hasOutput: true,
            error: false,
            expanded: false,
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
            threadId: null,
            output: 'command not found',
            hasOutput: true,
            error: true,
            expanded: false,
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
          threadId: null,
          output: '',
          hasOutput: false,
          error: false,
          expanded: false,
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
      threadId: null,
      output: Array.from({ length: 30 }, (_, i) => `ligne ${i + 1}`).join('\n'),
      hasOutput: true,
      error: false,
      expanded: false,
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
            threadId: null,
            output: 'installing…',
            hasOutput: true,
            error: false,
            expanded: false,
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

  // ---- F-35 / SF-35-03 : sous-tâches visibles dans la vue terminal ----

  it("marque les commandes venant d'une sous-tâche, jamais celles du travail principal", () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '',
        actions: [],
        terminal: [
          {
            tool: 'bash',
            command: 'npm test',
            toolUseId: 'tu_1',
            threadId: 'thr_main',
            output: '',
            hasOutput: false,
            error: false,
            expanded: false,
          },
          {
            tool: 'bash',
            command: 'grep -r TODO',
            toolUseId: 'tu_2',
            threadId: 'thr_sub',
            output: '',
            hasOutput: false,
            error: false,
            expanded: false,
          },
        ],
      },
    ];
    fixture.detectChanges();

    const badges = Array.from(
      fixture.nativeElement.querySelectorAll('.terminal-subtask'),
    ) as HTMLElement[];
    expect(badges.length).toBe(1);
    expect(badges[0].textContent).toContain('sous-tâche 1');
  });

  it("n'affiche aucun badge pour un run séquentiel (non-régression F-30)", () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '',
        actions: [],
        terminal: [
          {
            tool: 'bash',
            command: 'npm test',
            toolUseId: 'tu_1',
            threadId: null,
            output: '12 passing',
            hasOutput: true,
            error: false,
            expanded: false,
          },
        ],
      },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('.terminal-subtask').length).toBe(0);
  });

  it('marque aussi les sous-tâches du tour en cours', () => {
    component.streaming = {
      status: 'running',
      blocks: [
        {
          tool: 'bash',
          command: 'npm test',
          toolUseId: 'tu_1',
          threadId: 'thr_main',
          output: '',
          hasOutput: false,
          error: false,
          expanded: false,
        },
        {
          tool: 'bash',
          command: 'grep -r TODO',
          toolUseId: 'tu_2',
          threadId: 'thr_sub',
          output: '',
          hasOutput: false,
          error: false,
          expanded: false,
        },
      ],
      text: '',
    };
    fixture.detectChanges();

    expect(text()).toContain('sous-tâche 1');
  });

  // ---- F-37 SF-37-02 : modifications du tour, repliées par fichier ----

  /** Tour assistant portant les modifications données. */
  function turnWithDiffs(diffs: AtelierFileDiffView[]): AtelierThreadItem[] {
    return [
      { id: 'u1', role: 'USER', content: 'corrige le bug', actions: [] },
      { id: 'a1', role: 'ASSISTANT', content: 'C\'est fait.', actions: [], diffs },
    ];
  }

  const diffView = (over: Partial<AtelierFileDiffView> = {}): AtelierFileDiffView => ({
    path: 'src/app.ts',
    added: false,
    diff: '@@ -1,2 +1,2 @@\n un\n-deux\n+DEUX',
    addedLines: 1,
    removedLines: 1,
    omittedLines: 0,
    unreadable: false,
    expanded: false,
    ...over,
  });

  it('liste une ligne par fichier modifié, repliée : le diff ne noie pas le fil (F-37)', () => {
    component.messages = turnWithDiffs([
      diffView(),
      diffView({ path: 'src/nouveau.ts', added: true, addedLines: 4, removedLines: 0 }),
    ]);
    fixture.detectChanges();

    const heads = fixture.nativeElement.querySelectorAll('.terminal-diff-head');
    expect(heads.length).toBe(2);
    expect(text()).toContain('Modifications');
    expect(text()).toContain('src/app.ts');
    expect(text()).toContain('+1 −1');
    // Un fichier créé se dit : sans cela, sa création n'apparaîtrait nulle part.
    expect(text()).toContain('nouveau');
    // Replié : aucune ligne de diff rendue tant qu'on n'a rien déplié.
    expect(fixture.nativeElement.querySelectorAll('.terminal-diff-line').length).toBe(0);
  });

  it('déplie le diff d\'un seul fichier, les autres restent repliés (F-37)', () => {
    const first = diffView();
    const second = diffView({ path: 'src/autre.ts' });
    component.messages = turnWithDiffs([first, second]);
    fixture.detectChanges();

    fixture.nativeElement.querySelectorAll('.terminal-diff-head')[0].click();
    fixture.detectChanges();

    expect(first.expanded).toBeTrue();
    expect(second.expanded).toBeFalse();
    const lines = fixture.nativeElement.querySelectorAll('.terminal-diff-line');
    expect(lines.length).toBe(4);
    expect(lines[2].classList).toContain('is-remove');
    expect(lines[3].classList).toContain('is-add');
    expect(lines[0].classList).toContain('is-hunk');
  });

  it('replie de nouveau au second clic (F-37)', () => {
    const only = diffView();
    component.messages = turnWithDiffs([only]);
    fixture.detectChanges();

    const head = fixture.nativeElement.querySelector('.terminal-diff-head');
    head.click();
    fixture.detectChanges();
    head.click();
    fixture.detectChanges();

    expect(only.expanded).toBeFalse();
    expect(fixture.nativeElement.querySelectorAll('.terminal-diff-line').length).toBe(0);
  });

  it('dit le volume omis quand le backend a borné le diff (F-37)', () => {
    component.messages = turnWithDiffs([diffView({ expanded: true, omittedLines: 120 })]);
    fixture.detectChanges();

    expect(text()).toContain('120 lignes omises');
  });

  it('dit « fichier binaire ou illisible » plutôt que d\'afficher un diff vide (F-37)', () => {
    component.messages = turnWithDiffs([
      diffView({ expanded: true, unreadable: true, diff: '', addedLines: 0, removedLines: 0 }),
    ]);
    fixture.detectChanges();

    expect(text()).toContain('fichier binaire ou illisible');
    expect(fixture.nativeElement.querySelectorAll('.terminal-diff-line').length).toBe(0);
  });

  it('n\'affiche aucune section quand le tour n\'a rien modifié (F-37)', () => {
    component.messages = [
      { id: 'u1', role: 'USER', content: 'bonjour', actions: [] },
      { id: 'a1', role: 'ASSISTANT', content: 'Bonjour.', actions: [] },
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-diffs')).toBeNull();
    expect(text()).not.toContain('Modifications');
  });
});
