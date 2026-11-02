import {
  AdditiveBlending, BufferAttribute, BufferGeometry, Group, LineBasicMaterial, LineLoop, LineSegments, Mesh, Points,
  Scene, ShaderMaterial, SphereGeometry,
} from 'three';

import { HEART_LOOKS, HEART_TRANSITIONS, HeartMood, waveAmplitude } from '../jarvis-heart';
import { HOLO_COLORS, MOTION_DURATIONS, ease, rgb } from '../jarvis-motion';
import { QualityLevel } from '../jarvis-render-capability';
import { ColorTween, Tween } from '../jarvis-tween';

/**
 * F-236 / SF-236-02 — **le cœur 3D** (le cerveau rapide, cadrage §9.1 / §10.1) : sphère de particules de rayon 1,4,
 * trois anneaux, ondes de voix (entrantes à l'écoute, sortantes à la parole), 64 rayons de parole, halo, onde de
 * choc du réveil. Tout est piloté par uniformes interpolés : zéro allocation par image.
 */

/** Rayon du cœur rapide (§10.1). */
export const HEART_RADIUS = 1.4;

/** Particules du cœur par niveau (§10.1 : PC 2 000, téléphone 900). */
export const HEART_PARTICLES: Record<QualityLevel, number> = { high: 2000, medium: 900, low: 500 };

const HEART_VERTEX = /* glsl */ `
attribute float aPhase;
attribute float aSize;
attribute float aHeat;
attribute float aRank;
uniform float uTime;
uniform float uPixelRatio;
uniform float uBreath;
uniform float uBreathPhase;
uniform float uSwirl;
uniform float uSpin;
uniform float uWave;
uniform float uWaveDir;
uniform float uWavePhase;
uniform float uDensity;
uniform float uScale;
uniform vec3 uColor;
uniform vec3 uCore;
varying vec3 vColor;
varying float vAlpha;
void main() {
  vec3 p = position;
  float r = length(p);
  float a = uSpin + uSwirl * (1.6 - r) * 1.8 + uSwirl * 0.35 * sin(uTime * 0.001 + aPhase);
  float c = cos(a);
  float s = sin(a);
  p = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
  p *= 1.0 + uBreath * sin(uBreathPhase + aPhase * 0.15);
  float ripple = sin(r * 7.0 - uWaveDir * uWavePhase);
  p *= 1.0 + uWave * 0.10 * ripple;
  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = aSize * uPixelRatio * (uScale / max(-mv.z, 0.5)) * (1.0 + uWave * 0.5 * max(ripple, 0.0));
  vColor = mix(uColor, uCore, aHeat);
  vAlpha = (1.0 - smoothstep(uDensity - 0.06, uDensity, aRank)) * (0.72 + 0.28 * sin(uTime * 0.0016 + aPhase * 6.2831));
}`;

const GLOW_FRAGMENT = /* glsl */ `
uniform float uGlow;
varying vec3 vColor;
varying float vAlpha;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.0, 0.5, d);
  a *= a;
  gl_FragColor = vec4(vColor * a * vAlpha * uGlow, 1.0);
}`;

const RAYS_VERTEX = /* glsl */ `
attribute float aEnd;
attribute float aIndex;
uniform float uTime;
uniform float uRays;
uniform float uLevel;
varying float vEnd;
void main() {
  float n = 0.5 + 0.5 * sin(aIndex * 12.9898 + uTime * 0.012);
  float len = aEnd * (0.12 + 1.3 * uLevel * n) * uRays;
  vec3 dir = normalize(position);
  vec3 p = dir * (1.75 + len);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
  vEnd = aEnd;
}`;

const RAYS_FRAGMENT = /* glsl */ `
uniform vec3 uColor;
uniform float uRays;
uniform float uGlow;
varying float vEnd;
void main() {
  gl_FragColor = vec4(uColor * (1.0 - vEnd * 0.8) * uRays * uGlow, 1.0);
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
varying float vRim;
void main() {
  gl_FragColor = vec4(uColor * pow(vRim, 1.6) * uOpacity, 1.0);
}`;

const RING_RADII = [1.85, 2.1, 2.35];
const RING_TILTS: readonly [number, number][] = [[1.15, 0.2], [-0.9, 0.7], [0.4, -1.1]];
const SHELLS = 3;
const RAYS = 64;

/** Copie trois composantes sans allouer. */
export function put(dst: number[], src: readonly number[]): void {
  dst[0] = src[0];
  dst[1] = src[1];
  dst[2] = src[2];
}

export class Heart3d {
  readonly group = new Group();
  private readonly particles: ShaderMaterial;
  private readonly halo: ShaderMaterial;
  private readonly rays: ShaderMaterial;
  private readonly shock: ShaderMaterial;
  private readonly shockMesh: Mesh;
  private readonly rings: LineLoop[] = [];
  private readonly ringMaterials: LineBasicMaterial[] = [];
  private readonly shells: LineLoop[] = [];
  private readonly shellMaterials: LineBasicMaterial[] = [];

  private readonly color: ColorTween;
  private readonly spin = new Tween(0);
  private readonly breath = new Tween(0);
  private readonly period = new Tween(4000);
  private readonly swirl = new Tween(0);
  private readonly glow = new Tween(0);
  private readonly rayGain = new Tween(0);
  private readonly facing = new Tween(0);
  private readonly density = new Tween(1);
  readonly bloom = new Tween(1.1);
  /** La lumière du cœur (0-1), lue par le décor (la lueur du fond suit le cœur). */
  light = 0.8;
  /** SF-236-03 — multiplicateur de lumière posé par les deux cerveaux (inspiration −20 %, dépôt). */
  lightGain = 1;
  /** SF-236-03 — flash au centre (étincelle, dépôt de la réponse), 0-1. */
  flash = 0;

  private readonly rgbNow: number[] = [0, 0, 0];
  private mood: HeartMood = 'repos';
  private heartTime = 0;
  private spinAngle = 0;
  private breathPhase = 0;
  private wavePhase = 0;
  private waveLevel = 0;
  private shockStart: number | null = null;
  private wakesSeen = 0;

  constructor(scene: Scene, random: () => number, private readonly ceiling: QualityLevel, pixelRatio: number) {
    const count = HEART_PARTICLES[ceiling];
    const look = HEART_LOOKS.repos;
    this.color = new ColorTween(rgb(HOLO_COLORS[look.color]));
    this.particles = this.buildParticles(count, random, pixelRatio);
    this.halo = this.buildHalo(pixelRatio);
    this.rays = this.buildRays();
    this.buildRings();
    this.buildShells();
    const shock = this.buildShock();
    this.shock = shock.material;
    this.shockMesh = shock.mesh;
    scene.add(this.group);
  }

  /** Pose une humeur : à `now` les cibles changent ; `instant` la pose sans transition (ouverture). */
  setMood(mood: HeartMood, now: number, instant: boolean): void {
    if (mood === this.mood && !instant) {
      return;
    }
    this.mood = mood;
    const look = HEART_LOOKS[mood];
    const { ms, curve } = HEART_TRANSITIONS[mood];
    const duration = instant ? 0 : ms;
    this.color.set(rgb(HOLO_COLORS[look.color]), now, duration, curve);
    this.spin.set(look.spin, now, duration, curve);
    this.breath.set(look.breath, now, duration, curve);
    this.period.set(look.breathPeriod, now, duration, curve);
    this.swirl.set(look.swirl, now, duration, curve);
    this.glow.set(look.glow, now, duration, curve);
    this.bloom.set(look.bloom, now, duration, curve);
    this.rayGain.set(look.rays, now, duration, curve);
    this.facing.set(look.facing, now, duration, curve);
  }

  /** N2 — un réveil de plus ⇒ onde de choc à partir de `startAt`. */
  setWakes(wakes: number, startAt: number): void {
    if (wakes > this.wakesSeen) {
      this.shockStart = startAt;
    }
    this.wakesSeen = wakes;
  }

  /** T-Q — la densité de particules suit le niveau, en fondu (jamais d'un coup). */
  setLevel(level: QualityLevel, now: number): void {
    const target = HEART_PARTICLES[level] / HEART_PARTICLES[this.ceiling];
    this.density.set(Math.min(1, target), now, MOTION_DURATIONS.quality, 'glisse');
  }

  /** Une image : `dt` en ms (borné), `level` = niveau de la voix (0-1). */
  frame(now: number, dt: number, voiceLevel: number): void {
    const look = HEART_LOOKS[this.mood];
    if (!look.frozen) {
      this.heartTime += dt;
      this.spinAngle += (this.spin.value(now) * dt) / 1000;
      this.breathPhase += (dt * Math.PI * 2) / Math.max(500, this.period.value(now));
      this.wavePhase += dt * 0.006;
    }
    // Lissage du niveau de voix indépendant de la cadence (constante de temps 80 ms).
    const target = waveAmplitude(this.mood, voiceLevel);
    this.waveLevel += (target - this.waveLevel) * (1 - Math.exp(-dt / 80));
    this.color.write(now, this.rgbNow);
    const glow = this.glow.value(now) * this.lightGain;
    this.light = glow;

    const u = this.particles.uniforms;
    u['uTime'].value = this.heartTime;
    u['uSpin'].value = this.spinAngle;
    u['uBreath'].value = this.breath.value(now);
    u['uBreathPhase'].value = this.breathPhase;
    u['uSwirl'].value = this.swirl.value(now);
    u['uWave'].value = this.waveLevel;
    u['uWaveDir'].value = look.waveDir === 0 ? 1 : look.waveDir;
    u['uWavePhase'].value = this.wavePhase;
    u['uDensity'].value = this.density.value(now) + 0.06;
    u['uGlow'].value = glow;
    put(u['uColor'].value as number[], this.rgbNow);

    const h = this.halo.uniforms;
    // SF-236-07 — halo plus discret (le centre saturait en blanc sous le bloom, surtout au téléphone).
    h['uGlow'].value = glow * 0.6 * (1 + 0.08 * Math.sin(this.breathPhase)) + this.flash * 1.6;
    put(h['uColor'].value as number[], this.rgbNow);

    const r = this.rays.uniforms;
    r['uTime'].value = this.heartTime;
    r['uRays'].value = this.rayGain.value(now);
    r['uLevel'].value = this.waveLevel;
    r['uGlow'].value = glow;
    put(r['uColor'].value as number[], this.rgbNow);

    const facing = this.facing.value(now);
    for (let i = 0; i < this.rings.length; i++) {
      const [tx, ty] = RING_TILTS[i];
      this.rings[i].rotation.set(tx * (1 - facing), ty * (1 - facing), this.spinAngle * (i % 2 === 0 ? 1 : -0.7));
      this.ringMaterials[i].color.setRGB(this.rgbNow[0], this.rgbNow[1], this.rgbNow[2]);
      this.ringMaterials[i].opacity = 0.35 * glow;
    }

    // Ondes de voix : cercles face à la caméra qui convergent (écoute) ou s'éloignent (parole, « oui »).
    const dir = look.waveDir === 0 ? (this.mood === 'oui' ? 1 : 0) : look.waveDir;
    for (let k = 0; k < SHELLS; k++) {
      const phase = (this.wavePhase * 0.08 + k / SHELLS) % 1;
      const radius = dir < 0 ? 3.8 - 2.2 * phase : 1.6 + 2.2 * phase;
      this.shells[k].scale.setScalar(radius);
      this.shellMaterials[k].color.setRGB(this.rgbNow[0], this.rgbNow[1], this.rgbNow[2]);
      this.shellMaterials[k].opacity = dir === 0 ? 0 : this.waveLevel * Math.sin(phase * Math.PI) * 0.7;
    }

    // T-V2 — onde de choc du réveil (600 ms, `sortie`).
    if (this.shockStart !== null) {
      const p = (now - this.shockStart) / MOTION_DURATIONS.wake;
      if (p >= 1 || p < 0) {
        this.shockMesh.visible = p < 0;
        if (p >= 1) {
          this.shockStart = null;
        }
      } else {
        const e = ease('sortie', p);
        this.shockMesh.visible = true;
        this.shockMesh.scale.setScalar(HEART_RADIUS + e * 7);
        this.shock.uniforms['uOpacity'].value = (1 - e) * 2.4;
        put(this.shock.uniforms['uColor'].value as number[], this.rgbNow);
      }
    }
  }

  /** Le décor demande l'avancement de l'onde de choc (les cercles du sol s'illuminent à son passage). */
  shockRadius(now: number): number {
    if (this.shockStart === null) {
      return -1;
    }
    const p = (now - this.shockStart) / MOTION_DURATIONS.wake;
    return p < 0 || p > 1 ? -1 : HEART_RADIUS + ease('sortie', p) * 7;
  }

  private buildParticles(count: number, random: () => number, pixelRatio: number): ShaderMaterial {
    const pos = new Float32Array(count * 3);
    const phase = new Float32Array(count);
    const size = new Float32Array(count);
    const heat = new Float32Array(count);
    const rank = new Float32Array(count);
    const golden = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < count; i++) {
      const y = 1 - (i / Math.max(count - 1, 1)) * 2;
      const ring = Math.sqrt(1 - y * y);
      const a = golden * i;
      const depth = 0.3 + 0.7 * Math.cbrt(random());
      const r = HEART_RADIUS * depth;
      pos[i * 3] = Math.cos(a) * ring * r;
      pos[i * 3 + 1] = y * r;
      pos[i * 3 + 2] = Math.sin(a) * ring * r;
      phase[i] = random() * 10;
      size[i] = 1.5 + random() * 2.4;
      heat[i] = Math.pow(1 - depth, 1.8) * 1.0;
      rank[i] = random();
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(pos, 3));
    geometry.setAttribute('aPhase', new BufferAttribute(phase, 1));
    geometry.setAttribute('aSize', new BufferAttribute(size, 1));
    geometry.setAttribute('aHeat', new BufferAttribute(heat, 1));
    geometry.setAttribute('aRank', new BufferAttribute(rank, 1));
    const material = new ShaderMaterial({
      vertexShader: HEART_VERTEX,
      fragmentShader: GLOW_FRAGMENT,
      transparent: true,
      depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uTime: { value: 0 }, uPixelRatio: { value: pixelRatio }, uBreath: { value: 0 }, uBreathPhase: { value: 0 },
        uSwirl: { value: 0 }, uSpin: { value: 0 }, uWave: { value: 0 }, uWaveDir: { value: 1 },
        uWavePhase: { value: 0 }, uDensity: { value: 1.06 }, uScale: { value: 22 }, uGlow: { value: 0.8 },
        uColor: { value: rgb(HOLO_COLORS.normal) }, uCore: { value: rgb(HOLO_COLORS.core) },
      },
    });
    this.group.add(new Points(geometry, material));
    return material;
  }

  /** Halo : deux lueurs additives larges au centre (la lumière qui « éclaire la pièce »). */
  private buildHalo(pixelRatio: number): ShaderMaterial {
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(6), 3));
    geometry.setAttribute('aPhase', new BufferAttribute(new Float32Array([0, 0]), 1));
    geometry.setAttribute('aSize', new BufferAttribute(new Float32Array([110, 36]), 1));
    geometry.setAttribute('aHeat', new BufferAttribute(new Float32Array([0, 0.8]), 1));
    geometry.setAttribute('aRank', new BufferAttribute(new Float32Array([0, 0]), 1));
    const material = new ShaderMaterial({
      vertexShader: HEART_VERTEX,
      fragmentShader: GLOW_FRAGMENT,
      transparent: true,
      depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uTime: { value: 0 }, uPixelRatio: { value: pixelRatio }, uBreath: { value: 0 }, uBreathPhase: { value: 0 },
        uSwirl: { value: 0 }, uSpin: { value: 0 }, uWave: { value: 0 }, uWaveDir: { value: 1 },
        uWavePhase: { value: 0 }, uDensity: { value: 1.06 }, uScale: { value: 20 }, uGlow: { value: 0.5 },
        uColor: { value: rgb(HOLO_COLORS.normal) }, uCore: { value: rgb(HOLO_COLORS.core) },
      },
    });
    const halo = new Points(geometry, material);
    halo.renderOrder = -1;
    this.group.add(halo);
    return material;
  }

  private buildRays(): ShaderMaterial {
    const pos = new Float32Array(RAYS * 2 * 3);
    const end = new Float32Array(RAYS * 2);
    const index = new Float32Array(RAYS * 2);
    for (let i = 0; i < RAYS; i++) {
      const a = (i / RAYS) * Math.PI * 2;
      for (let k = 0; k < 2; k++) {
        const v = i * 2 + k;
        pos[v * 3] = Math.cos(a);
        pos[v * 3 + 1] = Math.sin(a);
        pos[v * 3 + 2] = 0;
        end[v] = k;
        index[v] = i;
      }
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(pos, 3));
    geometry.setAttribute('aEnd', new BufferAttribute(end, 1));
    geometry.setAttribute('aIndex', new BufferAttribute(index, 1));
    const material = new ShaderMaterial({
      vertexShader: RAYS_VERTEX,
      fragmentShader: RAYS_FRAGMENT,
      transparent: true,
      depthWrite: false,
      blending: AdditiveBlending,
      uniforms: {
        uTime: { value: 0 }, uRays: { value: 0 }, uLevel: { value: 0 }, uGlow: { value: 1 },
        uColor: { value: rgb(HOLO_COLORS.normal) },
      },
    });
    const rays = new LineSegments(geometry, material);
    rays.frustumCulled = false;
    this.group.add(rays);
    return material;
  }

  private circle(segments: number): BufferGeometry {
    const pos = new Float32Array(segments * 3);
    for (let s = 0; s < segments; s++) {
      const a = (s / segments) * Math.PI * 2;
      pos[s * 3] = Math.cos(a);
      pos[s * 3 + 1] = Math.sin(a);
    }
    const geometry = new BufferGeometry();
    geometry.setAttribute('position', new BufferAttribute(pos, 3));
    return geometry;
  }

  private buildRings(): void {
    for (const radius of RING_RADII) {
      const material = new LineBasicMaterial({
        color: HOLO_COLORS.normal, transparent: true, opacity: 0.3, blending: AdditiveBlending, depthWrite: false,
      });
      const ring = new LineLoop(this.circle(128), material);
      ring.scale.setScalar(radius);
      this.rings.push(ring);
      this.ringMaterials.push(material);
      this.group.add(ring);
    }
  }

  private buildShells(): void {
    for (let k = 0; k < SHELLS; k++) {
      const material = new LineBasicMaterial({
        color: HOLO_COLORS.normal, transparent: true, opacity: 0, blending: AdditiveBlending, depthWrite: false,
      });
      const shell = new LineLoop(this.circle(96), material);
      this.shells.push(shell);
      this.shellMaterials.push(material);
      this.group.add(shell);
    }
  }

  private buildShock(): { material: ShaderMaterial; mesh: Mesh } {
    const material = new ShaderMaterial({
      vertexShader: SHELL_VERTEX,
      fragmentShader: SHELL_FRAGMENT,
      transparent: true,
      depthWrite: false,
      blending: AdditiveBlending,
      uniforms: { uColor: { value: rgb(HOLO_COLORS.normal) }, uOpacity: { value: 0 } },
    });
    const mesh = new Mesh(new SphereGeometry(1, 32, 16), material);
    mesh.visible = false;
    this.group.add(mesh);
    return { material, mesh };
  }
}
