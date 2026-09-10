# Mini-spec — F-50 / SF-50-02 — Le crochet de fin de tour

## Identifiant

`F-50 / SF-50-02`

## Feature parente

`F-50` — Points de contrôle de la boucle

## Statut

`done`

## Date de création

2026-09-10

## Branche Git

`feat/SF-50-02-crochet-fin-de-tour`

---

## Objectif

Poser le second point d'accroche : **quand le modèle croit avoir fini**, un contrôle peut refuser la
fin du tour et **renvoyer le modèle au travail** avec l'action corrective à exécuter.

---

## Comportement attendu

### Cas nominal

1. Le modèle rend un tour **sans appel d'outil** : c'est sa réponse finale, et la boucle s'apprête à
   sortir.
2. Avant de sortir, la boucle passe le point de contrôle `END_OF_TURN` au registre, avec le contexte
   du tour : l'utilisateur, le projet, le **texte de la réponse** et la liste des **fichiers écrits**
   pendant le tour (chemins, sans doublon, dans l'ordre d'écriture).
3. **Verdict « passe »** (le cas de F-50 : aucun contrôle enregistré) → le tour se termine
   exactement comme aujourd'hui.
4. **Verdict « bloque »** → le tour **ne se termine pas** :
   - le message d'assistant du tour (raisonnement signé compris, s'il y en a) est rejoué dans la
     conversation, suivi d'un **message utilisateur** : `Fin de tour contrôlée : <action corrective>` ;
   - la boucle **repart** à l'itération suivante, avec ses garde-fous inchangés (plafond d'étapes,
     budget de temps, plafond de consommation, interruption).
5. Le blocage est visible au rechargement : un bloc de transcription `point de contrôle` en **erreur**
   porte le message, comme n'importe quel échec d'outil (SF-39-17).

### Cas d'erreur et bornes

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Le tour s'arrête pour une **autre** raison qu'une réponse finale (interruption, budget de temps, plafond de consommation, réponse tronquée, plafond d'étapes) | **Aucun contrôle n'est exécuté.** On ne renvoie pas au travail quelqu'un qu'on vient d'arrêter, et surtout pas quand la raison de l'arrêt est un plafond : ce serait le franchir | — |
| Un contrôle bloque **trois fois** dans le même message | Le troisième blocage est le dernier : le tour se termine avec la réponse du modèle, et une ligne de journal le signale. Un contrôle qui bloque en boucle ne prend jamais le message en otage | — |
| Un contrôle lève une exception | Ignoré (repli passant), comme en SF-50-01 | — |
| Blocage sans action corrective | Message de repli : `Fin de tour contrôlée : reprends le travail avant de conclure.` | — |
| Le tour final n'a produit **aucun texte** | Le contrôle est exécuté normalement ; le message d'assistant rejoué porte le texte de repli non vide (`EMPTY_REPLY_FALLBACK`), l'API refusant un bloc de texte vide (SF-28-18) | — |
| Le blocage arrive à la **dernière** itération autorisée | La boucle sort sur son plafond d'étapes, avec le texte du modèle. Le crochet ne relève jamais le plafond | — |

### Ce que le blocage coûte

Une reprise = un appel fournisseur de plus, compté dans les compteurs du tour comme n'importe quelle
itération. Rien n'est soustrait au plafond de consommation ni au budget de temps : le crochet
s'exerce **à l'intérieur** des bornes du message, jamais au-dessus.

---

## Critères d'acceptation

- [x] Un contrôle enregistré sur `END_OF_TURN` est appelé quand le modèle rend une réponse finale.
- [x] Il reçoit le texte de la réponse et les chemins écrits pendant le tour, sans doublon.
- [x] Un verdict bloquant **empêche** la sortie : la boucle rappelle le fournisseur.
- [x] Le message correctif est déposé comme message **utilisateur** et contient l'action corrective.
- [x] Après reprise, la réponse finale rendue à l'utilisateur est celle du **dernier** tour, pas celle qui a été bloquée.
- [x] Un verdict passant termine le tour à l'identique d'aujourd'hui.
- [x] Sans contrôle enregistré, aucun comportement ne change.
- [x] Un tour interrompu, arrêté sur le budget de temps, sur le plafond de consommation, sur une réponse tronquée ou sur le plafond d'étapes **ne déclenche aucun contrôle**.
- [x] Au troisième blocage, le tour se termine malgré tout.
- [x] Le blocage apparaît dans la transcription persistée, en erreur.
- [x] Le contexte porte le `userId` et le `workspaceId` du tour (isolation).
- [x] Aucun texte de réponse, aucun chemin et aucune action corrective ne sont écrits dans le journal serveur.

---

## Périmètre

### Hors scope (explicite)

- Tout **contrôle** réellement branché → F-51 / F-52 (dont le « juge sémantique de fin de tour »,
  qui sera un contrôle parmi d'autres, best-effort et jamais une autorité).
- Toute **configuration utilisateur** → F-51.
- Toute exécution de code fourni par un tiers — exclue du périmètre de la feature.
- Le chemin **Managed Agents** : F-50 vise la boucle maison.
- La **sous-boucle d'exploration** (SF-39-14) : elle ne rend pas de réponse à l'utilisateur, elle
  rend un résultat d'outil à la boucle principale. Son « tour » n'est pas une fin de tour.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| action corrective | Non (nulle = repli) | 2 000 | texte libre (borne posée en SF-50-01) | Non | `trim()`, troncature |
| `replyText` du contexte | Non | — | le texte final du tour, tel que rendu par le modèle | Non | — |
| `writtenPaths` du contexte | Oui (liste, éventuellement vide) | 200 chemins | chemins **relatifs au projet** | Sans doublon | ordre d'écriture conservé |
| blocages par message | — | **3** | constante `MAX_END_OF_TURN_BLOCKS` | — | — |

Notes :
- **3 blocages** : assez pour une correction, sa vérification et un rattrapage ; trop peu pour qu'un
  contrôle mal écrit transforme un message en boucle coûteuse. La borne est une constante, pas un
  réglage : F-50 n'expose rien à la configuration.
- **200 chemins** : au-delà, la liste n'est plus une information mais un inventaire, et elle voyage
  dans chaque appel de contrôle.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Classes modifiées

| Classe | Modification |
|---|---|
| `AtelierCheckpointContext` | Deux champs de plus (`replyText`, `writtenPaths`) et une fabrique `endOfTurn(...)` ; la fabrique `afterFileWrite` est inchangée pour l'appelant |
| `AtelierCheckpointRunner` | `endOfTurnBlockedMessage(verdict)` — même règle d'écriture, préfixe propre au point d'accroche |
| `AtelierChatService` | Le crochet posé sur la sortie « réponse finale » de `runLoop` ; collecte des chemins écrits ; bornage des reprises |

### Composants Angular

Aucun — la transcription rend déjà un bloc en erreur (SF-39-17).

---

## Plan de test

### Tests unitaires

- [x] `AtelierCheckpointRunnerTest` — `endOfTurnBlockedMessage` porte l'action ; sans action, le repli.
- [x] `AtelierCheckpointContextTest` — `endOfTurn(...)` borne la liste des chemins et la rend immuable.

### Tests d'intégration (boucle)

- [x] `AtelierChatServiceEndOfTurnCheckpointTest` — contrôle bloquant → la boucle repart, la réponse rendue est celle du tour suivant.
- [x] Le message correctif est déposé côté **utilisateur** et contient l'action.
- [x] Le contexte porte le texte de la réponse et les chemins écrits pendant le tour, sans doublon.
- [x] Contrôle passant → le tour se termine, un seul appel fournisseur de plus n'est pas fait.
- [x] Sans contrôle → comportement d'avant.
- [x] Tour **interrompu** → aucun contrôle exécuté.
- [x] Réponse **tronquée** → aucun contrôle exécuté.
- [x] Plafond d'étapes atteint → aucun contrôle exécuté.
- [x] Un contrôle qui bloque toujours → le tour se termine au troisième blocage.
- [x] La transcription persistée contient le bloc `checkpoint` en erreur.

### Isolation workspace / utilisateur

- [x] Applicable — même garantie qu'en SF-50-01 : le contexte porte le couple du tour, obtenu après
      `requireOwned`. Test : le contexte capté porte bien les identifiants du tour.

---

## Dépendances

### Subfeatures bloquantes

- `SF-50-01` — statut : **done** (le contrat, le registre et le premier crochet).

### Questions ouvertes impactées

- [x] Aucune.

---

## Notes et décisions

**D1 — Le correctif est un message utilisateur, pas un `tool_result`.** Il n'y a aucun appel d'outil
auquel le rattacher : le tour bloqué est, par définition, celui qui n'en a demandé aucun. Un
`tool_result` orphelin serait refusé par le fournisseur. Le message utilisateur est la seule forme
correcte — et c'est aussi celle qu'emprunte déjà une précision déposée en cours de tour (SF-39-19).

**D2 — Trois blocages, et on rend la main.** Une borne dure, non configurable. Sans elle, un contrôle
qui bloque quoi qu'il arrive ferait tourner le message jusqu'au plafond d'étapes (30) ou jusqu'au
plafond de consommation — c'est-à-dire qu'il ferait payer à l'utilisateur le prix d'une règle mal
écrite. Le plafond d'étapes et le budget continuent de s'appliquer par-dessus : le crochet ne relève
aucune borne existante.

**D3 — Jamais sur un arrêt subi.** Interruption, budget de temps, plafond de consommation, réponse
tronquée, plafond d'étapes : dans les cinq cas, la boucle s'arrête pour une raison qui n'est pas
« le modèle a fini ». Y exécuter un contrôle serait au mieux inutile, au pire nuisible — renvoyer au
travail un tour arrêté sur son plafond, c'est franchir le plafond.

**D4 — La réponse rendue est celle du dernier tour.** Le texte bloqué est rejoué dans la conversation
(le modèle doit voir ce qu'il avait dit), mais il ne devient jamais la réponse persistée : c'est le
tour d'après qui conclut. Sinon, l'utilisateur lirait la réponse que le contrôle venait de refuser.
