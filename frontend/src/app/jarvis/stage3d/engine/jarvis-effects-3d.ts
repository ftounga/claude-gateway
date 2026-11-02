import {
  AdditiveBlending, BufferAttribute, BufferGeometry, Camera, Group, LineBasicMaterial, LineLoop, Scene, Vector3,
} from 'three';

import { HOLO_COLORS, MOTION_DURATIONS, ease, rgb } from '../jarvis-motion';
import { CORE_KEY } from '../jarvis-link-graph';
import { Effect3d, SceneTone } from '../scenes3d/jarvis-scene3d.model';

/**
 * F-236 / SF-236-08 — **les effets des scènes 3D** (cadrage §9.2) : chaque effet déclaratif du plan a sa recette.
 * Objets <b>préalloués</b> (pools fixes), seules leurs propriétés changent à chaque image : zéro allocation.
 *
 * <p>S-GEN : un <b>anneau d'impulsion</b> part de son ancre à chaque appel d'outil (900 ms, `sortie`), face à la
 * caméra.</p>
 */

/** Où est un nœud (coordonnées du monde) ; `false` s'il n'est pas posé. */
export type Locator = (key: string, out: Vector3) => boolean;

/** Anneaux d'impulsion simultanés au plus. */
const RING_POOL = 6;
const RING_SEGMENTS = 64;

interface Ring {
  line: LineLoop;
  material: LineBasicMaterial;
  start: number;
  anchor: string;
}

export class Effects3d {
  readonly group = new Group();
  private readonly rings: Ring[] = [];
  private pulseSeq = -1;
  private readonly at = new Vector3();
  private readonly palette = new Map<SceneTone, [number, number, number]>();

  constructor(scene: Scene) {
    scene.add(this.group);
    const geometry = new BufferGeometry();
    const points = new Float32Array(RING_SEGMENTS * 3);
    for (let i = 0; i < RING_SEGMENTS; i++) {
      const a = (i / RING_SEGMENTS) * Math.PI * 2;
      points[i * 3] = Math.cos(a);
      points[i * 3 + 1] = Math.sin(a);
    }
    geometry.setAttribute('position', new BufferAttribute(points, 3));
    for (let i = 0; i < RING_POOL; i++) {
      const material = new LineBasicMaterial({ transparent: true, opacity: 0, blending: AdditiveBlending,
        depthWrite: false });
      const line = new LineLoop(geometry, material);
      line.visible = false;
      line.frustumCulled = false;
      this.group.add(line);
      this.rings.push({ line, material, start: -Infinity, anchor: CORE_KEY });
    }
    for (const tone of ['normal', 'wait', 'deep', 'green', 'alert', 'off'] as SceneTone[]) {
      this.palette.set(tone, rgb(HOLO_COLORS[tone]));
    }
  }

  /** Les effets du plan ; `opening` (banc) : ce qui se déclenche part de t = 0. */
  set(effects: readonly Effect3d[], now: number, opening: boolean): void {
    for (const effect of effects) {
      if (effect.kind === 'pulse') {
        if (opening || effect.seq > this.pulseSeq) {
          this.fireRing(effect.anchor, effect.tone, opening ? 0 : now);
        }
        this.pulseSeq = effect.seq;
      }
    }
  }

  /** Une image. `camera` : les anneaux lui font face. */
  frame(now: number, camera: Camera, locate: Locator): void {
    for (const ring of this.rings) {
      const t = now - ring.start;
      if (t < 0 || t > MOTION_DURATIONS.pulse) {
        ring.line.visible = false;
        continue;
      }
      const k = t / MOTION_DURATIONS.pulse;
      if (ring.anchor === CORE_KEY || !locate(ring.anchor, this.at)) {
        this.at.set(0, 0, 0);
      }
      ring.line.visible = true;
      ring.line.position.copy(this.at);
      ring.line.quaternion.copy(camera.quaternion);
      ring.line.scale.setScalar(0.6 + 2.6 * ease('sortie', k));
      ring.material.opacity = 0.9 * (1 - ease('glisse', k));
    }
  }

  dispose(): void {
    for (const ring of this.rings) {
      ring.material.dispose();
    }
    this.rings[0]?.line.geometry.dispose();
    this.group.removeFromParent();
  }

  private fireRing(anchor: string, tone: SceneTone, at: number): void {
    // Le plus ancien anneau est réutilisé (pool fixe).
    let ring = this.rings[0];
    for (const r of this.rings) {
      if (r.start < ring.start) {
        ring = r;
      }
    }
    ring.start = at;
    ring.anchor = anchor;
    const c = this.palette.get(tone)!;
    ring.material.color.setRGB(c[0], c[1], c[2]);
  }
}
