import { RunnerKillResult } from '../../core/models/atelier.models';

/**
 * Ce que le coupe-circuit a **réellement** fait (F-82 / SF-82-02, partagé par SF-82-05).
 *
 * <p>Jamais ce qu'on lui a demandé : couper une liaison déjà coupée n'est pas une erreur — le
 * coupe-circuit est idempotent —, mais annoncer « coupée » alors que zéro jeton a été révoqué
 * ferait croire à un geste qui n'a rien eu à faire.</p>
 *
 * <p>La phrase sur le runner qui <b>continue de tourner</b> est obligatoire, et c'est pour elle que
 * cette fonction vit ici plutôt qu'en double : le terminal promettait moins que ce qu'il faisait,
 * et deux formulations divergent à la première retouche.</p>
 */
export function killHostSuccessMessage(hostName: string, result: RunnerKillResult): string {
  const returned = result.workspacesReturned > 0
    ? ` ${result.workspacesReturned} projet(s) ramené(s) au bac à sable.`
    : '';
  if (result.revokedTokens === 0 && !result.disconnected) {
    return `« ${hostName} » n'avait plus de liaison ouverte.${returned}`
      + ' Le runner, lui, tourne peut-être encore sur la machine.';
  }
  return `Liaison coupée avec « ${hostName} » : ${result.revokedTokens} jeton(s) révoqué(s).`
    + `${returned} Le runner continue de tourner sur la machine tant qu'il n'y est pas arrêté.`;
}
