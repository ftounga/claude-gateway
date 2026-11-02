import { GENERIC_SCENE, SCENE_REGISTRY, SceneRegistry } from '../../scenes/jarvis-scene-registry';
import { SceneView } from '../../scenes/jarvis-scene-reader';
import { Scene3d, SceneContext, ScenePlan3d } from './jarvis-scene3d.model';
import { sceneGeneric } from './jarvis-scene-sgen';
import { sceneCommande, sceneFichiers, sceneTickets } from './jarvis-scenes-poste';
import { sceneAttente, sceneEtape, sceneLePoint, sceneOui, scenePointsDeRoute } from './jarvis-scenes-chantier';
import { sceneMessage } from './jarvis-scene-message';

/**
 * F-236 / SF-236-08 — **le registre des scènes 3D** (cadrage §3.2, prolonge F-234 D17).
 *
 * <p>Toute scène du registre F-234 <b>déclare son rendu 3D</b> : soit une fonction dans {@link SCENES_3D}, soit
 * son appartenance explicite à {@link SGEN_3D_IDS} (elle retombe sur S-GEN 3D). Une scène ajoutée au registre sans
 * l'une ni l'autre fait échouer la garde (`jarvis-scenes3d.spec.ts`) — ligne de `review-checklist.md`.</p>
 */
export const SCENES_3D: Readonly<Record<string, Scene3d>> = {
  [GENERIC_SCENE]: sceneGeneric,
  // SF-236-09 — commandes, tickets, fichiers.
  S3: sceneCommande,
  S4: sceneTickets,
  S5: sceneFichiers,
  // SF-236-10 — chantiers, étapes, attentes, « oui ».
  S2: sceneOui,
  S9: sceneEtape,
  S11: sceneAttente,
  S14: scenePointsDeRoute,
  S15: sceneLePoint,
  // SF-236-11 — À envoyer.
  S1: sceneMessage,
};

/**
 * Les scènes qui retombent <b>volontairement</b> sur S-GEN 3D. Itération 2 : les scènes dédiées les quittent une à
 * une (SF-236-09 → 13) ; restent la vague D F-234 (S18 Radar, S19 Teams, D-PO-7) et S16 (« S-GEN libellée »).
 */
export const SGEN_3D_IDS: ReadonlySet<string> = new Set([
  'S5b', 'S6', 'S7', 'S10', 'S12', 'S13', 'S16', 'S17',
  'S18', 'S19',
]);

/** Les scènes que le lecteur F-234 pose hors registre (S4 tickets, S1 messages, S2 autorisation, S5b vidéo). */
export const READER_SCENE_IDS: readonly string[] = ['S1', 'S2', 'S4', 'S5b', 'S9', 'S11', 'S14'];

/** Toutes les scènes connues : celles du registre (`scene` et `cible`) et celles du lecteur. */
export function knownSceneIds(registry: SceneRegistry = SCENE_REGISTRY): Set<string> {
  const ids = new Set<string>([GENERIC_SCENE, ...READER_SCENE_IDS]);
  for (const section of [registry.tools, registry.prefixes, registry.steps]) {
    for (const entry of Object.values(section)) {
      ids.add(entry.scene);
      if (entry.cible) {
        ids.add(entry.cible);
      }
    }
  }
  return ids;
}

/** Le rendu 3D d'une scène : dédié, sinon S-GEN 3D (jamais rien). */
export function scene3dFor(id: string): Scene3d {
  return Object.hasOwn(SCENES_3D, id) ? SCENES_3D[id] : sceneGeneric;
}

/** La scène F-234 en cours ⇒ son plan 3D (`null` sans scène). */
export function directScene(scene: SceneView | null, ctx: SceneContext): ScenePlan3d | null {
  return scene ? scene3dFor(scene.id)(scene, ctx) : null;
}
