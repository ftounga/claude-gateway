/**
 * F-236 / SF-236-01 — **la direction artistique de Jarvis en 3D, en jetons** (cadrage §7, DESIGN_SYSTEM §23).
 *
 * <p>Fichier <b>pur</b> (aucun import de `three`) : il est la <b>seule source</b> des couleurs d'état, des paliers de
 * transparence, des courbes nommées et des durées. Le moteur 3D les lit en JavaScript ; l'écran les expose en
 * propriétés CSS (`--cg-holo-*`, `--jv-ease-*`, `--jv-dur-*`) par {@link holoCssVars}. Aucune autre valeur n'est
 * admise ailleurs.</p>
 *
 * <p><b>Goût du PO (2026-11-01, §17)</b> : V1 = <b>orange de la marque</b> en teinte dominante (le cyan devient la
 * couleur de l'attente), V2 = <b>Space Grotesk</b> (aucune police nouvelle), V3 = décor <b>riche</b>.</p>
 */

/** Les rôles de la grammaire des couleurs d'état : une signification chacun, nulle part ailleurs. */
export type HoloRole = 'normal' | 'normalDim' | 'core' | 'deep' | 'deepCore' | 'green' | 'wait' | 'alert' | 'off'
  | 'abyss' | 'fog' | 'text';

/** DESIGN_SYSTEM §23 — palette holographique bornée au rendu 3D de `/jarvis`. */
export const HOLO_COLORS: Readonly<Record<HoloRole, string>> = {
  /** Normal — cerveau rapide, liens, nœuds (émissif) : l'orange de la marque `--cg-orange`. */
  normal: '#E07B39',
  /** Normal atténué (orbites, trame, liserés) : même teinte, l'opacité vient de {@link HOLO_ALPHA}. */
  normalDim: '#E07B39',
  /** Cœur incandescent (centre des cœurs) : blanc chaud. */
  core: '#FFF3E4',
  /** Le cerveau principal (Opus) au travail. */
  deep: '#9B7BFF',
  /** Centre du grand cerveau. */
  deepCore: '#F3EDFF',
  /** Connecté / réussi. */
  green: '#4CF2A0',
  /** Attend le PO (« oui », question, à envoyer, attente) : le cyan, puisque l'orange est la teinte dominante. */
  wait: '#5FD4FF',
  /** Alerte. */
  alert: '#FF5A6E',
  /** Éteint / déconnecté. */
  off: '#2A4256',
  /** Lueur centrale du fond (dégradé vers le navy), chaude sous le cœur. */
  abyss: '#26170E',
  /** Fond et brouillard : le navy de la charte `--cg-navy`. */
  fog: '#0B1020',
  /** Texte des étiquettes et panneaux : l'orange clair `--cg-orange-2` (8,2:1 sur navy). */
  text: '#F0954F',
};

/** Les cinq paliers de transparence (§7.1) : aucune valeur intermédiaire arbitraire. */
export const HOLO_ALPHA = {
  latent: 0.04,
  context: 0.18,
  active: 0.45,
  panel: 0.75,
  lit: 1,
} as const;

export type HoloAlpha = keyof typeof HOLO_ALPHA;

/** Opacité de la teinte atténuée (orbites, trame, liserés). */
export const HOLO_DIM_OPACITY = 0.35;

/** Les courbes nommées (§7.4) : cubic-bezier, partagées CSS / JS. */
export const MOTION_CURVES = {
  sortie: [0.16, 1, 0.3, 1],
  entree: [0.7, 0, 0.84, 0],
  glisse: [0.65, 0, 0.35, 1],
  souffle: [0.37, 0, 0.63, 1],
  rebond: [0.34, 1.3, 0.64, 1],
} as const satisfies Record<string, readonly [number, number, number, number]>;

export type MotionCurve = keyof typeof MOTION_CURVES;

/** Le ressort de caméra : amorti critique, aucun dépassement. */
export const CAMERA_SPRING_OMEGA = 5.5;

/** Durées des transitions livrées par le socle (ms) ; les suivantes s'ajoutent ici, nulle part ailleurs. */
export const MOTION_DURATIONS = {
  /** T-F — fondu croisé 2D ↔ 3D (chargement, repli) : jamais de noir. */
  fade: 400,
  /** T-Q — changement de niveau de qualité. */
  quality: 600,
  /** SF-236-02 — T-C1 cœur : repos → écoute. */
  heartListen: 350,
  /** T-C2 — écoute → réflexion. */
  heartThink: 600,
  /** T-C3 — réflexion → parole. */
  heartSpeak: 250,
  /** T-C4 — → repos. */
  heartRest: 1200,
  /** T-C5 — → « oui » attendu. */
  heartWait: 500,
  /** T-C6 — coupure : gel, puis effacement (400 ms). */
  heartCutFreeze: 1000,
  heartCut: 400,
  /** T-V1 — mise en veille. */
  sleep: 2500,
  /** T-V2 — réveil (onde de choc). */
  wake: 600,
  /** SF-236-08 — T-P1 apparition d'un panneau (`rebond`), balayage lumineux, filament d'ancrage, écart entre panneaux. */
  panelIn: 420,
  panelSweep: 300,
  panelFilament: 250,
  panelStagger: 90,
  /** T-P2 — disparition d'un panneau (`entree`). */
  panelOut: 280,
  /** S-GEN — un anneau d'impulsion par appel. */
  pulse: 900,
} as const;

/** Lit une courbe en `cubic-bezier(...)`. */
export function cssCurve(name: MotionCurve): string {
  return `cubic-bezier(${MOTION_CURVES[name].join(', ')})`;
}

/**
 * Évalue une courbe de Bézier cubique (0,0)-(x1,y1)-(x2,y2)-(1,1) à l'avancement `t` ∈ [0, 1] (bissection sur x,
 * déterministe). Sert au moteur, qui n'interpole jamais autrement.
 */
export function ease(name: MotionCurve, t: number): number {
  if (t <= 0) {
    return 0;
  }
  if (t >= 1) {
    return 1;
  }
  const [x1, y1, x2, y2] = MOTION_CURVES[name];
  const bez = (a: number, b: number, s: number): number => {
    const u = 1 - s;
    return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s;
  };
  let lo = 0;
  let hi = 1;
  let s = t;
  for (let i = 0; i < 30; i++) {
    s = (lo + hi) / 2;
    if (bez(x1, x2, s) < t) {
      lo = s;
    } else {
      hi = s;
    }
  }
  return bez(y1, y2, s);
}

/** Convertit `#RRGGBB` en composantes 0-1 (pour les uniformes du moteur). */
export function rgb(hex: string): [number, number, number] {
  const n = Number.parseInt(hex.slice(1), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

/** Les propriétés CSS de la palette et du mouvement, posées sur l'hôte de l'écran `/jarvis`. */
export function holoCssVars(): Record<string, string> {
  const vars: Record<string, string> = {
    '--cg-holo': HOLO_COLORS.normal,
    '--cg-holo-dim': `color-mix(in srgb, ${HOLO_COLORS.normal} ${HOLO_DIM_OPACITY * 100}%, transparent)`,
    '--cg-holo-core': HOLO_COLORS.core,
    '--cg-holo-deep': HOLO_COLORS.deep,
    '--cg-holo-deep-core': HOLO_COLORS.deepCore,
    '--cg-holo-green': HOLO_COLORS.green,
    '--cg-holo-wait': HOLO_COLORS.wait,
    '--cg-holo-red': HOLO_COLORS.alert,
    '--cg-holo-off': HOLO_COLORS.off,
    '--cg-holo-abyss': HOLO_COLORS.abyss,
    '--cg-holo-text': HOLO_COLORS.text,
  };
  for (const name of Object.keys(MOTION_CURVES) as MotionCurve[]) {
    vars[`--jv-ease-${name}`] = cssCurve(name);
  }
  for (const [name, ms] of Object.entries(MOTION_DURATIONS)) {
    vars[`--jv-dur-${name}`] = `${ms}ms`;
  }
  return vars;
}
