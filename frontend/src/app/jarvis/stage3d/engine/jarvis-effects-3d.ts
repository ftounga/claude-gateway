import {
  AdditiveBlending, BufferAttribute, BufferGeometry, Camera, EdgesGeometry, Group, IcosahedronGeometry, Line,
  LineBasicMaterial,
  LineDashedMaterial, LineLoop, LineSegments, Mesh, MeshBasicMaterial, PlaneGeometry, Points, Scene, ShaderMaterial,
  Vector3,
} from 'three';

import { HOLO_COLORS, MOTION_DURATIONS, ease, rgb } from '../jarvis-motion';
import { CORE_KEY } from '../jarvis-link-graph';
import { CLIENT_ORBIT } from '../jarvis-layout-3d';
import {
  ANCHOR_ABOVE, ANCHOR_BELOW, ANCHOR_GLOBE, ANCHOR_SIDE, CloudPoint, Effect3d, SceneTone,
} from '../scenes3d/jarvis-scene3d.model';

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
const RING_POOL = 16;
/** S15 — écart de la vague entre deux nœuds (ms). */
const WAVE_STAGGER = 80;
/** S2 — une onde lente toutes les 2,4 s. */
const WAIT_WAVE_MS = 2400;
/** T-L4 — rupture d'une liaison (ms). */
const RUPTURE_MS = 700;
/** S2 — l'arc du compte à rebours (segments). */
const ARC_SEGMENTS = 128;
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
  /** Taille de l'anneau (1 = impulsion du cœur ; plus petit autour d'un nœud). */
  scale: number;
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
type TetherSpec = Extract<Effect3d, { kind: 'tether' }>;
type CountdownSpec = Extract<Effect3d, { kind: 'countdown' }>;
type CrownSpec = Extract<Effect3d, { kind: 'crown' }>;
type GlobeSpec = Extract<Effect3d, { kind: 'globe' }>;
type SwarmSpec = Extract<Effect3d, { kind: 'swarm' }>;
type DialSpec = Extract<Effect3d, { kind: 'dial' }>;
type RadarSpec = Extract<Effect3d, { kind: 'radar' }>;
type ActivitySpec = Extract<Effect3d, { kind: 'activity' }>;

/** SF-236-26 — la carte d'activité : 12 clients au plus, 7 segments, 6 traits par segment, un anneau de 0,62. */
const ACTIVITY_RINGS = 12;
const ACTIVITY_DAYS = 7;
const ACTIVITY_STEPS = 6;
const ACTIVITY_RADIUS = 0.62;
/** Un segment éteint (aucun tour) reste à peine visible : l'anneau se lit en entier. */
const ACTIVITY_FLOOR = 0.08;

/** SF-236-25 — le radar : son rayon (au-delà de l'orbite des postes), un balayage en 4 s, un écho pulse en 1,2 s. */
const RADAR_MARGIN = 1.4;
const RADAR_SWEEP_MS = 4000;
const RADAR_ECHO_MS = 1200;

/** S13 — satellites (petits cœurs) au plus. */
const SWARM_POOL = 4;
/** S17 — graduations du cadran. */
const DIAL_TICKS = 12;

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
  private readonly tether: Presence<TetherSpec> = { spec: null, on: false, since: -Infinity };
  private readonly countdown: Presence<CountdownSpec> = { spec: null, on: false, since: -Infinity };
  private readonly crown: Presence<CrownSpec> = { spec: null, on: false, since: -Infinity };
  /** T-L4 — l'instant de la rupture (attente levée), `null` sinon. */
  private brokenAt: number | null = null;
  private waveSeq = -1;
  private lastWaitWave = -Infinity;
  private readonly tetherLine: LineSegments;
  private readonly tetherMaterial: LineDashedMaterial;
  private readonly tetherPos = new Float32Array(12);
  private readonly tetherDist = new Float32Array(4);
  private readonly arc: Line;
  private readonly arcMaterial: LineBasicMaterial;
  private readonly mid = new Vector3();
  private readonly forward = new Vector3();
  private readonly globe: Presence<GlobeSpec> = { spec: null, on: false, since: -Infinity };
  private readonly swarm: Presence<SwarmSpec> = { spec: null, on: false, since: -Infinity };
  private readonly dial: Presence<DialSpec> = { spec: null, on: false, since: -Infinity };
  private readonly globeMesh: LineSegments;
  private readonly globeMaterial: LineBasicMaterial;
  private readonly swarmLines: LineSegments;
  private readonly swarmMaterial: LineBasicMaterial;
  private readonly swarmPos = new Float32Array(SWARM_POOL * 6);
  private readonly dialMesh: LineSegments;
  private readonly dialMaterial: LineBasicMaterial;
  private readonly radar: Presence<RadarSpec> = { spec: null, on: false, since: -Infinity };
  private readonly radarRing: LineSegments;
  private readonly radarMaterial: LineBasicMaterial;
  private readonly radarRadius: number;
  private readonly activity: Presence<ActivitySpec> = { spec: null, on: false, since: -Infinity };
  private readonly activityLines: LineSegments;
  private readonly activityMaterial: LineBasicMaterial;
  private readonly activityPos = new Float32Array(ACTIVITY_RINGS * ACTIVITY_DAYS * ACTIVITY_STEPS * 6);
  private readonly activityCol = new Float32Array(ACTIVITY_RINGS * ACTIVITY_DAYS * ACTIVITY_STEPS * 6);

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
      this.rings.push({ line, material, start: -Infinity, anchor: CORE_KEY, scale: 1 });
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

    // SF-236-10 — L-attente : deux segments pointillés (un seul tant que l'attente tient, deux fragments à la rupture).
    const tether = new BufferGeometry();
    tether.setAttribute('position', new BufferAttribute(this.tetherPos, 3));
    tether.setAttribute('lineDistance', new BufferAttribute(this.tetherDist, 1));
    this.tetherMaterial = new LineDashedMaterial({ dashSize: 0.08, gapSize: 0.06, transparent: true, opacity: 0,
      blending: AdditiveBlending, depthWrite: false });
    this.tetherLine = new LineSegments(tether, this.tetherMaterial);
    this.tetherLine.visible = false;
    this.tetherLine.frustumCulled = false;
    this.group.add(this.tetherLine);
    // SF-236-10 — S2 : l'arc du compte à rebours autour du cœur.
    const arc = new BufferGeometry();
    const arcPoints = new Float32Array((ARC_SEGMENTS + 1) * 3);
    for (let i = 0; i <= ARC_SEGMENTS; i++) {
      const a = Math.PI / 2 - (i / ARC_SEGMENTS) * Math.PI * 2;
      arcPoints[i * 3] = Math.cos(a);
      arcPoints[i * 3 + 1] = Math.sin(a);
    }
    arc.setAttribute('position', new BufferAttribute(arcPoints, 3));
    this.arcMaterial = this.lineMaterial();
    this.arc = new Line(arc, this.arcMaterial);
    this.arc.visible = false;
    this.arc.frustumCulled = false;
    this.group.add(this.arc);

    // SF-236-12 — S6 : le globe filaire (fil de fer additif, opacité 0,35, rotation lente).
    const ico = new IcosahedronGeometry(0.9, 1);
    this.globeMaterial = this.lineMaterial();
    this.globeMesh = new LineSegments(new EdgesGeometry(ico), this.globeMaterial);
    ico.dispose();
    this.globeMesh.visible = false;
    this.globeMesh.frustumCulled = false;
    this.group.add(this.globeMesh);
    // SF-236-12 — S13 : L-essaim, un filament du cœur à chaque satellite.
    const swarm = new BufferGeometry();
    swarm.setAttribute('position', new BufferAttribute(this.swarmPos, 3));
    this.swarmMaterial = this.lineMaterial();
    this.swarmLines = new LineSegments(swarm, this.swarmMaterial);
    this.swarmLines.visible = false;
    this.swarmLines.frustumCulled = false;
    this.group.add(this.swarmLines);
    // SF-236-12 — S17 : le cadran (cercle + 12 graduations), face à la caméra.
    const dial = new BufferGeometry();
    const dialPoints: number[] = [];
    for (let i = 0; i < 48; i++) {
      const a0 = (i / 48) * Math.PI * 2;
      const a1 = ((i + 1) / 48) * Math.PI * 2;
      dialPoints.push(Math.cos(a0), Math.sin(a0), 0, Math.cos(a1), Math.sin(a1), 0);
    }
    for (let i = 0; i < DIAL_TICKS; i++) {
      const a = (i / DIAL_TICKS) * Math.PI * 2;
      dialPoints.push(Math.cos(a) * 0.8, Math.sin(a) * 0.8, 0, Math.cos(a), Math.sin(a), 0);
    }
    dialPoints.push(0, 0, 0, 0, 0.7, 0);
    dial.setAttribute('position', new BufferAttribute(new Float32Array(dialPoints), 3));
    this.dialMaterial = this.lineMaterial();
    this.dialMesh = new LineSegments(dial, this.dialMaterial);
    this.dialMesh.visible = false;
    this.dialMesh.frustumCulled = false;
    this.group.add(this.dialMesh);
    // SF-236-25 — le radar : un anneau horizontal autour de l'univers, 4 graduations, et le rayon qui balaie.
    this.radarRadius = (narrow ? CLIENT_ORBIT.narrow : CLIENT_ORBIT.wide) + RADAR_MARGIN;
    const radarPoints: number[] = [];
    for (let i = 0; i < RING_SEGMENTS; i++) {
      const a0 = (i / RING_SEGMENTS) * Math.PI * 2;
      const a1 = ((i + 1) / RING_SEGMENTS) * Math.PI * 2;
      radarPoints.push(Math.cos(a0), 0, Math.sin(a0), Math.cos(a1), 0, Math.sin(a1));
    }
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * Math.PI * 2;
      radarPoints.push(Math.cos(a) * 0.94, 0, Math.sin(a) * 0.94, Math.cos(a) * 1.06, 0, Math.sin(a) * 1.06);
    }
    radarPoints.push(0, 0, 0, 1, 0, 0); // le rayon du balayage (tourné à chaque image)
    const radar = new BufferGeometry();
    radar.setAttribute('position', new BufferAttribute(new Float32Array(radarPoints), 3));
    this.radarMaterial = this.lineMaterial();
    this.radarRing = new LineSegments(radar, this.radarMaterial);
    this.radarRing.visible = false;
    this.radarRing.frustumCulled = false;
    this.group.add(this.radarRing);
    // SF-236-26 — la carte d'activité : des arcs colorés par sommet (éclat du jour), tampons préalloués.
    const activity = new BufferGeometry();
    activity.setAttribute('position', new BufferAttribute(this.activityPos, 3));
    activity.setAttribute('color', new BufferAttribute(this.activityCol, 3));
    activity.setDrawRange(0, 0);
    this.activityMaterial = new LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0,
      blending: AdditiveBlending, depthWrite: false });
    this.activityLines = new LineSegments(activity, this.activityMaterial);
    this.activityLines.visible = false;
    this.activityLines.frustumCulled = false;
    this.group.add(this.activityLines);
  }

  /** Les effets du plan ; `opening` (banc) : tout part de t = 0. */
  set(effects: readonly Effect3d[], now: number, opening: boolean): void {
    const at = opening ? 0 : now;
    const seen = { flow: null as FlowSpec | null, cards: null as CardsSpec | null, scan: null as ScanSpec | null,
      cloud: null as CloudSpec | null, tether: null as TetherSpec | null, countdown: null as CountdownSpec | null,
      crown: null as CrownSpec | null, globe: null as GlobeSpec | null, swarm: null as SwarmSpec | null,
      dial: null as DialSpec | null, radar: null as RadarSpec | null, activity: null as ActivitySpec | null };
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
        case 'tether':
          seen.tether = effect;
          break;
        case 'countdown':
          seen.countdown = effect;
          break;
        case 'crown':
          seen.crown = effect;
          break;
        case 'globe':
          seen.globe = effect;
          break;
        case 'swarm':
          seen.swarm = effect;
          break;
        case 'dial':
          seen.dial = effect;
          break;
        case 'radar':
          seen.radar = effect;
          break;
        case 'activity':
          seen.activity = effect;
          break;
        case 'wave':
          if (opening || effect.seq > this.waveSeq) {
            effect.keys.forEach((key, i) => this.fireRing(key, effect.tone, at + i * WAVE_STAGGER, 0.5));
          }
          this.waveSeq = effect.seq;
          break;
      }
    }
    // T-L4 : l'attente levée — la rupture part de l'instant où la liaison se rompt (une fois).
    if (seen.tether?.broken && !this.tether.spec?.broken) {
      this.brokenAt = at;
    } else if (seen.tether && !seen.tether.broken) {
      this.brokenAt = null;
    }
    if (seen.countdown && !this.countdown.on) {
      this.lastWaitWave = -Infinity;
    }
    present(this.flow, seen.flow, at, (a, b) => a.from === b.from && a.to === b.to);
    present(this.card, seen.cards, at, (a, b) => a.anchor === b.anchor && a.layout === b.layout);
    present(this.scan, seen.scan, at, (a, b) => a.anchor === b.anchor);
    present(this.cloud, seen.cloud, at, (a, b) => a.anchor === b.anchor);
    present(this.tether, seen.tether, at, (a, b) => a.from === b.from && a.to === b.to);
    present(this.countdown, seen.countdown, at, () => true);
    present(this.crown, seen.crown, at, (a, b) => a.done.length === b.done.length);
    present(this.globe, seen.globe, at, () => true);
    present(this.swarm, seen.swarm, at, () => true);
    present(this.dial, seen.dial, at, (a, b) => a.anchor === b.anchor);
    present(this.radar, seen.radar, at, (a, b) => a.echoes.length === b.echoes.length);
    present(this.activity, seen.activity, at, (a, b) => a.rings.length === b.rings.length);
  }

  /** Une image. `camera` : anneaux, cartes et loupe lui font face. */
  frame(now: number, camera: Camera, locate: Locator): void {
    const m = camera.matrixWorld.elements;
    this.right.set(m[0], m[1], m[2]).normalize();
    this.up.set(m[4], m[5], m[6]).normalize();
    this.forward.set(-m[8], -m[9], -m[10]).normalize();
    this.frameRings(now, camera, locate);
    this.frameCards(now, camera, locate);
    this.frameScan(now, camera, locate);
    this.frameTether(now, locate);
    this.frameCountdown(now, camera);
    this.frameGlobe(now, locate);
    this.frameDial(now, camera, locate);
    this.frameActivity(now, locate);
    let used = this.frameFlow(now, locate, 0);
    used = this.frameCloud(now, locate, used);
    used = this.frameCrown(now, used);
    used = this.frameSwarm(now, used);
    used = this.frameRadar(now, locate, used);
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
    this.tetherLine.geometry.dispose();
    this.tetherMaterial.dispose();
    this.arc.geometry.dispose();
    this.arcMaterial.dispose();
    this.globeMesh.geometry.dispose();
    this.globeMaterial.dispose();
    this.swarmLines.geometry.dispose();
    this.swarmMaterial.dispose();
    this.dialMesh.geometry.dispose();
    this.dialMaterial.dispose();
    this.radarRing.geometry.dispose();
    this.radarMaterial.dispose();
    this.activityLines.geometry.dispose();
    this.activityMaterial.dispose();
    this.group.removeFromParent();
  }

  private lineMaterial(): LineBasicMaterial {
    return new LineBasicMaterial({ transparent: true, opacity: 0, blending: AdditiveBlending, depthWrite: false });
  }

  private anchor(key: string, locate: Locator, out: Vector3): Vector3 {
    switch (key) {
      case ANCHOR_GLOBE:
        // Derrière le cœur (vu de la caméra), un peu au-dessus.
        return out.set(0, 0, 0).addScaledVector(this.forward, 3).addScaledVector(this.up, 1.2)
          .addScaledVector(this.right, 0.8);
      case ANCHOR_BELOW:
        return out.set(0, 0, 0).addScaledVector(this.up, -1.8);
      case ANCHOR_ABOVE:
        return out.set(0, 0, 0).addScaledVector(this.up, 1.7);
      case ANCHOR_SIDE:
        return out.set(0, 0, 0).addScaledVector(this.right, 0.6).addScaledVector(this.up, 0.3);
    }
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
      ring.line.scale.setScalar((0.6 + 2.6 * ease('sortie', k)) * ring.scale);
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
      } else if (spec.layout === 'film') {
        // S5b — la pellicule en arc au-dessus du cœur (−60° → +60°), le plan extrait agrandi.
        const a = count > 1 ? (-60 + (120 * i) / (count - 1)) * (Math.PI / 180) : 0;
        this.to.copy(this.at).addScaledVector(this.right, Math.sin(a) * 1.5 * grow)
          .addScaledVector(this.up, (0.9 + Math.cos(a) * 0.5) * grow);
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
      // Un arc (passation) se lève au milieu de la course ; sinon une légère ondulation.
      const wobble = Math.sin(u * Math.PI * 4 + j) * 0.05 + (spec.lift ?? 0) * Math.sin(u * Math.PI);
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

  /**
   * S11 — L-attente : pointillé de la teinte d'attente du chantier à la personne (ou à un point flottant si elle
   * n'est pas posée). Levée (T-L4, 0,7 s, `entree`) : le filament se scinde au milieu, chaque fragment se rétracte
   * vers son bout et s'éteint.
   */
  private frameTether(now: number, locate: Locator): void {
    const spec = this.tether.spec;
    const presence = level(this.tether, now);
    if (!spec || presence <= 0) {
      this.tetherLine.visible = false;
      return;
    }
    this.anchor(spec.from, locate, this.at);
    if (!spec.to || !locate(spec.to, this.to)) {
      this.to.copy(this.at).addScaledVector(this.right, 0.9).addScaledVector(this.up, 0.35);
    }
    const k = this.brokenAt === null ? 0 : ease('entree', clamp((now - this.brokenAt) / RUPTURE_MS));
    // Rupture : un trou s'ouvre au milieu, chaque fragment se rétracte vers son bout.
    const gap = this.brokenAt === null ? 0 : 0.08 + 0.42 * k;
    this.mid.copy(this.at).lerp(this.to, 0.5 - gap);
    write(this.tetherPos, 0, this.at);
    write(this.tetherPos, 1, this.mid);
    this.mid.copy(this.at).lerp(this.to, 0.5 + gap);
    write(this.tetherPos, 2, this.mid);
    write(this.tetherPos, 3, this.to);
    const len = this.at.distanceTo(this.to);
    this.tetherDist[0] = 0;
    this.tetherDist[1] = len * (0.5 - gap);
    this.tetherDist[2] = len * (0.5 + gap);
    this.tetherDist[3] = len;
    const geometry = this.tetherLine.geometry;
    (geometry.getAttribute('position') as BufferAttribute).needsUpdate = true;
    (geometry.getAttribute('lineDistance') as BufferAttribute).needsUpdate = true;
    const c = this.palette.get(spec.tone)!;
    this.tetherMaterial.color.setRGB(c[0], c[1], c[2]);
    this.tetherMaterial.opacity = presence * (1 - k);
    this.tetherLine.visible = true;
  }

  /** S2 — l'arc qui se vide autour du cœur (fraction restante) et une onde lente toutes les 2,4 s. */
  private frameCountdown(now: number, camera: Camera): void {
    const spec = this.countdown.spec;
    const presence = level(this.countdown, now);
    if (!spec || presence <= 0) {
      this.arc.visible = false;
      return;
    }
    this.arc.visible = true;
    this.arc.position.set(0, 0, 0);
    this.arc.quaternion.copy(camera.quaternion);
    this.arc.scale.setScalar(1.25);
    this.arc.geometry.setDrawRange(0, Math.max(2, Math.round(clamp(spec.fraction) * ARC_SEGMENTS) + 1));
    const c = this.palette.get(spec.tone)!;
    this.arcMaterial.color.setRGB(c[0], c[1], c[2]);
    this.arcMaterial.opacity = presence;
    if (this.countdown.on && now - this.lastWaitWave >= WAIT_WAVE_MS) {
      this.lastWaitWave = now;
      this.fireRing(CORE_KEY, spec.tone, now, 0.6);
    }
  }

  /** S14 — la couronne de perles sous le cœur (arc de 120°), une par point de route ; traité = vert. */
  private frameCrown(now: number, offset: number): number {
    const spec = this.crown.spec;
    const presence = level(this.crown, now);
    if (!spec || presence <= 0) {
      return offset;
    }
    const n = Math.min(this.poolSize - offset, spec.done.length);
    for (let j = 0; j < n; j++) {
      const a = n > 1 ? (-60 + (120 * j) / (n - 1)) * (Math.PI / 180) : 0;
      const i = offset + j;
      this.mid.set(0, 0, 0).addScaledVector(this.right, Math.sin(a) * 1.4)
        .addScaledVector(this.up, -1.2 - (1 - Math.cos(a)) * 0.4);
      write(this.pos, i, this.mid);
      const c = this.palette.get(spec.done[j] ? 'green' : 'normal')!;
      const glow = presence * (spec.done[j] ? 1 : 0.6);
      this.col[i * 3] = c[0] * glow;
      this.col[i * 3 + 1] = c[1] * glow;
      this.col[i * 3 + 2] = c[2] * glow;
      this.size[i] = 5;
    }
    return offset + n;
  }

  /** S6 — le globe filaire derrière le cœur, en rotation lente. */
  private frameGlobe(now: number, locate: Locator): void {
    const presence = level(this.globe, now);
    if (!this.globe.spec || presence <= 0) {
      this.globeMesh.visible = false;
      return;
    }
    this.globeMesh.visible = true;
    this.globeMesh.position.copy(this.anchor(ANCHOR_GLOBE, locate, this.at));
    this.globeMesh.rotation.set(0.3, now / 6000, 0);
    this.globeMesh.scale.setScalar(1.5);
    const c = this.palette.get('normal')!;
    this.globeMaterial.color.setRGB(c[0], c[1], c[2]);
    this.globeMaterial.opacity = 0.5 * presence;
  }

  /** S17 — le cadran attaché au nœud, l'aiguille tourne (un tour en 6 s). */
  private frameDial(now: number, camera: Camera, locate: Locator): void {
    const spec = this.dial.spec;
    const presence = level(this.dial, now);
    if (!spec || presence <= 0) {
      this.dialMesh.visible = false;
      return;
    }
    this.anchor(spec.anchor, locate, this.at);
    this.dialMesh.visible = true;
    this.dialMesh.position.copy(this.at).addScaledVector(this.up, 0.45);
    this.dialMesh.quaternion.copy(camera.quaternion);
    this.dialMesh.scale.setScalar(0.22);
    const c = this.palette.get(spec.tone)!;
    this.dialMaterial.color.setRGB(c[0], c[1], c[2]);
    this.dialMaterial.opacity = 0.8 * presence;
  }

  /** S13 + N8 — les satellites orbitent autour du cœur (rayon 1,6), chacun relié au cœur ; une traînée les suit. */
  private frameSwarm(now: number, offset: number): number {
    const spec = this.swarm.spec;
    const presence = level(this.swarm, now);
    if (!spec || presence <= 0) {
      this.swarmLines.visible = false;
      return offset;
    }
    const n = Math.min(SWARM_POOL, spec.count);
    const c = this.palette.get(spec.tone)!;
    let used = offset;
    for (let j = 0; j < n; j++) {
      const a = (j / n) * Math.PI * 2 + now / 2200;
      this.mid.set(0, 0, 0).addScaledVector(this.right, Math.cos(a) * 1.6)
        .addScaledVector(this.up, Math.sin(a) * 0.9).addScaledVector(this.forward, Math.sin(a) * 0.4);
      write(this.swarmPos, j * 2, this.to.set(0, 0, 0));
      write(this.swarmPos, j * 2 + 1, this.mid);
      // Le satellite et sa traînée (4 perles qui pâlissent).
      for (let k = 0; k < 5 && used < this.poolSize; k++) {
        const b = a - k * 0.08;
        this.at.set(0, 0, 0).addScaledVector(this.right, Math.cos(b) * 1.6)
          .addScaledVector(this.up, Math.sin(b) * 0.9).addScaledVector(this.forward, Math.sin(b) * 0.4);
        write(this.pos, used, this.at);
        const glow = presence * (k === 0 ? 1 : 0.5 / k);
        const core = k === 0 ? this.palette.get('core')! : c;
        this.col[used * 3] = core[0] * glow;
        this.col[used * 3 + 1] = core[1] * glow;
        this.col[used * 3 + 2] = core[2] * glow;
        this.size[used] = k === 0 ? 7 : 3;
        used++;
      }
    }
    for (let j = n; j < SWARM_POOL; j++) {
      write(this.swarmPos, j * 2, this.to.set(0, 0, 0));
      write(this.swarmPos, j * 2 + 1, this.to);
    }
    (this.swarmLines.geometry.getAttribute('position') as BufferAttribute).needsUpdate = true;
    this.swarmLines.visible = true;
    this.swarmMaterial.color.setRGB(c[0], c[1], c[2]);
    this.swarmMaterial.opacity = 0.55 * presence;
    return used;
  }

  /**
   * SF-236-25 (NF-15) — le radar : l'anneau autour de l'univers et son balayage ; un écho par alerte, à l'azimut de
   * son client sur l'anneau, qui pulse (taille et éclat) ; teinte de l'alerte. 0 allocation : tampons préalloués.
   */
  private frameRadar(now: number, locate: Locator, offset: number): number {
    const spec = this.radar.spec;
    const presence = level(this.radar, now);
    if (!spec || presence <= 0) {
      this.radarRing.visible = false;
      return offset;
    }
    const r = this.radarRadius;
    this.radarRing.visible = true;
    this.radarRing.position.set(0, -0.2, 0);
    this.radarRing.scale.setScalar(r);
    const hot = spec.echoes.some((e) => e.tone === 'alert');
    const c = this.palette.get(hot ? 'alert' : 'wait')!;
    this.radarMaterial.color.setRGB(c[0], c[1], c[2]);
    this.radarMaterial.opacity = 0.35 * presence;
    // Le rayon : le dernier segment de la géométrie, tourné autour de l'axe vertical.
    const sweep = (now / RADAR_SWEEP_MS) * Math.PI * 2;
    const position = this.radarRing.geometry.getAttribute('position') as BufferAttribute;
    position.setXYZ(position.count - 1, Math.cos(sweep), 0, Math.sin(sweep));
    position.needsUpdate = true;
    let used = offset;
    for (let i = 0; i < spec.echoes.length && used < this.poolSize; i++) {
      const echo = spec.echoes[i];
      this.anchor(echo.anchor, locate, this.at);
      const a = this.at.x === 0 && this.at.z === 0 ? (i / Math.max(1, spec.echoes.length)) * Math.PI * 2
        : Math.atan2(this.at.z, this.at.x);
      this.to.set(Math.cos(a) * r, -0.2, Math.sin(a) * r);
      write(this.pos, used, this.to);
      const k = ((now + i * 300) % RADAR_ECHO_MS) / RADAR_ECHO_MS;
      const glow = presence * (1 - 0.6 * ease('sortie', k));
      const tone = this.palette.get(echo.tone)!;
      this.col[used * 3] = tone[0] * glow;
      this.col[used * 3 + 1] = tone[1] * glow;
      this.col[used * 3 + 2] = tone[2] * glow;
      this.size[used] = 8 + 14 * ease('sortie', k);
      used++;
    }
    return used;
  }

  /**
   * SF-236-26 (NF-16) — la carte d'activité : autour de chaque client, un anneau face à la caméra en 7 arcs (le plus
   * ancien en haut, puis dans le sens des aiguilles), chacun éclairé selon les tours du jour. 0 allocation.
   */
  private frameActivity(now: number, locate: Locator): void {
    const spec = this.activity.spec;
    const presence = level(this.activity, now);
    if (!spec || presence <= 0) {
      this.activityLines.visible = false;
      return;
    }
    const c = this.palette.get('normal')!;
    const gap = 0.12;
    const span = (Math.PI * 2) / ACTIVITY_DAYS;
    let v = 0;
    const rings = Math.min(ACTIVITY_RINGS, spec.rings.length);
    for (let r = 0; r < rings; r++) {
      const ring = spec.rings[r];
      this.anchor(ring.anchor, locate, this.at);
      const days = Math.min(ACTIVITY_DAYS, ring.levels.length);
      for (let d = 0; d < days; d++) {
        const glow = ACTIVITY_FLOOR + (1 - ACTIVITY_FLOOR) * clamp(ring.levels[d]);
        const start = Math.PI / 2 - d * span - gap / 2;
        for (let k = 0; k < ACTIVITY_STEPS; k++) {
          const a0 = start - (k / ACTIVITY_STEPS) * (span - gap);
          const a1 = start - ((k + 1) / ACTIVITY_STEPS) * (span - gap);
          this.to.copy(this.at).addScaledVector(this.right, Math.cos(a0) * ACTIVITY_RADIUS)
            .addScaledVector(this.up, Math.sin(a0) * ACTIVITY_RADIUS);
          write(this.activityPos, v, this.to);
          this.activityCol[v * 3] = c[0] * glow;
          this.activityCol[v * 3 + 1] = c[1] * glow;
          this.activityCol[v * 3 + 2] = c[2] * glow;
          v++;
          this.to.copy(this.at).addScaledVector(this.right, Math.cos(a1) * ACTIVITY_RADIUS)
            .addScaledVector(this.up, Math.sin(a1) * ACTIVITY_RADIUS);
          write(this.activityPos, v, this.to);
          this.activityCol[v * 3] = c[0] * glow;
          this.activityCol[v * 3 + 1] = c[1] * glow;
          this.activityCol[v * 3 + 2] = c[2] * glow;
          v++;
        }
      }
    }
    const geometry = this.activityLines.geometry;
    geometry.setDrawRange(0, v);
    (geometry.getAttribute('position') as BufferAttribute).needsUpdate = true;
    (geometry.getAttribute('color') as BufferAttribute).needsUpdate = true;
    this.activityLines.visible = v > 0;
    this.activityMaterial.opacity = 0.9 * presence;
  }

  private fireRing(anchor: string, tone: SceneTone, at: number, scale = 1): void {
    // Le plus ancien anneau est réutilisé (pool fixe).
    let ring = this.rings[0];
    for (const r of this.rings) {
      if (r.start < ring.start) {
        ring = r;
      }
    }
    ring.start = at;
    ring.anchor = anchor;
    ring.scale = scale;
    const c = this.palette.get(tone)!;
    ring.material.color.setRGB(c[0], c[1], c[2]);
  }
}

function write(buffer: Float32Array, index: number, v: Vector3): void {
  buffer[index * 3] = v.x;
  buffer[index * 3 + 1] = v.y;
  buffer[index * 3 + 2] = v.z;
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
