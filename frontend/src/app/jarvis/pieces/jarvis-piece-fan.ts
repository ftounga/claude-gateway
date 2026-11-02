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
  /** Palier de transparence (DESIGN_SYSTEM §23) : 1 héros, 0,75 sans héros, 0,18 latent. */
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
      hero, scale: hero ? 1.6 : 1,
      alpha: selected == null ? 0.75 : hero ? 1 : 0.18,
    });
  }
  return slots;
}
