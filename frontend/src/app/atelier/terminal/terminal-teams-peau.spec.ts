import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent, TEAMS_TERMINAL_BAR_LABEL } from './atelier-terminal.component';
import { AtelierExecStreamingItem, AtelierThreadItem } from '../atelier.types';
import { AtelierTeamsCard } from '../../core/models/atelier.models';
import { TeamsLink } from '../teams/teams-link.service';

/**
 * **Le terminal Teams se reconnaît au premier regard** (F-89 / SF-89-07).
 *
 * <p>Le PO avait demandé « un vrai basculement visuel » ; SF-89-03 l'avait réduit à une police. Ces
 * tests tiennent la réparation : la surface « Prune » (jetons `--cg-terminal-teams-*`), <b>et
 * l'AA de tout ce qui s'y lit</b> — vérifié par les couleurs CALCULÉES, styles globaux chargés,
 * sur un terminal Teams rendu avec tout ce qu'il sait afficher. Une liste de sélecteurs vieillirait
 * au premier élément ajouté ; un balayage du DOM, non.</p>
 */
describe('AtelierTerminalComponent — la peau du terminal Teams (F-89 / SF-89-07)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  // La surface « Prune », par ses valeurs calculées.
  const TEAMS_BG = 'rgb(35, 26, 54)'; // --cg-terminal-teams-bg #231A36
  const TEAMS_BAR = 'rgb(27, 20, 41)'; // --cg-terminal-teams-bar #1B1429
  const TEAMS_CARD = 'rgb(46, 35, 69)'; // --cg-terminal-teams-card #2E2345
  const TEAMS_RULE = 'rgb(67, 51, 95)'; // --cg-terminal-teams-rule #43335F
  const PRIMARY = 'rgb(26, 58, 92)'; // --cg-primary #1A3A5C — tout autre terminal
  const SURFACES = [TEAMS_BG, TEAMS_BAR, TEAMS_CARD, TEAMS_RULE];

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
  function groundOf(element: Element, root: Element): { color: Rgba; owner: Element } {
    const layers: Rgba[] = [];
    let owner: Element = root;
    for (let node: Element | null = element; node; node = node.parentElement) {
      const layer = parse(getComputedStyle(node).backgroundColor);
      if (layer[3] > 0) {
        layers.push(layer);
        if (layer[3] >= 1) {
          owner = node;
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
    return { color, owner };
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
   * Chaque élément porteur de texte posé SUR LA SURFACE TEAMS : contraste ≥ 4,5:1 (3:1 pour une
   * icône ou un grand texte). Ce qui a sa propre pastille (§5, §9) est vérifié à part : sa paire de
   * couleurs est celle de la charte, et c'est son FOND qui doit se détacher de la surface.
   */
  function sweep(root: Element): { texts: number; failures: Finding[]; pastilles: Finding[] } {
    const failures: Finding[] = [];
    const pastilles: Finding[] = [];
    let texts = 0;
    for (const element of Array.from(root.querySelectorAll('*'))) {
      if (!hasOwnText(element) || element.getClientRects().length === 0) {
        continue;
      }
      const style = getComputedStyle(element);
      if (style.visibility === 'hidden' || element.closest(':disabled, [aria-disabled="true"]')) {
        continue; // un composant inactif est exempté par le WCAG
      }
      const ground = groundOf(element, root);
      const onSurface = SURFACES.includes(getComputedStyle(ground.owner).backgroundColor);
      if (!onSurface) {
        // Une pastille : son fond contre la surface qui la porte.
        const beneath = ground.owner.parentElement
          ? groundOf(ground.owner.parentElement, root)
          : ground;
        const ratio = contrast(ground.color, beneath.color);
        if (ratio < 3) {
          pastilles.push({ element: describeElement(element), ratio, ground: String(ground.color) });
        }
        continue;
      }
      texts += 1;
      const ink = parse(style.color);
      ink[3] *= opacityOf(element, root);
      const ratio = contrast(over(ink, ground.color), ground.color);
      const size = parseFloat(style.fontSize);
      const bold = Number(style.fontWeight) >= 700;
      const large = size >= 24 || (bold && size >= 18.66);
      const icon = element.tagName.toLowerCase() === 'mat-icon';
      const required = icon || large ? 3 : 4.5;
      if (ratio < required) {
        failures.push({ element: describeElement(element), ratio: Number(ratio.toFixed(2)), ground: String(ground.color) });
      }
    }
    return { texts, failures, pastilles };
  }

  // ------------------------------------------------------------------ un terminal Teams chargé

  const card: AtelierTeamsCard = {
    kind: 'MEETING_CARD',
    title: 'Comité de migration',
    subtitle: '12 septembre, 45 min',
    window: 'du 5 au 12 septembre, 47 messages lus',
    gaps: ['3 messages non reconnus le 9 septembre'],
    recordingNotice: 'Ce compte rendu provient d\'un enregistrement local.',
    sections: [
      {
        title: 'Ce qu\'on attend de vous',
        lines: [
          {
            text: 'Fournir le schéma réseau avant vendredi',
            author: 'Paul',
            at: new Date(2026, 8, 12, 14, 32).toISOString(),
            messageId: 'm-1',
            webUrl: 'https://teams.microsoft.com/l/message/m-1',
            certainty: 'EXPLICITE',
          },
          {
            text: 'La migration passe au T3',
            author: 'Claire',
            at: new Date(2026, 8, 12, 14, 40).toISOString(),
            messageId: 'm-2',
            webUrl: '',
            certainty: 'A_CONFIRMER',
          },
        ],
      },
    ],
    moments: [
      {
        at: new Date(2026, 8, 12, 14, 48).toISOString(),
        quote: 'Le planning décale au T3',
        speaker: 'Claire',
        imageId: '',
        webUrl: 'https://teams.microsoft.com/l/t?at=1932',
      },
    ],
  } as AtelierTeamsCard;

  const reply = [
    '## Ce qu\'on attend de vous',
    '',
    'Voir [le fil](https://example.com), **important**, et `npm test`.',
    '',
    '> Une citation.',
    '',
    '| Colonne | Valeur |',
    '| --- | --- |',
    '| a | b |',
  ].join('\n');

  const thread: AtelierThreadItem[] = [
    { id: 'u1', role: 'USER', content: 'résume la réunion Data Platform', actions: [] },
    {
      id: 'a1',
      role: 'ASSISTANT',
      content: reply,
      actions: [],
      terminal: [
        { tool: 'bash', command: 'ls ~/dev', toolUseId: 't1', threadId: null, output: 'corp\nsocle', hasOutput: true, error: false, expanded: false },
        { tool: 'bash', command: 'cat absent', toolUseId: 't2', threadId: null, output: 'No such file', hasOutput: true, error: true, expanded: false },
      ],
      cost: { elapsedSeconds: 41, tokens: 12_000 },
      interrupted: true,
      budgetReached: true,
      diffs: [
        {
          path: 'notes.md',
          added: true,
          diff: '@@ -1 +1 @@\n-ancien\n+nouveau\n contexte',
          addedLines: 1,
          removedLines: 1,
          omittedLines: 2,
          unreadable: false,
          expanded: true,
        },
      ],
    },
  ];

  const streaming: AtelierExecStreamingItem = {
    status: 'running',
    tokens: 3_400,
    text: '### En cours',
    plan: [
      { title: 'Lire la transcription', status: 'done' },
      { title: 'Classer les engagements', status: 'active' },
      { title: 'Rédiger', status: 'pending' },
    ],
    blocks: [
      { tool: 'teams_meeting_card', toolUseId: 'tu_1', threadId: null, output: '', hasOutput: false, error: false, expanded: false, card },
    ],
  };

  const linked = { state: 'LINKED', label: 'Teams relié', sentence: 'Relié.', remedy: '' } as unknown as TeamsLink;
  const changed = { state: 'TEAMS_CHANGED', label: 'Teams a changé', sentence: 'Teams a changé.', remedy: '' } as unknown as TeamsLink;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'Terminal Teams';
    component.projectId = 'wtt1';
    component.hostName = 'CAGIP';
    component.hostId = 'h1';
  });

  function view(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('.terminal-view') as HTMLElement;
  }

  function loadEverything(link: TeamsLink = linked): void {
    component.teamsTerminal = true;
    component.teamsOptionInactive = true;
    component.teamsLink = link;
    component.live = true;
    component.localFolder = '/home/paul/dev';
    component.messages = thread;
    component.streaming = streaming;
    component.elapsedLabel = '00:41';
    fixture.detectChanges();
  }

  // ------------------------------------------------------------------ la surface

  it('le terminal Teams porte la surface « Prune » : fond #231A36, barre #1B1429', () => {
    loadEverything();

    expect(getComputedStyle(view()).backgroundColor).toBe(TEAMS_BG);
    const bar = view().querySelector('.terminal-bar') as HTMLElement;
    expect(getComputedStyle(bar).backgroundColor).toBe(TEAMS_BAR);
  });

  it('la carte de réunion est une carte de la surface : #2E2345, filet #43335F', () => {
    loadEverything();

    const article = view().querySelector('.teams-card') as HTMLElement;
    expect(getComputedStyle(article).backgroundColor).toBe(TEAMS_CARD);
    expect(getComputedStyle(article).borderTopColor).toBe(TEAMS_RULE);
  });

  it('AUCUN AUTRE TERMINAL NE CHANGE : un terminal de projet garde `--cg-primary`', () => {
    component.messages = thread;
    fixture.detectChanges();

    expect(getComputedStyle(view()).backgroundColor).toBe(PRIMARY);
    const bar = view().querySelector('.terminal-bar') as HTMLElement;
    expect(getComputedStyle(bar).backgroundColor).toBe('rgba(0, 0, 0, 0)');
  });

  it('les deux se distinguent au premier regard : les fonds ne se confondent pas', () => {
    const ratio = contrast(parse(TEAMS_BG), parse(PRIMARY));
    const [r1, g1, b1] = parse(TEAMS_BG);
    const [r2, g2, b2] = parse(PRIMARY);
    // Pas une question de luminance (deux terminaux sombres) : de TEINTE. Bleu dominant d'un côté,
    // rouge et bleu mêlés de l'autre — la distance entre les deux couleurs se voit.
    expect(Math.hypot(r1 - r2, g1 - g2, b1 - b2)).toBeGreaterThan(40);
    expect(ratio).toBeGreaterThan(1);
  });

  // ------------------------------------------------------------------ l'AA, automatisé

  it('LES JETONS DE TEXTE tiennent l\'AA sur le fond, la barre et la carte', () => {
    const grounds = ['--cg-terminal-teams-bg', '--cg-terminal-teams-bar', '--cg-terminal-teams-card'];
    const inks = [
      '--cg-terminal-teams-text',
      '--cg-terminal-teams-title',
      '--cg-terminal-teams-muted',
      '--cg-terminal-teams-error',
      // Les encres de la charte qui restent posées sur la surface Teams.
      '--cg-accent',
      '--cg-accent-2',
      '--cg-orange-2',
      '--cg-success',
    ];
    for (const ground of grounds) {
      for (const ink of inks) {
        expect(contrast(token(ink), token(ground)))
          .withContext(`${ink} sur ${ground}`)
          .toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('CHAQUE TEXTE ET CHAQUE BADGE posé sur la surface Teams tient l\'AA (balayage du DOM)', () => {
    loadEverything();

    const result = sweep(view());

    // Le balayage a réellement vu le terminal chargé — sinon il passerait sur un écran vide.
    expect(result.texts).toBeGreaterThan(40);
    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
    expect(result.pastilles).withContext(JSON.stringify(result.pastilles, null, 2)).toEqual([]);
  });

  it('la liaison « Teams a changé » (§14, ambre de §12) garde sa pastille, détachée de la barre', () => {
    loadEverything(changed);

    const result = sweep(view());

    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
    expect(result.pastilles).withContext(JSON.stringify(result.pastilles, null, 2)).toEqual([]);
  });

  it('la demande d\'autorisation et la tuile en lecture seule tiennent l\'AA elles aussi', () => {
    component.teamsTerminal = true;
    component.messages = thread;
    component.pendingConfirmation = {
      toolUseId: 'tu', tool: 'bash', detail: 'rm -rf build', source: 'LOCAL_MACHINE',
      answering: false, denying: true, reason: '', deadline: null, timeoutMs: null,
    };
    fixture.detectChanges();
    const open = sweep(view());
    expect(open.failures).withContext(JSON.stringify(open.failures, null, 2)).toEqual([]);

    component.readOnly = true;
    fixture.detectChanges();
    expect(getComputedStyle(view()).backgroundColor).withContext('tuile Teams').toBe(TEAMS_BG);
    const tile = sweep(view());
    expect(tile.failures).withContext(JSON.stringify(tile.failures, null, 2)).toEqual([]);
  });

  // ------------------------------------------------------------------ la barre

  it('la barre dit « Conversations Teams » à côté du client — l\'adresse reste celle du terminal', () => {
    loadEverything();

    const trail = Array.from(view().querySelectorAll<HTMLAnchorElement>('.forge-crumb'));
    const last = trail[trail.length - 1];
    expect(last.textContent?.trim()).toBe(TEAMS_TERMINAL_BAR_LABEL);
    expect(last.getAttribute('href')).toBe('/atelier/wtt1');
    expect(view().querySelector('app-teams-link-badge')).not.toBeNull();
  });

  it('un terminal de projet garde son nom dans la barre', () => {
    component.projectName = 'mon-projet';
    fixture.detectChanges();

    const trail = Array.from(view().querySelectorAll<HTMLAnchorElement>('.forge-crumb'));
    expect(trail[trail.length - 1].textContent?.trim()).toBe('mon-projet');
  });
});
