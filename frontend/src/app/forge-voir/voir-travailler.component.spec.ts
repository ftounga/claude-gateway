import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { MosaiqueComponent } from '../mosaique/mosaique.component';
import { SupervisionComponent } from '../supervision/supervision.component';
import { FORGE_DENSITY_STORAGE_KEY } from './forge-density';
import { VoirTravaillerComponent } from './voir-travailler.component';

/** Remplaçants : on vérifie ici la PORTE, pas ce que chaque densité fait (leurs suites le vérifient). */
@Component({ selector: 'app-supervision', template: '<p class="stub-apercus">aperçus</p>' })
class SupervisionStubComponent {}

@Component({ selector: 'app-mosaique', template: '<p class="stub-flux">flux</p>' })
class MosaiqueStubComponent {}

describe('VoirTravaillerComponent', () => {
  let fixture: ComponentFixture<VoirTravaillerComponent>;
  let query$: BehaviorSubject<ParamMap>;

  function build(densite: string | null): HTMLElement {
    query$ = new BehaviorSubject<ParamMap>(convertToParamMap(densite ? { densite } : {}));
    TestBed.configureTestingModule({
      imports: [VoirTravaillerComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: query$, snapshot: {} } },
      ],
    });
    TestBed.overrideComponent(VoirTravaillerComponent, {
      remove: { imports: [SupervisionComponent, MosaiqueComponent] },
      add: { imports: [SupervisionStubComponent, MosaiqueStubComponent] },
    });
    fixture = TestBed.createComponent(VoirTravaillerComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => localStorage.removeItem(FORGE_DENSITY_STORAGE_KEY));

  it('ouvre les aperçus par défaut, sans rien de retenu', () => {
    const root = build(null);

    expect(root.querySelector('.stub-apercus')).not.toBeNull();
    expect(root.querySelector('.stub-flux')).toBeNull();
    expect(root.querySelector('[data-density="apercus"]')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('?densite=flux ouvre la mosaïque, et retient ce choix', () => {
    const root = build('flux');

    expect(root.querySelector('.stub-flux')).not.toBeNull();
    expect(localStorage.getItem(FORGE_DENSITY_STORAGE_KEY)).toBe('flux');
  });

  it('sans paramètre, retrouve le choix retenu', () => {
    localStorage.setItem(FORGE_DENSITY_STORAGE_KEY, 'flux');

    const root = build(null);

    expect(root.querySelector('.stub-flux')).not.toBeNull();
  });

  it('le sélecteur retient le choix et remplace l’entrée d’historique', () => {
    const root = build('apercus');
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    (root.querySelector('[data-density="flux"]') as HTMLButtonElement).click();

    expect(localStorage.getItem(FORGE_DENSITY_STORAGE_KEY)).toBe('flux');
    expect(navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
      queryParams: { densite: 'flux' }, replaceUrl: true,
    }));

    query$.next(convertToParamMap({ densite: 'flux' }));
    fixture.detectChanges();
    expect(root.querySelector('.stub-flux')).not.toBeNull();
  });

  it('ramène à la Forge, et se nomme « Voir travailler »', () => {
    const root = build(null);

    expect(root.querySelector('.voir__back')?.getAttribute('href')).toBe('/forge');
    expect(root.querySelector('h1')?.textContent?.trim()).toBe('Voir travailler');
  });
});
