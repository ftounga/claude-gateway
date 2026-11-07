import {
  AdditiveBlending, BufferAttribute, BufferGeometry, Group, LineSegments, Points, Scene, ShaderMaterial, Sphere, Vector3,
} from 'three';

import { HeartMood } from '../jarvis-heart';
import { FACE_COLORS, HOLO_COLORS, rgb } from '../jarvis-motion';
import { QualityLevel } from '../jarvis-render-capability';
import {
  FACE_STEP, LIP_Y, buildFaceFeatures, buildFaceGrid,
} from '../../face/jarvis-face-geometry';
import { MouthShape } from '../../face/jarvis-visemes';
import { FaceLook, faceLookAt, faceMoodTargets } from '../../face/jarvis-face-motion';

/**
 * F-250 / SF-250-04 / SF-250-05 — **le visage de Jarvis** (cadrage §4) : un visage holographique <b>générique</b>,
 * généré par code (nuage de points + fil de fer partageant les sommets + traits lumineux), style B « cyan
 * holographique » (choix PO du 2026-11-07, DESIGN_SYSTEM §23).
 *
 * <p>Toute la déformation (bouche, paupières, regard, sourcils, dissolution des bords, recomposition depuis le cœur)
 * se fait <b>dans le shader de sommets</b> : le processeur ne pose que des uniformes, zéro allocation par image.</p>
 */

/** Seuil du bloom quand le visage est là (maquette : 0,035 ; un peu plus haut pour épargner les étoiles du décor). */
export const FACE_BLOOM_THRESHOLD = 0.24;

/** Le visage occupe la place du cœur (rayon 1,4) : ≈ 2,8 unités de haut. */
export const FACE_SCALE = 1.5;

/** Aura (particules en orbite) par niveau ; aucune au niveau Bas (téléphone allégé, cadrage §5). */
const AURA: Record<QualityLevel, number> = { high: 1600, medium: 900, low: 0 };
/** Ondes (4 anneaux de points) : points par anneau. */
const RING_POINTS: Record<QualityLevel, number> = { high: 360, medium: 260, low: 180 };
const RINGS = 4;

/**
 * Gain de lumière du visage : la maquette sortait en sRGB (passe de sortie) ; le moteur écrit les couleurs telles
 * quelles, les tons moyens des points y paraissent ~2 fois plus sombres. Gain appliqué avant le mélange additif.
 */
const FACE_GAIN = 2.2;

const MOUTH_GLSL = /* glsl */ `
uniform vec4 uMouth;
vec3 deformMouth(vec3 p, float above) {
  float dyL = p.y - ${LIP_Y.toFixed(3)};
  float xr = p.x / 0.165;
  float slit = pow(max(0.0, 1.0 - xr * xr), 0.75);
  float open = uMouth.x * (1.0 - 0.92 * uMouth.w);
  float wm = exp(-(p.x * p.x) / 0.1024) * exp(-(dyL * dyL) / 0.0256);
  float dx = p.x * (0.24 * uMouth.y - 0.42 * uMouth.z) * wm;
  float dz = (0.075 * uMouth.z - 0.012 * uMouth.w) * exp(-(p.x * p.x) / 0.04) * exp(-(dyL * dyL) / 0.0081);
  float dy = 0.0;
  if (above > 0.5) {
    dy += open * 0.06 * slit * exp(-(dyL * dyL) / 0.0049);
  } else {
    float lowJaw = exp(-(p.x * p.x) / 0.1764) * smoothstep(0.0, -0.14, dyL);
    dy -= open * (0.13 * slit * exp(-(dyL * dyL) / 0.0081) + 0.12 * lowJaw);
    dz -= open * 0.04 * lowJaw;
  }
  return vec3(p.x + dx, p.y + dy, p.z + dz);
}`;

const GRID_VERTEX = /* glsl */ `
attribute float aSeed;
attribute float aEdge;
attribute float aHot;
attribute float aAbove;
attribute vec2 aLit;
attribute vec3 aFrom;
uniform float uTime, uPix, uScanY, uScanA, uFlicker, uMouthGlow, uThink, uSize, uAssemble, uDissolve, uDensity, uWorld;
varying float vA;
varying float vHot;
varying float vThink;
${MOUTH_GLSL}
void main() {
  vec3 p = position;
  if (p.y < 0.02 && abs(p.x) < 0.62) {
    p = deformMouth(p, aAbove);
  }
  // Bords qui se dissolvent en particules.
  float e = aEdge;
  if (e > 0.8) {
    float amt = (e - 0.8) / 0.2 * (0.03 + 0.03 * sin(uTime * 0.6 + aSeed * 40.0)) * uDissolve;
    p += vec3(sign(position.x) * amt, amt * 0.6 * sin(uTime * 0.4 + aSeed * 17.0), -amt * 1.4);
  }
  // T-F1 : les points du cœur se recomposent en visage (décalés par leur graine).
  float k = clamp(uAssemble * 1.6 - aSeed * 0.6, 0.0, 1.0);
  k = k * k * (3.0 - 2.0 * k);
  p = mix(aFrom, p, k);
  vec4 wp = modelMatrix * vec4(p, 1.0);
  vec4 mv = viewMatrix * wp;
  gl_Position = projectionMatrix * mv;
  float fl = mix(1.0, 0.55 + 0.45 * sin(uTime * (1.1 + aSeed * 2.9) + aSeed * 61.0), uFlicker);
  float scan = 0.42 * uScanA * exp(-pow((position.y - uScanY) / 0.07, 2.0));
  float edge = 1.0 - smoothstep(0.72, 1.0, aEdge);
  vec2 dm = position.xy - vec2(0.0, ${LIP_Y.toFixed(3)}); dm.x *= 0.7;
  float mg = uMouthGlow * exp(-dot(dm, dm) / 0.012);
  float syn = uThink * pow(max(0.0, sin(uTime * 3.1 + aSeed * 40.0 + position.y * 9.0)), 24.0);
  float shade = 0.025 + 0.75 * aLit.x * aLit.x * aLit.x + 0.6 * aLit.y * aLit.y;
  float dens = 1.0 - step(uDensity, aSeed);
  vA = ((shade + 0.35 * aHot) * fl * (0.3 + 0.7 * edge) + scan * 0.8 + mg * 0.8 + syn * 0.9) * dens;
  vHot = aHot * 0.8 + scan + mg + syn * 0.5;
  vThink = syn;
  gl_PointSize = uSize * (0.8 + aHot * 0.6 + aLit.y * 0.6 + scan * 1.2 + mg) * uPix * (4.2 * uWorld / max(-mv.z, 0.5));
}`;

const GRID_POINTS_FRAGMENT = /* glsl */ `
uniform vec3 uColor, uHot, uDeep;
uniform float uAlpha;
varying float vA;
varying float vHot;
varying float vThink;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = smoothstep(0.5, 0.0, d);
  a *= a;
  vec3 c = mix(uColor, uHot, clamp(vHot * 0.65, 0.0, 1.0));
  c = mix(c, uDeep, clamp(vThink, 0.0, 1.0));
  gl_FragColor = vec4(c * a * vA * uAlpha * ${FACE_GAIN.toFixed(2)}, 1.0);
}`;

const GRID_LINES_FRAGMENT = /* glsl */ `
uniform vec3 uColor, uHot, uDeep;
uniform float uAlpha;
varying float vA;
varying float vHot;
varying float vThink;
void main() {
  vec3 c = mix(uColor, uHot, clamp(vHot * 0.4, 0.0, 1.0));
  c = mix(c, uDeep, clamp(vThink, 0.0, 1.0));
  gl_FragColor = vec4(c * vA * uAlpha * ${FACE_GAIN.toFixed(2)}, 1.0);
}`;

/** Les traits : chaque famille se déforme selon ses uniformes (cf. FEATURE_KIND). */
const FEATURE_VERTEX = /* glsl */ `
attribute vec3 aOther;
attribute float aKind;
attribute float aAbove;
attribute vec3 aParam;
attribute float aAlpha;
attribute float aH;
uniform float uLid, uBrow, uBrowR, uLipA, uEyeA, uAssemble, uPix, uDotSize, uWorld;
uniform vec2 uGaze;
varying float vA;
varying float vH;
${MOUTH_GLSL}
void main() {
  vec3 p = position;
  float a = aAlpha;
  float kind = aKind;
  if (kind < 0.5) {
    p = deformMouth(p, aAbove);
    a *= uLipA;
  } else if (kind < 1.5) {
    p.y += (uLid - 1.0) * aParam.x;
  } else if (kind < 3.5 && kind > 2.5) {
    p.y += uBrow * aParam.x;
  } else if (kind > 3.5 && kind < 5.5) {
    float ry = min(1.0, uLid * 1.1);
    p.x += uGaze.x + aParam.x;
    p.y += uGaze.y + aParam.y * ry;
    p.z += aParam.z;
    a *= uEyeA * step(0.25, uLid);
  } else if (kind > 5.5 && kind < 6.5) {
    p.y += uBrow + aParam.y * uBrowR * aParam.x;
  } else if (kind > 7.5) {
    vec3 up = deformMouth(position, 1.0);
    vec3 lo = deformMouth(aOther, 0.0);
    p = mix(up, lo, aParam.x);
    float o = uMouth.x * (1.0 - 0.92 * uMouth.w);
    a *= o * smoothstep(0.06, 0.12, o) * uLipA;
  }
  a *= smoothstep(0.55, 1.0, uAssemble);
  vA = a;
  vH = aH;
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = uDotSize * (1.6 + 1.6 * aH) * uPix * (4.2 * uWorld / max(-mv.z, 0.5));
}`;

const FEATURE_LINES_FRAGMENT = /* glsl */ `
uniform vec3 uColor, uHot;
varying float vA;
varying float vH;
void main() {
  gl_FragColor = vec4(mix(uColor, uHot, vH) * vA, 1.0);
}`;

const FEATURE_DOTS_FRAGMENT = /* glsl */ `
uniform vec3 uColor, uHot;
varying float vA;
varying float vH;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = smoothstep(0.5, 0.0, d);
  gl_FragColor = vec4(mix(uColor, uHot, vH) * a * a * vA * 0.55, 1.0);
}`;

/**
 * Aura (particules en orbite) et ondes (anneaux de points) dans <b>un seul</b> appel de dessin : `aP.w < 0` marque
 * un point d'onde (anneau `aP.x`, angle `aP.y`).
 */
const HALO_VERTEX = /* glsl */ `
attribute vec4 aP;
uniform float uPhase, uTime, uPix, uPull, uRingPhase, uRingMode, uRingLevel, uAuraA, uAssemble, uWorld;
uniform vec3 uRingCenter;
varying float vA;
varying float vS;
varying float vRing;
void main() {
  vec3 p;
  if (aP.w < 0.0) {
    float f = fract(uRingPhase + aP.x / ${RINGS}.0);
    float r = uRingMode < 1.5 ? mix(2.3, 0.95, f) : mix(0.12, 1.9, f);
    float wob = 1.0 + 0.04 * sin(aP.y * 7.0 + uRingPhase * 20.0) * uRingLevel;
    p = uRingCenter + vec3(cos(aP.y) * r * wob, sin(aP.y) * r * wob * 0.92,
      (uRingMode < 1.5 ? 0.6 * (1.0 - f) : 0.4 * f));
    vA = step(0.5, uRingMode) * sin(f * 3.14159) * (0.3 + 0.7 * uRingLevel) * (uRingMode < 1.5 ? 0.95 : 0.6);
    vS = 0.0;
    vRing = 1.0;
    vec4 mv = modelViewMatrix * vec4(p, 1.0);
    gl_Position = projectionMatrix * mv;
    gl_PointSize = 2.6 * uPix * (4.2 * uWorld / max(-mv.z, 0.5));
  } else {
    float th = aP.x + uPhase * (0.25 + aP.w * 0.6) * (aP.w > 0.5 ? 1.0 : -1.0);
    float ph = aP.y + 0.25 * sin(uTime * 0.2 + aP.w * 20.0);
    float r = aP.z * mix(1.0, 0.82, uPull);
    p = vec3(sin(ph) * cos(th) * r * 0.95, cos(ph) * r * 0.85 + 0.17, sin(ph) * sin(th) * r * 0.75 - 0.15);
    vS = aP.w;
    vRing = 0.0;
    vA = (0.15 + 0.5 * aP.w) * smoothstep(2.6, 1.15, r) * (0.6 + 0.4 * sin(uTime * 1.7 + aP.w * 80.0)) * uAuraA;
    vec4 mv = modelViewMatrix * vec4(p, 1.0);
    gl_Position = projectionMatrix * mv;
    gl_PointSize = (1.2 + aP.w * 2.2) * uPix * (4.2 * uWorld / max(-mv.z, 0.5));
  }
  vA *= uAssemble;
}`;

const HALO_FRAGMENT = /* glsl */ `
uniform vec3 uColor, uRingColor, uDeep;
uniform float uThink;
varying float vA;
varying float vS;
varying float vRing;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = smoothstep(0.5, 0.0, d);
  a = mix(a * a, a, vRing);
  vec3 c = mix(mix(uColor, uDeep, uThink * step(0.4, vS)), uRingColor, vRing);
  gl_FragColor = vec4(c * a * vA * ${FACE_GAIN.toFixed(2)}, 1.0);
}`;

/** Cadran tournant et socle du projecteur : une géométrie, trois groupes tournés dans le shader. */
const DIAL_VERTEX = /* glsl */ `
attribute float aGroup;
uniform float uDialA, uDialB;
void main() {
  vec3 p = position;
  if (aGroup < 1.5) {
    float a = aGroup < 0.5 ? uDialA : uDialB;
    float c = cos(a);
    float s = sin(a);
    p = vec3(c * p.x - s * p.y, s * p.x + c * p.y, 0.0) + vec3(0.0, 0.17, -0.55);
  } else {
    p = vec3(p.x, -1.02, -p.y);
  }
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}`;

const DIAL_FRAGMENT = /* glsl */ `
uniform vec3 uColor;
uniform float uOpacity;
void main() {
  gl_FragColor = vec4(uColor * uOpacity * ${FACE_GAIN.toFixed(2)}, 1.0);
}`;

/** Ce que le visage lit à chaque image (rien n'est alloué : l'appelant réutilise ses objets). */
export interface FaceFrame {
  /** Horloge de la scène (ms). */
  now: number;
  /** Pas (ms), borné. */
  dt: number;
  /** Niveau du micro ou de la voix (0-1). */
  level: number;
  /** La bouche (visèmes, ou niveau sonore en repli). */
  mouth: MouthShape;
  /** Mouvements réduits (aucun balancement, aucun scintillement). */
  reduced: boolean;
}

export class Face3d {
  readonly group = new Group();
  private readonly grid: ShaderMaterial;
  private readonly wire: ShaderMaterial;
  private readonly lines: ShaderMaterial;
  private readonly dots: ShaderMaterial | null;
  private readonly halo: ShaderMaterial;
  private readonly dial: ShaderMaterial | null;
  private readonly look: FaceLook;
  private readonly wireObject: LineSegments;
  private mood: HeartMood = 'repos';
  private moodSince = 0;
  /** 0 = cœur, 1 = visage (T-F1) ; posé par le moteur. */
  presence = 0;
  /** Lumière du visage (0-1), lue par le décor comme celle du cœur. */
  light = 0.8;
  /** Bloom souhaité. */
  bloom = 1.05;
  /** Échelle du visage dans le monde : la taille des points la suit (même rendu qu'à l'échelle de la maquette). */
  private readonly world = { value: 1 };
  /** Décalage latéral (pancartes) et regard vers elles (SF-250-07), posés par le moteur. */
  aside = 0;
  private asideNow = 0;

  constructor(scene: Scene, random: () => number, private readonly level: QualityLevel, pixelRatio: number,
              heartRadius: number) {
    const low = level === 'low';
    const grid = buildFaceGrid(FACE_STEP[level], low ? 1 : 2, heartRadius / FACE_SCALE, random);
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(grid.rest, 3));
    geometry.setAttribute('aSeed', new BufferAttribute(grid.seed, 1));
    geometry.setAttribute('aEdge', new BufferAttribute(grid.edge, 1));
    geometry.setAttribute('aHot', new BufferAttribute(grid.hot, 1));
    geometry.setAttribute('aLit', new BufferAttribute(grid.lit, 2));
    geometry.setAttribute('aAbove', new BufferAttribute(grid.above, 1));
    geometry.setAttribute('aFrom', new BufferAttribute(grid.from, 3));
    geometry.boundingSphere = new Sphere(new Vector3(0, 0.15, 0), 1.7);
    const shared = () => ({
      uTime: { value: 0 }, uPix: { value: pixelRatio }, uScanY: { value: 1.2 }, uScanA: { value: 1 },
      uFlicker: { value: 1 }, uMouthGlow: { value: 0 }, uThink: { value: 0 }, uAssemble: { value: 0 },
      uDissolve: { value: 1 }, uDensity: { value: 1.01 }, uMouth: { value: [0, 0, 0, 0] },
      uColor: { value: rgb(FACE_COLORS.base) }, uHot: { value: rgb(FACE_COLORS.hot) },
      uDeep: { value: rgb(HOLO_COLORS.deep) }, uWorld: this.world,
    });
    this.grid = new ShaderMaterial({
      vertexShader: GRID_VERTEX, fragmentShader: GRID_POINTS_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: { ...shared(), uSize: { value: low ? 2.6 : 2.2 }, uAlpha: { value: FACE_COLORS.pointAlpha } },
    });
    this.wire = new ShaderMaterial({
      vertexShader: GRID_VERTEX.replace('gl_PointSize', 'float unusedSize'), fragmentShader: GRID_LINES_FRAGMENT,
      transparent: true, depthWrite: false, blending: AdditiveBlending,
      uniforms: { ...shared(), uSize: { value: 1 }, uAlpha: { value: FACE_COLORS.lineAlpha } },
    });
    const wireGeometry = new BufferGeometry();
    for (const name of ['position', 'aSeed', 'aEdge', 'aHot', 'aLit', 'aAbove', 'aFrom']) {
      wireGeometry.setAttribute(name, geometry.getAttribute(name));
    }
    wireGeometry.setIndex(new BufferAttribute(grid.wire, 1));
    wireGeometry.boundingSphere = geometry.boundingSphere;
    this.wireObject = new LineSegments(wireGeometry, this.wire);
    this.group.add(this.wireObject);
    this.group.add(new Points(geometry, this.grid));

    const features = buildFaceFeatures(low ? 0.7 : 1);
    const fg = new BufferGeometry();
    fg.setAttribute('position', new BufferAttribute(features.position, 3));
    fg.setAttribute('aOther', new BufferAttribute(features.other, 3));
    fg.setAttribute('aKind', new BufferAttribute(features.kind, 1));
    fg.setAttribute('aAbove', new BufferAttribute(features.above, 1));
    fg.setAttribute('aParam', new BufferAttribute(features.param, 3));
    fg.setAttribute('aAlpha', new BufferAttribute(features.alpha, 1));
    fg.setAttribute('aH', new BufferAttribute(features.hot, 1));
    fg.boundingSphere = geometry.boundingSphere;
    const featureUniforms = {
      uLid: { value: 0.93 }, uBrow: { value: 0 }, uBrowR: { value: 0 }, uLipA: { value: 0.75 }, uEyeA: { value: 0.8 },
      uGaze: { value: [0, 0] }, uMouth: this.grid.uniforms['uMouth'], uAssemble: this.grid.uniforms['uAssemble'],
      uPix: { value: pixelRatio }, uDotSize: { value: 1 }, uWorld: this.world,
      uColor: { value: rgb(FACE_COLORS.accent) }, uHot: { value: rgb(FACE_COLORS.hot) },
    };
    this.lines = new ShaderMaterial({
      vertexShader: FEATURE_VERTEX.replace('gl_PointSize', 'float unusedSize'), fragmentShader: FEATURE_LINES_FRAGMENT,
      transparent: true, depthWrite: false, blending: AdditiveBlending, uniforms: featureUniforms,
    });
    this.group.add(new LineSegments(fg, this.lines));
    // Les points des traits (une lueur le long des courbes) : PC et niveau moyen seulement.
    this.dots = low ? null : new ShaderMaterial({
      vertexShader: FEATURE_VERTEX, fragmentShader: FEATURE_DOTS_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending, uniforms: featureUniforms,
    });
    if (this.dots) {
      this.group.add(new Points(fg, this.dots));
    }

    this.halo = this.buildHalo(random, pixelRatio);
    this.dial = low ? null : this.buildDial();
    this.look = faceMoodTargets('repos');
    this.group.visible = false;
    scene.add(this.group);
  }

  /** Le moteur a posé l'échelle finale du groupe : la taille des points la suit. */
  syncScale(): void {
    this.world.value = this.group.scale.x;
  }

  /** Nombre de points du visage (mesure, banc). */
  get points(): number {
    return (this.group.children[1] as Points).geometry.getAttribute('position').count;
  }

  setMood(mood: HeartMood, now: number): void {
    if (mood !== this.mood) {
      this.mood = mood;
      this.moodSince = now;
    }
  }

  /** T-Q — la densité suit le niveau (fondu fait par l'appelant ; ici une coupe par graine). */
  setDensity(fraction: number): void {
    this.grid.uniforms['uDensity'].value = Math.min(1.01, fraction + 0.01);
  }

  /**
   * Une image. `presence` (0-1) a été posée par le moteur ; sous 0,01 le visage n'est pas dessiné. `facing` =
   * lacet de la caméra, `pitch` = son tangage (le visage regarde toujours l'écran).
   */
  frame(input: FaceFrame, facing: number, pitch = 0): void {
    const visible = this.presence > 0.01;
    this.group.visible = visible;
    if (!visible) {
      return;
    }
    const t = input.now / 1000;
    faceLookAt(this.look, this.mood, (input.now - this.moodSince) / 1000, t, input.dt / 1000, input.level,
      input.mouth, input.reduced);
    const look = this.look;
    const mv = input.reduced ? 0 : 1;
    // Tête : respiration, balancement lent, hochements discrets pendant la parole ; face à la caméra.
    const breath = Math.sin((t * Math.PI * 2) / 4.2);
    this.asideNow += (this.aside - this.asideNow) * (1 - Math.exp(-input.dt / 240));
    this.group.rotation.set(
      // Le visage regarde la caméra : il se penche vers elle quand elle est en hauteur.
      -pitch * 0.85 + mv * (Math.sin(t * 0.17) * 0.035 + 0.02 * look.mouth.open) + look.tilt,
      facing + mv * (Math.sin(t * 0.21) * 0.17 + Math.sin(t * 0.53) * 0.035) + (input.reduced ? 0.14 : 0)
        - 0.35 * this.asideNow,
      mv * look.roll, 'YXZ');
    const scale = FACE_SCALE * (1 - 0.4 * this.asideNow) * (1 + mv * breath * 0.006);
    this.group.scale.setScalar(scale);
    this.group.position.set(Math.cos(facing) * 2.6 * this.asideNow, mv * breath * 0.012 * FACE_SCALE,
      -Math.sin(facing) * 2.6 * this.asideNow);

    const assemble = Math.min(1, this.presence);
    const u = this.grid.uniforms;
    const mouth = u['uMouth'].value as number[];
    mouth[0] = look.mouth.open;
    mouth[1] = look.mouth.wide;
    mouth[2] = look.mouth.round;
    mouth[3] = look.mouth.press;
    for (const m of [this.grid, this.wire]) {
      const g = m.uniforms;
      g['uTime'].value = t * mv;
      g['uScanY'].value = look.scanY;
      g['uScanA'].value = input.reduced ? 0 : FACE_COLORS.scan;
      g['uFlicker'].value = input.reduced ? 0 : 1;
      g['uMouthGlow'].value = look.glow * 0.9;
      g['uThink'].value = input.reduced ? 0 : look.think;
      g['uAssemble'].value = assemble;
      g['uDissolve'].value = mv;
    }
    this.grid.uniforms['uAlpha'].value = FACE_COLORS.pointAlpha * look.points;
    this.wire.uniforms['uAlpha'].value = FACE_COLORS.lineAlpha * look.wire * assemble;
    this.wireObject.visible = look.wire > 0.01;
    const f = this.lines.uniforms;
    f['uLid'].value = look.lid;
    f['uBrow'].value = look.brow;
    f['uBrowR'].value = look.browR;
    f['uLipA'].value = look.lipA * look.points;
    f['uEyeA'].value = look.eyeA * look.points;
    const gaze = f['uGaze'].value as number[];
    gaze[0] = look.gx;
    gaze[1] = look.gy;
    // « Oui » attendu : la teinte ne change pas (le cyan est celle du visage, §23) ; l'attente se lit à la posture
    // (yeux ouverts, sourcils levés, ondes lentes) et le mot reste écrit dans la pastille.

    const h = this.halo.uniforms;
    h['uPhase'].value = look.auraPhase;
    h['uTime'].value = t * mv;
    h['uAuraA'].value = look.auraA * (input.reduced ? 0.5 : 1) * look.points;
    h['uPull'].value = look.pull;
    h['uThink'].value = input.reduced ? 0 : look.think;
    h['uRingPhase'].value = look.ringPhase;
    h['uRingMode'].value = input.reduced ? 0 : look.ringMode;
    h['uRingLevel'].value = look.ringLevel;
    h['uAssemble'].value = assemble;
    const center = h['uRingCenter'].value as number[];
    center[0] = 0;
    center[1] = look.ringMode > 1.5 ? LIP_Y : 0.12;
    center[2] = look.ringMode > 1.5 ? 0.55 : 0.5;
    if (this.dial) {
      this.dial.uniforms['uDialA'].value = look.dialA;
      this.dial.uniforms['uDialB'].value = look.dialB;
      this.dial.uniforms['uOpacity'].value = 0.22 * assemble * look.points;
    }
    this.light = (0.55 + 0.45 * look.glow) * look.points;
    this.bloom = (this.level === 'low' ? 0.8 : 1.05) + look.glow * 0.35 + look.think * 0.15;
  }

  private buildHalo(random: () => number, pixelRatio: number): ShaderMaterial {
    const aura = AURA[this.level];
    const ring = RING_POINTS[this.level];
    const total = aura + RINGS * ring;
    const p = new Float32Array(total * 4);
    for (let i = 0; i < aura; i++) {
      p[i * 4] = random() * Math.PI * 2;
      p[i * 4 + 1] = Math.acos(2 * random() - 1);
      p[i * 4 + 2] = 1.15 + Math.pow(random(), 1.6) * 1.3;
      p[i * 4 + 3] = random();
    }
    for (let r = 0; r < RINGS; r++) {
      for (let i = 0; i < ring; i++) {
        const k = aura + r * ring + i;
        p[k * 4] = r;
        p[k * 4 + 1] = (i / ring) * Math.PI * 2;
        p[k * 4 + 3] = -1;
      }
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(total * 3), 3));
    geometry.setAttribute('aP', new BufferAttribute(p, 4));
    geometry.boundingSphere = new Sphere(new Vector3(), 5);
    const material = new ShaderMaterial({
      vertexShader: HALO_VERTEX, fragmentShader: HALO_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uPhase: { value: 0 }, uTime: { value: 0 }, uPix: { value: pixelRatio }, uPull: { value: 0 },
        uRingPhase: { value: 0 }, uRingMode: { value: 0 }, uRingLevel: { value: 0 }, uAuraA: { value: 0.5 },
        uAssemble: { value: 0 }, uThink: { value: 0 }, uRingCenter: { value: [0, 0.12, 0.5] },
        uColor: { value: rgb(FACE_COLORS.aura) }, uRingColor: { value: rgb(FACE_COLORS.accent) },
        uDeep: { value: rgb(HOLO_COLORS.deep) }, uWorld: this.world,
      },
    });
    const points = new Points(geometry, material);
    points.frustumCulled = false;
    this.group.add(points);
    return material;
  }

  private buildDial(): ShaderMaterial {
    const pos: number[] = [];
    const group: number[] = [];
    const segment = (a: number[], b: number[], g: number): void => {
      pos.push(a[0], a[1], 0, b[0], b[1], 0);
      group.push(g, g);
    };
    const ring = (r: number, n: number, dash: number, g: number): void => {
      for (let i = 0; i < n; i++) {
        if (dash && i % dash === dash - 1) {
          continue;
        }
        const a0 = (i / n) * Math.PI * 2;
        const a1 = ((i + 1) / n) * Math.PI * 2;
        segment([Math.cos(a0) * r, Math.sin(a0) * r], [Math.cos(a1) * r, Math.sin(a1) * r], g);
      }
    };
    ring(1.42, 180, 0, 0);
    ring(1.5, 120, 4, 0);
    for (let i = 0; i < 72; i++) {
      const a = (i / 72) * Math.PI * 2;
      const l = i % 6 === 0 ? 0.09 : 0.035;
      segment([Math.cos(a) * 1.55, Math.sin(a) * 1.55], [Math.cos(a) * (1.55 + l), Math.sin(a) * (1.55 + l)], 0);
    }
    for (const [a0, a1] of [[0.2, 0.9], [1.4, 1.75], [2.5, 3.3], [3.9, 4.15], [4.6, 5.6]]) {
      for (let i = 0; i < 20; i++) {
        const u = a0 + ((a1 - a0) * i) / 20;
        const v = a0 + ((a1 - a0) * (i + 1)) / 20;
        segment([Math.cos(u) * 1.34, Math.sin(u) * 1.34], [Math.cos(v) * 1.34, Math.sin(v) * 1.34], 1);
      }
    }
    for (const [r, d] of [[0.55, 0], [0.75, 3], [0.95, 0], [1.25, 6]]) {
      ring(r, 140, d, 2);
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(pos), 3));
    geometry.setAttribute('aGroup', new BufferAttribute(new Float32Array(group), 1));
    geometry.boundingSphere = new Sphere(new Vector3(), 3);
    const material = new ShaderMaterial({
      vertexShader: DIAL_VERTEX, fragmentShader: DIAL_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: { uDialA: { value: 0 }, uDialB: { value: 0 }, uOpacity: { value: 0.22 }, uColor: { value: rgb(FACE_COLORS.line) } },
    });
    this.group.add(new LineSegments(geometry, material));
    return material;
  }
}
