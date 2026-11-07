import { Camera, Scene, Vector2, WebGLRenderer } from 'three';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { SMAAPass } from 'three/examples/jsm/postprocessing/SMAAPass.js';
import { ShaderPass } from 'three/examples/jsm/postprocessing/ShaderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';

import { QualityLevel } from '../jarvis-render-capability';
import { FINISH, PostChain, postChain } from '../jarvis-post-chain';

/**
 * F-236 / SF-236-02 — **la lumière** (cadrage T4) : bloom à seuil. Il ne touche que ce qui est émissif — particules,
 * filaments, halos, anneaux ; le fond (sous le seuil) et le texte ne sont jamais floutés : <b>aucun texte n'est
 * dessiné dans le canevas</b> (étiquettes et panneaux sont du DOM, T5/T6). Pleine résolution en Haut,
 * demi-résolution en Moyen, coupé en Bas (la lueur reste portée par les halos additifs).
 *
 * <p>SF-236-15 — sur PC : <b>SMAA</b> et <b>passe de finition</b> (vignette, grain, aberration sur les bords), selon
 * {@link postChain}.</p>
 */
export const BLOOM_THRESHOLD = 0.32;
export const BLOOM_RADIUS = 0.55;
const BLOOM_SCALE: Record<QualityLevel, number> = { high: 1, medium: 0.5, low: 0 };

const FINISH_SHADER = {
  uniforms: {
    tDiffuse: { value: null },
    uTime: { value: 0 },
    uVignette: { value: FINISH.vignette },
    uGrain: { value: FINISH.grain },
    uAberration: { value: FINISH.aberration },
  },
  vertexShader: /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}`,
  fragmentShader: /* glsl */ `
uniform sampler2D tDiffuse;
uniform float uTime;
uniform float uVignette;
uniform float uGrain;
uniform float uAberration;
varying vec2 vUv;
void main() {
  vec2 c = vUv - 0.5;
  float r2 = dot(c, c);
  vec2 off = c * uAberration * r2 * 4.0;
  vec3 color = vec3(texture2D(tDiffuse, vUv + off).r, texture2D(tDiffuse, vUv).g, texture2D(tDiffuse, vUv - off).b);
  color *= 1.0 - uVignette * smoothstep(0.08, 0.5, r2);
  float n = fract(sin(dot(gl_FragCoord.xy + floor(uTime / 40.0), vec2(12.9898, 78.233))) * 43758.5453);
  color += (n - 0.5) * uGrain;
  gl_FragColor = vec4(color, 1.0);
}`,
};

export class JarvisPost {
  private readonly composer: EffectComposer;
  private readonly bloom: UnrealBloomPass;
  private readonly smaa: SMAAPass;
  private readonly finish: ShaderPass;
  private chain: PostChain;
  private level: QualityLevel;
  private width: number;
  private height: number;

  constructor(private readonly renderer: WebGLRenderer, private readonly scene: Scene, private readonly camera: Camera,
      width: number, height: number, level: QualityLevel, private readonly narrow = false) {
    this.width = width;
    this.height = height;
    this.level = level;
    this.chain = postChain(level, narrow);
    this.composer = new EffectComposer(renderer);
    this.composer.addPass(new RenderPass(scene, camera));
    this.bloom = new UnrealBloomPass(new Vector2(width, height), 1.1, BLOOM_RADIUS, BLOOM_THRESHOLD);
    this.composer.addPass(this.bloom);
    this.finish = new ShaderPass(FINISH_SHADER);
    this.composer.addPass(this.finish);
    this.smaa = new SMAAPass();
    this.composer.addPass(this.smaa);
    this.applyChain();
    this.setSize(width, height);
  }

  /** Les passes de la chaîne courante (lu par les tests du moteur et le banc). */
  get passes(): PostChain {
    return this.chain;
  }

  setLevel(level: QualityLevel): void {
    this.level = level;
    this.chain = postChain(level, this.narrow);
    this.applyChain();
    this.setSize(this.width, this.height);
  }

  setSize(width: number, height: number): void {
    this.width = width;
    this.height = height;
    this.composer.setPixelRatio(this.renderer.getPixelRatio());
    this.composer.setSize(width, height);
    const scale = BLOOM_SCALE[this.level];
    if (scale > 0) {
      const pr = this.renderer.getPixelRatio();
      this.bloom.setSize(Math.max(1, Math.round(width * pr * scale)), Math.max(1, Math.round(height * pr * scale)));
    }
  }

  /**
   * F-250 — le seuil du bloom (0-1) : le visage, fait de points fins, ne s'illumine qu'avec un seuil bas (il descend
   * avec la présence du visage, remonte avec le cœur).
   */
  setThreshold(threshold: number): void {
    this.bloom.threshold = threshold;
  }

  /** Dessine l'image avec un bloom d'intensité `strength` (montée à 1,6 aux moments forts), à l'instant `t`. */
  render(strength: number, t = 0): void {
    const bloomOn = BLOOM_SCALE[this.level] > 0 && strength > 0.01;
    if (!bloomOn && !this.chain.smaa && !this.chain.finish) {
      this.renderer.render(this.scene, this.camera);
      return;
    }
    this.bloom.enabled = bloomOn;
    this.bloom.strength = strength;
    this.finish.uniforms['uTime'].value = t;
    this.composer.render();
  }

  dispose(): void {
    this.bloom.dispose();
    this.smaa.dispose();
    this.finish.dispose();
    this.composer.dispose();
  }

  private applyChain(): void {
    this.smaa.enabled = this.chain.smaa;
    this.finish.enabled = this.chain.finish;
  }
}
