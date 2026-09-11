import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ForgeBreadcrumbComponent } from './forge-breadcrumb.component';
import { hostInitials } from '../host-identity';

/**
 * Le fil d'Ariane de la Forge (F-68 / SF-68-01).
 *
 * <p>Ce qu'on vérifie ici, ce n'est pas un habillage : c'est le contrat que F-68 met à la place de
 * l'onglet supprimé. Il doit dire <b>où l'on est</b> — toujours à partir de la Forge — et
 * <b>chez qui</b>, en réemployant la pastille d'identité du poste plutôt qu'en inventant un
 * quatrième registre de couleur.</p>
 */
describe('ForgeBreadcrumbComponent', () => {
  let fixture: ComponentFixture<ForgeBreadcrumbComponent>;
  let component: ForgeBreadcrumbComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ForgeBreadcrumbComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(ForgeBreadcrumbComponent);
    component = fixture.componentInstance;
  });

  function crumbs(): HTMLAnchorElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('.forge-crumb'),
    );
  }

  it('commence toujours par « Forge », liée à l\'accueil de la Forge', () => {
    fixture.detectChanges();
    const trail = crumbs();

    expect(trail.length).toBe(1);
    expect(trail[0].textContent?.trim()).toBe('Forge');
    expect(trail[0].getAttribute('href')).toBe('/forge');
  });

  it('marque le dernier niveau comme page courante — et le laisse cliquable', () => {
    component.crumbs = [{ label: 'mon-projet', link: ['/atelier', 'w1'] }];
    fixture.detectChanges();
    const trail = crumbs();

    expect(trail.length).toBe(2);
    // Chaque niveau est un lien : décision explicite du PO.
    expect(trail[0].getAttribute('href')).toBe('/forge');
    expect(trail[1].getAttribute('href')).toBe('/atelier/w1');
    // Seul le dernier dit « vous êtes ici ».
    expect(trail[0].getAttribute('aria-current')).toBeNull();
    expect(trail[1].getAttribute('aria-current')).toBe('page');
  });

  it('rend les niveaux dans l\'ordre, séparés, sous un repère de navigation nommé', () => {
    component.crumbs = [
      { label: 'Poste CAGIP', link: ['/forge'], hostName: 'Poste CAGIP' },
      { label: 'mon-projet', link: ['/atelier', 'w1'] },
    ];
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('nav')?.getAttribute('aria-label')).toBe("Fil d'Ariane");
    expect(root.querySelectorAll('.forge-crumbs__sep').length).toBe(2);
    expect(crumbs().length).toBe(3);
    expect(crumbs()[2].textContent?.trim()).toBe('mon-projet');
  });

  // ------------------------------------------- chez qui (F-49 / SF-49-03), réemployé tel quel

  it('dit CHEZ QUI avec la pastille d\'identité du poste — initiales, nom écrit, couleur du nom', () => {
    component.crumbs = [
      { label: 'Poste CAGIP', link: ['/forge'], fragment: 'poste-h1', hostName: 'Poste CAGIP' },
    ];
    fixture.detectChanges();
    const host = (fixture.nativeElement as HTMLElement).querySelector('.forge-crumb--host');

    expect(host).not.toBeNull();
    expect(host?.querySelector('.host-badge__mark')?.textContent?.trim())
      .toBe(hostInitials('Poste CAGIP'));
    // La couleur ne porte jamais seule l'information : le nom reste écrit.
    expect(host?.querySelector('.host-badge__name')?.textContent?.trim()).toBe('Poste CAGIP');
    // « Chez qui » ramène à la carte de ce client sur l'accueil de la Forge.
    expect(host?.getAttribute('href')).toBe('/forge#poste-h1');
  });

  it('reste cliquable sans ancrage quand l\'identifiant du poste est inconnu', () => {
    component.crumbs = [{ label: 'Poste CAGIP', link: ['/forge'], hostName: 'Poste CAGIP' }];
    fixture.detectChanges();

    expect(crumbs()[1].getAttribute('href')).toBe('/forge');
  });

  // ------------------------------------------- où en est-on (F-60 / SF-60-02), toujours écrit

  it('montre l\'état de mission à côté du poste, avec son libellé', () => {
    component.crumbs = [
      { label: 'Poste CAGIP', link: ['/forge'], hostName: 'Poste CAGIP', missionStatus: 'CLOSED' },
    ];
    fixture.detectChanges();
    const mission = (fixture.nativeElement as HTMLElement).querySelector('.forge-crumb__mission');

    expect(mission).not.toBeNull();
    expect(mission?.textContent).toContain('Clôturé');
    // Deux registres côte à côte, aucun ne mange l'autre.
    expect((fixture.nativeElement as HTMLElement)
      .querySelector('.host-badge__name')?.textContent?.trim()).toBe('Poste CAGIP');
  });

  it('n\'affiche aucun état de mission quand il n\'y a rien à dire', () => {
    component.crumbs = [{ label: 'Poste CAGIP', link: ['/forge'], hostName: 'Poste CAGIP' }];
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.forge-crumb__mission')).toBeNull();
  });

  // ------------------------------------------- garde-fous

  it('saute un niveau sans libellé plutôt que de rendre un chaînon creux', () => {
    component.crumbs = [{ label: '  ', link: ['/atelier'] }];
    fixture.detectChanges();

    expect(crumbs().length).toBe(1);
    expect(crumbs()[0].getAttribute('aria-current')).toBe('page');
  });

  it('se réduit à « Forge » quand on ne lui donne rien', () => {
    component.crumbs = null;
    fixture.detectChanges();

    expect(crumbs().length).toBe(1);
    expect(crumbs()[0].textContent?.trim()).toBe('Forge');
  });
});
