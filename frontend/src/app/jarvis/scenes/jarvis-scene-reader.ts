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
}

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
  private line = '';
  private scanned = 0;
  private block: { kind: BlockKind; to: string | null; subject: string | null } | null = null;

  constructor(index: NameIndex = new NameIndex(null), subjectId: string | null = null) {
    this.index = index;
    this.subjectId = subjectId;
  }

  /** Un nouvel univers (ouverture, fin de tour) : les noms se reconnaissent sur lui. */
  setIndex(index: NameIndex): void {
    this.index = index;
    this.toSendAdded = 0; // l'univers relu compte déjà les messages finis
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
    const sceneId = source ? 'S4' : resolved.scene;
    const key = source ? `tickets-${source}` : type;
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
      id: sceneId, key, libelle: source ? ticketLabel(source) : resolved.libelle, count: 1,
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
  confirm(detail: string | null, now: number): void {
    if (this.scene && !this.scene.pinned) {
      this.next = this.scene;
    }
    this.scene = {
      id: 'S2', key: 'confirm', libelle: 'J’attends votre « oui »', count: 1, detail: sceneDetail(detail),
      entity: this.currentSubject(), pinned: true, since: now, updatedAt: now,
    };
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
      show: this.showRequest,
    };
  }

  // ---- interne ----

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
    const fence = /^```\s*(mail|message)\s*$/i.exec(trimmed);
    if (!this.block && fence) {
      this.block = { kind: fence[1].toLowerCase() as BlockKind, to: null, subject: null };
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
      id: 'GENERIQUE', key, libelle: finished ? `${noun} prêt · à envoyer` : `${noun} en préparation`, count: 1,
      detail: sceneDetail(parts.join(' · ')), entity: entity ?? this.currentSubject(), pinned: false,
      since: now, updatedAt: now,
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
