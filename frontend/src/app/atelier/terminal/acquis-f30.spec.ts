import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { chatStepsToBlocks } from './chat-steps';
import { AtelierThreadItem } from '../atelier.types';
import { AtelierTerminalBlock } from '../../core/models/atelier.models';

/**
 * **Les treize acquis visuels du §4 de `docs/features/F-39/CADRAGE.md`, rendus exécutables**
 * (F-39 / SF-39-09, décision D-L4-8).
 *
 * <p>Le cadrage est explicite : « ces treize subfeatures sont le résultat d'itérations explicites
 * sur l'apparence et le comportement du terminal. La refonte de l'écran les <b>reprend</b>, elle ne
 * les redécouvre pas. Toute régression ici est bloquante. » Une checklist de revue protège une PR ;
 * un test protège six mois de PR. Chaque cas porte le numéro de l'acquis et sa subfeature d'origine,
 * pour qu'un échec dise <b>lequel</b> des treize a été perdu.</p>
 */
describe('F-39 §4 — acquis visuels repris par l\'écran unique', () => {
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

  function block(over: Partial<AtelierTerminalBlock> = {}): AtelierTerminalBlock {
    return {
      tool: 'bash', command: 'npm test', toolUseId: 'tu_1', threadId: null,
      output: '12 passing', hasOutput: true, error: false, expanded: false, ...over,
    };
  }

  function turn(over: Partial<AtelierThreadItem> = {}): AtelierThreadItem[] {
    return [
      { id: 'u1', role: 'USER', content: 'lance les tests', actions: [] },
      {
        id: 'a1', role: 'ASSISTANT', content: 'Tests verts.', actions: [],
        terminal: [block()], ...over,
      },
    ];
  }

  function html(): string {
    return (fixture.nativeElement as HTMLElement).innerHTML;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  // 1 — Sortie des commandes relayée (SF-30-01) : la sortie, pas seulement la commande.
  it('1 · relaie la sortie des commandes, pas seulement la commande (SF-30-01)', () => {
    component.messages = turn();
    fixture.detectChanges();

    expect(text()).toContain('npm test');
    expect(text()).toContain('12 passing');
  });

  // 2 — Rendu terminal : commande PUIS sortie (SF-30-02).
  it('2 · rend la commande puis sa sortie, dans cet ordre (SF-30-02)', () => {
    component.messages = turn();
    fixture.detectChanges();

    const rendered = html();
    expect(rendered.indexOf('npm test')).toBeLessThan(rendered.indexOf('12 passing'));
    expect(fixture.nativeElement.querySelector('.terminal-command')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.terminal-output')).not.toBeNull();
  });

  // 3 — Terminal immersif plein écran (SF-30-07) : l'écran EST le terminal.
  it('3 · occupe tout l\'écran, sans liste de projets ni bulles de conversation (SF-30-07)', () => {
    component.messages = turn();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-view')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.atelier-layout')).toBeNull();
    expect(fixture.nativeElement.querySelector('.message-thread')).toBeNull();
  });

  // 4 — Markdown mis en forme (SF-30-12) : plus de `**`, `##` ni backticks bruts.
  it('4 · met le commentaire en forme au lieu d\'afficher du Markdown brut (SF-30-12)', () => {
    component.messages = turn({ content: '## Bilan\n\nTout est **vert**.' });
    fixture.detectChanges();

    const agent: HTMLElement = fixture.nativeElement.querySelector('.terminal-agent');
    expect(agent.innerHTML).toContain('<strong>');
    expect(agent.textContent).not.toContain('**');
    expect(agent.textContent).not.toContain('##');
  });

  // 5 — Ligne vivante pendant le tour (SF-30-13).
  it('5 · dit ce que l\'agent fait à l\'instant, avec étapes, tokens et durée (SF-30-13)', () => {
    component.streaming = {
      status: 'running', blocks: [block({ hasOutput: false, output: '' })],
      text: '', tokens: 1234,
    };
    component.elapsedLabel = '0:42';
    fixture.detectChanges();

    const live: HTMLElement = fixture.nativeElement.querySelector('.terminal-live');
    expect(live).not.toBeNull();
    expect(live.textContent).toContain('npm test');
    expect(live.textContent).toContain('1 étape');
    // `toLocaleString('fr-FR')` sépare les milliers par une espace insécable étroite.
    expect(live.textContent).toMatch(/1[\s\u202f\u00a0]234 tokens/);
    expect(live.textContent).toContain('0:42');
  });

  // 6 — Coût du tour affiché (SF-30-05) : tokens et durée en fin de tour.
  it('6 · affiche ce qu\'a coûté le tour quand la consommation est connue (SF-30-05)', () => {
    component.messages = turn({ cost: { elapsedSeconds: 42, tokens: 1234 } });
    fixture.detectChanges();

    const cost: HTMLElement = fixture.nativeElement.querySelector('.terminal-cost');
    expect(cost).not.toBeNull();
    expect(cost.textContent).toMatch(/1[\s\u202f\u00a0]234 tokens/);

    // Consommation inconnue : rien ne s'affiche — un « 0 token » se lirait comme une mesure.
    component.messages = turn();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.terminal-cost')).toBeNull();
  });

  // 7 — Transcription conservée (SF-30-02 / SF-30-09), DES DEUX MOTEURS.
  it('7 · conserve la transcription d\'un tour terminé, quel que soit le moteur (SF-30-09)', () => {
    // Moteur hébergé : blocs déjà appariés par le flux d'agent.
    component.engine = 'HOSTED_SANDBOX';
    component.messages = turn();
    fixture.detectChanges();
    expect(text()).toContain('12 passing');

    // Boucle maison : mêmes blocs, obtenus par l'adaptateur (SF-39-08, D-L4-5).
    component.engine = 'LOCAL_MACHINE';
    component.messages = turn({
      terminal: chatStepsToBlocks([{ type: 'bash', path: 'npm run build', output: 'построено' }]),
    });
    fixture.detectChanges();
    expect(text()).toContain('npm run build');
    expect(text()).toContain('построено');
  });

  // 8 — Mise en valeur Gold (SF-30-03) : badge + accent orange de la charte.
  it('8 · garde la mise en valeur Gold de la charte (SF-30-03)', () => {
    component.runnerHint = 'GIT';
    fixture.detectChanges();

    // Le badge de la charte porte le moteur ; l'accent orange porte la proposition de runner.
    expect(fixture.nativeElement.querySelector('.badge')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.terminal-hint-runner-icon')).not.toBeNull();
  });

  // 9 — Réinitialiser l'environnement (SF-30-06).
  it('9 · permet de repartir d\'un environnement neuf, là où il y en a un (SF-30-06)', () => {
    const emitted: number[] = [];
    component.resetSandbox.subscribe(() => emitted.push(1));
    component.engine = 'HOSTED_SANDBOX';
    fixture.detectChanges();

    const reset = Array.from(fixture.nativeElement.querySelectorAll('button'))
      .find((b) => (b as HTMLElement).textContent!.includes('Réinitialiser')) as HTMLButtonElement;
    reset.click();

    expect(emitted.length).toBe(1);
  });

  // 10 — Aller de l'explorateur au terminal, et retour (SF-30-10).
  it('10 · garde le chemin vers les fichiers du projet (SF-30-10)', () => {
    const emitted: number[] = [];
    component.openFiles.subscribe(() => emitted.push(1));
    fixture.detectChanges();

    const files = Array.from(fixture.nativeElement.querySelectorAll('button'))
      .find((b) => (b as HTMLElement).textContent!.includes('Fichiers')) as HTMLButtonElement;
    files.click();

    expect(emitted.length).toBe(1);
  });

  // 11 — Un tour ne lit que ses propres events (SF-30-11) : pas de rejeu du tour précédent.
  it('11 · n\'affiche pas les blocs du tour précédent dans le tour en cours (SF-30-11)', () => {
    component.messages = turn({ terminal: [block({ command: 'commande-passee' })] });
    component.streaming = {
      status: 'running', blocks: [block({ command: 'commande-en-cours', hasOutput: false, output: '' })],
      text: '', tokens: null,
    };
    fixture.detectChanges();

    const live: HTMLElement = fixture.nativeElement.querySelector('.terminal-live');
    expect(live.textContent).toContain('commande-en-cours');
    expect(live.textContent).not.toContain('commande-passee');
  });

  // 12 — Diagnostic d'erreur fournisseur (SF-30-08) : une commande en échec se voit.
  it('12 · distingue une commande en échec du reste du flux (SF-30-08)', () => {
    component.messages = turn({ terminal: [block({ error: true, output: 'command not found' })] });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.terminal-block.failed')).not.toBeNull();
  });

  // 13 — Session persistante (SF-30-04) : l'état survit d'un message à l'autre.
  it('13 · garde les tours précédents à l\'arrivée d\'un nouveau (SF-30-04)', () => {
    component.messages = turn();
    fixture.detectChanges();

    component.messages = [
      ...turn(),
      { id: 'u2', role: 'USER', content: 'et le build ?', actions: [] },
    ];
    fixture.detectChanges();

    // Rien n'est perdu : le fil s'allonge, il ne se remplace pas.
    expect(text()).toContain('lance les tests');
    expect(text()).toContain('12 passing');
    expect(text()).toContain('et le build ?');
  });

  // Acquis F-38 côté runner, rappelés au §4 : porte de confirmation, audit, coupe-circuit,
  // interruption. Le sélecteur de branche (F-31) vit dans l'explorateur, hors de ce composant.
  it('F-38 · garde la porte de confirmation, l\'audit, le coupe-circuit et l\'interruption', () => {
    component.executionTarget = 'RUNNER';
    component.submitting = true;
    component.pendingConfirmation = {
      toolUseId: 'tu_1', tool: 'bash', detail: 'rm -rf /tmp/x',
      source: 'LOCAL_MACHINE', answering: false, denying: false, reason: '',
    };
    fixture.detectChanges();

    expect(text()).toContain('rm -rf /tmp/x');
    expect(fixture.nativeElement.querySelector('.terminal-runner-audit')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.terminal-runner-kill')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.terminal-interrupt')).not.toBeNull();
  });
});
