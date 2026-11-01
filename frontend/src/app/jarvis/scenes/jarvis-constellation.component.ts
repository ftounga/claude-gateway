import {
  Component, DestroyRef, OnInit, computed, effect, inject, input, output, signal, untracked,
} from '@angular/core';

import {
  CHANTIER_RING, CLIENT_RING, ConstellationLayout, PlacedSubject, SUBJECT_RING, anchorFor, layoutUniverse, polar,
} from './jarvis-constellation-layout';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { ShowFocus } from './jarvis-show';
import { JarvisUniverse, NodeRef, UniverseChantier } from './jarvis-universe.models';

/** Rayons de la carte (repris en dur dans le gabarit : 62 et 88). */
export const RINGS = { subjects: SUBJECT_RING, clients: CLIENT_RING } as const;

/** Sous cette largeur, téléphone : seul le client courant déploie ses sujets (D7). */
export const NARROW_QUERY = '(max-width: 767px)';
/** Au plus une annonce au lecteur d'écran toutes les 4 s, jamais pendant que Jarvis parle (D10). */
export const ANNOUNCE_EVERY_MS = 4000;

/** Un nœud cité, placé pour être dessiné (fil lumineux vers l'orbe, nom en surbrillance). */
interface LitMark {
  key: string;
  kind: NodeRef['kind'];
  x: number;
  y: number;
  label: string;
  anchor: 'start' | 'middle' | 'end';
  /** Le nœud est dessiné ici même (chantier, personne, sujet d'un client replié). */
  own: boolean;
}

/**
 * **La constellation** (F-234 / SF-234-03, D6, D7, D8, D9, D10, D12) — l'univers du PO autour de l'orbe : clients en
 * couronne, sujets du client courant en anneau ; le nœud dont il est question s'allume, un fil lumineux le relie à
 * l'orbe, son nom passe en surbrillance ; un fil d'Ariane texte dit toujours la même chose (équivalent accessible).
 *
 * <p>SVG + CSS, pas de WebGL (D6) : noms nets, animations de `opacity` / `stroke-dashoffset` seulement, aucune
 * animation sous `prefers-reduced-motion` (D9). Toucher un sujet = « passe sur X » (même effet que le menu, D12) ;
 * client et chantier ne se touchent pas. Aucune couleur nouvelle (D8).</p>
 */
@Component({
  selector: 'app-jarvis-constellation',
  template: `
    <div class="cst">
      <svg class="cst__map" [class.is-showing]="!!focus()" viewBox="-100 -100 200 200"
           preserveAspectRatio="xMidYMid meet">
        <g aria-hidden="true">
          <circle class="cst__orbit" r="62" />
          <circle class="cst__orbit cst__orbit--outer" r="88" />
          @for (p of pulse(); track p) {
            <circle class="cst__pulse" r="40" />
          }
          @for (m of lit(); track m.key) {
            <line class="cst__thread" [attr.x1]="m.x" [attr.y1]="m.y" x2="0" y2="0" pathLength="100" />
          }
          @for (c of layout().clients; track c.key) {
            <g class="cst__client" [class.is-current]="c.current" [class.is-off]="!c.connected"
               [class.is-lit]="isLit(c.key)" [class.is-shown]="isShown(c.key)" [attr.data-key]="c.key">
              @if (halo(c.key); as h) {
                <!-- SF-234-13 : poste connecté = halo vert ; déconnecté = halo éteint (la note écrite le dit aussi). -->
                <circle class="cst__halo" [class.cst__halo--off]="h === 'off'" [attr.cx]="c.x" [attr.cy]="c.y" r="8" />
              }
              <circle [attr.cx]="c.x" [attr.cy]="c.y" [attr.r]="isShown(c.key) ? 5.4 : 4.2" />
              <text [attr.x]="c.x" [attr.y]="c.y + (c.y < 0 ? -7 : 10)" text-anchor="middle">{{ c.name }}</text>
            </g>
          }
        </g>
        @for (s of layout().subjects; track s.id) {
          <g class="cst__subject" [class.is-cluster]="s.ring === 'cluster'" [class.is-lit]="isLit(s.id)"
             [class.is-shown]="isShown(s.id)"
             [class.is-current]="s.id === subjectId()" [class.is-live]="s.live" [attr.data-key]="s.id"
             role="button" tabindex="0" [attr.aria-label]="'Passer sur ' + s.name"
             (click)="pick(s)" (keydown.enter)="pick(s)" (keydown.space)="pick(s); $event.preventDefault()">
            <circle class="cst__hit" [attr.cx]="s.x" [attr.cy]="s.y" [attr.r]="hitRadius()" />
            <circle class="cst__dot" [attr.cx]="s.x" [attr.cy]="s.y" [attr.r]="s.ring === 'cluster' ? 1.4 : 2.6" />
            @if (s.named || isLit(s.id) || s.id === subjectId()) {
              <text aria-hidden="true" [attr.x]="labelX(s)" [attr.y]="s.y + 1.8"
                    [attr.text-anchor]="anchor(s.x)">{{ s.name }}</text>
            }
          </g>
        }
        <g aria-hidden="true">
          @for (m of shown(); track m.key) {
            <!-- SF-234-13 : chantiers et personnes demandés, au premier plan sur l'anneau des chantiers. -->
            <line class="cst__thread cst__thread--shown" [attr.x1]="m.x" [attr.y1]="m.y" x2="0" y2="0" pathLength="100" />
            <g class="cst__mark is-lit is-shown" [attr.data-kind]="m.kind" [attr.data-key]="m.key">
              @if (m.kind === 'chantier') {
                <rect [attr.x]="m.x - 2.6" [attr.y]="m.y - 2.6" width="5.2" height="5.2"
                      [attr.transform]="'rotate(45 ' + m.x + ' ' + m.y + ')'" />
              } @else {
                <rect [attr.x]="m.x - 2.4" [attr.y]="m.y - 2.4" width="4.8" height="4.8" rx="1.2" />
              }
              <text [attr.x]="m.x + (m.anchor === 'start' ? 5 : m.anchor === 'end' ? -5 : 0)"
                    [attr.y]="m.y + (m.anchor === 'middle' ? (m.y < 0 ? -5 : 8) : 1.8)"
                    [attr.text-anchor]="m.anchor">{{ m.label }}</text>
            </g>
          }
          @for (m of lit(); track m.key) {
            @if (m.own) {
              <g class="cst__mark is-lit" [attr.data-kind]="m.kind" [attr.data-key]="m.key">
                @switch (m.kind) {
                  @case ('chantier') {
                    <rect [attr.x]="m.x - 2.6" [attr.y]="m.y - 2.6" width="5.2" height="5.2"
                          [attr.transform]="'rotate(45 ' + m.x + ' ' + m.y + ')'" />
                  }
                  @case ('person') {
                    <rect [attr.x]="m.x - 2.4" [attr.y]="m.y - 2.4" width="4.8" height="4.8" rx="1.2" />
                  }
                  @default {
                    <circle [attr.cx]="m.x" [attr.cy]="m.y" r="2.4" />
                  }
                }
                <text [attr.x]="m.x + (m.anchor === 'start' ? 5 : m.anchor === 'end' ? -5 : 0)"
                      [attr.y]="m.y + (m.anchor === 'middle' ? -5 : 1.8)" [attr.text-anchor]="m.anchor">{{ m.label }}</text>
              </g>
            }
          }
        </g>
      </svg>
      <div class="cst__orb"><ng-content /></div>
    </div>
    <nav class="cst__crumbs" aria-label="Où en est Jarvis">
      @for (c of crumbs(); track $index; let last = $last) {
        <span class="cst__crumb" [class.is-last]="last">{{ c }}</span>
        @if (!last) { <span class="cst__sep" aria-hidden="true">›</span> }
      }
    </nav>
    <p class="cst__live" aria-live="polite">{{ announced() }}</p>
  `,
  styles: `
    :host { display: block; width: min(94vw, 560px); }
    .cst { position: relative; width: 100%; aspect-ratio: 1; }
    .cst__map { position: absolute; inset: 0; width: 100%; height: 100%; overflow: visible; pointer-events: none; }
    .cst__orb {
      position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none;
    }
    .cst__orb > * { pointer-events: auto; }
    .cst__orbit { fill: none; stroke: color-mix(in srgb, var(--cg-cream) 10%, transparent); stroke-width: 0.3; }
    .cst__orbit--outer { stroke-dasharray: 1 2.5; }
    /* Repère de 200 unités : 6,5 unités ≈ 12 px sur un téléphone de 390 px, ≈ 18 px au plus large. */
    text { font-family: var(--cg-font-body); font-size: 6.5px; fill: color-mix(in srgb, var(--cg-cream) 85%, transparent); }
    .cst__subject text { font-size: 6px; }
    .cst__client circle {
      fill: var(--cg-navy-2); stroke: color-mix(in srgb, var(--cg-cream) 55%, transparent); stroke-width: 0.6;
    }
    .cst__client.is-current circle { stroke: var(--cg-cream); stroke-width: 0.9; }
    .cst__client.is-current text { font-weight: 600; fill: var(--cg-cream); }
    .cst__client.is-off { opacity: 0.55; }
    /* SF-234-13 — « Montrer » : le demandé au premier plan, le reste estompé. */
    .cst__map.is-showing .cst__client:not(.is-shown), .cst__map.is-showing .cst__subject:not(.is-shown) {
      opacity: 0.22;
    }
    .cst__client.is-shown { opacity: 1; }
    .cst__client.is-shown text { fill: var(--cg-cream); font-weight: 600; }
    .cst__halo {
      fill: none; stroke: var(--cg-terminal-success); stroke-width: 1.1;
      animation: cst-breathe 2.4s ease-in-out infinite;
    }
    .cst__halo--off {
      stroke: color-mix(in srgb, var(--cg-cream) 30%, transparent); stroke-dasharray: 1 1.6; animation: none;
    }
    .cst__thread--shown { opacity: 0.35; }
    .cst__subject { pointer-events: auto; cursor: pointer; outline: none; }
    .cst__subject.is-cluster text { display: none; }
    .cst__hit { fill: transparent; }
    .cst__dot { fill: color-mix(in srgb, var(--cg-cream) 35%, transparent); transition: fill 200ms ease; }
    .cst__subject.is-current .cst__dot { fill: color-mix(in srgb, var(--cg-cream) 80%, transparent); }
    .cst__subject:focus-visible .cst__dot { stroke: var(--cg-accent); stroke-width: 0.8; }
    .is-lit circle, .is-lit .cst__dot, .cst__mark rect { fill: var(--cg-accent); }
    .is-lit text, .cst__mark text { fill: var(--cg-orange-2); font-weight: 600; }
    .cst__mark { animation: cst-in 300ms ease-out; }
    .cst__thread {
      stroke: var(--cg-orange-2); stroke-width: 0.6; stroke-linecap: round; opacity: 0.8;
      stroke-dasharray: 100; stroke-dashoffset: 0; animation: cst-draw 500ms ease-out;
    }
    .cst__pulse {
      fill: none; stroke: var(--cg-accent); stroke-width: 0.6; opacity: 0; animation: cst-pulse 900ms ease-out;
    }
    .cst__subject.is-live .cst__dot { animation: cst-breathe 2.4s ease-in-out infinite; }
    .cst__crumbs {
      display: flex; flex-wrap: wrap; justify-content: center; gap: 4px; margin: var(--cg-space-1) 0 0;
      font-size: 13px; color: color-mix(in srgb, var(--cg-cream) 80%, transparent); min-height: 18px;
    }
    .cst__crumb.is-last { color: var(--cg-orange-2); font-weight: 600; }
    .cst__live {
      position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; margin: -1px;
    }
    @keyframes cst-draw { from { stroke-dashoffset: 100; } to { stroke-dashoffset: 0; } }
    @keyframes cst-in { from { opacity: 0; } to { opacity: 1; } }
    @keyframes cst-pulse { 0% { opacity: 0.7; } 100% { opacity: 0; transform: scale(1.25); } }
    @keyframes cst-breathe { 0%, 100% { opacity: 0.55; } 50% { opacity: 1; } }
    @media (prefers-reduced-motion: reduce) {
      .cst__thread, .cst__mark, .cst__pulse, .cst__subject.is-live .cst__dot, .cst__halo { animation: none; }
      .cst__pulse { display: none; }
      .cst__dot { transition: none; }
    }
  `,
})
export class JarvisConstellationComponent implements OnInit {
  private readonly bus = inject(JarvisSceneBus);
  private readonly destroyRef = inject(DestroyRef);

  /** Le sujet courant de l'écran. */
  readonly subjectId = input<string | null>(null);
  /** Jarvis parle : aucune annonce au lecteur d'écran pendant ce temps (D10). */
  readonly speaking = input(false);
  /** Toucher un sujet : « passe sur X » (D12). */
  readonly choose = output<string>();

  /** Téléphone (< 768 px). */
  readonly narrow = signal(matches(NARROW_QUERY));

  readonly layout = computed<ConstellationLayout>(() =>
    layoutUniverse(this.bus.universe(), this.subjectId(), this.narrow()));

  private readonly litKeys = computed(() => new Set(this.bus.state().lit.map((l) => l.node.key)));

  /** Un élément par appel d'outil : l'anneau pulse une fois (le dernier seulement est rendu). */
  readonly pulse = computed(() => {
    const n = this.bus.state().pulses;
    return n > 0 ? [n] : [];
  });

  readonly lit = computed<LitMark[]>(() => this.placeLit(this.bus.universe(), this.layout()));

  /** SF-234-13 — ce que le PO a demandé à voir. */
  readonly focus = computed<ShowFocus | null>(() => this.bus.focus());

  /** Les nœuds mis au premier plan, et leurs parents (client, sujet) qui ne s'estompent pas. */
  private readonly shownKeys = computed(() => {
    const keys = new Set<string>();
    for (const item of this.focus()?.items ?? []) {
      keys.add(item.node.key);
      keys.add(item.node.clientKey);
      if (item.node.subjectId) {
        keys.add(item.node.subjectId);
      }
    }
    return keys;
  });

  /** Chantiers et personnes demandés, répartis sur l'anneau des chantiers (au premier plan, même sur téléphone). */
  readonly shown = computed<LitMark[]>(() => {
    const items = (this.focus()?.items ?? []).filter((i) => i.node.kind === 'chantier' || i.node.kind === 'person');
    return items.map((item, i) => {
      const at = polar(CHANTIER_RING, -90 + (360 * (i + 0.5)) / items.length);
      const label = item.node.kind === 'chantier' ? shortChantier(item.node.label) : item.node.label;
      return { key: item.node.key, kind: item.node.kind, ...at, label, anchor: anchorFor(at.x), own: true };
    });
  });

  readonly hitRadius = computed(() => {
    const ring = this.layout().subjects.filter((s) => s.ring === 'current').length;
    const spacing = ring > 0 ? (2 * Math.PI * SUBJECT_RING) / ring : 24;
    return Math.max(4, Math.min(12, spacing / 2));
  });

  /** Le fil d'Ariane : client › sujet › chantier › étape — la dernière chose citée, sinon le sujet courant. */
  readonly crumbs = computed<string[]>(() => {
    const focus = this.focus();
    if (focus) {
      return [focus.title]; // SF-234-13 : le fil d'Ariane dit ce qui est montré (équivalent accessible)
    }
    return breadcrumb(this.bus.universe(), this.bus.state().lit.map((l) => l.node), this.subjectId());
  });

  /** Ce que le lecteur d'écran annonce (limité, D10). */
  readonly announced = signal('');
  private lastAnnounce = 0;

  constructor() {
    effect(() => {
      const text = this.crumbs().join(', ');
      const speaking = this.speaking();
      untracked(() => {
        const now = Date.now();
        if (!speaking && text !== this.announced() && now - this.lastAnnounce >= ANNOUNCE_EVERY_MS) {
          this.lastAnnounce = now;
          this.announced.set(text);
        }
      });
    });
  }

  ngOnInit(): void {
    this.bus.load();
    if (typeof window !== 'undefined' && window.matchMedia) {
      const query = window.matchMedia(NARROW_QUERY);
      const onChange = () => this.narrow.set(query.matches);
      query.addEventListener?.('change', onChange);
      this.destroyRef.onDestroy(() => query.removeEventListener?.('change', onChange));
    }
  }

  isLit(key: string): boolean {
    return this.litKeys().has(key);
  }

  isShown(key: string): boolean {
    return this.shownKeys().has(key);
  }

  /** Le halo d'un poste montré : `on` connecté, `off` déconnecté, `null` sinon (ou « Hébergé »). */
  halo(key: string): 'on' | 'off' | null {
    const item = this.focus()?.items.find((i) => i.node.kind === 'client' && i.node.key === key);
    if (!item || item.connected === null) {
      return null;
    }
    return item.connected ? 'on' : 'off';
  }

  anchor(x: number): 'start' | 'middle' | 'end' {
    return anchorFor(x);
  }

  labelX(s: PlacedSubject): number {
    const anchor = anchorFor(s.x);
    return s.x + (anchor === 'start' ? 4.5 : anchor === 'end' ? -4.5 : 0);
  }

  pick(s: PlacedSubject): void {
    this.choose.emit(s.id);
  }

  private placeLit(universe: JarvisUniverse | null, layout: ConstellationLayout): LitMark[] {
    const marks: LitMark[] = [];
    for (const { node } of this.bus.state().lit) {
      const subject = node.subjectId ?? (node.kind === 'subject' ? node.key : null);
      const placedSubject = subject ? layout.subjects.find((s) => s.id === subject) : undefined;
      const client = layout.clients.find((c) => c.key === node.clientKey);
      if (node.kind === 'client' && client) {
        marks.push({ key: node.key, kind: node.kind, x: client.x, y: client.y, label: node.label,
          anchor: 'middle', own: false });
      } else if (node.kind === 'subject') {
        if (placedSubject) {
          marks.push({ key: node.key, kind: node.kind, x: placedSubject.x, y: placedSubject.y, label: node.label,
            anchor: anchorFor(placedSubject.x), own: false });
        } else if (client) {
          // Téléphone : le sujet d'un client replié s'écrit près de son client.
          const at = polar(CHANTIER_RING, client.angle + 8);
          marks.push({ key: node.key, kind: node.kind, ...at, label: node.label, anchor: anchorFor(at.x), own: true });
        }
      } else if (node.kind === 'chantier') {
        const angle = placedSubject?.angle ?? (client ? client.angle + 8 : -90);
        const at = polar(CHANTIER_RING, angle);
        marks.push({ key: node.key, kind: node.kind, ...at, label: chantierLabel(universe, node), anchor: anchorFor(at.x),
          own: true });
      } else if (node.kind === 'person' && client) {
        const at = polar(CLIENT_RING - 12, client.angle - 10);
        marks.push({ key: node.key, kind: node.kind, ...at, label: node.label, anchor: anchorFor(at.x), own: true });
      }
    }
    return marks;
  }
}

/** « Chantier 3 — Bascule DNS » → « Ch. 3 — Bascule DNS », au plus 30 caractères. */
function shortChantier(label: string): string {
  const short = label.replace(/^Chantier\s+/, 'Ch. ');
  return short.length > 30 ? `${short.slice(0, 29)}…` : short;
}

/** « Chantier 3 — Bascule DNS » (le libellé du nœud), raccourci pour la carte. */
function chantierLabel(universe: JarvisUniverse | null, node: NodeRef): string {
  const found = findChantier(universe, node);
  if (!found) {
    return node.label;
  }
  const title = found.chantier.title.length > 28 ? `${found.chantier.title.slice(0, 27)}…` : found.chantier.title;
  return `Ch. ${found.chantier.number} — ${title}`;
}

function findChantier(universe: JarvisUniverse | null, node: NodeRef):
    { client: string; subject: string; chantier: UniverseChantier } | null {
  for (const client of universe?.clients ?? []) {
    for (const subject of client.subjects) {
      if (subject.id !== node.subjectId) {
        continue;
      }
      const number = Number(node.key.split('#')[1]);
      const chantier = subject.chantiers.find((c) => c.number === number);
      return chantier ? { client: client.name, subject: subject.name, chantier } : null;
    }
  }
  return null;
}

/**
 * Le fil d'Ariane (D7, D10) : le dernier chantier, sujet ou client cité, remonté jusqu'à son client ; sinon le sujet
 * courant. Le chantier ajoute son étape courante (« Étape 2 — Valider le TTL »).
 */
export function breadcrumb(universe: JarvisUniverse | null, lit: NodeRef[], subjectId: string | null): string[] {
  if (!universe) {
    return [];
  }
  const recent = [...lit].reverse();
  const target = recent.find((n) => n.kind === 'chantier') ?? recent.find((n) => n.kind === 'subject')
    ?? recent.find((n) => n.kind === 'client');
  if (target?.kind === 'chantier') {
    const found = findChantier(universe, target);
    if (found) {
      const crumbs = [found.client, found.subject, `Chantier ${found.chantier.number}`];
      if (found.chantier.step) {
        crumbs.push(`Étape ${found.chantier.step.rank} — ${found.chantier.step.title}`);
      }
      return crumbs;
    }
  }
  const subject = target?.kind === 'subject' ? target.key : target?.kind === 'client' ? null : subjectId;
  if (target?.kind === 'client') {
    return [target.label];
  }
  for (const client of universe.clients) {
    const s = client.subjects.find((x) => x.id === subject);
    if (s) {
      return [client.name, s.name];
    }
  }
  return [];
}

function matches(query: string): boolean {
  try {
    return typeof window !== 'undefined' && !!window.matchMedia?.(query).matches;
  } catch {
    return false;
  }
}

