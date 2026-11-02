import {
  AdditiveBlending, BufferAttribute, BufferGeometry, Group, LineBasicMaterial, LineLoop, LineSegments, Mesh, Points,
  Scene, ShaderMaterial, SphereGeometry,
} from 'three';

import { BrainEnd, BrainFrame, nearestPairs } from '../jarvis-brains';
import { HOLO_COLORS, rgb } from '../jarvis-motion';
import { QualityLevel } from '../jarvis-render-capability';
import { HEART_RADIUS, put } from './jarvis-heart-3d';

/**
 * F-236 / SF-236-03 — **le grand cerveau** (cadrage §10) : champ neuronal violet de rayon 3,2 déployé autour du petit
 * cœur, connexions denses avec éclairs synaptiques, gyroscope de 2 anneaux de plus (5 en tout), enveloppe de
 * Fresnel, onde de choc à l'étincelle, une perle par délégation. Positions de repos ET déployées précalculées,
 * interpolées par uniformes : zéro allocation par image.
 */

/** Rayon déployé (§10.1). */
export const DEEP_RADIUS = 3.2;

/** Budgets §6.1 / §10.1 : particules et connexions par niveau. */
export const DEEP_BUDGET: Record<QualityLevel, { particles: number; links: number }> = {
  high: { particles: 12000, links: 8000 },
  medium: { particles: 2500, links: 1500 },
  low: { particles: 1200, links: 600 },
};

const MAX_PEARLS = 6;

const FIELD_VERTEX = /* glsl */ `
attribute vec3 aRest;
attribute float aDelay;
attribute float aPhase;
attribute float aSize;
attribute float aHeat;
uniform float uTime;
uniform float uDeploy;
uniform float uBreathPhase;
uniform float uSpin;
uniform float uGather;
uniform float uPixelRatio;
uniform float uHue;
uniform vec3 uNormal;
uniform vec3 uDeep;
uniform vec3 uDeepCore;
uniform float uGain;
varying vec3 vColor;
varying float vAlpha;
void main() {
  float local = clamp((uDeploy - aDelay * 0.25) / 0.75, 0.0, 1.0);
  vec3 p = mix(aRest, position, local);
  p *= (1.0 + 0.03 * sin(uBreathPhase + aPhase * 0.2)) * (1.0 - 0.18 * uGather);
  float c = cos(uSpin);
  float s = sin(uSpin);
  p = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = min(aSize * uPixelRatio * (20.0 / max(-mv.z, 0.5)), 14.0 * uPixelRatio);
  vColor = mix(mix(uNormal, uDeep, uHue), uDeepCore, aHeat * uHue);
  // SF-236-14 — réserve du PO (itération 1) : le centre du grand cerveau saturait sur PC (densité + chaleur sous le
  // bloom) ; les particules du cœur chaud sont atténuées quand le champ est déployé, la périphérie reste intacte.
  vAlpha = uGain * local * (0.65 + 0.35 * sin(uTime * 0.0021 + aPhase * 6.2831)) * (1.0 - 0.6 * aHeat * uHue);
}`;

const FIELD_FRAGMENT = /* glsl */ `
varying vec3 vColor;
varying float vAlpha;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.0, 0.5, d);
  gl_FragColor = vec4(vColor * a * a * vAlpha, 1.0);
}`;

const LINK_VERTEX = /* glsl */ `
attribute vec3 aRest;
attribute float aDelay;
attribute float aOrder;
attribute float aT;
attribute float aPhase;
uniform float uDeploy;
uniform float uBreathPhase;
uniform float uSpin;
uniform float uGather;
uniform float uReveal;
varying float vT;
varying float vPhase;
varying float vShow;
void main() {
  float local = clamp((uDeploy - aDelay * 0.25) / 0.75, 0.0, 1.0);
  vec3 p = mix(aRest, position, local);
  p *= (1.0 + 0.03 * sin(uBreathPhase + aPhase * 0.2)) * (1.0 - 0.18 * uGather);
  float c = cos(uSpin);
  float s = sin(uSpin);
  p = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
  vT = aT;
  vPhase = aPhase;
  vShow = smoothstep(aOrder - 0.12, aOrder, uReveal) * local;
}`;

const LINK_FRAGMENT = /* glsl */ `
uniform float uTime;
uniform float uHue;
uniform float uGather;
uniform vec3 uNormal;
uniform vec3 uDeep;
uniform vec3 uDeepCore;
varying float vT;
varying float vPhase;
varying float vShow;
void main() {
  // Éclair synaptique : une étincelle qui court le long de la connexion (vers le centre au rassemblement).
  float head = fract(uTime * 0.00055 + vPhase);
  float along = mix(vT, 1.0 - vT, uGather);
  float spark = exp(-pow((along - head) * 9.0, 2.0));
  vec3 base = mix(uNormal, uDeep, uHue);
  vec3 color = base * 0.12 + mix(base, uDeepCore, 0.5) * spark * (0.6 + uGather);
  gl_FragColor = vec4(color * vShow, 1.0);
}`;

const SHELL_VERTEX = /* glsl */ `
varying float vRim;
void main() {
  vec3 n = normalize(normalMatrix * normal);
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  vRim = 1.0 - abs(dot(n, normalize(-mv.xyz)));
  gl_Position = projectionMatrix * mv;
}`;

const SHELL_FRAGMENT = /* glsl */ `
uniform vec3 uColor;
uniform float uOpacity;
uniform float uPower;
varying float vRim;
void main() {
  gl_FragColor = vec4(uColor * pow(vRim, uPower) * uOpacity, 1.0);
}`;

const PEARL_VERTEX = /* glsl */ `
attribute float aSize;
uniform float uPixelRatio;
void main() {
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = aSize * uPixelRatio * (20.0 / max(-mv.z, 0.5));
}`;

const PEARL_FRAGMENT = /* glsl */ `
uniform vec3 uColor;
uniform float uOpacity;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.1, 0.5, d);
  gl_FragColor = vec4(uColor * a * uOpacity, 1.0);
}`;

const GYRO_RADII = [3.6, 3.95];

export class Brain3d {
  readonly group = new Group();
  private readonly field: ShaderMaterial;
  private readonly links: ShaderMaterial;
  private readonly envelope: ShaderMaterial;
  private readonly shock: ShaderMaterial;
  private readonly shockMesh: Mesh;
  private readonly pearlMaterial: ShaderMaterial;
  private readonly pearlPositions: Float32Array;
  private readonly pearlGeometry: BufferGeometry;
  private readonly gyros: LineLoop[] = [];
  private readonly gyroMaterials: LineBasicMaterial[] = [];
  private readonly pearlColors: Record<BrainEnd, number[]> = {
    normal: rgb(HOLO_COLORS.core), cancel: rgb(HOLO_COLORS.off), fail: rgb(HOLO_COLORS.alert),
  };
  private spin = 0;
  private breathPhase = 0;
  private orbit = 0;
  private time = 0;

  constructor(scene: Scene, random: () => number, level: QualityLevel, pixelRatio: number) {
    const budget = DEEP_BUDGET[level];
    const field = this.buildField(budget.particles, random);
    this.field = this.fieldMaterial(pixelRatio);
    // Lumière totale constante quel que soit le nombre de particules (sinon 12 000 points additifs saturent).
    this.field.uniforms['uGain'].value = Math.min(1, Math.sqrt(1200 / budget.particles));
    this.group.add(new Points(field.geometry, this.field));
    this.links = this.buildLinks(field, budget.links, random);
    this.envelope = this.buildEnvelope();
    const shock = this.buildShock();
    this.shock = shock.material;
    this.shockMesh = shock.mesh;
    this.buildGyros();
    this.pearlPositions = new Float32Array(MAX_PEARLS * 3);
    this.pearlGeometry = new BufferGeometry();
    this.pearlGeometry.setAttribute('position', new BufferAttribute(this.pearlPositions, 3));
    this.pearlGeometry.setAttribute('aSize', new BufferAttribute(new Float32Array(MAX_PEARLS).fill(7), 1));
    this.pearlGeometry.setDrawRange(0, 0);
    this.pearlMaterial = new ShaderMaterial({
      vertexShader: PEARL_VERTEX, fragmentShader: PEARL_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: { uPixelRatio: { value: pixelRatio }, uColor: { value: rgb(HOLO_COLORS.core) }, uOpacity: { value: 0 } },
    });
    const pearls = new Points(this.pearlGeometry, this.pearlMaterial);
    pearls.frustumCulled = false;
    this.group.add(pearls);
    this.group.visible = false;
    scene.add(this.group);
  }

  /** Une image : `f` = la chorégraphie à cet instant (jarvis-brains.ts), `pearls` délégations vivantes. */
  frame(dt: number, f: BrainFrame, pearls: number, variant: BrainEnd): void {
    const active = f.deploy > 0.001 || f.shock >= 0 || f.pearl > 0.01 || f.flash > 0.01;
    this.group.visible = active;
    if (!active) {
      return;
    }
    this.time += dt;
    this.spin += (dt / 1000) * 0.12;
    this.breathPhase += (dt * Math.PI * 2) / 3000;
    this.orbit += (dt / 1000) * 0.6;
    for (const m of [this.field, this.links]) {
      const u = m.uniforms;
      u['uDeploy'].value = f.deploy;
      u['uBreathPhase'].value = this.breathPhase;
      u['uSpin'].value = this.spin;
      u['uGather'].value = f.gather;
      u['uHue'].value = f.hue;
    }
    this.field.uniforms['uTime'].value = this.time;
    this.links.uniforms['uTime'].value = this.time;
    this.links.uniforms['uReveal'].value = f.reveal * 1.12;

    this.envelope.uniforms['uOpacity'].value = 0.22 * f.deploy * f.hue;

    for (let i = 0; i < this.gyros.length; i++) {
      const g = this.gyros[i];
      g.rotation.set(this.spin * (i === 0 ? 1.3 : -0.9) + 0.6, this.spin * (i === 0 ? 0.7 : 1.1), this.spin * 0.5);
      g.scale.setScalar(GYRO_RADII[i] * (0.6 + 0.4 * f.rings));
      this.gyroMaterials[i].opacity = 0.4 * f.rings;
    }

    if (f.shock >= 0 && f.shock < 1) {
      this.shockMesh.visible = true;
      this.shockMesh.scale.setScalar(HEART_RADIUS + f.shock * 9);
      this.shock.uniforms['uOpacity'].value = (1 - f.shock) * 2.2;
    } else {
      this.shockMesh.visible = false;
    }

    // Une perle par délégation vivante, en orbite autour de l'enveloppe ; au dépôt, elle rentre dans le noyau.
    const count = Math.min(MAX_PEARLS, Math.max(pearls, f.pearl > 0.01 ? 1 : 0));
    const radius = 1.2 + 2.6 * Math.min(1, f.deploy + (f.pearl > 0 ? 0.2 : 0));
    for (let k = 0; k < count; k++) {
      const a = this.orbit + (k / Math.max(count, 1)) * Math.PI * 2;
      const tilt = 0.5 + k * 0.35;
      this.pearlPositions[k * 3] = Math.cos(a) * radius;
      this.pearlPositions[k * 3 + 1] = Math.sin(a) * radius * Math.sin(tilt);
      this.pearlPositions[k * 3 + 2] = Math.sin(a) * radius * Math.cos(tilt);
    }
    (this.pearlGeometry.getAttribute('position') as BufferAttribute).needsUpdate = true;
    this.pearlGeometry.setDrawRange(0, count);
    put(this.pearlMaterial.uniforms['uColor'].value as number[], this.pearlColors[variant]);
    this.pearlMaterial.uniforms['uOpacity'].value = f.pearl;
  }

  private buildField(count: number, random: () => number): {
    geometry: BufferGeometry; deployed: Float32Array; rest: Float32Array; delay: Float32Array; phase: Float32Array;
    count: number;
  } {
    const deployed = new Float32Array(count * 3);
    const rest = new Float32Array(count * 3);
    const delay = new Float32Array(count);
    const phase = new Float32Array(count);
    const size = new Float32Array(count);
    const heat = new Float32Array(count);
    for (let i = 0; i < count; i++) {
      const u = random() * 2 - 1;
      const theta = random() * Math.PI * 2;
      const s = Math.sqrt(1 - u * u);
      const dx = s * Math.cos(theta);
      const dy = u;
      const dz = s * Math.sin(theta);
      // Champ : surtout en couronne (0,45-1), quelques particules au cœur.
      const depth = 0.45 + 0.55 * Math.pow(random(), 0.6);
      deployed[i * 3] = dx * DEEP_RADIUS * depth;
      deployed[i * 3 + 1] = dy * DEEP_RADIUS * depth;
      deployed[i * 3 + 2] = dz * DEEP_RADIUS * depth;
      const restDepth = 0.25 + 0.75 * Math.cbrt(random());
      rest[i * 3] = dx * HEART_RADIUS * restDepth;
      rest[i * 3 + 1] = dy * HEART_RADIUS * restDepth;
      rest[i * 3 + 2] = dz * HEART_RADIUS * restDepth;
      delay[i] = random();
      phase[i] = random() * 10;
      size[i] = 1 + random() * 2;
      heat[i] = Math.max(0, 1 - depth * 2.2);
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(deployed, 3));
    geometry.setAttribute('aRest', new BufferAttribute(rest, 3));
    geometry.setAttribute('aDelay', new BufferAttribute(delay, 1));
    geometry.setAttribute('aPhase', new BufferAttribute(phase, 1));
    geometry.setAttribute('aSize', new BufferAttribute(size, 1));
    geometry.setAttribute('aHeat', new BufferAttribute(heat, 1));
    return { geometry, deployed, rest, delay, phase, count };
  }

  private fieldMaterial(pixelRatio: number): ShaderMaterial {
    return new ShaderMaterial({
      vertexShader: FIELD_VERTEX, fragmentShader: FIELD_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uTime: { value: 0 }, uDeploy: { value: 0 }, uBreathPhase: { value: 0 }, uSpin: { value: 0 },
        uGather: { value: 0 }, uPixelRatio: { value: pixelRatio }, uHue: { value: 0 }, uGain: { value: 1 },
        uNormal: { value: rgb(HOLO_COLORS.normal) }, uDeep: { value: rgb(HOLO_COLORS.deep) },
        uDeepCore: { value: rgb(HOLO_COLORS.deepCore) },
      },
    });
  }

  /** Connexions aux plus proches voisins (grille spatiale, jamais O(n²)), bornées au budget du niveau. */
  private buildLinks(field: { deployed: Float32Array; rest: Float32Array; delay: Float32Array; phase: Float32Array;
      count: number }, budget: number, random: () => number): ShaderMaterial {
    const pairs = nearestPairs(field.deployed, field.count, budget, 2);
    const n = pairs.length / 2;
    const pos = new Float32Array(n * 6);
    const rest = new Float32Array(n * 6);
    const delay = new Float32Array(n * 2);
    const order = new Float32Array(n * 2);
    const along = new Float32Array(n * 2);
    const phase = new Float32Array(n * 2);
    for (let e = 0; e < n; e++) {
      const ends = [pairs[e * 2], pairs[e * 2 + 1]];
      let reach = 0;
      for (const i of ends) {
        const r = Math.hypot(field.deployed[i * 3], field.deployed[i * 3 + 1], field.deployed[i * 3 + 2]);
        reach = Math.max(reach, r / DEEP_RADIUS);
      }
      const p = random();
      for (let k = 0; k < 2; k++) {
        const i = ends[k];
        const v = e * 2 + k;
        for (let c = 0; c < 3; c++) {
          pos[v * 3 + c] = field.deployed[i * 3 + c];
          rest[v * 3 + c] = field.rest[i * 3 + c];
        }
        delay[v] = field.delay[i];
        order[v] = reach;
        along[v] = k;
        phase[v] = p;
      }
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(pos, 3));
    geometry.setAttribute('aRest', new BufferAttribute(rest, 3));
    geometry.setAttribute('aDelay', new BufferAttribute(delay, 1));
    geometry.setAttribute('aOrder', new BufferAttribute(order, 1));
    geometry.setAttribute('aT', new BufferAttribute(along, 1));
    geometry.setAttribute('aPhase', new BufferAttribute(phase, 1));
    const material = new ShaderMaterial({
      vertexShader: LINK_VERTEX, fragmentShader: LINK_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uTime: { value: 0 }, uDeploy: { value: 0 }, uBreathPhase: { value: 0 }, uSpin: { value: 0 },
        uGather: { value: 0 }, uReveal: { value: 0 }, uHue: { value: 0 },
        uNormal: { value: rgb(HOLO_COLORS.normal) }, uDeep: { value: rgb(HOLO_COLORS.deep) },
        uDeepCore: { value: rgb(HOLO_COLORS.deepCore) },
      },
    });
    const lines = new LineSegments(geometry, material);
    lines.frustumCulled = false;
    this.group.add(lines);
    return material;
  }

  private shell(radius: number, power: number, color: string): { material: ShaderMaterial; mesh: Mesh } {
    const material = new ShaderMaterial({
      vertexShader: SHELL_VERTEX, fragmentShader: SHELL_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending,
      uniforms: { uColor: { value: rgb(color) }, uOpacity: { value: 0 }, uPower: { value: power } },
    });
    const mesh = new Mesh(new SphereGeometry(1, 48, 24), material);
    mesh.scale.setScalar(radius);
    this.group.add(mesh);
    return { material, mesh };
  }

  private buildEnvelope(): ShaderMaterial {
    return this.shell(DEEP_RADIUS * 1.05, 2.4, HOLO_COLORS.deep).material;
  }

  private buildShock(): { material: ShaderMaterial; mesh: Mesh } {
    const shock = this.shell(1, 1.6, HOLO_COLORS.deep);
    shock.mesh.visible = false;
    return shock;
  }

  private buildGyros(): void {
    for (let i = 0; i < GYRO_RADII.length; i++) {
      const segments = 160;
      const pos = new Float32Array(segments * 3);
      for (let s = 0; s < segments; s++) {
        const a = (s / segments) * Math.PI * 2;
        pos[s * 3] = Math.cos(a);
        pos[s * 3 + 1] = Math.sin(a);
      }
      const geometry = new BufferGeometry();
      geometry.setAttribute('position', new BufferAttribute(pos, 3));
      const material = new LineBasicMaterial({
        color: HOLO_COLORS.deep, transparent: true, opacity: 0, blending: AdditiveBlending, depthWrite: false,
      });
      const ring = new LineLoop(geometry, material);
      this.gyros.push(ring);
      this.gyroMaterials.push(material);
      this.group.add(ring);
    }
  }
}
