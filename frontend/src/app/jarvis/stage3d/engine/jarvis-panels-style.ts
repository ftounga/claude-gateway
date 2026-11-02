/**
 * F-236 / SF-236-08 — **le décor des panneaux holographiques** (cadrage §7.2 « verre holographique », T6), en CSS
 * posé une fois dans le document par le moteur 3D : il vit dans le <b>morceau paresseux</b> (bundle initial +0, budget
 * des styles de l'écran inchangé). Couleurs : jetons `--cg-holo-*` (DESIGN_SYSTEM §23) seulement ; polices de la
 * charte. Le moteur anime opacité, échelle, balayage et filament sur son horloge (T-P1 / T-P2) ; le CSS ne fait que
 * le décor (verre, trame qui défile en 8 s, reflet qui traverse en 12 s).
 */
export const PANELS_STYLE_ID = 'jv3d-panels-style';

export const PANELS_CSS = `
.jv3d-panels {
  position: absolute;
  inset: 0;
  /* Au-dessus des étiquettes : CSS2DRenderer leur donne un z-index par profondeur (jusqu'au nombre de nœuds). */
  z-index: 100000;
  pointer-events: none;
  overflow: hidden;
}

.jv3d-panel,
.jv3d-filament {
  --tone: var(--cg-holo, #E07B39);
  --tone-text: var(--cg-holo-text, #F0954F);
}
.jv3d-panel[data-tone=wait],
.jv3d-filament[data-tone=wait] {
  --tone: var(--cg-holo-wait, #5FD4FF);
  --tone-text: var(--cg-holo-wait, #5FD4FF);
}
.jv3d-panel[data-tone=deep],
.jv3d-filament[data-tone=deep] {
  --tone: var(--cg-holo-deep, #9B7BFF);
  --tone-text: var(--cg-holo-deep, #9B7BFF);
}
.jv3d-panel[data-tone=green],
.jv3d-filament[data-tone=green] {
  --tone: var(--cg-holo-green, #4CF2A0);
  --tone-text: var(--cg-holo-green, #4CF2A0);
}
.jv3d-panel[data-tone=alert],
.jv3d-filament[data-tone=alert] {
  --tone: var(--cg-holo-red, #FF5A6E);
  --tone-text: var(--cg-holo-red, #FF5A6E);
}
.jv3d-panel[data-tone=off],
.jv3d-filament[data-tone=off] {
  --tone: var(--cg-holo-off, #2A4256);
  --tone-text: var(--cg-cream, #F5EFE3);
}

@keyframes jv3d-panel-reflect {
  from {
    transform: translateX(-120%) skewX(-20deg);
  }
  to {
    transform: translateX(320%) skewX(-20deg);
  }
}
@keyframes jv3d-panel-scan {
  from {
    background-position: 0 0;
  }
  to {
    background-position: 0 24px;
  }
}
.jv3d-panel {
  position: absolute;
  top: 0;
  left: 0;
  box-sizing: border-box;
  width: max-content;
  min-width: 160px;
  max-width: min(300px, 100vw - 32px);
  padding: 8px 12px 12px;
  border: 1px solid color-mix(in srgb, var(--tone) 70%, transparent);
  background: linear-gradient(180deg, color-mix(in srgb, var(--tone) 13%, transparent), transparent 70%), color-mix(in srgb, var(--cg-navy, #0B1020) 84%, transparent);
  clip-path: polygon(10px 0, 100% 0, 100% calc(100% - 10px), calc(100% - 10px) 100%, 0 100%, 0 10px);
  color: var(--cg-cream, #F5EFE3);
  opacity: 0;
  transform-origin: 50% 50%;
  will-change: transform, opacity;
  overflow: hidden;
}
.jv3d-panel::before {
  content: "";
  position: absolute;
  inset: 0;
  background: repeating-linear-gradient(180deg, color-mix(in srgb, var(--tone) 4%, transparent) 0 1px, transparent 1px 3px);
  animation: jv3d-panel-scan 8s linear infinite;
  pointer-events: none;
}
.jv3d-panel::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 30%;
  background: linear-gradient(90deg, transparent, color-mix(in srgb, var(--cg-cream, #F5EFE3) 6%, transparent), transparent);
  animation: jv3d-panel-reflect 12s linear infinite;
  pointer-events: none;
}
.jv3d-panel--front {
  z-index: 2;
}
.jv3d-panel--mono .jv3d-panel__line {
  font-family: var(--cg-font-mono);
  font-size: 12px;
  white-space: pre;
}

@keyframes jv3d-pages {
  from { background-position: 0 0; }
  to { background-position: 0 -60px; }
}
.jv3d-panel.is-flying {
  box-shadow: 0 0 24px var(--tone);
}
.jv3d-panel__pages {
  position: relative;
  display: block;
  height: 36px;
  margin-top: 8px;
  background: repeating-linear-gradient(180deg, color-mix(in srgb, var(--tone) 35%, transparent) 0 2px,
    transparent 2px 6px, color-mix(in srgb, var(--tone) 22%, transparent) 6px 8px, transparent 8px 12px);
  background-size: 70% 60px;
  background-repeat: repeat-y;
  -webkit-mask-image: linear-gradient(180deg, transparent, #000 30%, #000 70%, transparent);
  mask-image: linear-gradient(180deg, transparent, #000 30%, #000 70%, transparent);
  animation: jv3d-pages 4s linear infinite;
}
@media (max-width: 767px) {
  .jv3d-panel {
    max-width: min(240px, 100vw - 32px);
  }
}
@media (pointer: fine) {
  .jv3d-panel {
    backdrop-filter: blur(10px);
  }
}
.jv3d-panel__title {
  position: relative;
  margin: 0 0 4px;
  font-family: var(--cg-font-heading);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  line-height: 16px;
  text-transform: uppercase;
  color: var(--tone-text);
}

.jv3d-panel__line {
  position: relative;
  margin: 0;
  overflow: hidden;
  font-family: var(--cg-font-body);
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.jv3d-panel__line.is-hot {
  color: var(--cg-holo-core, #FFF3E4);
  font-weight: 600;
}
.jv3d-panel__line[data-tone=green] {
  color: var(--cg-holo-green, #4CF2A0);
}
.jv3d-panel__line[data-tone=wait] {
  color: var(--cg-holo-wait, #5FD4FF);
}
.jv3d-panel__line[data-tone=alert] {
  color: var(--cg-holo-red, #FF5A6E);
}

.jv3d-panel__bar {
  position: relative;
  display: block;
  height: 4px;
  margin-top: 8px;
  background: color-mix(in srgb, var(--tone) 18%, transparent);
}
.jv3d-panel__bar > span {
  display: block;
  height: 100%;
  background: var(--tone);
  transition: width var(--jv-dur-fade, 400ms) var(--jv-ease-glisse, ease-in-out);
}

.jv3d-panel__sweep {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(var(--sweep, -1) * 100% - 12px);
  display: none;
  height: 24px;
  background: linear-gradient(180deg, transparent, color-mix(in srgb, var(--tone) 45%, transparent), transparent);
  pointer-events: none;
}

.jv3d-panel.is-sweeping .jv3d-panel__sweep {
  display: block;
}

.jv3d-filament {
  position: absolute;
  top: 0;
  left: 0;
  height: 1px;
  background: linear-gradient(90deg, var(--tone), color-mix(in srgb, var(--tone) 40%, transparent));
  box-shadow: 0 0 6px var(--tone);
  transform-origin: 0 50%;
  opacity: 0;
  will-change: transform, opacity;
}
`;

/** Pose la feuille une fois (idempotent). */
export function ensurePanelsStyle(doc: Document): void {
  if (doc.getElementById(PANELS_STYLE_ID)) {
    return;
  }
  const style = doc.createElement('style');
  style.id = PANELS_STYLE_ID;
  style.textContent = PANELS_CSS;
  doc.head.appendChild(style);
}
