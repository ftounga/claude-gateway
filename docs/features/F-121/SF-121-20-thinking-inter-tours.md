# Mini-spec — F-121 / SF-121-20 — Thinking préservé entre les tours : vérification et clôture

## Identifiant

`F-121 / SF-121-20`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart **Lot 3 / P4** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §4 :

> **F-121-20** — **Thinking préservé inter-tours** (dernier tour seulement) — optionnel, à peser (coût
> jetons, signatures qui expirent). (P4)

## Statut

`done` — **clos sans développement de capacité** : l'écart est **couvert par F-134 / SF-134-04**, et
plus largement que ce que le cadrage proposait. Deux propriétés jusqu'ici non gardées de bout en bout
sont **verrouillées par des témoins**.

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-20-thinking-inter-tours`

---

## Objectif

Vérifier, critère par critère, que les blocs de raisonnement **survivent au tour** et repartent au
fournisseur aux tours suivants ; verrouiller ce qui ne l'était pas ; et clore l'écart sans écrire de
capacité nouvelle.

---

## Contexte — l'écart a été fermé ailleurs, entre-temps

Le cadrage F-121 (2026-09-15) décrivait un raisonnement qui « vit le temps d'un tour » et proposait,
en option de priorité 4, de préserver celui du **dernier tour**. C'était exact à la date du cadrage :
`AtelierToolTrace` ne gardait alors rien du raisonnement.

**F-134 / SF-134-04 a renversé cette décision**, et pour une raison mesurée : pendant un tour, le
message assistant envoyé au fournisseur **commence** par ses blocs de raisonnement, et c'est cet
ensemble qui est mis en cache. En les omettant au rejeu, on renvoyait un ruban qui différait du sien
**dès le premier bloc de chaque tour** — tout ce qui suivait était réécrit au **double** du tarif
d'entrée (mesuré : 23 % de contexte relu sur un fil de deux tours, 98 % du coût d'un tour en écriture
de cache).

L'état de `main` est donc **au-delà** de l'écart :

| Critère du cadrage | État vérifié |
|---|---|
| Raisonnement préservé après la fin du tour | **Oui** — `AtelierToolTrace.Thought` (texte, signature, charge expurgée) est persisté dans la trajectoire du message assistant (`AtelierChatServiceReasoningTest.keepsTheReasoningInTheTraceSoTheNextTurnCanReplayIt`). |
| Rejoué au(x) tour(s) suivant(s) | **Oui** — `AtelierToolTrace.replay()` réémet les `Thought` en **tête** du message assistant, avant le texte et les `tool_use`. |
| « Dernier tour seulement » | **Dépassé** — la préservation vaut pour **toute la fenêtre tracée** (`replayedTraceTurns`, défaut 12, par paliers SF-134-01), pas seulement le dernier tour. |
| Bloc signé jamais retouché | **Oui** — recopié tel quel ; `AnthropicAgentProvider.toApiBlock` réémet `thinking`/`signature` sans reconstruction, et omet une signature absente plutôt que d'envoyer `null`. |
| Raisonnement expurgé | **Oui** — `redacted_thinking` réémis sans interprétation, et prioritaire sur les deux autres champs. |
| Jamais exposé hors de la boucle | **Oui** — ni dans la réponse rendue, ni dans le relevé du tour (`theReasoningIsNeverExposedOutsideTheProviderLoop`). |

**Les deux réserves du cadrage sont levées, et non ignorées :**

- **Coût jetons** : la réserve supposait que renvoyer le raisonnement coûtait plus cher. La mesure de
  F-134 a montré l'inverse — l'omettre **cassait le cache** et coûtait le double sur tout ce qui
  suivait. Le texte du raisonnement est d'ailleurs le plus souvent vide ; c'est la **signature** qui
  voyage.
- **Signatures qui expirent** : elles ne sont ni recalculées ni interprétées par la gateway, et le
  périmètre rejoué est **borné** par la fenêtre de traces et par la compaction. Un bloc refusé n'est
  pas un risque silencieux : il donnerait une erreur franche du fournisseur, et le filet réactif de
  F-117 / SF-117-02 couvre déjà le rejeu qui déborde.

### Ce qui reste volontairement non préservé

Le raisonnement de la **dernière étape** d'un tour — celle qui rend la réponse finale, **sans appel
d'outil** — n'est pas rejoué : `replay()` écarte une itération dont aucun `tool_use` n'est apparié à
un `tool_result` (l'API refuse l'un sans l'autre), et l'historique rejoue ce message par son **texte**.
C'est un **choix conservé**, pas un oubli :

1. le fournisseur n'exige pas les blocs de raisonnement des tours passés — il exige qu'ils ne soient
   pas **retouchés** ;
2. la forme du rejeu a été mesurée et arbitrée par F-134 ; la modifier sans mesure nouvelle
   toucherait le préfixe caché, là où une erreur coûte 98 % du coût d'un tour.

---

## Comportement attendu

### Nominal

1. Un tour dont une étape a produit un bloc signé écrit ce bloc dans sa trajectoire.
2. Au tour suivant, le rejeu de cette trajectoire remet le bloc **en tête** du message assistant,
   **avant** le texte et les `tool_use`, signature comprise et **inchangée**.
3. Un bloc **expurgé** repart en `redacted_thinking`, sans interprétation.
4. Une trajectoire sans raisonnement (traces antérieures à F-134, ou tour sans raisonnement) rejoue
   exactement comme avant : aucun bloc de raisonnement, aucune erreur.

### Cas d'erreur / limites

- **Trajectoire illisible** : `AtelierToolTrace.fromJson` rend une trajectoire vide, le message
  retombe sur son texte seul (comportement existant, inchangé).
- **Bloc vide** (ni signature, ni charge expurgée) : ignoré au rejeu — un bloc sans signature serait
  refusé par le fournisseur.
- **Signature périmée / bloc refusé** : erreur franche du fournisseur, pas de corruption silencieuse ;
  aucun chemin de rattrapage nouveau n'est introduit ici.

---

## Critères d'acceptation

1. Le rejeu d'un historique dont la trajectoire porte un `Thought` signé fait parvenir au fournisseur
   un bloc `Reasoning` portant **la même** signature, **en première position** du message assistant.
2. Un `Thought` **expurgé** parvient au fournisseur en `RedactedReasoning`, charge inchangée.
3. Un historique sans raisonnement n'en fabrique aucun (témoin existant conservé).
4. Le raisonnement rejoué ne sort toujours pas de la boucle (témoin existant conservé).
5. **Aucun code de production n'est modifié** par cette subfeature.

---

## Plan de test minimal

### Unitaires / intégration boucle (`AtelierChatServiceReasoningTest`)

- `aReplayedHistoryCarriesTheSignedReasoningOfItsTracedTurns` (**nouveau**) : la signature d'un tour
  **précédent** arrive au fournisseur au tour suivant, en tête du message assistant.
- `aReplayedHistoryCarriesRedactedReasoningUntouched` (**nouveau**) : la charge expurgée d'un tour
  précédent repart telle quelle.
- `keepsTheReasoningInTheTraceSoTheNextTurnCanReplayIt`, `aReplayedHistoryCarriesNoReasoningBlock`,
  `theReasoningIsNeverExposedOutsideTheProviderLoop` (existants) : persistance, absence de fabrication,
  non-exposition.

### Isolation utilisateur

- Sans objet : aucun accès données nouveau. Le rejeu part de l'historique déjà filtré
  `(workspaceId, userId)` par `buildReplayMessages` — chemin inchangé.

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : aucun. **Frontend** : aucun. **Protocole runner** : aucun.
- **Code de production** : **aucun** (clôture par vérification).
- **Tests** : `AtelierChatServiceReasoningTest` (deux témoins) ; commentaire de classe périmé corrigé.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | rejeu issu de `buildReplayMessages`, déjà filtré `(workspaceId, userId)` |
| Plans / limites | Non | aucun changement de consommation : rien n'est ajouté à l'appel, la forme du rejeu est celle de `main` |
| Navigation / routing | Non | — |

---

## Hors périmètre

- **Rejouer le raisonnement de l'étape finale** (celle sans appel d'outil) : toucherait le préfixe
  caché arbitré par F-134 sans mesure nouvelle. Conservé en l'état, motivé ci-dessus.
- **`thinking.display`** et tout rendu du raisonnement à l'écran : le raisonnement ne sort pas de la
  boucle, c'est une décision de F-39 / SF-39-10 qui n'est pas rouverte ici.
- **NotebookEdit**, cité en fin du même point P4 du cadrage : outil sans rapport avec le thinking, non
  demandé, et sans usage identifié sur le produit — reste hors périmètre.
