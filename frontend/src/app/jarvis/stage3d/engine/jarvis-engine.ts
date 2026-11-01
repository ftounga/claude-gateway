import {
  AdditiveBlending, BufferAttribute, BufferGeometry, ColorManagement, LineBasicMaterial, LineSegments,
  LinearSRGBColorSpace, Material, Mesh, Object3D, PerspectiveCamera, PlaneGeometry, Points, Scene, ShaderMaterial,
  WebGLRenderer,
} from 'three';

import { FrameLoop } from '../jarvis-frame-loop';
import { JarvisEngine, JarvisEngineOptions } from '../jarvis-engine.contract';
import { HOLO_ALPHA, HOLO_COLORS, HOLO_DIM_OPACITY, MOTION_DURATIONS, rgb } from '../jarvis-motion';
import { QualityGovernor } from '../jarvis-quality';
import { QualityLevel } from '../jarvis-render-capability';
import { StagePlan } from '../jarvis-stage-director';
import { Tween } from '../jarvis-tween';
import { Heart3d } from './jarvis-heart-3d';
import { JarvisPost } from './jarvis-post';

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
  vec3 color = mix(mix(uFog, uAbyss, uGlow), uFog, smoothstep(0.0, 0.85, r));
  if (uNebula > 0.5) {
    float t = uTime * 0.000012;
    float n = fbm(p * 2.2 + vec2(t, -t * 0.7));
    float m = fbm(p * 4.0 - vec2(t * 0.5, t));
    float veil = smoothstep(0.35, 0.8, n) * (1.0 - smoothstep(0.15, 1.25, r));
    color += uTint * veil * 0.30 + uDeep * smoothstep(0.5, 0.9, m) * veil * 0.14;
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
  vTwinkle = 0.72 + 0.28 * sin(uTime * 0.0016 + aPhase * 6.2831);
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

    this.camera = new PerspectiveCamera(50, options.width / Math.max(options.height, 1), 0.1, 200);
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
      this.applyPlan(options.plan, start, true);
    } else {
      this.heart = null;
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
  }

  /** Les cibles du plan ; `opening` = le plan de départ (le banc rejoue ses transitions depuis t = 0). */
  private applyPlan(plan: StagePlan, now: number, opening: boolean): void {
    if (!this.heart) {
      return;
    }
    if (opening && this.options.replayFromZero) {
      this.heart.setMood('repos', 0, true);
      this.heart.setMood(plan.mood, 0, false);
      this.heart.setWakes(plan.wakes, 0);
      return;
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
    this.post.dispose();
    this.renderer.dispose();
    this.renderer.forceContextLoss();
  }

  private draw(frameNow: number): void {
    const t = this.options.clock();
    const frameMs = this.lastNow === null ? 0 : frameNow - this.lastNow;
    this.lastNow = frameNow;
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
    if (this.heart) {
      this.heart.frame(t, Math.min(Math.max(frameMs, 0), MAX_DT_MS), this.options.voiceLevel());
      bloom = this.heart.bloom.value(t);
      light = this.heart.light;
    }
    this.backdrop.uniforms['uGlow'].value = 0.35 + 0.65 * light;
    // Dérive lente de la caméra (parallaxe des calques) : aucun roulis, focale fixe (§7.3, règle 8).
    this.camera.position.x = Math.sin(t * 0.00005) * 0.35;
    this.camera.position.y = 0.6 + Math.sin(t * 0.000037) * 0.12;
    this.camera.lookAt(0, 0, 0);
    this.post.render(bloom);
    if (!this.firstFrameSeen) {
      this.firstFrameSeen = true;
      this.options.onFirstFrame();
    }
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
