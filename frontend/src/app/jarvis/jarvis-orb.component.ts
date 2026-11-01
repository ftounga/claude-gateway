import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy, ViewChild, inject, input } from '@angular/core';

/** Ce que fait Jarvis — l'orbe le montre (D6). */
export type JarvisState = 'repos' | 'ecoute' | 'reflexion' | 'parole';

interface Node3 {
  x: number;
  y: number;
  z: number;
}

/** Nombre de neurones de l'orbe. */
const NODES = 96;
/** Deux neurones plus proches que ce seuil (sphère unité) sont reliés. */
const LINK = 0.42;

/**
 * **L'orbe neuronal de Jarvis** (F-231 / SF-231-02, D6) : un réseau de neurones sur une sphère, qui tourne et
 * respire. Il dit l'état — **repos** (lent, discret), **écoute** (s'ouvre avec la voix du PO), **réflexion**
 * (tourne plus vite, les liens s'allument), **parole** (pulse avec la voix de Jarvis).
 *
 * <p>Couleurs du design system (`--cg-accent`, `--cg-orange-2`, `--cg-cream`), lues sur l'hôte : aucune couleur
 * nouvelle. La boucle d'animation tourne hors de la détection de changements ; elle ralentit quand l'utilisateur
 * préfère moins de mouvement.</p>
 */
@Component({
  selector: 'app-jarvis-orb',
  template: `<canvas #canvas class="orb" role="img" [attr.aria-label]="label()"></canvas>`,
  styles: `
    :host { display: block; position: relative; width: 100%; aspect-ratio: 1; }
    .orb { width: 100%; height: 100%; display: block; }
  `,
})
export class JarvisOrbComponent implements AfterViewInit, OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly state = input<JarvisState>('repos');
  /** Le niveau sonore du moment (micro en écoute, voix en parole), de 0 à 1. */
  readonly level = input<() => number>(() => 0);

  @ViewChild('canvas', { static: true }) private canvas!: ElementRef<HTMLCanvasElement>;

  private readonly nodes: Node3[] = sphere(NODES);
  private frame = 0;
  private angle = 0;
  private smoothed = 0;
  private colors = { accent: '#E07B39', glow: '#F0954F', ink: '#F5EFE6' };
  private readonly reducedMotion = typeof window !== 'undefined'
    && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  label(): string {
    switch (this.state()) {
      case 'ecoute': return 'Jarvis écoute';
      case 'reflexion': return 'Jarvis réfléchit';
      case 'parole': return 'Jarvis parle';
      default: return 'Jarvis au repos';
    }
  }

  ngAfterViewInit(): void {
    const style = getComputedStyle(this.host.nativeElement);
    this.colors = {
      accent: style.getPropertyValue('--cg-accent').trim() || this.colors.accent,
      glow: style.getPropertyValue('--cg-orange-2').trim() || this.colors.glow,
      ink: style.getPropertyValue('--cg-cream').trim() || this.colors.ink,
    };
    this.zone.runOutsideAngular(() => {
      const tick = () => {
        this.draw();
        this.frame = requestAnimationFrame(tick);
      };
      this.frame = requestAnimationFrame(tick);
    });
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }

  private draw(): void {
    const canvas = this.canvas.nativeElement;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }
    const ratio = window.devicePixelRatio || 1;
    const size = Math.max(1, Math.round(canvas.clientWidth * ratio));
    if (canvas.width !== size || canvas.height !== size) {
      canvas.width = size;
      canvas.height = size;
    }
    const state = this.state();
    const raw = state === 'ecoute' || state === 'parole' ? this.level()() : 0;
    this.smoothed += (raw - this.smoothed) * 0.25;
    const speed = (state === 'reflexion' ? 0.018 : state === 'repos' ? 0.003 : 0.008) * (this.reducedMotion ? 0.3 : 1);
    this.angle += speed;
    const breathe = state === 'repos' ? Math.sin(performance.now() / 1600) * 0.02 : 0;
    const radius = size * (0.33 + breathe + this.smoothed * 0.08);
    const cx = size / 2;
    const cy = size / 2;

    ctx.clearRect(0, 0, size, size);
    // Le halo : plus vif quand Jarvis écoute ou parle.
    const halo = ctx.createRadialGradient(cx, cy, radius * 0.2, cx, cy, radius * 1.45);
    halo.addColorStop(0, withAlpha(this.colors.accent, 0.18 + this.smoothed * 0.3));
    halo.addColorStop(1, withAlpha(this.colors.accent, 0));
    ctx.fillStyle = halo;
    ctx.fillRect(0, 0, size, size);

    const cos = Math.cos(this.angle);
    const sin = Math.sin(this.angle);
    const tilt = 0.35;
    const projected = this.nodes.map((n) => {
      const x = n.x * cos - n.z * sin;
      const z0 = n.x * sin + n.z * cos;
      const y = n.y * Math.cos(tilt) - z0 * Math.sin(tilt);
      const z = n.y * Math.sin(tilt) + z0 * Math.cos(tilt);
      return { x, y, z, sx: cx + x * radius, sy: cy + y * radius };
    });

    const linkAlpha = state === 'reflexion' ? 0.5 : state === 'repos' ? 0.16 : 0.3;
    ctx.lineWidth = Math.max(1, size / 600);
    for (let i = 0; i < projected.length; i++) {
      for (let j = i + 1; j < projected.length; j++) {
        const a = this.nodes[i];
        const b = this.nodes[j];
        const d = Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
        if (d > LINK) {
          continue;
        }
        const depth = (projected[i].z + projected[j].z + 2) / 4;
        ctx.strokeStyle = withAlpha(state === 'reflexion' ? this.colors.glow : this.colors.accent,
          linkAlpha * depth * (1 - d / LINK));
        ctx.beginPath();
        ctx.moveTo(projected[i].sx, projected[i].sy);
        ctx.lineTo(projected[j].sx, projected[j].sy);
        ctx.stroke();
      }
    }
    for (const p of projected) {
      const depth = (p.z + 1) / 2;
      ctx.fillStyle = withAlpha(depth > 0.5 ? this.colors.ink : this.colors.glow, 0.35 + depth * 0.6);
      ctx.beginPath();
      ctx.arc(p.sx, p.sy, (size / 220) * (0.6 + depth + this.smoothed), 0, Math.PI * 2);
      ctx.fill();
    }
  }
}

/** `count` points répartis régulièrement sur la sphère unité (spirale de Fibonacci). */
export function sphere(count: number): Node3[] {
  const nodes: Node3[] = [];
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < count; i++) {
    const y = 1 - (i / Math.max(1, count - 1)) * 2;
    const r = Math.sqrt(1 - y * y);
    const theta = golden * i;
    nodes.push({ x: Math.cos(theta) * r, y, z: Math.sin(theta) * r });
  }
  return nodes;
}

/** Une couleur `#RRGGBB` avec une opacité. Toute autre forme est rendue telle quelle. */
export function withAlpha(hex: string, alpha: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) {
    return hex;
  }
  const value = parseInt(m[1], 16);
  const a = Math.max(0, Math.min(1, alpha));
  return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${a.toFixed(3)})`;
}
