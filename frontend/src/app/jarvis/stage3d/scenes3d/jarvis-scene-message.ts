import { SceneView } from '../../scenes/jarvis-scene-reader';
import { CORE_KEY } from '../jarvis-link-graph';
import { anchorOf, sceneTitle } from './jarvis-scene-sgen';
import { posteOf, safeLine } from './jarvis-scenes-poste';
import { PanelLine, Scene3d, SceneContext, ScenePlan3d } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-11 — **S1 Enveloppe / bulle** (cadrage §9.2, T-P3, L-message). Fichier <b>pur</b>.
 *
 * <p>Zoom sur le destinataire (sinon le poste) ; l'enveloppe holographique se forme, le destinataire puis l'objet
 * s'écrivent — <b>jamais le corps</b> (le lecteur F-234 ne le lit pas). Son filament d'ancrage, en teinte d'attente,
 * est la liaison L-message (personne ↔ enveloppe). À la fin du bloc, l'enveloppe <b>vole</b> vers « À envoyer » (T-P3,
 * 1,1 s) ; le compteur ne prend +1 qu'à son arrivée. Un courriel pour soi (`email_me`) ne part pas vers le compteur.</p>
 */
export const sceneMessage: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const data = scene.data ?? {};
  const person = scene.entity?.kind === 'person' ? scene.entity.key : null;
  const anchor = anchorOf(person ?? posteOf(ctx), ctx);
  const lines: PanelLine[] = [];
  if (data.forMe) {
    lines.push({ text: 'Pour vous' });
  } else if (data.to) {
    lines.push({ text: safeLine(`À : ${data.to}`) });
  }
  if (data.subject) {
    lines.push({ text: safeLine(`Objet : ${data.subject}`) });
  }
  if (data.proof) {
    lines.push({ text: 'Preuves jointes' });
  }
  const leaving = data.finished === true && !data.forMe;
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor, tone: data.forMe ? 'normal' : 'wait', kind: 'envelope',
      title: sceneTitle(scene), lines, fly: leaving ? 'to-send' : undefined,
    }],
    effects: [],
    focus: anchor === CORE_KEY ? null : anchor,
  };
};
