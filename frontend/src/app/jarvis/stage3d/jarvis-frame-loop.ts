/**
 * F-236 / SF-236-01 — **la boucle d'images** (cadrage §6.2). Fichier <b>pur</b> : `requestAnimationFrame` et la
 * visibilité du document sont injectés.
 *
 * <p>Onglet caché ou écran verrouillé ⇒ la boucle <b>s'arrête</b> (zéro image) ; elle reprend au retour. `stop()` la
 * coupe pour de bon (fermeture de l'écran).</p>
 */

export interface FrameHost {
  request(cb: (now: number) => void): number;
  cancel(id: number): void;
  hidden(): boolean;
  onVisibility(cb: () => void): () => void;
}

export function browserFrameHost(): FrameHost {
  return {
    request: (cb) => requestAnimationFrame(cb),
    cancel: (id) => cancelAnimationFrame(id),
    hidden: () => document.visibilityState === 'hidden',
    onVisibility: (cb) => {
      document.addEventListener('visibilitychange', cb);
      return () => document.removeEventListener('visibilitychange', cb);
    },
  };
}

export class FrameLoop {
  private handle: number | null = null;
  private running = false;
  private stopped = false;
  private readonly unlisten: () => void;
  /** Images dessinées depuis l'ouverture (la mesure du rendu, SF-236-06, s'y branchera). */
  frames = 0;

  constructor(private readonly host: FrameHost, private readonly draw: (now: number) => void) {
    this.unlisten = host.onVisibility(() => this.sync());
  }

  start(): void {
    this.running = true;
    this.sync();
  }

  /** Suspension (contexte perdu) : `start()` relance. */
  pause(): void {
    this.running = false;
    this.cancelPending();
  }

  /** Arrêt définitif : plus aucune image, plus aucun écouteur. */
  stop(): void {
    this.stopped = true;
    this.running = false;
    this.cancelPending();
    this.unlisten();
  }

  get active(): boolean {
    return this.handle !== null;
  }

  private sync(): void {
    if (this.stopped || !this.running || this.host.hidden()) {
      this.cancelPending();
      return;
    }
    if (this.handle === null) {
      this.handle = this.host.request((now) => this.tick(now));
    }
  }

  private tick(now: number): void {
    this.handle = null;
    if (this.stopped || !this.running || this.host.hidden()) {
      return;
    }
    this.frames++;
    this.draw(now);
    this.sync();
  }

  private cancelPending(): void {
    if (this.handle !== null) {
      this.host.cancel(this.handle);
      this.handle = null;
    }
  }
}
