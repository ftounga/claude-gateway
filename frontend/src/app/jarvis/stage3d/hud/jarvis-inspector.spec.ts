import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { resolveShow } from '../../scenes/jarvis-show';
import { layoutUniverse3d } from '../jarvis-layout-3d';
import { bancWeatherUniverse } from '../jarvis-banc-scenes';
import { inspect, search } from './jarvis-inspector';

describe('F-236 / SF-236-19 — inspecteur de nœud (fiche fidèle à l’univers)', () => {
  const u = testUniverse();
  const fact = (card: ReturnType<typeof inspect>, label: string) => card?.facts.find((f) => f.label === label)?.value;

  it('poste : état, mission, sujets (hors terminal), chantiers, à envoyer ; liens vers sujets et personnes', () => {
    const card = inspect(u, 'h-cagip', 'client');
    expect(card?.title).toBe('CAGIP');
    expect(fact(card, 'Poste')).toBe('connecté');
    expect(fact(card, 'Mission')).toBe('active');
    expect(fact(card, 'Sujets')).toBe('4');
    expect(fact(card, 'Chantiers')).toBe('1');
    expect(fact(card, 'À envoyer')).toBe('1');
    expect(card?.links.map((l) => l.label)).toEqual(['Migration Forge', 'Doc archi', 'web', 'Ingress', 'Zahi Ben']);
    expect(fact(inspect(u, 'h-eden', 'client'), 'Poste')).toBe('déconnecté');
  });

  it('sujet : poste, terminal, chantiers (avec leur météo) ; il se choisit', () => {
    const card = inspect(u, 's-forge', 'subject');
    expect(card).toEqual(jasmine.objectContaining({ type: 'Sujet', title: 'Migration Forge', subjectId: 's-forge' }));
    expect(fact(card, 'Terminal')).toBe('au travail');
    expect(card?.links).toEqual([{ key: 's-forge#3', kind: 'chantier', label: 'Chantier 3 — Bascule DNS',
      note: 'attend Zahi' }]);
  });

  it('chantier et étape : phase, avancement, étape, météo ; liens vers le sujet et la personne attendue', () => {
    const card = inspect(u, 's-forge#3', 'chantier');
    expect(fact(card, 'Phase')).toBe('Exécution');
    expect(fact(card, 'Avancement')).toBe('2/5');
    expect(fact(card, 'Étape en cours')).toBe('3 — Basculer');
    expect(fact(card, 'Météo')).toBe('Nuageux — attend Zahi');
    expect(card?.links.map((l) => l.key)).toEqual(['s-forge', 'h-cagip:zahi']);
    const step = inspect(u, 's-forge#3/2', 'step');
    expect(step?.type).toBe('Étape');
    expect(step?.links.at(-1)?.key).toBe('s-forge#3');
  });

  it('personne : poste, rôle, attentes, message prêt ; lien vers le chantier qui l’attend', () => {
    const card = inspect(u, 'h-cagip:zahi', 'person');
    expect(card?.title).toBe('Zahi Ben');
    expect(fact(card, 'Rôle')).toBe('RSSI');
    expect(fact(card, 'Message prêt')).toBe('oui');
    expect(card?.links.map((l) => l.key)).toEqual(['s-forge#3']);
  });

  it('chaque nœud posé par l’univers 3D a une fiche ; clé inconnue ou univers absent ⇒ rien', () => {
    const layout = layoutUniverse3d(u, { subjectId: 's-forge', narrow: false, shown: new Set() });
    for (const n of layout.nodes) {
      expect(inspect(u, n.key, n.kind)).withContext(`${n.kind} ${n.key}`).not.toBeNull();
    }
    expect(inspect(u, 'absent', 'client')).toBeNull();
    expect(inspect(u, 's-forge#99', 'chantier')).toBeNull();
    expect(inspect(null, 'h-cagip', 'client')).toBeNull();
  });
});

describe('F-236 / SF-236-19 — recherche « montre-moi » (filtres de Montrer + noms, sans tour)', () => {
  it('« chantiers bloqués » ⇒ le filtre, résolu exactement comme « Montrer »', () => {
    const results = search(testUniverse(), 'montre-moi les chantiers bloqués');
    const show = results[0];
    expect(show).toEqual(jasmine.objectContaining({ type: 'show', filter: 'chantiers-bloques', client: null }));
    const focus = resolveShow(testUniverse(), { filter: 'chantiers-bloques', client: null })!;
    expect(show.type === 'show' && show.count).toBe(focus.items.length);
  });

  it('« edenred bloqué » ⇒ restreint à Edenred (orage du banc) ; « cagip connectés » ⇒ restreint à CAGIP', () => {
    const u = bancWeatherUniverse();
    const [show] = search(u, 'edenred bloqué');
    expect(show).toEqual(jasmine.objectContaining({ type: 'show', filter: 'chantiers-bloques', client: 'edenred',
      count: 1 }));
    expect(search(testUniverse(), 'cagip connectés')[0]).toEqual(jasmine.objectContaining({ filter: 'connectes',
      client: 'cagip', count: 1 }));
    // Filtre sans élément (Edenred n'a rien de bloqué dans l'univers de test) : seul le nœud est proposé.
    expect(search(testUniverse(), 'edenred bloqué').map((r) => r.type)).toEqual(['node']);
  });

  it('noms : postes, sujets, chantiers (titre ou numéro), personnes ; accents et casse ignorés', () => {
    const u = testUniverse();
    expect(search(u, 'forge').map((r) => r.type === 'node' && r.key)).toEqual(['s-forge']);
    expect(search(u, 'BASCULE').map((r) => r.type === 'node' && r.key)).toEqual(['s-forge#3']);
    expect(search(u, 'chantier 3').map((r) => r.type === 'node' && r.key)).toEqual(['s-forge#3']);
    expect(search(u, 'zahi')[0]).toEqual(jasmine.objectContaining({ type: 'node', key: 'h-cagip:zahi', kind: 'person' }));
    expect(search(u, 'édenred')[0]).toEqual(jasmine.objectContaining({ key: 'h-eden', kind: 'client' }));
    expect(search(u, 'ingress').length).toBe(2);
  });

  it('« à envoyer » ne restreint pas à un client par erreur ; trop court ou univers absent ⇒ rien', () => {
    expect(search(testUniverse(), 'à envoyer')[0]).toEqual(jasmine.objectContaining({ filter: 'a-envoyer',
      client: null }));
    expect(search(testUniverse(), 'z')).toEqual([]);
    expect(search(null, 'forge')).toEqual([]);
    expect(search(testUniverse(), 'xyzzy')).toEqual([]);
  });
});
