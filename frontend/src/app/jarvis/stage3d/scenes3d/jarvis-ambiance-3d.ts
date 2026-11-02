import { radarEchoes } from './jarvis-radar';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { JarvisUniverse, clientKey } from '../../scenes/jarvis-universe.models';
import { CORE_KEY } from '../jarvis-link-graph';
import { safeLine } from './jarvis-scenes-poste';
import { Effect3d, Panel3d, SceneTone } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-13 — **ambiance et alertes** (cadrage §9.2 A1-A5, §11 N12 / N13, §8.2 L-veille). Fichier <b>pur</b>.
 *
 * <ul>
 *   <li><b>A1 Poste muet</b> : client <b>rouge</b>, halo lent, lien qui grésille, « poste muet » ;</li>
 *   <li><b>A2 Terminaux vivants</b> : lunes qui respirent, impulsions vers leur client ;</li>
 *   <li><b>A3 Cloche</b> : le sujet appelle (teinte d'attente) et lance une onde vers le cœur ;</li>
 *   <li><b>A4 / N12 Coût</b> : compteur en € en fin de tour, 5 s — <b>administrateur seulement</b> ;</li>
 *   <li><b>A5 La veille sonne</b> : zoom sur le client, onde client → cœur, cadran relié au client (L-veille), panneau
 *   « Veille … » — <b>rouge</b> (alerte).</li>
 * </ul>
 * <b>N13</b> : le rouge (`alert`) n'est utilisé <b>que</b> par A1 et A5 — nulle part ailleurs (test sur le plan).
 * L'ambiance ne se montre qu'<b>au repos</b> (aucun tour, aucune scène), sauf A5 (D14).
 */

/** A4 — le coût reste affiché 5 s. */
export const COST_VISIBLE_MS = 5000;

export interface AmbientPlan3d {
  /** Teintes imposées aux nœuds (rouge du poste muet, attente de la cloche, rouge de la veille qui sonne). */
  tones: Readonly<Record<string, SceneTone>>;
  /** A2 — les sujets dont un terminal travaille : ils respirent. */
  breathe: readonly string[];
  /** A1 / A5 — ce qui grésille (halo et lien de l'alerte). */
  flicker: readonly string[];
  panels: Panel3d[];
  effects: Effect3d[];
  /** A5 — le client à cadrer. */
  focus: string | null;
}

export interface AmbientContext {
  universe: JarvisUniverse | null;
  /** Les nœuds posés dans l'univers 3D. */
  nodes: ReadonlySet<string>;
  /** Un tour tourne ou une scène est à l'écran : seule A5 se montre. */
  busy: boolean;
  /** A4 — l'utilisateur est administrateur (le serveur ne livre d'ailleurs le coût qu'à lui). */
  admin: boolean;
  /** A4 — le coût du dernier tour est encore dans ses 5 s d'affichage. */
  costFresh: boolean;
  /** A3 — compteur d'appels (une onde par appel). */
  seq: number;
}

export function directAmbient(ambient: Ambient | null, ctx: AmbientContext): AmbientPlan3d | null {
  if (!ambient) {
    return null;
  }
  const tones: Record<string, SceneTone> = {};
  const breathe: string[] = [];
  const flicker: string[] = [];
  const panels: Panel3d[] = [];
  const effects: Effect3d[] = [];
  let focus: string | null = null;
  const posed = (key: string | null | undefined): key is string => !!key && ctx.nodes.has(key);

  // A5 — la veille qui sonne : seule ambiance montrée même pendant un tour.
  if (ambient.alert) {
    const client = ambient.alert.clientKey;
    const anchor = posed(client) ? client : CORE_KEY;
    if (posed(client)) {
      tones[client] = 'alert';
      flicker.push(client);
      focus = client;
      effects.push({ kind: 'flow', from: client, to: CORE_KEY, tone: 'alert', rate: 0.6 });
      // L-veille : le cadran de la veille, relié à son client.
      effects.push({ kind: 'dial', anchor: client, tone: 'alert' });
    }
    panels.push({ id: 'ambient:alert', anchor, tone: 'alert', kind: 'alert', title: 'La veille sonne',
      lines: [{ text: safeLine(ambient.alert.label, 48) }], front: true });
  }
  if (!ctx.busy) {
    // A1 — le poste muet.
    if (ambient.hostDown && posed(ambient.hostDown.clientKey)) {
      const key = ambient.hostDown.clientKey;
      tones[key] = 'alert';
      flicker.push(key);
      panels.push({ id: 'ambient:down', anchor: key, tone: 'alert', kind: 'alert', title: 'Poste muet',
        lines: [{ text: safeLine(ambient.hostDown.name, 40) }] });
    }
    // A2 — les terminaux vivants respirent, une impulsion part vers leur client.
    for (const id of ambient.liveSubjects) {
      if (posed(id)) {
        breathe.push(id);
      }
    }
    const first = breathe[0];
    const owner = first ? clientOf(ctx.universe, first) : null;
    if (first && posed(owner) && !ambient.alert) {
      effects.push({ kind: 'flow', from: first, to: owner, tone: 'normal', rate: 0.25 });
    }
    // A3 — la cloche : le sujet appelle une fois, une onde part vers le cœur.
    if (ambient.calling && posed(ambient.calling.subjectId)) {
      const key = ambient.calling.subjectId;
      tones[key] = 'wait';
      effects.push({ kind: 'pulse', anchor: key, tone: 'wait', seq: ctx.seq });
      panels.push({ id: 'ambient:calling', anchor: key, tone: 'wait', kind: 'label', title: 'Un sujet vous appelle',
        lines: [{ text: safeLine(ambient.calling.label, 40) }] });
    }
    // A4 / N12 — le coût, administrateur seulement, 5 s en fin de tour.
    if (ctx.admin && ctx.costFresh && ambient.cost) {
      panels.push({ id: 'ambient:cost', anchor: CORE_KEY, tone: 'normal', kind: 'cost', title: 'Coût du tour',
        lines: [{ text: safeLine(ambient.cost, 24), hot: true }], mono: true });
    }
  }
  // SF-236-25 (NF-15) — le radar : un écho par alerte réelle, à l'azimut de son client. Comme l'ambiance, au repos
  // seulement — sauf l'écho de la veille qui sonne (A5), montré même pendant un tour.
  const echoes = radarEchoes(ambient, ctx.universe, ctx.nodes).filter((e) => !ctx.busy || e.kind === 'veille');
  if (echoes.length > 0) {
    effects.push({ kind: 'radar', echoes: echoes.map((e) => ({ anchor: e.anchor, tone: e.tone })) });
  }
  const empty = !panels.length && !effects.length && !breathe.length && !Object.keys(tones).length;
  return empty ? null : { tones, breathe, flicker, panels, effects, focus };
}

function clientOf(universe: JarvisUniverse | null, subjectId: string): string | null {
  const client = universe?.clients.find((c) => c.subjects.some((s) => s.id === subjectId));
  return client ? clientKey(client) : null;
}
