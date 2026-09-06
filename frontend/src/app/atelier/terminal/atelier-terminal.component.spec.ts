import {
  ComponentFixture,
  TestBed,
  discardPeriodicTasks,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';
import { AtelierTerminalBlock } from '../../core/models/atelier.models';
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
      tokens: null,
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
      source: 'HOSTED_SANDBOX',
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
      source: 'HOSTED_SANDBOX',
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
      source: 'HOSTED_SANDBOX',
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
      tokens: null,
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
  /**
   * SF-30-12 — le modèle répond en Markdown ; le terminal l'interpolait brut, si bien que les ** et
   * les ## s'affichaient tels quels. Le rendu passe par le pipe partagé (marked + DOMPurify).
   */
  describe('rendu Markdown du commentaire de l\'agent (SF-30-12)', () => {
    function agentTurn(content: string): AtelierThreadItem[] {
      return [
        { id: 'u1', role: 'USER', content: 'décris le projet', actions: [] },
        { id: 'a1', role: 'ASSISTANT', content, actions: [] },
      ];
    }

    function agentBlock(): HTMLElement {
      const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.terminal-agent');
      expect(el).withContext('bloc du commentaire de l\'agent introuvable').not.toBeNull();
      return el as HTMLElement;
    }

    it('met en forme le gras, le code et les titres au lieu d\'afficher les marqueurs', () => {
      component.messages = agentTurn('## Vue d\'ensemble\n\nC\'est **important** et `scrm` est un mot.');
      fixture.detectChanges();

      const block = agentBlock();
      expect(block.querySelector('h2')?.textContent).toContain('Vue d\'ensemble');
      expect(block.querySelector('strong')?.textContent).toBe('important');
      expect(block.querySelector('code')?.textContent).toBe('scrm');
      // Les marqueurs eux-mêmes ont disparu du texte rendu.
      expect(block.textContent).not.toContain('**');
      expect(block.textContent).not.toContain('##');
    });

    it('rend les listes et les blocs de code', () => {
      component.messages = agentTurn('- un\n- deux\n\n```\nnpm test\n```');
      fixture.detectChanges();

      const block = agentBlock();
      expect(block.querySelectorAll('li').length).toBe(2);
      expect(block.querySelector('pre code')?.textContent).toContain('npm test');
    });

    it('rend aussi le texte du tour en cours, au fil de l\'eau', () => {
      component.streaming = {
        status: 'running',
        blocks: [],
        text: 'Je lance **le build**.',
        tokens: null,
      };
      fixture.detectChanges();

      expect(agentBlock().querySelector('strong')?.textContent).toBe('le build');
    });

    it('neutralise le HTML dangereux : le contenu vient d\'un LLM', () => {
      component.messages = agentTurn('Voici <script>alert(1)</script> et <img src=x onerror="alert(2)">');
      fixture.detectChanges();

      const block = agentBlock();
      expect(block.querySelector('script')).toBeNull();
      expect(block.querySelector('img')?.hasAttribute('onerror')).toBeFalse();
    });

    it('ouvre les liens dans un nouvel onglet, sans fuite d\'opener', () => {
      component.messages = agentTurn('voir [le dépôt](https://github.com/ftounga/scrm)');
      fixture.detectChanges();

      const link = agentBlock().querySelector('a');
      expect(link?.getAttribute('target')).toBe('_blank');
      expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('n\'affiche aucun bloc quand le commentaire est vide', () => {
      component.messages = agentTurn('');
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).querySelector('.terminal-agent')?.textContent ?? '')
        .toBe('');
    });
  });
  /**
   * SF-30-13 — la ligne vivante. Le spinner de la barre du haut est hors du champ de vision pendant
   * qu'on lit défiler les commandes : entre deux étapes longues, rien ne bougeait là où l'œil est.
   */
  describe('ligne vivante pendant un tour (SF-30-13)', () => {
    function live(over: Partial<AtelierTerminalComponent['streaming'] & object> = {}) {
      component.streaming = {
        status: 'running',
        blocks: [],
        text: '',
        tokens: null,
        ...over,
      } as AtelierTerminalComponent['streaming'];
      fixture.detectChanges();
    }

    function lineText(): string {
      return (fixture.nativeElement as HTMLElement)
        .querySelector('.terminal-live')?.textContent?.replace(/\s+/g, ' ')
        .trim() ?? '';
    }

    function block(over: Record<string, unknown>) {
      return {
        tool: 'bash',
        command: '',
        toolUseId: 't1',
        threadId: null,
        output: '',
        hasOutput: false,
        error: false,
        expanded: false,
        ...over,
      } as unknown as AtelierTerminalBlock;
    }

    it('est absente tant qu\'aucun tour n\'est en cours', () => {
      component.messages = [{ id: 'a1', role: 'ASSISTANT', content: 'fini', actions: [] }];
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).querySelector('.terminal-live')).toBeNull();
    });

    it('annonce le démarrage avant la première action', () => {
      live();

      expect(lineText()).toContain('démarrage…');
    });

    it('montre la commande en cours et compte les étapes', () => {
      live({
        blocks: [
          block({ command: 'npm install', hasOutput: true, output: 'ok' }),
          block({ command: 'npm test', toolUseId: 't2' }),
        ],
      });

      const line = lineText();
      // La dernière action SANS sortie est celle qui tourne.
      expect(line).toContain('npm test');
      expect(line).not.toContain('npm install');
      expect(line).toContain('2 étapes');
    });

    it('accorde le singulier sur une seule étape', () => {
      live({ blocks: [block({ command: 'npm test' })] });

      expect(lineText()).toContain('1 étape');
    });

    it('affiche les tokens dès qu\'un relevé est arrivé', () => {
      live({ blocks: [block({ command: 'npm test' })], tokens: 12345 });

      // Séparateur de milliers français : le chiffre se lit d'un coup d'œil.
      expect(lineText()).toContain('12 345 tokens');
    });

    it('omet les tokens tant qu\'aucun relevé n\'est arrivé', () => {
      live({ blocks: [block({ command: 'npm test' })] });

      // Un « 0 token » se lirait comme une mesure, alors que c'est une absence de mesure.
      expect(lineText()).not.toContain('tokens');
      expect((fixture.nativeElement as HTMLElement).querySelector('.terminal-live-tokens')).toBeNull();
    });

    it('porte le chrono et un rôle accessible', () => {
      component.elapsedLabel = '1:07';
      live();

      const line = (fixture.nativeElement as HTMLElement).querySelector('.terminal-live');
      expect(line?.getAttribute('role')).toBe('status');
      expect(line?.getAttribute('aria-live')).toBe('polite');
      expect(lineText()).toContain('1:07');
    });

    it('n\'anime le spinner que pendant un tour', fakeAsync(() => {
      live();
      const first = component.spinnerFrame;

      component.streamingActive = true;
      tick(400);
      const spinning = component.spinnerFrame;
      expect(spinning).not.toBe(first);

      // Tour terminé : l'animation s'arrête et l'image revient à son état de repos.
      component.streamingActive = false;
      const atRest = component.spinnerFrame;
      tick(400);
      // Une animation qui tourne dans le vide consomme du rendu et ment sur l'état du système.
      expect(component.spinnerFrame).toBe(atRest);
      expect(component.spinnerFrame).toBe(first);

      discardPeriodicTasks();
    }));
  });

  // ---- F-39 / SF-39-08 : moteur affiché, réglages runner portés dans l'écran unique ----

  describe('moteur et cible d\'exécution (F-39 SF-39-08)', () => {

    it('dit « bac à sable hébergé » quand le tour part chez le fournisseur', () => {
      component.engine = 'HOSTED_SANDBOX';
      fixture.detectChanges();

      expect(component.engineLabel()).toBe('bac à sable hébergé');
      expect(component.engineIcon()).toBe('cloud');
      expect(text()).toContain('bac à sable hébergé');
      // Les mots « Assistant » et « Terminal » désignaient un moteur : ils ont disparu (D1).
      expect(text()).not.toContain('Assistant');
    });

    it('dit « ma machine » et l\'état du runner quand le tour part sur la machine', () => {
      component.engine = 'LOCAL_MACHINE';

      component.runnerStatus = { connected: true, lastSeenAt: null };
      expect(component.engineLabel()).toBe('ma machine — connectée');

      component.runnerStatus = { connected: false, lastSeenAt: null };
      expect(component.engineLabel()).toBe('ma machine — hors ligne');

      // « État inconnu » se dit, il ne se devine pas.
      component.runnerStatus = null;
      expect(component.engineLabel()).toBe('ma machine — état inconnu');
      expect(component.engineIcon()).toBe('dns');
    });

    it('ne propose pas « Réinitialiser » sur la machine de l\'utilisateur (D-L4-6)', () => {
      component.engine = 'LOCAL_MACHINE';
      fixture.detectChanges();
      // Le geste recrée un environnement hébergé : ici il n'y a rien à recréer.
      expect(text()).not.toContain('Réinitialiser');

      component.engine = 'HOSTED_SANDBOX';
      fixture.detectChanges();
      expect(text()).toContain('Réinitialiser');
    });

    it('porte le réglage de cible d\'exécution dans l\'écran unique (D-L4-4)', () => {
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.terminal-target-toggle')).not.toBeNull();
      expect(text()).toContain("Où s'exécutent les outils");
    });

    it('ne montre les gestes runner qu\'en cible « ma machine »', () => {
      component.executionTarget = 'SANDBOX';
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.terminal-runner-pair')).toBeNull();
      expect(fixture.nativeElement.querySelector('.terminal-runner-kill')).toBeNull();

      component.executionTarget = 'RUNNER';
      fixture.detectChanges();

      // Appairage, journal d'audit et coupe-circuit restent atteignables : sans quoi l'écran unique
      // ferait régresser les acquis F-38 (§4 du cadrage).
      expect(fixture.nativeElement.querySelector('.terminal-runner-pair')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.terminal-runner-audit')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.terminal-runner-kill')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.terminal-runner-refresh')).not.toBeNull();
    });

    it('émet la bascule de cible plutôt que de l\'appliquer lui-même', () => {
      const emitted: string[] = [];
      component.executionTargetChange.subscribe((t) => emitted.push(t));
      fixture.detectChanges();

      component.executionTargetChange.emit('RUNNER');

      // Composant de présentation : la cible qui fait foi est celle que le backend confirme.
      expect(emitted).toEqual(['RUNNER']);
      expect(component.executionTarget).toBe('SANDBOX');
    });
  });
});
