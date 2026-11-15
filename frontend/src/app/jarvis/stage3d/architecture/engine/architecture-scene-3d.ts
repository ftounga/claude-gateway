import {
  AdditiveBlending, BoxGeometry, BufferAttribute, BufferGeometry, CanvasTexture, Color, ColorManagement,
  CustomBlending, DynamicDrawUsage, Group, InstancedBufferAttribute, InstancedMesh, LineBasicMaterial, LineSegments,
  LinearFilter, LinearSRGBColorSpace, Matrix4, Mesh, Object3D, OneFactor, OneMinusSrcAlphaFactor, PerspectiveCamera,
  PlaneGeometry, Points, Quaternion, Scene, ShaderMaterial, Vector3, WebGLRenderer,
} from 'three';
import { LineMaterial } from 'three/examples/jsm/lines/LineMaterial.js';
import { LineSegments2 } from 'three/examples/jsm/lines/LineSegments2.js';
import { LineSegmentsGeometry } from 'three/examples/jsm/lines/LineSegmentsGeometry.js';
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js';
import type { InterleavedBufferAttribute } from 'three';

import { EngineClock } from '../../jarvis-banc';
import { FrameHost, FrameLoop } from '../../jarvis-frame-loop';
import { HOLO_COLORS, HoloRole, rgb } from '../../jarvis-motion';
import { QualityLevel } from '../../jarvis-render-capability';
import { levelBudget } from '../../hud/jarvis-device-profile';
import { JarvisPost } from '../../engine/jarvis-post';
import { ArchitectureIconSource } from '../architecture-icons';
import {
  ArchLayout, ICON_SIZE, pathPoints, writeEdgePath, writeParticles,
} from '../architecture-layout-3d';
import {
  ArchFrame, ArchPhaseKind, ArchState, ArchitecturePlan, ArchitectureStageDirector, Viewport, cameraBasis,
} from '../architecture-stage-director';

/**
 * F-238 / SF-238-09 — **la scène d'architecture en 3D** (cadrage §9, §10, §14). Seul fichier `three` de la SF,
 * chargé paresseusement. Tout est instancié : plateaux, socles et halos, ombres, icônes = un `InstancedMesh`
 * chacun ; liserés (continus / pointillés) et filaments (noyau / halo) = un `LineSegments2` chacun ; particules =
 * un pool fixe ; décor (fond, étoiles et poussière, sol polaire) — ≤ 20 appels de dessin pour 60 services. À chaque
 * image : le directeur écrit l'image en place, la scène recopie dans des tampons fixes ; <b>0 allocation</b>.
 */

/** Particules de flux au plus (cadrage §14). */
export const PARTICLES_PC = 2000;
export const PARTICLES_PHONE = 400;
/** Étiquettes CSS2D au plus (cadrage §14). */
export const LABELS_PC = 48;
export const LABELS_PHONE = 16;

const PLATE_THICKNESS = 0.04;
/** Intensité du bloom F-236 : liserés, filaments et particules brillent, sans noyer les icônes. */
const BLOOM_STRENGTH = 0.75;
const SOCKET_RADIUS = 0.42;
const HALO_RADIUS = 0.78;

const PLATE_VERTEX = /* glsl */ `
varying vec2 vUv;
varying vec3 vColor;
varying vec2 vWorld;
varying float vTop;
void main() {
  vUv = uv;
  vColor = vec3(1.0);
#ifdef USE_INSTANCING_COLOR
  vColor = instanceColor;
#endif
  vec4 world = modelMatrix * instanceMatrix * vec4(position, 1.0);
  vWorld = world.xz;
  vTop = step(0.5, normal.y);
  gl_Position = projectionMatrix * viewMatrix * world;
}`;

// Verre holographique : fond faible, bords qui s'illuminent, trame discrète (pas de 32 px du SVG) sur le dessus.
const PLATE_FRAGMENT = /* glsl */ `
varying vec2 vUv;
varying vec3 vColor;
varying vec2 vWorld;
varying float vTop;
void main() {
  vec2 c = abs(vUv - 0.5) * 2.0;
  float edge = pow(max(c.x, c.y), 8.0);
  vec2 g = abs(fract(vWorld / 0.32) - 0.5);
  float grid = (1.0 - smoothstep(0.0, 0.04, min(g.x, g.y))) * 0.35 * vTop;
  float k = vTop > 0.5 ? (0.55 + 1.6 * edge + grid) : 1.4;
  gl_FragColor = vec4(vColor * k, 1.0);
}`;

const DISC_VERTEX = /* glsl */ `
attribute float aKind;
varying vec2 vPos;
varying vec3 vColor;
varying float vKind;
void main() {
  vPos = position.xz;
  vKind = aKind;
  vColor = vec3(1.0);
#ifdef USE_INSTANCING_COLOR
  vColor = instanceColor;
#endif
  gl_Position = projectionMatrix * viewMatrix * modelMatrix * instanceMatrix * vec4(position, 1.0);
}`;

// Socle (aKind 0) : anneau net et disque intérieur ; halo (aKind 1) : lueur douce ; ombre (aKind 2) : tache navy.
const DISC_FRAGMENT = /* glsl */ `
uniform vec3 uShadow;
varying vec2 vPos;
varying vec3 vColor;
varying float vKind;
void main() {
  float r = length(vPos);
  if (r > 1.0) discard;
  if (vKind > 1.5) {
    float a = (1.0 - smoothstep(0.0, 1.0, r));
    a = a * a * 0.55 * vColor.r;
    gl_FragColor = vec4(uShadow * a, a);
    return;
  }
  float v;
  if (vKind < 0.5) {
    v = smoothstep(0.78, 0.86, r) * (1.0 - smoothstep(0.9, 1.0, r)) * 1.2 + (1.0 - smoothstep(0.0, 0.86, r)) * 0.16;
  } else {
    float s = 1.0 - smoothstep(0.0, 1.0, r);
    v = s * s * 0.35;
  }
  gl_FragColor = vec4(vColor * v, 0.0);
}`;

// Icônes : quads toujours face à la caméra (sprites instanciés), une cellule de l'atlas chacun, non recolorées.
const ICON_VERTEX = /* glsl */ `
attribute float aCell;
attribute float aAlpha;
uniform vec2 uGrid;
varying vec2 vUv;
varying float vAlpha;
void main() {
  mat4 m = modelMatrix * instanceMatrix;
  vec4 center = viewMatrix * m * vec4(0.0, 0.0, 0.0, 1.0);
  float s = length(m[0].xyz);
  center.xy += position.xy * s;
  gl_Position = projectionMatrix * center;
  float col = mod(aCell, uGrid.x);
  float row = floor(aCell / uGrid.x);
  vUv = (vec2(col, uGrid.y - 1.0 - row) + uv) / uGrid;
  vAlpha = aAlpha;
}`;

const ICON_FRAGMENT = /* glsl */ `
uniform sampler2D uMap;
varying vec2 vUv;
varying float vAlpha;
void main() {
  vec4 t = texture2D(uMap, vUv);
  if (t.a * vAlpha < 0.02) discard;
  gl_FragColor = vec4(t.rgb, t.a * vAlpha);
}`;

const SPARK_VERTEX = /* glsl */ `
attribute float aGlow;
uniform float uPixelRatio;
uniform float uSize;
varying float vGlow;
void main() {
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = min(uSize * uPixelRatio * (10.0 / max(-mv.z, 0.5)), 22.0 * uPixelRatio);
  vGlow = aGlow;
}`;

const SPARK_FRAGMENT = /* glsl */ `
uniform vec3 uColor;
uniform vec3 uCore;
uniform float uOpacity;
varying float vGlow;
void main() {
  float d = length(gl_PointCoord - 0.5) * 2.0;
  float a = 1.0 - smoothstep(0.0, 1.0, d);
  a = a * a;
  vec3 c = mix(uColor, uCore, smoothstep(0.55, 1.0, a));
  gl_FragColor = vec4(c * a * (0.35 + 0.65 * vGlow) * uOpacity, 1.0);
}`;

const BACKDROP_VERTEX = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.999, 1.0);
}`;

// Le fond de l'univers F-236 (lueur d'abysse, nébuleuse lente) : même grammaire, dessiné une fois par image.
const BACKDROP_FRAGMENT = /* glsl */ `
uniform float uTime;
uniform float uAspect;
uniform float uNebula;
uniform vec3 uFog;
uniform vec3 uAbyss;
uniform vec3 uTint;
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
  vec2 p = (vUv - vec2(0.5, 0.45)) * vec2(uAspect, 1.0);
  float r = length(p);
  vec3 color = mix(mix(uFog, uAbyss, 0.85), uFog, smoothstep(0.0, 0.9, r));
  if (uNebula > 0.5) {
    float t = uTime * 0.000012;
    float n = fbm(p * 2.2 + vec2(t, -t * 0.7));
    float veil = smoothstep(0.35, 0.8, n) * smoothstep(0.3, 0.65, r) * (1.0 - smoothstep(0.65, 1.5, r));
    color += uTint * veil * 0.14;
  }
  gl_FragColor = vec4(color, 1.0);
}`;

const STARS_VERTEX = /* glsl */ `
attribute float aSize;
attribute vec3 aColor;
uniform float uPixelRatio;
uniform float uTime;
varying vec3 vColor;
void main() {
  vec3 p = position + 0.25 * vec3(sin(uTime * 0.00021 + position.z), cos(uTime * 0.00017 + position.x), 0.0);
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = min(aSize * uPixelRatio * (30.0 / max(-mv.z, 1.0)), 6.0 * uPixelRatio);
  vColor = aColor;
}`;

const STARS_FRAGMENT = /* glsl */ `
varying vec3 vColor;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.0, 0.5, d);
  gl_FragColor = vec4(vColor * a * a, 1.0);
}`;

export interface ArchitectureSceneOptions {
  layout: ArchLayout;
  icons: ArchitectureIconSource;
  level: QualityLevel;
  phone: boolean;
  pixelRatio: number;
  random: () => number;
  /** Où poser les étiquettes DOM ; `null` = aucune (tests). */
  labelHost: HTMLElement | null;
}

interface Label {
  object: CSS2DObject;
  element: HTMLElement;
  /** Opacité affichée (quantifiée au vingtième : le DOM n'est touché qu'au changement). */
  shown: number;
  lit: boolean;
}

/**
 * Le graphe de scène (sans rendu) : construit les objets `three` et les met à jour depuis une {@link ArchFrame}.
 * Séparé du rendu pour être compté et testé sans WebGL.
 */
export class ArchitectureSceneGraph {
  readonly scene = new Scene();
  readonly world = new Group();
  readonly director: ArchitectureStageDirector;
  private readonly palette = new Map<HoloRole, [number, number, number]>();
  private readonly plates: InstancedMesh;
  private readonly discs: InstancedMesh;
  private readonly icons: InstancedMesh;
  private readonly lines: LineSegments2;
  private readonly dashes: LineSegments2;
  private readonly filaments: LineSegments2;
  private readonly glows: LineSegments2;
  private readonly sparks: Points;
  readonly backdrop: ShaderMaterial;
  private readonly stars: ShaderMaterial;
  readonly lineMaterials: LineMaterial[] = [];
  private readonly sparkMaterial: ShaderMaterial;
  private readonly iconAlpha: InstancedBufferAttribute;
  private readonly texture: CanvasTexture;
  /** Chemins 3D des liaisons (un tampon par liaison, écrit en place) et longueurs. */
  private readonly paths: Float32Array[];
  private readonly lengths: Float32Array;
  private readonly sparkPositions: Float32Array;
  private readonly sparkGlow: Float32Array;
  private readonly maxSparks: number;
  private readonly matrix = new Matrix4();
  private readonly basis = new Matrix4();
  private readonly stand = new Quaternion();
  private readonly flat = new Quaternion();
  private readonly labels: Label[] = [];
  private readonly nodeLabels: (Label | null)[];
  private readonly plateLabels: (Label | null)[];
  private readonly solidPlates: number[];
  private readonly dashedPlates: number[];
  private sparkCount = 0;
  readonly frame: ArchFrame;

  constructor(private readonly options: ArchitectureSceneOptions) {
    const { layout } = options;
    this.director = new ArchitectureStageDirector(layout);
    this.frame = this.director.newFrame();
    for (const role of Object.keys(HOLO_COLORS) as HoloRole[]) {
      this.palette.set(role, rgb(HOLO_COLORS[role]));
    }
    const budget = levelBudget(options.level, options.phone);
    this.scene.add(this.world);

    // Décor (cadrage §10, « décor riche ») : fond, étoiles et poussière, sol polaire sous le plateau racine.
    this.backdrop = new ShaderMaterial({
      vertexShader: BACKDROP_VERTEX, fragmentShader: BACKDROP_FRAGMENT, depthTest: false, depthWrite: false,
      uniforms: {
        uTime: { value: 0 }, uAspect: { value: 1 }, uNebula: { value: budget.nebula ? 1 : 0 },
        uFog: { value: rgb(HOLO_COLORS.fog) }, uAbyss: { value: rgb(HOLO_COLORS.abyss) },
        uTint: { value: rgb(HOLO_COLORS.normal) },
      },
    });
    const backdrop = new Mesh(new PlaneGeometry(2, 2), this.backdrop);
    backdrop.frustumCulled = false;
    backdrop.renderOrder = -10;
    this.scene.add(backdrop);
    this.stars = this.buildStars(budget.starsPerLayer + budget.dust);
    this.buildFloor(budget.floorRings);

    // Plateaux : une dalle de verre instanciée par zone.
    const plateGeometry = new BoxGeometry(1, 1, 1);
    this.plates = new InstancedMesh(plateGeometry, new ShaderMaterial({
      vertexShader: PLATE_VERTEX, fragmentShader: PLATE_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
    }), Math.max(1, layout.plates.length));
    this.plates.count = layout.plates.length;
    this.plates.setColorAt(0, this.color(0, 0, 0));
    this.plates.frustumCulled = false;
    this.plates.renderOrder = 1;
    this.world.add(this.plates);

    // Socles, halos et ombres : un disque instancié, trois sortes (aKind), 3 instances par service.
    const n = layout.nodes.length;
    const discGeometry = new PlaneGeometry(2, 2);
    discGeometry.rotateX(-Math.PI / 2);
    const kinds = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      kinds[i * 3] = 2;
      kinds[i * 3 + 1] = 1;
      kinds[i * 3 + 2] = 0;
    }
    discGeometry.setAttribute('aKind', new InstancedBufferAttribute(kinds, 1));
    this.discs = new InstancedMesh(discGeometry, new ShaderMaterial({
      vertexShader: DISC_VERTEX, fragmentShader: DISC_FRAGMENT, transparent: true, depthWrite: false,
      // Alpha prémultiplié : l'ombre (alpha > 0) assombrit, socles et halos (alpha 0) s'ajoutent — un seul appel.
      blending: CustomBlending, blendSrc: OneFactor, blendDst: OneMinusSrcAlphaFactor,
      uniforms: { uShadow: { value: rgb(HOLO_COLORS.fog) } },
    }), n * 3);
    this.discs.setColorAt(0, this.color(0, 0, 0));
    this.discs.frustumCulled = false;
    this.discs.renderOrder = 3;
    this.world.add(this.discs);

    // Icônes : quads instanciés + atlas (une texture, un appel).
    const icons = options.icons;
    this.texture = new CanvasTexture(icons.image);
    this.texture.minFilter = LinearFilter;
    this.texture.generateMipmaps = false;
    const iconGeometry = new PlaneGeometry(1, 1);
    const cells = new Float32Array(n);
    layout.nodes.forEach((node, i) => (cells[i] = icons.cellOf(node)));
    iconGeometry.setAttribute('aCell', new InstancedBufferAttribute(cells, 1));
    this.iconAlpha = new InstancedBufferAttribute(new Float32Array(n), 1);
    this.iconAlpha.setUsage(DynamicDrawUsage);
    iconGeometry.setAttribute('aAlpha', this.iconAlpha);
    this.icons = new InstancedMesh(iconGeometry, new ShaderMaterial({
      vertexShader: ICON_VERTEX, fragmentShader: ICON_FRAGMENT, transparent: true, depthWrite: false,
      uniforms: { uMap: { value: this.texture }, uGrid: { value: [icons.columns, icons.rows] } },
    }), n);
    this.icons.frustumCulled = false;
    this.icons.renderOrder = 5;
    this.world.add(this.icons);

    // Liserés des plateaux (continus / pointillés selon le `kind`) + colonnes lumineuses socle → icône.
    this.solidPlates = layout.plates.filter((p) => !p.dashed).map((p) => p.index);
    this.dashedPlates = layout.plates.filter((p) => p.dashed).map((p) => p.index);
    const lineWidth = options.phone ? 1.6 : 1.4;
    this.lines = this.buildLines(this.solidPlates.length * 4 + n, { linewidth: lineWidth });
    this.dashes = this.buildLines(this.dashedPlates.length * 4, {
      linewidth: lineWidth, dashed: true, dashSize: 0.12, gapSize: 0.08,
    });
    this.writePlateOutlines();
    this.dashes.computeLineDistances();

    // Filaments : noyau clair fin + halo orange large, segments orthogonaux.
    this.paths = layout.edges.map((e) => new Float32Array(pathPoints(e) * 3));
    this.lengths = new Float32Array(layout.edges.length);
    const segments = layout.edges.reduce((sum, e) => sum + pathPoints(e) - 1, 0);
    this.filaments = this.buildLines(segments, { linewidth: options.phone ? 1.8 : 1.6 });
    this.glows = this.buildLines(segments, { linewidth: options.phone ? 5 : 6, opacity: 0.4 });

    // Particules : pool fixe.
    this.maxSparks = options.phone ? PARTICLES_PHONE : PARTICLES_PC;
    this.sparkPositions = new Float32Array(this.maxSparks * 3);
    this.sparkGlow = new Float32Array(this.maxSparks);
    const sparkGeometry = new BufferGeometry();
    sparkGeometry.setAttribute('position', new BufferAttribute(this.sparkPositions, 3).setUsage(DynamicDrawUsage));
    sparkGeometry.setAttribute('aGlow', new BufferAttribute(this.sparkGlow, 1).setUsage(DynamicDrawUsage));
    sparkGeometry.setDrawRange(0, 0);
    this.sparkMaterial = new ShaderMaterial({
      vertexShader: SPARK_VERTEX, fragmentShader: SPARK_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uPixelRatio: { value: options.pixelRatio }, uSize: { value: options.phone ? 3.2 : 2.8 },
        uColor: { value: rgb(HOLO_COLORS.normal) }, uCore: { value: rgb(HOLO_COLORS.core) }, uOpacity: { value: 1 },
      },
    });
    this.sparks = new Points(sparkGeometry, this.sparkMaterial);
    this.sparks.frustumCulled = false;
    this.sparks.renderOrder = 6;
    this.world.add(this.sparks);

    // Étiquettes (DOM, `textContent`, jamais `innerHTML`).
    const cap = options.phone ? LABELS_PHONE : LABELS_PC;
    // Les services d'abord (le contenu), puis les zones dans la limite du budget.
    this.nodeLabels = layout.nodes.map((node) => (this.labels.length < cap
      ? this.makeLabel(node.label, 'service', node.x, node.z + ICON_SIZE / 2 + 0.04) : null));
    this.plateLabels = layout.plates.map((p) => (this.labels.length < cap
      ? this.makeLabel(p.label, 'zone', p.x0 + 0.06, p.z0 + 0.04) : null));
  }

  /** Nombre d'objets dessinés à l'image (un appel chacun : aucune matière multiple). */
  drawables(): number {
    let count = 0;
    this.scene.traverseVisible((o: Object3D) => {
      const m = o as Mesh;
      if ((m as unknown as { isMesh?: boolean }).isMesh || (o as Points).isPoints
          || (o as LineSegments).isLine || (o as LineSegments2).isLineSegments2) {
        if (!(o instanceof InstancedMesh) || o.count > 0) {
          count++;
        }
      }
    });
    return count;
  }

  /** Nombre de particules à l'image (lu par les tests et le banc). */
  get particleCount(): number {
    return this.sparkCount;
  }

  /** Le chemin 3D de la liaison `i` à l'image (lu par les tests). */
  path(i: number): Float32Array {
    return this.paths[i];
  }

  /** Les positions des particules (lues par les tests). */
  get particles(): Float32Array {
    return this.sparkPositions;
  }

  /** Étiquettes posées (lues par les tests). */
  get labelElements(): readonly HTMLElement[] {
    return this.labels.map((l) => l.element);
  }

  /** Recopie l'image (plan, phase, instant en ms) dans les tampons de la scène. N'alloue rien. */
  update(plan: ArchitecturePlan, phase: ArchPhaseKind, phaseMs: number, seconds: number, aspect: number): void {
    const f = this.director.frame(plan, phase, phaseMs, this.frame);
    const { layout } = this.options;
    this.backdrop.uniforms['uTime'].value = seconds * 1000;
    this.backdrop.uniforms['uAspect'].value = aspect;
    this.stars.uniforms['uTime'].value = seconds * 1000;

    // T-A1 / T-A2 : le panneau 2D debout face à la caméra se couche en plateau racine.
    if (this.standFor !== plan) {
      this.standFor = plan;
      const b = cameraBasis(plan.camera);
      this.ax.set(b.right[0], b.right[1], b.right[2]);
      this.ay.set(-b.forward[0], -b.forward[1], -b.forward[2]);
      this.az.set(-b.up[0], -b.up[1], -b.up[2]);
      this.basis.makeBasis(this.ax, this.ay, this.az);
      this.stand.setFromRotationMatrix(this.basis);
    }
    this.world.quaternion.slerpQuaternions(this.stand, this.flat, f.lay);

    const normal = this.palette.get('normal') as number[];
    for (const p of layout.plates) {
      const alt = f.plateAlt[p.index];
      this.matrix.makeScale(p.w, PLATE_THICKNESS, p.d);
      this.matrix.setPosition(p.x0 + p.w / 2, alt - PLATE_THICKNESS / 2, p.z0 + p.d / 2);
      this.plates.setMatrixAt(p.index, this.matrix);
      const k = f.plateFill[p.index];
      this.plates.setColorAt(p.index, this.color(normal[0] * k, normal[1] * k, normal[2] * k));
    }
    this.plates.instanceMatrix.needsUpdate = true;
    if (this.plates.instanceColor) {
      this.plates.instanceColor.needsUpdate = true;
    }
    this.writePlateOutlines();

    // Services : ombre, halo, socle, icône (échelle T-A1, éclat selon le palier).
    const icon = this.iconAlpha.array as Float32Array;
    for (const node of layout.nodes) {
      const i = node.index;
      const alt = node.plate < 0 ? 0 : f.plateAlt[node.plate];
      const s = Math.max(0, f.nodeScale[i]);
      const a = f.nodeAlpha[i];
      const role = this.palette.get(plan.nodeRole[i]) as number[];
      this.disc(i * 3, node.x, alt + 0.001, node.z, HALO_RADIUS * 0.8 * s, a, 0, 0);
      this.disc(i * 3 + 1, node.x, alt + 0.002, node.z, HALO_RADIUS * s, normal[0] * a, normal[1] * a,
        normal[2] * a);
      const k = 0.4 + 0.6 * a;
      this.disc(i * 3 + 2, node.x, alt + 0.003, node.z, SOCKET_RADIUS * s, role[0] * k, role[1] * k, role[2] * k);
      const size = ICON_SIZE * s;
      this.matrix.makeScale(size, size, size);
      this.matrix.setPosition(node.x, alt + f.float, node.z);
      this.icons.setMatrixAt(i, this.matrix);
      icon[i] = Math.min(1, s) * (a >= 0.45 ? 1 : 0.55);
    }
    this.discs.instanceMatrix.needsUpdate = true;
    if (this.discs.instanceColor) {
      this.discs.instanceColor.needsUpdate = true;
    }
    this.icons.instanceMatrix.needsUpdate = true;
    this.iconAlpha.needsUpdate = true;
    this.writeColumns(f);

    // Filaments tracés de `from` vers `to`, puis les particules qui les parcourent.
    this.writeFilaments(plan, f);
    let count = 0;
    if (f.particles > 0.001) {
      for (const e of layout.edges) {
        const trace = f.edgeTrace[e.index];
        if (trace >= 1) {
          count = writeParticles(this.paths[e.index], 0, pathPoints(e), this.lengths[e.index], e.bidirectional,
            seconds, plan.edgeFlow[e.index], this.sparkPositions, this.sparkGlow, count, this.maxSparks);
        }
      }
    }
    this.sparkCount = count;
    this.sparkMaterial.uniforms['uOpacity'].value = f.particles;
    const sg = this.sparks.geometry;
    sg.setDrawRange(0, count);
    (sg.attributes['position'] as BufferAttribute).needsUpdate = true;
    (sg.attributes['aGlow'] as BufferAttribute).needsUpdate = true;

    this.writeLabels(plan, f);
  }

  setResolution(width: number, height: number): void {
    for (const m of this.lineMaterials) {
      m.resolution.set(width, height);
    }
  }

  dispose(): void {
    this.scene.traverse((o) => {
      const m = o as Mesh;
      if (m.geometry) {
        m.geometry.dispose();
      }
      const mat = m.material as { dispose?: () => void } | undefined;
      mat?.dispose?.();
    });
    this.texture.dispose();
    for (const l of this.labels) {
      l.object.removeFromParent();
      l.element.remove();
    }
  }

  // ------------------------------------------------------------------------------------------------ construction

  private readonly tint = new Color();
  private readonly ax = new Vector3();
  private readonly ay = new Vector3();
  private readonly az = new Vector3();
  private standFor: ArchitecturePlan | null = null;

  private color(r: number, g: number, b: number): Color {
    return this.tint.setRGB(r, g, b);
  }

  private disc(i: number, x: number, y: number, z: number, radius: number, r: number, g: number, b: number): void {
    const s = Math.max(radius, 1e-5);
    this.matrix.makeScale(s, 1, s);
    this.matrix.setPosition(x, y, z);
    this.discs.setMatrixAt(i, this.matrix);
    this.discs.setColorAt(i, this.color(r, g, b));
  }

  private buildLines(segments: number, look: { linewidth: number; dashed?: boolean; dashSize?: number;
      gapSize?: number; opacity?: number }): LineSegments2 {
    const geometry = new LineSegmentsGeometry();
    const n = Math.max(1, segments);
    geometry.setPositions(new Float32Array(n * 6));
    geometry.setColors(new Float32Array(n * 6));
    const material = new LineMaterial({
      linewidth: look.linewidth, vertexColors: true, transparent: true, depthWrite: false,
      blending: AdditiveBlending, worldUnits: false, dashed: !!look.dashed, dashSize: look.dashSize ?? 1,
      gapSize: look.gapSize ?? 1, opacity: look.opacity ?? 1,
    });
    this.lineMaterials.push(material);
    const line = new LineSegments2(geometry, material);
    line.frustumCulled = false;
    line.renderOrder = 4;
    line.visible = segments > 0;
    this.world.add(line);
    return line;
  }

  private buildStars(count: number): ShaderMaterial {
    const random = this.options.random;
    const pos = new Float32Array(count * 3);
    const col = new Float32Array(count * 3);
    const size = new Float32Array(count);
    const cream = rgb('#F5EFE3');
    const warm = rgb(HOLO_COLORS.normal);
    const reach = Math.max(this.options.layout.width, this.options.layout.depth);
    for (let i = 0; i < count; i++) {
      const dust = i % 3 === 0;
      const u = random() * 2 - 1;
      const theta = random() * Math.PI * 2;
      const r = dust ? reach * (0.4 + random() * 0.6) : 40 + random() * 30;
      const s = Math.sqrt(1 - u * u);
      pos[i * 3] = r * s * Math.cos(theta);
      pos[i * 3 + 1] = dust ? (random() - 0.3) * reach * 0.4 : r * u;
      pos[i * 3 + 2] = r * s * Math.sin(theta);
      const tint = dust ? warm : cream;
      const k = (dust ? 0.5 : 0.55) + random() * 0.45;
      col[i * 3] = tint[0] * k;
      col[i * 3 + 1] = tint[1] * k;
      col[i * 3 + 2] = tint[2] * k;
      size[i] = dust ? 0.8 + random() * 1.2 : 2 + random() * 3;
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(pos, 3));
    geometry.setAttribute('aColor', new BufferAttribute(col, 3));
    geometry.setAttribute('aSize', new BufferAttribute(size, 1));
    const material = new ShaderMaterial({
      vertexShader: STARS_VERTEX, fragmentShader: STARS_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending, uniforms: { uPixelRatio: { value: this.options.pixelRatio }, uTime: { value: 0 } },
    });
    const points = new Points(geometry, material);
    points.frustumCulled = false;
    this.scene.add(points);
    return material;
  }

  private buildFloor(rings: number): void {
    const segments = 96;
    const rays = 24;
    const step = Math.max(this.options.layout.width, this.options.layout.depth) * 0.16;
    const vertices: number[] = [];
    for (let ring = 1; ring <= rings; ring++) {
      const r = ring * step;
      for (let s = 0; s < segments; s++) {
        const a0 = (s / segments) * Math.PI * 2;
        const a1 = ((s + 1) / segments) * Math.PI * 2;
        vertices.push(Math.cos(a0) * r, 0, Math.sin(a0) * r, Math.cos(a1) * r, 0, Math.sin(a1) * r);
      }
    }
    for (let k = 0; k < rays; k++) {
      const a = (k / rays) * Math.PI * 2;
      vertices.push(Math.cos(a) * step, 0, Math.sin(a) * step, Math.cos(a) * rings * step, 0,
        Math.sin(a) * rings * step);
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(vertices), 3));
    const floor = new LineSegments(geometry, new LineBasicMaterial({
      color: HOLO_COLORS.normalDim, transparent: true, opacity: 0.12, blending: AdditiveBlending, depthWrite: false,
    }));
    floor.position.y = -0.35;
    floor.renderOrder = 0;
    this.world.add(floor);
  }

  private makeLabel(text: string, kind: 'zone' | 'service', x: number, z: number): Label | null {
    if (!this.options.labelHost) {
      return null;
    }
    const element = document.createElement('div');
    element.className = `jv3d-label jv3d-label--arch-${kind}`;
    element.textContent = text;
    element.style.opacity = '0';
    const object = new CSS2DObject(element);
    object.center.set(kind === 'zone' ? 0 : 0.5, 0);
    object.position.set(x, 0, z);
    this.world.add(object);
    const label = { object, element, shown: 0, lit: false };
    this.labels.push(label);
    return label;
  }

  // ------------------------------------------------------------------------------------------------ écritures

  private lineArrays(line: LineSegments2): { p: Float32Array; c: Float32Array } {
    const geometry = line.geometry;
    const pos = (geometry.attributes['instanceStart'] as InterleavedBufferAttribute).data;
    const col = (geometry.attributes['instanceColorStart'] as InterleavedBufferAttribute).data;
    return { p: pos.array as Float32Array, c: col.array as Float32Array };
  }

  private touch(line: LineSegments2): void {
    const geometry = line.geometry;
    (geometry.attributes['instanceStart'] as InterleavedBufferAttribute).data.needsUpdate = true;
    (geometry.attributes['instanceColorStart'] as InterleavedBufferAttribute).data.needsUpdate = true;
  }

  private segment(p: Float32Array, c: Float32Array, k: number, x0: number, y0: number, z0: number, x1: number,
      y1: number, z1: number, r: number, g: number, b: number): void {
    const o = k * 6;
    p[o] = x0;
    p[o + 1] = y0;
    p[o + 2] = z0;
    p[o + 3] = x1;
    p[o + 4] = y1;
    p[o + 5] = z1;
    c[o] = r;
    c[o + 1] = g;
    c[o + 2] = b;
    c[o + 3] = r;
    c[o + 4] = g;
    c[o + 5] = b;
  }

  private writePlateOutlines(): void {
    const f = this.frame;
    const { plates } = this.options.layout;
    const write = (line: LineSegments2, indices: number[]): void => {
      const { p, c } = this.lineArrays(line);
      let k = 0;
      for (const i of indices) {
        const plate = plates[i];
        const y = f.plateAlt[i] + 0.001;
        const col = this.palette.get('normal') as number[];
        const a = f.plateLine[i];
        const r = col[0] * a;
        const g = col[1] * a;
        const b = col[2] * a;
        const x0 = plate.x0;
        const z0 = plate.z0;
        const x1 = plate.x0 + plate.w;
        const z1 = plate.z0 + plate.d;
        this.segment(p, c, k++, x0, y, z0, x1, y, z0, r, g, b);
        this.segment(p, c, k++, x1, y, z0, x1, y, z1, r, g, b);
        this.segment(p, c, k++, x1, y, z1, x0, y, z1, r, g, b);
        this.segment(p, c, k++, x0, y, z1, x0, y, z0, r, g, b);
      }
      this.touch(line);
    };
    write(this.lines, this.solidPlates);
    write(this.dashes, this.dashedPlates);
  }

  /** Colonnes lumineuses socle → icône (dans le même objet que les liserés continus). */
  private writeColumns(f: ArchFrame): void {
    const { p, c } = this.lineArrays(this.lines);
    const base = this.solidPlates.length * 4;
    const col = this.palette.get('normal') as number[];
    for (const node of this.options.layout.nodes) {
      const alt = node.plate < 0 ? 0 : f.plateAlt[node.plate];
      const a = f.nodeAlpha[node.index] * Math.min(1, Math.max(0, f.nodeScale[node.index])) * 0.6;
      const top = alt + Math.max(0, f.float - ICON_SIZE / 2);
      this.segment(p, c, base + node.index, node.x, alt + 0.004, node.z, node.x, top, node.z, col[0] * a,
        col[1] * a, col[2] * a);
    }
    this.touch(this.lines);
  }

  private writeFilaments(plan: ArchitecturePlan, f: ArchFrame): void {
    const core = this.lineArrays(this.filaments);
    const glow = this.lineArrays(this.glows);
    const normal = this.palette.get('normal') as number[];
    const cream = this.palette.get('text') as number[];
    let k = 0;
    for (const e of this.options.layout.edges) {
      const path = this.paths[e.index];
      const length = writeEdgePath(e, f.plateAlt, path);
      this.lengths[e.index] = length;
      const count = pathPoints(e);
      const a = f.edgeAlpha[e.index];
      const role = plan.edgeRole[e.index] === 'core' ? this.palette.get('core') as number[] : cream;
      const traced = f.edgeTrace[e.index] * length;
      let walked = 0;
      for (let s = 1; s < count; s++) {
        const o0 = (s - 1) * 3;
        const o1 = s * 3;
        const len = Math.abs(path[o1] - path[o0]) + Math.abs(path[o1 + 1] - path[o0 + 1])
          + Math.abs(path[o1 + 2] - path[o0 + 2]);
        // Le tracé avance segment par segment de `from` vers `to` ; au-delà, le segment se réduit à son départ.
        const u = len > 0 ? Math.max(0, Math.min(1, (traced - walked) / len)) : (traced > walked ? 1 : 0);
        const vis = traced > walked ? a : 0;
        const x1 = path[o0] + (path[o1] - path[o0]) * u;
        const y1 = path[o0 + 1] + (path[o1 + 1] - path[o0 + 1]) * u;
        const z1 = path[o0 + 2] + (path[o1 + 2] - path[o0 + 2]) * u;
        this.segment(core.p, core.c, k, path[o0], path[o0 + 1], path[o0 + 2], x1, y1, z1, role[0] * vis,
          role[1] * vis, role[2] * vis);
        this.segment(glow.p, glow.c, k, path[o0], path[o0 + 1], path[o0 + 2], x1, y1, z1, normal[0] * vis,
          normal[1] * vis, normal[2] * vis);
        walked += len;
        k++;
      }
    }
    this.touch(this.filaments);
    this.touch(this.glows);
  }

  private writeLabels(plan: ArchitecturePlan, f: ArchFrame): void {
    const { plates, nodes } = this.options.layout;
    plates.forEach((p, i) => this.placeLabel(this.plateLabels[i], p.x0 + 0.06, f.plateAlt[i] + 0.01, p.z0 + 0.04,
      plan.plateLabel[i] ? f.labels : 0, plan.plateRole[i] === 'core'));
    nodes.forEach((n, i) => {
      const alt = n.plate < 0 ? 0 : f.plateAlt[n.plate];
      this.placeLabel(this.nodeLabels[i], n.x, alt + f.float, n.z + ICON_SIZE / 2 + 0.04,
        plan.nodeLabel[i] ? f.labels * Math.min(1, Math.max(0, f.nodeScale[i])) : 0, plan.nodeRole[i] === 'core');
    });
  }

  private placeLabel(label: Label | null, x: number, y: number, z: number, alpha: number, lit: boolean): void {
    if (!label) {
      return;
    }
    label.object.position.set(x, y, z);
    const q = Math.round(alpha * 20) / 20;
    if (q !== label.shown) {
      label.shown = q;
      label.element.style.opacity = String(q);
      label.object.visible = q > 0;
    }
    if (lit !== label.lit) {
      label.lit = lit;
      label.element.dataset['state'] = lit ? 'lit' : 'normal';
    }
  }
}

export interface ArchitectureEngineOptions extends ArchitectureSceneOptions {
  canvas: HTMLCanvasElement;
  width: number;
  height: number;
  clock: EngineClock;
  frameHost: FrameHost;
  onFirstFrame?(): void;
}

/** Le moteur : rendu WebGL, lumière F-236 (bloom), étiquettes, boucle d'images (0 image onglet caché). */
export class ArchitectureEngine {
  readonly graph: ArchitectureSceneGraph;
  private readonly renderer: WebGLRenderer;
  private readonly camera: PerspectiveCamera;
  private readonly post: JarvisPost;
  private readonly labels: CSS2DRenderer | null;
  private readonly loop: FrameLoop;
  private plan: ArchitecturePlan;
  private phase: ArchPhaseKind = 'entree';
  private phaseStart = 0;
  private width: number;
  private height: number;
  private firstFrame = false;
  /** Appels de dessin de la dernière image (hors post-traitement), et i/s lissés. */
  calls = 0;
  fps = 0;
  private last: number | null = null;
  private readonly look = new Vector3();
  private cameraFor: ArchitecturePlan | null = null;

  constructor(private readonly options: ArchitectureEngineOptions, state: ArchState, phase: ArchPhaseKind) {
    ColorManagement.enabled = false;
    this.width = options.width;
    this.height = options.height;
    this.graph = new ArchitectureSceneGraph(options);
    this.renderer = new WebGLRenderer({ canvas: options.canvas, antialias: options.level === 'high', alpha: false,
      powerPreference: 'high-performance' });
    this.renderer.outputColorSpace = LinearSRGBColorSpace;
    this.renderer.setPixelRatio(Math.min(options.pixelRatio, levelBudget(options.level, options.phone).pixelRatio));
    this.renderer.setClearColor(HOLO_COLORS.fog, 1);
    this.renderer.setSize(options.width, options.height, false);
    this.renderer.info.autoReset = false;
    // Appels de dessin de la scène seule : lus à la fin de son rendu, avant les passes de lumière (hors budget §14).
    this.graph.scene.onAfterRender = () => {
      this.calls = this.renderer.info.render.calls;
    };
    this.camera = new PerspectiveCamera(40, options.width / Math.max(1, options.height), 0.05, 400);
    this.post = new JarvisPost(this.renderer, this.graph.scene, this.camera, options.width, options.height,
      options.level, options.phone);
    this.labels = options.labelHost ? new CSS2DRenderer({ element: options.labelHost }) : null;
    this.labels?.setSize(options.width, options.height);
    this.graph.setResolution(options.width, options.height);
    this.plan = this.graph.director.plan(state, this.viewport());
    // L'horloge part de 0 à la pose : au banc (horloge figée à t), l'instant de la phase est t.
    this.setPhase(phase, 0);
    this.loop = new FrameLoop(options.frameHost, (now) => this.draw(now));
  }

  get frames(): number {
    return this.loop.frames;
  }

  start(): void {
    this.loop.start();
  }

  /** Nouvel état (alloué au changement seulement). */
  setState(state: ArchState): void {
    this.plan = this.graph.director.plan(state, this.viewport());
  }

  setPhase(phase: ArchPhaseKind, start = this.options.clock()): void {
    this.phase = phase;
    this.phaseStart = start;
  }

  resize(width: number, height: number): void {
    this.width = width;
    this.height = height;
    this.renderer.setSize(width, height, false);
    this.cameraFor = null;
    this.post.setSize(width, height);
    this.labels?.setSize(width, height);
    this.graph.setResolution(width, height);
    this.plan = this.graph.director.plan(this.plan.state, this.viewport());
  }

  dispose(): void {
    this.loop.stop();
    this.graph.dispose();
    this.post.dispose();
    this.renderer.dispose();
  }

  private viewport(): Viewport {
    return { width: this.width, height: this.height };
  }

  private draw(now: number): void {
    const t = this.options.clock();
    if (this.last !== null) {
      const dt = now - this.last;
      if (dt > 0) {
        this.fps = this.fps === 0 ? 1000 / dt : this.fps * 0.9 + (1000 / dt) * 0.1;
      }
    }
    this.last = now;
    this.graph.update(this.plan, this.phase, t - this.phaseStart, t / 1000, this.width / Math.max(1, this.height));
    if (this.cameraFor !== this.plan) {
      // La caméra suit le plan (SF-238-10 y mettra le ressort T-A9) ; recalculée au changement seulement.
      this.cameraFor = this.plan;
      const cam = this.plan.camera;
      const b = cameraBasis(cam);
      this.camera.fov = cam.fov;
      this.camera.aspect = this.width / Math.max(1, this.height);
      this.camera.updateProjectionMatrix();
      this.camera.position.set(b.position[0], b.position[1], b.position[2]);
      this.camera.up.set(b.up[0], b.up[1], b.up[2]);
      this.look.set(cam.target[0], cam.target[1], cam.target[2]);
      this.camera.lookAt(this.look);
    }
    this.renderer.info.reset();
    this.post.render(BLOOM_STRENGTH, t);
    this.labels?.render(this.graph.scene, this.camera);
    if (!this.firstFrame) {
      this.firstFrame = true;
      this.options.onFirstFrame?.();
    }
  }
}
