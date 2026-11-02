import {
  PANEL_FLY_MS, PanelLook, PanelViewport, PanelRequest, flyLook, panelLook, placePanels,
} from '../jarvis-panels';
import { MOTION_DURATIONS } from '../jarvis-motion';
import { CORE_KEY } from '../jarvis-link-graph';
import { Panel3d } from '../scenes3d/jarvis-scene3d.model';
import { ensurePanelsStyle } from './jarvis-panels-style';

/**
 * F-236 / SF-236-08 — **les panneaux holographiques** (cadrage T6, §7.2) : du <b>DOM</b> en verre holographique,
 * projeté depuis son ancre 3D et toujours <b>face à l'écran</b> (texte jamais incliné ni flouté). Le moteur les
 * anime sur <b>son</b> horloge (T-P1 / T-P2), d'où des captures reproductibles au banc ; le contenu est écrit par
 * `textContent` (jamais de HTML injecté).
 */

/** Un point de l'écran, ou `null` si l'ancre est derrière la caméra. */
export type Projector = (key: string) => { x: number; y: number } | null;

interface LivePanel {
  spec: Panel3d;
  el: HTMLDivElement;
  sweep: HTMLSpanElement;
  bar: HTMLSpanElement | null;
  filament: HTMLDivElement;
  phase: 'in' | 'out';
  start: number;
  rank: number;
  w: number;
  h: number;
  x: number;
  y: number;
  placed: boolean;
  signature: string;
  /** T-P3 — le vol vers « À envoyer » : début, départ, arrivée. */
  fly: { start: number; fromX: number; fromY: number; toX: number; toY: number } | null;
  bornFlying: boolean;
}

/** Suivi de la position visée (ms) : un panneau glisse vers sa place, jamais de saut. */
const TAU_PLACE = 120;

export class Panels3d {
  private readonly layer: HTMLDivElement | null;
  private readonly live = new Map<string, LivePanel>();
  private lastFrame: number | null = null;

  constructor(host: HTMLElement | null) {
    if (host) {
      ensurePanelsStyle(document);
      this.layer = document.createElement('div');
      this.layer.className = 'jv3d-panels';
      host.appendChild(this.layer);
    } else {
      this.layer = null;
    }
  }

  /** Les panneaux du plan (déjà bornés par le directeur). `out` = banc : ils sortent depuis `now` (T-P2). */
  set(panels: readonly Panel3d[], now: number, out = false): void {
    if (!this.layer) {
      return;
    }
    const wanted = new Set(panels.map((p) => p.id));
    for (const live of this.live.values()) {
      if (!wanted.has(live.spec.id) && live.phase === 'in') {
        live.phase = 'out';
        live.start = now;
      }
    }
    let rank = 0;
    for (const spec of panels) {
      const existing = this.live.get(spec.id);
      if (existing) {
        if (existing.phase === 'out') {
          // Le même panneau revient : il repart de l'état courant (interruptible), sans rejouer son entrée.
          existing.phase = 'in';
          existing.start = now - MOTION_DURATIONS.panelIn;
        }
        this.write(existing, spec);
        continue;
      }
      const live = this.create(spec, now, rank++);
      if (out) {
        live.phase = 'out';
        live.start = now;
      }
    }
  }

  /** Une image : placement (quadrant libre) et aspect (T-P1 / T-P2) ; les panneaux sortis sont retirés. */
  frame(now: number, project: Projector, viewport: PanelViewport): void {
    if (!this.layer || this.live.size === 0) {
      this.lastFrame = now;
      return;
    }
    const dt = this.lastFrame === null ? 0 : Math.max(0, now - this.lastFrame);
    this.lastFrame = now;
    const ordered = [...this.live.values()].sort((a, b) => Number(!!b.spec.front) - Number(!!a.spec.front));
    const requests: PanelRequest[] = [];
    const anchors = new Map<string, { x: number; y: number } | null>();
    for (const p of ordered) {
      const anchor = p.spec.anchor === CORE_KEY ? { x: viewport.core.x, y: viewport.core.y } : project(p.spec.anchor);
      anchors.set(p.spec.id, anchor);
      requests.push({ id: p.spec.id, anchor, onCore: p.spec.anchor === CORE_KEY || !anchor, w: p.w, h: p.h,
        front: p.spec.front });
    }
    const spots = new Map(placePanels(requests, viewport).map((s) => [s.id, s]));
    const k = dt <= 0 ? 1 : 1 - Math.exp(-dt / TAU_PLACE);
    for (const p of ordered) {
      const spot = spots.get(p.spec.id)!;
      if (!p.placed) {
        p.x = spot.x;
        p.y = spot.y;
        p.placed = true;
      } else if (p.phase === 'in') {
        p.x += (spot.x - p.x) * k;
        p.y += (spot.y - p.y) * k;
      }
      if (p.spec.fly && p.placed && !p.fly && p.phase === 'in') {
        this.takeOff(p, now, viewport);
      }
      if (p.fly) {
        this.applyFly(p, now);
      } else {
        const look = panelLook(p.phase, now - p.start, p.phase === 'in' ? p.rank : 0);
        this.apply(p, look, anchors.get(p.spec.id) ?? viewport.core);
      }
      if (p.phase === 'out' && now - p.start >= MOTION_DURATIONS.panelOut) {
        p.el.remove();
        p.filament.remove();
        this.live.delete(p.spec.id);
      }
    }
  }

  /** Les panneaux affichés (tests au banc). */
  get count(): number {
    return this.live.size;
  }

  dispose(): void {
    for (const p of this.live.values()) {
      p.el.remove();
      p.filament.remove();
    }
    this.live.clear();
    this.layer?.remove();
  }

  private create(spec: Panel3d, now: number, rank: number): LivePanel {
    const el = document.createElement('div');
    el.className = 'jv3d-panel';
    const sweep = document.createElement('span');
    sweep.className = 'jv3d-panel__sweep';
    const filament = document.createElement('div');
    filament.className = 'jv3d-filament';
    this.layer!.appendChild(filament);
    this.layer!.appendChild(el);
    const live: LivePanel = { spec, el, sweep, bar: null, filament, phase: 'in', start: now, rank, w: 0, h: 0, x: 0,
      y: 0, placed: false, signature: '', fly: null, bornFlying: !!spec.fly };
    this.write(live, spec);
    this.live.set(spec.id, live);
    return live;
  }

  /** Écrit le contenu (texte seulement) et remesure si la forme change. */
  private write(live: LivePanel, spec: Panel3d): void {
    live.spec = spec;
    const signature = JSON.stringify([spec.title, spec.lines, spec.kind, spec.tone, spec.mono, spec.progress,
      spec.scroll]);
    if (signature === live.signature) {
      return;
    }
    live.signature = signature;
    const el = live.el;
    el.dataset['tone'] = spec.tone;
    el.dataset['kind'] = spec.kind;
    el.classList.toggle('jv3d-panel--mono', !!spec.mono);
    el.classList.toggle('jv3d-panel--front', !!spec.front);
    live.filament.dataset['tone'] = spec.tone;
    el.replaceChildren(live.sweep);
    const title = document.createElement('p');
    title.className = 'jv3d-panel__title';
    title.textContent = spec.title;
    el.appendChild(title);
    for (const line of spec.lines) {
      const p = document.createElement('p');
      p.className = 'jv3d-panel__line';
      if (line.hot) {
        p.classList.add('is-hot');
      }
      if (line.tone) {
        p.dataset['tone'] = line.tone;
      }
      p.textContent = line.text;
      el.appendChild(p);
    }
    if (spec.scroll) {
      // N11 — les pages qui défilent : des lignes de texte stylisées (aucun contenu) qui remontent lentement.
      const pages = document.createElement('span');
      pages.className = 'jv3d-panel__pages';
      el.appendChild(pages);
    }
    live.bar = null;
    if (spec.progress !== undefined) {
      const bar = document.createElement('span');
      bar.className = 'jv3d-panel__bar';
      const fill = document.createElement('span');
      fill.style.width = `${Math.round(Math.max(0, Math.min(1, spec.progress)) * 100)}%`;
      bar.appendChild(fill);
      el.appendChild(bar);
      live.bar = bar;
    }
    // Une seule lecture de mise en page par changement de contenu (jamais à chaque image).
    live.w = el.offsetWidth || 240;
    live.h = el.offsetHeight || 64;
  }

  /** T-P3 — départ du vol : la cible est le compteur « À envoyer » du HUD, sinon le coin haut droit. */
  private takeOff(p: LivePanel, now: number, viewport: PanelViewport): void {
    // Une seule lecture de mise en page, au départ du vol.
    const counter = typeof document === 'undefined' ? null : document.querySelector('.jv__count');
    const rect = counter?.getBoundingClientRect();
    const toX = rect && rect.width > 0 ? rect.left + rect.width / 2 : viewport.width - 120;
    const toY = rect && rect.height > 0 ? rect.top + rect.height / 2 : 32;
    // Un panneau né en partance (bloc fini d'un coup, ou banc) part dès son apparition.
    p.fly = { start: p.bornFlying ? p.start : now, fromX: p.x + p.w / 2, fromY: p.y + p.h / 2, toX, toY };
    p.el.classList.add('is-flying');
  }

  /** T-P3 — arc (levée vers le haut), réduction 1 → 0,25 ; le filament (L-message) suit, la traînée s'éteint. */
  private applyFly(p: LivePanel, now: number): void {
    const f = p.fly!;
    const look = flyLook(now - f.start);
    const cx = f.fromX + (f.toX - f.fromX) * look.k;
    const cy = f.fromY + (f.toY - f.fromY) * look.k - 80 * look.lift;
    const style = p.el.style;
    style.opacity = look.opacity.toFixed(3);
    style.transform = `translate3d(${Math.round(cx - p.w / 2)}px, ${Math.round(cy - p.h / 2)}px, 0) scale(${look.scale
      .toFixed(4)})`;
    p.el.classList.toggle('is-sweeping', false);
    p.filament.style.opacity = (0.6 * (1 - look.k)).toFixed(3);
    if (now - f.start >= PANEL_FLY_MS) {
      style.opacity = '0';
    }
  }

  private apply(p: LivePanel, look: PanelLook, anchor: { x: number; y: number }): void {
    const x = Math.round(p.x);
    const y = Math.round(p.y);
    const style = p.el.style;
    style.opacity = look.opacity.toFixed(3);
    style.transform = `translate3d(${x}px, ${y}px, 0) scale(${look.scale.toFixed(4)})`;
    p.el.style.setProperty('--sweep', look.sweep === null ? '-1' : look.sweep.toFixed(3));
    p.el.classList.toggle('is-sweeping', look.sweep !== null);
    // Le filament d'ancrage : de l'ancre au point le plus proche du panneau, tracé depuis le nœud.
    const tx = Math.max(x, Math.min(anchor.x, x + p.w));
    const ty = Math.max(y, Math.min(anchor.y, y + p.h));
    const dx = tx - anchor.x;
    const dy = ty - anchor.y;
    const length = Math.hypot(dx, dy);
    const f = p.filament.style;
    f.width = `${Math.round(length)}px`;
    f.opacity = (look.opacity * 0.9).toFixed(3);
    f.transform = `translate3d(${Math.round(anchor.x)}px, ${Math.round(anchor.y)}px, 0) rotate(${Math.atan2(dy, dx)
      .toFixed(4)}rad) scaleX(${look.filament.toFixed(3)})`;
  }
}
