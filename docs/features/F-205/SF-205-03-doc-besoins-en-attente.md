# Mini-spec — F-205 / SF-205-03 — Doc ops : récupérer et marquer les besoins en attente

## Identifiant
`F-205 / SF-205-03`. Dépend de SF-205-01 (#1200) et SF-205-02 (#1201), mergées. Branche :
`feat/SF-205-03-doc-besoins`. Date : 2026-10-16.

## Objectif
Que Claude Code sache, sans relire le code, récupérer les besoins en attente, les cadrer et les marquer.

## Comportement attendu
`docs/ops/besoins-en-attente.md` décrit : le cycle de vie des statuts ; les appels `curl` des routes admin
(liste, détail, pièce, `PATCH`) avec le jeton de session passé par variable d'environnement (jamais écrit
sur disque) ; les requêtes SQL lecture seule (`q-prod-ro.sh`) ; la procédure de cadrage (vérifier
l'existant, regrouper, cadrage + PRODUCT_SPEC après validation PO, `CADRE`/`LIVRE`).

### Cas d'erreur
| Situation | Documenté |
|---|---|
| Codes 400/403/404/409 | oui, avec leur sens |
| Pièce `FAILED` | redéposer le fichier ; pas de relance automatique |

## Critères d'acceptation
- [ ] Les routes et le corps `PATCH` documentés correspondent au contrat de SF-205-01.
- [ ] Aucun secret dans le document.

## Plan de test
Relecture croisée avec `AppRequestAdminController` / `AppRequestApiIntegrationTest` (mêmes routes, mêmes
champs). Pas de code.

## Impacts
`docs/ops/besoins-en-attente.md` (nouveau). Aucune table, aucun endpoint.

## Hors périmètre
Script automatisé de cadrage ; création automatique de feature.
