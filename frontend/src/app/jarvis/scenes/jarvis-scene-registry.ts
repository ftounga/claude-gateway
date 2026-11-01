import registryJson from './jarvis-scene-registry.json';

/**
 * **Le registre des scènes** (F-234 / SF-234-02, D3) — outil ou famille → scène de l'écran Jarvis.
 *
 * Recherche : nom d'outil exact → préfixe de famille (le plus long) → type d'étape → **générique**. Tout outil absent
 * du registre s'anime quand même (scène générique, nom humanisé) : rien ne reste muet, rien à développer pour un
 * outil neuf. La garde passerelle (SF-234-04) exige néanmoins que tout outil offert à l'agent y soit déclaré.
 */

/** Une entrée du registre. */
export interface SceneEntry {
  /** La scène rendue aujourd'hui : `GENERIQUE` tant que la scène dédiée n'est pas livrée. */
  scene: string;
  /** La scène dédiée prévue au cadrage (§5), pour mémoire. */
  cible?: string;
  /** Le nom lisible de l'action. */
  libelle: string;
  /** Le libellé d'une rafale, `{n}` = nombre d'appels regroupés. */
  pluriel?: string;
}

export interface SceneRegistry {
  tools: Record<string, SceneEntry>;
  prefixes: Record<string, SceneEntry>;
  steps: Record<string, SceneEntry>;
}

/** La scène par défaut. */
export const GENERIC_SCENE = 'GENERIQUE';

/** Le registre embarqué (un seul fichier JSON, lu aussi par la garde passerelle). */
export const SCENE_REGISTRY: SceneRegistry = {
  tools: registryJson.tools as Record<string, SceneEntry>,
  prefixes: registryJson.prefixes as Record<string, SceneEntry>,
  steps: registryJson.steps as Record<string, SceneEntry>,
};

/** Ce que le registre dit d'un type d'événement `action` (nom d'outil ou type d'étape). */
export interface ResolvedScene {
  scene: string;
  libelle: string;
  pluriel?: string;
  /** D'où vient la réponse : utile à la mesure (part de la générique, SF-234-11). */
  source: 'tool' | 'prefix' | 'step' | 'fallback';
}

/** `update_blocker` → « update blocker » ; `teamsReadFile` → « teams read file ». */
export function humanize(name: string): string {
  const spaced = (name ?? '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_\-.:/]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
  if (!spaced) {
    return 'Action';
  }
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/** Résout un type d'action : exact → préfixe le plus long → type d'étape → générique (nom humanisé). */
export function resolveScene(type: string, registry: SceneRegistry = SCENE_REGISTRY): ResolvedScene {
  const key = (type ?? '').trim();
  const exact = registry.tools[key];
  if (exact) {
    return { scene: exact.scene, libelle: exact.libelle, pluriel: exact.pluriel, source: 'tool' };
  }
  const prefix = Object.keys(registry.prefixes)
    .filter((p) => key.startsWith(p))
    .sort((a, b) => b.length - a.length)[0];
  if (prefix) {
    const entry = registry.prefixes[prefix];
    const rest = key.slice(prefix.length);
    return {
      scene: entry.scene,
      libelle: rest ? `${entry.libelle} · ${humanize(rest).toLowerCase()}` : entry.libelle,
      source: 'prefix',
    };
  }
  const step = registry.steps[key];
  if (step) {
    return { scene: step.scene, libelle: step.libelle, pluriel: step.pluriel, source: 'step' };
  }
  return { scene: GENERIC_SCENE, libelle: humanize(key), source: 'fallback' };
}

/** Le libellé d'une rafale de `count` appels. */
export function burstLabel(resolved: ResolvedScene, count: number): string {
  if (count > 1 && resolved.pluriel) {
    return resolved.pluriel.replace('{n}', String(count));
  }
  return resolved.libelle;
}
