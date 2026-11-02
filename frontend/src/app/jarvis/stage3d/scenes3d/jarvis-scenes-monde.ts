import { MAX_SATELLITES, SceneView } from '../../scenes/jarvis-scene-reader';
import { CORE_KEY } from '../jarvis-link-graph';
import { anchorOf, sceneTitle } from './jarvis-scene-sgen';
import { safeLine } from './jarvis-scenes-poste';
import { stableHash } from '../jarvis-layout-3d';
import {
  ANCHOR_ABOVE, ANCHOR_BELOW, ANCHOR_GLOBE, ANCHOR_SIDE, CloudPoint, Effect3d, PanelLine, Scene3d, SceneContext,
  ScenePlan3d,
} from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-12 — **web, création, vidéo, passation, mémoire, essaim, veille** (cadrage §9.2 S6, S7, S5b, S10,
 * S12, S13 + N8, S17 ; S16 reste « S-GEN libellée »). Fichier <b>pur</b>.
 */

/** S13 — satellites dessinés au plus : PC 4, téléphone 2 (+ « +n »). */
export const SWARM_BOUND = { wide: MAX_SATELLITES, narrow: 2 } as const;
/** T-Z3 — le travelling d'une passation (60° au plus). */
export const HANDOFF_TRAVEL_DEG = 40;

const CREATION_LABEL: Record<string, string> = {
  page: 'Page', pdf: 'PDF', diagram: 'Diagramme', image: 'Visuel', office: 'Document', video: 'Vidéo', audio: 'Audio',
};

/** S6 — barre d'adresse au-dessus du cœur, globe filaire derrière, impulsions globe → cœur. */
export const sceneWeb: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const address = scene.data?.address ?? scene.detail ?? '';
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'address', mono: true,
      title: sceneTitle(scene),
      lines: address ? [{ text: safeLine(scene.data?.web === 'page' ? address : `Recherche : ${address}`), hot: true }]
        : [],
    }],
    effects: [
      { kind: 'globe' },
      { kind: 'flow', from: ANCHOR_GLOBE, to: CORE_KEY, tone: 'normal', rate: 0.6 },
    ],
    // Scène centrée sur le cœur : la caméra le cadre (vue d'ensemble), pas l'entité.
    focus: CORE_KEY,
  };
};

/**
 * S7 — l'objet se construit par couches sous son titre, les particules du cœur s'y déposent ; vidéo = salle de
 * montage (une couche par suivi du rendu) ; audio = onde ; une page lue = pages qui défilent (N11).
 */
export const sceneCreation: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const creation = scene.data?.creation ?? 'page';
  const checks = scene.data?.checks ?? 0;
  const lines: PanelLine[] = [];
  if (scene.data?.title) {
    lines.push({ text: safeLine(scene.data.title), hot: true });
  }
  lines.push({ text: CREATION_LABEL[creation] ?? 'Création' });
  if (creation === 'video' && checks > 0) {
    lines.push({ text: `Rendu suivi × ${checks}` });
  }
  const effects: Effect3d[] = creation === 'audio'
    ? [{ kind: 'pulse', anchor: CORE_KEY, tone: 'normal', seq: ctx.pulses }]
    : [
      { kind: 'cards', anchor: CORE_KEY, count: creation === 'video' ? Math.min(6, 1 + checks) : 3,
        hot: creation === 'video' ? Math.min(5, checks) : 2, layout: 'stack', tone: 'normal' },
      { kind: 'flow', from: CORE_KEY, to: ANCHOR_SIDE, tone: 'normal', rate: 0.5 },
    ];
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'creation', title: sceneTitle(scene), lines,
      scroll: scene.key === 'page_lire',
    }],
    effects,
    // Scène centrée sur le cœur : la caméra le cadre (vue d'ensemble), pas l'entité.
    focus: CORE_KEY,
  };
};

/** S5b — la pellicule en arc au-dessus du cœur, le plan extrait agrandi. */
export const sceneVideo: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const lines: PanelLine[] = [];
  if (scene.data?.film) {
    lines.push({ text: safeLine(scene.data.film), hot: true });
  }
  if (scene.data?.extract) {
    lines.push({ text: safeLine(scene.data.extract) });
  }
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{ id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'film', title: sceneTitle(scene),
      lines }],
    effects: [{ kind: 'cards', anchor: CORE_KEY, count: 6, hot: 3, layout: 'film', tone: 'normal' }],
    // Scène centrée sur le cœur : la caméra le cadre (vue d'ensemble), pas l'entité.
    focus: CORE_KEY,
  };
};

/** S10 — arc lumineux du sujet courant au sujet visé, un seul travelling (T-Z3) par passation. */
export const scenePassation: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const from = anchorOf(ctx.subjectId, ctx);
  const to = anchorOf(scene.data?.toKey, ctx);
  const label = [scene.data?.fromLabel, scene.data?.toLabel].filter(Boolean).join(' → ');
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{ id: `scene:${scene.key}`, anchor: to === CORE_KEY ? from : to, tone: 'normal', kind: 'label',
      title: sceneTitle(scene), lines: label ? [{ text: safeLine(label), hot: true }] : [] }],
    effects: from !== CORE_KEY && to !== CORE_KEY && from !== to
      ? [{ kind: 'flow', from, to, tone: 'normal', rate: 0.7, lift: 1.2 }] : [],
    focus: to === CORE_KEY ? null : to,
    travel: { id: `${scene.key}@${scene.since}`, degrees: HANDOFF_TRAVEL_DEG },
  };
};

/**
 * S12 — un souvenir (« tour 34 ») remonte des profondeurs du cœur ; la carte du savoir = constellation secondaire au-
 * dessus du cœur ; une règle proposée s'inscrit ; la compaction resserre les particules.
 */
export const sceneMemoire: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const repere = scene.data?.repere;
  const lines: PanelLine[] = [];
  if (repere) {
    lines.push({ text: safeLine(repere), hot: true });
  }
  if (scene.detail) {
    lines.push({ text: scene.detail });
  }
  const effects: Effect3d[] = [];
  if (scene.key === 'carte_chercher') {
    effects.push({ kind: 'cloud', anchor: ANCHOR_ABOVE, points: knowledgeStars(scene.detail ?? scene.libelle) });
  } else if (scene.key === 'compaction') {
    effects.push({ kind: 'pulse', anchor: CORE_KEY, tone: 'normal', seq: ctx.pulses });
  } else {
    effects.push({ kind: 'flow', from: ANCHOR_BELOW, to: CORE_KEY, tone: 'normal', rate: 0.6 });
  }
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{ id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'memory', title: sceneTitle(scene),
      lines }],
    effects,
    // Scène centrée sur le cœur : la caméra le cadre (vue d'ensemble), pas l'entité.
    focus: CORE_KEY,
  };
};

/** La carte du savoir : une petite constellation stable (hachage du libellé), 12 étoiles. */
function knowledgeStars(seed: string): CloudPoint[] {
  return Array.from({ length: 12 }, (_, i) => ({
    angle: stableHash(`${seed}~${i}`) * Math.PI * 2, height: (stableHash(`${seed}#${i}`) - 0.5) * 0.8,
    write: false, hot: i === 0,
  }));
}

/** S13 + N8 — un petit cœur satellite par délégation (≤ 4 ; téléphone ≤ 2 + « +n »), relié au cœur (L-essaim). */
export const sceneEssaim: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const all = scene.data?.satellites ?? [];
  const bound = ctx.narrow ? SWARM_BOUND.narrow : SWARM_BOUND.wide;
  const shown = all.slice(-bound);
  const more = all.length - shown.length;
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'swarm', title: sceneTitle(scene),
      lines: [...shown.map((q) => ({ text: safeLine(q, 48) })), ...(more > 0 ? [{ text: `+${more}` }] : [])],
    }],
    effects: shown.length ? [{ kind: 'swarm', count: shown.length, tone: 'normal' }] : [],
    // Scène centrée sur le cœur : la caméra le cadre (vue d'ensemble), pas l'entité.
    focus: CORE_KEY,
  };
};

/** S17 — un cadran de veille attaché au sujet (alimente l'horloge des veilles, itération 4). */
export const sceneVeille: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const anchor = anchorOf(ctx.subjectId, ctx);
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{ id: `scene:${scene.key}`, anchor, tone: 'normal', kind: 'dial', title: sceneTitle(scene),
      lines: scene.detail ? [{ text: scene.detail }] : [] }],
    effects: [{ kind: 'dial', anchor, tone: 'normal' }],
    focus: anchor === CORE_KEY ? null : anchor,
  };
};
