import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { JarvisUniverse, clientKey } from '../../scenes/jarvis-universe.models';
import { CORE_KEY } from '../jarvis-link-graph';
import { SceneTone } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-25 — **le radar des alertes** (NF-15). Fichier <b>pur</b>.
 *
 * <p>Un anneau radar entoure l'univers ; chaque alerte <b>réelle</b> de l'ambiance est un écho qui pulse à
 * l'azimut de son client : la veille qui sonne (A5, rouge), le poste muet (A1, rouge), le sujet qui appelle (A3, la
 * teinte de l'attente). Aucune alerte ⇒ aucun radar. Rien n'est inventé : sans client posé, l'écho est au cœur.</p>
 */

export type EchoKind = 'veille' | 'poste' | 'cloche';

export interface RadarEcho {
  kind: EchoKind;
  /** Le nœud dont l'écho prend l'azimut (le client), ou le cœur. */
  anchor: string;
  tone: SceneTone;
}

/** Au plus autant d'échos (les alertes réelles sont rares ; borne de sûreté). */
export const MAX_ECHOES = 8;

export function radarEchoes(ambient: Ambient | null, universe: JarvisUniverse | null,
    nodes: ReadonlySet<string>): RadarEcho[] {
  if (!ambient) {
    return [];
  }
  const at = (key: string | null | undefined) => (key && nodes.has(key) ? key : CORE_KEY);
  const echoes: RadarEcho[] = [];
  if (ambient.alert) {
    echoes.push({ kind: 'veille', anchor: at(ambient.alert.clientKey), tone: 'alert' });
  }
  if (ambient.hostDown) {
    echoes.push({ kind: 'poste', anchor: at(ambient.hostDown.clientKey), tone: 'alert' });
  }
  if (ambient.calling) {
    const owner = universe?.clients.find((c) => c.subjects.some((s) => s.id === ambient.calling?.subjectId));
    echoes.push({ kind: 'cloche', anchor: at(owner ? clientKey(owner) : null), tone: 'wait' });
  }
  return echoes.slice(0, MAX_ECHOES);
}
