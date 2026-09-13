import { HttpErrorResponse } from '@angular/common/http';

import { RadarVerification, RadarVerificationCheck } from '../../core/models/radar.models';
import type { RunnerHostPlatform } from '../../atelier/runner/runner-pairing-dialog.component';
import { httpErrorMessage } from '../../shared/http-error.util';

/**
 * **La vérification guidée, en fonctions pures** (F-100 / SF-100-06) : les étapes, l'état de chaque case,
 * le remède exact d'une case vide ou d'un refus, les commandes de mise à jour du runner. Sans Angular ni
 * HTTP (hors typage de l'erreur).
 */

/** Temps entre la fin d'un appel et le suivant. */
export const VERIFICATION_POLL_MS = 5000;

/** Les quatre cases, dans l'ordre où l'utilisateur les coche. */
export type VerificationKey = 'session' | 'conversations' | 'meetings' | 'transcripts';

/** Ce que la ligne dit de sa case. */
export type LineState = 'seen' | 'waiting' | 'remedy';

/** Une commande à copier. */
export interface RemedyCommand {
  title: string;
  content: string;
}

/** Le remède d'une case ou d'un refus : une phrase, et parfois des commandes exactes. */
export interface Remedy {
  text: string;
  commands: RemedyCommand[];
}

/** Une étape affichée. */
export interface VerificationLine {
  key: VerificationKey;
  title: string;
  state: LineState;
  sentence: string;
  remedy: Remedy | null;
}

const TITLES: Record<VerificationKey, string> = {
  session: 'Session Microsoft : la fenêtre Chrome de Teams est reliée au runner',
  conversations: '1. Ouvrez un fil de conversation dans la fenêtre Chrome de Teams',
  meetings: '2. Ouvrez une réunion passée dans le calendrier de Teams',
  transcripts: "3. Ouvrez l'onglet Transcription de cette réunion",
};

const KEYS: readonly VerificationKey[] = ['session', 'conversations', 'meetings', 'transcripts'];

/**
 * Les commandes qui téléchargent le runner à jour, pour le système du navigateur. Les routes sont
 * publiques (F-38 / F-44) ; sous macOS l'architecture est inconnue, les deux paquets sont donnés.
 */
export function runnerUpdateCommands(platform: RunnerHostPlatform, origin: string): RemedyCommand[] {
  const base = `${origin.replace(/\/+$/, '')}/api/runner/download`;
  const curl = platform === 'windows' ? 'curl.exe' : 'curl';
  const jar: RemedyCommand = { title: 'Runner (jar)', content: `${curl} -fL -o claude-runner.jar ${base}` };
  switch (platform) {
    case 'windows':
      return [
        { title: 'Paquet Windows', content: `curl.exe -fL -o claude-runner-windows-x64.zip ${base}/windows` },
        jar,
      ];
    case 'macos':
      return [
        { title: 'Paquet macOS (Apple Silicon)', content: `curl -fL -o claude-runner-macos-aarch64.tar.gz ${base}/macos-aarch64` },
        { title: 'Paquet macOS (Intel)', content: `curl -fL -o claude-runner-macos-x64.tar.gz ${base}/macos-x64` },
        jar,
      ];
    default:
      return [jar];
  }
}

/** « Mettez le runner à jour » et comment. */
export function updateRemedy(platform: RunnerHostPlatform, origin: string, why: string): Remedy {
  return {
    text: `${why} Mettez le runner à jour : téléchargez-le avec la commande ci-dessous, à la place de l'ancien, `
      + 'puis relancez-le comme d\'habitude.',
    commands: runnerUpdateCommands(platform, origin),
  };
}

const NO_TEAMS_REMEDY = 'Si le runner a été lancé avec --no-teams, relancez-le sans cette option.';

/** Le remède d'une case vide, ou `null` quand il suffit de suivre l'étape. */
export function checkRemedy(key: VerificationKey, check: RadarVerificationCheck,
  platform: RunnerHostPlatform, origin: string): Remedy | null {
  if (check.ok) {
    return null;
  }
  if (key === 'session') {
    switch (check.state) {
      case 'BROWSER_NOT_DETECTED':
        return {
          text: "La fenêtre Chrome de Teams n'est pas reliée au runner. Lancez-la avec la commande donnée par le runner, "
            + 'connectez-vous à Teams dans cette fenêtre, puis laissez ce dialogue ouvert :',
          commands: check.sentence ? [{ title: 'Donné par le runner', content: check.sentence }] : [],
        };
      case 'NOT_SIGNED_IN':
      case 'TEAMS_NOT_OPEN':
        return { text: check.sentence || 'Ouvrez Teams et connectez-vous dans la fenêtre Chrome reliée au runner.', commands: [] };
      case 'TEAMS_CHANGED':
        return updateRemedy(platform, origin, "Teams a changé : l'adaptateur du runner ne lit plus ce que Teams renvoie.");
      case 'TEAMS_DISABLED':
        return { text: `Le volet Teams est désactivé sur ce poste. ${NO_TEAMS_REMEDY}`, commands: [] };
      default:
        return null;
    }
  }
  if (key === 'transcripts') {
    switch (check.state) {
      case 'DISABLED_OR_NOT_PRODUCED':
        return {
          text: "La transcription est désactivée par votre client, ou n'a pas été produite : le Radar le dira au lieu de "
            + "présenter ces réunions comme vides. Rien à faire de votre côté ; l'organisateur peut l'activer.",
          commands: [],
        };
      case 'ACCESS_DENIED':
        return {
          text: 'Votre rôle dans cette réunion ne donne pas accès à sa transcription : essayez une réunion que vous avez '
            + "organisée, ou demandez l'accès à l'organisateur.",
          commands: [],
        };
      default:
        return null;
    }
  }
  return null;
}

/** Les quatre lignes du dialogue. */
export function verificationLines(verification: RadarVerification | null, platform: RunnerHostPlatform,
  origin: string): VerificationLine[] {
  return KEYS.map((key) => {
    const check = verification?.[key] ?? null;
    const remedy = check ? checkRemedy(key, check, platform, origin) : null;
    const sentence = check?.sentence ?? '';
    // La phrase du runner déjà reprise par le remède (texte ou commande) n'est pas dite deux fois.
    const repeated = !!remedy && (remedy.text === sentence || remedy.commands.some((c) => c.content === sentence));
    return {
      key,
      title: TITLES[key],
      state: check?.ok ? 'seen' : remedy ? 'remedy' : 'waiting',
      sentence: repeated ? '' : sentence,
      remedy,
    };
  });
}

/** Un refus qui arrête la vérification : ce qu'on en dit. */
export function refusalRemedy(err: unknown, platform: RunnerHostPlatform, origin: string): Remedy {
  if (err instanceof HttpErrorResponse) {
    const code = (err.error as { error?: unknown } | null)?.error;
    const message = httpErrorMessage(err, '');
    if (err.status === 409 && code === 'radar_teams_disabled') {
      const remedy = updateRemedy(platform, origin, 'Le runner de ce poste ne sait pas encore vérifier : il est trop ancien, '
        + 'ou son volet Teams est désactivé.');
      return { text: `${remedy.text} ${NO_TEAMS_REMEDY}`, commands: remedy.commands };
    }
    if (err.status === 409 && code === 'radar_runner_unavailable' && /mettez le runner à jour/i.test(message)) {
      return updateRemedy(platform, origin, "Le runner de ce poste a répondu dans une forme que la gateway ne lit pas.");
    }
    if (err.status === 409 && code === 'radar_runner_unavailable') {
      return { text: message || 'Poste hors ligne : lancez le runner, puis réessayez.', commands: [] };
    }
    if (err.status === 403 || err.status === 404 || err.status === 409) {
      return { text: "La vérification n'est pas disponible pour ce client.", commands: [] };
    }
  }
  return { text: "La gateway n'a pas répondu. Rien n'est perdu : réessayez.", commands: [] };
}
