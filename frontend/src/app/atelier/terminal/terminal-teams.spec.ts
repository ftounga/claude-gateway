import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierExecStreamingItem } from '../atelier.types';
import { AtelierTeamsCard } from '../../core/models/atelier.models';

/**
 * **Le compte rendu dans le fil** (F-89 / SF-89-03).
 *
 * <p>Ce que ces tests tiennent, dans l'ordre d'importance : <b>un terminal de projet reste textuel
 * pour toujours</b> ; <b>chaque ligne porte sa source</b> ; <b>ce qui est incertain se lit comme
 * incertain, par la typographie et jamais par un pictogramme ni par un chiffre</b> ; et <b>ce qui
 * n'a pas pu être lu se voit sans un geste</b>.</p>
 */
describe('AtelierTerminalComponent — le compte rendu Teams (F-89 / SF-89-03)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  const card: AtelierTeamsCard = {
    kind: 'MEETING_CARD',
    title: 'Comité de migration',
    subtitle: '12 septembre, 45 min',
    window: 'du 5 au 12 septembre, 47 messages lus, 3 non reconnus',
    gaps: ['3 messages non reconnus le 9 septembre'],
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
        ],
      },
      {
        title: 'Décisions',
        lines: [
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
    moments: [],
  };

  const momentsCard: AtelierTeamsCard = {
    ...card,
    kind: 'MOMENTS',
    sections: [],
    moments: [
      {
        at: new Date(2026, 8, 12, 14, 32).toISOString(),
        quote: 'Le planning décale au T3',
        speaker: 'Claire',
        imageId: 'abc123',
        webUrl: 'https://teams.microsoft.com/l/t?at=1932',
      },
      {
        at: new Date(2026, 8, 12, 14, 48).toISOString(),
        quote: 'Rien à montrer, mais c\'est dit',
        speaker: '',
        imageId: '',
        webUrl: '',
      },
    ],
  };

  function turnWith(payload: AtelierTeamsCard): AtelierExecStreamingItem {
    return {
      status: 'running',
      tokens: null,
      text: '',
      plan: [],
      blocks: [
        {
          tool: 'teams_meeting_card',
          toolUseId: 'tu_1',
          threadId: null,
          output: '',
          hasOutput: false,
          error: false,
          expanded: false,
          card: payload,
        },
      ],
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'Terminal Teams';
    component.projectId = 'ws-1';
  });

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return host().textContent ?? '';
  }

  function render(payload: AtelierTeamsCard, teams = true): void {
    component.teamsTerminal = teams;
    component.streaming = turnWith(payload);
    fixture.detectChanges();
  }

  // ------------------------------------------------------------- la règle non négociable

  it('LE MÊME BLOC EST RENDU EN TEXTE SUR UN TERMINAL DE PROJET — jamais en carte', () => {
    render(card, false);

    expect(host().querySelector('.teams-card')).toBeNull();
    // Rendu en TEXTE, pas masqué : masquer ferait disparaître une information sans le dire.
    expect(host().querySelector('pre.terminal-output')).not.toBeNull();
    expect(text()).toContain('Fournir le schéma réseau avant vendredi');
    expect(text()).toContain('Fenêtre lue');
  });

  it('sur un terminal Teams, la carte est rendue', () => {
    render(card);

    expect(host().querySelector('.teams-card')).not.toBeNull();
    expect(text()).toContain('Comité de migration');
  });

  it('la peau ne s\'applique qu\'au terminal Teams', () => {
    render(card, false);
    expect(host().querySelector('.terminal-view--teams')).toBeNull();

    render(card, true);
    expect(host().querySelector('.terminal-view--teams')).not.toBeNull();
  });

  // ------------------------------------------------------------- la densité d'un compte rendu

  it('les sections viennent DANS L\'ORDRE REÇU : ce qu\'on attend du lecteur d\'abord', () => {
    render(card);

    const titles = Array.from(host().querySelectorAll('.teams-card__section-title'))
      .map((element) => element.textContent?.trim());

    expect(titles).toEqual(['Ce qu\'on attend de vous', 'Décisions']);
  });

  it('chaque ligne porte son auteur, son heure et son lien', () => {
    render(card);

    const first = host().querySelector('.teams-card__line');
    expect(first?.textContent).toContain('Paul');
    expect(first?.textContent).toContain('14:32');

    const link = first?.querySelector('a.teams-card__link') as HTMLAnchorElement | null;
    expect(link?.getAttribute('href')).toBe('https://teams.microsoft.com/l/message/m-1');
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  // ------------------------------------------------------------- F-91 : la mention

  it('LA MENTION D\'UN ENREGISTREMENT LOCAL SE LIT AU-DESSUS DU TITRE, jamais repliée', () => {
    const notice = 'Ce compte rendu provient d\'un ENREGISTREMENT LOCAL — les participants n\'en '
      + 'ont pas été avertis par Teams.';
    render({ ...card, recordingNotice: notice });

    const mention = host().querySelector('.teams-card__recording');
    expect(mention).not.toBeNull();
    expect(mention?.textContent).toContain('ENREGISTREMENT LOCAL');
    // AU-DESSUS du titre : c'est une information sur la NATURE de ce qu'on va lire.
    const article = host().querySelector('.teams-card');
    expect(article?.firstElementChild).toBe(mention as Element);
  });

  it('la mention se lit par la TYPOGRAPHIE : aucun pictogramme d\'alerte', () => {
    render({ ...card, recordingNotice: 'Enregistrement local.' });

    expect(host().querySelectorAll('.teams-card__recording mat-icon').length).toBe(0);
  });

  it('sans mention, aucun cadre vide : la plupart des blocs ne viennent pas d\'une capture', () => {
    render(card);

    expect(host().querySelector('.teams-card__recording')).toBeNull();
  });

  // ------------------------------------------------------------- l'incertitude

  it('une ligne « à confirmer » le dit EN TOUTES LETTRES et se lit en italique', () => {
    render(card);

    const uncertain = host().querySelector('.teams-card__line--uncertain');
    expect(uncertain).not.toBeNull();
    expect(uncertain?.textContent).toContain('à confirmer');
    // La ligne explicite, elle, ne porte pas la marque d'incertitude.
    expect(host().querySelectorAll('.teams-card__line--uncertain').length).toBe(1);
  });

  it('AUCUN PICTOGRAMME D\'AVERTISSEMENT dans une carte : l\'incertitude n\'est pas un danger', () => {
    render(card);

    const icons = Array.from(host().querySelectorAll('.teams-card mat-icon'))
      .map((icon) => icon.textContent?.trim());

    expect(icons).toEqual([]);
  });

  it('AUCUN CHIFFRE DE CERTITUDE : pas de score, pas de pourcentage', () => {
    render(card);

    const sources = Array.from(host().querySelectorAll('.teams-card__certainty'))
      .map((element) => element.textContent ?? '');

    expect(sources.length).toBeGreaterThan(0);
    sources.forEach((source) => {
      expect(source).not.toMatch(/\d/);
      expect(source).not.toContain('%');
    });
  });

  // ------------------------------------------------------------- ce qui n'a pas pu être lu

  it('la fenêtre lue et les manques sont visibles SANS UN GESTE', () => {
    render(card);

    const foot = host().querySelector('.teams-card__foot');
    expect(foot?.textContent).toContain('du 5 au 12 septembre, 47 messages lus, 3 non reconnus');
    expect(foot?.textContent).toContain('3 messages non reconnus le 9 septembre');
    // Aucun bouton de dépliage : un trou qu'il faut déplier est un trou qu'on ne voit pas.
    expect(foot?.querySelector('button')).toBeNull();
  });

  it('sans manque, le pied dit « aucun manque signalé » — jamais rien', () => {
    render({ ...card, gaps: [] });

    expect(host().querySelector('.teams-card__gaps')?.textContent)
      .toContain('Aucun manque signalé');
  });

  // ------------------------------------------------------------- les moments

  it('un moment pose l\'image À CÔTÉ de la phrase, et l\'heure ouvre la transcription', () => {
    render(momentsCard);

    const first = host().querySelector('.teams-moment');
    expect(first?.querySelector('img.teams-moment__image')).not.toBeNull();
    expect(first?.textContent).toContain('Le planning décale au T3');
    expect(first?.textContent).toContain('Claire');

    const time = first?.querySelector('a.teams-moment__time') as HTMLAnchorElement | null;
    expect(time?.getAttribute('href')).toBe('https://teams.microsoft.com/l/t?at=1932');
    expect(time?.textContent?.trim()).toBe('14:32');
  });

  it('UN MOMENT SANS IMAGE RESTE UN MOMENT : la phrase et l\'heure suffisent', () => {
    render(momentsCard);

    const moments = host().querySelectorAll('.teams-moment');
    expect(moments.length).toBe(2);
    expect(moments[1].querySelector('img')).toBeNull();
    expect(moments[1].textContent).toContain('Rien à montrer, mais c\'est dit');
    expect(moments[1].textContent).toContain('14:48');
  });

  it('une image qui ne charge pas le DIT, et son moment reste', () => {
    render(momentsCard);

    const image = host().querySelector('img.teams-moment__image') as HTMLImageElement;
    image.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(host().querySelector('.teams-moment__missing')?.textContent)
      .toContain('Image indisponible');
    expect(text()).toContain('Le planning décale au T3');
  });

  // ------------------------------------------------------------- agrandir, le geste de la mosaïque

  it('un clic agrandit, un second rend l\'image à sa place', () => {
    render(momentsCard);

    const button = host().querySelector('button.teams-moment__zoom') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toBe('Agrandir l\'image de 14:32');

    button.click();
    fixture.detectChanges();
    expect(host().querySelector('.teams-moment--zoomed')).not.toBeNull();
    expect(host().querySelector('button.teams-moment__zoom')?.getAttribute('aria-label'))
      .toBe('Réduire l\'image de 14:32');

    (host().querySelector('button.teams-moment__zoom') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host().querySelector('.teams-moment--zoomed')).toBeNull();
  });

  it('Échap rend l\'image à sa place', () => {
    render(momentsCard);
    (host().querySelector('button.teams-moment__zoom') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host().querySelector('.teams-moment--zoomed')).not.toBeNull();

    component.closeMomentZoom();
    fixture.detectChanges();

    expect(host().querySelector('.teams-moment--zoomed')).toBeNull();
  });

  it('l\'image est servie par la route du terminal, jamais par une adresse fabriquée ailleurs', () => {
    render(momentsCard);

    const image = host().querySelector('img.teams-moment__image') as HTMLImageElement;
    expect(image.getAttribute('src')).toBe('/api/workspaces/ws-1/teams/moments/abc123');
  });

  // ------------------------------------------------ F-89 / SF-89-04 : un terminal sans droit le dit

  it('SF-89-04 — terminal Teams sans droit : le bandeau le dit, avec ses deux gestes, et le fil reste', () => {
    component.teamsOptionInactive = true;
    render(card);

    const banner = host().querySelector('.terminal-teams-inactive') as HTMLElement;
    expect(banner).not.toBeNull();
    expect(banner.getAttribute('role')).toBe('status');
    expect(banner.textContent).toContain('Option Teams non active');
    expect(banner.textContent).toContain('Saisir un code d\'accès');
    expect(banner.textContent).toContain('Voir la facturation');
    // Rien n'est bloqué : le compte rendu déjà là reste lisible.
    expect(host().querySelector('.teams-card')).not.toBeNull();
  });

  it('SF-89-04 — les gestes du bandeau mènent au code d\'accès et à la facturation', () => {
    component.teamsOptionInactive = true;
    render(card);
    const code = spyOn(component.openAccessCode, 'emit');
    const billing = spyOn(component.openBilling, 'emit');

    const buttons = host().querySelectorAll('.terminal-teams-inactive button');
    (buttons[0] as HTMLButtonElement).click();
    (buttons[1] as HTMLButtonElement).click();

    expect(code).toHaveBeenCalled();
    expect(billing).toHaveBeenCalled();
  });

  it('SF-89-04 — pas de bandeau avec le droit, ni sur un terminal de projet', () => {
    render(card);
    expect(host().querySelector('.terminal-teams-inactive')).toBeNull();

    component.teamsOptionInactive = true;
    render(card, false);
    expect(host().querySelector('.terminal-teams-inactive')).toBeNull();
  });

  // ------------------------------------------------------------- un bloc vide

  it('un bloc sans ligne ni moment n\'est pas rendu : mieux vaut rien qu\'un cadre creux', () => {
    render({ ...card, sections: [], moments: [] });

    expect(host().querySelector('.teams-card')).toBeNull();
  });
});
