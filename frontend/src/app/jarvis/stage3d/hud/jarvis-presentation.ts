import { SceneData, SceneView } from '../../scenes/jarvis-scene-reader';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { JarvisUniverse, NodeRef, clientKey } from '../../scenes/jarvis-universe.models';
import { normalizeName } from '../../scenes/jarvis-name-matcher';
import { ScreenSignals } from '../jarvis-stage-director';

/**
 * F-236 / SF-236-23 — **le mode présentation** (NF-11, `?presentation=1`). Fichier <b>pur</b>.
 *
 * <p>Montrer Jarvis à un client ou à un manager <b>sans fuite</b> : ce que l'écran passe à la scène est filtré
 * <b>avant</b> le dessin — noms de personnes réduits à leurs initiales, coût retiré, sorties de commande et détails
 * (cibles, chemins, objets, adresses, motifs, fichiers, tickets) retirés. Les postes et les sujets gardent leur nom
 * (ce que l'on présente). Le reste de l'écran (sous-titres, détail, cockpit) n'est pas rendu.</p>
 */

/** « Zahi Ben » ⇒ « Z. B. » ; un nom vide ⇒ « • ». */
export function initials(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/[\s\-_.']+/).filter((w) => w.length > 0);
  return words.length === 0 ? '•' : words.slice(0, 3).map((w) => `${w[0].toUpperCase()}.`).join(' ');
}

/**
 * L'univers présentable : personnes, attentes et destinataires en initiales — une attente ou un destinataire qui
 * désigne une personne connue (nom ou variante) prend <b>ses</b> initiales, pour que les liaisons restent justes ;
 * postes, sujets, chantiers gardés.
 */
export function presentUniverse(universe: JarvisUniverse | null): JarvisUniverse | null {
  if (!universe) {
    return null;
  }
  return {
    ...universe,
    clients: universe.clients.map((c) => {
      const known = new Map<string, string>();
      for (const p of c.people) {
        for (const n of [p.name, ...p.variants]) {
          known.set(normalizeName(n), initials(p.name));
        }
      }
      const mask = (name: string) => known.get(normalizeName(name)) ?? initials(name);
      return {
        ...c,
        people: c.people.map((p) => ({ name: initials(p.name), variants: [], role: null })),
        toSend: c.toSend.map(mask),
        subjects: c.subjects.map((s) => ({
          ...s,
          chantiers: s.chantiers.map((ch) => ({ ...ch, waitingOn: ch.waitingOn.map(mask) })),
        })),
      };
    }),
  };
}

/**
 * La clé présentable d'un nœud : une clé de personne (`client:nom`) devient celle de ses initiales — celles de la
 * personne connue qu'elle désigne (nom ou variante), pour rester sur le nœud dessiné ; les autres clés sont gardées.
 */
export function personKeys(universe: JarvisUniverse | null): (key: string) => string {
  const known = new Map<string, string>();
  for (const c of universe?.clients ?? []) {
    const ck = clientKey(c);
    for (const p of c.people) {
      for (const n of [p.name, ...p.variants]) {
        known.set(`${ck}:${normalizeName(n)}`, `${ck}:${normalizeName(initials(p.name))}`);
      }
    }
  }
  return (key: string) => {
    const cut = key.indexOf(':');
    return cut < 0 ? key : known.get(key) ?? `${key.slice(0, cut)}:${normalizeName(initials(key.slice(cut + 1)))}`;
  };
}

function presentNode(node: NodeRef | null, keyOf: (key: string) => string): NodeRef | null {
  return node && node.kind === 'person' ? { ...node, key: keyOf(node.key), label: initials(node.label) } : node;
}

/** Les champs d'une scène qui portent un détail (retirés) ; la forme de la scène (mode, progression…) reste. */
function presentData(data: SceneData | undefined): SceneData | undefined {
  if (!data) {
    return data;
  }
  const out: SceneData = { ...data };
  delete out.lines;
  delete out.pattern;
  delete out.subject;
  delete out.address;
  delete out.title;
  delete out.film;
  delete out.extract;
  delete out.repere;
  if (out.tickets) {
    out.tickets = [];
  }
  if (out.files) {
    out.files = [];
  }
  if (out.satellites) {
    out.satellites = out.satellites.map(() => '•');
  }
  if (out.questions) {
    out.questions = out.questions.map((q) => ({ libelle: '•', state: q.state }));
  }
  if (out.person !== undefined) {
    out.person = initials(out.person);
  }
  if (out.to !== undefined) {
    out.to = initials(out.to);
  }
  return out;
}

/** La scène présentable : libellé générique gardé, détail et données de détail retirés, personne en initiales. */
export function presentScene(scene: SceneView | null, keyOf: (key: string) => string = personKeys(null)):
    SceneView | null {
  if (!scene) {
    return null;
  }
  return { ...scene, detail: null, entity: presentNode(scene.entity, keyOf), data: presentData(scene.data) };
}

/** L'ambiance présentable : jamais de coût ; l'appel d'un autre sujet et l'alerte gardent leur signal, sans texte. */
export function presentAmbient(ambient: Ambient | null): Ambient | null {
  if (!ambient) {
    return null;
  }
  return {
    ...ambient,
    cost: null,
    calling: ambient.calling ? { ...ambient.calling, label: '' } : null,
    alert: ambient.alert ? { ...ambient.alert, label: '' } : null,
  };
}

/**
 * Ce que l'écran passe à la scène en mode présentation : univers, scène et ambiance filtrés, jamais administrateur
 * (A4), aucun ticket nommé dans la phrase dite. Le même objet en entrée et en sortie hors mode.
 */
export function presentSignals(s: ScreenSignals): ScreenSignals {
  return new PresentationFilter().signals(s);
}

/** Une fonction à une entrée, mémorisée sur l'identité de sa dernière entrée. */
function memo<I, O>(fn: (input: I) => O): (input: I) => O {
  let last: { input: I; output: O } | null = null;
  return (input: I) => {
    if (!last || last.input !== input) {
      last = { input, output: fn(input) };
    }
    return last.output;
  };
}

/**
 * {@link presentSignals} qui rend les <b>mêmes objets</b> tant que l'entrée ne change pas (univers, scène, ambiance) :
 * le plan de la scène n'est pas recalculé pour rien (`sameScreen` compare par identité).
 */
export class PresentationFilter {
  private readonly universe = memo(presentUniverse);
  private readonly keys = memo(personKeys);
  private readonly scene = memo((scene: SceneView | null) => presentScene(scene, this.keyOf));
  private readonly ambient = memo(presentAmbient);
  private keyOf: (key: string) => string = personKeys(null);

  /**
   * « Montrer » n'est pas rendu en présentation (ses listes peuvent nommer des personnes) ; les nœuds cités le
   * restent, sous leur clé présentable.
   */
  signals(s: ScreenSignals): ScreenSignals {
    const keyOf = this.keys(s.universe);
    if (keyOf !== this.keyOf) {
      this.keyOf = keyOf;
      this.scene(null); // l'univers a changé : la scène est refiltrée avec les nouvelles clés
    }
    return { ...s, universe: this.universe(s.universe), scene: this.scene(s.scene), ambient: this.ambient(s.ambient),
      lit: s.lit.map(keyOf), said: s.said.map(keyOf), shown: null, showFocus: null, admin: false, spokenTickets: [] };
  }
}

/** Le tour automatique lent de l'univers : un poste, puis ses sujets (hors terminal du poste), dans l'ordre. */
export function tourKeys(universe: JarvisUniverse | null): string[] {
  const keys: string[] = [];
  for (const c of universe?.clients ?? []) {
    keys.push(clientKey(c));
    for (const s of c.subjects) {
      if (!s.hostTerminal) {
        keys.push(s.id);
      }
    }
  }
  return keys;
}

/** Un nœud regardé toutes les… (lent : la caméra a le temps d'arriver et de se poser). */
export const TOUR_STEP_MS = 9000;
