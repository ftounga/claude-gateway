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
});
