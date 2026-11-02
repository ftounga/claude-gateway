import { SceneView } from '../../scenes/jarvis-scene-reader';
import { CORE_KEY } from '../jarvis-link-graph';
import { Panel3d, Scene3d, SceneContext, ScenePlan3d, SceneTone } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-08 — **S-GEN 3D**, la scène générique (cadrage §9.2) : un anneau d'impulsion part du cœur à chaque
 * appel ; le libellé du registre s'écrit dans un petit panneau (« Radar · sujets du client × 3 ») ancré au cœur, ou à
 * l'entité quand la caméra la cadre ; l'entité est allumée (sinon le sujet courant). Couvre toute scène sans rendu
 * dédié et <b>tout outil futur</b>.
 */

/** L'ancrage d'un panneau : le nœud s'il est posé dans l'univers 3D, sinon le cœur. */
export function anchorOf(key: string | null | undefined, ctx: SceneContext): string {
  return key && ctx.nodes.has(key) ? key : CORE_KEY;
}

/** Le titre d'une scène : libellé du registre, « × n » quand la rafale n'est pas déjà comptée (comme le 2D). */
export function sceneTitle(scene: SceneView): string {
  return scene.count > 1 && !scene.libelle.includes(String(scene.count))
    ? `${scene.libelle} × ${scene.count}` : scene.libelle;
}

/** Le nœud mis en avant : l'entité reconnue, sinon le sujet courant, s'il est posé. */
export function sceneFocus(scene: SceneView, ctx: SceneContext): string | null {
  const key = scene.entity?.key ?? ctx.subjectId;
  return key && ctx.nodes.has(key) ? key : null;
}

/** La teinte de base : l'attente du PO (une autorisation épinglée) ou l'état normal. */
export function baseTone(scene: SceneView): SceneTone {
  return scene.pinned ? 'wait' : 'normal';
}

export const sceneGeneric: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const tone = baseTone(scene);
  const focus = sceneFocus(scene, ctx);
  const panel: Panel3d = {
    id: `scene:${scene.key}`,
    // Ancré au cœur ; quand la caméra cadre l'entité (§9.3), le panneau s'attache à elle pour rester à l'écran.
    anchor: focus ?? CORE_KEY,
    tone,
    kind: 'label',
    title: sceneTitle(scene),
    lines: [
      ...(scene.entity ? [{ text: scene.entity.label }] : []),
      ...(scene.detail ? [{ text: scene.detail }] : []),
    ],
    front: scene.pinned,
  };
  return {
    id: scene.id,
    rendered: 'generic',
    panels: [panel],
    effects: [{ kind: 'pulse', anchor: CORE_KEY, tone, seq: ctx.pulses }],
    focus,
  };
};
