import { AtelierLane } from '../core/services/atelier.service';
import {
  NO_PANES, PaneLayout, arrivalLayout, closeSide, place, placeBeside, prune, readLayout, sideOf, writeLayout,
} from './pane-layout';

/** F-246 / SF-246-01 — quel chantier est dans quel volet : les parcours du PO du 03/11, en fonctions pures. */
describe('pane-layout (F-246)', () => {
  const lane = (chantier: number | null, startedAt: number, live = true, autonomy = false): AtelierLane =>
    ({ chantier, startedAt, live, autonomy });
  const layout = (left: number | null, right: number | null): PaneLayout => ({ left, right });

  describe('arrivée sur le sujet', () => {
    it('sans disposition gardée : le focus à gauche, le plus ancien autre chantier actif à droite', () => {
      expect(arrivalLayout(null, 10, [lane(12, 5), lane(11, 3), lane(10, 1), lane(null, 0)], [9, 10, 11, 12]))
        .toEqual(layout(10, 11));
    });

    it('sans autre chantier actif : un seul volet', () => {
      expect(arrivalLayout(null, 10, [lane(10, 1), lane(9, 0, false)], [9, 10])).toEqual(layout(10, null));
    });

    it('la disposition gardée revient telle quelle, même si le focus du serveur a bougé', () => {
      expect(arrivalLayout(layout(9, 11), 12, [lane(10, 1)], [9, 10, 11, 12])).toEqual(layout(9, 11));
    });

    it('un chantier gardé mais clos depuis ne revient pas', () => {
      expect(arrivalLayout(layout(9, 11), 10, [], [9, 10])).toEqual(layout(9, null));
      expect(arrivalLayout(layout(9, 11), 10, [], [10, 11])).toEqual(layout(10, null));
    });

    it('chantiers pas encore connus : la disposition gardée est crue', () => {
      expect(arrivalLayout(layout(9, 11), 10, [], null)).toEqual(layout(9, 11));
    });

    it('sujet non guidé : aucun volet', () => {
      expect(arrivalLayout(null, null, [lane(7, 1)], [])).toEqual(NO_PANES);
    });
  });

  describe('placer un chantier', () => {
    it('10 à gauche, 11 à droite : ouvrir 9 à gauche ne touche pas la droite', () => {
      expect(place(layout(10, 11), 9, 'left')).toEqual(layout(9, 11));
    });

    it('un chantier déjà dans l\'autre volet échange sa place (rien ne disparaît)', () => {
      expect(place(layout(10, 11), 10, 'right')).toEqual(layout(11, 10));
      expect(place(layout(10, 11), 11, 'left')).toEqual(layout(11, 10));
    });

    it('déjà à sa place : rien ne change (même objet)', () => {
      const current = layout(10, 11);
      expect(place(current, 10, 'left')).toBe(current);
    });

    it('à droite sans split : le split s\'ouvre, la gauche reste', () => {
      expect(place(layout(10, null), 11, 'right')).toEqual(layout(10, 11));
    });
  });

  describe('ouvrir ou lancer un chantier à côté', () => {
    it('depuis la gauche : il arrive à droite', () => {
      expect(placeBeside(layout(9, null), 10, 'left')).toEqual(layout(9, 10));
      expect(placeBeside(layout(9, 10), 11, 'left')).toEqual(layout(9, 11));
    });

    it('depuis la droite : il arrive à gauche, la droite ne bouge pas', () => {
      expect(placeBeside(layout(9, 10), 11, 'right')).toEqual(layout(11, 10));
    });

    it('déjà affiché : rien ne bouge', () => {
      const current = layout(9, 10);
      expect(placeBeside(current, 9, 'left')).toBe(current);
    });

    it('sujet sans chantier épinglé : le chantier lancé s\'ouvre à droite du fil du sujet', () => {
      expect(placeBeside(NO_PANES, 8, 'left')).toEqual(layout(null, 8));
    });
  });

  describe('fermer puis rouvrir', () => {
    it('fermer la droite referme le split ; le chantier se rouvre à droite', () => {
      const closed = closeSide(layout(10, 11), 'right');
      expect(closed).toEqual(layout(10, null));
      expect(place(closed, 11, 'right')).toEqual(layout(10, 11));
    });

    it('fermer la gauche laisse le chantier de droite seul ; l\'autre se rouvre', () => {
      const closed = closeSide(layout(10, 11), 'left');
      expect(closed).toEqual(layout(11, null));
      expect(place(closed, 10, 'right')).toEqual(layout(11, 10));
    });

    it('fermer sans split : rien', () => {
      const current = layout(10, null);
      expect(closeSide(current, 'left')).toBe(current);
    });
  });

  describe('chantier clos', () => {
    it('le chantier clos de droite quitte son volet, la gauche ne bouge pas', () => {
      expect(prune(layout(9, 11), [9, 10], 9)).toEqual(layout(9, null));
    });

    it('le chantier clos de gauche est remplacé par le focus, sans toucher la droite', () => {
      expect(prune(layout(9, 11), [10, 11], 10)).toEqual(layout(10, 11));
    });

    it('le focus est déjà à droite : un autre chantier ouvert prend la gauche', () => {
      expect(prune(layout(9, 11), [10, 11], 11)).toEqual(layout(10, 11));
    });

    it('plus aucun autre chantier : celui de droite reste seul', () => {
      expect(prune(layout(9, 11), [11], 11)).toEqual(layout(11, null));
    });

    it('plus aucun chantier : plus de volet', () => {
      expect(prune(layout(9, 11), [], null)).toEqual(NO_PANES);
    });

    it('rien de clos : rien ne bouge', () => {
      expect(prune(layout(9, 11), [9, 10, 11], 10)).toEqual(layout(9, 11));
    });
  });

  it('sideOf dit où est un chantier', () => {
    expect(sideOf(layout(9, 11), 9)).toBe('left');
    expect(sideOf(layout(9, 11), 11)).toBe('right');
    expect(sideOf(layout(9, 11), 10)).toBeNull();
  });

  describe('gardée sur l\'appareil, par sujet', () => {
    afterEach(() => localStorage.removeItem('cg.atelier.panes.w-test'));

    it('écrite puis relue (rechargement)', () => {
      writeLayout('w-test', layout(9, 11));
      expect(readLayout('w-test')).toEqual(layout(9, 11));
      expect(readLayout('w-autre')).toBeNull();
    });

    it('sans volet épinglé : effacée', () => {
      writeLayout('w-test', layout(9, 11));
      writeLayout('w-test', NO_PANES);
      expect(readLayout('w-test')).toBeNull();
    });

    it('illisible ou trafiquée : ignorée', () => {
      localStorage.setItem('cg.atelier.panes.w-test', '{pas du json');
      expect(readLayout('w-test')).toBeNull();
      localStorage.setItem('cg.atelier.panes.w-test', JSON.stringify({ left: 'x', right: 2 }));
      expect(readLayout('w-test')).toBeNull();
      localStorage.setItem('cg.atelier.panes.w-test', JSON.stringify({ left: 3, right: -1 }));
      expect(readLayout('w-test')).toEqual(layout(3, null));
    });

    it('stockage bloqué : rien ne casse', () => {
      spyOn(localStorage, 'getItem').and.throwError('bloqué');
      spyOn(localStorage, 'setItem').and.throwError('bloqué');
      expect(() => writeLayout('w-test', layout(1, 2))).not.toThrow();
      expect(readLayout('w-test')).toBeNull();
    });
  });
});
