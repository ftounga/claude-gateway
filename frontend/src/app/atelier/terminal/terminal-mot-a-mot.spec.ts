import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierExecStreamingItem } from '../atelier.types';

/**
 * **Le terminal répond mot à mot** (F-116 / SF-116-02) : la ligne vivante affiche le texte de l'agent
 * dès le premier delta, et le laisse grandir, sans attendre la fin du tour. Le composant est
 * présentation pure — aucun réseau ; on lui donne un `streaming.text` partiel puis complété, et on
 * vérifie que l'écran suit à chaque étape.
 */
describe('AtelierTerminalComponent — réponse mot à mot (F-116 / SF-116-02)', () => {
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

  function liveText(text: string): AtelierExecStreamingItem {
    // Objet neuf à chaque delta, comme la mise à jour immuable du signal côté page.
    return { status: 'running', tokens: null, text, plan: [], blocks: [] };
  }

  function agentLine(): string {
    const el = (fixture.nativeElement as HTMLElement).querySelector('.terminal-agent');
    return el?.textContent?.trim() ?? '';
  }

  it('affiche un texte partiel dès le premier delta, sans attendre la fin', () => {
    component.streaming = liveText('Bon');
    fixture.detectChanges();

    // Le texte incomplet est déjà à l'écran : aucun tampon n'attend le tour entier.
    expect(agentLine()).toContain('Bon');
  });

  it('laisse la ligne vivante grandir à mesure que les deltas arrivent', () => {
    component.streaming = liveText('Bon');
    fixture.detectChanges();
    expect(agentLine()).toContain('Bon');

    component.streaming = liveText('Bonjour le');
    fixture.detectChanges();
    expect(agentLine()).toContain('Bonjour le');

    component.streaming = liveText('Bonjour le monde');
    fixture.detectChanges();
    expect(agentLine()).toContain('Bonjour le monde');
  });

  it("ne rend aucune ligne d'agent tant qu'aucun delta n'est arrivé", () => {
    component.streaming = liveText('');
    fixture.detectChanges();

    // Ligne vivante ouverte mais texte vide : pas de paragraphe d'agent creux.
    expect((fixture.nativeElement as HTMLElement).querySelector('.terminal-agent')).toBeNull();
  });
});
