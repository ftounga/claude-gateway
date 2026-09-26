# Mini-spec — F-121 / SF-121-11 — Pilotage : steers consultés entre appels d'outils, cap assoupli/coalescé, interjections étiquetées et datées

## Identifiant

`F-121 / SF-121-11`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart de parité **Lot 2** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §3 :

> **F-121-11** — **Pilotage** : steers lus seulement à la frontière d'itération, plafond 5, sans
> étiquetage → ressenti « messages multiples ». → Consulter la file **entre appels d'outils** ;
> assouplir/coalescer le cap ; **étiqueter** les steers comme interjections utilisateur datées.

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-11-pilotage-steers`

---

## Objectif

Qu'une précision déposée pendant le tour soit **lue dès la fin de l'outil en cours** (et non à la
seule frontière d'itération), **jamais refusée pour un plafond trop serré**, et présentée au modèle
comme **une interjection d'utilisateur datée** — une seule, quel qu'en soit le nombre.

---

## Contexte — pourquoi c'est un écart de parité

Trois défauts du pilotage actuel (F-39 / SF-39-19, porté au tour vivant par F-84 / SF-84-06) :

1. **Lecture à la seule frontière d'itération.** `AtelierChatService` prend la file
   (`listener.takeSteers()`) **au début de l'étape**, avant l'appel au fournisseur. Une précision
   déposée pendant un `bash` de 90 s ou au milieu d'une rafale de cinq outils n'est ni prise ni
   **annoncée** avant que toute la rafale soit finie : l'écran affiche « en attente de l'étape
   suivante » pendant tout ce temps, et l'utilisateur croit n'avoir pas été entendu.
2. **Cap à 5, sec.** `LiveTurn.MAX_PENDING_STEERS = 5` refuse la 6ᵉ précision en
   `409 too_many_steers`. Un utilisateur qui pense à voix haute pendant un tour long se fait
   refuser — alors que le coût réel n'est pas le **nombre** de précisions mais le **volume de
   texte** ajouté.
3. **Aucun étiquetage, et un message par précision.** Chaque précision est injectée telle quelle en
   `AgentMessage.userText(...)`, une par message. Le modèle reçoit donc, après les résultats
   d'outils, une suite de messages utilisateur nus, indiscernables de la demande initiale : c'est le
   « ressenti messages multiples » du cadrage. Claude Code, lui, étiquette l'interjection.

Rien ici ne touche au raisonnement ni à une capacité IA : c'est du **réglage de boucle**, conforme
`PROJECT.md` §3.2 (Gateway-First) et §3.3 (Provider-First).

---

## Comportement nominal

### 1. Consultation **entre appels d'outils**

Dans la boucle qui exécute les appels d'outils d'une étape, **après chaque appel** (résultat rangé),
la file est consultée. Une précision trouvée est immédiatement :

- **persistée** (`atelier_messages`, rôle `USER`, `user_id` + `workspace_id` du tour) — comme avant,
  texte **brut** ;
- **annoncée appliquée** (`steer_applied`, `steerId`, étape à laquelle le modèle la lira) — l'écran
  passe de « en attente » à « prise en compte » **sans attendre la fin de la rafale** ;
- **jointe au message de résultats d'outils de l'étape en cours**, en bloc de texte placé **après**
  tous les `tool_result` (ordre exigé par le fournisseur).

La frontière d'itération reste consultée (une précision arrivée pendant l'appel au fournisseur, ou
avant la première étape, y est lue comme avant).

### 2. Cap **assoupli et coalescé**

- `MAX_PENDING_STEERS` : **5 → 10**.
- Nouveau plafond de **volume** : `MAX_PENDING_STEER_CHARS = 8000` caractères cumulés en attente.
- File pleine (10 en attente) **et** volume disponible : la précision est **coalescée** dans la
  dernière en attente (texte concaténé, saut de ligne), qui garde son `steerId` et son horodatage ;
  le reçu est `ACCEPTED` avec ce `steerId`. L'utilisateur n'est pas refusé.
- `FULL` (⇒ `409 too_many_steers`) n'est rendu **que** lorsque le volume cumulé dépasserait 8000
  caractères : c'est un vrai plafond, pas une gêne de comptage.

### 3. Interjections **étiquetées et datées**, en **un seul** message

Les précisions prises à une même frontière partent en **un seul** message utilisateur (au lieu d'une
par message), chacune sous la forme :

```
[Interjection de l'utilisateur — 2026-09-26 14:32:05] <texte de l'utilisateur>
```

Horodatage : instant du **dépôt** (`queuedAtMs`), zone `Europe/Paris`, motif `yyyy-MM-dd HH:mm:ss`
(convention du projet, cf. `RadarUnknownsService.DEFAULT_ZONE`). Le texte **persisté** reste brut :
l'étiquette sert le modèle du tour vivant, pas le fil relu par l'utilisateur.

---

## Cas d'erreur

| # | Cas | Comportement attendu |
|---|-----|----------------------|
| E1 | Volume cumulé des précisions en attente dépasserait 8000 caractères | `SteerReceipt.FULL` → `409 too_many_steers`, message « Trop de texte en attente… », le tour **n'est pas touché** |
| E2 | Tour fini ou scellé | `ENDED` inchangé : l'envoi ouvre un tour neuf (aucune régression SF-84-06) |
| E3 | Tour interrompu alors que des précisions attendent | `sealAndDrain()` + `steers_dropped` inchangés : aucune ne disparaît en silence |
| E4 | Précision déposée pendant la réponse **finale** (aucun outil, aucune étape suivante) | Non consommée par la boucle : le tour vivant ouvre le **tour de suite** (`pollFollowUpOrSeal`), inchangé |
| E5 | Précision au texte vide/blanc | Ignorée à l'injection (aucun bloc de texte vide envoyé au fournisseur : `400 text content blocks must be non-empty`) |
| E6 | Précision déposée alors que le tour attend une autorisation | Inchangé : une précision ne vaut ni accord ni refus |

---

## Critères d'acceptation

- **CA1** — Une précision déposée pendant l'exécution des outils d'une étape est prise **à la fin de
  l'appel d'outil en cours**, annoncée `steer_applied` à ce moment-là, et jointe au message de
  résultats d'outils de cette étape (après les `tool_result`).
- **CA2** — Le modèle lit cette précision à l'étape suivante ; `steer_applied.step` annonce bien
  cette étape-là.
- **CA3** — Plusieurs précisions prises ensemble partent en **un seul** message utilisateur, dans
  l'ordre de dépôt.
- **CA4** — Chaque précision est présentée au modèle préfixée de `[Interjection de l'utilisateur —
  <date> <heure>]`, l'horodatage étant celui du **dépôt**.
- **CA5** — Le texte **persisté** en base reste le texte brut de l'utilisateur (sans étiquette).
- **CA6** — La 6ᵉ… 10ᵉ précision en attente est `ACCEPTED` (cap assoupli).
- **CA7** — La 11ᵉ est **coalescée** dans la 10ᵉ : même `steerId`, textes concaténés, `ACCEPTED`.
- **CA8** — Au-delà de 8000 caractères cumulés, le reçu est `FULL` et le tour reste vivant.
- **CA9** — Isolation : une précision reste cherchée par `(userId, workspaceId)` ; rien ne change au
  routage local/pair, et un projet d'autrui rend 404 avant tout accès au tour.
- **CA10** — Aucune régression : tour sans précision strictement identique (mêmes messages, même
  préfixe caché F-134), précision d'avant-première-étape lue à l'étape 1, précision tardive laissée
  au tour de suite, précisions abandonnées annoncées.

---

## Plan de test minimal

### Unitaires — `LiveTurnSteerTest`
- T1 — 10 précisions acceptées (cap assoupli).
- T2 — la 11ᵉ est coalescée dans la dernière : même `steerId`, texte concaténé, `ACCEPTED`, taille de
  file inchangée.
- T3 — volume cumulé > 8000 caractères ⇒ `FULL`, tour toujours vivant, file inchangée.
- T4 — tour scellé ⇒ `ENDED` (non-régression).

### Unitaires — `AtelierChatServiceTest` (boucle)
- T5 — précision déposée **entre deux appels d'outils** d'une même étape : `steer_applied` émis
  pendant l'étape, texte présent dans le message de résultats **après** le dernier `tool_result`.
- T6 — étiquetage daté : le texte envoyé au fournisseur contient `[Interjection de l'utilisateur —`
  et l'heure du dépôt ; la base, elle, contient le texte brut.
- T7 — deux précisions ⇒ **un seul** message utilisateur les portant toutes deux, dans l'ordre.
- T8 — non-régressions existantes conservées (précision d'étape 1, injection unique, précision
  tardive au tour de suite, tour sans précision inchangé).

### Isolation utilisateur
- T9 — `AtelierChatControllerSteerTest` / `LiveTurnSteerTest` : la précision d'ALICE ne tombe pas
  dans le tour de BOB (tests existants, conservés).

### Frontend (`SF-121-11-FE`)
- T10 — libellé d'attente « lue dès la fin de l'outil en cours » et message `too_many_steers` alignés
  sur le nouveau plafond (tests de composant existants étendus).

---

## Tables / endpoints / composants impactés

| Type | Élément | Nature |
|------|---------|--------|
| Table | *aucune* | aucun changement de schéma, aucune migration Liquibase |
| Endpoint | `POST /api/atelier/{id}/chat/steer` | inchangé (contrat identique) ; `409 too_many_steers` devient rare (plafond de volume) |
| Backend | `atelier/live/LiveTurn.java` | cap 10, coalescence, plafond de volume |
| Backend | `atelier/AtelierProgressListener.java` | `AtelierSteer` porte `queuedAtMs` |
| Backend | `atelier/AtelierChatController.java` | passe l'horodatage ; message du 409 |
| Backend | `atelier/AtelierMcpTurnLauncher.java` | passe l'horodatage |
| Backend | `atelier/AtelierChatService.java` | consultation entre appels d'outils, injection coalescée et étiquetée |
| Frontend | `atelier/terminal/atelier-terminal.component.ts`, `atelier/atelier.component.ts` | libellés (SF-121-11-FE) |

---

## Contraintes de validation

| Champ | Contrainte | Source |
|-------|-----------|--------|
| Précisions en attente | ≤ **10** par tour | tranché ici (était 5) |
| Volume cumulé en attente | ≤ **8000** caractères | tranché ici (borne dure, remplace le refus au comptage) |
| Texte d'une précision | déjà borné par la validation de `AtelierChatRequest` (`@NotBlank`) | existant |
| Horodatage | `Europe/Paris`, `yyyy-MM-dd HH:mm:ss` | convention projet |
| Étiquette | littéral stable `[Interjection de l'utilisateur — …]` | tranché ici (cache F-134 : littéral, pas de variation de forme) |

Aucune question de `docs/OPEN_QUESTIONS.md` n'est impactée.

---

## Préoccupations transversales

| Préoccupation | Cochée ? | Composants impactés |
|---------------|----------|---------------------|
| Auth / Principal | non | aucun changement d'authentification ni de Principal |
| Contexte tenant | non | `(userId, workspaceId)` reste la clé de recherche du tour vivant : `TurnSteering`, `LiveTurnRegistry`, `RemoteTurnSource`, `AtelierChatController.steer` (404 avant tout accès), inchangés |
| Plans / limites | non | aucun quota ni gate touché ; le plafond de dépense du tour (SF-39-15) est inchangé |
| Navigation / routing | non | aucune route |

---

## Hors périmètre

- **Interrompre** l'outil en cours ou la rafale d'outils sur réception d'une précision : le geste qui
  arrête reste l'interruption (F-38 / SF-38-07). Une précision **n'annule jamais** un appel en vol.
- Modifier la conversation **pendant** l'appel au fournisseur (décision D2 de SF-39-19).
- Étiqueter les précisions **persistées** (le fil relu montre ce que l'utilisateur a écrit).
- Rendre le cap configurable par propriété (constantes, comme les autres bornes de `LiveTurn`).
- Tout ce qui relève d'OCR/RAG/pgvector (hors périmètre V1).

---

## Arbitrages pris (gates réversibles)

| # | Décision | Pourquoi | Alternative écartée | Réversible |
|---|----------|----------|---------------------|------------|
| A1 | Cap 5 → **10** + coalescence, plutôt qu'un cap illimité | Le coût réel est le volume, pas le nombre ; un plafond de volume borne la mémoire du tour | Cap illimité (fuite mémoire possible) | oui |
| A2 | Coalescence dans la **dernière** précision en attente (même `steerId`) | L'écran suit déjà un `steerId` : réutiliser celui de la dernière évite un état orphelin | Nouvelle précision à `steerId` neuf refusée en `FULL` | oui |
| A3 | Étiquette **seulement** vers le modèle, base brute | Le fil relu doit montrer ce que l'utilisateur a écrit ; le rejeu (`buildReplayMessages`) reste stable | Persister l'étiquette (pollue le fil et le rejeu) | oui |
| A4 | Précisions jointes au message de `tool_result` (bloc texte final) plutôt qu'un message séparé | Supprime les messages utilisateur consécutifs — la cause du « ressenti messages multiples » | Message utilisateur séparé (statu quo) | oui |
| A5 | Zone `Europe/Paris` en dur | Convention déjà en place dans le code (Radar) ; déterministe en test | Zone du poste (indisponible côté gateway) | oui |
