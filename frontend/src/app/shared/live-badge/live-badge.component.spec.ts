import { ComponentFixture, TestBed } from '@angular/core/testing';

import { LiveBadgeComponent } from './live-badge.component';

/**
 * La pastille de vie d'un terminal (F-70 / SF-70-02).
 *
 * <p>Deux garanties, et elles sont toutes les deux des <b>interdits</b> : le libellé est
 * <b>toujours</b> écrit — la pastille ne porte jamais seule l'information —, et le composant
 * n'emprunte <b>aucun</b> des trois registres de couleur qui cohabitent déjà (statut §5, identité
 * du poste §9, état de mission §10). Un quatrième les rendrait tous illisibles.</p>
 */
describe('LiveBadgeComponent', () => {
  let fixture: ComponentFixture<LiveBadgeComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [LiveBadgeComponent] }).compileComponents();
    fixture = TestBed.createComponent(LiveBadgeComponent);
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent?.trim() ?? '';
  }

  it('écrit « connecté » dans la barre du terminal', () => {
    fixture.componentRef.setInput('scope', 'terminal');
    fixture.detectChanges();
    expect(text()).toBe('connecté');
  });

  it('lève l’ambiguïté sur la carte du poste, où « connecté » désigne déjà le runner', () => {
    fixture.componentRef.setInput('scope', 'host');
    fixture.detectChanges();
    expect(text()).toBe('Terminal connecté');
  });

  it('accorde son libellé au pluriel', () => {
    fixture.componentRef.setInput('scope', 'host');
    fixture.componentRef.setInput('count', 2);
    fixture.detectChanges();
    expect(text()).toBe('2 terminaux connectés');
  });

  it('affiche toujours une pastille ET un mot — jamais la pastille seule', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.live-badge__dot')).not.toBeNull();
    const label = host.querySelector('.live-badge__label');
    expect(label?.textContent?.trim().length).toBeGreaterThan(0);
  });

  it("n'emprunte aucun des trois registres de couleur existants", () => {
    fixture.componentRef.setInput('scope', 'host');
    fixture.detectChanges();
    const markup = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(markup).not.toContain('badge--success');
    expect(markup).not.toContain('badge--warning');
    expect(markup).not.toContain('badge--neutral');
    // Aucune couleur posée en ligne : ni ton d'identité de poste, ni couleur de statut.
    expect(markup).not.toMatch(/style="[^"]*(color|background)/);
  });

  it('ramène un compte absurde à un terminal plutôt que d’écrire « 0 terminaux »', () => {
    fixture.componentRef.setInput('scope', 'host');
    fixture.componentRef.setInput('count', 0);
    fixture.detectChanges();
    expect(text()).toBe('Terminal connecté');
  });

  it('dit ce que ça engage : un tour consommé en parallèle', () => {
    fixture.detectChanges();
    const badge = (fixture.nativeElement as HTMLElement).querySelector('.live-badge');
    expect(badge?.getAttribute('title')).toContain('en parallèle');
  });
});
