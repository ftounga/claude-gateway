import { HttpErrorResponse } from '@angular/common/http';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { MapGraph } from '../../core/models/governance.models';
import { GovernanceService } from '../../core/services/governance.service';
import { CYTOSCAPE_LOADER, elementsOf, nodeCaption } from './forge-map-canvas.component';
import { ForgeMapComponent, mapViewFrom } from './forge-map.component';
import { levelFor } from './forge-map-levels';
import { mapGraph, mapNode } from './forge-map.testing';

@Component({
  standalone: true,
  imports: [ForgeMapComponent],
  template: `<app-forge-map [hostRef]="ref()" hostName="CAGIP"><p forgeMapFiles class="files">les fichiers</p></app-forge-map>`,
})
class HostComponent {
  readonly ref = signal('h1');
}

describe('ForgeMapComponent (F-173 / SF-173-02)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let governance: jasmine.SpyObj<GovernanceService>;
  let router: jasmine.SpyObj<Router>;
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let cyCalls: unknown[];
  let loaderFails: boolean;
  let mobile: boolean;

  const graph: MapGraph = mapGraph([
    mapNode('compte', { kind: 'compte_aws', label: 'compte prod', children: 1 }),
    mapNode('lzi', { parentId: 'compte', depth: 1, label: 'lzi-prod', traps: 2 }),
    mapNode('proxy', { kind: 'proxy', label: 'netskope', stale: true }),
  ]);

  function build(query: Record<string, string> = {}, response = of(graph) as ReturnType<GovernanceService['hostMapGraph']>): void {
    governance = jasmine.createSpyObj<GovernanceService>('GovernanceService', ['hostMapGraph']);
    governance.hostMapGraph.and.returnValue(response);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.returnValue(Promise.resolve(true));
    params = new BehaviorSubject(convertToParamMap(query));
    cyCalls = [];
    const fakeCy = { on: () => undefined, destroy: () => undefined, fit: () => undefined };
    spyOn(window, 'matchMedia').and.callFake(
      (q: string) => ({ matches: mobile, media: q, addEventListener: () => undefined, removeEventListener: () => undefined, addListener: () => undefined, removeListener: () => undefined }) as unknown as MediaQueryList,
    );
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [
        { provide: GovernanceService, useValue: governance },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { queryParamMap: params, snapshot: { queryParamMap: params.value } } },
        {
          provide: CYTOSCAPE_LOADER,
          useValue: () =>
            loaderFails
              ? Promise.reject(new Error('chunk'))
              : Promise.resolve((options: unknown) => {
                  cyCalls.push(options);
                  return fakeCy;
                }),
        },
      ],
    });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    loaderFails = false;
    mobile = false;
  });

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const el = (selector: string) => (fixture.nativeElement as HTMLElement).querySelector(selector) as HTMLElement | null;
  const all = (selector: string) => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(selector)) as HTMLElement[];

  it('lit le plan du poste et l’ouvre en vue Plan par défaut, dessiné par cytoscape', async () => {
    build();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(governance.hostMapGraph).toHaveBeenCalledOnceWith('h1');
    expect(el('app-forge-map-canvas')).not.toBeNull();
    expect(cyCalls.length).toBe(1);
    expect(el('.forge-map__files')?.hidden).toBeTrue();
    expect(text()).toContain('Carte indexée le');
  });

  it('la bascule pose la vue dans l’URL ; Plan n’encombre pas l’adresse', () => {
    build();
    all('.forge-map__switch-option')[2].click();
    expect(router.navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
      queryParams: { 'vue-carte': 'fichiers' }, queryParamsHandling: 'merge',
    }));
    all('.forge-map__switch-option')[0].click();
    expect(router.navigate).toHaveBeenCalledWith([], jasmine.objectContaining({ queryParams: { 'vue-carte': null } }));
    expect(mapViewFrom('n-importe')).toBe('plan');
  });

  it('vue Fichiers : l’écran d’avant, projeté et visible ; aucun plan dessiné', () => {
    build({ 'vue-carte': 'fichiers' });
    expect(el('.forge-map__files')?.hidden).toBeFalse();
    expect(el('.files')?.textContent).toContain('les fichiers');
    expect(el('app-forge-map-canvas')).toBeNull();
  });

  it('vue Liste : un clic descend, le noeud entre dans l’URL ; le fil d’Ariane remonte', () => {
    build({ 'vue-carte': 'liste' });
    const items = all('.forge-map__item');
    expect(items.map((i) => i.querySelector('.forge-map__item-label')?.textContent)).toEqual(['compte prod', 'netskope']);
    expect(items[1].textContent).toContain('périmé');
    items[0].click();
    expect(router.navigate).toHaveBeenCalledWith([], jasmine.objectContaining({ queryParams: { noeud: 'compte' } }));

    params.next(convertToParamMap({ 'vue-carte': 'liste', noeud: 'compte' }));
    fixture.detectChanges();
    expect(all('.forge-map__item')[0].textContent).toContain('2 pièges');
    all('.forge-map__crumb')[0].click();
    expect(router.navigate).toHaveBeenCalledWith([], jasmine.objectContaining({ queryParams: { noeud: null } }));
  });

  it('sur téléphone, Plan devient la liste : aucun canevas', () => {
    mobile = true;
    build();
    expect(el('app-forge-map-canvas')).toBeNull();
    expect(all('.forge-map__item').length).toBe(2);
  });

  it('cytoscape introuvable : repli sur la liste, avec une phrase', async () => {
    loaderFails = true;
    build();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el('app-forge-map-canvas')).toBeNull();
    expect(text()).toContain('voici la même carte en liste');
  });

  it('carte non indexée : le dit, et propose les fichiers', () => {
    build({}, of(mapGraph([], { indexed: false, pendingSections: 4 })));
    expect(text()).toContain("La carte n'est pas encore indexée.");
    expect(text()).toContain('4 section(s) en cours de lecture');
  });

  it('plan illisible : le dit, et Réessayer relit', () => {
    build({}, throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(text()).toContain("Le plan n'a pas pu être lu.");
    governance.hostMapGraph.and.returnValue(of(graph));
    (el('.forge-map__state button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(governance.hostMapGraph).toHaveBeenCalledTimes(2);
    expect(text()).not.toContain("Le plan n'a pas pu être lu.");
  });

  it('les éléments du canevas : cadre composé, pièges écrits, sélection', () => {
    const level = levelFor(graph, 'lzi', 'CAGIP');
    const elements = elementsOf(level);
    expect(elements[0].data['id']).toBe('cadre:compte');
    const lzi = elements.find((e) => e.data['id'] === 'lzi');
    expect(lzi?.data['parent']).toBe('cadre:compte');
    expect(lzi?.data['selected']).toBeTrue();
    expect(nodeCaption('lzi-prod', 2, true)).toBe('lzi-prod\n⚠ 2 pièges · à cartographier');
  });
});
