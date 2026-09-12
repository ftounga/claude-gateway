import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';

import { REFUSAL_SNACK_DURATION_MS } from './file-format-names';

/**
 * **Le vocabulaire du refus d'accès à la Forge** — F-85 / SF-85-04.
 *
 * <p>Même défaut que le refus de format, sur un autre objet : le produit <b>sait</b> pourquoi il
 * refuse, et ne le dit pas. La garde ({@code AtelierAccessService}) est juste ; c'est son silence
 * qui ne l'est pas — l'utilisateur clique, rien n'arrive, et il en déduit que l'outil est cassé.</p>
 *
 * <p>Ce fichier est à l'accès ce que {@code file-format-names.ts} est aux formats (SF-85-02) :
 * <b>un seul endroit</b> où un refus devient des mots et une destination. Le message était écrit
 * en clair à cinq endroits, en quatre formulations qui divergeaient déjà.</p>
 *
 * <p><b>Ce qui ne change pas</b> : la garde, les droits, et le {@code 403} du serveur. Le serveur
 * garde son message exact — il s'adresse à un appelant d'API. C'est l'écran qui traduit.</p>
 */

/** Route de la Facturation. Écrite ici une fois, jamais recopiée dans un composant. */
export const FORGE_ACCESS_BILLING_ROUTE = '/billing';

/**
 * Ancre de la section « Vous avez un code d'accès ? » de la Facturation. C'est **l'endroit exact**
 * où conduire : beaucoup de ces utilisateurs <b>ont</b> un code, reçu par courriel, et ne savent
 * pas où le mettre.
 */
export const FORGE_ACCESS_CODE_FRAGMENT = 'code-acces';

/** Ce qui est refusé — le premier des trois temps. */
export const FORGE_ACCESS_HEADLINE = "L'accès à la Forge n'est pas ouvert sur ce compte.";

/**
 * Les **deux** sorties, nommées. La seconde est celle qu'on oubliait : un code d'accès reçu par
 * courriel n'ouvre rien tant qu'on ignore où le saisir.
 */
export const FORGE_ACCESS_EXITS =
  "Deux façons de l'ouvrir : souscrire, ou saisir le code d'accès que vous avez reçu par courriel.";

/** Le message en deux temps, d'un bloc — pour une snackbar ou un texte d'erreur en ligne. */
export const FORGE_ACCESS_REFUSAL = `${FORGE_ACCESS_HEADLINE} ${FORGE_ACCESS_EXITS}`;

/** Libellé de l'action qui **conduit** — dit la destination, pas le geste. */
export const FORGE_ACCESS_ACTION_LABEL = 'Où saisir mon code';

/** Le discriminant métier posé par le serveur sur ce refus précis. */
const FORGE_ACCESS_ERROR_CODE = 'atelier_forbidden';

/**
 * Vrai si l'erreur est **le refus de la garde d'accès à la Forge**, et rien d'autre.
 *
 * <p>C'est la distinction qui manquait : sans elle, « accès refusé » et « la gateway est tombée »
 * se disent pareil, et <b>l'un des deux ment</b>. Une panne réseau ({@code status: 0}), un
 * {@code 5xx}, un {@code 409} (runner déconnecté) ou un {@code 404} (poste supprimé) ne sont
 * <b>jamais</b> reconnus ici : ils gardent leur message, qui est le bon.</p>
 *
 * <p>Un {@code 403} <b>sans corps exploitable</b> est accepté : sur les chemins de la Forge, la
 * garde est la seule source possible de {@code 403} (réponse {@code blob}, gateway antérieure).
 * Un {@code 403} qui porte un <b>autre</b> discriminant ({@code admin_forbidden},
 * {@code access_code_not_for_account}) est refusé — mieux vaut un message générique qu'un message
 * faux, la règle de SF-85-02.</p>
 */
export function isForgeAccessDenied(err: unknown): boolean {
  if (!(err instanceof HttpErrorResponse) || err.status !== 403) {
    return false;
  }
  const code = forgeErrorCode(err.error);
  return code === null || code === FORGE_ACCESS_ERROR_CODE;
}

/** Le code métier du corps d'erreur, ou `null` quand le corps n'en porte pas de lisible. */
function forgeErrorCode(body: unknown): string | null {
  if (body === null || typeof body !== 'object') {
    return null;
  }
  const code = (body as { error?: unknown }).error;
  return typeof code === 'string' && code.length > 0 ? code : null;
}

/**
 * Dit le refus **et y conduit** : une snackbar dont l'action mène à la section « code d'accès »
 * de la Facturation.
 *
 * <p>Une snackbar, et non un dialogue : un dialogue arrêterait l'utilisateur sur un geste qu'il
 * n'a pas demandé. Elle dure aussi longtemps qu'un refus de format (SF-85-02) — un message qui
 * nomme deux sorties ne se lit pas en quatre secondes.</p>
 */
export function openForgeAccessSnackBar(snackBar: MatSnackBar, router: Router): void {
  snackBar
    .open(FORGE_ACCESS_REFUSAL, FORGE_ACCESS_ACTION_LABEL, {
      duration: REFUSAL_SNACK_DURATION_MS,
      panelClass: 'snack-error',
    })
    .onAction()
    .subscribe(() => {
      void router.navigate([FORGE_ACCESS_BILLING_ROUTE], { fragment: FORGE_ACCESS_CODE_FRAGMENT });
    });
}
