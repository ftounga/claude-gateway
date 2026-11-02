import { DETAIL_CHARS, MAX_TICKETS } from '../../scenes/jarvis-scene-reader';
import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { CORE_KEY } from '../jarvis-link-graph';
import { SCENES_3D, SGEN_3D_IDS } from './index';
import { BANC_SCENE_VIEWS, sceneOf } from './jarvis-scene3d.fixture';
import { Effect3d, SceneContext } from './jarvis-scene3d.model';
import { sceneCommande, sceneFichiers, sceneTickets } from './jarvis-scenes-poste';
import {
  EMPTY_SESSION, MAX_CLOUD_POINTS, SessionMemory, TICKET_TTL_MS, dirOf, folderSpot,
} from './jarvis-session-memory';

function ctx(extra: Partial<SceneContext> = {}): SceneContext {
  return { universe: testUniverse(), subjectId: 's-forge', nodes: new Set(['h-cagip', 's-forge', 's-forge#3', 'h-eden']),
    pulses: 1, narrow: false, session: EMPTY_SESSION, spokenTickets: [], ...extra };
}

function effect<K extends Effect3d['kind']>(effects: readonly Effect3d[], kind: K): Extract<Effect3d, { kind: K }> {
  return effects.find((e) => e.kind === kind) as Extract<Effect3d, { kind: K }>;
}

describe('F-236 / SF-236-09 — S3 commande (+ N6)', () => {
  it('zoom sur le poste ; panneau mono : la commande et ses 2 dernières lignes', () => {
    const plan = sceneCommande(BANC_SCENE_VIEWS['commande'], ctx());
    expect(plan.focus).toBe('h-cagip');
    expect(plan.panels[0]).toEqual(jasmine.objectContaining({ anchor: 'h-cagip', mono: true, kind: 'terminal' }));
    expect(plan.panels[0].lines.map((l) => l.text)).toEqual(['$ kubectl get pods -n forge',
      'forge-api-7d9c   1/1   Running   0   2d', 'export TOKEN=•••']);
  });

  it('jamais plus de 2 lignes de sortie, 80 car. au plus, secrets masqués (défense en profondeur, D11)', () => {
    const long = 'x'.repeat(200);
    const scene = sceneOf('S3', 'bash', 'Commande', { detail: 'curl -H "Authorization: Bearer abc.def"' },
      { lines: ['une', 'password=hunter2 deux', long] });
    const lines = sceneCommande(scene, ctx()).panels[0].lines.map((l) => l.text);
    expect(lines.length).toBe(3);
    expect(lines.every((l) => l.length <= DETAIL_CHARS)).toBeTrue();
    expect(lines.join(' ')).not.toContain('hunter2');
    expect(lines.join(' ')).not.toContain('abc.def');
    expect(lines).not.toContain('une');
  });

  it('N6 : flux du poste vers le cœur, débit ∝ sortie', () => {
    const quiet = effect(sceneCommande(sceneOf('S3', 'bash', 'C', {}, { lines: [] }), ctx()).effects, 'flow');
    const busy = effect(sceneCommande(BANC_SCENE_VIEWS['commande'], ctx()).effects, 'flow');
    expect(quiet).toEqual(jasmine.objectContaining({ from: 'h-cagip', to: CORE_KEY }));
    expect(busy.rate).toBeGreaterThan(quiet.rate);
    expect(busy.rate).toBeLessThanOrEqual(1);
  });

  it('poste inconnu : panneau au cœur, aucun flux', () => {
    const plan = sceneCommande(BANC_SCENE_VIEWS['commande'], ctx({ subjectId: null }));
    expect(plan.panels[0].anchor).toBe(CORE_KEY);
    expect(plan.effects).toEqual([]);
  });
});

describe('F-236 / SF-236-09 — S4 tickets', () => {
  it('cartes en éventail sorties du client, clés exactes ; la carte nommée dans la phrase dite s’illumine', () => {
    const plan = sceneTickets(BANC_SCENE_VIEWS['tickets'], ctx({ spokenTickets: ['FORGE-42'] }));
    expect(plan.panels[0].lines.map((l) => l.text)).toEqual(['FORGE-41', 'FORGE-42', 'OPS-7']);
    expect(plan.panels[0].lines.find((l) => l.hot)?.text).toBe('FORGE-42');
    expect(effect(plan.effects, 'cards')).toEqual({ kind: 'cards', anchor: 'h-cagip', count: 3, hot: 1, layout: 'fan',
      tone: 'normal' });
  });

  it('≤ 6 cartes ; les tickets vus plus tôt dans la session (même poste) restent, les plus récents', () => {
    const memory = new SessionMemory();
    memory.see(sceneOf('S4', 't', 'Jira', {}, { tickets: ['A-1', 'A-2', 'A-3', 'A-4'], source: 'Jira' }), 'h-cagip',
      's-forge', 0);
    memory.see(sceneOf('S4', 't2', 'Jira', {}, { tickets: ['Z-9'], source: 'Jira' }), 'h-eden', 's-runner', 1);
    const plan = sceneTickets(BANC_SCENE_VIEWS['tickets'], ctx({ session: memory.snapshot(10) }));
    const keys = plan.panels[0].lines.map((l) => l.text);
    expect(keys.length).toBe(MAX_TICKETS);
    expect(keys).toEqual(['A-2', 'A-3', 'A-4', 'FORGE-41', 'FORGE-42', 'OPS-7']);
    expect(keys).not.toContain('Z-9');
    expect(plan.panels[0].lines.find((l) => l.text === 'A-2')?.tone).toBe('off');
  });
});

describe('F-236 / SF-236-09 — S5 fichiers (+ N9, N11)', () => {
  it('écriture : feuillets en pile, le dernier en blanc vif', () => {
    const plan = sceneFichiers(BANC_SCENE_VIEWS['fichiers'], ctx());
    expect(effect(plan.effects, 'cards')).toEqual(jasmine.objectContaining({ layout: 'stack', count: 3, hot: 2,
      anchor: 's-forge' }));
    expect(plan.panels[0].lines[2]).toEqual({ text: 'bascule.ts', hot: true });
    expect(plan.panels[0].scroll).toBeFalse();
  });

  it('recherche : la loupe balaie, le motif s’écrit', () => {
    const plan = sceneFichiers(BANC_SCENE_VIEWS['recherche'], ctx());
    expect(effect(plan.effects, 'scan')).toEqual({ kind: 'scan', anchor: 's-forge', tone: 'normal' });
    expect(plan.panels[0].lines[0].text).toBe('Recherche : ttl');
  });

  it('N11 : un document lu se lit en pages qui défilent ; un fichier de code non', () => {
    expect(sceneFichiers(BANC_SCENE_VIEWS['lecture'], ctx()).panels[0].scroll).toBeTrue();
    const code = sceneOf('S5', 'read_file', 'Lecture', { detail: 'src/a.ts' }, { mode: 'read', files: ['a.ts'] });
    expect(sceneFichiers(code, ctx()).panels[0].scroll).toBeFalse();
  });

  it('N9 : le nuage ne montre que les fichiers de la session pour le sujet courant (un amas par dossier)', () => {
    const memory = new SessionMemory();
    memory.see(sceneOf('S5', 'a', 'L', { detail: 'src/dns/zone.ts' }, { mode: 'read', files: ['zone.ts'] }),
      'h-cagip', 's-forge', 0);
    memory.see(sceneOf('S5', 'b', 'E', { detail: 'src/dns/ttl.ts' }, { mode: 'write', files: ['ttl.ts'] }),
      'h-cagip', 's-forge', 1);
    memory.see(sceneOf('S5', 'c', 'L', { detail: 'lib/x.ts' }, { mode: 'read', files: ['x.ts'] }),
      'h-eden', 's-runner', 2);
    const cloud = effect(sceneFichiers(BANC_SCENE_VIEWS['fichiers'], ctx({ session: memory.snapshot(3) })).effects,
      'cloud');
    expect(cloud.points.length).toBe(2);
    expect(cloud.points.map((p) => p.write)).toEqual([false, true]);
    expect(Math.abs(cloud.points[0].height - cloud.points[1].height)).toBe(0); // même dossier, même amas
    expect(effect(sceneFichiers(BANC_SCENE_VIEWS['fichiers'], ctx()).effects, 'cloud')).toBeUndefined();
  });
});

describe('F-236 / SF-236-09 — mémoire de session (noms seulement, aucun appel)', () => {
  it('un ticket s’estompe après 30 min sans mention ; une nouvelle mention le ravive', () => {
    const memory = new SessionMemory();
    const s4 = (key: string) => sceneOf('S4', key, 'Jira', {}, { tickets: [key], source: 'Jira' });
    memory.see(s4('A-1'), 'h-cagip', 's-forge', 0);
    expect(memory.snapshot(TICKET_TTL_MS).tickets.map((t) => t.key)).toEqual(['A-1']);
    expect(memory.snapshot(TICKET_TTL_MS + 1).tickets).toEqual([]);
    memory.see(s4('A-1'), 'h-cagip', 's-forge', TICKET_TTL_MS + 5);
    expect(memory.snapshot(TICKET_TTL_MS + 6).tickets.length).toBe(1);
  });

  it('le nuage est borné ; une même scène n’est lue qu’une fois ; les recherches n’y entrent pas', () => {
    const memory = new SessionMemory();
    for (let i = 0; i < MAX_CLOUD_POINTS + 10; i++) {
      memory.see(sceneOf('S5', `k${i}`, 'L', { detail: `d/f${i}.ts` }, { mode: 'read', files: [`f${i}.ts`] }),
        null, 's-forge', i);
    }
    expect(memory.snapshot(1000).files.length).toBe(MAX_CLOUD_POINTS);
    const search = sceneOf('S5', 'grep', 'R', {}, { mode: 'search', pattern: 'x' });
    expect(memory.see(search, null, 's-forge', 2000)).toBeFalse();
    expect(memory.see(search, null, 's-forge', 2001)).toBeFalse();
  });

  it('dossier lu dans le chemin ; même dossier ⇒ même place dans le nuage', () => {
    expect(dirOf('src/app/dns/ttl.ts', 'ttl.ts')).toBe('src/app/dns');
    expect(dirOf('ttl.ts', 'ttl.ts')).toBe('');
    expect(dirOf('autre/chose.ts', 'ttl.ts')).toBe('');
    expect(folderSpot('src/a')).toEqual(folderSpot('src/a'));
  });
});

describe('F-236 / SF-236-09 — registre', () => {
  it('S3, S4, S5 ont leur rendu dédié et ne retombent plus sur S-GEN', () => {
    for (const id of ['S3', 'S4', 'S5']) {
      expect(Object.hasOwn(SCENES_3D, id)).withContext(id).toBeTrue();
      expect(SGEN_3D_IDS.has(id)).withContext(id).toBeFalse();
    }
  });
});
