/**
 * F-237 / SF-237-05 — la **galerie en éventail** (cadrage §8.1) : les cartes sur un arc de 110° devant la caméra,
 * rayon fixe, profondeur légèrement alternée ; au plus 12 visibles, l'arc défile autour de la sélection. Pur : rend
 * la position de chaque carte, l'écran l'applique en transformation CSS 3D (perspective).
 */

export const FAN_ARC_DEG = 110;
export const FAN_VISIBLE = 12;
export const FAN_RADIUS_PX = 900;
/** Profondeur alternée (une carte sur deux un peu en retrait). */
export const FAN_DEPTH_PX = 40;
/** F-264 — la carte choisie grandit dans l'arc (×1,25 : à ×1,6 elle chevauchait le panneau centré au-dessus). */
export const HERO_SCALE = 1.25;

export interface FanSlot {
  /** Index de la carte (1..n). */
  index: number;
  /** Visible dans l'arc. */
  visible: boolean;
  /** Angle sur l'arc, en degrés (0 = face à la caméra). */
  angle: number;
  /** Décalage horizontal (px) et profondeur (px, négatif = au loin). */
  x: number;
  z: number;
  /** La carte héros (sélectionnée) avance et grandit. */
  hero: boolean;
  /** Échelle appliquée. */
  scale: number;
  /** Palier de transparence (DESIGN_SYSTEM §23) : 1 sans héros et pour le héros, 0,75 pour les autres (F-264 : jamais
   * 0,18 — le PO doit les voir nettement pour dire « la troisième »). */
  alpha: number;
}

/** La première carte visible de l'arc, centrée sur la sélection autant que possible. */
export function fanStart(count: number, selected: number | null): number {
  if (count <= FAN_VISIBLE || selected == null) {
    return 1;
  }
  const half = Math.floor(FAN_VISIBLE / 2);
  return Math.min(Math.max(1, selected - half), count - FAN_VISIBLE + 1);
}

/** La position de chaque carte. */
export function fanLayout(count: number, selected: number | null): FanSlot[] {
  const start = fanStart(count, selected);
  const shown = Math.min(count, FAN_VISIBLE);
  const step = shown > 1 ? FAN_ARC_DEG / (shown - 1) : 0;
  const slots: FanSlot[] = [];
  for (let index = 1; index <= count; index++) {
    const rank = index - start;
    const visible = rank >= 0 && rank < shown;
    const angle = visible ? -FAN_ARC_DEG / 2 + rank * step + (shown === 1 ? FAN_ARC_DEG / 2 : 0) : 0;
    const rad = (angle * Math.PI) / 180;
    const hero = selected === index;
    const depth = rank % 2 === 0 ? 0 : -FAN_DEPTH_PX;
    slots.push({
      index, visible, angle: Math.round(angle * 100) / 100,
      x: Math.round(Math.sin(rad) * FAN_RADIUS_PX * 0.6),
      z: hero ? 120 : Math.round((Math.cos(rad) - 1) * FAN_RADIUS_PX * 0.4) + depth,
      hero, scale: hero ? HERO_SCALE : 1,
      alpha: selected == null || hero ? 1 : 0.75,
    });
  }
  return slots;
}

/**
 * F-264 (cadrage F-237 §8.4, T-G5) — **l'arc tourne autour de la pièce choisie** : la carte héros revient face à la
 * caméra (angle 0, au centre), les autres gardent leur écart sur l'arc ; une carte qui passerait derrière (au-delà de
 * 90°) est masquée. Sans sélection, l'arc est rendu tel quel.
 */
export function arcAroundHero(slots: FanSlot[]): FanSlot[] {
  const hero = slots.find((s) => s.hero && s.visible);
  if (!hero || hero.angle === 0) {
    return slots;
  }
  return slots.map((slot) => {
    if (!slot.visible) {
      return slot;
    }
    const angle = Math.round((slot.angle - hero.angle) * 100) / 100;
    const rad = (angle * Math.PI) / 180;
    const depth = slot.hero ? 0 : (slot.index - hero.index) % 2 === 0 ? 0 : -FAN_DEPTH_PX;
    return { ...slot, angle, visible: Math.abs(angle) <= 90,
      x: Math.round(Math.sin(rad) * FAN_RADIUS_PX * 0.6),
      z: slot.hero ? 120 : Math.round((Math.cos(rad) - 1) * FAN_RADIUS_PX * 0.4) + depth };
  });
}
