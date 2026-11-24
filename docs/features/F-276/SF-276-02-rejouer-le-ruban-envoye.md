# Mini-spec — F-276 / SF-276-02 Rejouer le ruban envoyé

## Identifiant
`F-276 / SF-276-02`

## Feature parente
`F-276` — Le raisonnement survit d'un tour à l'autre

## Statut
`ready`

## Date de création
2026-11-24

## Branche Git
`feat/SF-276-02-rejouer-le-ruban-envoye`

---

## Objectif
Chaque tour est rangé **tel qu'il est parti au fournisseur** et rejoué à l'identique : l'historique devient append-only, et le raisonnement des tours passés n'est plus écarté.

## Comportement attendu

### Cas nominal

**1. Rangement en fin de tour.**

Le ruban du tour est l'ensemble des messages que le tour a ajoutés à la conversation, à partir de la consigne augmentée. Il comprend :
- les consignes d'effort ;
- chaque étape (raisonnement signé, texte, appels, résultats entiers) ;
- les précisions étiquetées ;
- les corrections de fin de tour ;
- la **dernière réponse réelle** du modèle (raisonnement et texte).

Il est rangé en JSON sans perte (`AtelierSentRibbon`) sur le message assistant du tour, dans la colonne `atelier_messages.sent_ribbon`, avec :
- l'identifiant du message utilisateur qui l'ouvre ;
- les identifiants des précisions rangées pendant le tour.

**2. Rejeu.**

Dans `replayableHistory`, un tour **dans la fenêtre tracée** (F-134, inchangée) dont le ruban est lisible est rejoué ainsi :
- ses lignes (message utilisateur, précisions, réponse) sont remplacées par son ruban, à l'octet près ;
- une ligne du groupe qui n'appartient pas au ruban est rejouée à sa place d'avant, **après** le ruban.

Un tour sans ruban (antérieur au déploiement, ou non rangé) garde le rejeu d'avant. Au-delà de la fenêtre, le rejeu est en texte seul, comme avant.

**3. Effort en vigueur.**

Au 1er appel, le niveau d'effort en vigueur est celui de la **dernière consigne d'effort rejouée**, et non plus le niveau racine. Le modèle ne reste donc jamais au niveau baissé d'un tour passé : une consigne est glissée dès que le niveau voulu diffère.

**4. Purge.**

À la compaction (nouvelle frontière), les rubans des messages d'avant la frontière sont effacés : ils ne seront plus jamais rejoués.

**5. Mesure.**

La mesure du tour range :
- `ribbon_replayed_turns` : tours rejoués depuis leur ruban ;
- `ribbon_chars` : taille du ruban rangé.

**6. Coupe-circuit.**

`app.atelier.replay-sent-ribbon` (`APP_ATELIER_REPLAY_SENT_RIBBON`, défaut `true`). À `false`, le rejeu est strictement celui d'avant. Le rangement continue, pour pouvoir rallumer sans perte.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Conversation rebâtie pendant le tour (débordement compacté, séquence réassainie) | Ruban non rangé ; ce tour sera rejoué comme avant. |
| Arrêt subi sans réponse complète (réponse coupée, refus, emballement, débordement, synthèse forcée) | Ruban non rangé ; rejeu d'avant. |
| Interruption, budget de temps, plafond de consommation (arrêt à une frontière d'étape) | Ruban rangé, et clos par le texte persisté du tour, comme le rejeu d'avant. |
| Ruban de plus de 4 Mo (captures d'écran) | Non rangé ; rejeu d'avant ; un journal `info` donne la taille. |
| Ruban illisible, ou message d'ouverture absent du fil | Rejeu d'avant pour ce tour, jamais d'échec du tour. |
| Le fournisseur rejette la séquence | Le réassainissement existant (F-117 / SF-117-08) rebâtit sans raisonnement, comme aujourd'hui. |

## Critères d'acceptation
- [ ] **Append-only** : deux tours consécutifs, avec une consigne augmentée, un résultat d'outil de plus de 8 000 caractères, une consigne d'effort et une précision. Les messages rejoués au tour N+1 commencent par **exactement** les messages envoyés au dernier appel du tour N, suivis de sa dernière réponse (test d'égalité structurelle).
- [ ] Le JSON du ruban fait l'aller-retour sans perte sur tous les blocs (`Text`, `ToolUse`, `ToolResult` avec images, `Image`, `Document`, `Reasoning`, `RedactedReasoning`, `ServerTool`, consigne d'effort) (test).
- [ ] Les tours sans ruban, au-delà de la fenêtre, ou avec le coupe-circuit à `false`, sont rejoués exactement comme avant (les tests existants de `replayableHistory` passent inchangés).
- [ ] Après un tour terminé en effort baissé, le 1er appel du tour suivant glisse une consigne au niveau voulu (test).
- [ ] Une ligne du groupe hors ruban est rejouée après le ruban (test).
- [ ] La compaction efface les rubans d'avant la frontière, pour ce seul utilisateur et ce seul terminal (test d'intégration).
- [ ] En prod, à J+1 : le journal « Raisonnement écarté » ne montre plus d'écart au 1er appel des tours qui suivent un tour rangé, hors paliers et compactions. `thinking_dropped` de SF-276-01 vaut 0 sur ces tours.

## Plan de test minimal
- **Unitaires** :
  - `AtelierSentRibbonTest` : aller-retour, borne de 4 Mo, JSON illisible ;
  - `replayableHistory` : ruban, sans ruban, hors fenêtre, ligne étrangère, coupe-circuit ;
  - effort en vigueur.
- **Service** : `AtelierChatService`, deux tours enchaînés avec un fournisseur bouchon qui enregistre les requêtes. Le préfixe du tour N+1 est égal au dernier envoi du tour N, plus sa réponse. Le ruban n'est pas rangé après un débordement compacté.
- **Intégration** : migration et colonne ; purge à la compaction isolée par `user_id` et terminal.
- **Non-régression** : suite complète du backend (règle « deux constructeurs »).

## Impacts
- **Table** `atelier_messages` : `sent_ribbon TEXT NULL`. **Table** `atelier_turn_measures` : `ribbon_replayed_turns INT NULL`, `ribbon_chars BIGINT NULL`. Migration `276-02`, réversible.
- **Code** :
  - `AtelierSentRibbon` (nouveau) ;
  - `AtelierMessage` ;
  - `AtelierChatService` (rangement, rejeu, effort, collecte des identifiants de précisions) ;
  - `AtelierCompactionService` (purge) ;
  - `AtelierMessageRepository` ;
  - `AtelierProperties` ;
  - `TurnMeasure` et `TurnMeasureWriter`.
- **Endpoints** : aucun. **Écran** : aucun. L'affichage du fil lit toujours `content`.
- **Préoccupations transversales** : aucune.

## Décisions
- **D1 — Estimateur de compaction inchangé.** Il compte la trajectoire, pas le ruban. Compter le ruban déclencherait la compaction plus tôt, donc retirerait du contexte, ce que la règle « justesse avant coût » interdit. Le surplus réellement rejoué est mesuré (`ribbon_chars`). Le débordement reste rattrapé par F-117 / SF-117-02.
- **D2 — Fenêtre tracée F-134 conservée.** Son palier reste la seule mutation volontaire du préfixe, une tous les 12 tours (tier 3 du guide, mesurée).
- **D3 — Texte affiché inchangé.** `content` reste la réponse retenue (F-125), à l'écran et dans la compaction. Seul le rejeu lit le ruban.

## Hors périmètre
- Stabiliser `system` ou `tools` d'un tour à l'autre : SF-276-03, conditionnelle à la mesure de SF-276-01.
- Les sous-boucles déléguées (`explore`, `task`), qui ont leur propre conversation.
- Le seuil de compaction et la fenêtre des trajectoires.
