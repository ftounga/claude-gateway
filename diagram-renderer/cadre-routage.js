/**
 * Le routeur orthogonal du schéma cadré (F-238 / SF-238-03, SF-238-04 ; cadrage §4.2 étape 3).
 *
 * Une fois zones et nœuds posés, chaque liaison est cherchée par A* sur une GRILLE DE CANAUX de 8 px :
 * - déplacements horizontaux ou verticaux seulement (G1 par construction), pénalité de COUDE ;
 * - obstacles : icônes et libellés des nœuds (à 8 px près), bandeaux des zones ; « aucune liaison sous une
 *   icône » ;
 * - une bordure de zone ne se traverse que PERPENDICULAIREMENT (jamais le long, ni par un coin) ; longer une
 *   bordure à 8 px coûte cher ; sortir d'une zone qu'on n'a pas à quitter, ou entrer dans une zone étrangère,
 *   coûte cher ;
 * - les liaisons déjà tracées sont des canaux occupés : les SUPERPOSER coûte très cher (deux liaisons parallèles
 *   prennent deux canaux voisins, à pas constant de 8 px : le faisceau), les CROISER un peu ;
 * - extrémités : des PORTS sur le bord de l'icône (haut, gauche, droite) ou sous le libellé (bas), à 8 px de
 *   pas ; le côté du flux est préféré, un port déjà pris l'est moins (les liaisons s'écartent) — G2.
 *
 * Déterministe : ordre des liaisons fixé, file de priorité départagée par un compteur.
 */

"use strict";

const GRID = 8;
const ICON = 64;
const BEND = 6;
const NEAR_BORDER = 10;
const OVERLAP = 60;
const CROSSING = 3;
const FOREIGN_ZONE = 40;
const PORT_USED = 12;
/**
 * A* pondéré (SF-238-04) : l'heuristique de Manhattan est multipliée. Le chemin reste orthogonal et évite les
 * obstacles ; il peut être un peu plus long que l'optimum. Presque exact sur un schéma courant ; plus glouton
 * au-delà de DENSE_EDGES liaisons, pour tenir 2 s aux bornes de F-142 (60 nœuds, 120 liens).
 */
const HEURISTIC_WEIGHT = 1.2;
const DENSE_WEIGHT = 4;
const DENSE_EDGES = 40;
/** Fenêtre de recherche autour des extrémités, en cases (40 × 8 px = 320 px) ; toute la grille si elle échoue. */
const WINDOW = 40;
const DIRS = [[1, 0], [0, 1], [-1, 0], [0, -1]]; // droite, bas, gauche, haut

function textWidth(text, px) {
  return Math.ceil(String(text || "").length * px * 0.56) + 4;
}

/** La forme d'un nœud dans sa cellule : icône centrée en haut, libellé dessous. */
function shapeOfCell(node) {
  const cx = node.box.x + node.box.w / 2;
  const top = node.box.y;
  const lines = Math.max(1, node.lines.length);
  const lw = Math.max(0, ...node.lines.map((l) => textWidth(l, 13)));
  return {
    cx,
    top,
    icon: { x: cx - ICON / 2, y: top, w: ICON, h: ICON },
    label: { x: cx - lw / 2, y: top + ICON + 8, w: lw, h: lines * 16 },
    bottom: top + ICON + 8 + lines * 16,
  };
}

/** Petite file de priorité binaire (f, puis ordre d'insertion : déterministe). */
class Heap {
  constructor() {
    this.f = [];
    this.s = [];
    this.n = [];
    this.seq = 0;
  }

  get size() {
    return this.s.length;
  }

  less(i, j) {
    return this.f[i] < this.f[j] || (this.f[i] === this.f[j] && this.n[i] < this.n[j]);
  }

  swap(i, j) {
    [this.f[i], this.f[j]] = [this.f[j], this.f[i]];
    [this.s[i], this.s[j]] = [this.s[j], this.s[i]];
    [this.n[i], this.n[j]] = [this.n[j], this.n[i]];
  }

  push(f, s) {
    this.f.push(f);
    this.s.push(s);
    this.n.push(this.seq++);
    let i = this.s.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (!this.less(i, p)) {
        break;
      }
      this.swap(i, p);
      i = p;
    }
  }

  topF() {
    return this.f[0];
  }

  pop() {
    const s = this.s[0];
    const last = this.s.length - 1;
    this.swap(0, last);
    this.f.pop();
    this.s.pop();
    this.n.pop();
    let i = 0;
    for (;;) {
      const l = 2 * i + 1;
      const r = l + 1;
      let m = i;
      if (l < this.s.length && this.less(l, m)) {
        m = l;
      }
      if (r < this.s.length && this.less(r, m)) {
        m = r;
      }
      if (m === i) {
        break;
      }
      this.swap(i, m);
      i = m;
    }
    return s;
  }
}

function popcount(x) {
  let v = x >>> 0;
  let c = 0;
  while (v) {
    v &= v - 1;
    c += 1;
  }
  return c;
}

/**
 * Route toutes les liaisons. `model` : {bounds, zones[{id, box, parent, header}], nodes[{id, box, lines}],
 * edges[{from, to}], direction}. Rend, pour chaque liaison (même ordre), sa polyligne en pixels, et les notes.
 */
function routeAll(model) {
  const cols = Math.floor(model.bounds.w / GRID) + 1;
  const rows = Math.floor(model.bounds.h / GRID) + 1;
  const N = cols * rows;
  const at = (gx, gy) => gy * cols + gx;
  const blocked = new Uint8Array(N);
  const borderH = new Uint8Array(N);
  const borderV = new Uint8Array(N);
  const nearH = new Uint8Array(N);
  const nearV = new Uint8Array(N);
  const usedH = new Uint8Array(N);
  const usedV = new Uint8Array(N);
  const mask = new Uint32Array(N);

  const forRect = (r, fn, strict) => {
    const x0 = Math.max(0, Math.ceil(r.x / GRID));
    const x1 = Math.min(cols - 1, Math.floor((r.x + r.w) / GRID));
    const y0 = Math.max(0, Math.ceil(r.y / GRID));
    const y1 = Math.min(rows - 1, Math.floor((r.y + r.h) / GRID));
    for (let gy = y0; gy <= y1; gy += 1) {
      for (let gx = x0; gx <= x1; gx += 1) {
        const x = gx * GRID;
        const y = gy * GRID;
        if (strict && (x <= r.x || x >= r.x + r.w || y <= r.y || y >= r.y + r.h)) {
          continue;
        }
        fn(at(gx, gy));
      }
    }
  };
  const inflate = (r, d) => ({ x: r.x - d, y: r.y - d, w: r.w + 2 * d, h: r.h + 2 * d });

  // Obstacles : icônes et libellés (à 8 px près, intérieur strict), bandeaux de zone (bord compris).
  const shapes = new Map();
  for (const n of model.nodes) {
    const s = shapeOfCell(n);
    shapes.set(n.id, s);
    forRect(inflate(s.icon, GRID), (p) => { blocked[p] = 1; }, true);
    forRect(inflate(s.label, GRID), (p) => { blocked[p] = 1; }, true);
  }
  const zoneBit = new Map();
  model.zones.forEach((z, k) => {
    if (k < 32) {
      zoneBit.set(z.id, (1 << k) >>> 0);
    }
    const b = z.box;
    if (z.header) {
      forRect(z.header, (p) => { blocked[p] = 1; }, false);
    }
    forRect({ x: b.x, y: b.y, w: b.w, h: 0 }, (p) => { borderH[p] = 1; }, false);
    forRect({ x: b.x, y: b.y + b.h, w: b.w, h: 0 }, (p) => { borderH[p] = 1; }, false);
    forRect({ x: b.x, y: b.y, w: 0, h: b.h }, (p) => { borderV[p] = 1; }, false);
    forRect({ x: b.x + b.w, y: b.y, w: 0, h: b.h }, (p) => { borderV[p] = 1; }, false);
    for (const dy of [-GRID, GRID]) {
      forRect({ x: b.x, y: b.y + dy, w: b.w, h: 0 }, (p) => { nearH[p] = 1; }, false);
      forRect({ x: b.x, y: b.y + b.h + dy, w: b.w, h: 0 }, (p) => { nearH[p] = 1; }, false);
    }
    for (const dx of [-GRID, GRID]) {
      forRect({ x: b.x + dx, y: b.y, w: 0, h: b.h }, (p) => { nearV[p] = 1; }, false);
      forRect({ x: b.x + b.w + dx, y: b.y, w: 0, h: b.h }, (p) => { nearV[p] = 1; }, false);
    }
    if (k < 32) {
      const bit = zoneBit.get(z.id);
      forRect(b, (p) => { mask[p] |= bit; }, false);
    }
  });

  const zones = new Map(model.zones.map((z) => [z.id, z]));
  const ancestorsMask = (zoneId) => {
    let m = 0;
    let z = zoneId;
    while (z && zones.has(z)) {
      m |= zoneBit.get(z) || 0;
      z = zones.get(z).parent;
    }
    return m >>> 0;
  };

  // Ports : côté du flux préféré ; pas de 8 px.
  const tb = model.direction === "TB";
  const sideCost = (side, role) => {
    const forward = tb ? (role === "src" ? "S" : "N") : (role === "src" ? "E" : "W");
    const backward = tb ? (role === "src" ? "N" : "S") : (role === "src" ? "W" : "E");
    return side === forward ? 0 : side === backward ? 10 : 3;
  };
  const portsUsed = new Map();
  const portsOf = (nodeId, role) => {
    const s = shapes.get(nodeId);
    const out = [];
    const add = (side, x, y, dir, k) => {
      const gx = x / GRID;
      const gy = y / GRID;
      const sx = gx + DIRS[dir][0];
      const sy = gy + DIRS[dir][1];
      if (!Number.isInteger(gx) || !Number.isInteger(gy) || sx < 0 || sy < 0 || sx >= cols || sy >= rows) {
        return;
      }
      const stub = at(sx, sy);
      if (blocked[stub]) {
        return;
      }
      const key = `${nodeId}:${x},${y}`;
      out.push({ x, y, dir, stub, key,
        cost: sideCost(side, role) + Math.abs(k) * 0.5 + (portsUsed.has(key) ? PORT_USED : 0) });
    };
    for (const k of [0, -1, 1, -2, 2]) {
      add("N", s.cx + k * GRID, s.top, 3, k);
      if (Math.abs(k * GRID) <= s.label.w / 2) {
        // Sous le libellé, et seulement sous lui : une extrémité ne flotte jamais à côté du texte (G2).
        add("S", s.cx + k * GRID, s.bottom, 1, k);
      }
      add("E", s.cx + ICON / 2, s.top + ICON / 2 + k * GRID, 0, k);
      add("W", s.cx - ICON / 2, s.top + ICON / 2 + k * GRID, 2, k);
    }
    return out;
  };

  // États de la recherche, remis à zéro par GÉNÉRATION (pas de remplissage de 4N cases par liaison).
  const g = new Float64Array(4 * N);
  const prev = new Int32Array(4 * N);
  const seenAt = new Int32Array(4 * N);
  const closedAt = new Int32Array(4 * N);
  let generation = 0;
  const notices = [];
  const nodeById = new Map(model.nodes.map((n) => [n.id, n]));
  const weight = model.edges.length > DENSE_EDGES ? DENSE_WEIGHT : HEURISTIC_WEIGHT;

  /** Une liaison : d'abord dans une FENÊTRE autour de ses extrémités, puis, faute de chemin, sur toute la grille. */
  function search(edge) {
    return searchIn(edge, WINDOW) || searchIn(edge, Infinity);
  }

  function searchIn(edge, margin) {
    const from = nodeById.get(edge.from);
    const to = nodeById.get(edge.to);
    const sources = portsOf(edge.from, "src");
    let targets = portsOf(edge.to, "dst");
    const penal = (~(ancestorsMask(from.group) ^ ancestorsMask(to.group))) >>> 0;
    if (edge.from === edge.to) {
      targets = targets.filter((t) => t.dir !== sources[0]?.dir);
    }
    if (!sources.length || !targets.length) {
      return null;
    }
    const goalAt = new Map();
    for (const t of targets) {
      if (!goalAt.has(t.stub)) {
        goalAt.set(t.stub, []);
      }
      goalAt.get(t.stub).push(t);
    }
    const tx = targets.map((t) => t.stub % cols);
    const ty = targets.map((t) => Math.floor(t.stub / cols));
    // Heuristique : distance de Manhattan au rectangle englobant des ports d'arrivée (admissible, et bon marché).
    const tx0 = Math.min(...tx);
    const tx1 = Math.max(...tx);
    const ty0 = Math.min(...ty);
    const ty1 = Math.max(...ty);
    const h = (p) => {
      const x = p % cols;
      const y = (p - x) / cols;
      return Math.max(tx0 - x, 0, x - tx1) + Math.max(ty0 - y, 0, y - ty1);
    };
    const all = [...sources.map((s) => s.stub), ...targets.map((t) => t.stub)];
    const xs = all.map((p) => p % cols);
    const ys = all.map((p) => Math.floor(p / cols));
    const wx0 = Math.max(0, Math.min(...xs) - margin);
    const wx1 = Math.min(cols - 1, Math.max(...xs) + margin);
    const wy0 = Math.max(0, Math.min(...ys) - margin);
    const wy1 = Math.min(rows - 1, Math.max(...ys) + margin);
    generation += 1;
    const gen = generation;
    const gOf = (s) => (seenAt[s] === gen ? g[s] : Infinity);
    const heap = new Heap();
    const startOf = new Map();
    for (const s of sources) {
      const st = s.stub * 4 + s.dir;
      const cost = s.cost + 1;
      if (cost < gOf(st)) {
        g[st] = cost;
        seenAt[st] = gen;
        prev[st] = -1;
        startOf.set(st, s);
        heap.push(cost + weight * h(s.stub), st);
      }
    }
    let best = Infinity;
    let bestState = -1;
    let bestTarget = null;
    while (heap.size) {
      if (heap.topF() >= best) {
        break;
      }
      const st = heap.pop();
      if (closedAt[st] === gen) {
        continue;
      }
      closedAt[st] = gen;
      const p = st >> 2;
      const d = st & 3;
      const gs = g[st];
      const goals = goalAt.get(p);
      if (goals) {
        for (const t of goals) {
          const inward = (t.dir + 2) % 4;
          const total = gs + (d === inward ? 0 : BEND) + 1 + t.cost;
          if (d !== t.dir && total < best && !(startOf.get(st) && startOf.get(st).key === t.key)) {
            best = total;
            bestState = st;
            bestTarget = t;
          }
        }
      }
      const x = p % cols;
      const y = (p - x) / cols;
      for (let nd = 0; nd < 4; nd += 1) {
        if (nd === ((d + 2) & 3)) {
          continue;
        }
        const nx = x + DIRS[nd][0];
        const ny = y + DIRS[nd][1];
        if (nx < wx0 || ny < wy0 || nx > wx1 || ny > wy1) {
          continue;
        }
        const q = ny * cols + nx;
        if (blocked[q]) {
          continue;
        }
        const horizontal = nd === 0 || nd === 2;
        if (horizontal ? (borderH[p] && borderH[q]) : (borderV[p] && borderV[q])) {
          continue;
        }
        let cost = 1 + (nd === d ? 0 : BEND);
        if (horizontal) {
          cost += (nearH[q] ? NEAR_BORDER : 0) + (usedH[q] ? OVERLAP : 0) + (usedV[q] ? CROSSING : 0);
        } else {
          cost += (nearV[q] ? NEAR_BORDER : 0) + (usedV[q] ? OVERLAP : 0) + (usedH[q] ? CROSSING : 0);
        }
        const crossed = (mask[p] ^ mask[q]) & penal;
        if (crossed) {
          cost += FOREIGN_ZONE * popcount(crossed);
        }
        const ns = q * 4 + nd;
        const ng = gs + cost;
        if (ng < gOf(ns)) {
          g[ns] = ng;
          seenAt[ns] = gen;
          prev[ns] = st;
          heap.push(ng + weight * h(q), ns);
        }
      }
    }
    if (bestState < 0) {
      return null;
    }
    const cells = [];
    let st = bestState;
    while (st >= 0) {
      cells.push(st >> 2);
      if (startOf.has(st) && prev[st] < 0) {
        break;
      }
      st = prev[st];
    }
    cells.reverse();
    const start = startOf.get(st);
    return { cells, start, end: bestTarget };
  }

  const order = model.edges.map((e, i) => {
    const a = nodeById.get(e.from).box;
    const b = nodeById.get(e.to).box;
    return { i, d: Math.abs(a.x - b.x) + Math.abs(a.y - b.y) };
  }).sort((u, v) => u.d - v.d || u.i - v.i);

  const result = new Array(model.edges.length);
  for (const { i } of order) {
    const edge = model.edges[i];
    const found = search(edge);
    if (!found) {
      // Jamais d'oblique, même en échec : un coude droit direct, et on le DIT.
      const a = shapes.get(edge.from);
      const b = shapes.get(edge.to);
      result[i] = simplify([{ x: a.cx, y: a.bottom }, { x: a.cx, y: b.top }, { x: b.cx, y: b.top }]);
      notices.push(`liaison ${edge.from} -> ${edge.to} tracée sans contournement`);
      continue;
    }
    const pts = [{ x: found.start.x, y: found.start.y }];
    for (const c of found.cells) {
      pts.push({ x: (c % cols) * GRID, y: Math.floor(c / cols) * GRID });
    }
    pts.push({ x: found.end.x, y: found.end.y });
    for (let k = 1; k < found.cells.length; k += 1) {
      const a = found.cells[k - 1];
      const b = found.cells[k];
      const arr = Math.abs(a - b) === 1 ? usedH : usedV;
      arr[a] = 1;
      arr[b] = 1;
    }
    portsUsed.set(found.start.key, true);
    portsUsed.set(found.end.key, true);
    result[i] = simplify(pts);
  }
  return { points: result, notices, shapes };
}

/** Retire les points intermédiaires alignés : une polyligne n'a que ses coudes. */
function simplify(points) {
  const out = [];
  for (const p of points) {
    const last = out[out.length - 1];
    if (last && last.x === p.x && last.y === p.y) {
      continue;
    }
    out.push(p);
    while (out.length >= 3) {
      const [a, b, c] = out.slice(-3);
      if (!((a.x === b.x && b.x === c.x) || (a.y === b.y && b.y === c.y))) {
        break;
      }
      out.splice(out.length - 2, 1);
    }
  }
  return out;
}

module.exports = { routeAll, shapeOfCell, simplify };
