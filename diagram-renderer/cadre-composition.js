/**
 * La composition des zones du schéma cadré (F-238 / SF-238-03, cadrage §4.2 étape 1-2, §4.4).
 *
 * ELK seul traite deux Availability Zones sœurs comme deux ÉTAPES du flux : il les empile, ou les pose l'une
 * après l'autre (essai du 2026-11-02, capture `trois-tiers-elk-lr.png`). Les schémas de référence AWS les
 * dessinent CÔTE À CÔTE. On pose donc l'arbre des zones nous-mêmes, AVANT les nœuds :
 *
 * - les AZ sœurs sont PARALLÈLES (colonnes si le flux descend, bandes s'il va à droite), de TAILLE ÉGALE ;
 *   deux AZ de même gabarit (mêmes types, par sous-réseau) reçoivent la MÊME mise en page interne — jamais
 *   de composant ajouté ni déplacé pour la symétrie ;
 * - dans une zone, les sous-réseaux publics passent AVANT les privés, dans le sens du flux ;
 * - les ressources régionales hors VPC (S3, CloudWatch…) forment une BANDE RÉGIONALE le long du VPC ;
 * - les autres unités suivent l'ordre du FLUX (tri topologique des liens entre elles) : les acteurs externes
 *   arrivent donc à l'entrée ;
 * - les nœuds d'une même zone sont placés par ELK `layered` (liens internes seulement), sur la grille de 8 px.
 *
 * Pur et déterministe. Tout est en coordonnées x/y ; seul l'AXE d'empilement dépend du sens du flux.
 */

"use strict";

const ELK = require("elkjs/lib/elk.bundled.js");

const GRID = 8;
const CELL_W = 128;
const CELL_H = 112;
const NODE_GAP = 48;
const RANK_GAP = 64;
/** Écart entre deux unités d'une zone (zones sœurs, bloc de nœuds) : la place d'un faisceau de liaisons. */
const UNIT_GAP = 48;
const PAD = { top: 48, left: 24, right: 24, bottom: 24 };
const SUBNETS = new Set(["subnet_public", "subnet_private"]);

const snap = (v) => Math.round(v / GRID) * GRID;
const snapDown = (v) => Math.floor(v / GRID) * GRID;
const snapUp = (v) => Math.ceil(v / GRID) * GRID;

function textWidth(text, px) {
  return Math.ceil(String(text || "").length * px * 0.56) + 4;
}

/** La cellule d'un nœud : 128 × 112 (icône 64 + libellé sur 2 lignes), plus haute si le libellé l'exige. */
function cellOf(node) {
  const lines = Math.max(1, node.lines.length);
  const labelW = Math.max(0, ...node.lines.map((l) => textWidth(l, 13)));
  // Largeur multiple de 16 : le CENTRE de la cellule (axe de l'icône, des ports) tombe sur la grille (G5).
  return { w: Math.max(CELL_W, Math.ceil((labelW + 16) / 16) * 16), h: Math.max(CELL_H, 80 + 16 * lines) };
}

function emptyBlock() {
  return { w: 0, h: 0, items: [] };
}

function moved(items, dx, dy) {
  return items.map((it) => ({ ...it, x: it.x + dx, y: it.y + dy }));
}

/**
 * Empile des blocs le long d'un axe (`x` ou `y`), avec un écart, centrés (sur la grille) sur l'autre axe —
 * ou alignés au début (`align: "start"`).
 */
function stack(blocks, axis, gap, align) {
  const list = blocks.filter((b) => b && (b.w > 0 || b.h > 0));
  if (!list.length) {
    return emptyBlock();
  }
  const along = axis === "x" ? "w" : "h";
  const across = axis === "x" ? "h" : "w";
  const crossMax = Math.max(...list.map((b) => b[across]));
  let cursor = 0;
  const items = [];
  for (const b of list) {
    const offset = align === "start" ? 0 : snapDown((crossMax - b[across]) / 2);
    items.push(...(axis === "x" ? moved(b.items, cursor, offset) : moved(b.items, offset, cursor)));
    cursor += b[along] + gap;
  }
  const total = cursor - gap;
  return axis === "x" ? { w: total, h: crossMax, items } : { w: crossMax, h: total, items };
}

/** Agrandit une zone (son cadre est le premier élément du bloc) ; le contenu est centré sur l'axe transverse. */
function stretch(block, w, h, crossAxis) {
  if (block.w === w && block.h === h) {
    return block;
  }
  const dx = crossAxis === "x" ? snapDown((w - block.w) / 2) : 0;
  const dy = crossAxis === "y" ? snapDown((h - block.h) / 2) : 0;
  const [frame, ...rest] = block.items;
  return { w, h, items: [{ ...frame, w, h }, ...moved(rest, dx, dy)] };
}

/**
 * Compose le schéma. Rend `{w, h, zones: Map id→box, nodes: Map id→box}` (coordonnées relatives au bloc racine).
 */
async function compose(prepared, direction) {
  const elk = new ELK();
  const main = direction === "TB" ? "y" : "x";
  const cross = main === "y" ? "x" : "y";
  const groups = new Map(prepared.groups.map((g) => [g.id, g]));
  const parentOf = (gid) => {
    const g = groups.get(gid);
    return g && g.parent && groups.has(g.parent) ? g.parent : "";
  };
  const nodeIndex = new Map(prepared.nodes.map((n, i) => [n.id, i]));
  const nodesById = new Map(prepared.nodes.map((n) => [n.id, n]));
  const zoneOfNode = (n) => (n.group && groups.has(n.group) ? n.group : "");
  const childZones = new Map([["", []]]);
  for (const g of prepared.groups) {
    childZones.set(g.id, childZones.get(g.id) || []);
  }
  for (const g of prepared.groups) {
    childZones.get(parentOf(g.id)).push(g.id);
  }
  const directNodes = new Map([["", []]]);
  for (const g of prepared.groups) {
    directNodes.set(g.id, []);
  }
  for (const n of prepared.nodes) {
    directNodes.get(zoneOfNode(n)).push(n);
  }
  /** Le plus petit rang de déclaration des nœuds d'une zone : l'ordre de l'auteur, en dernier recours. */
  const firstIndex = new Map();
  const firstOf = (gid) => {
    if (firstIndex.has(gid)) {
      return firstIndex.get(gid);
    }
    let best = Infinity;
    for (const n of directNodes.get(gid)) {
      best = Math.min(best, nodeIndex.get(n.id));
    }
    for (const c of childZones.get(gid)) {
      best = Math.min(best, firstOf(c));
    }
    firstIndex.set(gid, best);
    return best;
  };

  // --- gabarits : deux AZ de même gabarit reçoivent la même mise en page interne ---------------------------
  const nodeSig = (n) => `${n.type}/${cellOf(n).w}x${cellOf(n).h}`;
  const sigCache = new Map();
  const sigOf = (gid) => {
    if (!sigCache.has(gid)) {
      const nodes = directNodes.get(gid).map(nodeSig).sort();
      const kids = childZones.get(gid).map(sigOf).sort();
      sigCache.set(gid, `${groups.get(gid).kind}[${nodes.join(",")}|${kids.join(",")}]`);
    }
    return sigCache.get(gid);
  };
  /** Les identifiants d'une zone dans un ordre canonique (gabarit, puis déclaration) : la correspondance A ↔ B. */
  const canon = (gid) => {
    const nodes = [...directNodes.get(gid)].sort((a, b) =>
      nodeSig(a).localeCompare(nodeSig(b)) || nodeIndex.get(a.id) - nodeIndex.get(b.id));
    const kids = [...childZones.get(gid)].sort((a, b) =>
      sigOf(a).localeCompare(sigOf(b)) || firstOf(a) - firstOf(b));
    return ["z:" + gid, ...nodes.map((n) => "n:" + n.id), ...kids.flatMap(canon)];
  };
  const cloneAs = (block, fromGid, toGid) => {
    const a = canon(fromGid);
    const b = canon(toGid);
    const map = new Map(a.map((key, i) => [key, b[i]]));
    return {
      w: block.w,
      h: block.h,
      items: block.items.map((it) => {
        const key = map.get((it.kind === "zone" ? "z:" : "n:") + it.id);
        return { ...it, id: key.slice(2) };
      }),
    };
  };

  // --- nœuds d'une zone : ELK layered, liens internes seulement ---------------------------------------------
  async function leafBlock(nodes) {
    if (!nodes.length) {
      return emptyBlock();
    }
    const ids = new Set(nodes.map((n) => n.id));
    const graph = {
      id: "feuille",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": direction === "TB" ? "DOWN" : "RIGHT",
        "elk.spacing.nodeNode": String(NODE_GAP),
        "elk.layered.spacing.nodeNodeBetweenLayers": String(RANK_GAP),
        "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
        "elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
        "elk.padding": "[top=0,left=0,bottom=0,right=0]",
        "elk.separateConnectedComponents": "false",
      },
      children: nodes.map((n) => ({ id: n.id, width: cellOf(n).w, height: cellOf(n).h })),
      edges: prepared.edges
        .map((e, i) => ({ id: "f" + i, sources: [e.from], targets: [e.to], ok: ids.has(e.from) && ids.has(e.to) }))
        .filter((e) => e.ok && e.sources[0] !== e.targets[0])
        .map(({ id, sources, targets }) => ({ id, sources, targets })),
    };
    const out = await elk.layout(graph);
    const items = out.children.map((c) => ({ kind: "node", id: c.id, x: snap(c.x), y: snap(c.y), w: c.width, h: c.height }));
    const minX = Math.min(...items.map((i) => i.x));
    const minY = Math.min(...items.map((i) => i.y));
    const placed = moved(items, -minX, -minY);
    return {
      w: Math.max(...placed.map((i) => i.x + i.w)),
      h: Math.max(...placed.map((i) => i.y + i.h)),
      items: placed,
    };
  }

  /** La bande régionale : les nœuds alignés dans le sens du flux, le long du VPC. */
  function bandBlock(nodes) {
    return stack(nodes.map((n) => {
      const c = cellOf(n);
      return { w: c.w, h: c.h, items: [{ kind: "node", id: n.id, x: 0, y: 0, w: c.w, h: c.h }] };
    }), main, NODE_GAP, "center");
  }

  async function zoneBlock(gid) {
    const g = groups.get(gid);
    const inner = await containerBlock(gid);
    const header = (g.kind === "az" || g.kind === "security_group" || g.kind === "generic" ? 12 : 40)
      + textWidth(g.label, 13) + 16;
    const w = snapUp(Math.max(inner.w + PAD.left + PAD.right, header, 96));
    const h = snapUp(inner.h + PAD.top + PAD.bottom);
    const dx = PAD.left + snapDown((w - PAD.left - PAD.right - inner.w) / 2);
    return { w, h, items: [{ kind: "zone", id: gid, x: 0, y: 0, w, h }, ...moved(inner.items, dx, PAD.top)] };
  }

  /** Le contenu d'une zone (ou de la racine, gid = "") : ses unités, dans l'ordre du flux. */
  async function containerBlock(gid) {
    const kids = childZones.get(gid);
    const direct = directNodes.get(gid);
    const kindOf = (z) => groups.get(z).kind;
    const units = [];
    // Bande régionale : dans une Région qui contient un VPC (ou d'autres zones), ses nœuds propres longent le reste.
    const useBand = gid && kindOf(gid) === "region" && kids.length > 0 && direct.length > 0;
    if (direct.length && !useBand) {
      units.push({ members: new Set([""]), key: Math.min(...direct.map((n) => nodeIndex.get(n.id))),
        block: await leafBlock(direct) });
    }
    const azs = kids.filter((z) => kindOf(z) === "az");
    const subnets = kids.filter((z) => SUBNETS.has(kindOf(z)));
    const others = kids.filter((z) => !(azs.length >= 2 && kindOf(z) === "az")
      && !(subnets.length >= 2 && SUBNETS.has(kindOf(z))));
    if (azs.length >= 2) {
      // AZ sœurs : côte à côte (axe transverse), de taille égale ; même gabarit ⇒ même mise en page.
      const blocks = [];
      const done = [];
      for (const z of azs) {
        const twin = done.find((d) => sigOf(d.gid) === sigOf(z));
        blocks.push(twin ? cloneAs(twin.block, twin.gid, z) : await zoneBlock(z));
        if (!twin) {
          done.push({ gid: z, block: blocks[blocks.length - 1] });
        }
      }
      const w = Math.max(...blocks.map((b) => b.w));
      const h = Math.max(...blocks.map((b) => b.h));
      units.push({ members: new Set(azs), key: Math.min(...azs.map(firstOf)),
        block: stack(blocks.map((b) => stretch(b, w, h, cross)), cross, UNIT_GAP, "start") });
    }
    if (subnets.length >= 2) {
      // Sous-réseaux : publics puis privés, dans le sens du flux, de même largeur.
      const rank = (z) => (kindOf(z) === "subnet_public" ? 0 : 1);
      const ordered = [...subnets].sort((a, b) => rank(a) - rank(b) || firstOf(a) - firstOf(b));
      const blocks = [];
      for (const z of ordered) {
        blocks.push(await zoneBlock(z));
      }
      const size = Math.max(...blocks.map((b) => (cross === "x" ? b.w : b.h)));
      units.push({ members: new Set(subnets), key: Math.min(...subnets.map(firstOf)),
        block: stack(blocks.map((b) => (cross === "x" ? stretch(b, size, b.h, "x") : stretch(b, b.w, size, "y"))),
          main, UNIT_GAP, "start") });
    }
    for (const z of others) {
      units.push({ members: new Set([z]), key: firstOf(z), block: await zoneBlock(z) });
    }
    // Les unités d'un même rang du flux (sans lien entre elles) se rangent CÔTE À CÔTE ; les rangs se suivent.
    const levels = flowLevels(units, gid);
    let block = stack(levels.map((level) => stack(level.map((u) => u.block), cross, UNIT_GAP, "start")),
      main, UNIT_GAP, "center");
    if (useBand) {
      block = stack([block, bandBlock(direct)], cross, UNIT_GAP, "start");
    }
    return block;
  }

  /** L'unité (dans la zone `gid`) qui contient un nœud, ou -1 s'il vit ailleurs. */
  function unitIndex(units, gid, nodeId) {
    let z = zoneOfNode(nodesById.get(nodeId));
    if (z === gid) {
      return units.findIndex((u) => u.members.has(""));
    }
    while (z && parentOf(z) !== gid) {
      z = parentOf(z);
    }
    if (!z || parentOf(z) !== gid) {
      return -1;
    }
    return units.findIndex((u) => u.members.has(z));
  }

  /**
   * Les rangs du flux : tri topologique (à égalité ou en cycle, l'ordre de l'auteur), puis rang = plus long
   * chemin depuis l'entrée. Un lien qui remonte l'ordre (cycle) est ignoré pour le rang.
   */
  function flowLevels(units, gid) {
    if (units.length < 2) {
      return units.length ? [units] : [];
    }
    const order = flowOrder(units, gid);
    const pos = new Map(order.map((u, i) => [u, i]));
    const level = new Map(order.map((u) => [u, 0]));
    // Relaxation dans l'ordre topologique : les liens « vers l'avant » seulement.
    for (const u of order) {
      for (const e of prepared.edges) {
        const a = unitIndex(units, gid, e.from);
        const b = unitIndex(units, gid, e.to);
        if (a < 0 || b < 0 || units[a] !== u || a === b) {
          continue;
        }
        const target = units[b];
        if (pos.get(target) > pos.get(u)) {
          level.set(target, Math.max(level.get(target), level.get(u) + 1));
        }
      }
    }
    const rows = [];
    for (const u of order) {
      const l = level.get(u);
      (rows[l] = rows[l] || []).push(u);
    }
    return rows.filter(Boolean);
  }

  /** Tri topologique des unités selon les liens qui les relient ; à égalité (ou en cycle), l'ordre de l'auteur. */
  function flowOrder(units, gid) {
    if (units.length < 2) {
      return units;
    }
    const succ = units.map(() => new Set());
    const indeg = units.map(() => 0);
    for (const e of prepared.edges) {
      const u = unitIndex(units, gid, e.from);
      const v = unitIndex(units, gid, e.to);
      if (u >= 0 && v >= 0 && u !== v && !succ[u].has(v)) {
        succ[u].add(v);
        indeg[v] += 1;
      }
    }
    const left = new Set(units.map((_, i) => i));
    const out = [];
    while (left.size) {
      const ready = [...left].filter((i) => indeg[i] === 0);
      const pool = ready.length ? ready : [...left];
      pool.sort((a, b) => units[a].key - units[b].key);
      const pick = pool[0];
      left.delete(pick);
      out.push(units[pick]);
      for (const v of succ[pick]) {
        indeg[v] -= 1;
      }
    }
    return out;
  }

  const root = await containerBlock("");
  const zones = new Map();
  const nodes = new Map();
  for (const it of root.items) {
    (it.kind === "zone" ? zones : nodes).set(it.id, { x: it.x, y: it.y, w: it.w, h: it.h });
  }
  return { w: root.w, h: root.h, zones, nodes };
}

module.exports = { compose, cellOf, GRID };
