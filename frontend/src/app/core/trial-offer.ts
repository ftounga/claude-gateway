/**
 * L'offre d'essai, du point de vue des pages **publiques** (F-66).
 *
 * Un écran connecté n'a pas besoin de ce fichier : il lit `trialDays` et `trialTokens` sur
 * `GET /billing/subscription`, c'est-à-dire la durée et l'allocation que le serveur sert vraiment.
 * La page d'accueil, elle, s'affiche **sans authentification** et ne peut appeler aucun endpoint
 * protégé : elle a besoin d'un littéral.
 *
 * Ce fichier existe pour qu'il n'y en ait **qu'un**. Avant F-66, la durée était écrite à trois
 * endroits — 14 sur la page d'accueil, 14 dans ses encarts, 5 dans la page de facturation — pendant
 * que la configuration en servait 5. Une promesse publique tenue à 36 %, née de rien d'autre que de
 * chiffres recopiés.
 *
 * **À tenir aligné avec `app.billing.trial-days`** (backend, `application.yml`). Si les deux
 * divergent un jour, c'est l'écran **Facturation** qui dit vrai pour un client connecté : il lit la
 * valeur servie.
 */
export const ADVERTISED_TRIAL_DAYS = 14;

/**
 * Allocation d'essai affichée **en repli**, le temps que l'abonnement arrive — l'écran connecté
 * affiche ensuite `trialTokens`, la valeur réellement servie.
 *
 * Miroir de `app.quota.trial-tokens` (backend). Sa valeur fait l'objet d'une question ouverte au PO
 * (`OQ-16`) : 200 000 jetons représentent quatre à dix tours de Forge. Le jour où elle change, seul
 * ce repli d'affichage est à retoucher — l'écran, lui, suivra le serveur tout seul.
 */
export const DEFAULT_TRIAL_TOKENS = 200_000;
