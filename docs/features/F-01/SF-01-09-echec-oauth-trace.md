# Mini-spec — F-01 / SF-01-09 — Échec du retour Google : cause tracée, SPA sur son écran d'échec

## Statut
`in-review` — 2026-11-06 — branche `feat/SF-01-09-echec-oauth-trace`

## Objectif
> Quand la connexion Google échoue au retour, la cause est journalisée et le PO revient sur l'écran d'échec de la SPA,
> au lieu d'une erreur muette de l'API (`/api/login?error` → « No static resource login »).

## Constat (prod, 2026-11-06 22:34-22:35 UTC)
4 retours Google en échec, aucune cause en journal ; identifiants Google du serveur vérifiés valides (`invalid_grant`
sur un code factice).

## Comportement
- `OAuth2LoginFailureHandler` : WARN `code=<code OAuth2>`, présence du cookie `oauth2_auth_request`, classe de
  l'exception — jamais de jeton ni de code d'autorisation ; redirection `${frontend}/auth/callback#error=<code>`
  (écran d'échec existant, `OauthCallbackComponent`).
- Exception non OAuth2 : `oauth_failed`.

## Critères d'acceptation
- [ ] Redirection SPA avec le code OAuth2 ; code générique sinon (tests unitaires).
- [ ] Contexte Spring démarre avec Google configuré (`OAuth2LoginEnabledIntegrationTest`).
- [ ] En prod, un échec écrit une ligne WARN lisible dans CloudWatch.

## Plan de test
Unitaires `OAuth2LoginFailureHandlerTest` (2) ; non-régression `OAuth2LoginEnabledIntegrationTest`,
`OAuth2LoginSuccessHandlerTest`, `HttpCookieOAuth2AuthorizationRequestRepositoryTest`. Isolation : sans objet.

## Impacts
`OAuth2LoginFailureHandler` (nouveau), `OAuth2ClientConfig`, `SecurityConfig`. Préoccupation **Auth** : seul le flux
`oauth2Login` change (échec) ; JWT, auth locale, runner, MCP inchangés (chaîne et filtres identiques). Aucune table.

## Hors périmètre
Corriger la cause elle-même (inconnue avant trace).
