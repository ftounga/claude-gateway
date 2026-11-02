import {
  AdditiveBlending, BufferAttribute, BufferGeometry, Camera, EdgesGeometry, Group, LineBasicMaterial, LineLoop,
  LineSegments, Mesh, MeshBasicMaterial, PlaneGeometry, Points, Scene, ShaderMaterial, Vector3,
} from 'three';

import { HOLO_COLORS, MOTION_DURATIONS, ease, rgb } from '../jarvis-motion';
import { CORE_KEY } from '../jarvis-link-graph';
import { CloudPoint, Effect3d, SceneTone } from '../scenes3d/jarvis-scene3d.model';

/**
 * F-236 / SF-236-08 → 09 — **les effets des scènes 3D** (cadrage §9.2) : chaque effet déclaratif du plan a sa
 * recette. Objets <b>préalloués</b> (pools fixes, T9 : impulsions PC 1 024 / téléphone 128), seules leurs propriétés
 * et le contenu de leurs tampons changent à chaque image : zéro allocation.
 *
 * <ul>
 *   <li>S-GEN : un <b>anneau d'impulsion</b> part de son ancre à chaque appel (900 ms, `sortie`), face à la caméra ;</li>
 *   <li>N6 : un <b>flux</b> de particules d'un nœud vers un autre (débit ∝ `rate`) ;</li>
 *   <li>S4 / S5 : des <b>cartes</b> holographiques en éventail (90 ms d'écart, `rebond`) ou en pile ;</li>
 *   <li>S5 : la <b>loupe</b> qui balaie ; N9 : le <b>nuage du dépôt</b> (un amas par dossier).</li>
 * </ul>
 * Toute famille apparaît et s'efface en fondu (aucun saut), sur l'horloge du moteur (banc reproductible).
 */

/** Où est un nœud (coordonnées du monde) ; `false` s'il n'est pas posé. */
export type Locator = (key: string, out: Vector3) => boolean;

/** Anneaux d'impulsion simultanés au plus. */
const RING_POOL = 6;
const RING_SEGMENTS = 64;
/** Cartes (tickets, feuillets) au plus. */
const CARD_POOL = 6;
/** Particules : PC 1 024, téléphone 128 (T9). */
export const PARTICLE_POOL = { wide: 1024, narrow: 128 } as const;
/** Flux : particules au plus (débit plein). */
const FLOW_MAX = { wide: 64, narrow: 20 } as const;
/** Un aller du flux (ms). */
const FLOW_PERIOD = 1400;
const FADE_IN = 300;
const FADE_OUT = 500;

const PARTICLE_VERTEX = /* glsl */ `
attribute float aSize;
attribute vec3 aColor;
uniform float uPixelRatio;
varying vec3 vColor;
void main() {
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = min(aSize * uPixelRatio * (8.0 / max(-mv.z, 0.5)), 40.0 * uPixelRatio);
  vColor = aColor;
}`;

const PARTICLE_FRAGMENT = /* glsl */ `
varying vec3 vColor;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.0, 0.5, d);
  gl_FragColor = vec4(vColor * a * a, 1.0);
}`;

interface Ring {
  line: LineLoop;
  material: LineBasicMaterial;
  start: number;
  anchor: string;
}

interface Card {
  plane: Mesh;
  fill: MeshBasicMaterial;
  edge: LineSegments;
  line: LineBasicMaterial;
}

/** Une famille d'effet : sa dernière consigne, et le fondu d'entrée / de sortie. */
interface Presence<T> {
  spec: T | null;
  on: boolean;
  since: number;
}

type FlowSpec = Extract<Effect3d, { kind: 'flow' }>;
type CardsSpec = Extract<Effect3d, { kind: 'cards' }>;
type ScanSpec = Extract<Effect3d, { kind: 'scan' }>;
type CloudSpec = Extract<Effect3d, { kind: 'cloud' }>;

export class Effects3d {
  readonly group = new Group();
  private readonly rings: Ring[] = [];
  private pulseSeq = -1;
  private readonly at = new Vector3();
  private readonly to = new Vector3();
  private readonly right = new Vector3();
  private readonly up = new Vector3();
  private readonly palette = new Map<SceneTone | 'core', [number, number, number]>();
  private readonly cards: Card[] = [];
  private readonly scanRing: LineLoop;
  private readonly scanMaterial: LineBasicMaterial;
  private readonly particles: Points;
  private readonly particleMaterial: ShaderMaterial;
  private readonly pos: Float32Array;
  private readonly col: Float32Array;
  private readonly size: Float32Array;
  private readonly poolSize: number;
  private readonly flowMax: number;
  private readonly flow: Presence<FlowSpec> = { spec: null, on: false, since: -Infinity };
  private readonly card: Presence<CardsSpec> = { spec: null, on: false, since: -Infinity };
  private readonly scan: Presence<ScanSpec> = { spec: null, on: false, since: -Infinity };
  private readonly cloud: Presence<CloudSpec> = { spec: null, on: false, since: -Infinity };

  constructor(scene: Scene, narrow = false, pixelRatio = 1) {
    scene.add(this.group);
    for (const tone of ['normal', 'wait', 'deep', 'green', 'alert', 'off', 'core'] as const) {
      this.palette.set(tone, rgb(HOLO_COLORS[tone]));
    }
    const ring = new BufferGeometry();
    const points = new Float32Array(RING_SEGMENTS * 3);
    for (let i = 0; i < RING_SEGMENTS; i++) {
      const a = (i / RING_SEGMENTS) * Math.PI * 2;
      points[i * 3] = Math.cos(a);
      points[i * 3 + 1] = Math.sin(a);
    }
    ring.setAttribute('position', new BufferAttribute(points, 3));
    for (let i = 0; i < RING_POOL; i++) {
      const material = this.lineMaterial();
      const line = new LineLoop(ring, material);
      line.visible = false;
      line.frustumCulled = false;
      this.group.add(line);
      this.rings.push({ line, material, start: -Infinity, anchor: CORE_KEY });
    }
    this.scanMaterial = this.lineMaterial();
    this.scanRing = new LineLoop(ring, this.scanMaterial);
    this.scanRing.visible = false;
    this.scanRing.frustumCulled = false;
    this.group.add(this.scanRing);

    const plane = new PlaneGeometry(0.36, 0.24);
    const edges = new EdgesGeometry(plane);
    for (let i = 0; i < CARD_POOL; i++) {
      const fill = new MeshBasicMaterial({ transparent: true, opacity: 0, blending: AdditiveBlending, depthWrite: false });
      const mesh = new Mesh(plane, fill);
      const line = this.lineMaterial();
      const edge = new LineSegments(edges, line);
      mesh.add(edge);
      mesh.visible = false;
      mesh.frustumCulled = false;
      this.group.add(mesh);
      this.cards.push({ plane: mesh, fill, edge, line });
    }

    this.poolSize = narrow ? PARTICLE_POOL.narrow : PARTICLE_POOL.wide;
    this.flowMax = narrow ? FLOW_MAX.narrow : FLOW_MAX.wide;
    this.pos = new Float32Array(this.poolSize * 3);
    this.col = new Float32Array(this.poolSize * 3);
    this.size = new Float32Array(this.poolSize);
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(this.pos, 3));
    geometry.setAttribute('aColor', new BufferAttribute(this.col, 3));
    geometry.setAttribute('aSize', new BufferAttribute(this.size, 1));
    geometry.setDrawRange(0, 0);
    this.particleMaterial = new ShaderMaterial({
      vertexShader: PARTICLE_VERTEX, fragmentShader: PARTICLE_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending, uniforms: { uPixelRatio: { value: pixelRatio } },
    });
    this.particles = new Points(geometry, this.particleMaterial);
    this.particles.frustumCulled = false;
    this.group.add(this.particles);
  }

  /** Les effets du plan ; `opening` (banc) : tout part de t = 0. */
  set(effects: readonly Effect3d[], now: number, opening: boolean): void {
    const at = opening ? 0 : now;
    const seen = { flow: null as FlowSpec | null, cards: null as CardsSpec | null, scan: null as ScanSpec | null,
      cloud: null as CloudSpec | null };
    for (const effect of effects) {
      switch (effect.kind) {
        case 'pulse':
          if (opening || effect.seq > this.pulseSeq) {
            this.fireRing(effect.anchor, effect.tone, at);
          }
          this.pulseSeq = effect.seq;
          break;
        case 'flow':
          seen.flow = effect;
          break;
        case 'cards':
          seen.cards = effect;
          break;
        case 'scan':
          seen.scan = effect;
          break;
        case 'cloud':
          seen.cloud = effect;
          break;
      }
    }
    present(this.flow, seen.flow, at, (a, b) => a.from === b.from && a.to === b.to);
    present(this.card, seen.cards, at, (a, b) => a.anchor === b.anchor && a.layout === b.layout);
    present(this.scan, seen.scan, at, (a, b) => a.anchor === b.anchor);
    present(this.cloud, seen.cloud, at, (a, b) => a.anchor === b.anchor);
  }

  /** Une image. `camera` : anneaux, cartes et loupe lui font face. */
  frame(now: number, camera: Camera, locate: Locator): void {
    const m = camera.matrixWorld.elements;
    this.right.set(m[0], m[1], m[2]).normalize();
    this.up.set(m[4], m[5], m[6]).normalize();
    this.frameRings(now, camera, locate);
    this.frameCards(now, camera, locate);
    this.frameScan(now, camera, locate);
    let used = this.frameFlow(now, locate, 0);
    used = this.frameCloud(now, locate, used);
    const geometry = this.particles.geometry;
    geometry.setDrawRange(0, used);
    if (used > 0) {
      (geometry.getAttribute('position') as BufferAttribute).needsUpdate = true;
      (geometry.getAttribute('aColor') as BufferAttribute).needsUpdate = true;
      (geometry.getAttribute('aSize') as BufferAttribute).needsUpdate = true;
    }
  }

  dispose(): void {
    for (const ring of this.rings) {
      ring.material.dispose();
    }
    this.rings[0]?.line.geometry.dispose();
    this.scanMaterial.dispose();
    for (const card of this.cards) {
      card.fill.dispose();
      card.line.dispose();
    }
    this.cards[0]?.plane.geometry.dispose();
    this.cards[0]?.edge.geometry.dispose();
    this.particles.geometry.dispose();
    this.particleMaterial.dispose();
    this.group.removeFromParent();
  }

  private lineMaterial(): LineBasicMaterial {
    return new LineBasicMaterial({ transparent: true, opacity: 0, blending: AdditiveBlending, depthWrite: false });
  }

  private anchor(key: string, locate: Locator, out: Vector3): Vector3 {
    if (key === CORE_KEY || !locate(key, out)) {
      out.set(0, 0, 0);
    }
    return out;
  }

  private frameRings(now: number, camera: Camera, locate: Locator): void {
    for (const ring of this.rings) {
      const t = now - ring.start;
      if (t < 0 || t > MOTION_DURATIONS.pulse) {
        ring.line.visible = false;
        continue;
      }
      const k = t / MOTION_DURATIONS.pulse;
      ring.line.visible = true;
      ring.line.position.copy(this.anchor(ring.anchor, locate, this.at));
      ring.line.quaternion.copy(camera.quaternion);
      ring.line.scale.setScalar(0.6 + 2.6 * ease('sortie', k));
      ring.material.opacity = 0.9 * (1 - ease('glisse', k));
    }
  }

  /** S4 éventail / S5 pile : billboards, apparition en cascade (90 ms, `rebond`), carte nommée en blanc chaud. */
  private frameCards(now: number, camera: Camera, locate: Locator): void {
    const spec = this.card.spec;
    const presence = level(this.card, now);
    const count = spec ? Math.min(spec.count, CARD_POOL) : 0;
    if (spec && presence > 0) {
      this.anchor(spec.anchor, locate, this.at);
    }
    for (let i = 0; i < CARD_POOL; i++) {
      const card = this.cards[i];
      if (!spec || presence <= 0 || i >= count) {
        card.plane.visible = false;
        continue;
      }
      const grow = this.card.on ? ease('rebond', clamp((now - this.card.since - i * MOTION_DURATIONS.panelStagger)
        / MOTION_DURATIONS.panelIn)) : 1;
      if (grow <= 0) {
        card.plane.visible = false;
        continue;
      }
      const hot = spec.hot === i;
      if (spec.layout === 'fan') {
        // Arc tourné vers l'extérieur (à l'opposé du cœur, à l'écran) : de −50° à +50° autour de cette direction.
        const ox = this.at.dot(this.right);
        const oy = this.at.dot(this.up);
        const base = Math.hypot(ox, oy) > 0.3 ? Math.atan2(ox, oy) : 0;
        const a = base + (count > 1 ? (-50 + (100 * i) / (count - 1)) * (Math.PI / 180) : 0);
        this.to.copy(this.at).addScaledVector(this.right, Math.sin(a) * 0.62 * grow)
          .addScaledVector(this.up, Math.cos(a) * 0.62 * grow);
      } else {
        // Pile à côté du sujet, du côté opposé au cœur : chaque feuillet un peu plus haut, le dernier devant.
        const side = this.at.dot(this.right) >= 0 ? 1 : -1;
        this.to.copy(this.at).addScaledVector(this.right, side * (0.5 + i * 0.03))
          .addScaledVector(this.up, 0.1 + i * 0.07);
      }
      card.plane.visible = true;
      card.plane.position.copy(this.to);
      card.plane.quaternion.copy(camera.quaternion);
      card.plane.scale.setScalar(Math.max(0.001, grow * (hot ? 1.15 : 1)));
      // Verre discret sous le bloom : la carte nommée a un liseré blanc chaud, jamais une tache blanche.
      const c = this.palette.get(spec.tone)!;
      const e = this.palette.get(hot ? 'core' : spec.tone)!;
      card.fill.color.setRGB(c[0], c[1], c[2]);
      card.line.color.setRGB(e[0], e[1], e[2]);
      card.fill.opacity = presence * (hot ? 0.14 : 0.06);
      card.line.opacity = presence * (hot ? 0.9 : 0.55);
    }
  }

  /** S5 recherche : la loupe tourne autour du sujet (un tour en 1,8 s). */
  private frameScan(now: number, camera: Camera, locate: Locator): void {
    const spec = this.scan.spec;
    const presence = level(this.scan, now);
    if (!spec || presence <= 0) {
      this.scanRing.visible = false;
      return;
    }
    this.anchor(spec.anchor, locate, this.at);
    const a = (now / 1800) * Math.PI * 2;
    this.scanRing.visible = true;
    this.scanRing.position.copy(this.at).addScaledVector(this.right, Math.cos(a) * 0.38)
      .addScaledVector(this.up, Math.sin(a) * 0.38);
    this.scanRing.quaternion.copy(camera.quaternion);
    this.scanRing.scale.setScalar(0.16);
    const c = this.palette.get(spec.tone)!;
    this.scanMaterial.color.setRGB(c[0], c[1], c[2]);
    this.scanMaterial.opacity = presence;
  }

  /** N6 : des perles qui remontent de `from` vers `to` ; un peu d'ondulation, éclat maximal à mi-course. */
  private frameFlow(now: number, locate: Locator, offset: number): number {
    const spec = this.flow.spec;
    const presence = level(this.flow, now);
    if (!spec || presence <= 0) {
      return offset;
    }
    this.anchor(spec.from, locate, this.at);
    this.anchor(spec.to, locate, this.to);
    const n = Math.min(this.poolSize - offset, Math.max(1, Math.round(this.flowMax * clamp(spec.rate))));
    const c = this.palette.get(spec.tone)!;
    for (let j = 0; j < n; j++) {
      const u = (now / FLOW_PERIOD + j / n) % 1;
      const wobble = Math.sin(u * Math.PI * 4 + j) * 0.05;
      const i = offset + j;
      this.pos[i * 3] = this.at.x + (this.to.x - this.at.x) * u + this.up.x * wobble;
      this.pos[i * 3 + 1] = this.at.y + (this.to.y - this.at.y) * u + this.up.y * wobble;
      this.pos[i * 3 + 2] = this.at.z + (this.to.z - this.at.z) * u + this.up.z * wobble;
      const glow = presence * Math.sin(u * Math.PI);
      this.col[i * 3] = c[0] * glow;
      this.col[i * 3 + 1] = c[1] * glow;
      this.col[i * 3 + 2] = c[2] * glow;
      this.size[i] = 2.4;
    }
    return offset + n;
  }

  /** N9 : le nuage du dépôt — un amas par dossier autour du sujet ; lecture orange, écriture blanc chaud. */
  private frameCloud(now: number, locate: Locator, offset: number): number {
    const spec = this.cloud.spec;
    const presence = level(this.cloud, now);
    if (!spec || presence <= 0) {
      return offset;
    }
    this.anchor(spec.anchor, locate, this.at);
    const points: readonly CloudPoint[] = spec.points;
    const n = Math.min(this.poolSize - offset, points.length);
    for (let j = 0; j < n; j++) {
      const p = points[j];
      const i = offset + j;
      const r = 0.7 + ((j * 0.37) % 1) * 0.12;
      this.pos[i * 3] = this.at.x + Math.cos(p.angle) * r;
      this.pos[i * 3 + 1] = this.at.y + p.height * 0.6 + 0.15;
      this.pos[i * 3 + 2] = this.at.z + Math.sin(p.angle) * r;
      const c = this.palette.get(p.write || p.hot ? 'core' : 'normal')!;
      const glow = presence * (p.hot ? 1 : 0.55) * (p.hot ? 0.85 + 0.15 * Math.sin(now / 160) : 1);
      this.col[i * 3] = c[0] * glow;
      this.col[i * 3 + 1] = c[1] * glow;
      this.col[i * 3 + 2] = c[2] * glow;
      this.size[i] = p.hot ? 4 : 2.2;
    }
    return offset + n;
  }

  private fireRing(anchor: string, tone: SceneTone, at: number): void {
    // Le plus ancien anneau est réutilisé (pool fixe).
    let ring = this.rings[0];
    for (const r of this.rings) {
      if (r.start < ring.start) {
        ring = r;
      }
    }
    ring.start = at;
    ring.anchor = anchor;
    const c = this.palette.get(tone)!;
    ring.material.color.setRGB(c[0], c[1], c[2]);
  }
}

function clamp(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/** Une famille d'effet change de consigne : apparition (fondu d'entrée) ou disparition (fondu de sortie). */
function present<T>(p: Presence<T>, spec: T | null, at: number, same: (a: T, b: T) => boolean): void {
  if (spec) {
    if (!p.on || !p.spec || !same(p.spec, spec)) {
      p.since = at;
    }
    p.on = true;
    p.spec = spec;
  } else if (p.on) {
    p.on = false;
    p.since = at;
  }
}

/** L'intensité d'une famille à `now` (0-1) : fondu d'entrée 300 ms, de sortie 500 ms. */
function level<T>(p: Presence<T>, now: number): number {
  if (!p.spec) {
    return 0;
  }
  return p.on ? ease('sortie', clamp((now - p.since) / FADE_IN)) : 1 - ease('glisse', clamp((now - p.since) / FADE_OUT));
}
