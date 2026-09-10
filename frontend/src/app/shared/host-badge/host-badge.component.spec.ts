import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HostBadgeComponent } from './host-badge.component';
import { hostIdentity } from '../host-identity';

/**
 * La pastille d'identité d'un poste (F-49 / SF-49-03) : ce qu'elle montre, et surtout ce qu'elle
 * **écrit** — la couleur ne porte jamais seule l'information.
 */
describe('HostBadgeComponent', () => {
  let fixture: ComponentFixture<HostBadgeComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostBadgeComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostBadgeComponent);
  });

  function render(name: string | null, showName = true, size: 'sm' | 'md' | 'lg' = 'md'): HTMLElement {
    fixture.componentInstance.name = name;
    fixture.componentInstance.showName = showName;
    fixture.componentInstance.size = size;
    // `size` et `showName` sont des entrées simples : sur un composant OnPush, seules les entrées
    // signal marquent la vue à revoir. Ce marquage explicite permet de rejouer un rendu dans le
    // même test.
    fixture.changeDetectorRef.markForCheck();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('montre les initiales et ÉCRIT le nom du poste', () => {
    const el = render('Poste CAGIP');
    expect(el.querySelector('.host-badge__mark')?.textContent?.trim()).toBe('PC');
    expect(el.querySelector('.host-badge__name')?.textContent?.trim()).toBe('Poste CAGIP');
  });

  it('peint la pastille de la couleur dérivée du nom', () => {
    const el = render('Poste CAGIP');
    const mark = el.querySelector('.host-badge__mark') as HTMLElement;
    const expected = hostIdentity('Poste CAGIP').tone.solid.toLowerCase();
    // Le navigateur normalise en rgb() : on compare les composantes.
    const rgb = mark.style.background;
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(expected.slice(i, i + 2), 16));
    expect(rgb).toContain(`${r}, ${g}, ${b}`);
  });

  it('donne à deux postes de noms différents des couleurs différentes', () => {
    const first = (render('Poste bureau').querySelector('.host-badge__mark') as HTMLElement)
      .style.background;
    const second = (render('Poste maison').querySelector('.host-badge__mark') as HTMLElement)
      .style.background;
    expect(first).not.toBe(second);
  });

  it('nomme la pastille quand le nom n\'est pas écrit à côté', () => {
    const el = render('Poste CAGIP', false);
    const mark = el.querySelector('.host-badge__mark') as HTMLElement;
    expect(el.querySelector('.host-badge__name')).toBeNull();
    expect(mark.getAttribute('role')).toBe('img');
    expect(mark.getAttribute('aria-label')).toBe('Poste Poste CAGIP');
  });

  it('masque la pastille aux lecteurs d\'écran quand le nom est déjà écrit', () => {
    const mark = render('Poste CAGIP').querySelector('.host-badge__mark') as HTMLElement;
    expect(mark.getAttribute('aria-hidden')).toBe('true');
    expect(mark.getAttribute('role')).toBeNull();
  });

  it('ne rend rien du tout sans nom de poste', () => {
    expect(render(null).querySelector('.host-badge')).toBeNull();
    expect(render('   ').querySelector('.host-badge')).toBeNull();
  });

  it('porte la classe de la grande taille', () => {
    expect(render('Poste CAGIP', true, 'lg').querySelector('.host-badge--lg')).not.toBeNull();
  });

  it('porte la classe de la petite taille', () => {
    expect(render('Poste CAGIP', true, 'sm').querySelector('.host-badge--sm')).not.toBeNull();
  });
});
