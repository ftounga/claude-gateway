import { ShowFilter, ShowFocus } from '../../scenes/jarvis-show';
import { CORE_KEY } from '../jarvis-link-graph';
import { safeLine } from './jarvis-scenes-poste';
import { Panel3d, SceneTone } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-11 — **« Montrer » en 3D** (cadrage §9.2 S20, T-M). Fichier <b>pur</b>.
 *
 * <p>La demande « Montrer » est <b>déjà résolue</b> sur l'univers par F-234 (`resolveShow`, filtres fermés, ≤ 8
 * éléments) : la 3D en est la traduction exacte — mêmes éléments, rien de plus, rien de moins. Un filtre inconnu
 * n'arrive jamais ici (`resolveShow` rend `null`) : rien n'est montré.</p>
 *
 * <ul>
 *   <li>`connectes` : postes en <b>vert</b> ; `deconnectes` : postes <b>éteints</b> ;</li>
 *   <li>`chantiers-bloques` : losanges en teinte d'<b>attente</b> + la personne attendue ;</li>
 *   <li>`etapes-en-cours` : chantiers déployés (chapelet) + l'étape en panneau ;</li>
 *   <li>`a-envoyer` : les enveloppes des destinataires, autour du compteur ;</li>
 *   <li>`clients` : tout l'univers déployé.</li>
 * </ul>
 * Le reste passe en latent (graphe F-236 / SF-236-04) ; la caméra dézoome, puis les éléments s'allument avec 50 ms
 * d'écart (T-M).
 */

/** T-M — écart entre deux éléments montrés (ms). */
export const SHOW_STAGGER_MS = 50;

export interface ShowPlan3d {
  filter: ShowFilter;
  /** Les éléments montrés, dans l'ordre de `resolveShow` (≤ 8). */
  keys: readonly string[];
  /** La teinte imposée à chaque élément montré (grammaire §23). */
  tones: Readonly<Record<string, SceneTone>>;
  /** Le titre (« 2 postes connectés sur 3 ») et les panneaux des éléments (étapes, enveloppes). */
  panels: Panel3d[];
}

const TONE: Record<ShowFilter, (connected: boolean | null) => SceneTone> = {
  clients: (c) => (c === false ? 'off' : 'normal'),
  connectes: () => 'green',
  deconnectes: () => 'off',
  'chantiers-bloques': () => 'wait',
  'etapes-en-cours': () => 'normal',
  'a-envoyer': () => 'wait',
};

export function directShow(focus: ShowFocus | null): ShowPlan3d | null {
  if (!focus) {
    return null;
  }
  const keys = focus.items.map((i) => i.node.key);
  const tones: Record<string, SceneTone> = {};
  for (const item of focus.items) {
    tones[item.node.key] = TONE[focus.filter](item.connected);
  }
  const title: Panel3d = {
    id: 'show:title', anchor: CORE_KEY, tone: focus.filter === 'connectes' ? 'green' : focus.filter === 'deconnectes'
      ? 'off' : focus.filter === 'a-envoyer' || focus.filter === 'chantiers-bloques' ? 'wait' : 'normal',
    kind: 'show', title: safeLine(focus.title, 48),
    lines: focus.more > 0 ? [{ text: `+${focus.more} autres` }] : [],
  };
  const items: Panel3d[] = focus.filter === 'etapes-en-cours' || focus.filter === 'a-envoyer'
    ? focus.items.map((item) => ({
      id: `show:${item.node.key}`, anchor: item.node.key, tone: tones[item.node.key],
      kind: focus.filter === 'a-envoyer' ? 'envelope' as const : 'chantier' as const,
      title: safeLine(item.node.label, 40), lines: item.note ? [{ text: safeLine(item.note, 48) }] : [],
    }))
    : [];
  return { filter: focus.filter, keys, tones, panels: [title, ...items] };
}
