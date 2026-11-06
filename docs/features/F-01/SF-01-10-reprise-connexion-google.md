# Mini-spec — F-01 / SF-01-10 — Connexion Google : reprise quand le retour atterrit ailleurs

## Statut
`in-review` — 2026-11-07 — branche `feat/SF-01-10-reprise-connexion-google`

## Objectif
> Sur Android, quand le retour de Google est capté par une autre application que celle du départ (l'application
> installée), la connexion se relance d'elle-même dans cette application au lieu d'afficher « Connexion Google échouée ».

## Constat (prod, 2026-11-06 22:56-22:58 UTC, trace SF-01-09)
3 échecs `authorization_request_not_found`, cookie de départ absent : départ dans le navigateur, retour dans l'appli
installée. Le PO relance à la main depuis l'écran d'échec → succès (même application de bout en bout).

## Comportement
- `#error=authorization_request_not_found` : l'écran de retour relance `/api/oauth2/authorization/google` une fois ;
  Google, déjà choisi, revient aussitôt → connecté.
- Garde anti-boucle : une reprise par minute (`sessionStorage`), sinon l'écran d'échec existant.
- Aucun affaiblissement : nouveau départ complet (nouvel état, nouveau cookie) ; le contrôle d'état reste intact.

## Critères d'acceptation
- [ ] Une reprise permise, une seconde refusée dans la minute, permise après (tests unitaires).
- [ ] Succès : repère effacé ; autres erreurs : écran d'échec inchangé.
- [ ] Essai réel du PO : connexion depuis le navigateur sans écran d'échec.

## Plan de test
`oauth-callback.component.spec.ts` (2) ; suite `src/app/auth` verte. Isolation : sans objet.

## Impacts
`OauthCallbackComponent` seul. Préoccupation **Navigation** : route `/auth/callback` inchangée, seule l'issue
`authorization_request_not_found` change. Aucun endpoint, aucune table.

## Hors périmètre
Rendre l'état OAuth indépendant du cookie (affaiblirait la protection anti-CSRF de connexion).
