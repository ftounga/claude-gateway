import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent, TEAMS_TERMINAL_BAR_LABEL } from './atelier-terminal.component';
import { AtelierExecStreamingItem, AtelierThreadItem } from '../atelier.types';
import { AtelierTeamsCard } from '../../core/models/atelier.models';
import { TeamsLink } from '../teams/teams-link.service';

/**
 * **Le terminal Teams, opposé à celui de la Forge — couleurs seulement** (F-89 / SF-89-09).
 *
 * <p>Le PO avait demandé deux terminaux « vraiment opposés » ; « Prune » (SF-89-07), un violet
 * sombre, restait un terminal sombre confondu de loin avec la Forge. Ces tests tiennent la
 * réparation : la surface CLAIRE « Papier » (jetons `--cg-terminal-teams-*`, remplacés), <b>l'AA de
 * tout ce qui s'y lit</b> — vérifié par les couleurs CALCULÉES, styles globaux chargés, sur un
 * terminal Teams rendu avec tout ce qu'il sait afficher — et <b>l'identité de la POLICE, de la
 * TAILLE et de l'INTERLIGNE</b> avec la Forge (correction du PO : seule la couleur change). Une
 * liste de sélecteurs vieillirait au premier élément ajouté ; un balayage du DOM, non.</p>
 */
describe('AtelierTerminalComponent — la peau du terminal Teams (F-89 / SF-89-09)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  // La surface « Papier », par ses valeurs calculées.
  const TEAMS_BG = 'rgb(250, 248, 243)'; // --cg-terminal-teams-bg #FAF8F3
  const TEAMS_BAR = 'rgb(239, 234, 249)'; // --cg-terminal-teams-bar #EFEAF9
  const TEAMS_CARD = 'rgb(255, 255, 255)'; // --cg-terminal-teams-card #FFFFFF
  const TEAMS_RULE = 'rgb(230, 224, 210)'; // --cg-terminal-teams-rule #E6E0D2
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
   * icône ou un grand texte). C'est la garantie de la peau : tout ce que le terminal écrit LUI-MÊME
   * est lisible sur le fond, le bandeau et la carte « Papier ».
   *
   * Ce qui a sa propre pastille (§5, §9, §12 — badge de statut, badge d'hôte, puce de moteur) GARDE
   * sa palette de charte (règle §15 : les registres ne changent pas, seule la surface change). Ces
   * pastilles pâles ne se détachent PAS d'un fond clair — pas plus ici que sur n'importe quel écran
   * blanc du produit ; leur lisibilité interne est une affaire de charte, à l'échelle du produit,
   * hors du périmètre de SF-89-09 (l'écart §5 « En attente » est signalé depuis SF-89-07). Le
   * balayage les COLLECTE (`pastilles`) à titre d'information, mais n'en fait pas un échec : la règle
   * du fond clair n'est pas celle du fond sombre « Prune » où un badge pâle ressortait tout seul.
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

  it('le terminal Teams porte la surface « Papier » : fond #FAF8F3, barre #EFEAF9', () => {
    loadEverything();

    expect(getComputedStyle(view()).backgroundColor).toBe(TEAMS_BG);
    const bar = view().querySelector('.terminal-bar') as HTMLElement;
    expect(getComputedStyle(bar).backgroundColor).toBe(TEAMS_BAR);
  });

  it('la carte de réunion est une carte de la surface : #FFFFFF, filet #E6E0D2', () => {
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

  it('les deux se distinguent au premier regard : clair contre sombre, sans lire un libellé', () => {
    const ratio = contrast(parse(TEAMS_BG), parse(PRIMARY));
    const [r1, g1, b1] = parse(TEAMS_BG);
    const [r2, g2, b2] = parse(PRIMARY);
    // « Papier » est CLAIR, la Forge SOMBRE : la distance de couleur est franche et, cette fois,
    // c'est surtout une question de LUMINANCE — les deux silhouettes ne se confondent plus.
    expect(Math.hypot(r1 - r2, g1 - g2, b1 - b2)).toBeGreaterThan(200);
    expect(ratio).toBeGreaterThan(4.5);
  });

  it('POLICE, TAILLE, INTERLIGNE identiques à la Forge : seule la couleur change', () => {
    // LE MÊME composant, LE MÊME élément, LE MÊME contenu — d'abord en terminal Teams, puis en
    // terminal de projet (la Forge) : seul `teamsTerminal` bascule. On mesure les polices calculées
    // sur le flux et la barre dans les deux états, et on compare. (Un seul fixture : deux fixtures
    // simultanés ne sont pas fiablement connectés au document sous Karma.)
    loadEverything();
    const metricsOf = () => {
      const sb = view().querySelector('.terminal-scrollback') as HTMLElement;
      const bar = view().querySelector('.terminal-bar') as HTMLElement;
      const sbStyle = getComputedStyle(sb);
      const barStyle = getComputedStyle(bar);
      return {
        bg: getComputedStyle(view()).backgroundColor,
        fontFamily: sbStyle.fontFamily,
        fontSize: sbStyle.fontSize,
        lineHeight: sbStyle.lineHeight,
        barFontFamily: barStyle.fontFamily,
        barFontSize: barStyle.fontSize,
      };
    };

    const teams = metricsOf();

    // Bascule vers la Forge (terminal de projet), même contenu, même élément.
    component.teamsTerminal = false;
    component.readOnly = false;
    fixture.detectChanges();
    const forge = metricsOf();

    // La surface est vraiment celle de la Forge, pas de la peau Teams.
    expect(teams.bg).toBe(TEAMS_BG);
    expect(forge.bg).toBe(PRIMARY);

    // Le flux : même police, même taille, même interligne.
    expect(teams.fontFamily).toBe(forge.fontFamily);
    expect(teams.fontSize).toBe(forge.fontSize);
    expect(teams.lineHeight).toBe(forge.lineHeight);
    // La barre aussi.
    expect(teams.barFontFamily).toBe(forge.barFontFamily);
    expect(teams.barFontSize).toBe(forge.barFontSize);

    // ...mais la couleur de fond, elle, diffère : c'est là toute la subfeature.
    expect(teams.bg).not.toBe(forge.bg);
  });

  // ------------------------------------------------------------------ l'AA, automatisé

  it('LES JETONS DE TEXTE, D\'ACCENT ET D\'ÉTAT « PAPIER » tiennent l\'AA sur le fond, la barre et la carte', () => {
    const grounds = ['--cg-terminal-teams-bg', '--cg-terminal-teams-bar', '--cg-terminal-teams-card'];
    const inks = [
      '--cg-terminal-teams-text',
      '--cg-terminal-teams-title',
      '--cg-terminal-teams-muted',
      '--cg-terminal-teams-bar-ink',
      // Les deux accents « Papier » qui remplacent l'or et l'orange de la Forge, illisibles sur clair.
      '--cg-terminal-teams-message',
      '--cg-terminal-teams-step',
      // Les couleurs d'état de la charte APPROFONDIES pour le fond clair.
      '--cg-terminal-teams-error',
      '--cg-terminal-teams-add',
      // F-89 / SF-89-11 : l'ambre de l'attention (§12), approfondi pour l'AA sur le papier.
      '--cg-terminal-teams-warn',
    ];
    for (const ground of grounds) {
      for (const ink of inks) {
        expect(contrast(token(ink), token(ground)))
          .withContext(`${ink} sur ${ground}`)
          .toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('L\'OR ET L\'ORANGE DE LA CHARTE seraient illisibles sur le fond clair — d\'où les accents « Papier »', () => {
    // La preuve, par les couleurs calculées, de pourquoi la Forge et le terminal Teams ne peuvent
    // pas partager leurs accents : sur le papier crème, l'or et l'orange de charte tombent sous l'AA.
    const bg = token('--cg-terminal-teams-bg');
    expect(contrast(token('--cg-accent'), bg)).toBeLessThan(3);
    expect(contrast(token('--cg-orange-2'), bg)).toBeLessThan(3);
  });

  it('CHAQUE TEXTE que le terminal écrit sur la surface « Papier » tient l\'AA (balayage du DOM)', () => {
    loadEverything();

    const result = sweep(view());

    // Le balayage a réellement vu le terminal chargé — sinon il passerait sur un écran vide.
    expect(result.texts).toBeGreaterThan(40);
    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
  });

  it('F-89 / SF-89-11 — le bloc d\'échec de lecture et le bandeau de repli tiennent l\'AA (balayage)', () => {
    component.teamsTerminal = true;
    component.streaming = {
      status: 'running',
      tokens: null,
      text: '',
      plan: [],
      blocks: [
        {
          tool: 'teams_project_fallback', toolUseId: 'b0', threadId: null, output: '',
          hasOutput: false, error: false, expanded: false,
          card: {
            kind: 'PROJECT_FALLBACK', title: 'Réponse basée sur le projet, pas sur Teams',
            subtitle: 'Vous avez autorisé le repli : cette réponse vient des fichiers du poste.',
            window: '', sections: [], moments: [], gaps: [],
          },
        },
        {
          tool: 'teams_find_conversations', toolUseId: 'b1', threadId: null, output: '',
          hasOutput: false, error: false, expanded: false,
          card: {
            kind: 'READ_FAILED', title: 'Teams n\'a pas pu être lu',
            subtitle: 'Teams n\'a rien servi : ouvrez l\'écran voulu dans Teams, puis réessayez.',
            window: '', sections: [], moments: [], gaps: [], reason: 'NOTHING_SERVED',
          },
        },
        {
          tool: 'teams_meeting_transcript', toolUseId: 'b2', threadId: null, output: '',
          hasOutput: false, error: false, expanded: false,
          card: {
            kind: 'READ_FAILED', title: 'Teams n\'a pas pu être lu',
            subtitle: 'La session Microsoft a expiré : reconnectez-vous à Teams.',
            window: '', sections: [], moments: [], gaps: [], reason: 'SESSION_EXPIRED',
          },
        },
      ],
    } as AtelierExecStreamingItem;
    fixture.detectChanges();

    // Les deux blocs et le bandeau sont bien rendus (ambre ET rouge).
    expect(view().querySelectorAll('.teams-read-failed').length).toBe(2);
    expect(view().querySelector('.teams-read-failed--broken')).not.toBeNull();
    expect(view().querySelector('.teams-fallback-banner')).not.toBeNull();
    // Et tout ce qu'ils écrivent tient l'AA sur la surface « Papier ».
    const result = sweep(view());
    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
  });

  it('la liaison « Teams a changé » (§14) GARDE sa palette de charte sur la surface « Papier »', () => {
    loadEverything(changed);

    const result = sweep(view());
    // Le terminal reste AA partout où il écrit lui-même.
    expect(result.failures).withContext(JSON.stringify(result.failures, null, 2)).toEqual([]);
    // Et le registre de liaison n'a pas été repeint aux couleurs « Papier » : il porte la pastille
    // de charte (§12/§5 « en attente »), preuve que seule la SURFACE change (§15).
    expect(view().querySelector('app-teams-link-badge .badge--warning')).not.toBeNull();
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
