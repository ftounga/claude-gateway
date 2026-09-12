import { AtelierTerminalBlock, TerminalActivity } from '../../core/models/atelier.models';

/** Lignes relevées. Six : ce que la gateway garde (SF-76-01), donc ce qu'il sert d'envoyer. */
export const PREVIEW_MAX_LINES = 6;

/** Longueur d'une ligne relevée. La gateway reborne : ici on évite de transporter l'inutile. */
export const PREVIEW_MAX_LINE_LENGTH = 160;

/**
 * Ce qu'un terminal **dit de lui-même** (F-76 / SF-76-02) : ce qu'il fait, et ses dernières lignes.
 *
 * <p>Forme **exacte** du corps envoyé à la gateway : ce qui est relevé ici est ce qui sera lu sur la
 * carte du poste et dans la tuile de supervision. Une seule vérité, deux densités d'affichage.</p>
 */
export interface TerminalPreviewReport {
  activity: TerminalActivity;
  activityDetail: string | null;
  previewLines: string[];
}

/** L'état de l'écran du terminal, réduit à ce dont l'aperçu a besoin. */
export interface TerminalPreviewSource {
  /** Commande soumise à décision, ou `null` : c'est elle qui fait passer devant tout le reste. */
  awaitingCommand: string | null;
  /** Un tour est en cours (l'agent travaille), même si aucune commande n'est encore engagée. */
  running: boolean;
  /** Les blocs affichés — du tour en cours s'il y en a un, sinon du dernier tour. */
  blocks: AtelierTerminalBlock[];
}

/**
 * Dérive l'aperçu de l'état de l'écran. **Fonction pure** : elle ne lit rien, n'appelle rien, et se
 * teste sans monter le moindre composant — c'est ce qui permet de vérifier la règle qui compte
 * (l'attente d'autorisation passe devant tout) sans simuler un tour entier.
 *
 * <p><b>L'ordre des questions est la règle.</b> On demande d'abord si quelque chose attend une
 * décision : c'est le seul état que l'utilisateur <b>doit</b> voir, et une demande d'autorisation
 * arrive précisément pendant qu'un tour est « en cours ». Répondre « en cours » à ce moment-là
 * serait exactement l'erreur du 2026-09-08 — un écran qui dit « ça travaille » alors que ça attend
 * depuis douze heures.</p>
 */
export function derivePreview(source: TerminalPreviewSource): TerminalPreviewReport {
  const lines = lastLines(source.blocks);
  if (source.awaitingCommand) {
    return {
      activity: 'AWAITING_APPROVAL',
      activityDetail: truncate(source.awaitingCommand),
      previewLines: lines,
    };
  }
  if (source.running) {
    const command = lastCommand(source.blocks);
    return {
      // Un tour dont aucune commande n'est encore engagée RÉFLÉCHIT : dire « exécute » sans rien
      // à nommer laisserait croire qu'une commande tourne et qu'on ne sait pas laquelle.
      activity: command ? 'RUNNING' : 'THINKING',
      activityDetail: command,
      previewLines: lines,
    };
  }
  return { activity: 'IDLE', activityDetail: null, previewLines: lines };
}

/** Vrai si deux relevés disent la même chose — ce qui dispense de renvoyer le second. */
export function samePreview(
  left: TerminalPreviewReport | null,
  right: TerminalPreviewReport | null,
): boolean {
  if (left === null || right === null) {
    return left === right;
  }
  return (
    left.activity === right.activity &&
    left.activityDetail === right.activityDetail &&
    left.previewLines.length === right.previewLines.length &&
    left.previewLines.every((line, index) => line === right.previewLines[index])
  );
}

/**
 * Les **dernières** lignes de la transcription : la commande en invite, puis sa sortie. Les
 * dernières, jamais les premières — un aperçu dit **où l'on en est**, et garder le début d'un
 * `npm test` afficherait éternellement « Running tests… ».
 */
function lastLines(blocks: AtelierTerminalBlock[]): string[] {
  const lines: string[] = [];
  for (const block of blocks) {
    lines.push(`$ ${block.command ?? block.tool}`);
    if (block.output) {
      for (const line of block.output.split('\n')) {
        lines.push(line);
      }
    }
  }
  return lines
    .map((line) => truncate(line))
    .filter((line): line is string => line !== null)
    .slice(-PREVIEW_MAX_LINES);
}

/** La commande du dernier bloc — celle qui tourne, quand quelque chose tourne. */
function lastCommand(blocks: AtelierTerminalBlock[]): string | null {
  if (blocks.length === 0) {
    return null;
  }
  const last = blocks[blocks.length - 1];
  return truncate(last.command ?? last.tool);
}

/** Ramène une ligne à ce qu'un aperçu peut porter ; `null` s'il n'en reste rien. */
function truncate(value: string | null): string | null {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return null;
  }
  return trimmed.length <= PREVIEW_MAX_LINE_LENGTH
    ? trimmed
    : trimmed.slice(0, PREVIEW_MAX_LINE_LENGTH);
}
