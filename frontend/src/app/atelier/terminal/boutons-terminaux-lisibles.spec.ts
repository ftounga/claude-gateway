import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **Les boutons des terminaux sont lisibles** (F-30 / SF-30-15).
 *
 * <p>Sur le fond sombre d'un terminal NON-Teams (projet `--cg-primary`, tuile de mosaïque en lecture
 * seule `--cg-navy-2`), les composants Angular Material sans encre propre héritaient des inks des
 * écrans clairs : libellé de bouton texte ~2,6:1, sélecteur `mat-button-toggle` ~1,0:1, boutons
 * cerclés et boutons-icônes de même ; et le bloc « Courriel envoyé », transparent, héritait de
 * l'encre foncée du corps de page (~1,3:1). SF-89-07 ne l'avait corrigé que sous la peau Teams.</p>
 *
 * <p>Ces tests tiennent la réparation par les COULEURS CALCULÉES, styles globaux chargés : chaque
 * libellé de bouton, chaque texte de bouton-toggle, chaque bouton-icône et le bloc courriel tiennent
 * l'AA sur leur fond effectif. Un balayage du DOM, pas une liste de sélecteurs qui vieillirait.</p>
 */
describe('AtelierTerminalComponent — boutons lisibles hors Teams (F-30 / SF-30-15)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  // Les fonds sombres d'un terminal, par leurs valeurs calculées.
  const PRIMARY = 'rgb(26, 58, 92)'; // --cg-primary #1A3A5C — terminal de projet / poste
  const NAVY_2 = 'rgb(20, 29, 51)'; // --cg-navy-2 #141D33 — tuile de mosaïque, blocs internes
  const TEAMS_BG = 'rgb(35, 26, 54)'; // --cg-terminal-teams-bg #231A36 — contrôle « ne change pas »

  // Les contrôles que cette subfeature répare : boutons SANS encre propre (texte, cerclé, icône),
  // le sélecteur de cible, et le bloc courriel. Les boutons PLEINS de marque (or + blanc, `styles.scss`)
  // portent leur propre paire de couleurs — hors constat, exclus.
  const SCOPE = '.mat-mdc-button, .mat-mdc-outlined-button, .mat-mdc-icon-button, mat-button-toggle, .terminal-email';

  // ------------------------------------------------------------------ calcul du contraste (WCAG 2.x)

  type Rgba = [number, number, number, number];

  function parse(color: string): Rgba {
    const parts = color.match(/[\d.]+/g)?.map(Number) ?? [0, 0, 0, 0];
    return [parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : 1];
  }

  function over(top: Rgba, bottom: Rgba): Rgba {
    const a = top[3];
    return [
      top[0] * a + bottom[0] * (1 - a),
      top[1] * a + bottom[1] * (1 - a),
      top[2] * a + bottom[2] * (1 - a),
      1,
    ];
  }

  function luminance([r, g, b]: Rgba): number {
    const channel = (v: number) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  }

  function contrast(a: Rgba, b: Rgba): number {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  }

  function token(name: string): Rgba {
    const probe = document.createElement('span');
    probe.style.color = `var(${name})`;
    document.body.appendChild(probe);
    const value = getComputedStyle(probe).color;
    probe.remove();
    return parse(value);
  }

  /** Le fond sur lequel l'œil lit l'élément : le premier fond opaque en remontant, fondu au besoin. */
  function groundOf(element: Element, root: Element): Rgba {
    const layers: Rgba[] = [];
    for (let node: Element | null = element; node; node = node.parentElement) {
      const layer = parse(getComputedStyle(node).backgroundColor);
      if (layer[3] > 0) {
        layers.push(layer);
        if (layer[3] >= 1) {
          break;
        }
      }
      if (node === root) {
        break;
      }
    }
    let color: Rgba = layers.length > 0 && layers[layers.length - 1][3] >= 1
      ? layers.pop() as Rgba
      : parse(getComputedStyle(root).backgroundColor);
    while (layers.length > 0) {
      color = over(layers.pop() as Rgba, color);
    }
    return color;
  }

  function opacityOf(element: Element, root: Element): number {
    let opacity = 1;
    for (let node: Element | null = element; node; node = node.parentElement) {
      opacity *= Number(getComputedStyle(node).opacity);
      if (node === root) {
        break;
      }
    }
    return opacity;
  }

  function hasOwnText(element: Element): boolean {
    return Array.from(element.childNodes)
      .some((node) => node.nodeType === Node.TEXT_NODE && (node.textContent ?? '').trim().length > 0);
  }

  function describeElement(element: Element): string {
    const classes = Array.from(element.classList).join('.');
    return `${element.tagName.toLowerCase()}${classes ? '.' + classes : ''} « ${(element.textContent ?? '').trim().slice(0, 30)} »`;
  }

  interface Finding {
    element: string;
    ratio: number;
    ground: string;
  }

  /**
   * Chaque élément porteur de texte À L'INTÉRIEUR d'un contrôle réparé par cette subfeature : contraste
   * ≥ 4,5:1 (≥ 3:1 pour une icône `mat-icon` ou un grand texte) contre son fond effectif calculé.
   */
  function sweep(root: Element): { swept: number; failures: Finding[] } {
    const failures: Finding[] = [];
    let swept = 0;
    for (const element of Array.from(root.querySelectorAll('*'))) {
      if (!hasOwnText(element) || !element.closest(SCOPE) || element.getClientRects().length === 0) {
        continue;
      }
      const style = getComputedStyle(element);
      if (style.visibility === 'hidden' || element.closest(':disabled, [aria-disabled="true"]')) {
        continue; // un composant inactif est exempté par le WCAG
      }
      swept += 1;
      const ground = groundOf(element, root);
      const ink = parse(style.color);
      ink[3] *= opacityOf(element, root);
      const ratio = contrast(over(ink, ground), ground);
      const size = parseFloat(style.fontSize);
      const bold = Number(style.fontWeight) >= 700;
      const large = size >= 24 || (bold && size >= 18.66);
      const icon = element.tagName.toLowerCase() === 'mat-icon';
      const required = icon || large ? 3 : 4.5;
      if (ratio < required) {
        failures.push({ element: describeElement(element), ratio: Number(ratio.toFixed(2)), ground: String(ground) });
      }
    }
    return { swept, failures };
  }

  // ------------------------------------------------------------------ un terminal non-Teams chargé

  const longOutput = Array.from({ length: 40 }, (_, i) => `ligne ${i + 1}`).join('\n');

  const thread: AtelierThreadItem[] = [
    { id: 'u1', role: 'USER', content: 'lance les tests', actions: [] },
    {
      id: 'a1',
      role: 'ASSISTANT',
      content: 'Voici le résultat.',
      actions: [],
      terminal: [
        // Un bloc à sortie longue → bouton « Afficher tout » (geste orange de la charte).
        { tool: 'bash', command: 'npm test', toolUseId: 't1', threadId: null, output: longOutput, hasOutput: true, error: false, expanded: false },
        // Le bloc « Courriel envoyé », dans ses deux états sur le fond sombre.
        {
          tool: 'email_me', command: '', toolUseId: 't2', threadId: null, output: '', hasOutput: false, error: false, expanded: false,
          email: { emailId: '', recipient: 'franck@cagip.fr', recipientVerified: true, clientName: 'CAGIP', subject: 'Compte rendu', attachmentCount: 0, status: 'SENT' },
        },
        {
          tool: 'email_me', command: '', toolUseId: 't3', threadId: null, output: '', hasOutput: false, error: false, expanded: false,
          email: { emailId: '', recipient: 'x@cagip.fr', recipientVerified: false, clientName: 'CAGIP', subject: 'Note', attachmentCount: 0, status: 'FAILED' },
        },
        // Le bloc « Page publiée » : ÎLOT BLANC — ses boutons doivent rester lisibles (encre foncée).
        {
          tool: 'page_publish', command: '', toolUseId: 't4', threadId: null, output: '', hasOutput: false, error: false, expanded: false,
          page: { pageId: 'p1', title: 'Rapport', description: 'un rapport', version: 2 },
        },
      ],
      // Un tour au plafond de dépense → bouton « Racheter des tokens » (geste orange).
      budgetReached: true,
    },
  ];

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({
      id: 'p1', title: 'Rapport', description: 'un rapport', space: 'HOST', hostId: 'h1', workspaceId: 'w1',
      currentVersion: 2, createdAt: '', updatedAt: '', viewUrl: '',
    }) as never);

    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
    component.projectId = 'w1';
    component.hostName = 'CAGIP';
    component.hostId = 'h1';
  });

  function view(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('.terminal-view') as HTMLElement;
  }

  /** Un terminal de projet non-Teams, chargé de tous les contrôles réparés par la subfeature. */
  function loadProjectTerminal(): void {
    component.messages = thread;
    // Cible « Ma machine » → le sélecteur `mat-button-toggle`, l'état du poste, les boutons-icônes
    // (actualiser / journal runner, coupe-circuit), et la commande de reprise à copier.
    component.executionTarget = 'RUNNER';
    component.runnerStatus = { connected: false, paired: true, rootName: 'dev' } as never;
    component.engine = 'HOSTED_SANDBOX';
    component.gitProject = true;
    component.instructionsPath = 'CLAUDE.md';
    // Au repos : le sélecteur de cible et « Publier » sont alors ACTIFS (donc balayés — un contrôle
    // désactivé est exempté par le WCAG). Le champ porte un brouillon pour que « Envoyer » soit actif.
    component.submitting = false;
    component.steerable = true;
    component.draft = 'continue';
    component.live = true;
    component.elapsedLabel = '00:12';
    // Une demande d'autorisation → Autoriser (plein, exclu), Tout autoriser / Refuser (cerclés).
    component.pendingConfirmation = {
      toolUseId: 'tu', tool: 'bash', detail: 'rm -rf build', source: 'LOCAL_MACHINE',
      answering: false, denying: false, reason: '', deadline: null, timeoutMs: null,
    } as never;
    component.runnerElevated = true;
    component.confirmationCountdown = 'Réponse attendue sous 1:30';
    fixture.detectChanges();
  }

  // ------------------------------------------------------------------ le fond ne change pas

  it('un terminal de projet garde son fond `--cg-primary`, une tuile `--cg-navy-2`', () => {
    loadProjectTerminal();
    expect(getComputedStyle(view()).backgroundColor).toBe(PRIMARY);

    component.readOnly = true;
    fixture.detectChanges();
    expect(getComputedStyle(view()).backgroundColor).toBe(NAVY_2);
  });

  // ------------------------------------------------------------------ l'AA, automatisé

  it('CHAQUE bouton, chaque texte de bouton-toggle, chaque bouton-icône et le bloc courriel tiennent l\'AA (balayage)', () => {
    loadProjectTerminal();

    const result = sweep(view());

    // Le balayage a réellement vu les contrôles chargés — sinon il passerait sur un écran vide.
    expect(result.swept).toBeGreaterThan(15);
    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
  });

  it('la même chose sur une TUILE de mosaïque en lecture seule (fond `--cg-navy-2`)', () => {
    component.messages = thread;
    component.readOnly = true;
    fixture.detectChanges();

    const result = sweep(view());
    expect(result.swept).toBeGreaterThan(0);
    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
  });

  it('le sélecteur « Où s\'exécutent les outils » est lisible, sélectionné comme non sélectionné', () => {
    loadProjectTerminal();

    const toggles = Array.from(view().querySelectorAll<HTMLElement>('mat-button-toggle .mat-button-toggle-label-content'));
    expect(toggles.length).toBe(2);
    for (const label of toggles) {
      const ratio = contrast(over(parse(getComputedStyle(label).color), groundOf(label, view())), groundOf(label, view()));
      expect(ratio).withContext(label.textContent?.trim() ?? '').toBeGreaterThanOrEqual(4.5);
    }
  });

  it('le bloc « Courriel envoyé » est lisible, échec compris — filet rouge, pas couleur du texte', () => {
    loadProjectTerminal();

    const emails = Array.from(view().querySelectorAll<HTMLElement>('.terminal-email'));
    expect(emails.length).toBe(2);
    for (const block of emails) {
      for (const el of Array.from(block.querySelectorAll<HTMLElement>('*')).filter(hasOwnText)) {
        const ratio = contrast(over(parse(getComputedStyle(el).color), groundOf(el, view())), groundOf(el, view()));
        const min = el.tagName.toLowerCase() === 'mat-icon' ? 3 : 4.5;
        expect(ratio).withContext(describeElement(el)).toBeGreaterThanOrEqual(min);
      }
    }
    // L'échec reste dit par le filet gauche rouge (jamais la seule couleur du texte).
    const failed = view().querySelector('.terminal-email[data-status="FAILED"]') as HTMLElement;
    expect(getComputedStyle(failed).borderLeftColor).toBe('rgb(211, 47, 47)'); // --cg-error #D32F2F
  });

  // ------------------------------------------------------------------ ce qui ne change pas

  it('AUCUN autre écran ne change : un terminal Teams garde sa surface `#231A36`', () => {
    component.messages = thread;
    component.teamsTerminal = true;
    fixture.detectChanges();

    expect(getComputedStyle(view()).backgroundColor).toBe(TEAMS_BG);
    // Le sélecteur de cible du terminal Teams porte l'encre Teams, pas celle de la charte claire :
    // les règles de SF-30-15 sont bornées par `:not(.terminal-view--teams)`.
    const target = view().querySelector('.terminal-target') as HTMLElement;
    const [r, g, b] = token('--cg-terminal-teams-text').map(Math.round);
    expect(getComputedStyle(target).color).toBe(`rgb(${r}, ${g}, ${b})`);
  });

  it('l\'îlot blanc (page publiée) garde des boutons à encre foncée, non affectés par la cascade', () => {
    loadProjectTerminal();

    const stroked = view().querySelector('.page-block .mat-mdc-outlined-button') as HTMLElement;
    expect(stroked).withContext('bouton « Plein écran »').not.toBeNull();
    const label = stroked.querySelector('.mdc-button__label') as HTMLElement;
    const ground = groundOf(label, view()); // fond blanc de l'îlot
    const ratio = contrast(over(parse(getComputedStyle(label).color), ground), ground);
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });
});
