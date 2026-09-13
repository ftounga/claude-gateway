import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';
import { MarkdownPipe } from '../../shared/markdown.pipe';

/**
 * **Les titres du terminal sont lisibles** (F-30 / SF-30-14).
 *
 * <p>Constat du PO en production : « dans le terminal, les titres s'affichent en noir ». Le
 * commentaire de l'agent est inséré par `innerHTML` ; en encapsulation émulée, ces éléments ne
 * portent pas l'attribut du composant, et les styles descendants de `.terminal-agent` ne les
 * atteignaient pas — la règle globale `h1, h2, h3, h4` (couleur des écrans clairs) l'emportait.</p>
 *
 * <p>Ces tests lisent la <b>couleur calculée</b>, avec la feuille globale chargée : c'est la seule
 * vérification qui voit ce que voit l'œil. Une assertion sur le DOM seul passait avant le correctif.</p>
 */
describe('AtelierTerminalComponent — Markdown lisible sur le fond du terminal (F-30 / SF-30-14)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  // Jetons de la charte (§2), par leur valeur calculée.
  const SURFACE = 'rgb(255, 255, 255)'; // --cg-surface
  const TEXT_PRIMARY = 'rgb(28, 43, 58)'; // --cg-text-primary (#1C2B3A) — la couleur du défaut
  const ORANGE_2 = 'rgb(240, 149, 79)'; // --cg-orange-2
  const NAVY_2 = 'rgb(20, 29, 51)'; // --cg-navy-2
  const DIVIDER = 'rgb(224, 228, 234)'; // --cg-divider

  const reply = [
    '# Titre un',
    '',
    '## Titre deux',
    '',
    '### Titre trois',
    '',
    '#### Titre quatre',
    '',
    'Voir [la doc](https://example.com) et `npm test`.',
    '',
    '> Une citation.',
    '',
    '| Colonne | Valeur |',
    '| --- | --- |',
    '| a | b |',
  ].join('\n');

  const thread: AtelierThreadItem[] = [
    { id: 'u1', role: 'USER', content: 'décris le projet', actions: [] },
    { id: 'a1', role: 'ASSISTANT', content: reply, actions: [] },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
  });

  function agentBlock(): HTMLElement {
    const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.terminal-agent');
    expect(el).withContext('bloc du commentaire de l\'agent introuvable').not.toBeNull();
    return el as HTMLElement;
  }

  function inside<T extends Element>(selector: string): T {
    const el = agentBlock().querySelector<T>(selector);
    expect(el).withContext(`${selector} absent du rendu Markdown`).not.toBeNull();
    return el as T;
  }

  function expectReadable(): void {
    for (const level of ['h1', 'h2', 'h3', 'h4']) {
      expect(getComputedStyle(inside(level)).color)
        .withContext(`${level} du terminal`)
        .toBe(SURFACE);
    }
    expect(getComputedStyle(inside('a')).color).withContext('lien').toBe(ORANGE_2);
    expect(getComputedStyle(inside('p code')).backgroundColor).withContext('code en ligne').toBe(NAVY_2);
    expect(getComputedStyle(inside('blockquote')).color).withContext('citation').toBe(DIVIDER);
    expect(getComputedStyle(inside('td')).borderLeftColor).withContext('cellule de tableau').toBe(NAVY_2);
    expect(getComputedStyle(inside('td')).color).withContext('texte de cellule').toBe(DIVIDER);
  }

  it('rend un h2 inséré par innerHTML dans la couleur du terminal, pas dans celle des écrans clairs', () => {
    component.messages = thread;
    fixture.detectChanges();

    const color = getComputedStyle(inside('h2')).color;
    expect(color).not.toBe(TEXT_PRIMARY);
    expect(color).toBe(SURFACE);
  });

  it('terminal de projet : titres, lien, code en ligne, citation et tableau sont lisibles', () => {
    component.messages = thread;
    fixture.detectChanges();

    expectReadable();
  });

  it('garde la hiérarchie par la graisse, jamais par la taille (SF-30-12)', () => {
    component.messages = thread;
    fixture.detectChanges();

    const blockSize = getComputedStyle(agentBlock()).fontSize;
    expect(getComputedStyle(inside('h1')).fontSize).toBe(blockSize);
    expect(getComputedStyle(inside('h1')).fontWeight).toBe('700');
    // Premier élément collé au bloc : le combinateur `>` passe lui aussi sous `::ng-deep`.
    expect(getComputedStyle(inside('h1')).marginTop).toBe('0px');
  });

  it('ligne vivante (tour en cours) : même rendu que l\'historique', () => {
    component.streaming = { status: 'running', tokens: null, text: reply, plan: [], blocks: [] };
    fixture.detectChanges();

    expectReadable();
  });

  it('terminal de poste : lisible', () => {
    component.hostName = 'poste-paris';
    component.executionTarget = 'RUNNER';
    component.messages = thread;
    fixture.detectChanges();

    expectReadable();
  });

  it('terminal Teams : lisible', () => {
    component.teamsTerminal = true;
    component.messages = thread;
    fixture.detectChanges();

    expectReadable();
  });

  it('tuile de mosaïque (lecture seule) : lisible', () => {
    component.readOnly = true;
    component.messages = thread;
    fixture.detectChanges();

    expectReadable();
  });
});

/** Un Markdown rendu HORS du terminal, comme le chat ou l'aide. */
@Component({
  selector: 'app-hors-terminal',
  imports: [MarkdownPipe],
  template: '<div class="markdown-body" [innerHTML]="text | markdown"></div>',
})
class HorsTerminalComponent {
  text = '## Titre du chat';
}

describe('Markdown hors du terminal — aucun effet (F-30 / SF-30-14)', () => {
  it('un h2 rendu ailleurs garde la couleur de texte des écrans clairs', async () => {
    await TestBed.configureTestingModule({ imports: [HorsTerminalComponent] }).compileComponents();
    const fixture = TestBed.createComponent(HorsTerminalComponent);
    fixture.detectChanges();

    const h2 = (fixture.nativeElement as HTMLElement).querySelector('h2') as HTMLElement;
    const expected = getComputedStyle(document.documentElement).getPropertyValue('--cg-text-primary').trim();
    const probe = document.createElement('span');
    probe.style.color = expected;
    document.body.appendChild(probe);
    const expectedRgb = getComputedStyle(probe).color;
    probe.remove();

    expect(getComputedStyle(h2).color).toBe(expectedRgb);
  });
});
