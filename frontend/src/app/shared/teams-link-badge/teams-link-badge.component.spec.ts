import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TeamsLinkBadgeComponent } from './teams-link-badge.component';
import { TeamsLink, TeamsLinkState } from '../../atelier/teams/teams-link.service';

/**
 * L'indicateur de liaison Teams (F-87 / SF-87-03).
 *
 * <p>Trois garanties, et ce sont trois <b>interdits</b> : le libellé est <b>toujours</b> écrit,
 * l'indicateur ne porte <b>aucune</b> action, et il n'ajoute <b>aucun</b> registre de couleur — les
 * deux états colorés empruntent les pastilles de statut existantes (§5), celui qui ne l'est pas
 * prend l'encre de la barre (§11).</p>
 */
describe('TeamsLinkBadgeComponent', () => {
  let fixture: ComponentFixture<TeamsLinkBadgeComponent>;

  function link(state: TeamsLinkState, overrides: Partial<TeamsLink> = {}): TeamsLink {
    return {
      state,
      label: '',
      sentence: '',
      remedy: '',
      browser: '',
      healthVerdict: '',
      recognizedFields: 0,
      expectedFields: 0,
      missingFields: [],
      observedApiVersions: [],
      conclusive: false,
      ...overrides,
    };
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return host().textContent?.trim() ?? '';
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TeamsLinkBadgeComponent] }).compileComponents();
    fixture = TestBed.createComponent(TeamsLinkBadgeComponent);
  });

  it('écrit « Teams relié » sans emprunter aucune couleur', () => {
    fixture.componentRef.setInput('link', link('LINKED', { label: 'Teams relié' }));
    fixture.detectChanges();

    expect(text()).toBe('Teams relié');
    const markup = host().innerHTML;
    expect(markup).not.toContain('badge--warning');
    expect(markup).not.toContain('badge--neutral');
    expect(markup).not.toContain('badge--success');
  });

  it('emprunte la pastille neutre existante quand le navigateur n’est pas détecté', () => {
    fixture.componentRef.setInput('link', link('BROWSER_NOT_DETECTED'));
    fixture.detectChanges();

    expect(text()).toBe('Teams : navigateur non détecté');
    expect(host().innerHTML).toContain('badge--neutral');
  });

  it('emprunte la pastille « En attente » — et aucune autre — quand Teams a changé', () => {
    fixture.componentRef.setInput('link', link('TEAMS_CHANGED'));
    fixture.detectChanges();

    expect(text()).toBe('Teams a changé');
    const markup = host().innerHTML;
    expect(markup).toContain('badge--warning');
    expect(markup).not.toContain('badge--success');
  });

  it('affiche toujours une pastille ET un mot — jamais la pastille seule', () => {
    fixture.componentRef.setInput('link', link('TEAMS_CHANGED'));
    fixture.detectChanges();

    expect(host().querySelector('.teams-link__dot')).not.toBeNull();
    expect(host().querySelector('.teams-link__label')?.textContent?.trim().length)
      .toBeGreaterThan(0);
  });

  it("ne porte AUCUNE action : ni bouton, ni lien", () => {
    fixture.componentRef.setInput('link', link('BROWSER_NOT_DETECTED'));
    fixture.detectChanges();

    expect(host().querySelector('button')).toBeNull();
    expect(host().querySelector('a')).toBeNull();
  });

  it("porte le remède dans l'infobulle — la commande à coller, pas seulement le conseil", () => {
    fixture.componentRef.setInput(
      'link',
      link('BROWSER_NOT_DETECTED', {
        sentence: "Le navigateur du poste n'est pas relié.",
        remedy: 'google-chrome --remote-debugging-port=9222 --user-data-dir="…"',
      }),
    );
    fixture.detectChanges();

    const title = host().querySelector('.teams-link')?.getAttribute('title') ?? '';
    expect(title).toContain("Le navigateur du poste n'est pas relié.");
    expect(title).toContain('--remote-debugging-port=9222');
  });

  it("n'est jamais muet, même sans relevé", () => {
    fixture.detectChanges();

    expect(text().length).toBeGreaterThan(0);
    expect(host().querySelector('.teams-link')?.getAttribute('title'))
      .toContain("pas encore été relevé");
  });

  it('ne pose aucune couleur en ligne', () => {
    fixture.componentRef.setInput('link', link('TEAMS_CHANGED'));
    fixture.detectChanges();

    expect(host().innerHTML).not.toMatch(/style="[^"]*(color|background)/);
  });
});
