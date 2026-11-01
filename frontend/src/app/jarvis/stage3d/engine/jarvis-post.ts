import { Camera, Scene, Vector2, WebGLRenderer } from 'three';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';

import { QualityLevel } from '../jarvis-render-capability';

/**
 * F-236 / SF-236-02 — **la lumière** (cadrage T4) : bloom à seuil. Il ne touche que ce qui est émissif — particules,
 * filaments, halos, anneaux ; le fond (sous le seuil) et le texte ne sont jamais floutés : <b>aucun texte n'est
 * dessiné dans le canevas</b> (étiquettes et panneaux sont du DOM, T5/T6). Pleine résolution en Haut,
 * demi-résolution en Moyen, coupé en Bas (la lueur reste portée par les halos additifs).
 */
export const BLOOM_THRESHOLD = 0.32;
export const BLOOM_RADIUS = 0.55;
const BLOOM_SCALE: Record<QualityLevel, number> = { high: 1, medium: 0.5, low: 0 };

export class JarvisPost {
  private readonly composer: EffectComposer;
  private readonly bloom: UnrealBloomPass;
  private level: QualityLevel;
  private width: number;
  private height: number;

  constructor(private readonly renderer: WebGLRenderer, private readonly scene: Scene, private readonly camera: Camera,
      width: number, height: number, level: QualityLevel) {
    this.width = width;
    this.height = height;
    this.level = level;
    this.composer = new EffectComposer(renderer);
    this.composer.addPass(new RenderPass(scene, camera));
    this.bloom = new UnrealBloomPass(new Vector2(width, height), 1.1, BLOOM_RADIUS, BLOOM_THRESHOLD);
    this.composer.addPass(this.bloom);
    this.setSize(width, height);
  }

  setLevel(level: QualityLevel): void {
    this.level = level;
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

  /** Dessine l'image avec un bloom d'intensité `strength` (montée à 1,6 aux moments forts). */
  render(strength: number): void {
    if (BLOOM_SCALE[this.level] === 0 || strength <= 0.01) {
      this.renderer.render(this.scene, this.camera);
      return;
    }
    this.bloom.strength = strength;
    this.composer.render();
  }

  dispose(): void {
    this.bloom.dispose();
    this.composer.dispose();
  }
}
