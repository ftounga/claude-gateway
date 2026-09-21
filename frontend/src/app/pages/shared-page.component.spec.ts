import { Title } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { SharedPageComponent } from './shared-page.component';

/** Une page partagée, ouverte sans compte (F-109 / SF-109-05). */
describe('SharedPageComponent', () => {
  function render(token: string): HTMLElement {
    TestBed.configureTestingModule({
      imports: [SharedPageComponent],
      providers: [{ provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ token })) } }],
    });
    const fixture = TestBed.createComponent(SharedPageComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it("montre la page dans son bac à sable, sur la route publique de la gateway", () => {
    const token = 'Ab_-'.repeat(10) + 'xyz';
    const root = render(token);

    expect(root.textContent).toContain('Page partagée');
    const frame = root.querySelector('iframe')!;
    expect(frame.getAttribute('src')).toBe(`/api/p/${token}/`);
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-popups');
  });

  it("un jeton qui n'a pas la forme d'un lien n'est jamais posé dans l'iframe", () => {
    for (const token of ['t1.abc.def', '../me', 'court']) {
      TestBed.resetTestingModule();
      const root = render(token);
      expect(root.querySelector('iframe')).toBeNull();
      expect(root.textContent).toContain("n'est pas ou plus valide");
    }
  });

  it("ne montre ni logo ni titre d'outil : c'est un CLIENT qui ouvre cette page", () => {
    // F-110 / SF-110-06. Le produit porte déjà la règle « rien de ce qui sort ne doit suggérer
    // quel outil l'a produit » ; elle valait pour les commits, pas pour ce que le client voit.
    const dom = render('Ab_-'.repeat(10) + 'xyz');

    expect(dom.querySelector('img')).toBeNull();
    expect(dom.textContent ?? '').not.toContain('Claude');
    // Et le titre de l'onglet, la trace la plus facile à oublier et l'une des plus visibles.
    expect(TestBed.inject(Title).getTitle()).toBe('Page partagée');
  });
});
