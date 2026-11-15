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
import { HOLO_ALPHA, HOLO_COLORS, HoloRole, MOTION_DURATIONS, rgb } from '../../jarvis-motion';
import { QualityLevel } from '../../jarvis-render-capability';
import { QualityGovernor } from '../../jarvis-quality';
import { levelBudget } from '../../hud/jarvis-device-profile';
import { JarvisPost } from '../../engine/jarvis-post';
import { ArchitectureIconSource } from '../architecture-icons';
import { LabelRect, declutter } from '../architecture-labels';
import {
  ArchLayout, ICON_SIZE, pathPoints, writeEdgePath, writeParticles,
} from '../architecture-layout-3d';
import {
  ArchCamera, ArchFrame, ArchMove, ArchPhaseKind, ArchState, ArchTransition, ArchitecturePlan,
  ArchitectureStageDirector, MAJOR_MOVE_MS, Viewport, blendCamera, cameraBasis, moveFor, moveProgress, paceMove,
  pickAt,
} from '../architecture-stage-director';
import { ArchitectureNarrator } from '../architecture-narration';
import { phoneZoneLabel } from '../architecture-render';
import { PHONE_ZONE_ORDER } from '../architecture-stage-director';

/** Rang d'une nature de zone au téléphone (AZ d'abord). */
function phoneZoneRank(kind: string): number {
  const i = PHONE_ZONE_ORDER.indexOf(kind);
  return i < 0 ? PHONE_ZONE_ORDER.length : i;
}

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
/**
 * Retour PO 2026-11-16 — « trop lumineux » : au repos la lumière est sobre (icônes AWS et libellés lisibles) ; la forte
 * lueur est réservée à ce que Jarvis allume (narration, flux, héros).
 */
export const BLOOM_REST = 0.3;
export const BLOOM_LIT = 0.65;
/** Halo, socle et filaments d'un élément au repos : une fraction de la lueur d'un élément allumé. */
export const REST_GLOW = 0.3;
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
varying vec2 vLocal;
varying float vAlpha;
void main() {
  vLocal = uv;
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
uniform vec3 uCard;
varying vec2 vUv;
varying vec2 vLocal;
varying float vAlpha;
void main() {
  vec4 t = texture2D(uMap, vUv);
  // Une carte claire sous l'icône (comme le fond blanc du 2D) : les icônes au trait restent lisibles sur le navy.
  // L'icône elle-même n'est jamais recolorée (licence) : elle est posée telle quelle par-dessus.
  vec2 q = abs(vLocal - 0.5) - 0.38;
  float card = 1.0 - smoothstep(0.0, 0.03, length(max(q, 0.0)) - 0.06);
  vec3 color = mix(uCard, t.rgb, t.a);
  float a = max(card * 0.92, t.a) * vAlpha;
  if (a < 0.02) discard;
  gl_FragColor = vec4(color * (0.82 + 0.18 * t.a), a);
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
  /** Priorité de base (plus petit = d'abord) : services avant zones, zones les plus précises d'abord. */
  priority: number;
  masked: boolean;
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
  /**
   * Les icônes sont dessinées APRÈS la lumière (une scène à part, même caméra) : le bloom fait briller liserés,
   * filaments et particules, jamais le contenu — une icône (et sa carte claire) resterait sinon noyée de blanc.
   */
  readonly iconScene = new Scene();
  private readonly iconWorld = new Group();
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
  private texture: CanvasTexture;
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
  /** SF-238-10 — la capsule d'une zone repliée (« ⌬ 7 services »), une par plateau, hors budget d'étiquettes. */
  private readonly capsuleLabels: (Label | null)[];
  private labelsFor: ArchitecturePlan | null = null;
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
      vertexShader: ICON_VERTEX, fragmentShader: ICON_FRAGMENT, transparent: true, depthWrite: false, depthTest: false,
      uniforms: { uMap: { value: this.texture }, uGrid: { value: [icons.columns, icons.rows] },
        uCard: { value: rgb(HOLO_COLORS.core) } },
    }), n);
    this.icons.frustumCulled = false;
    this.icons.renderOrder = 5;
    this.iconScene.add(this.iconWorld);
    this.iconWorld.add(this.icons);

    // Liserés des plateaux (continus / pointillés selon le `kind`) + colonnes lumineuses socle → icône.
    this.solidPlates = layout.plates.filter((p) => !p.dashed).map((p) => p.index);
    this.dashedPlates = layout.plates.filter((p) => p.dashed).map((p) => p.index);
    const lineWidth = options.phone ? 1.6 : 1.4;
    // Liserés continus, colonnes socle → icône, puis 4 piliers d'angle par plateau (les terrasses se lisent).
    this.lines = this.buildLines(this.solidPlates.length * 4 + n + layout.plates.length * 4, { linewidth: lineWidth });
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
    this.glows = this.buildLines(segments, { linewidth: options.phone ? 4 : 5, opacity: 0.2 });

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
    const maxArea = Math.max(1e-6, ...layout.plates.map((p) => p.w * p.d));
    this.nodeLabels = layout.nodes.map((node) => (this.labels.length < cap
      ? this.makeLabel(node.label, 'service', node.x, node.z + ICON_SIZE / 2 + 0.04, 1) : null));
    // Zone : au milieu de son bord avant (le plus proche de la caméra), à l'intérieur ; les plateaux emboîtés y
    // sont décalés en plan ET en altitude, leurs libellés ne s'empilent plus dans un même coin.
    // SF-238-11 — cause du défaut téléphone : les zones partageaient le plafond des services (16) et seules les 6
    // premières avaient un libellé. Toutes les zones (≤ 24) ont désormais le leur ; le plan décide lesquels se voient.
    this.plateLabels = layout.plates.map((p) => (this.options.labelHost
      ? this.makeLabel(options.phone ? phoneZoneLabel(p.label) : p.label, 'zone', p.x0 + p.w / 2, p.z0 + p.d - 0.05,
        // SF-238-11 — au téléphone, les zones (courtes) passent avant les services au dé-chevauchement : le PO doit
        // lire la structure (AZ, puis sous-réseaux, puis VPC…) ; un service masqué se lit à son icône et au toucher.
        options.phone ? 0.5 + phoneZoneRank(p.kind) / 100 : 2 + (p.w * p.d) / maxArea,
        p.kind === 'subnet_public' || p.kind === 'subnet_private') : null));
    this.capsuleLabels = layout.plates.map((p) => this.makeLabel('', 'capsule', p.x0 + p.w / 2, p.z0 + p.d / 2, 0.5));
  }

  /**
   * Change de source d'icônes (l'atlas des icônes officielles de SF-238-08, chargé après la pose) : même quad, même
   * appel de dessin ; seules la texture et les cellules changent.
   */
  setIcons(icons: ArchitectureIconSource): void {
    const previous = this.texture;
    this.texture = new CanvasTexture(icons.image);
    this.texture.minFilter = LinearFilter;
    this.texture.generateMipmaps = false;
    const material = this.icons.material as ShaderMaterial;
    material.uniforms['uMap'].value = this.texture;
    material.uniforms['uGrid'].value = [icons.columns, icons.rows];
    const cells = this.icons.geometry.getAttribute('aCell') as InstancedBufferAttribute;
    this.options.layout.nodes.forEach((node, i) => cells.setX(i, icons.cellOf(node)));
    cells.needsUpdate = true;
    previous.dispose();
  }

  /** La cellule d'atlas de chaque service (lue par les tests). */
  get iconCells(): ArrayLike<number> {
    return (this.icons.geometry.getAttribute('aCell') as InstancedBufferAttribute).array as Float32Array;
  }

  /** Nombre d'objets dessinés à l'image (un appel chacun : aucune matière multiple). */
  drawables(): number {
    let count = 0;
    const visit = (o: Object3D): void => {
      const m = o as Mesh;
      if ((m as unknown as { isMesh?: boolean }).isMesh || (o as Points).isPoints
          || (o as LineSegments).isLine || (o as LineSegments2).isLineSegments2) {
        if (!(o instanceof InstancedMesh) || o.count > 0) {
          count++;
        }
      }
    };
    this.scene.traverseVisible(visit);
    this.iconScene.traverseVisible(visit);
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
  update(plan: ArchitecturePlan, phase: ArchPhaseKind, phaseMs: number, seconds: number, aspect: number,
      transition: ArchTransition | null = null, move: ArchMove | null = null): void {
    const f = this.director.frame(plan, phase, phaseMs, this.frame, seconds * 1000, transition, move);
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
    this.iconWorld.quaternion.copy(this.world.quaternion);

    const normal = this.palette.get('normal') as number[];
    for (const p of layout.plates) {
      const alt = f.plateAlt[p.index];
      this.matrix.makeScale(p.w, PLATE_THICKNESS, p.d);
      this.matrix.setPosition(p.x0 + p.w / 2, alt - PLATE_THICKNESS / 2, p.z0 + p.d / 2);
      this.plates.setMatrixAt(p.index, this.matrix);
      const k = f.plateFill[p.index] * (1 - f.plateHidden[p.index]);
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
      // SF-238-10 — un service rentré dans une capsule se rapproche de son centre en rapetissant (T-A4 / T-A5).
      const s = Math.max(0, f.nodeScale[i] * (1 - f.nodeFold[i]));
      const a = f.nodeAlpha[i];
      const x = f.nodeX[i];
      const z = f.nodeZ[i];
      const role = this.palette.get(plan.nodeRole[i]) as number[];
      this.disc(i * 3, x, alt + 0.001, z, HALO_RADIUS * 0.8 * s, a, 0, 0);
      const on = plan.nodeLit[i] === 1;
      const g = on ? a : a * REST_GLOW;
      this.disc(i * 3 + 1, x, alt + 0.002, z, HALO_RADIUS * s, normal[0] * g, normal[1] * g,
        normal[2] * g);
      const k = on ? 0.4 + 0.6 * a : 0.15 + 0.25 * a;
      this.disc(i * 3 + 2, x, alt + 0.003, z, SOCKET_RADIUS * s, role[0] * k, role[1] * k, role[2] * k);
      const size = ICON_SIZE * s;
      this.matrix.makeScale(size, size, size);
      this.matrix.setPosition(x, alt + f.float, z);
      this.icons.setMatrixAt(i, this.matrix);
      // Icônes AWS à leurs couleurs officielles, pleinement lisibles ; seulement atténuées hors du focus.
      icon[i] = Math.min(1, s) * (a >= HOLO_ALPHA.context ? 1 : 0.7);
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
          // Parcours intégré par le directeur (×2 pendant 2 s après T-A7, sans saut de position).
          count = writeParticles(this.paths[e.index], 0, pathPoints(e), this.lengths[e.index], e.bidirectional,
            f.edgeTravel[e.index], plan.edgeFlow[e.index], this.sparkPositions, this.sparkGlow, count,
            this.maxSparks);
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
    this.iconWorld.traverse((o) => {
      const m = o as Mesh;
      m.geometry?.dispose();
      (m.material as { dispose?: () => void } | undefined)?.dispose?.();
    });
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

  private makeLabel(text: string, kind: 'zone' | 'service' | 'capsule', x: number, z: number, priority: number,
      rightAligned = false): Label | null {
    if (!this.options.labelHost) {
      return null;
    }
    const element = document.createElement('div');
    element.className = `jv3d-label jv3d-label--arch-${kind}`;
    element.textContent = text;
    element.style.opacity = '0';
    const object = new CSS2DObject(element);
    object.center.set(kind === 'zone' && this.options.phone ? (rightAligned ? 1 : 0) : 0.5,
      kind === 'zone' ? 1 : kind === 'capsule' ? 0.5 : 0);
    object.position.set(x, 0, z);
    this.world.add(object);
    const label = { object, element, shown: 0, lit: false, priority, masked: false };
    this.labels.push(label);
    return label;
  }

  /**
   * Zéro chevauchement : lit le cadre affiché des étiquettes visibles et masque celles qui en touchent une plus
   * prioritaire. Appelé au changement de plan et pendant les transitions (pas à chaque image : lecture du DOM).
   */
  declutterLabels(): void {
    const live = this.labels.filter((l) => l.shown > 0 && l.object.visible);
    const rects: LabelRect[] = live.map((l) => {
      const r = l.element.getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height, priority: l.lit ? 0 : l.priority };
    });
    const keep = declutter(rects);
    live.forEach((l, i) => {
      const masked = !keep[i];
      if (masked !== l.masked) {
        l.masked = masked;
        l.element.classList.toggle('is-masked', masked);
      }
    });
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
        // Capsule : son liseré s'allume (actif au moins) ; un plateau dans une zone repliée s'efface.
        const a = Math.max(f.plateLine[i], HOLO_ALPHA.active * f.plateCapsule[i]) * (1 - f.plateHidden[i]);
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
    // Piliers d'angle : du plateau parent (ou du sol) jusqu'au plateau, aux quatre coins.
    const { p, c } = this.lineArrays(this.lines);
    let k = this.solidPlates.length * 4 + this.options.layout.nodes.length;
    const col = this.palette.get('normal') as number[];
    for (const plate of plates) {
      const top = f.plateAlt[plate.index];
      const bottom = plate.parent < 0 ? 0 : f.plateAlt[plate.parent];
      const a = f.plateLine[plate.index] * 0.7 * (1 - f.plateHidden[plate.index]);
      const x1 = plate.x0 + plate.w;
      const z1 = plate.z0 + plate.d;
      this.segment(p, c, k++, plate.x0, bottom, plate.z0, plate.x0, top, plate.z0, col[0] * a, col[1] * a, col[2] * a);
      this.segment(p, c, k++, x1, bottom, plate.z0, x1, top, plate.z0, col[0] * a, col[1] * a, col[2] * a);
      this.segment(p, c, k++, x1, bottom, z1, x1, top, z1, col[0] * a, col[1] * a, col[2] * a);
      this.segment(p, c, k++, plate.x0, bottom, z1, plate.x0, top, z1, col[0] * a, col[1] * a, col[2] * a);
    }
  }

  /** Colonnes lumineuses socle → icône (dans le même objet que les liserés continus). */
  private writeColumns(f: ArchFrame): void {
    const { p, c } = this.lineArrays(this.lines);
    const base = this.solidPlates.length * 4;
    const col = this.palette.get('normal') as number[];
    for (const node of this.options.layout.nodes) {
      const i = node.index;
      const alt = node.plate < 0 ? 0 : f.plateAlt[node.plate];
      const a = f.nodeAlpha[i] * Math.min(1, Math.max(0, f.nodeScale[i] * (1 - f.nodeFold[i]))) * 0.6;
      const top = alt + Math.max(0, f.float - ICON_SIZE / 2);
      this.segment(p, c, base + i, f.nodeX[i], alt + 0.004, f.nodeZ[i], f.nodeX[i], top, f.nodeZ[i], col[0] * a,
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
      const white = this.palette.get('core') as number[];
      const lit = plan.edgeRole[e.index] === 'core';
      // T-A7 : les segments déjà balayés prennent le blanc chaud ; les suivants restent au palier actif.
      const swept = f.edgeSweep[e.index] * (count - 1);
      const traced = f.edgeTrace[e.index] * length;
      let walked = 0;
      for (let s = 1; s < count; s++) {
        const o0 = (s - 1) * 3;
        const o1 = s * 3;
        const len = Math.abs(path[o1] - path[o0]) + Math.abs(path[o1 + 1] - path[o0 + 1])
          + Math.abs(path[o1 + 2] - path[o0 + 2]);
        // Le tracé avance segment par segment de `from` vers `to` ; au-delà, le segment se réduit à son départ.
        const u = len > 0 ? Math.max(0, Math.min(1, (traced - walked) / len)) : (traced > walked ? 1 : 0);
        const hot = lit && s - 1 < swept;
        const role = hot ? white : cream;
        const base = traced > walked ? (hot || !lit ? a : Math.min(a, HOLO_ALPHA.active)) : 0;
        const vis = lit ? base : base * (0.35 + 0.65 * REST_GLOW);
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
    if (this.labelsFor !== plan) {
      // SF-238-10 — textes recalculés au changement de plan seulement : capsule (« ⌬ 7 services »), badge « × 3 ».
      this.labelsFor = plan;
      plates.forEach((p, i) => {
        const capsule = this.capsuleLabels[i];
        if (capsule && capsule.element.textContent !== plan.capsuleText[i]) {
          capsule.element.textContent = plan.capsuleText[i];
        }
        const zone = this.plateLabels[i];
        const name = this.options.phone ? phoneZoneLabel(p.label) : p.label;
        const text = plan.plateBadge[i] ? `${name} ${plan.plateBadge[i]}` : name;
        if (zone && zone.element.textContent !== text) {
          zone.element.textContent = text;
        }
      });
    }
    // SF-238-11 — le libellé d'une zone au milieu du bord le plus proche de la caméra (le schéma peut tourner d'un
    // quart de tour au téléphone, et l'orbite le fait tourner).
    const sa = Math.sin(plan.camera.azimuth);
    const ca = Math.cos(plan.camera.azimuth);
    const side = Math.abs(sa) > Math.abs(ca);
    // Téléphone : au coin gauche de ce bord (vu de la caméra), aligné à gauche — deux zones voisines ne posent plus
    // leurs libellés au même endroit (convention AWS : le libellé en haut à gauche).
    const left = this.options.phone;
    const rx = ca;
    const rz = -sa;
    plates.forEach((p, i) => {
      let lx = side ? (sa > 0 ? p.x0 + p.w - 0.05 : p.x0 + 0.05) : p.x0 + p.w / 2;
      let lz = side ? p.z0 + p.d / 2 : ca > 0 ? p.z0 + p.d - 0.05 : p.z0 + 0.05;
      if (left) {
        // Le bout du bord le plus à gauche à l'écran (droite de la caméra = (cos a, 0, −sin a)), un peu en dedans ;
        // un sous-réseau au bout droit (aligné à droite) : il ne recouvre plus le libellé de son AZ, posé au même coin.
        const rightEnd = p.kind === 'subnet_public' || p.kind === 'subnet_private';
        const flip = rightEnd ? -1 : 1;
        if (side) {
          lz = rz * flip < 0 ? p.z0 + p.d - 0.08 : p.z0 + 0.08;
        } else {
          lx = rx * flip < 0 ? p.x0 + p.w - 0.08 : p.x0 + 0.08;
        }
      }
      this.placeLabel(this.plateLabels[i], lx, f.plateAlt[i] + 0.01, lz,
        plan.plateLabel[i] ? f.labels * (1 - f.plateHidden[i]) : 0, plan.plateRole[i] === 'core');
      this.placeLabel(this.capsuleLabels[i], p.x0 + p.w / 2, f.plateAlt[i] + f.float, p.z0 + p.d / 2,
        plan.capsuleText[i] ? f.labels * f.plateCapsule[i] : 0, false);
    });
    nodes.forEach((n, i) => {
      const alt = n.plate < 0 ? 0 : f.plateAlt[n.plate];
      this.placeLabel(this.nodeLabels[i], f.nodeX[i], alt + f.float, f.nodeZ[i] + ICON_SIZE / 2 + 0.04,
        plan.nodeLabel[i] ? f.labels * Math.min(1, Math.max(0, f.nodeScale[i] * (1 - f.nodeFold[i]))) : 0,
        plan.nodeRole[i] === 'core');
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
  /** SF-238-11 — qualité adaptative (gouverneur F-236) ; Bas trop lent ⇒ `onFallback` (le SVG cadré reprend). */
  adaptive?: boolean;
  onFallback?(): void;
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
  /** Appels de la passe des icônes (dessinée après la lumière). */
  iconCalls = 0;
  private sceneCalls = 0;
  private declutterDue = true;
  /** SF-238-12 — la phrase en cours (ce qui était allumé avant, et quand elle a commencé). */
  private transition: ArchTransition | null = null;
  private narrator: ArchitectureNarrator | null = null;
  private script: readonly { at: number; text: string }[] = [];
  private scriptIndex = -1;
  fps = 0;
  private last: number | null = null;
  /** SF-238-11 — la qualité adaptative (3 niveaux, puis repli 2D). */
  private readonly governor: QualityGovernor | null;
  private gaveUp = false;
  private readonly look = new Vector3();
  private cameraFor: ArchitecturePlan | null = null;
  /** SF-238-10 — le déplacement en cours (T-A3 → T-A6, T-A9) et la caméra affichée (point de départ du suivant). */
  private move: ArchMove | null = null;
  private readonly cameraNow: ArchCamera;
  /** Retour PO 2026-11-16 — intensité courante de la lueur (repos ⇄ allumé). */
  private bloom = BLOOM_REST;
  /** SF-238-14 — début du dernier grand déplacement de caméra (rythme). */
  private lastMajorMove: number | null = null;
  /** SF-238-10 — l'état venu de la navigation, et ce que la narration y allume. */
  private base: ArchState;
  private narrated: { lit: readonly string[]; flow: readonly string[] } = { lit: [], flow: [] };

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
      this.sceneCalls = this.renderer.info.render.calls;
    };
    this.camera = new PerspectiveCamera(40, options.width / Math.max(1, options.height), 0.05, 400);
    this.post = new JarvisPost(this.renderer, this.graph.scene, this.camera, options.width, options.height,
      options.level, options.phone);
    this.labels = options.labelHost ? new CSS2DRenderer({ element: options.labelHost }) : null;
    this.labels?.setSize(options.width, options.height);
    this.graph.setResolution(options.width, options.height);
    this.base = state;
    this.plan = this.graph.director.plan(state, this.viewport());
    this.cameraNow = { ...this.plan.camera, target: [...this.plan.camera.target] as [number, number, number] };
    // L'horloge part de 0 à la pose : au banc (horloge figée à t), l'instant de la phase est t.
    this.setPhase(phase, 0);
    this.loop = new FrameLoop(options.frameHost, (now) => this.draw(now));
    this.governor = options.adaptive ? new QualityGovernor(options.level, 0) : null;
  }

  /** SF-238-11 — le niveau de qualité courant. */
  get level(): QualityLevel {
    return this.governor?.level ?? this.options.level;
  }

  get frames(): number {
    return this.loop.frames;
  }

  start(): void {
    this.loop.start();
  }

  /** L'atlas des icônes officielles est prêt (SF-238-08) : il remplace les boîtes neutres. */
  setIcons(icons: ArchitectureIconSource): void {
    this.graph.setIcons(icons);
  }

  /**
   * SF-238-12 — une phrase de Jarvis commence (signal `spoken`) : ce qu'elle nomme s'allume tout de suite (T-A8 /
   * T-A7), ce qui ne l'est plus s'éteint. Rapprochement local, aucun réseau. Renvoie ce qui a été allumé.
   */
  narrate(phrase: string, at = this.options.clock()): { lit: string[]; flow: string[] } {
    this.narrator ??= new ArchitectureNarrator(this.options.layout);
    const result = this.narrator.read(phrase, this.base.focus);
    this.narrated = { lit: result.lit, flow: result.flow };
    this.setState(this.compose(), at);
    return result;
  }

  /**
   * SF-238-10 — l'état de la navigation change (toucher, voix, outil `afficher`) : la scène y va par la transition
   * nommée (T-A3 → T-A6, T-A9), sans saut ; ce que la narration allume est gardé.
   */
  setNavState(state: ArchState, at = this.options.clock()): void {
    this.base = state;
    this.setState(this.compose(), at);
  }

  private compose(): ArchState {
    const flow = [...new Set([...(this.base.flow ?? []), ...this.narrated.flow])];
    return { ...this.base, lit: [...this.narrated.lit], flow };
  }

  /** SF-238-10 — l'élément sous un point de l'écran (toucher) : service d'abord, sinon la zone la plus précise. */
  pick(x: number, y: number): { kind: 'node' | 'plate'; id: string } | null {
    return pickAt(this.options.layout, this.graph.frame, this.cameraNow, this.viewport(), x, y);
  }

  /** Banc — une narration rejouée : chaque phrase commence à son instant (ms), horloge figée comprise. */
  playScript(script: readonly { at: number; text: string }[]): void {
    this.script = script;
    this.scriptIndex = -1;
  }

  /** Ce qui est allumé (banc, mesure) : services et zones, puis liaisons des flux. */
  get lit(): string {
    const state = this.plan.state;
    return [...state.lit, ...(state.flow ?? [])].join(' ');
  }

  /**
   * Nouvel état (alloué au changement seulement). La lumière part de ce qui est affiché (narration, T-A8) ; un
   * changement de vue, de focus, d'éclatement, de repli, de zoom ou d'orbite démarre un déplacement depuis l'image
   * affichée (règle 2 : une transition interrompue repart de la valeur courante).
   */
  setState(state: ArchState, at = this.options.clock()): void {
    const director = this.graph.director;
    const previous = this.plan;
    const { ms: nominal, curve } = moveFor(previous.state, state);
    // SF-238-14 — au plus un grand déplacement par 2,5 s : un second qui suit de près ralentit.
    const ms = paceMove(this.lastMajorMove, at, nominal);
    if (ms > 0) {
      this.move = director.startMove(this.graph.frame, this.cameraNow, at, ms, curve);
      if (nominal >= MAJOR_MOVE_MS) {
        this.lastMajorMove = at;
      }
    }
    this.transition = { from: director.snapshot(previous, this.graph.frame), at };
    this.plan = director.plan(state, this.viewport());
    this.declutterDue = true;
  }

  /** La caméra affichée (banc, tests). */
  get shownCamera(): ArchCamera {
    return this.cameraNow;
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
    this.declutterDue = true;
  }

  dispose(): void {
    this.loop.stop();
    this.graph.dispose();
    this.post.dispose();
    this.renderer.dispose();
  }

  /** Pose la phrase du script en cours à l'instant `t` (la précédente donne l'état d'avant). */
  private followScript(t: number): void {
    if (this.script.length === 0) {
      return;
    }
    let index = -1;
    for (let i = 0; i < this.script.length && this.script[i].at <= t; i++) {
      index = i;
    }
    if (index === this.scriptIndex || index < 0) {
      return;
    }
    if (this.scriptIndex < 0 && index > 0) {
      this.narrate(this.script[index - 1].text, this.script[index - 1].at);
    }
    this.scriptIndex = index;
    this.narrate(this.script[index].text, this.script[index].at);
  }

  /** SF-238-11 — le gouverneur F-236 : descend (densité de pixels, lumière), remonte, ou renonce (repli 2D). */
  private adapt(dt: number, now: number): void {
    const verdict = this.governor?.sample(dt, now);
    if (!verdict || verdict.kind === 'keep') {
      return;
    }
    if (verdict.kind === 'fallback2d') {
      if (!this.gaveUp) {
        this.gaveUp = true;
        this.options.onFallback?.();
      }
      return;
    }
    this.renderer.setPixelRatio(Math.min(this.options.pixelRatio, levelBudget(verdict.level, this.options.phone)
      .pixelRatio));
    this.renderer.setSize(this.width, this.height, false);
    this.post.setLevel(verdict.level);
    this.post.setSize(this.width, this.height);
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
        this.adapt(dt, now);
      }
    }
    this.last = now;
    this.followScript(t);
    if (this.phase === 'entree' && t - this.phaseStart >= MOTION_DURATIONS.archEntry) {
      this.phase = 'repos'; // T-A1 finie : l'image est la même, les transitions de navigation peuvent jouer
    }
    this.graph.update(this.plan, this.phase, t - this.phaseStart, t / 1000, this.width / Math.max(1, this.height),
      this.transition, this.move);
    const moving = this.move !== null;
    const p = moveProgress(this.move, t);
    if (moving || this.cameraFor !== this.plan) {
      // La caméra suit le plan par la transition nommée (ressort T-A6 / T-A9, glisse T-A3) ; sinon, immobile.
      this.cameraFor = this.plan;
      const cam = moving && p < 1 ? blendCamera((this.move as ArchMove).fromCamera, this.plan.camera, p,
        this.cameraNow) : blendCamera(this.plan.camera, this.plan.camera, 1, this.cameraNow);
      if (p >= 1) {
        this.move = null;
      }
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
    // La lueur monte quand Jarvis allume quelque chose, redescend au repos (glissé, sans allocation).
    const target = this.plan.state.lit.length > 0 || (this.plan.state.flow?.length ?? 0) > 0 ? BLOOM_LIT : BLOOM_REST;
    this.bloom += (target - this.bloom) * 0.08;
    this.post.render(this.bloom, t);
    // Les icônes par-dessus la lumière (hors bloom), comptées dans le budget d'appels.
    const before = this.renderer.info.render.calls;
    this.renderer.autoClear = false;
    // La dernière passe plein écran a écrit la profondeur : on la vide pour que les icônes ne soient pas masquées.
    this.renderer.setRenderTarget(null);
    this.renderer.clearDepth();
    this.renderer.render(this.graph.iconScene, this.camera);
    this.renderer.autoClear = true;
    this.iconCalls = this.renderer.info.render.calls - before;
    this.calls = this.sceneCalls + this.iconCalls;
    this.labels?.render(this.graph.scene, this.camera);
    // Chevauchements : à l'image qui suit un changement de plan, puis toutes les 15 images tant que ça bouge.
    if (this.labels && (this.declutterDue || (this.phase !== 'repos' && this.loop.frames % 15 === 0))) {
      this.declutterDue = false;
      this.graph.declutterLabels();
    }
    if (!this.firstFrame) {
      this.firstFrame = true;
      this.options.onFirstFrame?.();
    }
  }
}
