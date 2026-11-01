import { NameIndex } from './jarvis-name-matcher';
import { burstLabel, resolveScene } from './jarvis-scene-registry';
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
}

/** Ce que l'écran dessine. */
export interface ReaderState {
  lit: LitNode[];
  scene: SceneView | null;
  /** Un compteur qui avance à chaque appel d'outil : l'anneau de l'orbe pulse une fois par appel. */
  pulses: number;
  /** Les messages terminés dans ce tour, pas encore comptés par l'univers (« À envoyer (n) » +1). */
  toSendAdded: number;
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
    const current = this.scene;
    const entity = named[0] ?? this.currentSubject();
    if (current && !current.pinned && current.key === type && now - current.updatedAt <= BURST_MS) {
      const count = current.count + 1;
      this.scene = {
        ...current, count, libelle: burstLabel(resolved, count), detail: sceneDetail(action.path) ?? current.detail,
        entity: named[0] ?? current.entity, updatedAt: now,
      };
      return;
    }
    this.show({
      id: resolved.scene, key: type, libelle: resolved.libelle, count: 1, detail: sceneDetail(action.path),
      entity, pinned: false, since: now, updatedAt: now,
    }, now);
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
    return { lit: [...this.lit], scene: this.scene, pulses: this.pulses, toSendAdded: this.toSendAdded };
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
  }

  /** La ligne en cours : relue depuis la dernière frontière de mot, avec un recouvrement. */
  private scanPartial(now: number): void {
    if (this.block || !this.line) {
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
