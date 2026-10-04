/** Fabriques de test du plan (F-173). */
import { MapGraph, MapNode } from '../../core/models/governance.models';

export function mapNode(id: string, patch: Partial<MapNode> = {}): MapNode {
  return {
    id,
    label: id,
    kind: 'cluster',
    parentId: null,
    depth: 0,
    children: 0,
    domain: null,
    environment: null,
    state: null,
    identifiers: [],
    facts: 0,
    traps: 0,
    observedOn: null,
    stale: false,
    nextDue: null,
    toMap: false,
    ...patch,
  };
}

export function mapGraph(nodes: MapNode[], patch: Partial<MapGraph> = {}): MapGraph {
  return {
    indexed: true,
    indexedAt: '2026-10-04T08:00:00Z',
    pendingSections: 0,
    factMaxAgeDays: 120,
    totalNodes: nodes.length,
    truncated: false,
    nodes,
    edges: [],
    deadlines: [],
    toMap: [],
    ...patch,
  };
}

