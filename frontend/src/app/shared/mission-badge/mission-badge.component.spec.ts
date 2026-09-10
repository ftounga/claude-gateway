import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MissionBadgeComponent } from './mission-badge.component';

/**
 * La pastille d'état de mission (F-60 / SF-60-02).
 *
 * <p>Ce qui se vérifie ici est la contrainte non négociable du cadrage : <b>la couleur ne porte
 * jamais seule l'information</b>. Le libellé est écrit dans tous les cas, et aucune entrée du
 * composant ne permet de l'omettre.</p>
 */
describe('MissionBadgeComponent', () => {
  let fixture: ComponentFixture<MissionBadgeComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MissionBadgeComponent] });
  });

  function render(status: string | null | undefined): HTMLElement {
    fixture = TestBed.createComponent(MissionBadgeComponent);
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('écrit le libellé de chacun des trois états', () => {
    expect(render('ACTIVE').textContent).toContain('En cours');
    expect(render('PENDING').textContent).toContain('En attente');
    expect(render('CLOSED').textContent).toContain('Clôturé');
  });

  it('écrit « En cours » quand la gateway ne dit rien', () => {
    // Un backend antérieur n'envoie pas le champ : la pastille ne devient ni vide, ni « inconnu ».
    expect(render(null).textContent).toContain('En cours');
    expect(render(undefined).textContent).toContain('En cours');
  });

  it('pose la classe de statut de la charte, sans aucune couleur en ligne', () => {
    const host = render('PENDING');
    const badge = host.querySelector<HTMLElement>('.badge');

    expect(badge).not.toBeNull();
    expect(badge!.classList).toContain('badge');
    expect(badge!.classList).toContain('badge--warning');
    // Rien n'est posé en ligne : la couleur ne peut donc pas venir de la palette d'identité (§9).
    expect(badge!.getAttribute('style')).toBeNull();
  });

  it('change de classe avec l’état, et de rien d’autre', () => {
    const host = render('ACTIVE');
    expect(host.querySelector('.badge')!.classList).toContain('badge--success');

    fixture.componentRef.setInput('status', 'CLOSED');
    fixture.detectChanges();
    expect(host.querySelector('.badge')!.classList).toContain('badge--neutral');
    expect(host.querySelector('.badge')!.classList).not.toContain('badge--success');
  });

  it("garde l'icône décorative : c'est le texte qui informe", () => {
    const icon = render('CLOSED').querySelector('mat-icon');

    expect(icon).not.toBeNull();
    expect(icon!.getAttribute('aria-hidden')).toBe('true');
  });

  it("n'offre aucun moyen de n'afficher que la couleur", () => {
    // La contrainte tient parce que le composant n'a qu'une entrée : l'état. Il n'existe ni
    // « showLabel », ni « compact », ni « dotOnly » — donc aucun écran ne peut retirer le texte.
    const inputs = Object.keys(new MissionBadgeComponent() as object);
    expect(inputs).not.toContain('showLabel');
    expect(render('CLOSED').textContent!.trim().length).toBeGreaterThan(0);
  });
});
