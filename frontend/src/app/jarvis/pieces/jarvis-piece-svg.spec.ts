import { TestBed } from '@angular/core/testing';

import { JarvisPieceClient } from './jarvis-piece-client';
import { initialNav, reduceNav } from './jarvis-piece-nav';
import { JarvisPieceStore } from './jarvis-piece-store';
import { findTarget, svgTargets, viewOf } from './jarvis-piece-svg';
import { JarvisPieceViewerComponent } from './jarvis-piece-viewer.component';
import { registerViewer, resetViewers, viewerFor } from './jarvis-piece-viewers';
import { Piece } from './jarvis-piece.models';

/** Rendu maison (moteur ELK, SF-238-01) : zones et nœuds en coordonnées absolues. */
const MAISON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 500">
  <g id="zone-vpc" class="zone"><rect x="500" y="0" width="500" height="500"/><text>VPC de prod</text></g>
  <g id="zone-onprem" class="zone"><rect x="0" y="0" width="250" height="250"/><text>Sur site</text></g>
  <g id="node-db" class="noeud"><rect x="700" y="200" width="100" height="100"/><text>Base</text></g>
</svg>`;

/** Rendu Graphviz : grappes en polygone, translate racine. */
const GRAPHVIZ = `<svg xmlns="http://www.w3.org/2000/svg" width="400pt" height="200pt" viewBox="0 0 400 200">
  <g id="graph0" class="graph" transform="translate(4 196)">
    <g id="clust1" class="cluster"><title>cluster_vpc</title>
      <polygon points="0,-190 200,-190 200,-10 0,-10 0,-190"/><text>VPC</text></g>
  </g></svg>`;

const ARCHI: Piece = { id: 'diag:1', kind: 'ARCHITECTURE', category: 'PRODUCTION', title: 'Edenred en prod',
  open: '/jarvis/pieces/diag:1/image' };

class FakeClient {
  async objectUrl() {
    return 'blob:local/schema';
  }
  async text() {
    return MAISON;
  }
  async json() {
    return null;
  }
}

describe('Jarvis — schémas sur panneau (F-237 / SF-237-07)', () => {
  afterEach(() => resetViewers());

  it('lit les cibles nommées : zones et nœuds du rendu maison, grappes Graphviz avec leur translate', () => {
    const maison = svgTargets(MAISON);
    expect(maison.frame).toEqual({ x: 0, y: 0, w: 1000, h: 500 });
    expect(maison.targets.map((t) => t.label)).toEqual(['VPC de prod', 'Sur site', 'Base']);
    const graphviz = svgTargets(GRAPHVIZ);
    expect(graphviz.targets[0]).toEqual({ label: 'VPC', box: { x: 4, y: 6, w: 200, h: 180 } });
    expect(svgTargets('pas du svg').targets).toEqual([]);
  });

  it('trouve la cible : libellé exact, sinon unique approché, article ignoré', () => {
    const { targets } = svgTargets(MAISON);
    expect(findTarget(targets, 'le VPC de prod')?.label).toBe('VPC de prod');
    expect(findTarget(targets, 'vpc')?.label).toBe('VPC de prod');
    expect(findTarget(targets, 'la base')?.label).toBe('Base');
    expect(findTarget(targets, 'Kubernetes')).toBeNull();
  });

  it('cadre la cible : centrée, ~80 % du schéma, zoom borné', () => {
    const view = viewOf({ x: 0, y: 0, w: 1000, h: 500 }, { x: 500, y: 0, w: 500, h: 500 });
    expect(view.zoom).toBe(1);
    expect(view.tx).toBe(-25);
    expect(view.ty).toBe(0);
    const node = viewOf({ x: 0, y: 0, w: 1000, h: 500 }, { x: 700, y: 200, w: 100, h: 100 });
    expect(node.zoom).toBe(4);
    expect(node.tx).toBe(-25);
    expect(viewOf({ x: 0, y: 0, w: 1000, h: 500 }, { x: 0, y: 0, w: 1, h: 1 }).zoom).toBe(8);
  });

  it('panneau : zones touchables, « zoome sur la base » cadre la cible, introuvable dit', async () => {
    TestBed.configureTestingModule({
      imports: [JarvisPieceViewerComponent],
      providers: [{ provide: JarvisPieceClient, useValue: new FakeClient() }],
    });
    const store = TestBed.inject(JarvisPieceStore);
    store.reset();
    store.show({ subjectId: 's', label: 'Schémas' }, [ARCHI]);
    store.dispatch({ type: 'select', ref: 1 });
    const fixture = TestBed.createComponent(JarvisPieceViewerComponent);
    fixture.componentRef.setInput('piece', ARCHI);
    fixture.componentRef.setInput('nav', store.nav());
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    const zones = fixture.nativeElement.querySelectorAll('.pv__zone');
    expect(zones.length).toBe(3);
    (zones[2] as HTMLButtonElement).click(); // « Base »
    fixture.componentRef.setInput('nav', store.nav());
    fixture.detectChanges();
    const img = fixture.nativeElement.querySelector('.pv__image') as HTMLImageElement;
    expect(img.style.transform).toContain('scale(4)');
    expect(img.style.transform).toContain('translate(-25%, -0%)'.replace('-0%', '0%'));
    fixture.componentRef.setInput('nav', reduceNav(store.nav(), { type: 'zoom', target: 'Kubernetes' }).state);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Je ne trouve pas « Kubernetes »');
  });

  it('point d’extension : une visionneuse 3D remplace celle de l’architecture, le reste ne bouge pas', () => {
    registerViewer('ARCHITECTURE', { name: 'architecture-3d', paged: false, timed: false, zoomable: true });
    expect(viewerFor('ARCHITECTURE').name).toBe('architecture-3d');
    expect(viewerFor('DIAGRAM').name).toBe('schema-svg');
    // le réducteur accepte toujours une cible nommée générique
    const nav = reduceNav(reduceNav(initialNav(), { type: 'openGallery', scope: { subjectId: 's', label: 'x' },
      pieces: [ARCHI] }).state, { type: 'select', ref: 1 }).state;
    expect(reduceNav(nav, { type: 'zoom', target: 'sous-réseau privé' }).state.target).toBe('sous-réseau privé');
  });
});
