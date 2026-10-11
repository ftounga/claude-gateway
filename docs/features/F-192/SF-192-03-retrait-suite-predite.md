# Mini-spec — F-192 / SF-192-03 Retrait de la suite prédite F-144

## Identifiant
`F-192 / SF-192-03` — Feature parente : F-192 « Cap : Et maintenant ? » (cadrage `CADRAGE-F-192-cap-et-maintenant.md`).
Statut : `ready` — 2026-10-11 — Branche `feat/SF-192-03-retrait-suite-predite`.

## Objectif
Retirer la suite prédite de F-144 (texte fantôme, puces sans modèle, appel séparé `next-prompt`), remplacée par les suites de l'agent principal (SF-192-01/02).

## Comportement attendu

### Cas nominal
1. **Frontend** : plus de texte fantôme dans la saisie (Tab / → / Échap n'y font plus rien de spécial) ; plus de puces sous la saisie ; plus aucun appel `POST /api/workspaces/{id}/next-prompt` en fin de tour. Le composant partagé `turn-suggestions` (et `AtelierService.nextPrompt`, `AtelierNextPrompt`) est supprimé.
2. **Backend** : le paquet `atelier.nextprompt` (contrôleur, service, propriétés, config) est **supprimé** ; la route `POST /api/workspaces/{id}/next-prompt` n'existe plus ; la clé `app.atelier.next-prompt.enabled` (`APP_ATELIER_NEXT_PROMPT_ENABLED`) est retirée de `application.yml`.
3. `PRODUCT_SPEC.md` : F-144 marquée « Remplacée par F-192 ».

### Arbitrage « supprimer ou couper par défaut »
**Supprimer** (choix laissé au dev par le cadrage) : la remplaçante est livrée et en place (SF-192-02) ; garder un appel modèle payant coupé par défaut n'ajoute que du code mort. Réversible par `git revert` de cette PR.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Ancien onglet encore ouvert qui appelle `next-prompt` | 404 (route absente) ; l'ancien écran ignorait déjà les échecs en silence |
| Variable `APP_ATELIER_NEXT_PROMPT_ENABLED` encore posée en prod | ignorée (aucune propriété ne la lit) |

## Contraintes de validation
Aucune nouvelle donnée.

## Critères d'acceptation
- [ ] Aucune occurrence de `next-prompt`, `nextPrompt`, `NextPrompt`, `turn-suggestions`, `ghostPrompt` dans le code (`backend/src`, `frontend/src`).
- [ ] La fin d'un tour n'émet aucune requête réseau de suggestion.
- [ ] Le placeholder de la saisie redevient le texte fixe.
- [ ] Le contexte Spring démarre (suite backend complète verte) ; `ng build` et la suite frontend verts.

## Plan de test
- Suppression des tests propres à F-144 (`NextPromptServiceTest`, `NextPromptApiIntegrationTest`, `turn-suggestions*.spec.ts`, `terminal-suite-predite.spec.ts`).
- Ajout `terminal-et-maintenant.spec.ts` : la fin d'un tour n'émet **aucune** requête HTTP de suggestion ; le placeholder est le texte fixe.
- Suite backend complète (contexte Spring) + suite frontend complète.
- Isolation : sans objet (suppression d'une route ; aucun nouvel accès données).

## Impacts
- Tables : aucune. Endpoints : **suppression** de `POST /api/workspaces/{id}/next-prompt`.
- Composants : `atelier-terminal.component.*`, `atelier.service.ts`, `atelier.models.ts`, `shared/turn-suggestions/*` (supprimé), `atelier-terminal-mentions.component.scss` (style du fantôme), paquet backend `atelier.nextprompt` (supprimé), `application.yml`, `PRODUCT_SPEC.md`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ ; Plans/limites ☐ ; Navigation ☐.

## Hors périmètre
Les suites F-192 elles-mêmes (SF-192-01/02) ; le modèle rapide `ANTHROPIC_FAST_MODEL`, toujours utilisé ailleurs.
