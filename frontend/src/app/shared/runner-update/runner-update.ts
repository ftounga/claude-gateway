import { RunnerUpdateView } from '../../core/models/atelier.models';
import { RunnerHostPlatform } from '../../atelier/runner/runner-pairing-dialog.component';

/**
 * Les mots de la mise à jour du runner (F-111 / SF-111-01), partagés par la colonne des postes et
 * l'en-tête du client, Forge et Vigie. Fonctions pures : c'est ici que se vérifie ce qui est écrit.
 */

/** Ton de pastille de la charte (classes `badge--*` existantes — aucune couleur nouvelle). */
export type UpdateTone = 'error' | 'warning' | 'info';

/** Ce que l'en-tête écrit, ou `null` quand il n'y a rien à dire (à jour, inconnu). */
export interface UpdateNotice {
  /** Libellé long de la pastille. */
  label: string;
  /** Libellé court de la colonne des postes. */
  short: string;
  tone: UpdateTone;
  /** Vrai quand la mise à jour se fait à la main (commande à montrer). */
  manual: boolean;
}

/** Le numéro sémantique affichable (`1.0.0`), ou `null`. */
export function runnerVersionLabel(update: RunnerUpdateView | null | undefined, declared?: string | null): string | null {
  return update?.installedVersion ?? semantic(declared);
}

/** Ce que l'écran dit du runner d'un poste, ou `null` s'il est à jour ou si rien n'est comparable. */
export function updateNotice(update: RunnerUpdateView | null | undefined): UpdateNotice | null {
  if (!update) {
    return null;
  }
  const arrow = update.installedVersion && update.servedVersion
    ? ` — ${update.installedVersion} → ${update.servedVersion}` : '';
  switch (update.status) {
    case 'AVAILABLE':
      return update.required
        ? { label: `Mise à jour requise${arrow}`, short: 'Mise à jour requise', tone: 'error', manual: false }
        : { label: `Mise à jour disponible${arrow}`, short: 'Mise à jour disponible', tone: 'info', manual: false };
    case 'MANUAL_LAST_TIME':
      return {
        label: update.required
          ? 'Mise à jour requise — manuelle une dernière fois'
          : 'Runner sans mise à jour automatique : mise à jour manuelle une dernière fois',
        short: update.required ? 'Mise à jour requise' : 'Mise à jour manuelle',
        tone: update.required ? 'error' : 'warning',
        manual: true,
      };
    case 'MANUAL_JAVA':
      return {
        label: `Mise à jour manuelle requise (Java ${update.requiredJava} demandé)`,
        short: 'Mise à jour manuelle',
        tone: update.required ? 'error' : 'warning',
        manual: true,
      };
    default:
      return null;
  }
}

/** Le système d'un poste d'après ce que son runner déclare (`os.name` en minuscules). */
export function platformFromOs(os: string | null | undefined): RunnerHostPlatform {
  const value = (os ?? '').toLowerCase();
  if (value.startsWith('windows')) {
    return 'windows';
  }
  if (value.includes('mac') || value.includes('darwin')) {
    return 'macos';
  }
  return 'other';
}

/** L'explication de la dernière mise à jour manuelle, selon le cas. */
export function manualUpdateText(update: RunnerUpdateView): string {
  if (update.status === 'MANUAL_JAVA') {
    return `La nouvelle version du runner demande Java ${update.requiredJava}`
      + (update.installedJava ? `, et ce poste exécute Java ${update.installedJava}` : '')
      + '. Installez ce Java (ou le paquet autonome, qui l’embarque), téléchargez le runner avec la '
      + 'commande ci-dessous, puis relancez-le comme d’habitude.';
  }
  return 'Ce runner a été installé avant la mise à jour automatique. Arrêtez-le (Ctrl+C), téléchargez '
    + 'la nouvelle version avec la commande ci-dessous à la place de l’ancienne, puis relancez-le avec '
    + 'la même commande qu’avant — le jeton du poste est conservé, aucun nouveau code n’est demandé. '
    + 'C’est la dernière fois : ensuite, le runner se met à jour d’un clic depuis cet écran.';
}

function semantic(version: string | null | undefined): string | null {
  if (!version) {
    return null;
  }
  const dash = version.indexOf('-');
  return dash < 0 ? version : version.substring(0, dash);
}
