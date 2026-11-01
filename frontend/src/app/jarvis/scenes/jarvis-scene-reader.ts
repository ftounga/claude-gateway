import { NameIndex } from './jarvis-name-matcher';
import { burstLabel, resolveScene } from './jarvis-scene-registry';
import { ShowRequest, isShowLine, parseShowLine } from './jarvis-show';
import { NodeRef } from './jarvis-universe.models';

/**
 * **Le lecteur de scène** (F-234 / SF-234-02, D1, D2, D14) — **pur** : il consomme les événements d'un tour (étapes,
 * texte, autorisation, fin) avec l'instant `now`, consulte le registre et l'index des noms, et tient l'état que
 * l'écran dessine : les nœuds allumés (au plus 3) et **une** scène. Aucun appel réseau, aucun appel modèle, aucune
 * horloge interne : les tests le pilotent instant par instant.
 */

/** Combien de nœuds allumés à la fois (D2). */
export const MAX_LIT = 3;
/** Extinction douce d'un nœud cité (D2). */
export const LIT_MS = 6000;
/** Durée minimale d'une scène avant d'être remplacée (D14). */
export const MIN_SCENE_MS = 1200;
/** Deux appels du même outil plus proches que ce délai se regroupent en rafale (D14). */
export const BURST_MS = 2500;
/** Une scène sans nouvelle s'efface après ce délai (sauf l'autorisation, qui attend sa réponse). */
export const SCENE_TTL_MS = 8000;
/** Le détail d'une scène, au plus (D11). */
export const DETAIL_CHARS = 80;
/** Fenêtre de recouvrement quand on relit une ligne en cours d'écriture. */
const OVERLAP = 60;

/** Priorité d'une citation : action en cours > ligne 🎙 > corps (D2). */
export type LitPriority = 0 | 1 | 2;

export interface LitNode {
  node: NodeRef;
  priority: LitPriority;
  at: number;
}

/** Ce qu'une scène dédiée dessine en plus du libellé (jamais un corps, jamais un secret). */
export interface SceneData {
  /** S3 — les 2 dernières lignes de la sortie, masquées, 80 car. au plus (D11). */
  lines?: string[];
  /** S4 — les clés de tickets vues (« FORGE-41 »), au plus {@link MAX_TICKETS}. */
  tickets?: string[];
  /** S4 — Jira ou Confluence. */
  source?: 'Jira' | 'Confluence';
  /** S5 — les fichiers lus ou écrits (noms courts), au plus {@link MAX_FILES}, le plus récent en dernier. */
  files?: string[];
  /** S5 — lecture, écriture, liste ou recherche (loupe). */
  mode?: 'read' | 'write' | 'list' | 'search';
  /** S5 — le motif cherché (loupe). */
  pattern?: string;
  /** S9 — ce qui avance : une étape, le plan, la phase, l'autonomie. */
  progress?: 'step' | 'plan' | 'phase' | 'autonomy';
  /** S9 / S11 — le chantier concerné (clé `sujet#n`), s'il est reconnu. */
  chantierKey?: string;
  /** S9 — le rang de l'étape. */
  step?: number;
  /** S9 — l'état écrit (« faite », « vérifiée », « bloquée »…) ou la taille du plan. */
  status?: string;
  /** S11 — la personne attendue et la nature de l'attente. */
  person?: string;
  category?: string;
  /** S11 — attachée (posée) ou détachée (levée, annulée). */
  attached?: boolean;
  /** S2 — l'échéance de l'autorisation (horloge du lecteur) et les secondes restantes. */
  deadline?: number;
  remaining?: number;
  /** S2 — une question posée à l'écran du terminal, plutôt qu'une autorisation. */
  question?: boolean;
  /** S14 — les points de route du tour. */
  questions?: { libelle: string; state: string }[];
  /** S1 — enveloppe (courriel) ou bulle (messagerie, ticket). */
  envelope?: 'mail' | 'message';
  /** S1 — destinataire et objet, tels qu'écrits en tête du bloc (jamais le corps). */
  to?: string;
  subject?: string;
  /** S1 — le bloc est fini : il file dans « À envoyer (n) ». */
  finished?: boolean;
  /** S1 — `email_me` : il ne part qu'à vous. */
  forMe?: boolean;
  /** S1 — des preuves sont jointes (F-212 : l'objectif photographie). */
  proof?: boolean;
}

/** S9 — ce que fait chaque outil de chantier. */
const PROGRESS: Record<string, SceneData['progress']> = {
  update_plan_step: 'step', update_sub_steps: 'step',
  set_subject_plan: 'plan', set_plan: 'plan', valider_plan: 'plan', exit_plan_mode: 'plan',
  submit_diagnosis: 'phase', confirmer_diagnostic: 'phase', reopen_investigation: 'phase', propose_guided_mode: 'phase',
  lancer_chantier_autonome: 'autonomy',
};
/** S9 — les états d'étape écrits par l'étiquette passerelle (SF-234-04). */
const STEP_STATES = ['faite', 'vérifiée', 'en échec', 'bloquée', 'à faire', 'sous-étapes'];
/** S14 — un point de route, au plus. */
const ROUTE_CHARS = 48;

/** S3 : lignes de sortie montrées (D11). */
export const OUTPUT_LINES = 2;
/** S4 : cartes de tickets au plus. */
export const MAX_TICKETS = 6;
/** S5 : feuillets empilés au plus. */
export const MAX_FILES = 6;

/** Une commande qui vise Jira / Confluence (S4). */
const TICKET_COMMAND = /\b(?:jira|atlassian|confluence|acli)\b|\/rest\/api\/|\/wiki\//i;
/** Une clé de ticket : `ABC-123`. */
const TICKET_KEY = /\b[A-Z][A-Z0-9]{1,9}-\d{1,6}\b/g;

/** Les clés de tickets d'un texte, dans l'ordre, sans doublon. */
export function ticketKeys(text: string | null | undefined): string[] {
  return [...new Set((text ?? '').match(TICKET_KEY) ?? [])];
}

/** La commande vise-t-elle Jira ou Confluence ? */
export function ticketSource(command: string | null | undefined): 'Jira' | 'Confluence' | null {
  const c = command ?? '';
  if (!TICKET_COMMAND.test(c)) {
    return null;
  }
  return /confluence|\/wiki\//i.test(c) ? 'Confluence' : 'Jira';
}

/** Le mode d'une action sur les fichiers (S5), ou `null`. */
export function fileMode(type: string): SceneData['mode'] | null {
  switch (type) {
    case 'read': case 'read_file': return 'read';
    case 'write': case 'write_file': case 'edit_file': case 'multi_edit': return 'write';
    case 'list': case 'list_files': return 'list';
    case 'search': case 'grep': case 'glob': case 'search_files': return 'search';
    default: return null;
  }
}

/** `src/app/x.ts` → `x.ts`. */
export function baseName(path: string): string {
  const flat = (path ?? '').trim().replace(/[\\/]+$/, '');
  return flat.slice(Math.max(flat.lastIndexOf('/'), flat.lastIndexOf('\\')) + 1) || flat;
}

/** Les dernières lignes d'une sortie, masquées et coupées (D11). */
export function outputTail(output: string): string[] {
  return output.split(/\r?\n/).map((l) => l.replace(/\s+$/, '')).filter((l) => l.trim().length > 0)
    .slice(-OUTPUT_LINES).map((l) => {
      const masked = maskSecrets(l);
      return masked.length <= DETAIL_CHARS ? masked : `${masked.slice(0, DETAIL_CHARS - 1)}…`;
    });
}

/** La scène à l'écran. */
export interface SceneView {
  /** La scène rendue (`GENERIQUE` aujourd'hui, `S2` pour l'autorisation). */
  id: string;
  /** La clé de regroupement (type d'action, ou bloc de message). */
  key: string;
  libelle: string;
  count: number;
  /** Ce sur quoi porte l'action (chemin, commande, destinataire), tronqué et masqué ; jamais un corps. */
  detail: string | null;
  /** L'entité en surbrillance : la cible reconnue, sinon le sujet courant. */
  entity: NodeRef | null;
  /** L'autorisation attend sa réponse : elle reste devant toute autre scène. */
  pinned: boolean;
  since: number;
  updatedAt: number;
  /** Ce que la scène dédiée dessine (S3, S4, S5…). */
  data?: SceneData;
}

/** Ce que l'écran dessine. */
export interface ReaderState {
  lit: LitNode[];
  scene: SceneView | null;
  /** Un compteur qui avance à chaque appel d'outil : l'anneau de l'orbe pulse une fois par appel. */
  pulses: number;
  /** Les messages terminés dans ce tour, pas encore comptés par l'univers (« À envoyer (n) » +1). */
  toSendAdded: number;
  /** SF-234-13 — ce que le PO a demandé à voir (ligne « 🖥 montrer: »), jusqu'au tour suivant ou « c'est bon ». */
  show: ShowRequest | null;
  /** SF-234-06 — étapes faites pendant le tour, par chantier (`done/total` avance avant la relecture de l'univers). */
  stepsDone: Record<string, number>;
}

/** Masque les motifs de secret d'une ligne (D11) : jetons, en-têtes d'autorisation, mots de passe, clés. */
export function maskSecrets(text: string): string {
  return (text ?? '')
    .replace(/(authorization\s*:\s*)(bearer\s+|basic\s+|token\s+)?[^\s"']+/gi, '$1$2•••')
    .replace(/(bearer\s+)[^\s"']+/gi, '$1•••')
    .replace(/((?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key)\s*[=:]\s*)[^\s&"']+/gi, '$1•••')
    .replace(/\b(?:sk|pk|rk)-[A-Za-z0-9_-]{8,}/g, '•••')
    .replace(/\b(?:ghp|gho|ghs|github_pat|xox[abp])_[A-Za-z0-9_]{8,}/g, '•••')
    .replace(/\bAKIA[0-9A-Z]{12,}/g, '•••')
    .replace(/(-u\s+[^\s:]+:)[^\s"']+/g, '$1•••');
}

/** Un détail lisible : une ligne, masquée, tronquée. */
export function sceneDetail(text: string | null | undefined): string | null {
  const flat = maskSecrets((text ?? '').replace(/\s+/g, ' ').trim());
  if (!flat) {
    return null;
  }
  return flat.length <= DETAIL_CHARS ? flat : `${flat.slice(0, DETAIL_CHARS - 1)}…`;
}

/** Une étape relayée par le flux (sous-ensemble de `AtelierStreamAction`). */
export interface ReaderAction {
  type: string;
  path?: string;
}

type BlockKind = 'mail' | 'message';

export class JarvisSceneReader {
  private index: NameIndex;
  private subjectId: string | null;
  private lit: LitNode[] = [];
  private scene: SceneView | null = null;
  private next: SceneView | null = null;
  private pulses = 0;
  private toSendAdded = 0;
  private showRequest: ShowRequest | null = null;
  /** S3 — la sortie de la commande en cours (fin seulement). */
  private output = '';
  /** S9 — étapes faites pendant ce tour, par chantier, pas encore comptées par l'univers. */
  private stepsDone: Record<string, number> = {};
  private line = '';
  private scanned = 0;
  private block: { kind: BlockKind; to: string | null; subject: string | null; proof: boolean } | null = null;

  constructor(index: NameIndex = new NameIndex(null), subjectId: string | null = null) {
    this.index = index;
    this.subjectId = subjectId;
  }

  /** Un nouvel univers (ouverture, fin de tour) : les noms se reconnaissent sur lui. */
  setIndex(index: NameIndex): void {
    this.index = index;
    this.toSendAdded = 0; // l'univers relu compte déjà les messages finis
    this.stepsDone = {}; // … et les étapes faites
  }

  setSubject(subjectId: string | null): void {
    this.subjectId = subjectId;
  }

  /** Un tour commence : le texte repart de zéro (les nœuds allumés s'éteignent d'eux-mêmes). */
  startTurn(): void {
    this.line = '';
    this.scanned = 0;
    this.block = null;
    this.showRequest = null; // SF-234-13 : le tour suivant rend l'écran à son état normal
  }

  /** SF-234-13 — « c'est bon » : l'écran revient à l'état normal. Rend vrai si quelque chose était montré. */
  clearShow(): boolean {
    const had = this.showRequest !== null;
    this.showRequest = null;
    return had;
  }

  /** Une étape d'outil (avant son exécution). */
  action(action: ReaderAction, now: number): void {
    const type = action.type ?? '';
    const resolved = resolveScene(type);
    const named = action.path ? this.index.match(action.path, this.contextSubject()) : [];
    for (const node of named) {
      this.light(node, 2, now);
    }
    this.pulses++;
    // S4 — une commande qui vise Jira / Confluence devient des cartes de tickets (heuristique, D11).
    const source = resolved.scene === 'S3' ? ticketSource(action.path) : null;
    // S1 — les captures de preuve (F-212) passent par l'étape `bash` : elles sont une preuve jointe, pas une commande.
    const proof = type === 'bash' && /^captures de preuve\b/i.test(action.path ?? '');
    const sceneId = proof ? 'S1' : source ? 'S4' : resolved.scene;
    // S11 — pose, mise à jour et levée d'une attente forment une seule scène (avec la carte `attente`).
    const key = source ? `tickets-${source}` : sceneId === 'S11' ? 'attente' : type;
    const current = this.scene;
    const entity = named[0] ?? this.currentSubject();
    if (sceneId === 'S3') {
      this.output = '';
    }
    if (current && !current.pinned && current.key === key && now - current.updatedAt <= BURST_MS) {
      const count = current.count + 1;
      this.scene = {
        ...current, count, libelle: source ? ticketLabel(source) : burstLabel(resolved, count),
        detail: sceneDetail(action.path) ?? current.detail, entity: named[0] ?? current.entity, updatedAt: now,
        data: this.sceneData(sceneId, type, action.path, source, current.data),
      };
      return;
    }
    this.show({
      id: sceneId, key, libelle: proof ? 'Preuves capturées' : source ? ticketLabel(source) : resolved.libelle, count: 1,
      detail: sceneDetail(action.path), entity, pinned: false, since: now, updatedAt: now,
      data: this.sceneData(sceneId, type, action.path, source, undefined),
    }, now);
  }

  /** S3 — un morceau de sortie de la commande en cours : seules les 2 dernières lignes, masquées (D11). */
  commandOutput(chunk: string, now: number): void {
    if (!chunk) {
      return;
    }
    this.output = (this.output + chunk).slice(-2000);
    const target = this.next?.id === 'S3' ? this.next : this.scene?.id === 'S3' ? this.scene : null;
    if (!target) {
      return;
    }
    const updated: SceneView = { ...target, data: { ...target.data, lines: outputTail(this.output) }, updatedAt: now };
    if (target === this.next) {
      this.next = updated;
    } else {
      this.scene = updated;
    }
  }

  private sceneData(sceneId: string, type: string, path: string | undefined, source: 'Jira' | 'Confluence' | null,
      previous: SceneData | undefined): SceneData | undefined {
    if (sceneId === 'S3') {
      return { lines: [] };
    }
    if (sceneId === 'S4' && source) {
      const tickets = [...new Set([...(previous?.tickets ?? []), ...ticketKeys(path)])].slice(-MAX_TICKETS);
      return { tickets, source };
    }
    if (sceneId === 'S9') {
      return this.progressData(type, path);
    }
    if (sceneId === 'S1') {
      // `email_me` (« Courriel · objet ») ou captures de preuve (« captures de preuve : titre »).
      const subject = (path ?? '').replace(/^(courriel\s*·|captures de preuve\s*:)\s*/i, '').trim() || undefined;
      return type === 'bash'
        ? { envelope: 'message', proof: true, subject }
        : { envelope: 'mail', forMe: type === 'email_me', subject, finished: type === 'email_me' };
    }
    if (sceneId === 'S11') {
      return attenteData(type, path);
    }
    const mode = fileMode(type);
    if (sceneId === 'S5' && mode) {
      if (mode === 'search') {
        return { mode, pattern: sceneDetail(path) ?? undefined };
      }
      const name = path ? baseName(path) : null;
      const files = [...(previous?.files ?? []).filter((f) => f !== name), ...(name ? [name] : [])].slice(-MAX_FILES);
      return { mode, files };
    }
    return previous;
  }

  /** Un morceau de réponse. Les noms sont reconnus au fil de l'écriture ; le corps d'un message n'est jamais lu. */
  text(delta: string, now: number): void {
    if (!delta) {
      return;
    }
    this.line += delta;
    let cut = this.line.indexOf('\n');
    while (cut >= 0) {
      const complete = this.line.slice(0, cut);
      this.line = this.line.slice(cut + 1);
      this.completeLine(complete, now);
      this.scanned = 0;
      cut = this.line.indexOf('\n');
    }
    this.scanPartial(now);
  }

  /** Une autorisation attend un « oui » : elle passe devant tout (D14). */
  confirm(detail: string | null, now: number, timeoutMs?: number | null): void {
    this.pin({
      id: 'S2', key: 'confirm', libelle: 'J’attends votre « oui »', count: 1, detail: sceneDetail(detail),
      entity: this.currentSubject(), pinned: true, since: now, updatedAt: now, data: deadline(now, timeoutMs),
    });
  }

  /** SF-234-06 — une question structurée attend sa réponse à l'écran du terminal (S2, variante question). */
  question(now: number, timeoutMs?: number | null): void {
    this.pin({
      id: 'S2', key: 'question', libelle: 'J’ai une question pour vous', count: 1,
      detail: 'Réponse attendue à l’écran du terminal', entity: this.currentSubject(), pinned: true, since: now,
      updatedAt: now, data: { ...deadline(now, timeoutMs), question: true },
    });
  }

  /** SF-234-06 — S9 : le plan du tour se pose (événement `plan`, F-39). */
  plan(steps: { title: string; status: string }[], now: number): void {
    if (!steps.length) {
      return;
    }
    const done = steps.filter((s) => /^(done|fait|faite|completed|verifie)/i.test(s.status ?? '')).length;
    const size = `${steps.length} étape${steps.length > 1 ? 's' : ''}`;
    this.place({
      id: 'S9', key: 'plan', libelle: 'Le plan se pose', count: 1,
      detail: sceneDetail(done ? `${size} · ${done} faite${done > 1 ? 's' : ''}` : size),
      entity: this.currentSubject(), pinned: false, since: now, updatedAt: now,
      data: { progress: 'plan', status: `${done}/${steps.length}` },
    }, now);
  }

  /** SF-234-06 — S11 : une carte d'attente arrive (événement `attente`, F-175) — posée, ou levée. */
  attente(event: { status?: string | null; qui?: string | null; requestedTo?: string | null; categorie?: string | null },
      now: number): void {
    const person = (event.qui ?? event.requestedTo ?? '').trim() || undefined;
    const attached = event.status !== 'FAIT' && event.status !== 'ANNULE';
    const named = person ? this.index.match(person, this.contextSubject()) : [];
    for (const node of named) {
      this.light(node, 2, now);
    }
    const previous = this.scene?.key === 'attente' ? this.scene.data : undefined;
    this.place({
      id: 'S11', key: 'attente', libelle: attached ? 'Attente posée' : 'Attente levée', count: 1,
      detail: null, entity: named[0] ?? this.currentSubject(), pinned: false, since: now, updatedAt: now,
      data: {
        ...previous, person: person ?? previous?.person,
        category: event.categorie?.toLowerCase() ?? previous?.category, attached,
      },
    }, now);
  }

  /** SF-234-06 — S14 : les points de route du tour (événement `questions`, F-210). */
  questions(list: { libelle: string; state: string }[], now: number): void {
    if (!list.length) {
      return;
    }
    const done = list.filter((q) => q.state === 'TERMINEE').length;
    this.place({
      id: 'S14', key: 'questions', libelle: `Points de route · ${done}/${list.length}`, count: 1, detail: null,
      entity: this.currentSubject(), pinned: false, since: now, updatedAt: now,
      data: {
        questions: list.slice(0, 6).map((q) => ({
          libelle: q.libelle.length <= ROUTE_CHARS ? q.libelle : `${q.libelle.slice(0, ROUTE_CHARS - 1)}…`,
          state: q.state,
        })),
      },
    }, now);
  }

  /** La question structurée est tranchée. */
  questionResolved(now: number): void {
    if (this.scene?.pinned && this.scene.key === 'question') {
      this.confirmResolved(now);
    }
  }

  /** L'autorisation est tranchée (ici, ailleurs, ou expirée). */
  confirmResolved(now: number): void {
    if (this.scene?.pinned) {
      this.scene = this.next ? { ...this.next, since: now, updatedAt: now } : null;
      this.next = null;
    }
  }

  /** La fin du tour : la dernière ligne est lue. */
  done(now: number): void {
    if (this.line) {
      this.completeLine(this.line, now);
      this.line = '';
      this.scanned = 0;
    }
    this.block = null;
  }

  /** Le temps passe : extinctions, scène suivante, scène périmée. Rend vrai si l'état a changé. */
  tick(now: number): boolean {
    let changed = false;
    const kept = this.lit.filter((l) => now - l.at < LIT_MS);
    if (kept.length !== this.lit.length) {
      this.lit = kept;
      changed = true;
    }
    if (this.scene?.pinned) {
      // S2 — le compte à rebours ; à l'échéance, l'autorisation expire d'elle-même.
      const due = this.scene.data?.deadline;
      if (due !== undefined) {
        const remaining = Math.max(0, Math.ceil((due - now) / 1000));
        if (remaining === 0) {
          this.confirmResolved(now);
          return true;
        }
        if (remaining !== this.scene.data?.remaining) {
          this.scene = { ...this.scene, data: { ...this.scene.data, remaining } };
          return true;
        }
      }
      return changed;
    }
    if (this.next && (!this.scene || now - this.scene.since >= MIN_SCENE_MS)) {
      this.scene = { ...this.next, since: now };
      this.next = null;
      changed = true;
    } else if (this.scene && !this.next && now - this.scene.updatedAt >= SCENE_TTL_MS) {
      this.scene = null;
      changed = true;
    }
    return changed;
  }

  state(): ReaderState {
    return {
      lit: [...this.lit], scene: this.scene, pulses: this.pulses, toSendAdded: this.toSendAdded,
      show: this.showRequest, stepsDone: { ...this.stepsDone },
    };
  }

  // ---- interne ----

  /** Une scène qui attend une réponse : devant tout, la scène en cours reprend ensuite. */
  private pin(scene: SceneView): void {
    if (this.scene && !this.scene.pinned) {
      this.next = this.scene;
    }
    this.scene = scene;
  }

  /** Une scène d'événement : remplace la même (mise à jour) ou suit les règles d'affichage. */
  private place(scene: SceneView, now: number): void {
    if (this.scene && !this.scene.pinned && this.scene.key === scene.key) {
      this.scene = { ...scene, since: this.scene.since };
      return;
    }
    if (this.next?.key === scene.key) {
      this.next = scene;
      return;
    }
    this.show(scene, now);
  }

  /** S9 — l'étiquette « chantier 3 · étape 2 · faite » lue (SF-234-04) ; le chantier rapproché de l'univers. */
  private progressData(type: string, label: string | undefined): SceneData {
    const text = (label ?? '').toLowerCase();
    const number = /chantier\s+(\d{1,3})/.exec(text);
    const step = /étape\s+(\d{1,3})/.exec(text);
    const node = number
      ? this.contextSubject().map((s) => this.index.chantier(s, Number(number[1]))).find((n) => !!n) ?? null
      : null;
    const status = STEP_STATES.find((st) => text.includes(st))
      ?? /plan en \d+ étapes?/.exec(text)?.[0] ?? /confiance [a-z ]+/.exec(text)?.[0] ?? undefined;
    const progress = PROGRESS[type] ?? 'step';
    if (node && progress === 'step' && (status === 'faite' || status === 'vérifiée')) {
      this.stepsDone[node.key] = (this.stepsDone[node.key] ?? 0) + 1;
    }
    return { progress, chantierKey: node?.key, step: step ? Number(step[1]) : undefined, status };
  }

  private show(scene: SceneView, now: number): void {
    if (this.scene && (this.scene.pinned || now - this.scene.since < MIN_SCENE_MS)) {
      this.next = scene; // la plus récente gagne, après la durée minimale de la scène en cours
      return;
    }
    this.scene = scene;
    this.next = null;
  }

  private completeLine(raw: string, now: number): void {
    const line = raw.replace(/\r$/, '');
    const trimmed = line.trim();
    if (!this.block && isShowLine(trimmed)) {
      // SF-234-13 (D18) : la ligne « Montrer » est un ordre d'écran — ni nom à allumer, ni texte.
      const request = parseShowLine(trimmed);
      if (request) {
        this.showRequest = request;
      }
      return;
    }
    // La balise peut porter `attente=` et `preuves=` (MESSAGE_BLOCK_DOCTRINE, F-203/F-212).
    const fence = /^```\s*(mail|message)\b(.*)$/i.exec(trimmed);
    if (!this.block && fence) {
      this.block = { kind: fence[1].toLowerCase() as BlockKind, to: null, subject: null,
        proof: /\bpreuves\s*=/i.test(fence[2] ?? '') };
      this.showBlock(now, false);
      return;
    }
    if (this.block) {
      if (/^```\s*$/.test(trimmed)) {
        this.toSendAdded++;
        this.showBlock(now, true);
        this.block = null;
        return;
      }
      const to = /^(?:À|A)\s*:\s*(.+)$/i.exec(trimmed);
      if (to && this.block.to === null) {
        this.block.to = to[1].trim();
        for (const node of this.index.match(this.block.to, this.contextSubject())) {
          this.light(node, 1, now);
        }
        this.showBlock(now, false);
        return;
      }
      const subject = /^Objet\s*:\s*(.+)$/i.exec(trimmed);
      if (subject && this.block.subject === null) {
        this.block.subject = subject[1].trim();
        this.showBlock(now, false);
      }
      return; // le corps d'un message n'est ni lu ni rendu
    }
    this.lightFrom(line.slice(Math.max(0, this.scanned - OVERLAP)), trimmed.startsWith('🎙') ? 1 : 0, now);
    // S4 — les clés citées dans la réponse rejoignent les cartes pendant que la scène est là.
    if (this.scene?.id === 'S4' && this.scene.data?.tickets) {
      const keys = ticketKeys(line);
      if (keys.length) {
        const tickets = [...new Set([...this.scene.data.tickets, ...keys])].slice(-MAX_TICKETS);
        this.scene = { ...this.scene, data: { ...this.scene.data, tickets }, updatedAt: now };
      }
    }
  }

  /** La ligne en cours : relue depuis la dernière frontière de mot, avec un recouvrement. */
  private scanPartial(now: number): void {
    if (this.block || !this.line || isShowLine(this.line)) {
      return;
    }
    const boundary = Math.max(this.line.lastIndexOf(' '), this.line.lastIndexOf(','), this.line.lastIndexOf('.'));
    if (boundary <= this.scanned) {
      return;
    }
    const from = Math.max(0, this.scanned - OVERLAP);
    this.lightFrom(this.line.slice(from, boundary), this.line.trimStart().startsWith('🎙') ? 1 : 0, now);
    this.scanned = boundary;
  }

  private lightFrom(text: string, priority: LitPriority, now: number): void {
    for (const node of this.index.match(text, this.contextSubject())) {
      this.light(node, priority, now);
    }
  }

  private showBlock(now: number, finished: boolean): void {
    const block = this.block!;
    const noun = block.kind === 'mail' ? 'Mail' : 'Message';
    const parts = [block.to ? `À : ${block.to}` : null, block.subject ? `Objet : ${block.subject}` : null]
      .filter((p): p is string => !!p);
    const entity = block.to ? this.index.match(block.to, this.contextSubject())[0] ?? null : null;
    const key = `block-${block.kind}`;
    const view: SceneView = {
      id: 'S1', key, libelle: finished ? `${noun} prêt · à envoyer` : `${noun} en préparation`, count: 1,
      detail: sceneDetail(parts.join(' · ')), entity: entity ?? this.currentSubject(), pinned: false,
      since: now, updatedAt: now,
      data: {
        envelope: block.kind, to: block.to ? sceneDetail(block.to) ?? undefined : undefined,
        subject: block.subject ? sceneDetail(block.subject) ?? undefined : undefined, finished, proof: block.proof,
      },
    };
    if (this.scene && this.scene.key === key && !this.scene.pinned) {
      this.scene = { ...view, since: this.scene.since };
      return;
    }
    this.show(view, now);
  }

  /** Allume un nœud ; au-delà de 3, le moins prioritaire puis le plus ancien s'éteint (D2). */
  private light(node: NodeRef, priority: LitPriority, now: number): void {
    const existing = this.lit.find((l) => l.node.key === node.key);
    if (existing) {
      existing.at = now;
      existing.priority = Math.max(existing.priority, priority) as LitPriority;
      return;
    }
    if (this.lit.length >= MAX_LIT) {
      const weakest = [...this.lit].sort((a, b) => a.priority - b.priority || a.at - b.at)[0];
      if (weakest.priority > priority) {
        return;
      }
      this.lit = this.lit.filter((l) => l !== weakest);
    }
    this.lit.push({ node, priority, at: now });
  }

  /** Les sujets de contexte pour « chantier N » : le dernier sujet cité, puis le sujet courant. */
  private contextSubject(): (string | null)[] {
    const cited = [...this.lit].reverse().find((l) => l.node.kind === 'subject');
    return [cited?.node.key ?? null, this.subjectId];
  }

  private currentSubject(): NodeRef | null {
    return this.index.node(this.subjectId);
  }
}

/** « Jira · consultation », « Confluence · page ». */
function ticketLabel(source: 'Jira' | 'Confluence'): string {
  return source === 'Jira' ? 'Jira · consultation' : 'Confluence · page';
}

/** L'échéance d'une autorisation (S2), si le flux la donne. */
function deadline(now: number, timeoutMs?: number | null): SceneData {
  return timeoutMs && timeoutMs > 0
    ? { deadline: now + timeoutMs, remaining: Math.ceil(timeoutMs / 1000) }
    : {};
}

/** S11 — l'étiquette « Zahi · validation » (record), « Zahi · demandé » (update), « levée » (close). */
function attenteData(type: string, label: string | undefined): SceneData {
  const parts = (label ?? '').split(' · ').map((p) => p.trim()).filter(Boolean);
  if (type === 'close_blocker') {
    return { attached: false, status: parts[0] };
  }
  const first = parts[0];
  // Une personne commence par une capitale ; la catégorie seule est en minuscules (étiquette passerelle).
  const person = first && first[0] !== first[0].toLowerCase() ? first : undefined;
  const category = person ? parts[1] : first;
  return { person, category, attached: true };
}
