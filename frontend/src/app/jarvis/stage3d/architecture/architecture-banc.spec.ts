import { readBanc } from '../jarvis-banc';
import { ARCHITECTURE_BANC_SCENES, bancArchitecture } from './architecture-banc';

describe('F-238 / SF-238-09 — les scènes d\'architecture du banc', () => {
  it('chaque scène est lue par le banc (elle ne retombe pas sur le cœur), inopérante en production', () => {
    for (const scene of ARCHITECTURE_BANC_SCENES) {
      expect(readBanc(new URLSearchParams(`?banc=${scene}&t=1200`), true)?.scene).withContext(scene).toBe(scene);
      expect(readBanc(new URLSearchParams(`?banc=${scene}`), false)).toBeNull();
    }
  });

  it('chaque scène pose un graphe, un état et une phase ; la vue de dessus superpose le 2D cadré', () => {
    expect(bancArchitecture('architecture', false).phase).toBe('entree');
    expect(bancArchitecture('architecture-dessus', false)).toEqual(jasmine.objectContaining({ overlay: true }));
    expect(bancArchitecture('architecture-dessus', false).state.view).toBe('dessus');
    expect(bancArchitecture('architecture-eclate', true).state).toEqual(jasmine.objectContaining({ exploded: true,
      phone: true }));
    expect(bancArchitecture('architecture-sortie', false).phase).toBe('sortie');
    expect(bancArchitecture('architecture-60', false).graph.nodes.length).toBe(60);
    expect(bancArchitecture('architecture-allume', false).state.lit).toEqual(['alb', 'e1']);
  });
});
