import { AtelierMessage } from '../../../core/models/atelier.models';
import { SceneView } from '../../scenes/jarvis-scene-reader';
import { knownSceneIds } from '../scenes3d';
import { FAMILY_TONE, MAX_PEARLS, SCENE_FAMILY, TurnTimeline, dayAxis, dayTurns, familyOf } from './jarvis-timelines';
import { pearlArc } from './jarvis-cockpit.component';

function scene(id: string): SceneView {
  return { id, key: id, libelle: id, count: 1, detail: null, entity: null, pinned: false, since: 0, updatedAt: 0 };
}

function msg(id: string, role: 'USER' | 'ASSISTANT', createdAt: string, content: string,
    tools: string[] = [], omitted = 0): AtelierMessage {
  return {
    id, role, createdAt, content,
    terminal: role === 'ASSISTANT' ? {
      blocks: tools.map((tool) => ({ tool, command: null, toolUseId: null, output: '', hasOutput: false, error: false })),
      omittedBlocks: omitted, inputTokens: 0, outputTokens: 0, activeSeconds: 0,
    } : null,
  } as AtelierMessage;
}

describe('F-236 / SF-236-17 — chronologies (pur)', () => {
  it('perles colorées par famille = registre : toute scène connue a sa famille, dans la grammaire (jamais rouge)', () => {
    for (const id of knownSceneIds()) {
      expect(Object.hasOwn(SCENE_FAMILY, id)).withContext(id).toBeTrue();
    }
    expect(Object.values(FAMILY_TONE)).not.toContain('alert' as never);
    expect(familyOf('S3')).toBe('poste');
    expect(familyOf('S2')).toBe('attente');
    expect(familyOf('S7')).toBe('reussite');
    expect(familyOf('S13')).toBe('cerveau');
    expect(familyOf('INCONNUE')).toBe('autre');
    expect(familyOf(null)).toBe('autre');
  });

  it('chronologie du tour : une perle par appel, famille de la scène du moment ; remise à zéro au tour suivant', () => {
    const t = new TurnTimeline();
    expect(t.observe({ turnActive: true, pulses: 0, scene: null })).toBeTrue();
    t.observe({ turnActive: true, pulses: 2, scene: scene('S3') });
    t.observe({ turnActive: true, pulses: 3, scene: scene('S2') });
    expect(t.observe({ turnActive: true, pulses: 3, scene: scene('S2') })).toBeFalse();
    expect(t.snapshot().pearls.map((p) => p.family)).toEqual(['poste', 'poste', 'attente']);
    // Fin du tour : la chronologie reste lisible.
    t.observe({ turnActive: false, pulses: 3, scene: null });
    expect(t.snapshot().pearls.length).toBe(3);
    // Tour suivant : remise à zéro.
    t.observe({ turnActive: true, pulses: 0, scene: null });
    expect(t.snapshot().pearls.length).toBe(0);
  });

  it(`au-delà de ${MAX_PEARLS} perles : les dernières, et « +n »`, () => {
    const t = new TurnTimeline();
    t.observe({ turnActive: true, pulses: 55, scene: scene('S5') });
    expect(t.snapshot().pearls.length).toBe(MAX_PEARLS);
    expect(t.snapshot().more).toBe(15);
  });

  it('arc de perles : de gauche à droite, dans le cadre, une seule perle au sommet', () => {
    const dots = pearlArc([{ family: 'poste' }, { family: 'attente' }, { family: 'cerveau' }]);
    expect(dots[0].x).toBeLessThan(dots[1].x);
    expect(dots[1].x).toBeLessThan(dots[2].x);
    expect(dots.every((d) => d.x >= 0 && d.x <= 280 && d.y >= 0 && d.y <= 120)).toBeTrue();
    expect(dots.map((d) => d.tone)).toEqual(['normal', 'wait', 'deep']);
    expect(pearlArc([{ family: 'poste' }])[0].x).toBe(140);
  });

  it('chronologie de la journée : tours du jour seulement, réponse, actions et familles du registre', () => {
    const today = new Date(2026, 10, 2, 15, 0);
    const iso = (h: number, m: number, d = 2) => new Date(2026, 10, d, h, m).toISOString();
    const messages = [
      msg('u0', 'USER', iso(18, 0, 1), 'Hier'),
      msg('a0', 'ASSISTANT', iso(18, 1, 1), 'Fait.'),
      msg('u1', 'USER', iso(9, 5), 'Vérifie   les pods\nde forge'),
      msg('a1', 'ASSISTANT', iso(9, 6), '**Tout va bien.** Trois pods tournent.', ['bash', 'read_file', 'email_draft'], 2),
      { ...msg('u2', 'USER', iso(11, 30), 'Le point'), via: 'JARVIS' } as AtelierMessage,
    ];
    const turns = dayTurns(messages, today);
    expect(turns.map((t) => t.id)).toEqual(['u1', 'u2']);
    expect(turns[0]).toEqual(jasmine.objectContaining({ time: '09:05', minute: 545, ask: 'Vérifie les pods de forge',
      answer: 'Tout va bien.', actions: 5, voice: false }));
    expect(turns[0].pearls.length).toBe(3);
    expect(turns[0].pearls.slice(0, 2).map((p) => p.family)).toEqual(['poste', 'poste']);
    expect(turns[1]).toEqual(jasmine.objectContaining({ answer: null, actions: 0, voice: true }));
    expect(dayAxis(turns, today)).toEqual({ from: 8 * 60, to: 15 * 60 });
    // Un tour plus tard que l'horloge : l'axe va jusqu'à l'heure qui le suit.
    expect(dayAxis(turns, new Date(2026, 10, 2, 10, 0))).toEqual({ from: 8 * 60, to: 12 * 60 });
    expect(dayTurns([], today)).toEqual([]);
  });
});
