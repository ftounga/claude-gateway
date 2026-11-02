import {
  AdditiveBlending, BufferAttribute, BufferGeometry, ColorManagement, LineBasicMaterial, LineSegments,
  LinearSRGBColorSpace, Material, Mesh, Object3D, PerspectiveCamera, PlaneGeometry, Points, Scene, ShaderMaterial,
  Vector3, WebGLRenderer,
} from 'three';

import { FrameLoop } from '../jarvis-frame-loop';
import { JarvisEngine, JarvisEngineOptions } from '../jarvis-engine.contract';
import { HOLO_ALPHA, HOLO_COLORS, HOLO_DIM_OPACITY, MOTION_DURATIONS, rgb } from '../jarvis-motion';
import { QualityGovernor } from '../jarvis-quality';
import { QualityLevel } from '../jarvis-render-capability';
import { StagePlan } from '../jarvis-stage-director';
import { Tween } from '../jarvis-tween';
import { BrainsClock } from '../jarvis-brains';
import { Brain3d } from './jarvis-brain-3d';
import {
  CAMERA_FOV, CameraDirector, CameraShot, ENTRY_FULL_MS, ENTRY_SHORT_MS, EXIT_MS, MANUAL_HOLD_MS, PITCH_LIMIT_DEG,
  SKIP_FADE_MS, Spring, ZOOM_MAX, ZOOM_MIN, entryFrame, exitFrame, nearestAngle, nodeShot, overviewDistance,
  overviewShot, pickNearest,
} from '../jarvis-camera-rules';
import { fpsClass } from '../jarvis-render-stats';
import { Gestures } from './jarvis-gestures';
import { HEART_RADIUS } from './jarvis-heart-3d';
import { Heart3d } from './jarvis-heart-3d';
import { Universe3d } from './jarvis-universe-3d';
import { Node3d } from '../jarvis-layout-3d';
import { JarvisPost } from './jarvis-post';
import { Effects3d } from './jarvis-effects-3d';
import { Panels3d } from './jarvis-panels-3d';
import { panelViewport } from '../jarvis-panels';

/**
 * F-236 / SF-236-01 — **le moteur 3D, socle** : seul fichier (avec ses voisins de `engine/`) qui importe `three`,
 * donc seul contenu du morceau paresseux. Il dessine ce que l'écran lui demande et n'appelle jamais le réseau.
 *
 * <p>Le décor <b>riche</b> (goût du PO, V3) — lueur d'abysse, nébuleuse lente, étoiles sur trois profondeurs,
 * poussière, sol polaire (SF-236-01) ; le <b>cœur</b> et ses états, le bloom et la qualité adaptative (SF-236-02).
 * Tampons préalloués, uniformes seulement : zéro allocation par image.</p>
 */

/** Ce que chaque niveau dessine (§6.1 / §6.3) ; le décor s'allège, il ne disparaît jamais d'un coup. */
export const LEVEL_BUDGET: Record<QualityLevel, {
  pixelRatio: number; starLayers: number; starsPerLayer: number; dust: number; floorRings: number; nebula: boolean;
}> = {
  high: { pixelRatio: 2, starLayers: 3, starsPerLayer: 900, dust: 600, floorRings: 7, nebula: true },
  medium: { pixelRatio: 1.5, starLayers: 2, starsPerLayer: 500, dust: 250, floorRings: 5, nebula: true },
  low: { pixelRatio: 1, starLayers: 1, starsPerLayer: 300, dust: 0, floorRings: 3, nebula: false },
};

/** Le pas d'une image est borné : un onglet revenu ne fait pas « sauter » les animations. */
const MAX_DT_MS = 50;

const BACKDROP_VERTEX = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.999, 1.0);
}`;

const BACKDROP_FRAGMENT = /* glsl */ `
uniform float uTime;
uniform float uAspect;
uniform float uNebula;
uniform vec3 uFog;
uniform vec3 uAbyss;
uniform vec3 uTint;
uniform vec3 uDeep;
uniform float uGlow;
uniform float uDeepMix;
varying vec2 vUv;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0; float a = 0.5;
  for (int i = 0; i < 5; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; }
  return v;
}
void main() {
  vec2 p = (vUv - vec2(0.5, 0.56)) * vec2(uAspect, 1.0);
  float r = length(p);
  vec3 abyss = mix(uAbyss, uDeep * 0.28, uDeepMix);
  vec3 color = mix(mix(uFog, abyss, uGlow), uFog, smoothstep(0.0, 0.85, r));
  if (uNebula > 0.5) {
    float t = uTime * 0.000012;
    float n = fbm(p * 2.2 + vec2(t, -t * 0.7));
    float m = fbm(p * 4.0 - vec2(t * 0.5, t));
    // SF-236-07 — la nébuleuse reste loin du cœur : sous lui, elle passait le seuil du bloom (tache claire).
    float veil = smoothstep(0.35, 0.8, n) * smoothstep(0.28, 0.6, r) * (1.0 - smoothstep(0.6, 1.4, r));
    color += uTint * veil * 0.16 + uDeep * smoothstep(0.5, 0.9, m) * veil * 0.08;
  }
  gl_FragColor = vec4(color, 1.0);
}`;

const POINTS_VERTEX = /* glsl */ `
attribute float aSize;
attribute float aPhase;
attribute vec3 aColor;
uniform float uTime;
uniform float uPixelRatio;
uniform float uDrift;
uniform float uBreath;
uniform float uScale;
varying vec3 vColor;
varying float vTwinkle;
void main() {
  vec3 p = position * (1.0 + uBreath * sin(uTime * 0.0021 + aPhase * 0.3));
  p += uDrift * vec3(sin(uTime * 0.00021 + aPhase), cos(uTime * 0.00017 + aPhase * 1.3), sin(uTime * 0.00013 + aPhase * 0.7));
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = min(aSize * uPixelRatio * (uScale / max(-mv.z, 0.5)), 18.0 * uPixelRatio);
  vColor = aColor;
  // SF-236-07 — ce qui frôle la caméra s'efface (la poussière proche faisait des taches sous le bloom).
  vTwinkle = (0.72 + 0.28 * sin(uTime * 0.0016 + aPhase * 6.2831)) * smoothstep(1.5, 5.0, -mv.z);
}`;

const POINTS_FRAGMENT = /* glsl */ `
uniform float uOpacity;
varying vec3 vColor;
varying float vTwinkle;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.0, 0.5, d);
  a *= a;
  gl_FragColor = vec4(vColor * a * vTwinkle * uOpacity, 1.0);
}`;

/** Fabrique le moteur ; l'écran ne passe que par le contrat. */
export function createJarvisEngine(options: JarvisEngineOptions): JarvisEngine {
  return new ThreeJarvisEngine(options);
}

class ThreeJarvisEngine implements JarvisEngine {
  private readonly renderer: WebGLRenderer;
  private readonly scene = new Scene();
  private readonly camera: PerspectiveCamera;
  private readonly loop: FrameLoop;
  private readonly timed: ShaderMaterial[] = [];
  private readonly backdrop: ShaderMaterial;
  private readonly post: JarvisPost;
  private readonly heart: Heart3d | null;
  /** SF-236-03 — le grand cerveau et son horloge. */
  private readonly brain: Brain3d | null;
  /** SF-236-04 — l'univers et ses liaisons. */
  private readonly universe: Universe3d | null;
  /** SF-236-08 — les panneaux holographiques (DOM) et les effets des scènes. */
  private readonly panels: Panels3d | null;
  private readonly effects: Effects3d | null;
  private readonly projected = new Vector3();
  private width: number;
  private height: number;
  /** SF-236-05 — la caméra : directeur pur + ressorts amortis critiques (lacet, tangage, distance, point visé). */
  private readonly director = new CameraDirector();
  private readonly rig = {
    tx: new Spring(0), ty: new Spring(0), tz: new Spring(0), yaw: new Spring(0), pitch: new Spring(0.3),
    dist: new Spring(9),
  };
  private shot: CameraShot | null = null;
  /** SF-236-10 — travelling des scènes (S15 « le point », S10 passation) : décalage de lacet, joué une fois par id. */
  private travelYaw = 0;
  private travelId: string | null = null;
  private manualUntil = -Infinity;
  private lastDecide = -Infinity;
  private planDirty = true;
  private readonly world = new Vector3();
  private gestures: Gestures | null = null;
  private entryStart = 0;
  private entryTotal = 0;
  private exitStart: number | null = null;
  private wallNow = 0;
  /** SF-236-06 — histogramme des i/s instantanés (6 classes), sans allocation. */
  private readonly fpsHistogram = new Uint32Array(6);
  private brains: BrainsClock = new BrainsClock();
  private floorMaterial: LineBasicMaterial | null = null;
  private readonly floorNormal = rgb(HOLO_COLORS.normalDim);
  private readonly floorDeep = rgb(HOLO_COLORS.deep);
  private readonly governor: QualityGovernor | null;
  private readonly dustOpacity = new Tween(HOLO_ALPHA.panel);
  private dustMaterial: ShaderMaterial | null = null;
  private level: QualityLevel;
  private plan: StagePlan;
  private lastNow: number | null = null;
  private readonly onLost = (event: Event): void => {
    event.preventDefault();
    this.loop.pause();
    this.options.onContextLost();
  };
  private readonly onRestored = (): void => this.options.onContextRestored();
  private firstFrameSeen = false;
  private lastPoints: ShaderMaterial | null = null;
  private disposed = false;

  constructor(private readonly options: JarvisEngineOptions) {
    const budget = LEVEL_BUDGET[options.level];
    const canvas = options.canvas;
    this.level = options.level;
    this.plan = options.plan;
    // Les couleurs de la palette sont écrites telles quelles, au canevas comme dans les passes (aucune conversion).
    ColorManagement.enabled = false;
    canvas.addEventListener('webglcontextlost', this.onLost);
    canvas.addEventListener('webglcontextrestored', this.onRestored);
    this.renderer = new WebGLRenderer({
      canvas, antialias: options.level === 'high', alpha: false, powerPreference: 'high-performance',
    });
    this.renderer.outputColorSpace = LinearSRGBColorSpace;
    this.renderer.setPixelRatio(Math.min(options.pixelRatio, budget.pixelRatio));
    this.renderer.setClearColor(HOLO_COLORS.fog, 1);
    this.renderer.setSize(options.width, options.height, false);

    this.width = options.width;
    this.height = options.height;
    this.camera = new PerspectiveCamera(CAMERA_FOV, options.width / Math.max(options.height, 1), 0.1, 200);
    this.camera.position.set(0, 0.6, 9);
    this.camera.lookAt(0, 0, 0);

    this.backdrop = this.buildBackdrop(budget.nebula);
    this.buildStars(budget.starLayers, budget.starsPerLayer);
    if (budget.dust > 0) {
      this.buildDust(budget.dust);
    }
    this.buildFloor(budget.floorRings);
    const start = options.clock();
    if (options.withCore) {
      this.heart = new Heart3d(this.scene, options.random, options.level, this.renderer.getPixelRatio());
      this.brain = new Brain3d(this.scene, options.random, options.level, this.renderer.getPixelRatio());
      this.universe = new Universe3d(this.scene, options.labelHost, options.level, this.renderer.getPixelRatio(),
        options.onTouch);
      this.universe.setSize(options.width, options.height);
      this.panels = new Panels3d(options.labelHost);
      this.effects = new Effects3d(this.scene, options.width < 768, this.renderer.getPixelRatio());
      this.applyPlan(options.plan, start, true);
      this.placeCamera(overviewShot(overviewDistance(options.plan.universe.layout.extent,
        options.width / Math.max(options.height, 1)), options.width < options.height * 0.8), true);
      this.entryTotal = options.entry === 'full' ? ENTRY_FULL_MS : options.entry === 'short' ? ENTRY_SHORT_MS : 0;
      this.gestures = new Gestures(canvas, {
        orbit: (dYaw, dPitch) => this.manual(() => {
          this.rig.yaw.target += dYaw;
          const limit = (PITCH_LIMIT_DEG * Math.PI) / 180;
          this.rig.pitch.target = Math.max(-limit, Math.min(limit, this.rig.pitch.target + dPitch));
        }),
        zoom: (factor) => this.manual(() => {
          this.rig.dist.target = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, this.rig.dist.target * factor));
        }),
        overview: () => this.manual(() => this.placeCamera(overviewShot(this.overview(),
          this.width < this.height * 0.8), false)),
        tap: (x, y) => this.tap(x, y),
        firstTouch: () => this.skipEntry(),
      });
    } else {
      this.heart = null;
      this.brain = null;
      this.universe = null;
      this.panels = null;
      this.effects = null;
    }
    this.post = new JarvisPost(this.renderer, this.scene, this.camera, options.width, options.height, options.level);
    // Le banc (horloge figée) n'adapte pas la qualité : ses captures restent reproductibles.
    this.governor = options.adaptive ? new QualityGovernor(options.level, start) : null;

    this.loop = new FrameLoop(options.frameHost, (now) => this.draw(now));
  }

  get frames(): number {
    return this.loop.frames;
  }

  start(): void {
    if (!this.disposed) {
      this.loop.start();
    }
  }

  resize(width: number, height: number): void {
    if (this.disposed || width <= 0 || height <= 0) {
      return;
    }
    this.renderer.setSize(width, height, false);
    this.post.setSize(width, height);
    this.universe?.setSize(width, height);
    this.width = width;
    this.height = height;
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.backdrop.uniforms['uAspect'].value = width / height;
  }

  update(plan: StagePlan): void {
    if (this.disposed) {
      return;
    }
    this.applyPlan(plan, this.options.clock(), false);
    this.plan = plan;
    this.planDirty = true;
  }

  stats(): { fps: ArrayLike<number>; gestures: number } {
    return { fps: this.fpsHistogram, gestures: this.gestures?.count ?? 0 };
  }

  /** T-E1 sautée au premier toucher : fondu de 300 ms vers l'état final. */
  skipEntry(): void {
    if (this.entryTotal > 0 && this.wallNow - this.entryStart < this.entryTotal - SKIP_FADE_MS) {
      this.entryStart = this.wallNow - (this.entryTotal - SKIP_FADE_MS);
    }
  }

  /** T-S — la sortie (0,5 s). */
  exit(): void {
    if (this.exitStart === null) {
      this.exitStart = this.wallNow;
    }
  }

  /** Un geste : la caméra obéit au PO 8 s, puis revient d'elle-même (T-Z4). */
  private manual(apply: () => void): void {
    apply();
    this.manualUntil = this.wallNow + MANUAL_HOLD_MS;
  }

  /** Toucher bref : le cœur ⇒ parler ; un nœud ⇒ regarder (et un sujet se choisit) ; le vide ⇒ rien. */
  private tap(x: number, y: number): void {
    if (!this.universe) {
      return;
    }
    const node = pickNearest(this.universe.pickables(this.camera, this.width, this.height), x, y);
    if (node) {
      this.options.onTouch({ key: node.key, kind: node.kind, subjectId: node.subjectId });
      if (node.kind !== 'subject') {
        const portrait = this.width < this.height * 0.8;
        const shot = nodeShot({ key: node.key, kind: node.kind, x: node.x, y: node.y, z: node.z }, portrait);
        this.manual(() => this.placeCamera(shot, false, true));
      }
      return;
    }
    // Le cœur : distance au centre projeté, rayon apparent du cœur.
    const core = this.coreScreen();
    if ((x - core.x) ** 2 + (y - core.y) ** 2 <= core.r * core.r) {
      this.options.onTalk();
    }
  }

  /** Le cœur à l'écran : centre projeté et rayon apparent (≥ 28 px). */
  private coreScreen(): { x: number; y: number; r: number } {
    this.projected.set(0, 0, 0).project(this.camera);
    const halfV = Math.tan((CAMERA_FOV * Math.PI) / 360);
    const r = Math.max(28, (HEART_RADIUS / (this.camera.position.length() * halfV)) * (this.height / 2));
    return { x: (this.projected.x + 1) * 0.5 * this.width, y: (1 - this.projected.y) * 0.5 * this.height, r };
  }

  /**
   * SF-236-09 — le nœud que la caméra cadre : celui de la scène en cours (S3 le poste, S4 le client…) s'il est posé,
   * sinon le premier nœud cité (§9.3).
   */
  private focusNode(): Node3d | null {
    const key = this.plan.scene?.focus;
    const view = this.plan.universe;
    return (key ? view.layout.nodes.find((n) => n.key === key) : undefined) ?? view.focus;
  }

  /** SF-236-08 — un nœud à l'écran (ancre d'un panneau) ; `null` s'il n'est pas posé ou derrière la caméra. */
  private project(key: string): { x: number; y: number } | null {
    if (!this.universe || !this.universe.locate(key, this.projected)) {
      return null;
    }
    this.projected.project(this.camera);
    if (this.projected.z > 1) {
      return null;
    }
    return { x: (this.projected.x + 1) * 0.5 * this.width, y: (1 - this.projected.y) * 0.5 * this.height };
  }

  private overview(): number {
    return overviewDistance(this.plan.universe.layout.extent, this.width / Math.max(this.height, 1))
      * (1 + 0.15 * this.brains.frame.spread);
  }

  /** Vise un plan : ressorts vers ses valeurs (`instant` = posé). `world` = coordonnées déjà mondiales. */
  private placeCamera(shot: CameraShot, instant: boolean, world = false): void {
    this.shot = shot;
    this.world.set(shot.x, shot.y, shot.z);
    let yaw = shot.yaw + this.travelYaw;
    if (!world && this.universe && shot.kind === 'node') {
      this.universe.group.updateMatrixWorld();
      this.universe.group.localToWorld(this.world);
      yaw += this.universe.group.rotation.y;
    }
    const r = this.rig;
    r.tx.target = this.world.x;
    r.ty.target = this.world.y;
    r.tz.target = this.world.z;
    r.yaw.target = nearestAngle(r.yaw.value, yaw);
    r.pitch.target = shot.pitch;
    r.dist.target = shot.distance;
    if (instant) {
      for (const spring of [r.tx, r.ty, r.tz, r.yaw, r.pitch, r.dist]) {
        spring.jump(spring.target);
      }
    }
  }

  /** Les cibles du plan ; `opening` = le plan de départ (le banc rejoue ses transitions depuis t = 0). */
  private applyPlan(plan: StagePlan, now: number, opening: boolean): void {
    if (!this.heart) {
      return;
    }
    this.universe?.setView(plan.universe.layout, plan.universe.graph, opening);
    // SF-236-08 — la scène 3D : panneaux (T-P1 / T-P2) et effets ; au banc, tout part de t = 0.
    const replay = opening && this.options.replayFromZero;
    this.panels?.set(plan.scene?.panels ?? [], replay ? 0 : now, replay && plan.bancPanelsOut === true);
    this.effects?.set(plan.scene?.effects ?? [], replay ? 0 : now, replay);
    const travel = plan.scene?.travel;
    if (travel && travel.id !== this.travelId) {
      this.travelId = travel.id;
      this.travelYaw += (travel.degrees * Math.PI) / 180;
      this.planDirty = true;
    }
    if (opening && this.options.replayFromZero) {
      this.heart.setMood('repos', 0, true);
      this.heart.setMood(plan.mood, 0, false);
      this.heart.setWakes(plan.wakes, 0);
      this.brains = new BrainsClock(plan.brainsFromDeployed ? 'deployed' : 'folded');
      this.brains.set(plan.brains.target, plan.brains.variant, 0);
      return;
    }
    if (opening) {
      // À l'ouverture, les cerveaux sont posés tels quels (l'écran apparaît en fondu).
      this.brains = new BrainsClock(plan.brains.target);
    } else {
      this.brains.set(plan.brains.target, plan.brains.variant, now);
    }
    this.heart.setMood(plan.mood, now, opening);
    if (opening) {
      this.heart.setWakes(0, now);
    }
    this.heart.setWakes(plan.wakes, now);
  }

  /** T-Q — nouveau niveau : ratio de pixels, bloom, poussière et densité du cœur, en fondu. */
  private applyLevel(level: QualityLevel, now: number): void {
    this.level = level;
    const budget = LEVEL_BUDGET[level];
    this.renderer.setPixelRatio(Math.min(this.options.pixelRatio, budget.pixelRatio));
    this.post.setLevel(level);
    this.heart?.setLevel(level, now);
    this.dustOpacity.set(budget.dust > 0 ? HOLO_ALPHA.panel : 0, now, MOTION_DURATIONS.quality, 'glisse');
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.loop.stop();
    const canvas = this.options.canvas;
    canvas.removeEventListener('webglcontextlost', this.onLost);
    canvas.removeEventListener('webglcontextrestored', this.onRestored);
    this.scene.traverse((object: Object3D) => {
      const withGeometry = object as Object3D & { geometry?: BufferGeometry; material?: Material | Material[] };
      withGeometry.geometry?.dispose();
      const material = withGeometry.material;
      if (Array.isArray(material)) {
        material.forEach((m) => m.dispose());
      } else {
        material?.dispose();
      }
    });
    this.scene.clear();
    this.gestures?.dispose();
    this.panels?.dispose();
    this.effects?.dispose();
    this.universe?.dispose();
    this.post.dispose();
    this.renderer.dispose();
    this.renderer.forceContextLoss();
  }

  private draw(frameNow: number): void {
    const t = this.options.clock();
    const frameMs = this.lastNow === null ? 0 : frameNow - this.lastNow;
    this.lastNow = frameNow;
    if (frameMs > 0 && frameMs < 250) {
      this.fpsHistogram[fpsClass(1000 / frameMs)]++;
    }
    if (this.governor && frameMs > 0) {
      const verdict = this.governor.sample(frameMs, frameNow);
      if (verdict.kind === 'change') {
        this.applyLevel(verdict.level, t);
        this.options.onQualityChange(verdict.level);
      } else if (verdict.kind === 'fallback2d') {
        this.options.onLowPerf();
      }
    }
    for (const material of this.timed) {
      material.uniforms['uTime'].value = t;
    }
    if (this.dustMaterial) {
      this.dustMaterial.uniforms['uOpacity'].value = this.dustOpacity.value(t);
    }
    let bloom = 1.1;
    let light = 0.8;
    let spread = 0;
    if (this.heart && this.brain) {
      const dt = Math.min(Math.max(frameMs, 0), MAX_DT_MS);
      const f = this.brains.at(t);
      this.heart.group.scale.setScalar(f.coreScale);
      this.heart.lightGain = f.coreLight;
      this.heart.flash = f.flash;
      this.heart.frame(t, dt, this.options.voiceLevel());
      this.brain.frame(dt, f, this.plan.brains.pearls, this.brains.current === 'deployed' ? 'normal'
        : this.plan.brains.variant);
      bloom = this.heart.bloom.value(t) + f.bloom;
      light = this.heart.light;
      spread = f.spread;
      // Le fond et le sol virent au violet quand le grand cerveau travaille.
      this.backdrop.uniforms['uDeepMix'].value = f.hue;
      if (this.floorMaterial) {
        this.floorMaterial.color.setRGB(
          this.floorNormal[0] + (this.floorDeep[0] - this.floorNormal[0]) * f.hue,
          this.floorNormal[1] + (this.floorDeep[1] - this.floorNormal[1]) * f.hue,
          this.floorNormal[2] + (this.floorDeep[2] - this.floorNormal[2]) * f.hue);
      }
    }
    // SF-236-05 — l'entrée (T-E1 / T-E2) et la sortie (T-S), sur l'horloge murale de l'écran.
    this.wallNow = frameNow;
    if (!this.firstFrameSeen) {
      this.entryStart = frameNow;
    }
    const entry = entryFrame(this.entryTotal === ENTRY_FULL_MS ? 'full' : this.entryTotal > 0 ? 'short' : 'none',
      this.options.replayFromZero ? t : frameNow - this.entryStart);
    const exit = this.exitStart === null ? null : exitFrame(Math.min(EXIT_MS, frameNow - this.exitStart));
    this.backdrop.uniforms['uGlow'].value = (0.35 + 0.65 * light) * entry.light;
    if (this.heart) {
      this.heart.group.scale.multiplyScalar(Math.max(0.001, entry.core * (exit ? exit.core : 1)));
    }
    if (this.floorMaterial) {
      this.floorMaterial.opacity = HOLO_DIM_OPACITY * entry.orbits;
    }
    const dt = Math.min(Math.max(frameMs, 0), MAX_DT_MS);
    if (this.universe) {
      this.universe.reveal = entry.clientAt;
      this.universe.frame(dt);
      this.universe.group.scale.setScalar((1 + 0.15 * spread) * (exit ? Math.max(0.001, exit.universe) : 1));
      // Dérive lente (un tour en 10 min), arrêtée pendant l'écoute et sur un focus (§8.1).
      if (!this.plan.universe.freeze && this.options.adaptive) {
        this.universe.group.rotation.y += (dt / 600_000) * Math.PI * 2;
      }
    }
    this.moveCamera(frameNow, dt, entry.distance);
    if (this.effects && this.universe) {
      const universe = this.universe;
      this.camera.updateMatrixWorld();
      this.effects.frame(t, this.camera, (key, out) => universe.locate(key, out));
    }
    if (this.panels && this.panels.count > 0) {
      // Seulement quand un panneau est affiché : le placement (DOM) n'existe pas au repos.
      this.panels.frame(t, (key) => this.project(key), panelViewport(this.width, this.height, this.width < 768,
        this.coreScreen()));
    }
    this.post.render(bloom);
    this.universe?.renderLabels(this.scene, this.camera);
    if (!this.firstFrameSeen) {
      this.firstFrameSeen = true;
      this.options.onFirstFrame();
    }
  }

  /** SF-236-05 — le directeur décide (à chaque plan, et toutes les 250 ms) ; les ressorts rejoignent son plan. */
  private moveCamera(now: number, dt: number, entryDistance: number): void {
    if (this.options.replayFromZero) {
      this.bancCamera(entryDistance);
      return;
    }
    const manual = now < this.manualUntil;
    const waiting = this.plan.mood === 'oui';
    if (this.planDirty || now - this.lastDecide >= 250) {
      this.planDirty = false;
      this.lastDecide = now;
      const view = this.plan.universe;
      const portrait = this.width < this.height * 0.8;
      const shot = this.director.decide({
        focus: this.focusNode(), subjectId: view.subjectId, listening: this.plan.mood === 'ecoute', waiting,
        overview: this.overview(), portrait,
      }, now);
      // Pendant un geste, les ordres attendent — sauf l'autorisation S2, toujours devant.
      if (!manual || waiting) {
        this.placeCamera(shot, false);
      }
    }
    const r = this.rig;
    r.tx.step(dt);
    r.ty.step(dt);
    r.tz.step(dt);
    r.yaw.step(dt);
    r.pitch.step(dt);
    r.dist.step(dt);
    const d = r.dist.value * entryDistance;
    const cp = Math.cos(r.pitch.value);
    // Aucun roulis : la caméra garde l'axe vertical (lookAt, « up » fixe) ; focale fixe.
    this.camera.position.set(
      r.tx.value + Math.sin(r.yaw.value) * cp * d,
      r.ty.value + Math.sin(r.pitch.value) * d,
      r.tz.value + Math.cos(r.yaw.value) * cp * d);
    this.camera.lookAt(r.tx.value, r.ty.value, r.tz.value);
  }

  /**
   * Banc : la caméra est posée une fois pour toutes à l'instant figé `t` — un zoom (T-Z1) ou un dézoom (T-Z2) est
   * rejoué par pas de 16 ms depuis t = 0, d'où des séquences de captures reproductibles.
   */
  private bancPrimed = false;

  private bancCamera(entryDistance: number): void {
    if (!this.bancPrimed) {
      this.bancPrimed = true;
      const view = this.plan.universe;
      const portrait = this.width < this.height * 0.8;
      const wide = overviewShot(this.overview(), portrait);
      const focus = this.focusNode();
      const close = focus ? nodeShot(focus, portrait) : wide;
      const [from, to] = this.plan.bancCamera === 'dezoom' ? [close, wide] : this.plan.bancCamera === 'zoom'
        ? [wide, close] : [close, close];
      this.placeCamera(from, true);
      this.placeCamera(to, false);
      const r = this.rig;
      for (let elapsed = 0; elapsed < this.options.clock(); elapsed += 16) {
        for (const spring of [r.tx, r.ty, r.tz, r.yaw, r.pitch, r.dist]) {
          spring.step(16);
        }
      }
    }
    const r = this.rig;
    const d = r.dist.value * entryDistance;
    const cp = Math.cos(r.pitch.value);
    this.camera.position.set(
      r.tx.value + Math.sin(r.yaw.value) * cp * d,
      r.ty.value + Math.sin(r.pitch.value) * d,
      r.tz.value + Math.cos(r.yaw.value) * cp * d);
    this.camera.lookAt(r.tx.value, r.ty.value, r.tz.value);
  }

  /** L0 — lueur d'abysse sous le cœur et nébuleuse procédurale très lente. */
  private buildBackdrop(nebula: boolean): ShaderMaterial {
    const material = new ShaderMaterial({
      vertexShader: BACKDROP_VERTEX,
      fragmentShader: BACKDROP_FRAGMENT,
      depthTest: false,
      depthWrite: false,
      uniforms: {
        uTime: { value: 0 },
        uAspect: { value: this.options.width / Math.max(this.options.height, 1) },
        uNebula: { value: nebula ? 1 : 0 },
        uFog: { value: rgb(HOLO_COLORS.fog) },
        uAbyss: { value: rgb(HOLO_COLORS.abyss) },
        uTint: { value: rgb(HOLO_COLORS.normal) },
        uDeep: { value: rgb(HOLO_COLORS.deep) },
        uGlow: { value: 0.8 },
        uDeepMix: { value: 0 },
      },
    });
    const quad = new Mesh(new PlaneGeometry(2, 2), material);
    quad.frustumCulled = false;
    quad.renderOrder = -10;
    this.scene.add(quad);
    this.timed.push(material);
    return material;
  }

  /** L0 — étoiles sur trois profondeurs (une seule au niveau Bas). */
  private buildStars(layers: number, perLayer: number): void {
    const radii = [60, 38, 24];
    const sizes = [2.6, 3.2, 3.8];
    const cream = rgb('#F5EFE3');
    const warm = rgb(HOLO_COLORS.text);
    for (let layer = 0; layer < layers; layer++) {
      const random = this.options.random;
      this.addPoints(perLayer, (i, pos, col, size, phase) => {
        const u = random() * 2 - 1;
        const theta = random() * Math.PI * 2;
        const s = Math.sqrt(1 - u * u);
        const r = radii[layer] * (0.85 + random() * 0.3);
        pos[i * 3] = r * s * Math.cos(theta);
        pos[i * 3 + 1] = r * u;
        pos[i * 3 + 2] = r * s * Math.sin(theta) - 10;
        const mix = random() * 0.35;
        for (let c = 0; c < 3; c++) {
          col[i * 3 + c] = (cream[c] * (1 - mix) + warm[c] * mix) * (0.55 + random() * 0.45);
        }
        size[i] = sizes[layer] * (0.6 + random() * 0.8);
        phase[i] = random() * 10;
      }, { opacity: 1, drift: 0, breath: 0, scale: 46 });
    }
  }

  /** L0 — poussière en suspension qui accroche la lumière près du cœur. */
  private buildDust(count: number): void {
    const random = this.options.random;
    const orange = rgb(HOLO_COLORS.normal);
    this.addPoints(count, (i, pos, col, size, phase) => {
      const r = 1.8 + random() * 6;
      const a = random() * Math.PI * 2;
      pos[i * 3] = Math.cos(a) * r;
      pos[i * 3 + 1] = (random() - 0.5) * 4.5;
      pos[i * 3 + 2] = Math.sin(a) * r;
      const glow = 0.45 + 0.55 * Math.max(0, 1 - r / 8);
      for (let c = 0; c < 3; c++) {
        col[i * 3 + c] = orange[c] * glow;
      }
      size[i] = 1.6 + random() * 2.2;
      phase[i] = random() * 10;
    }, { opacity: HOLO_ALPHA.panel, drift: 0.35, breath: 0, scale: 16 });
    this.dustMaterial = this.lastPoints;
  }

  /** L1 — sol polaire : cercles gradués et rayons, orange atténué. */
  private buildFloor(rings: number): void {
    const segments = 96;
    const rays = 24;
    const vertices: number[] = [];
    for (let ring = 1; ring <= rings; ring++) {
      const r = ring * 1.1;
      for (let s = 0; s < segments; s++) {
        const a0 = (s / segments) * Math.PI * 2;
        const a1 = ((s + 1) / segments) * Math.PI * 2;
        vertices.push(Math.cos(a0) * r, 0, Math.sin(a0) * r, Math.cos(a1) * r, 0, Math.sin(a1) * r);
      }
    }
    const outer = rings * 1.1;
    for (let k = 0; k < rays; k++) {
      const a = (k / rays) * Math.PI * 2;
      vertices.push(Math.cos(a) * 1.1, 0, Math.sin(a) * 1.1, Math.cos(a) * outer, 0, Math.sin(a) * outer);
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(vertices), 3));
    const material = new LineBasicMaterial({
      color: HOLO_COLORS.normalDim, transparent: true, opacity: HOLO_DIM_OPACITY,
      blending: AdditiveBlending, depthWrite: false,
    });
    const floor = new LineSegments(geometry, material);
    this.floorMaterial = material;
    floor.position.y = -2.4;
    this.scene.add(floor);
  }

  private addPoints(
    count: number,
    fill: (i: number, pos: Float32Array, col: Float32Array, size: Float32Array, phase: Float32Array) => void,
    look: { opacity: number; drift: number; breath: number; scale: number },
  ): void {
    const pos = new Float32Array(count * 3);
    const col = new Float32Array(count * 3);
    const size = new Float32Array(count);
    const phase = new Float32Array(count);
    for (let i = 0; i < count; i++) {
      fill(i, pos, col, size, phase);
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(pos, 3));
    geometry.setAttribute('aColor', new BufferAttribute(col, 3));
    geometry.setAttribute('aSize', new BufferAttribute(size, 1));
    geometry.setAttribute('aPhase', new BufferAttribute(phase, 1));
    const material = new ShaderMaterial({
      vertexShader: POINTS_VERTEX,
      fragmentShader: POINTS_FRAGMENT,
      transparent: true,
      depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uTime: { value: 0 },
        uPixelRatio: { value: this.renderer.getPixelRatio() },
        uOpacity: { value: look.opacity },
        uDrift: { value: look.drift },
        uBreath: { value: look.breath },
        uScale: { value: look.scale },
      },
    });
    this.scene.add(new Points(geometry, material));
    this.timed.push(material);
    this.lastPoints = material;
  }
}
