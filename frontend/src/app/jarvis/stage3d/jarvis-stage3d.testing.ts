import { JarvisEngine, JarvisEngineFactory, JarvisEngineOptions } from './jarvis-engine.contract';
import { FrameHost } from './jarvis-frame-loop';
import { RenderProbe } from './jarvis-render-capability';
import { StagePlan } from './jarvis-stage-director';

/** F-236 / SF-236-01 — doublures de test du socle 3D (aucun import de `three`). */

/** Hôte d'images simulé : on avance image par image, on cache ou montre l'onglet. */
export class FakeFrameHost implements FrameHost {
  private next = 1;
  readonly pending = new Map<number, (now: number) => void>();
  isHidden = false;
  private listeners: (() => void)[] = [];

  request(cb: (now: number) => void): number {
    const id = this.next++;
    this.pending.set(id, cb);
    return id;
  }

  cancel(id: number): void {
    this.pending.delete(id);
  }

  hidden(): boolean {
    return this.isHidden;
  }

  onVisibility(cb: () => void): () => void {
    this.listeners.push(cb);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== cb);
    };
  }

  get listenerCount(): number {
    return this.listeners.length;
  }

  setHidden(hidden: boolean): void {
    this.isHidden = hidden;
    this.listeners.forEach((l) => l());
  }

  /** Exécute les images en attente, `n` fois. */
  step(n = 1): void {
    for (let i = 0; i < n; i++) {
      const callbacks = [...this.pending.values()];
      this.pending.clear();
      callbacks.forEach((cb) => cb(i * 16));
    }
  }
}

/** Un moteur simulé : compte les instances vivantes (fuite = instance jamais libérée). */
export class FakeEngine implements JarvisEngine {
  static live = 0;
  started = 0;
  disposed = false;
  frames = 0;
  resized: [number, number][] = [];
  plans: StagePlan[] = [];

  constructor(readonly options: JarvisEngineOptions) {
    FakeEngine.live++;
  }

  start(): void {
    this.started++;
  }

  resize(width: number, height: number): void {
    this.resized.push([width, height]);
  }

  update(plan: StagePlan): void {
    this.plans.push(plan);
  }

  /** Simule la première image dessinée. */
  firstFrame(): void {
    this.frames++;
    this.options.onFirstFrame();
  }

  dispose(): void {
    if (!this.disposed) {
      this.disposed = true;
      FakeEngine.live--;
    }
  }
}

export function fakeEngineFactory(created: FakeEngine[]): JarvisEngineFactory {
  return (options) => {
    const engine = new FakeEngine(options);
    created.push(engine);
    return engine;
  };
}

export const PC_PROBE: RenderProbe = {
  webgl2: true, majorPerformanceCaveat: false, reducedMotion: false, saveData: false, pointerFine: true, width: 1920,
  deviceMemory: 8, hardwareConcurrency: 8,
};

export const NO_WEBGL_PROBE: RenderProbe = { ...PC_PROBE, webgl2: false };
