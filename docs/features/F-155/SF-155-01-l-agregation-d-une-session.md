# Mini-spec — F-155 / SF-155-01 — L'agrégation d'une session

## Identifiant
`F-155 / SF-155-01` — feature parente `F-155`

## Objectif
Savoir, pour une session, **ce qui a été fait**, **ce que ça a coûté**, et **où l'argent est parti** —
à partir de ce qui est déjà mesuré.

## La demande
> PO : *« Qu'il analyse ce qui a été fait, ce que ça a coûté, s'il y a des choses à améliorer. »*

Aujourd'hui la matière existe (`usage_turns`, `runner_audit`) mais **personne ne la regarde à
l'échelle d'une session**. On sait qu'une semaine a coûté cher ; on ne sait pas **quelle session**,
ni **quel tour**.

## Ce qu'est une session, ici
La fenêtre entre la **frontière de rejeu** du projet (`chat_thread_started_at`, posée par « Nouveau
départ », F-117) et l'instant demandé. Pas une notion nouvelle : **celle que l'utilisateur ferme
lui-même** en repartant de zéro. Sans frontière posée, la session est **tout le projet**.

## Comportement attendu
1. Le relevé porte sur **un projet** et **une fenêtre de temps**, et rien d'autre.
2. **Ce qui a été fait** : nombre de tours, **durée réelle** (premier → dernier tour), appels
   d'outils, **fichiers écrits** (distincts), et le compte des tours **en échec**.
3. **Ce que ça a coûté** : euros (conversion d'affichage déjà configurée), jetons d'entrée, de
   sortie, **de cache lu et écrit**, et la **part du cache** — le levier mesuré par F-134.
4. **Où l'argent est parti** : les **tours les plus chers** (horodatage, modèle, coût, jetons) et
   les **outils les plus lourds** (appels, durée cumulée, échecs). Bornés : trois de chaque, pas un
   inventaire.
5. Un relevé **vide** est un relevé **valide** : zéro tour, zéro euro, aucun classement. Une session
   sans activité ne doit pas produire d'erreur.
6. Le relevé **ne juge rien**. Il compte. Les suggestions sont **SF-155-02** — et les mélanger
   empêcherait de tester les chiffres sans appeler un modèle.

| Cas d'erreur | Comportement |
|---|---|
| Projet non possédé | **404 indiscernable** (`requireOwned` d'abord) |
| Fenêtre inversée (fin avant début) | relevé vide, pas d'exception |
| Coût absent sur un tour (`provider_cost_usd` nul) | compté comme zéro euro, et le relevé dit **combien** de tours sont sans coût connu — sinon le total paraîtrait faux |

## Critères d'acceptation
- [ ] Le relevé compte les tours, la durée, les outils, les fichiers écrits **distincts**, les échecs.
- [ ] Il donne les jetons des quatre natures et la **part du cache**, arrondie au point.
- [ ] Il convertit en euros avec le taux d'affichage configuré, **jamais** un taux en dur.
- [ ] Il classe les **3** tours les plus chers et les **3** outils les plus lourds.
- [ ] Il **dit combien de tours** sont sans coût connu.
- [ ] Un projet sans activité rend un relevé **vide**, pas une erreur.
- [ ] **ISOLATION** : `requireOwned` d'abord ; toute lecture filtre `user_id` **et** `workspace_id`.

## Hors scope
Les **suggestions** (**SF-155-02**) · le **déclenchement** (**SF-155-03**) · l'**écran** et
l'artefact gardé (**SF-155-04**) · le renvoi vers F-156 (**SF-155-05**) · tout appel modèle · toute
lecture du transcript.

## Technique
| Élément | Changement |
|---|---|
| `SessionLedger` (record) | le relevé : ce qui a été fait, ce que ça a coûté, où c'est parti |
| `SessionLedgerService` | l'agrégation, sous isolation |
| `UsageTurnRepository` | lecture des tours d'un projet sur une fenêtre |
| `RunnerAuditRepository` | lecture des appels d'outils d'un projet sur une fenêtre |

**Aucune migration** : tout est déjà mesuré. C'est le point de la feature.

## Plan de test
- [ ] Tours, durée, outils, fichiers distincts, échecs — sur un jeu de données construit.
- [ ] Jetons et part du cache ; conversion en euros au taux configuré.
- [ ] Classements bornés à 3, et triés par coût / durée décroissants.
- [ ] Tours sans coût connu : comptés à part, total inchangé.
- [ ] Fenêtre vide et fenêtre inversée → relevé vide.
- [ ] **ISOLATION** : projet d'un autre compte → 404, aucune lecture.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint ici — le service est appelé par SF-155-03/04 |
| **Contexte tenant** | **oui** | `SessionLedgerService.of(...)` appelle `WorkspaceService.requireOwned` **en premier** ; les deux lectures (`UsageTurnRepository`, `RunnerAuditRepository`) portent `user_id` **et** `workspace_id`. Aucune méthode ne lit par projet seul. |
| Plans / limites | non | aucun appel fournisseur, aucun jeton consommé — c'est une lecture |
| Navigation / routing | non | aucune route |
