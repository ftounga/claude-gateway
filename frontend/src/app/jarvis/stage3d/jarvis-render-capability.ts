import { InjectionToken } from '@angular/core';

/**
 * F-236 / SF-236-01 — **3D ou 2D** (cadrage T10, T11). Fichier <b>pur</b> (aucun import de `three`).
 *
 * <p>Le 2D F-234 reste le repli : à la moindre cause, l'écran est <b>identique à aujourd'hui</b> et le morceau 3D
 * n'est <b>jamais demandé</b>.</p>
 */

/** Pourquoi l'écran reste en 2D (les compteurs de la mesure, SF-236-06, reprendront ces mots). */
export type Fallback2dReason = 'no_webgl' | 'perf_caveat' | 'reduced_motion' | 'save_data' | 'sober' | 'manual'
  | 'context_lost';

/** Le niveau de qualité de départ (la qualité adaptative, SF-236-02, le fera ensuite varier). */
export type QualityLevel = 'high' | 'medium' | 'low';

/** Ce que l'appareil dit de lui-même, relevé une fois à l'ouverture. */
export interface RenderProbe {
  /** Un contexte WebGL2 s'obtient. */
  webgl2: boolean;
  /** Le contexte n'est obtenu qu'avec `failIfMajorPerformanceCaveat: false` (rendu logiciel). */
  majorPerformanceCaveat: boolean;
  reducedMotion: boolean;
  saveData: boolean;
  pointerFine: boolean;
  width: number;
  deviceMemory: number | null;
  hardwareConcurrency: number | null;
}

/** L'état de l'écran qui pèse sur la décision. */
export interface RenderContext {
  sober: boolean;
  /** Choix manuel « Vue 2D », par appareil. */
  manual2d: boolean;
  /** Le contexte WebGL a été perdu deux fois dans la session. */
  contextLostTwice: boolean;
  /** Banc de captures (hors production seulement) : le rendu logiciel est admis. */
  banc: boolean;
}

export type RenderDecision =
  | { mode: '3d'; level: QualityLevel }
  | { mode: '2d'; reason: Fallback2dReason };

/** Table de vérité de capacité (T10) : la première cause rencontrée l'emporte, dans cet ordre. */
export function decideRender(probe: RenderProbe, ctx: RenderContext): RenderDecision {
  if (!probe.webgl2) {
    return { mode: '2d', reason: 'no_webgl' };
  }
  if (probe.majorPerformanceCaveat && !ctx.banc) {
    return { mode: '2d', reason: 'perf_caveat' };
  }
  if (probe.reducedMotion) {
    return { mode: '2d', reason: 'reduced_motion' };
  }
  if (probe.saveData) {
    return { mode: '2d', reason: 'save_data' };
  }
  if (ctx.sober) {
    return { mode: '2d', reason: 'sober' };
  }
  if (ctx.manual2d) {
    return { mode: '2d', reason: 'manual' };
  }
  if (ctx.contextLostTwice) {
    return { mode: '2d', reason: 'context_lost' };
  }
  return { mode: '3d', level: startLevel(probe) };
}

/** Niveau de départ : Bas si mémoire ≤ 3 Go ou ≤ 4 cœurs ; Haut si pointeur fin et ≥ 1 024 px ; Moyen sinon. */
export function startLevel(probe: RenderProbe): QualityLevel {
  if ((probe.deviceMemory !== null && probe.deviceMemory <= 3)
      || (probe.hardwareConcurrency !== null && probe.hardwareConcurrency <= 4)) {
    return 'low';
  }
  if (probe.pointerFine && probe.width >= 1024) {
    return 'high';
  }
  return 'medium';
}

/** Le 2D est-il un choix réversible depuis l'écran (le bouton « Vue 3D / 2D » n'apparaît qu'alors) ? */
export function canOffer3d(probe: RenderProbe, banc: boolean): boolean {
  return probe.webgl2 && (!probe.majorPerformanceCaveat || banc) && !probe.reducedMotion && !probe.saveData;
}

/**
 * T11 — perte du contexte WebGL : bascule <b>immédiate</b> en 2D ; une restauration tentée ; une seconde perte
 * garde le 2D pour la session (le compteur vit le temps de l'application, pas au-delà).
 */
export class ContextLossPolicy {
  private losses = 0;

  /** Le contexte vient d'être perdu : faut-il tenter une restauration ? */
  lost(): 'retry' | 'stay2d' {
    this.losses++;
    return this.losses >= 2 ? 'stay2d' : 'retry';
  }

  get lostTwice(): boolean {
    return this.losses >= 2;
  }
}

/** Le compteur de pertes partagé par toutes les ouvertures de l'écran pendant la session. */
export const SESSION_CONTEXT_LOSS = new ContextLossPolicy();

/** La préférence « Vue 2D » de cet appareil. */
export const JARVIS_VIEW_KEY = 'cg.jarvis.view';

export function readManual2d(): boolean {
  try {
    return localStorage.getItem(JARVIS_VIEW_KEY) === '2d';
  } catch {
    return false;
  }
}

export function writeManual2d(on: boolean): void {
  try {
    if (on) {
      localStorage.setItem(JARVIS_VIEW_KEY, '2d');
    } else {
      localStorage.removeItem(JARVIS_VIEW_KEY);
    }
  } catch {
    // Stockage indisponible (navigation privée) : le choix vaut pour cette ouverture seulement.
  }
}

/** Relève ce que l'appareil dit de lui-même ; injectable pour les tests (Karma n'a pas de GPU fiable). */
export const JARVIS_RENDER_PROBE = new InjectionToken<() => RenderProbe>('JARVIS_RENDER_PROBE', {
  providedIn: 'root',
  factory: () => browserProbe,
});

/** Le relevé réel du navigateur : un contexte d'essai ouvert puis rendu aussitôt. */
export function browserProbe(): RenderProbe {
  const media = (q: string): boolean => {
    try {
      return typeof matchMedia === 'function' && matchMedia(q).matches;
    } catch {
      return false;
    }
  };
  const nav = (typeof navigator === 'undefined' ? {} : navigator) as Navigator & {
    deviceMemory?: number;
    connection?: { saveData?: boolean };
  };
  const strict = tryWebgl2(true);
  const any = strict || tryWebgl2(false);
  return {
    webgl2: any,
    majorPerformanceCaveat: any && !strict,
    reducedMotion: media('(prefers-reduced-motion: reduce)'),
    saveData: nav.connection?.saveData === true,
    pointerFine: media('(pointer: fine)'),
    width: typeof window === 'undefined' ? 0 : window.innerWidth,
    deviceMemory: typeof nav.deviceMemory === 'number' ? nav.deviceMemory : null,
    hardwareConcurrency: typeof nav.hardwareConcurrency === 'number' ? nav.hardwareConcurrency : null,
  };
}

function tryWebgl2(failIfMajorPerformanceCaveat: boolean): boolean {
  try {
    const canvas = document.createElement('canvas');
    const gl = canvas.getContext('webgl2', { failIfMajorPerformanceCaveat });
    if (!gl) {
      return false;
    }
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    return true;
  } catch {
    return false;
  }
}
