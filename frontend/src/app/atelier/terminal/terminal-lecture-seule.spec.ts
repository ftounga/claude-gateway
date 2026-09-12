import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierExecStreamingItem, AtelierPendingConfirmation } from '../atelier.types';

/**
 * **Le terminal, en lecture seule** (F-83 / SF-83-01).
 *
 * <p>F-76 avait livré des <b>vignettes</b> de six lignes ; le PO demandait de voir <b>les quatre
 * terminaux</b>. Ce que ces tests verrouillent, c'est la condition pour que la mosaïque montre le
 * terminal — le vrai, avec le contenu réel de son flux — sans en fabriquer une copie qui
 * divergerait à la première retouche : <b>le même composant</b>, privé de tout ce qui sert à agir.</p>
 */
describe('AtelierTerminalComponent — lecture seule (F-83 / SF-83-01)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
  });

  function query(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  const runningTurn: AtelierExecStreamingItem = {
    status: 'running',
    tokens: 4321,
    text: 'Je lance les tests.',
    plan: [{ title: 'Lancer la suite', status: 'in_progress' }],
    blocks: [
      {
        tool: 'bash',
        command: 'npm test',
        toolUseId: 'tu_1',
        threadId: null,
        output: 'PASS src/app.spec.ts',
        hasOutput: true,
        error: false,
        expanded: false,
      },
      {
        tool: 'bash',
        command: 'npm run build',
        toolUseId: 'tu_2',
        threadId: null,
        output: '',
        hasOutput: false,
        error: false,
        expanded: false,
      },
    ],
  };

  const pending: AtelierPendingConfirmation = {
    toolUseId: 'sevt_1',
    source: 'LOCAL_MACHINE',
    tool: 'bash',
    detail: 'rm -rf build',
    answering: false,
    denying: false,
    reason: '',
    deadline: null,
    timeoutMs: null,
  };

  // ----------------------------------------------------- ce qui disparaît : tout ce qui agit

  it('ne rend ni invite, ni barre, ni réglages : il ne reste que le flux', () => {
    component.readOnly = true;
    component.streaming = runningTurn;
    component.elapsedLabel = '0:12';
    component.executionTarget = 'RUNNER';
    component.gitProject = true;
    fixture.detectChanges();

    expect(query('form.terminal-input')).toBeNull();
    expect(query('.terminal-bar')).toBeNull();
    expect(query('.terminal-target')).toBeNull();
    expect(query('.terminal-host-state')).toBeNull();
    // Le flux, lui, est bien là — c'est tout l'objet de F-83.
    expect(query('.terminal-scrollback')).not.toBeNull();
  });

  it('ne montre aucun bandeau de plafond : une lecture n\'ouvre rien, elle ne peut être refusée', () => {
    component.readOnly = true;
    component.liveLimitReached = true;
    component.liveTerminals = [];
    fixture.detectChanges();

    expect(query('.terminal-live-limit')).toBeNull();
  });

  it('n\'émet aucun envoi quand on force `submit()` — la lecture seule l\'est aussi dans le code', () => {
    component.readOnly = true;
    component.draft = 'lance les tests';
    fixture.detectChanges();
    let sent = 0;
    component.send.subscribe(() => (sent += 1));

    component.submit();

    expect(sent).toBe(0);
  });

  // ----------------------------------------------------- ce qui reste : le contenu RÉEL du flux

  it('rend le contenu réel du flux — commandes, sortie, commentaire, plan, ligne vivante', () => {
    component.readOnly = true;
    component.streaming = runningTurn;
    component.elapsedLabel = '0:12';
    fixture.detectChanges();

    const commands = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.terminal-command code'),
    ).map((node) => node.textContent?.trim());

    expect(commands).toEqual(['npm test', 'npm run build']);
    expect(query('.terminal-output')?.textContent).toContain('PASS src/app.spec.ts');
    expect(query('.terminal-agent')?.textContent).toContain('Je lance les tests.');
    expect(query('.terminal-plan')?.textContent).toContain('Lancer la suite');
    expect(query('.terminal-live-action')?.textContent?.trim()).toBe('npm run build');
    expect(query('.terminal-live-elapsed')?.textContent?.trim()).toBe('0:12');
  });

  it('rend le MÊME flux qu\'en mode complet : ce sont les mêmes lignes, pas un résumé', () => {
    component.streaming = runningTurn;
    component.elapsedLabel = '0:12';
    fixture.detectChanges();
    const full = query('.terminal-scrollback')?.textContent ?? '';

    component.readOnly = true;
    fixture.detectChanges();
    const readOnly = query('.terminal-scrollback')?.textContent ?? '';

    expect(readOnly).toBe(full);
  });

  // ------------------------------------------------- l'exigence littérale du PO : le fond

  it('a EXACTEMENT le fond du terminal — --cg-navy-2, #141D33', () => {
    component.readOnly = true;
    fixture.detectChanges();
    const view = query('.terminal-view') as HTMLElement;

    // On doit RECONNAÎTRE un terminal, pas découvrir un composant : aucune couleur nouvelle, et
    // celle-ci est nommée par le PO, par son hexadécimal.
    expect(getComputedStyle(view).backgroundColor).toBe('rgb(20, 29, 51)');
  });

  // ------------------------------------- l'exigence non négociable : ce qui attend se voit

  it('signale FRANCHEMENT une autorisation attendue, par un libellé écrit et sans bouton', () => {
    component.readOnly = true;
    component.pendingConfirmation = pending;
    fixture.detectChanges();
    const notice = query('.terminal-ask-readonly') as HTMLElement;

    expect(notice).not.toBeNull();
    expect(notice.textContent).toContain('Attend votre autorisation');
    expect(notice.textContent).toContain('rm -rf build');
    // Décider est un geste : il se prend dans le terminal entier, devant le flux entier.
    expect(notice.querySelector('button')).toBeNull();
    expect(query('.terminal-ask-allow')).toBeNull();
    expect(text()).toContain('Entrez dans le terminal pour autoriser ou refuser.');
  });

  it('emprunte la palette de statut §5 « En attente » — aucun quatrième registre de couleur', () => {
    component.readOnly = true;
    component.pendingConfirmation = pending;
    fixture.detectChanges();
    const title = query('.terminal-ask-readonly-title') as HTMLElement;

    expect(getComputedStyle(title).backgroundColor).toBe('rgb(255, 248, 225)');
    expect(getComputedStyle(title).color).toBe('rgb(249, 168, 37)');
  });

  it('dit « au repos » plutôt que d\'inviter à saisir : il n\'y a pas d\'invite ici', () => {
    component.readOnly = true;
    component.messages = [];
    component.streaming = null;
    fixture.detectChanges();

    expect(query('.terminal-hint--idle')?.textContent).toContain('Au repos');
    expect(text()).not.toContain('Décrivez la tâche à réaliser');
  });

  // ------------------------------------------------------------------ non-régression

  it('par défaut, le terminal reste le terminal complet : barre, réglages et invite', () => {
    component.executionTarget = 'RUNNER';
    fixture.detectChanges();

    expect(component.readOnly).toBeFalse();
    expect(query('.terminal-bar')).not.toBeNull();
    expect(query('.terminal-target')).not.toBeNull();
    expect(query('form.terminal-input')).not.toBeNull();
    expect(query('.terminal-host-state')).not.toBeNull();
  });

  it('garde son bloc de décision complet en mode normal — F-33 n\'est pas touché', () => {
    component.pendingConfirmation = pending;
    fixture.detectChanges();

    expect(query('.terminal-ask')).not.toBeNull();
    expect(query('.terminal-ask-readonly')).toBeNull();
    expect(query('.terminal-ask-allow')).not.toBeNull();
  });
});
