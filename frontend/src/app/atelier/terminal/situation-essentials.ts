import { Situation, SituationChantier, groupEnvois } from '../../core/models/situation.models';
import { splitEssentialWithFallback } from './essential';

/** Où mène le clic d'une ligne de l'essentiel (F-225 / SF-225-04). */
export type SituationEssentialTarget =
  | { kind: 'chantier'; number: number }
  | { kind: 'thread' }
  | { kind: 'detail' };

/** Une ligne de l'onglet « L'essentiel ». */
export interface SituationEssentialLine {
  key: 'where' | 'last' | 'waiting' | 'next' | 'empty';
  /** « Où j'en suis », « Fait en dernier », « Vous attend », « Prochaine action ». */
  label: string;
  text: string;
  target: SituationEssentialTarget | null;
}

/** Jamais plus de cinq lignes : l'essentiel se lit d'un coup d'œil. */
export const SITUATION_ESSENTIALS_MAX = 5;
const LAST_MAX = 160;
/** Un titre de chantier peut faire 200 caractères : la ligne « Où j'en suis » le coupe. */
const CHANTIER_TITLE_MAX = 70;

/**
 * **L'essentiel du point de situation** (F-225 / SF-225-04) — 1 à 5 lignes, toutes DÉRIVÉES de la lecture
 * existante (aucun appel modèle) : où j'en suis, ce qui a été fait en dernier, ce qui m'attend (0 à 2), la
 * prochaine action. Chaque ligne dit où mène son clic : le chantier, le fil, ou l'onglet « Détail ».
 *
 * @param chantier le chantier du volet (split) : il prime sur le focus du parcours.
 */
export function situationEssentials(s: Situation, chantier: number | null = null,
    now: number = Date.now()): SituationEssentialLine[] {
  const lines: SituationEssentialLine[] = [];
  const current = currentChantier(s.chantiers, chantier);

  // 1. Où j'en suis.
  if (current) {
    const others = s.chantiers.length - 1;
    const tail = others > 0 ? ` (et ${others} autre${others > 1 ? 's' : ''} ouvert${others > 1 ? 's' : ''})` : '';
    lines.push({ key: 'where', label: 'Où j’en suis', target: { kind: 'chantier', number: current.number },
      text: `${chantierName(current)} · ${[current.phaseLabel, `${current.done}/${current.total}`].filter(Boolean).join(' · ')}${tail}` });
  } else if (s.exchange || s.lastActivityAt) {
    lines.push({ key: 'where', label: 'Où j’en suis', target: { kind: 'thread' },
      text: `Au fil de l’eau, sans chantier ouvert${s.lastActivityAt ? ` — dernier échange ${ago(s.lastActivityAt, now)}` : ''}` });
  }

  // 2. Fait en dernier.
  const x = s.exchange;
  const reply = x?.reply ? essentialOf(x.reply) : null;
  if (reply) {
    const when = x?.replyAt ? `${capitalize(ago(x.replyAt, now))} : ` : '';
    lines.push({ key: 'last', label: 'Fait en dernier', target: { kind: 'thread' }, text: when + clip(reply, LAST_MAX) });
  } else if (x?.request) {
    lines.push({ key: 'last', label: 'Fait en dernier', target: { kind: 'thread' },
      text: `Vous avez demandé : « ${clip(x.request, LAST_MAX - 20)} »` });
  }

  // 3. Ce qui m'attend (au plus deux lignes, par priorité).
  const waiting: SituationEssentialLine[] = [];
  const gate = (current && (current.planAwaitingValidation || current.diagnosisPending)) ? current
    : s.chantiers.find((c) => c.planAwaitingValidation || c.diagnosisPending) ?? null;
  if (gate) {
    waiting.push({ key: 'waiting', label: 'Vous attend', target: { kind: 'chantier', number: gate.number },
      text: gate.planAwaitingValidation ? `Valider le plan du chantier ${gate.number}`
        : `« Planifier » après le diagnostic du chantier ${gate.number}` });
  }
  const unsent = groupEnvois(s.envois ?? []).filter((g) => !g.sent);
  if (unsent.length > 0) {
    const first = unsent[0].latest;
    const what = unsent.every((g) => g.latest.kind === 'MAIL') ? 'mail'
      : unsent.every((g) => g.latest.kind === 'MESSAGE') ? 'message' : 'envoi';
    const to = first.recipient ? ` (${what === 'envoi' ? (first.kind === 'MAIL' ? 'mail ' : 'message ') : ''}à ${first.recipient}${unsent.length > 1 ? '…' : ''})` : '';
    waiting.push({ key: 'waiting', label: 'Vous attend', target: { kind: 'detail' },
      text: `${unsent.length} ${what}${unsent.length > 1 ? 's' : ''} pas marqué${unsent.length > 1 ? 's' : ''} envoyé${unsent.length > 1 ? 's' : ''}${to}` });
  }
  const attentes = s.attentes ?? [];
  if (attentes.length > 0) {
    const oldest = [...attentes].sort((a, b) => stamp(a.since) - stamp(b.since))[0];
    const more = attentes.length > 1 ? ` (+${attentes.length - 1})` : '';
    waiting.push({ key: 'waiting', label: 'Vous attend',
      target: oldest.chantierNumber !== null ? { kind: 'chantier', number: oldest.chantierNumber } : { kind: 'detail' },
      text: `${clip(oldest.description, 90)}${oldest.to ? ` — ${oldest.to}` : ''}${more}` });
  }
  lines.push(...waiting.slice(0, 2));

  // 4. Prochaine action : l'étape suivante du chantier, sinon la première suite proposée.
  if (current?.nextStep && !current.planAwaitingValidation && !current.diagnosisPending) {
    lines.push({ key: 'next', label: 'Prochaine action', target: { kind: 'chantier', number: current.number },
      text: `${current.nextRank ? `Étape ${current.nextRank} : ` : ''}${clip(current.nextStep, 110)}` });
  } else if (!gate && (s.suites ?? []).length > 0) {
    lines.push({ key: 'next', label: 'Prochaine action', target: { kind: 'thread' },
      text: `${clip(s.suites[0].title, 110)}${s.suites.length > 1 ? ` (ou ${s.suites.length - 1} autre${s.suites.length > 2 ? 's' : ''} suite${s.suites.length > 2 ? 's' : ''})` : ''}` });
  }

  if (lines.length === 0) {
    return [{ key: 'empty', label: '', text: 'Rien encore dans ce terminal.', target: null }];
  }
  return lines.slice(0, SITUATION_ESSENTIALS_MAX);
}

/** Le chantier du volet, sinon celui au focus, sinon le premier qui n'est pas en sommeil, sinon le premier. */
function currentChantier(chantiers: readonly SituationChantier[], lane: number | null): SituationChantier | null {
  if (lane !== null) {
    const pinned = chantiers.find((c) => c.number === lane);
    if (pinned) {
      return pinned;
    }
  }
  return chantiers.find((c) => c.focus) ?? chantiers.find((c) => !c.stale) ?? chantiers[0] ?? null;
}

function chantierName(c: SituationChantier): string {
  return `Chantier ${c.number}${c.title ? ` — ${clip(c.title, CHANTIER_TITLE_MAX)}` : ''}`;
}

function essentialOf(reply: string): string | null {
  const split = splitEssentialWithFallback(reply);
  const text = (split.essential ?? split.detail ?? '').trim();
  return text.length > 0 ? stripMarkdown(text) : null;
}

/** Une ligne d'aperçu, pas un rendu : on retire la ponctuation Markdown la plus courante. */
function stripMarkdown(text: string): string {
  return text.replace(/```[\s\S]*?```/g, ' ').replace(/[*_`#>]+/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');
}

function clip(text: string, max: number): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat.length <= max) {
    return flat;
  }
  // Ne coupe pas un émoji (paire de substitution) en deux.
  const chars = Array.from(flat);
  return chars.length <= max ? flat : `${chars.slice(0, max - 1).join('').trimEnd()}…`;
}

function stamp(iso: string | null): number {
  const ms = iso ? Date.parse(iso) : NaN;
  return isNaN(ms) ? Number.MAX_SAFE_INTEGER : ms;
}

function ago(iso: string, now: number): string {
  const at = Date.parse(iso);
  if (isNaN(at)) {
    return '';
  }
  const hours = Math.max(0, Math.floor((now - at) / 3_600_000));
  if (hours < 1) {
    return 'il y a moins d’une heure';
  }
  return hours < 48 ? `il y a ${hours} h` : `il y a ${Math.floor(hours / 24)} jours`;
}

function capitalize(text: string): string {
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}
