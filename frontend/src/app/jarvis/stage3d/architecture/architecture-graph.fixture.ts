import { ArchitectureEdge, ArchitectureGraph, ArchitectureNode, ArchitectureZone } from './architecture-graph';
import TROIS_TIERS_JSON from './fixtures/trois-tiers.graph.json';
import TOUT_JSON from './fixtures/tout.graph.json';

/**
 * F-238 / SF-238-09 — **les graphes du banc et des tests** : sortis tels quels de `diagram-renderer/cadre.js`
 * (`build()` sur `tests/fixtures-cadre/trois-tiers.json` et `tout.json`, SF-238-05) — pas une imitation : les
 * coordonnées sont celles du SVG cadré. Aucune lecture réseau.
 */
export const TROIS_TIERS_GRAPH = TROIS_TIERS_JSON as unknown as ArchitectureGraph;
export const TOUT_GRAPH = TOUT_JSON as unknown as ArchitectureGraph;

/**
 * Un graphe de 60 services (la borne F-142) pour le budget d'appels de dessin et les i/s : une région, un VPC, trois
 * AZ de deux sous-réseaux chacune, des services sur une grille de 8 px, des liaisons orthogonales (en L).
 */
export function sixtyNodesGraph(): ArchitectureGraph {
  const zones: ArchitectureZone[] = [
    { id: 'cloud', kind: 'cloud', label: 'AWS Cloud', parent: '', depth: 0, box: { x: 40, y: 40, w: 2320, h: 1520 },
      inferred: false },
    { id: 'region', kind: 'region', label: 'Région eu-west-3', parent: 'cloud', depth: 1,
      box: { x: 64, y: 88, w: 2272, h: 1448 }, inferred: false },
    { id: 'vpc', kind: 'vpc', label: 'VPC 10.0.0.0/16', parent: 'region', depth: 2,
      box: { x: 88, y: 136, w: 2224, h: 1376 }, inferred: false },
  ];
  const nodes: ArchitectureNode[] = [];
  const edges: ArchitectureEdge[] = [];
  for (let az = 0; az < 3; az++) {
    const x = 112 + az * 736;
    zones.push({ id: `az${az}`, kind: 'az', label: `Availability Zone ${'ABC'[az]}`, parent: 'vpc', depth: 3,
      box: { x, y: 184, w: 712, h: 1304 }, inferred: false });
    for (let sub = 0; sub < 2; sub++) {
      const y = 232 + sub * 632;
      const id = `s${az}${sub}`;
      zones.push({ id, kind: sub === 0 ? 'subnet_public' : 'subnet_private', label: `Sous-réseau ${sub === 0
        ? 'public' : 'privé'} ${'ABC'[az]}`, parent: `az${az}`, depth: 4, box: { x: x + 24, y, w: 664, h: 600 },
      inferred: false });
      for (let k = 0; k < 10; k++) {
        const nx = x + 48 + (k % 5) * 128;
        const ny = y + 48 + Math.floor(k / 5) * 272;
        const nid = `n${az}${sub}${k}`;
        nodes.push({ id: nid, type: k % 2 ? 'aws.ec2' : 'aws.lambda', resolved: k % 2 ? 'aws/compute/ec2'
          : 'aws/compute/lambda', label: `Service ${nodes.length + 1}`, zone: id, family: 'compute',
        unknown: false, box: { x: nx, y: ny, w: 128, h: 112 } });
      }
    }
  }
  for (let i = 0; i + 1 < nodes.length && edges.length < 120; i++) {
    const a = nodes[i].box;
    const b = nodes[i + 1].box;
    const ax = a.x + 64;
    const ay = a.y + 32;
    const bx = b.x + 64;
    const by = b.y + 32;
    edges.push({ id: `e${i}`, from: nodes[i].id, to: nodes[i + 1].id, label: '', bidirectional: i % 7 === 0,
      points: ay === by ? [{ x: ax, y: ay }, { x: bx, y: by }] : [{ x: ax, y: ay }, { x: ax, y: by }, { x: bx, y: by }],
      labelBox: null });
  }
  return { version: 1, title: 'Soixante services', direction: 'TB', bounds: { w: 2400, h: 1600 }, zones, nodes,
    edges, notices: [] };
}
