# Mini-spec — F-39 / SF-39-03 — La mémoire de ce qui a été fait

## Identifiant

`F-39 / SF-39-03`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-03-memoire-trajectoire`

---

## Objectif

> Conserver la **trajectoire d'outils** d'un tour — ce qui a été appelé, avec quels arguments, et ce
> que ça a répondu — et la rejouer, bornée, au message suivant.

---

## Déclencheur

Lot 2 du cadrage F-39. Aujourd'hui l'historique rejoué auprès du fournisseur est **du texte seul** :
les messages `USER` et la réponse finale `ASSISTANT`. Tout ce que l'agent a fait pendant le tour —
les commandes lancées, les fichiers lus, les sorties obtenues — disparaît dès que le tour se termine.

Conséquence mesurée sur l'usage réel (sessions de 2 à 5 jours, 13,8 outils par demande en moyenne) :
au message suivant, l'agent **refait** ce qu'il vient de faire. Il relit les fichiers qu'il a déjà
lus, relance les commandes dont il a déjà la sortie, et redécouvre un projet qu'il connaissait
trente secondes plus tôt. C'est la dépense la plus bête du chantier : on paie deux fois le même
travail, et l'utilisateur attend deux fois.

---

## Comportement attendu

### Cas nominal

À la fin d'un tour, le message `ASSISTANT` persisté porte, à côté de son texte, la **trajectoire**
du tour : pour chaque itération, le commentaire de l'agent, les appels d'outils (nom + arguments)
et leurs résultats.

Au tour suivant, l'historique rejoué auprès du fournisseur restitue cette trajectoire sous sa forme
native — un message assistant portant les `tool_use`, puis un message portant les `tool_result` —
au lieu du seul texte final. L'agent retrouve ce qu'il a fait, comme s'il n'avait jamais été
interrompu.

### Ce qui borne le rejeu

Trois bornes, toutes explicites, parce qu'un historique non borné finit par coûter plus cher que le
travail lui-même :

| Borne | Valeur | Raison |
|---|---|---|
| Résultat d'un outil conservé | **4 000 caractères** | Une sortie de commande peut faire 128 Kio ; sa fin (le verdict) suffit à la mémoire, le tour en cours l'a eue en entier |
| Trajectoire d'un tour | **40 000 caractères** | Au-delà, les étapes les plus anciennes du tour sont abandonnées, les plus récentes conservées |
| Tours rejoués avec leur trajectoire | **5 derniers** | Les tours plus anciens restent rejoués en texte, comme aujourd'hui |

Un résultat coupé garde sa **fin** et non son début : c'est là que se trouvent le code de sortie et
le message d'erreur. La coupe est signalée par un marqueur en tête, jamais silencieuse.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Trajectoire illisible en base (ancienne, tronquée) | Le message est rejoué **en texte seul**, comme avant ; jamais d'exception | 200 |
| Message antérieur à cette subfeature (colonne nulle) | Rejoué en texte seul — non-régression complète | 200 |
| Tour interrompu au milieu d'une itération | Seules les itérations **complètes** (appels *et* résultats) sont conservées : l'API refuse un `tool_use` sans `tool_result` | 200 |
| Sérialisation impossible | Le tour est persisté **sans** trajectoire ; la réponse de l'agent n'est jamais perdue pour autant | 200 |

### Ce que l'écran montre

Rien de nouveau. La trajectoire est une donnée de **rejeu**, pas d'affichage : l'endpoint
d'historique renvoie exactement les mêmes champs qu'avant, la transcription Terminal
(`terminal_json`, SF-30-09) restant seule responsable de ce que l'utilisateur relit.

---

## Critères d'acceptation

- [ ] Un tour avec appels d'outils persiste sa trajectoire sur le message `ASSISTANT`.
- [ ] Un tour sans appel d'outil ne persiste **aucune** trajectoire (colonne nulle).
- [ ] Le tour suivant rejoue les `tool_use` / `tool_result` du tour précédent, dans l'ordre.
- [ ] Chaque `tool_use` rejoué a son `tool_result` apparié — jamais d'appel orphelin.
- [ ] Un résultat de plus de 4 000 caractères est conservé par sa **fin**, avec marqueur de coupe.
- [ ] Une trajectoire dépassant 40 000 caractères abandonne ses étapes les plus anciennes.
- [ ] Au plus les 5 derniers tours sont rejoués avec leur trajectoire.
- [ ] Une trajectoire illisible fait retomber le message sur un rejeu en texte seul.
- [ ] L'historique exposé par l'API ne contient pas la trajectoire.
- [ ] Isolation `user_id` : la trajectoire est lue et écrite par les mêmes requêtes filtrées qu'avant.

---

## Périmètre

### Hors scope (explicite)

- La **reprise de fil** et son choix explicite (D5) — **SF-39-04**.
- La compaction / édition de contexte quand l'historique devient trop long — lot 6 (SF-39-11).
- Le chemin Managed Agents, dont la mémoire vit dans la session du fournisseur — non touché.
- Tout affichage de la trajectoire dans l'écran : la transcription Terminal existe déjà pour ça.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| `tool_trace` | Non | JSON, ≤ 40 000 caractères | Étapes les plus anciennes abandonnées si dépassement |
| Résultat d'outil conservé | — | ≤ 4 000 caractères | Fin conservée, marqueur `… (début tronqué)` |
| Tours rejoués avec trajectoire | — | 5 | Les plus récents |

---

## Technique

### Endpoint(s)

Aucun. Contrat inchangé, y compris `GET /workspaces/{id}/chat`.

### Tables impactées

| Table | Changement |
|---|---|
| `atelier_messages` | Nouvelle colonne `tool_trace` (texte, nullable) |

### Migration Liquibase

- [x] `050-atelier-messages-tool-trace.xml` — `addColumn` nullable, réversible (rollback automatique),
      déclinée PostgreSQL / H2 comme `041`.

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierToolTrace` | **Nouveau** — trajectoire d'un tour, sérialisation JSON bornée, reconstruction en messages d'agent |
| `atelier/AtelierMessage` | Nouveau champ `toolTrace` |
| `atelier/AtelierChatService` | Capture des étapes dans la boucle, persistance, rejeu borné dans `replayableHistory` |

### Composants Angular

Aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | La trajectoire vit sur `atelier_messages`, déjà porteuse de `user_id`. Composants revus : `AtelierMessageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc` (seul chemin de lecture, filtré), `AtelierChatService.runLoop` (écriture avec `userId`/`workspaceId` du tour), `AtelierMessageResponse` (n'expose pas la colonne). Aucune nouvelle requête. |
| **Plans / limites** | **Oui** | Le rejeu augmente les tokens d'entrée d'un tour, donc le quota décompté (F-10) et le budget de session (F-36). C'est l'effet voulu — et il reste borné par les trois bornes ci-dessus. Composants revus : `QuotaService.recordUsage` / `assertWithinQuota` (règle inchangée), `AtelierProperties` (plafond d'étapes inchangé). |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierToolTraceTest` — aller-retour JSON d'une trajectoire.
- [ ] `AtelierToolTraceTest` — résultat trop long conservé par sa fin, avec marqueur.
- [ ] `AtelierToolTraceTest` — trajectoire trop longue : étapes anciennes abandonnées, récentes gardées.
- [ ] `AtelierToolTraceTest` — JSON illisible ⇒ trajectoire vide, aucune exception.
- [ ] `AtelierChatServiceMemoryTest` — un tour avec outils persiste sa trajectoire.
- [ ] `AtelierChatServiceMemoryTest` — un tour sans outil ne persiste rien.
- [ ] `AtelierChatServiceMemoryTest` — le tour suivant rejoue `tool_use` et `tool_result` appariés.
- [ ] `AtelierChatServiceMemoryTest` — au-delà de 5 tours, les plus anciens sont rejoués en texte seul.
- [ ] `AtelierChatServiceMemoryTest` — trajectoire illisible ⇒ rejeu en texte seul.

### Tests d'intégration

- [ ] `AtelierChatApiIntegrationTest` — l'historique renvoyé par l'API ne contient pas la trajectoire
      (non-régression du contrat).

### Isolation workspace

- [x] Applicable — couverte par les tests d'isolation existants : aucun accès qui ne passe par
      `workspaceId` **et** `userId`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-02` — done (le préfixe court rend le rejeu abordable).

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — La trajectoire vit sur le message, pas dans une table à part.** Elle est lue en bloc avec
l'historique, jamais requêtée, jamais agrégée : une colonne document sur `atelier_messages` suit
exactement le même raisonnement que `terminal_json` (SF-30-09), et hérite gratuitement de son
isolation `user_id`.

**D2 — Un résultat coupé garde sa fin.** Une sortie de commande dit son verdict à la fin (code de
sortie, message d'erreur). Garder le début reviendrait à mémoriser la question sans la réponse.

**D3 — Cinq tours, pas tout l'historique.** Le rejeu de la trajectoire est ce qui évite de refaire
le travail ; sa valeur décroît vite avec l'ancienneté, alors que son coût, lui, ne décroît pas.
Cinq tours couvrent le contexte de travail immédiat sans transformer chaque message en relecture de
la session entière. La borne est un point de départ mesurable, pas une vérité : elle sera réévaluée
avec la compaction (lot 6).

**D4 — Effet sur le cache, assumé.** Quand un tour sort de la fenêtre des cinq, le préfixe change en
son milieu et le cache se réécrit une fois. C'est le prix d'une borne ; l'alternative — tout garder —
coûte davantage à chaque tour.
