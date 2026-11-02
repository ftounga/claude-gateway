import {
  DETAIL_CHARS, MAX_FILES, MAX_TICKETS, OUTPUT_LINES, SceneView, maskSecrets,
} from '../../scenes/jarvis-scene-reader';
import { clientKey } from '../../scenes/jarvis-universe.models';
import { CORE_KEY } from '../jarvis-link-graph';
import { anchorOf, sceneTitle } from './jarvis-scene-sgen';
import { folderSpot } from './jarvis-session-memory';
import { CloudPoint, Effect3d, PanelLine, Scene3d, SceneContext, ScenePlan3d } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-09 — **commandes, tickets, fichiers** (cadrage §9.2 S3, S4, S5 ; N6, N9, N11) : familles 1-2 de
 * l'usage mesuré F-234 (≈ 4 800 appels / 30 j). Fichier <b>pur</b>.
 *
 * <ul>
 *   <li><b>S3 Commande</b> : zoom sur le poste ; <b>flux de données qui remonte</b> du poste au cœur (N6, débit ∝
 *   sortie) ; panneau mono en filigrane : la commande et ses <b>2 dernières lignes masquées</b>, 80 c. (D11).</li>
 *   <li><b>S4 Tickets</b> : cartes holographiques en éventail (≤ 6) sorties du client ; la carte nommée dans la
 *   phrase dite s'illumine ; les tickets de la session (≤ 30 min) restent avec elles.</li>
 *   <li><b>S5 Fichiers</b> : feuillets qui s'empilent (l'écriture = feuillet blanc vif), loupe qui balaie pour une
 *   recherche, <b>nuage du dépôt</b> près du sujet (N9, session seulement), <b>pages qui défilent</b> pour un
 *   document lu (N11).</li>
 * </ul>
 */

/** Le poste (client) du sujet courant, s'il est connu. */
export function posteOf(ctx: SceneContext): string | null {
  const client = ctx.universe?.clients.find((c) => c.subjects.some((s) => s.id === ctx.subjectId));
  return client ? clientKey(client) : null;
}

/** Une ligne bornée et masquée (défense en profondeur : le lecteur F-234 l'a déjà fait). */
export function safeLine(text: string, max = DETAIL_CHARS): string {
  const masked = maskSecrets(text ?? '').replace(/\s+$/, '');
  return masked.length <= max ? masked : `${masked.slice(0, max - 1)}…`;
}

export const sceneCommande: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const poste = anchorOf(posteOf(ctx), ctx);
  const lines = (scene.data?.lines ?? []).slice(-OUTPUT_LINES).map((l) => ({ text: safeLine(l) }));
  const output = lines.length;
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: poste, tone: 'normal', kind: 'terminal', mono: true, title: sceneTitle(scene),
      lines: [...(scene.detail ? [{ text: safeLine(`$ ${scene.detail}`) }] : []), ...lines],
    }],
    effects: poste === CORE_KEY ? [] : [
      // N6 — le flux remonte du poste au cœur ; il s'épaissit quand la commande parle.
      { kind: 'flow', from: poste, to: CORE_KEY, tone: 'normal',
        rate: 0.35 + (0.65 * Math.min(output, OUTPUT_LINES)) / OUTPUT_LINES },
    ],
    focus: poste === CORE_KEY ? null : poste,
  };
};

export const sceneTickets: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const poste = anchorOf(posteOf(ctx), ctx);
  const current = scene.data?.tickets ?? [];
  // Les tickets vus plus tôt dans la session, sur ce poste, restent avec ceux de la scène (≤ 6, les plus récents).
  const remembered = ctx.session.tickets.filter((t) => t.clientKey === (poste === CORE_KEY ? null : poste)
    && !current.includes(t.key)).map((t) => t.key);
  const keys = [...remembered, ...current].slice(-MAX_TICKETS);
  const spoken = keys.find((k) => ctx.spokenTickets.includes(k)) ?? null;
  const lines: PanelLine[] = keys.map((k) => ({ text: k, hot: k === spoken, tone: current.includes(k) ? undefined
    : 'off' }));
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: poste, tone: 'normal', kind: 'cards', mono: true, title: sceneTitle(scene),
      lines: lines.length ? lines : scene.detail ? [{ text: safeLine(scene.detail) }] : [],
    }],
    effects: keys.length ? [{ kind: 'cards', anchor: poste, count: keys.length,
      hot: spoken ? keys.indexOf(spoken) : null, layout: 'fan', tone: 'normal' }] : [],
    focus: poste === CORE_KEY ? null : poste,
  };
};

/** N11 — un document se lit en pages qui défilent (pas un fichier de code). */
const DOCUMENT = /\.(?:pdf|docx?|odt|md|txt|html?|pptx?|xlsx?|rtf)$/i;

export const sceneFichiers: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const subject = anchorOf(ctx.subjectId, ctx);
  const mode = scene.data?.mode ?? 'read';
  const files = (scene.data?.files ?? []).slice(-MAX_FILES);
  const last = files.length - 1;
  const search = mode === 'search';
  const lines: PanelLine[] = search
    ? [{ text: safeLine(`Recherche : ${scene.data?.pattern ?? scene.detail ?? ''}`), hot: true }]
    : files.map((f, i) => ({ text: safeLine(f), hot: i === last }));
  const effects: Effect3d[] = [];
  if (search) {
    effects.push({ kind: 'scan', anchor: subject, tone: 'normal' });
  } else if (files.length) {
    effects.push({ kind: 'cards', anchor: subject, count: files.length, hot: mode === 'write' ? last : null,
      layout: 'stack', tone: 'normal' });
  }
  const cloud = cloudPoints(ctx, files[last] ?? null);
  if (cloud.length && subject !== CORE_KEY) {
    effects.push({ kind: 'cloud', anchor: subject, points: cloud });
  }
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: subject, tone: 'normal', kind: 'sheets', mono: true, title: sceneTitle(scene),
      lines: lines.length ? lines : scene.detail ? [{ text: safeLine(scene.detail) }] : [],
      scroll: mode === 'read' && files.length > 0 && DOCUMENT.test(files[last]),
    }],
    effects,
    focus: subject === CORE_KEY ? null : subject,
  };
};

/** N9 — les fichiers de la session pour le sujet courant, en amas par dossier. */
function cloudPoints(ctx: SceneContext, hotName: string | null): CloudPoint[] {
  return ctx.session.files.filter((f) => f.subjectId === ctx.subjectId).map((f, i, all) => {
    const spot = folderSpot(f.dir);
    return { angle: spot.angle + ((i * 0.618) % 1 - 0.5) * 0.5, height: spot.height,
      write: f.write, hot: f.name === hotName && i === all.length - 1 };
  });
}
