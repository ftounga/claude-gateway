import { NameIndex, normalizeName } from './jarvis-name-matcher';
import { JarvisSceneReader, LIT_MS, MIN_SCENE_MS, maskSecrets } from './jarvis-scene-reader';
import { GENERIC_SCENE, SCENE_REGISTRY, humanize, resolveScene } from './jarvis-scene-registry';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-02 — registre des scènes', () => {
  it('un outil absent du registre ⇒ scène générique, nom humanisé', () => {
    const r = resolveScene('outil_tout_neuf');
    expect(r.scene).toBe(GENERIC_SCENE);
    expect(r.source).toBe('fallback');
    expect(r.libelle).toBe('Outil tout neuf');
    expect(humanize('update_blocker')).toBe('Update blocker');
  });

  it('teams_read_conversation ⇒ générique « Teams · … » par préfixe', () => {
    const r = resolveScene('teams_read_conversation');
    expect(r.source).toBe('prefix');
    expect(r.scene).toBe(GENERIC_SCENE);
    expect(r.libelle).toBe('Teams · read conversation');
  });

  it('nom exact avant le préfixe, type d\'étape ensuite', () => {
    expect(resolveScene('video_render').source).toBe('prefix');
    expect(resolveScene('video_images').libelle).toBe('Regarder une vidéo');
    expect(resolveScene('read').source).toBe('step');
    expect(resolveScene('record_blocker').libelle).toBe('Attente posée');
  });

  it('chaque entrée a un libellé et une scène', () => {
    for (const map of [SCENE_REGISTRY.tools, SCENE_REGISTRY.prefixes, SCENE_REGISTRY.steps]) {
      for (const [name, entry] of Object.entries(map)) {
        expect(entry.libelle).withContext(name).toBeTruthy();
        expect(entry.scene).withContext(name).toBeTruthy();
      }
    }
  });
});

describe('F-234 / SF-234-02 — rapprochement de noms (D2)', () => {
  const index = new NameIndex(testUniverse());

  it('« CAGIP » allume le client ; accents et casse normalisés', () => {
    expect(index.match('On regarde chez cagip.').map((n) => n.key)).toEqual(['h-cagip']);
    expect(normalizeName('Édénred’s')).toBe('edenred s');
  });

  it('« web » (exclu) n\'allume rien ; un nom présent dans deux sujets n\'allume rien', () => {
    expect(index.match('je cherche sur le web')).toEqual([]);
    expect(index.match('le certificat Ingress expire')).toEqual([]);
  });

  it('le plus long nom gagne, frontière de mot respectée', () => {
    expect(index.match('Sur Migration Forge, rien').map((n) => n.key)).toEqual(['s-forge']);
    expect(index.match('cagipx et runners')).toEqual([]);
  });

  it('« chantier 3 » allume le bon losange du sujet de contexte', () => {
    expect(index.match('le chantier 3 avance', 's-forge').map((n) => n.key)).toEqual(['s-forge#3']);
    expect(index.match('le chantier 3 avance', 's-doc')).toEqual([]);
  });

  it('une variante d\'interlocuteur allume la personne', () => {
    expect(index.match('écrire à Zahi').map((n) => n.kind)).toEqual(['person']);
  });
});

describe('F-234 / SF-234-02 — lecteur de scène', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('un outil inconnu ⇒ S-GEN avec nom humanisé, sujet courant en surbrillance', () => {
    reader.action({ type: 'outil_futur' }, 0);
    const s = reader.state().scene!;
    expect(s.id).toBe(GENERIC_SCENE);
    expect(s.libelle).toBe('Outil futur');
    expect(s.entity?.key).toBe('s-forge');
    expect(reader.state().pulses).toBe(1);
  });

  it('la cible d\'une action allume le nœud (priorité action)', () => {
    reader.action({ type: 'read', path: 'Doc archi/README.md' }, 0);
    expect(reader.state().scene?.entity?.key).toBe('s-doc');
    expect(reader.state().lit.map((l) => [l.node.key, l.priority])).toEqual([['s-doc', 2]]);
  });

  it('10 `read` en 1 s ⇒ une seule scène « 10 fichiers lus »', () => {
    for (let i = 0; i < 10; i++) {
      reader.action({ type: 'read', path: `src/f${i}.ts` }, i * 100);
    }
    const s = reader.state().scene!;
    expect(s.count).toBe(10);
    expect(s.libelle).toBe('10 fichiers lus');
    expect(reader.state().pulses).toBe(10);
  });

  it('une nouvelle scène attend la durée minimale de la précédente', () => {
    reader.action({ type: 'bash', path: 'ls' }, 0);
    reader.action({ type: 'grep', path: 'TODO' }, 200);
    expect(reader.state().scene?.key).toBe('bash');
    reader.tick(MIN_SCENE_MS + 10);
    expect(reader.state().scene?.key).toBe('grep');
  });

  it('bloc mail sur 5 deltas ⇒ « en préparation », À, Objet, puis « prêt », corps jamais rendu', () => {
    reader.text('🎙 Je prépare le mail.\n```ma', 0);
    reader.text('il\nÀ : Zahi\n', 10);
    expect(reader.state().scene?.libelle).toBe('Mail en préparation');
    expect(reader.state().scene?.detail).toBe('À : Zahi');
    expect(reader.state().scene?.entity?.kind).toBe('person');
    reader.text('Objet : Compte forge\n\nBonjour CAGIP, le mot de passe', 20);
    expect(reader.state().scene?.detail).toBe('À : Zahi · Objet : Compte forge');
    reader.text(' secret\n', 30);
    reader.text('```\n', 40);
    const s = reader.state().scene!;
    expect(s.libelle).toBe('Mail prêt · à envoyer');
    expect(reader.state().toSendAdded).toBe(1);
    expect(JSON.stringify(reader.state())).not.toContain('Bonjour');
    // « CAGIP » dans le corps n'allume rien.
    expect(reader.state().lit.some((l) => l.node.key === 'h-cagip')).toBeFalse();
  });

  it('les noms s\'allument au fil de l\'écriture ; ligne 🎙 > corps ; au plus 3 ; extinction après 6 s', () => {
    reader.text('🎙 On passe sur Edenred', 0);
    reader.text(' puis ', 10);
    expect(reader.state().lit.map((l) => l.node.key)).toEqual(['h-eden']);
    reader.text('CAGIP.\nRunner et Doc archi et Migration Forge.\n', 20);
    const lit = reader.state().lit;
    expect(lit.length).toBe(3);
    expect(lit.filter((l) => l.priority === 1).map((l) => l.node.key).sort()).toEqual(['h-cagip', 'h-eden']);
    reader.tick(LIT_MS + 30);
    expect(reader.state().lit).toEqual([]);
  });

  it('l\'autorisation passe devant et y reste jusqu\'à sa réponse', () => {
    reader.action({ type: 'bash', path: 'ls' }, 0);
    reader.confirm('Envoyer la note au CODIR ?', 100);
    reader.action({ type: 'grep', path: 'x' }, 5000);
    reader.tick(9000);
    expect(reader.state().scene?.id).toBe('S2');
    expect(reader.state().scene?.pinned).toBeTrue();
    reader.confirmResolved(9100);
    expect(reader.state().scene?.pinned ?? false).toBeFalse();
  });

  it('les secrets sont masqués dans le détail (D11)', () => {
    expect(maskSecrets('curl -H "Authorization: Bearer abc.def" https://x')).not.toContain('abc.def');
    expect(maskSecrets('export OPENAI=sk-proj-abcdefghijkl')).not.toContain('sk-proj');
    expect(maskSecrets('psql password=hunter2 host=db')).toContain('password=•••');
    reader.action({ type: 'bash', path: 'curl -H "Authorization: Bearer tok123456"' }, 0);
    expect(reader.state().scene?.detail).not.toContain('tok123456');
  });
});
