import {
  AdditiveBlending, BoxGeometry, BufferAttribute, BufferGeometry, Camera, Color, Group, InstancedMesh,
  InterleavedBufferAttribute, Matrix4, MeshBasicMaterial, OctahedronGeometry, Points, Scene, ShaderMaterial,
  SphereGeometry, Vector3,
} from 'three';
import { LineMaterial } from 'three/examples/jsm/lines/LineMaterial.js';
import { LineSegments2 } from 'three/examples/jsm/lines/LineSegments2.js';
import { LineSegmentsGeometry } from 'three/examples/jsm/lines/LineSegmentsGeometry.js';
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js';

import { Layout3d, Node3d, Node3dKind, UniverseTouch } from '../jarvis-layout-3d';
import { CORE_KEY, Graph3d, LinkState, stateAlpha } from '../jarvis-link-graph';
import { HOLO_COLORS, HoloRole, rgb } from '../jarvis-motion';
import { SHOW_STAGGER_MS, ShowPlan3d } from '../scenes3d/jarvis-show-3d';
import { AmbientPlan3d } from '../scenes3d/jarvis-ambiance-3d';
import { QualityLevel } from '../jarvis-render-capability';

/**
 * F-236 / SF-236-04 — **l'univers 3D et ses liaisons** (cadrage §8) : un maillage instancié par forme (client =
 * sphère, sujet = lune, chantier = losange, étape = perle, personne = carré), des filaments à largeur en pixels
 * (`LineSegments2`) en dégradé source → cible, des halos, et des <b>étiquettes DOM</b> (CSS2D : nettes, accessibles,
 * vrais boutons). Positions, opacités et couleurs sont <b>interpolées</b> (relecture de l'univers sans saut) ; les
 * tampons sont réécrits sur place : zéro allocation par image.
 */

/** Bornes d'étiquettes (T5) : PC 48, téléphone 16. */
export const LABEL_BOUND: Record<QualityLevel, number> = { high: 48, medium: 16, low: 16 };

const SIZE: Record<Node3dKind, number> = { client: 0.22, subject: 0.11, chantier: 0.12, step: 0.045, person: 0.09 };
const HALO: Record<Node3dKind, number> = { client: 60, subject: 30, chantier: 30, step: 10, person: 24 };
/** Constantes de temps : montée (T-L1, 0,5 s) plus vive que la retombée (T-L2, 0,9 s), déplacement (relecture). */
const TAU_UP = 160;
const TAU_DOWN = 300;
const TAU_MOVE = 350;

const HALO_VERTEX = /* glsl */ `
attribute float aSize;
attribute vec3 aColor;
uniform float uPixelRatio;
varying vec3 vColor;
void main() {
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = min(aSize * uPixelRatio * (8.0 / max(-mv.z, 0.5)), 120.0 * uPixelRatio);
  vColor = aColor;
}`;

const HALO_FRAGMENT = /* glsl */ `
varying vec3 vColor;
void main() {
  float d = length(gl_PointCoord - 0.5);
  float a = 1.0 - smoothstep(0.0, 0.5, d);
  gl_FragColor = vec4(vColor * a * a, 1.0);
}`;

interface Live {
  node: Node3d;
  /** Position affichée (interpolée vers celle de la disposition). */
  x: number;
  y: number;
  z: number;
  /** Opacité affichée et visée. */
  alpha: number;
  target: number;
  /** Opacité dessinée (état × entrée). */
  shown: number;
  role: HoloRole;
  label: CSS2DObject | null;
  /** Rang du client du nœud (cascade de l'entrée T-E1). */
  clientIndex: number;
  /** SF-236-11 (T-M) — attente avant de s'allumer (ms), pour la cascade de « Montrer ». */
  delay: number;
}

/** Un nœud projeté à l'écran (sélection en espace écran). */
export interface Pickable {
  key: string;
  kind: Node3dKind;
  subjectId?: string;
  sx: number;
  sy: number;
  visible: boolean;
  x: number;
  y: number;
  z: number;
}

export class Universe3d {
  readonly group = new Group();
  private readonly labels: CSS2DRenderer | null;
  private readonly meshes = new Map<Node3dKind, InstancedMesh>();
  private live: Live[] = [];
  private byKey = new Map<string, Live>();
  private lines: LineSegments2 | null = null;
  private lineMaterial: LineMaterial;
  private linkEnds: { key: string; from: Live | null; to: Live; alpha: number; target: number; role: HoloRole }[] = [];
  private readonly counters: Record<Node3dKind, number> = { client: 0, subject: 0, chantier: 0, step: 0, person: 0 };
  private halos: Points | null = null;
  private readonly haloMaterial: ShaderMaterial;
  private readonly matrix = new Matrix4();
  private readonly color = new Color();
  private readonly palette = new Map<HoloRole, number[]>();
  private signature = '';
  /** SF-236-11 — teintes imposées par « Montrer » (vert connecté, éteint, attente…). */
  private tones = new Map<string, HoloRole>();
  private showSignature = '';
  private ambientSignature = '';
  /** SF-236-13 — A2 : ce qui respire ; A1 / A5 : ce qui grésille ; l'horloge de l'univers (ms). */
  private breathe = new Set<string>();
  private flicker = new Set<string>();
  private clock = 0;
  private lastGraph: Graph3d | null = null;
  /** SF-236-05 — l'entrée en cascade : avancement d'un client selon son rang (1 = posé). */
  reveal: (clientIndex: number) => number = () => 1;
  private readonly projected = new Vector3();

  constructor(scene: Scene, private readonly labelHost: HTMLElement | null, private readonly level: QualityLevel,
      pixelRatio: number, private readonly onTouch: (touch: UniverseTouch) => void) {
    scene.add(this.group);
    this.lineMaterial = new LineMaterial({
      linewidth: level === 'high' ? 1.6 : 2, vertexColors: true, transparent: true, depthWrite: false,
      blending: AdditiveBlending, worldUnits: false,
    });
    this.haloMaterial = new ShaderMaterial({
      vertexShader: HALO_VERTEX, fragmentShader: HALO_FRAGMENT, transparent: true, depthWrite: false,
      blending: AdditiveBlending, uniforms: { uPixelRatio: { value: pixelRatio } },
    });
    if (labelHost) {
      this.labels = new CSS2DRenderer({ element: labelHost });
    } else {
      this.labels = null;
    }
    for (const role of Object.keys(HOLO_COLORS) as HoloRole[]) {
      this.palette.set(role, rgb(HOLO_COLORS[role]));
    }
  }

  /**
   * SF-236-11 — « Montrer » : chaque élément montré prend sa teinte et s'allume en cascade (50 ms d'écart, T-M) ;
   * la fin de « Montrer » rend leurs teintes d'origine. SF-236-13 — l'ambiance : teintes des alertes (rouge, qui
   * prime), lunes qui respirent (A2), halos et liens qui grésillent (A1 / A5).
   */
  setShow(show: ShowPlan3d | null, ambient: AmbientPlan3d | null = null): void {
    const showSignature = show ? `${show.filter}|${show.keys.join(',')}` : '';
    const ambientSignature = ambient ? JSON.stringify([ambient.tones, ambient.breathe, ambient.flicker]) : '';
    if (showSignature === this.showSignature && ambientSignature === this.ambientSignature) {
      return;
    }
    const showChanged = showSignature !== this.showSignature;
    this.showSignature = showSignature;
    this.ambientSignature = ambientSignature;
    this.tones = new Map([...Object.entries(show?.tones ?? {}), ...Object.entries(ambient?.tones ?? {})] as
      [string, HoloRole][]);
    this.breathe = new Set(ambient?.breathe ?? []);
    this.flicker = new Set(ambient?.flicker ?? []);
    for (const l of this.live) {
      l.role = this.tones.get(l.node.key) ?? nodeRole(l.node);
      if (showChanged) {
        const index = show ? show.keys.indexOf(l.node.key) : -1;
        l.delay = index >= 0 ? index * SHOW_STAGGER_MS : 0;
      }
    }
    if (this.lastGraph) {
      const links = new Map(this.lastGraph.links.map((k) => [k.key, k]));
      for (const end of this.linkEnds) {
        const link = links.get(end.key);
        if (link) {
          end.role = this.tones.get(link.to) ?? link.role;
        }
      }
    }
  }

  /** Une nouvelle disposition ou de nouveaux états : reconstruit seulement si l'ensemble des nœuds change. */
  setView(layout: Layout3d, graph: Graph3d, instant: boolean): void {
    this.lastGraph = graph;
    const signature = layout.nodes.map((n) => `${n.kind}:${n.key}`).join('|') + '#'
      + graph.links.map((l) => l.key).join('|');
    if (signature !== this.signature) {
      this.rebuild(layout, graph, instant);
      this.signature = signature;
    }
    for (const n of layout.nodes) {
      const live = this.byKey.get(n.key);
      if (live) {
        live.node = n;
        live.target = nodeAlpha(n, graph.nodes.get(n.key));
        live.role = this.tones.get(n.key) ?? nodeRole(n);
        if (instant) {
          live.x = n.x;
          live.y = n.y;
          live.z = n.z;
          live.alpha = live.target;
        }
        this.styleLabel(live, graph.nodes.get(n.key));
      }
    }
    const links = new Map(graph.links.map((l) => [l.key, l]));
    for (const end of this.linkEnds) {
      const link = links.get(end.key);
      if (!link) {
        continue;
      }
      end.target = stateAlpha(link.state);
      end.role = this.tones.get(link.to) ?? link.role;
      if (instant) {
        end.alpha = end.target;
      }
    }
    this.boundLabels(graph);
  }

  setSize(width: number, height: number): void {
    this.lineMaterial.resolution.set(width, height);
    this.labels?.setSize(width, height);
  }

  /** Une image : positions et opacités vers leurs cibles, tampons réécrits sur place. */
  frame(dt: number): void {
    this.clock += dt;
    const kUp = 1 - Math.exp(-dt / TAU_UP);
    const kDown = 1 - Math.exp(-dt / TAU_DOWN);
    const kMove = 1 - Math.exp(-dt / TAU_MOVE);
    for (const l of this.live) {
      l.x += (l.node.x - l.x) * kMove;
      l.y += (l.node.y - l.y) * kMove;
      l.z += (l.node.z - l.z) * kMove;
      if (l.delay > 0 && l.target > l.alpha) {
        // T-M — l'élément montré attend son tour dans la cascade.
        l.delay -= dt;
      } else {
        l.alpha += (l.target - l.alpha) * (l.target > l.alpha ? kUp : kDown);
      }
      l.shown = l.alpha * this.reveal(l.clientIndex);
    }
    for (const e of this.linkEnds) {
      e.alpha += (e.target - e.alpha) * (e.target > e.alpha ? kUp : kDown);
    }
    this.writeMeshes();
    this.writeLinks();
    this.writeHalos();
  }

  /** Dessine les étiquettes (après le rendu WebGL). */
  renderLabels(scene: Scene, camera: import('three').Camera): void {
    this.labels?.render(scene, camera);
  }

  dispose(): void {
    for (const l of this.live) {
      l.label?.element.remove();
      l.label?.removeFromParent();
    }
    this.live = [];
    this.byKey.clear();
    if (this.labelHost) {
      this.labelHost.replaceChildren();
    }
  }

  private rebuild(layout: Layout3d, graph: Graph3d, instant: boolean): void {
    const previous = this.byKey;
    for (const l of this.live) {
      if (!layout.nodes.some((n) => n.key === l.node.key)) {
        l.label?.element.remove();
        l.label?.removeFromParent();
      }
    }
    this.live = layout.nodes.map((n) => {
      const before = previous.get(n.key);
      if (before) {
        return before;
      }
      // Un nœud nouveau naît à la place de son parent (le cœur pour un client), puis glisse à sa place.
      const parent = previous.get(n.kind === 'subject' ? n.clientKey : n.subjectId ?? n.clientKey);
      const live: Live = {
        node: n, x: instant || !parent ? n.x : parent.x, y: instant || !parent ? n.y : parent.y,
        z: instant || !parent ? n.z : parent.z, alpha: instant ? nodeAlpha(n, graph.nodes.get(n.key)) : 0, target: 0,
        shown: 0, role: this.tones.get(n.key) ?? nodeRole(n), label: null, clientIndex: 0, delay: 0,
      };
      live.label = this.makeLabel(live);
      return live;
    });
    this.byKey = new Map(this.live.map((l) => [l.node.key, l]));
    const clientOrder = layout.nodes.filter((n) => n.kind === 'client').map((n) => n.key);
    for (const l of this.live) {
      l.clientIndex = Math.max(0, clientOrder.indexOf(l.node.clientKey));
    }

    for (const mesh of this.meshes.values()) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
      (mesh.material as MeshBasicMaterial).dispose();
    }
    this.meshes.clear();
    const kinds: Node3dKind[] = ['client', 'subject', 'chantier', 'step', 'person'];
    for (const kind of kinds) {
      const count = this.live.filter((l) => l.node.kind === kind).length;
      if (count === 0) {
        continue;
      }
      const geometry = kind === 'chantier' ? new OctahedronGeometry(1, 0) : kind === 'person'
        ? new BoxGeometry(1.4, 1.4, 1.4) : new SphereGeometry(1, kind === 'client' ? 24 : 12, kind === 'client' ? 16 : 8);
      const material = new MeshBasicMaterial({ transparent: true, depthWrite: false, blending: AdditiveBlending });
      const mesh = new InstancedMesh(geometry, material, count);
      mesh.frustumCulled = false;
      this.meshes.set(kind, mesh);
      this.group.add(mesh);
    }

    // Liaisons : un segment par liaison (dégradé source → cible).
    if (this.lines) {
      this.lines.removeFromParent();
      this.lines.geometry.dispose();
      this.lines = null;
    }
    this.linkEnds = graph.links.map((link) => ({
      key: link.key,
      from: link.from === CORE_KEY ? null : this.byKey.get(link.from) ?? null,
      to: this.byKey.get(link.to) as Live,
      alpha: instant ? stateAlpha(link.state) : 0,
      target: stateAlpha(link.state),
      role: link.role,
    })).filter((e) => !!e.to);
    if (this.linkEnds.length > 0) {
      const geometry = new LineSegmentsGeometry();
      geometry.setPositions(new Float32Array(this.linkEnds.length * 6));
      geometry.setColors(new Float32Array(this.linkEnds.length * 6));
      this.lines = new LineSegments2(geometry, this.lineMaterial);
      this.lines.frustumCulled = false;
      this.group.add(this.lines);
    }

    if (this.halos) {
      this.halos.removeFromParent();
      this.halos.geometry.dispose();
    }
    const halo = new BufferGeometry();
    halo.setAttribute('position', new BufferAttribute(new Float32Array(this.live.length * 3), 3));
    halo.setAttribute('aColor', new BufferAttribute(new Float32Array(this.live.length * 3), 3));
    halo.setAttribute('aSize', new BufferAttribute(Float32Array.from(this.live.map((l) => HALO[l.node.kind])), 1));
    this.halos = new Points(halo, this.haloMaterial);
    this.halos.frustumCulled = false;
    this.group.add(this.halos);
  }

  private writeMeshes(): void {
    const index = this.counters;
    index.client = 0;
    index.subject = 0;
    index.chantier = 0;
    index.step = 0;
    index.person = 0;
    for (const l of this.live) {
      const mesh = this.meshes.get(l.node.kind);
      if (!mesh) {
        continue;
      }
      const i = index[l.node.kind]++;
      const s = SIZE[l.node.kind] * (0.75 + 0.25 * l.alpha) * Math.min(1, this.reveal(l.clientIndex) * 1.5);
      this.matrix.makeScale(s, s, s);
      this.matrix.setPosition(l.x, l.y, l.z);
      mesh.setMatrixAt(i, this.matrix);
      const c = this.palette.get(l.role) as number[];
      const k = (0.2 + 0.6 * l.alpha) * 0.55 * this.reveal(l.clientIndex);
      const filled = l.node.kind !== 'step' || l.node.done ? 1 : 0.35;
      this.color.setRGB(c[0] * k * filled, c[1] * k * filled, c[2] * k * filled);
      mesh.setColorAt(i, this.color);
    }
    for (const mesh of this.meshes.values()) {
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) {
        mesh.instanceColor.needsUpdate = true;
      }
    }
  }

  private writeLinks(): void {
    if (!this.lines) {
      return;
    }
    const geometry = this.lines.geometry;
    const pos = (geometry.attributes['instanceStart'] as InterleavedBufferAttribute).data;
    const col = (geometry.attributes['instanceColorStart'] as InterleavedBufferAttribute).data;
    const p = pos.array as Float32Array;
    const c = col.array as Float32Array;
    for (let i = 0; i < this.linkEnds.length; i++) {
      const e = this.linkEnds[i];
      const from = e.from;
      p[i * 6] = from ? from.x : 0;
      p[i * 6 + 1] = from ? from.y : 0;
      p[i * 6 + 2] = from ? from.z : 0;
      p[i * 6 + 3] = e.to.x;
      p[i * 6 + 4] = e.to.y;
      p[i * 6 + 5] = e.to.z;
      // Dégradé : la source garde la teinte de la liaison, la cible celle de son nœud ; additif ⇒ couleur × opacité.
      const src = this.palette.get(e.role) as number[];
      const dst = this.palette.get(e.to.role) as number[];
      // A1 / A5 — le lien de l'alerte grésille.
      const a = e.alpha * this.reveal(e.to.clientIndex) * (this.flicker.has(e.to.node.key) ? this.crackle(i) : 1);
      for (let k = 0; k < 3; k++) {
        c[i * 6 + k] = src[k] * a;
        c[i * 6 + 3 + k] = dst[k] * a;
      }
    }
    pos.needsUpdate = true;
    col.needsUpdate = true;
  }

  private writeHalos(): void {
    if (!this.halos) {
      return;
    }
    const pos = this.halos.geometry.getAttribute('position') as BufferAttribute;
    const col = this.halos.geometry.getAttribute('aColor') as BufferAttribute;
    const p = pos.array as Float32Array;
    const c = col.array as Float32Array;
    for (let i = 0; i < this.live.length; i++) {
      const l = this.live[i];
      p[i * 3] = l.x;
      p[i * 3 + 1] = l.y;
      p[i * 3 + 2] = l.z;
      const rgbRole = this.palette.get(l.role) as number[];
      // Halo indexé sur l'état (§7.1) : latent 0,2 · contexte 0,45 · allumé 1.
      // A2 — une lune vivante respire (4 s) ; A1 / A5 — l'alerte : halo lent qui grésille.
      const live = this.breathe.has(l.node.key) ? 0.8 + 0.35 * Math.sin((this.clock / 4000) * Math.PI * 2) : 1;
      const alert = this.flicker.has(l.node.key) ? 1.3 * (0.75 + 0.25 * Math.sin((this.clock / 2600) * Math.PI * 2))
        * this.crackle(i) : 1;
      const k = (0.2 + 0.8 * l.alpha) * this.reveal(l.clientIndex) * live * alert;
      for (let j = 0; j < 3; j++) {
        c[i * 3 + j] = rgbRole[j] * k * 0.3;
      }
    }
    pos.needsUpdate = true;
    col.needsUpdate = true;
  }

  private makeLabel(live: Live): CSS2DObject | null {
    if (!this.labels || !live.node.label) {
      return null;
    }
    const element = document.createElement(live.node.kind === 'subject' ? 'button' : 'span');
    element.className = `jv3d-label jv3d-label--${live.node.kind}`;
    element.textContent = live.node.label;
    element.dataset['key'] = live.node.key;
    if (element instanceof HTMLButtonElement) {
      element.type = 'button';
      element.setAttribute('aria-label', `Passer sur ${live.node.label}`);
      element.addEventListener('click', () => this.onTouch({ key: live.node.key, kind: live.node.kind,
        subjectId: live.node.subjectId }));
    }
    const object = new CSS2DObject(element);
    // SF-236-07 — l'étiquette se pose sous le nœud et son halo, jamais dessus.
    object.center.set(0.5, live.node.kind === 'client' ? -3.2 : -1.4);
    object.position.set(0, 0, 0);
    this.group.add(object);
    return object;
  }

  private styleLabel(live: Live, state: LinkState | undefined): void {
    const label = live.label;
    if (!label) {
      return;
    }
    label.position.set(live.node.x, live.node.y, live.node.z);
    label.element.dataset['state'] = state ?? 'latent';
  }

  /** Borne des étiquettes (T5) : allumés, puis actifs, puis clients, puis contexte ; le reste est masqué. */
  private boundLabels(graph: Graph3d): void {
    const rank = (l: Live): number => {
      const s = graph.nodes.get(l.node.key) ?? 'latent';
      const base = s === 'lit' ? 0 : s === 'active' ? 1 : l.node.kind === 'client' ? 2 : s === 'context' ? 3 : 4;
      return base * 10 + (l.node.kind === 'client' ? 0 : l.node.kind === 'subject' ? 1 : 2);
    };
    const ordered = this.live.filter((l) => l.label).sort((a, b) => rank(a) - rank(b));
    const bound = LABEL_BOUND[this.level];
    ordered.forEach((l, i) => {
      const visible = i < bound && rank(l) < 40;
      (l.label as CSS2DObject).visible = visible;
    });
  }

  /** SF-236-05 — les nœuds projetés à l'écran (au toucher seulement : allocation hors de la boucle d'images). */
  pickables(camera: Camera, width: number, height: number): Pickable[] {
    this.group.updateMatrixWorld();
    return this.live.map((l) => {
      this.projected.set(l.x, l.y, l.z);
      this.group.localToWorld(this.projected);
      const world = this.projected.clone();
      this.projected.project(camera);
      return {
        key: l.node.key, kind: l.node.kind, subjectId: l.node.subjectId,
        sx: (this.projected.x + 1) * 0.5 * width, sy: (1 - this.projected.y) * 0.5 * height,
        visible: this.projected.z < 1 && l.shown > 0.1, x: world.x, y: world.y, z: world.z,
      };
    });
  }

  /** SF-236-08 — où est un nœud, en coordonnées du monde (ancrage des panneaux et des effets) ; sans allocation. */
  locate(key: string, out: Vector3): boolean {
    const live = this.byKey.get(key);
    if (!live) {
      return false;
    }
    out.set(live.x, live.y, live.z);
    this.group.localToWorld(out);
    return true;
  }

  /** Grésillement : un bruit rapide déterministe (somme de sinus), sans allocation. */
  private crackle(seed: number): number {
    const t = this.clock / 1000;
    const n = Math.sin(t * 37 + seed) * Math.sin(t * 23.3 + seed * 1.7) * Math.sin(t * 61.1);
    return 0.7 + 0.3 * (n > 0.15 ? 1 : n < -0.15 ? 0 : 0.5);
  }

  /** Les étiquettes visibles (tests au banc). */
  get visibleLabels(): number {
    return this.live.filter((l) => l.label?.visible).length;
  }

  /** Appels de dessin de l'univers (borne §6.1). */
  get drawCalls(): number {
    return this.meshes.size + (this.lines ? 1 : 0) + (this.halos ? 1 : 0);
  }
}

/** L'opacité d'un nœud selon son état (latent reste visible : la structure se devine). */
export function nodeAlpha(node: Node3d, state: LinkState | undefined): number {
  const s = state ?? 'latent';
  const base = s === 'lit' ? 1 : s === 'active' ? 0.75 : s === 'context' ? 0.45 : 0.2;
  return node.kind === 'client' ? Math.max(base, 0.45) : base;
}

/** La teinte d'un nœud (grammaire §23) : poste muet éteint, personne attendue en couleur d'attente. */
export function nodeRole(node: Node3d): HoloRole {
  if (node.kind === 'client' && node.connected === false) {
    return 'off';
  }
  if (node.kind === 'person' && node.awaited) {
    return 'wait';
  }
  return 'normal';
}
