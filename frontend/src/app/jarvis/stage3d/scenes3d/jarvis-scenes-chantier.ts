import { normalizeName } from '../../scenes/jarvis-name-matcher';
import { SceneView } from '../../scenes/jarvis-scene-reader';
import { UniverseChantier, clientKey } from '../../scenes/jarvis-universe.models';
import { CORE_KEY } from '../jarvis-link-graph';
import { anchorOf, sceneFocus, sceneTitle } from './jarvis-scene-sgen';
import { posteOf, safeLine } from './jarvis-scenes-poste';
import { Effect3d, PanelLine, Scene3d, SceneContext, ScenePlan3d } from './jarvis-scene3d.model';

/**
 * F-236 / SF-236-10 — **chantiers, étapes, attentes, « oui »** (cadrage §9.2 S9, S11, S2, S14, S15 ; familles 3-5).
 * Fichier <b>pur</b>.
 *
 * <ul>
 *   <li><b>S9 Étape</b> : zoom sur le chantier ; la barre progresse (`done + stepsDone` / `total`) ; une perle du
 *   chapelet se remplit, l'éclair court le long du chapelet ; la phase s'écrit.</li>
 *   <li><b>S11 Attente</b> : « personne · catégorie » relié au losange en pointillé (L-attente) ; levée = rupture
 *   T-L4 + lueur verte.</li>
 *   <li><b>S2 « Oui » / question</b> : panneau d'attente au-dessus du cœur, <b>devant tout</b> tant qu'il attend ;
 *   l'arc se vide au rythme de `remaining`.</li>
 *   <li><b>S14 Points de route</b> : couronne de perles sous le cœur, une par question, verte quand traitée.</li>
 *   <li><b>S15 Le point</b> : vague sur tous les sujets du client (80 ms d'écart) + tour lent de 60°.</li>
 * </ul>
 */

interface FoundChantier {
  key: string;
  chantier: UniverseChantier;
  clientKey: string;
}

/** Un chantier de l'univers par sa clé `sujet#n`. */
export function chantierOf(ctx: SceneContext, key: string | null | undefined): FoundChantier | null {
  if (!key || !ctx.universe) {
    return null;
  }
  for (const client of ctx.universe.clients) {
    for (const subject of client.subjects) {
      for (const chantier of subject.chantiers) {
        if (`${subject.id}#${chantier.number}` === key) {
          return { key, chantier, clientKey: clientKey(client) };
        }
      }
    }
  }
  return null;
}

/** Le chantier focalisé du sujet courant (celui que l'écran du sujet suit). */
function focusChantier(ctx: SceneContext): string | null {
  const subject = ctx.universe?.clients.flatMap((c) => c.subjects).find((s) => s.id === ctx.subjectId);
  const chantier = subject?.chantiers.find((c) => c.focus) ?? subject?.chantiers[0];
  return subject && chantier ? `${subject.id}#${chantier.number}` : null;
}

/** Les perles faites d'un chantier : celles de l'univers plus celles cochées pendant le tour, bornées au total. */
export function stepsFilled(found: FoundChantier, ctx: SceneContext): number {
  return Math.min(found.chantier.total, found.chantier.done + (ctx.stepsDone[found.key] ?? 0));
}

export const sceneEtape: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const key = scene.data?.chantierKey ?? focusChantier(ctx);
  const found = chantierOf(ctx, key);
  const anchor = anchorOf(key, ctx) === CORE_KEY ? anchorOf(ctx.subjectId, ctx) : (key as string);
  const filled = found ? stepsFilled(found, ctx) : 0;
  const lines: PanelLine[] = [{ text: sceneTitle(scene) }];
  if (found?.chantier.phaseLabel) {
    lines.push({ text: `Phase : ${found.chantier.phaseLabel}` });
  }
  const step = scene.data?.step;
  const status = scene.data?.status;
  if (step || status) {
    lines.push({ text: safeLine([step ? `Étape ${step}` : null, status].filter(Boolean).join(' — ')), hot: true,
      tone: status === 'faite' || status === 'vérifiée' ? 'green' : undefined });
  }
  if (found && found.chantier.total > 0) {
    lines.push({ text: `${filled}/${found.chantier.total} étapes` });
  }
  const effects: Effect3d[] = [];
  const bead = found && filled > 0 ? `${found.key}/${filled}` : null;
  if (bead && ctx.nodes.has(bead) && scene.data?.progress === 'step') {
    // L'éclair court du losange à la perle qui vient de se remplir, et elle s'allume.
    effects.push({ kind: 'flow', from: found!.key, to: bead, tone: 'normal', rate: 0.5 });
    effects.push({ kind: 'pulse', anchor: bead, tone: 'green', seq: ctx.pulses });
  } else {
    effects.push({ kind: 'pulse', anchor, tone: 'normal', seq: ctx.pulses });
  }
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor, tone: 'normal', kind: 'chantier',
      title: found ? safeLine(`Ch. ${found.chantier.number} — ${found.chantier.title}`, 48) : sceneTitle(scene),
      lines,
      progress: found && found.chantier.total > 0 ? filled / found.chantier.total : undefined,
    }],
    effects,
    focus: anchor === CORE_KEY ? null : anchor,
  };
};

export const sceneAttente: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const person = scene.data?.person;
  const attached = scene.data?.attached !== false;
  const wanted = person ? normalizeName(person) : null;
  // Le chantier qui attend cette personne (sujet courant), sinon le chantier focalisé.
  const subject = ctx.universe?.clients.flatMap((c) => c.subjects).find((s) => s.id === ctx.subjectId);
  const waiting = wanted ? subject?.chantiers.find((c) => c.waitingOn.some((w) => normalizeName(w) === wanted))
    : undefined;
  const key = waiting && subject ? `${subject.id}#${waiting.number}` : focusChantier(ctx);
  const anchor = anchorOf(key, ctx) === CORE_KEY ? anchorOf(ctx.subjectId, ctx) : (key as string);
  const poste = posteOf(ctx);
  const client = ctx.universe?.clients.find((c) => clientKey(c) === poste);
  const named = client?.people.find((p) => normalizeName(p.name) === wanted
    || p.variants.some((v) => normalizeName(v) === wanted))?.name ?? person;
  // Le nœud de la personne : la disposition le clé par le nom attendu ou par le nom complet de l'univers.
  const personKey = poste ? [named, person].filter((n): n is string => !!n).map((n) => `${poste}:${normalizeName(n)}`)
    .find((k) => ctx.nodes.has(k)) ?? null : null;
  const effects: Effect3d[] = [{ kind: 'tether', from: anchor, to: personKey, tone: 'wait', broken: !attached }];
  if (!attached) {
    effects.push({ kind: 'pulse', anchor, tone: 'green', seq: ctx.pulses });
  }
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor, tone: attached ? 'wait' : 'green', kind: 'label', title: sceneTitle(scene),
      lines: [{ text: safeLine(`${named ?? 'Quelqu’un'} · ${scene.data?.category ?? 'attente'}`) }],
    }],
    effects,
    focus: anchor === CORE_KEY ? null : anchor,
  };
};

/** S2 — la part qui reste (0-1) : `remaining` sur la durée totale accordée ; sans échéance, l'arc reste plein. */
export function countdownFraction(scene: SceneView): number {
  const remaining = scene.data?.remaining;
  const deadline = scene.data?.deadline;
  if (remaining === undefined || deadline === undefined) {
    return 1;
  }
  const total = Math.max(1, Math.ceil((deadline - scene.since) / 1000));
  return Math.max(0, Math.min(1, remaining / total));
}

export const sceneOui: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const fraction = countdownFraction(scene);
  const remaining = scene.data?.remaining;
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'wait', kind: 'question', title: sceneTitle(scene),
      lines: [
        ...(scene.detail ? [{ text: scene.detail }] : []),
        ...(remaining !== undefined ? [{ text: `${remaining} s`, hot: true }] : []),
      ],
      progress: remaining !== undefined ? fraction : undefined,
      // Devant tout tant qu'il attend sa réponse (D14).
      front: scene.pinned,
    }],
    // L'arc qui se vide et ses ondes lentes (le moteur les émet toutes les 2,4 s).
    effects: [{ kind: 'countdown', fraction, tone: 'wait' }],
    focus: sceneFocus(scene, ctx),
  };
};

export const scenePointsDeRoute: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const questions = scene.data?.questions ?? [];
  const done = questions.map((q) => q.state === 'TERMINEE');
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'route', title: sceneTitle(scene),
      lines: questions.map((q, i) => ({ text: safeLine(q.libelle, 48), tone: done[i] ? 'green' as const : undefined,
        hot: q.state === 'EN_COURS' })),
    }],
    effects: questions.length ? [{ kind: 'crown', done }] : [],
    // La couronne est sous le cœur : la caméra le cadre.
    focus: CORE_KEY,
  };
};

export const sceneLePoint: Scene3d = (scene: SceneView, ctx: SceneContext): ScenePlan3d => {
  const poste = posteOf(ctx);
  const client = ctx.universe?.clients.find((c) => clientKey(c) === poste);
  const keys = (client?.subjects ?? []).map((s) => s.id).filter((k) => ctx.nodes.has(k));
  return {
    id: scene.id,
    rendered: 'dedicated',
    panels: [{
      id: `scene:${scene.key}`, anchor: CORE_KEY, tone: 'normal', kind: 'label', title: sceneTitle(scene),
      lines: [{ text: client ? `${client.name} · ${keys.length} sujet${keys.length > 1 ? 's' : ''}` : 'Tous vos sujets' }],
    }],
    effects: keys.length ? [{ kind: 'wave', keys, tone: 'normal', seq: ctx.pulses }] : [],
    focus: null,
    // Un tour lent de 60° (une fois par scène) : la vague passe sur tout le client.
    travel: { id: `${scene.key}@${scene.since}`, degrees: 60 },
  };
};
