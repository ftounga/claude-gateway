import { AtelierMessage } from '../../../core/models/atelier.models';
import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import {
  FRAME_MS, MAX_ABSENCE, MAX_STEPS, ReplayPlayer, STEP_MS, TAIL_MS, absenceTracks, buildFrames, replaySteps, trackOf,
} from './jarvis-replay';

function user(id: string, createdAt: string, content = 'Bascule la zone DNS'): AtelierMessage {
  return { id, role: 'USER', createdAt, content } as AtelierMessage;
}

function reply(id: string, createdAt: string, blocks: { tool: string; command?: string | null; output?: string }[]):
    AtelierMessage {
  return {
    id, role: 'ASSISTANT', createdAt, content: 'Fait.',
    terminal: {
      blocks: blocks.map((b) => ({ tool: b.tool, command: b.command ?? null, toolUseId: null, output: b.output ?? '',
        hasOutput: !!b.output, error: false })),
      omittedBlocks: 0, inputTokens: 0, outputTokens: 0, activeSeconds: 0,
    },
  } as AtelierMessage;
}

const HISTORY: AtelierMessage[] = [
  user('u1', '2026-11-02T08:10:00Z'),
  reply('a1', '2026-11-02T08:11:00Z', [
    { tool: 'bash', command: 'kubectl get pods -n forge', output: 'forge-api 1/1 Running\nexport TOKEN=abc123secret' },
    { tool: 'read_file', command: 'docs/Migration Forge/plan.md' },
    { tool: 'write_file', command: 'src/dns/bascule.ts' },
  ]),
  user('u2', '2026-11-02T09:00:00Z', 'Juste une question'),
  reply('a2', '2026-11-02T09:00:05Z', []),
  user('u3', '2026-11-02T10:00:00Z', 'Prépare le mail à Zahi'),
  reply('a3', '2026-11-02T10:01:00Z', [{ tool: 'web_search', command: 'TTL DNS' }]),
];

describe('F-236 / SF-236-22 — replay d’un tour (lecteur privé, pur)', () => {
  const u = testUniverse();

  it('les étapes d’une réponse : outil + cible, sortie seulement si reçue ; bornées à MAX_STEPS', () => {
    expect(replaySteps(HISTORY[1]).map((s) => s.tool)).toEqual(['bash', 'read_file', 'write_file']);
    expect(replaySteps(HISTORY[1])[1]).toEqual({ tool: 'read_file', detail: 'docs/Migration Forge/plan.md', output: '' });
    const many = reply('x', '2026-11-02T08:00:00Z', Array.from({ length: MAX_STEPS + 9 }, () => ({ tool: 'grep' })));
    expect(replaySteps(many).length).toBe(MAX_STEPS);
    expect(replaySteps(null)).toEqual([]);
  });

  it('même tour ⇒ même suite de scènes (déterministe)', () => {
    const steps = replaySteps(HISTORY[1]);
    const a = buildFrames(steps, u, 's-forge');
    const b = buildFrames(steps, u, 's-forge');
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
    expect(a.length).toBe(Math.floor((steps.length * STEP_MS + TAIL_MS) / FRAME_MS) + 1);
  });

  it('chaque étape compte un appel ; les scènes du registre défilent dans l’ordre ; secrets masqués', () => {
    const frames = buildFrames(replaySteps(HISTORY[1]), u, 's-forge');
    expect(frames.at(-1)?.pulses).toBe(3);
    const ids = frames.map((f) => f.scene?.id ?? '-').filter((id, i, all) => id !== '-' && id !== all[i - 1]);
    expect(ids[0]).toBe('S3');
    expect(ids).toContain('S5');
    const lines = frames.flatMap((f) => f.scene?.data?.lines ?? []);
    expect(lines.join('\n')).not.toContain('abc123secret');
  });

  it('les noms cités allument leurs nœuds de l’univers (sujet nommé dans le chemin)', () => {
    const frames = buildFrames(replaySteps(HISTORY[1]), u, 's-forge');
    expect(frames.some((f) => f.lit.includes('s-forge'))).toBeTrue();
  });

  it('trackOf : le tour ouvert par le message ; un tour sans action ou inconnu ⇒ null', () => {
    const t = trackOf(HISTORY, 'u1', u, 's-forge');
    expect(t?.id).toBe('u1');
    expect(t?.ask).toBe('Bascule la zone DNS');
    expect(t?.duration).toBe(t?.frames.at(-1)?.at);
    expect(trackOf(HISTORY, 'u2', u, 's-forge')).toBeNull();
    expect(trackOf(HISTORY, 'a1', u, 's-forge')).toBeNull();
    expect(trackOf(HISTORY, 'absent', u, 's-forge')).toBeNull();
  });

  it('NF-10 : tours faits après la dernière visite, avec action, du plus ancien au plus récent ; aucune visite ⇒ rien', () => {
    expect(absenceTracks(HISTORY, Date.parse('2026-11-02T08:30:00Z'), u, 's-forge').map((t) => t.id)).toEqual(['u3']);
    expect(absenceTracks(HISTORY, Date.parse('2026-11-01T00:00:00Z'), u, 's-forge').map((t) => t.id))
      .toEqual(['u1', 'u3']);
    expect(absenceTracks(HISTORY, null, u, 's-forge')).toEqual([]);
    const lots: AtelierMessage[] = [];
    for (let i = 0; i < MAX_ABSENCE + 3; i++) {
      lots.push(user(`m${i}`, `2026-11-02T1${Math.floor(i / 6)}:${String((i % 6) * 10).padStart(2, '0')}:00Z`),
        reply(`r${i}`, '2026-11-02T18:00:00Z', [{ tool: 'grep' }]));
    }
    const kept = absenceTracks(lots, 0, u, null);
    expect(kept.length).toBe(MAX_ABSENCE);
    expect(kept.at(-1)?.id).toBe(`m${MAX_ABSENCE + 2}`);
  });
});

describe('F-236 / SF-236-22 — lecteur du replay (×1 / ×4, pause, image par image, enchaînement)', () => {
  const u = testUniverse();
  const t1 = trackOf(HISTORY, 'u1', u, 's-forge')!;
  const t3 = trackOf(HISTORY, 'u3', u, 's-forge')!;

  it('×1 et ×4 : la position avance du temps écoulé × vitesse', () => {
    const p = new ReplayPlayer([t1]);
    p.advance(1000);
    expect(p.at).toBe(1000);
    p.setSpeed(4);
    p.advance(1000);
    expect(p.at).toBe(5000);
    expect(p.frame()).toBe(t1.frames[Math.floor(5000 / FRAME_MS)]);
  });

  it('pause : la position ne bouge plus ; image par image met en pause et avance d’une image, bornée', () => {
    const p = new ReplayPlayer([t1]);
    p.togglePause();
    p.advance(5000);
    expect(p.at).toBe(0);
    p.togglePause();
    p.step(1);
    expect(p.paused).toBeTrue();
    expect(p.at).toBe(FRAME_MS);
    p.step(-1);
    p.step(-1);
    expect(p.at).toBe(0);
  });

  it('fin d’un tour ⇒ tour suivant ; fin du dernier ⇒ terminé (aucune image)', () => {
    const p = new ReplayPlayer([t1, t3]);
    p.advance(t1.duration + 1);
    expect(p.track).toBe(t3);
    expect(p.trackIndex).toBe(1);
    p.skip();
    expect(p.ended).toBeTrue();
    expect(p.frame()).toBeNull();
  });
});
