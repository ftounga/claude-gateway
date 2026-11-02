import { SceneData, SceneView } from '../../scenes/jarvis-scene-reader';
import { NodeRef } from '../../scenes/jarvis-universe.models';

/**
 * F-236 — **scènes F-234 de test** (univers de test : CAGIP, sujet « Migration Forge », chantier 3 « Bascule DNS »,
 * Zahi attendu ; Edenred déconnecté). Partagées par les tests des scènes 3D et par le banc de captures.
 */

export const FORGE: NodeRef = { kind: 'subject', key: 's-forge', label: 'Migration Forge', clientKey: 'h-cagip',
  subjectId: 's-forge' };

/** Une scène F-234 telle que le lecteur la publie. */
export function sceneOf(id: string, key: string, libelle: string, extra: Partial<SceneView> = {},
    data?: SceneData): SceneView {
  return { id, key, libelle, count: 1, detail: null, entity: FORGE, pinned: false, since: 0, updatedAt: 0, data,
    ...extra };
}

/** Les scènes du banc, par nom de scène du banc (`scene-<nom>`). */
export const BANC_SCENE_VIEWS: Readonly<Record<string, SceneView>> = {
  sgen: sceneOf('GENERIQUE', 'radar_sujets', 'Radar · sujets du client', { count: 3,
    detail: 'radar sujets CAGIP' }),
};
