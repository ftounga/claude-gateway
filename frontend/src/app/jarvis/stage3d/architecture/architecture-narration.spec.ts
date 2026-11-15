import { ArchitectureGraph } from './architecture-graph';
import { TOUT_GRAPH, TROIS_TIERS_GRAPH } from './architecture-graph.fixture';
import { ArchLayout, layoutArchitecture } from './architecture-layout-3d';
import { ArchitectureNarrator, NARRATION_MAX_GROUPS } from './architecture-narration';

/**
 * Le jeu étiqueté (cadrage §17, SF-238-12) : pour chaque phrase, l'ensemble de ce qu'il est JUSTE d'allumer
 * (services, zones, liaisons). Une lueur hors de cet ensemble est une lueur fausse.
 */
const SET: readonly { graph: 'trois-tiers' | 'tout'; phrase: string; ok: string[] }[] = [
  { graph: 'trois-tiers', phrase: 'Le trafic entre par l\'ALB, Monsieur, puis se répartit sur les deux zones.',
    ok: ['alb', 'aza', 'azb', 'e1'] },
  { graph: 'trois-tiers', phrase: 'Les instances écrivent dans RDS primaire.', ok: ['ec2a', 'ec2b', 'rdsa', 'e4', 'e5'] },
  { graph: 'trois-tiers', phrase: 'RDS primaire est répliquée vers RDS secours.', ok: ['rdsa', 'rdsb', 'e6'] },
  { graph: 'trois-tiers', phrase: 'La base de données est en zone B.', ok: ['azb'] },
  { graph: 'trois-tiers', phrase: 'Le bucket S3 sert les fichiers statiques.', ok: ['s3'] },
  { graph: 'trois-tiers', phrase: 'Route 53 résout le nom de domaine.', ok: ['r53'] },
  { graph: 'trois-tiers', phrase: 'Les NAT permettent aux instances de sortir vers Internet.',
    ok: ['nata', 'natb', 'ec2a', 'ec2b', 'e8', 'e9'] },
  { graph: 'trois-tiers', phrase: 'Web A parle à RDS primaire en SQL.', ok: ['ec2a', 'rdsa', 'e4'] },
  { graph: 'trois-tiers', phrase: 'Le sous-réseau privé A héberge Web A.', ok: ['priva', 'ec2a'] },
  { graph: 'trois-tiers', phrase: 'Tout est dans le VPC.', ok: ['vpc'] },
  { graph: 'trois-tiers', phrase: 'Les utilisateurs passent d\'abord par Route 53.', ok: ['users', 'r53', 'e0'] },
  { graph: 'trois-tiers', phrase: 'Monsieur, je n\'ai rien à signaler sur cette architecture.', ok: [] },
  { graph: 'trois-tiers', phrase: 'L\'application tourne sur deux instances EC2.', ok: ['ec2a', 'ec2b'] },
  { graph: 'trois-tiers', phrase: 'Web B écrit aussi dans RDS primaire.', ok: ['ec2b', 'rdsa', 'e5'] },
  { graph: 'trois-tiers', phrase: 'La zone A contient NAT A, Web A et RDS primaire.',
    ok: ['aza', 'nata', 'ec2a', 'rdsa', 'e4'] },
  { graph: 'trois-tiers', phrase: 'Le cache n\'existe pas dans cette version.', ok: [] },
  { graph: 'trois-tiers', phrase: 'Les sauvegardes partent dans S3 statiques.', ok: ['s3'] },
  { graph: 'trois-tiers', phrase: 'Le load balancer distribue vers Web A et Web B.',
    ok: ['alb', 'ec2a', 'ec2b', 'e2', 'e3'] },
  { graph: 'trois-tiers', phrase: 'En cas de panne, RDS secours prend le relais.', ok: ['rdsb'] },
  { graph: 'trois-tiers', phrase: 'Le sous-réseau public B abrite NAT B.', ok: ['pubb', 'natb'] },
  { graph: 'tout', phrase: 'La base est un Aurora.', ok: ['rds'] },
  { graph: 'tout', phrase: 'Le cache de la zone A est Cache A.', ok: ['aza', 'cachea'] },
  { graph: 'tout', phrase: 'L\'ERP dialogue avec l\'ALB par VPN.', ok: ['erp', 'alb', 'e17'] },
  { graph: 'tout', phrase: 'Les journaux de Web A vont dans CloudWatch.', ok: ['ec2a', 'cw', 'e15'] },
  { graph: 'tout', phrase: 'S3 statiques est répliqué vers S3 réplique en Irlande.', ok: ['s3', 's3dr', 'e16'] },
  { graph: 'tout', phrase: 'Les trois zones ont chacune leur NAT.', ok: ['aza', 'azb', 'azc', 'nata', 'nat_b', 'natc'] },
  { graph: 'tout', phrase: 'Le security group protège la base Aurora.', ok: ['sg', 'rds'] },
  { graph: 'tout', phrase: 'Le trafic entre par l\'ALB puis va vers Web C.', ok: ['e1', 'alb', 'ec2c', 'e4'] },
  { graph: 'tout', phrase: 'Le flux sortant de Web A part vers la base, le cache et S3 statiques.',
    ok: ['ec2a', 'e5', 'e8', 'e11', 'e14', 'e15', 's3', 'rds', 'cachea'] },
  { graph: 'tout', phrase: 'Monsieur, le centre de données reste sur site.', ok: ['dc'] },
];

function narrator(graph: ArchitectureGraph): ArchitectureNarrator {
  return new ArchitectureNarrator(layoutArchitecture(graph) as ArchLayout);
}

describe('F-238 / SF-238-12 — la narration synchronisée', () => {
  const byGraph = { 'trois-tiers': narrator(TROIS_TIERS_GRAPH), tout: narrator(TOUT_GRAPH) };

  it('jeu de 30 phrases étiquetées : précision ≥ 95 % (une lueur fausse au plus), ≤ 3 groupes par phrase', () => {
    expect(SET.length).toBe(30);
    let lit = 0;
    let wrong = 0;
    let expected = 0;
    let found = 0;
    for (const item of SET) {
      const r = byGraph[item.graph].read(item.phrase);
      const got = [...r.lit, ...r.flow];
      const bad = got.filter((id) => !item.ok.includes(id));
      expect(bad).withContext(item.phrase).toEqual([]);
      expect(r.groups).withContext(item.phrase).toBeLessThanOrEqual(NARRATION_MAX_GROUPS);
      lit += got.length;
      wrong += bad.length;
      expected += item.ok.length;
      found += got.filter((id) => item.ok.includes(id)).length;
    }
    expect(wrong).toBeLessThanOrEqual(1);
    expect((lit - wrong) / lit).toBeGreaterThanOrEqual(0.95);
    // Rappel indicatif (≤ 3 groupes : une phrase dense n'allume pas tout ce qu'elle cite).
    expect(found / expected).toBeGreaterThan(0.75);
  });

  it('exemple du cadrage §11.4 : ALB + chemin Utilisateurs → ALB + les deux AZ', () => {
    const r = byGraph['trois-tiers'].read('Le trafic entre par l\'ALB, Monsieur, puis se répartit sur les deux zones.');
    expect(r.flow).toEqual(['e1']);
    expect(r.lit).toEqual(jasmine.arrayWithExactContents(['alb', 'aza', 'azb']));
  });

  it('famille ambiguë ⇒ rien ; famille unique dans le focus ⇒ ce service', () => {
    expect(byGraph['trois-tiers'].read('La base est saturée.').lit).toEqual([]); // RDS primaire + secours
    expect(byGraph.tout.read('La base est saturée.').lit).toEqual([]); // Aurora + trois caches
    expect(byGraph.tout.read('Le cache est froid.').lit).toEqual([]);
    expect(byGraph['trois-tiers'].read('La base est saturée.', 'privb').lit).toEqual(['rdsb']);
    expect(byGraph.tout.read('Le cache est froid.', 'privb').lit).toEqual(['cacheb']);
  });

  it('flux : seulement dans le sens des liaisons (B → A n\'allume pas de chemin)', () => {
    expect(byGraph['trois-tiers'].read('Web A vers RDS primaire.').flow).toEqual(['e4']);
    expect(byGraph['trois-tiers'].read('RDS primaire vers Web A.').flow).toEqual([]);
  });

  it('flux sortant / entrant du focus', () => {
    const n = byGraph['trois-tiers'];
    expect(n.read('Voici le flux sortant.', 'ec2a').flow).toEqual(jasmine.arrayWithExactContents(['e4', 'e7', 'e8']));
    expect(n.read('Et le flux entrant.', 'rdsa').flow).toEqual(jasmine.arrayWithExactContents(['e4', 'e5']));
  });

  it('nom porté par deux éléments ⇒ rien (« Auto Scaling group » × 3)', () => {
    expect(byGraph.tout.read('L\'Auto Scaling group ajoute une instance.').lit.some((id) => id.startsWith('asg')))
      .toBeFalse();
  });

  it('au plus 3 groupes, priorité flux > service > zone', () => {
    const r = byGraph['trois-tiers'].read('La zone A contient NAT A, Web A et RDS primaire.');
    expect(r.groups).toBe(3);
    expect(r.flow).toEqual(['e4']);
    expect(r.lit).not.toContain('aza');
  });

  it('texte hostile : lu comme du texte, rien d\'autre ; phrase vide : rien', () => {
    expect(byGraph['trois-tiers'].read('<img src=x onerror=alert(1)> <b>ALB</b>').lit).toEqual(['alb']);
    expect(byGraph['trois-tiers'].read('')).toEqual({ lit: [], flow: [], groups: 0 });
  });

  it('allumage ≤ 100 ms : la lecture d\'une phrase prend moins de 5 ms (60 services compris), sans réseau', () => {
    const fetchSpy = spyOn(window, 'fetch').and.callThrough();
    const xhr = spyOn(XMLHttpRequest.prototype, 'open').and.callThrough();
    const n = byGraph.tout;
    const started = performance.now();
    for (const item of SET) {
      (item.graph === 'tout' ? n : byGraph['trois-tiers']).read(item.phrase);
    }
    expect((performance.now() - started) / SET.length).toBeLessThan(5);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhr).not.toHaveBeenCalled();
  });
});
