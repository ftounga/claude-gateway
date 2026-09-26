# Mini-spec — F-121 / SF-121-17 — END_OF_TURN neutralisé sur les tours qui répondent ou n'ayant rien écrit

## Identifiant

`F-121 / SF-121-17`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison). Écart de parité **Lot 3 / P3** du cadrage
`docs/features/F-121/CADRAGE-F-121-parite-claude-code-feuille-de-route.md` §4 :

> **F-121-17** — **END_OF_TURN** : neutraliser le crochet de relance sur les tours qui répondent à une
> question / en mode ANSWER-PLAN, ou le restreindre aux tours ayant réellement écrit. (P3)

## Statut

`done`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-17-fin-de-tour-neutralisee`

---

## Objectif

Ne plus renvoyer au travail un tour qui **répond** — mode Réponse/Plan, ou tour n'ayant écrit aucun
fichier — en n'interrogeant en fin de tour que les contrôles dont c'est réellement l'objet.

---

## Contexte — pourquoi c'est un écart de parité

Le crochet de fin de tour (F-50 / SF-50-02) est posé au **seul** endroit où le modèle rend la main de
lui-même, et il interroge **tous** les contrôles `END_OF_TURN` enregistrés, **quel que soit le tour**.
Conséquence observée : l'utilisateur pose une question, le modèle répond, et la boucle repart
(« Fin de tour contrôlée : … ») alors que **rien n'a été produit** à contrôler. Claude Code ne relance
jamais un tour de pure réponse.

Deux tours n'ont rien à faire dans ce crochet :

1. **Mode Réponse/Plan** (`AgentTurnMode.ANSWER_PLAN`, SF-120-02) : aucune mutation n'est même
   déclarée (`write_file`/`edit_file`/`bash` retirés de la panoplie). Un contrôle qui juge le travail
   produit n'a rien à juger ; pire, la porte de complétude SF-121-05 refuserait la clôture d'un tour
   de **plan** dont les étapes sont, par construction, toutes à faire.
2. **Tour n'ayant rien écrit** : les contrôles de gouvernance branchés aujourd'hui jugent **les
   fichiers écrits** (`juge-independant` audite `writtenPaths`, `integrite-du-poste` ne part
   « que si le tour a écrit », `juge-fin-de-tour` et `promotion-dette-bloquante` sont retirés depuis
   SF-125-06b). Les interroger sur un tour sans écriture ne peut rien produire d'utile — mais coûte
   la résolution des paquets actifs (lecture base) à chaque tour.

Exception nécessaire : la **porte de complétude** SF-121-05 juge le **plan du tour**, pas les
fichiers. Un tour d'investigation (lecture, `bash`) avec un plan à moitié coché doit rester refusé.
D'où un contrat explicite : un contrôle déclare s'il juge aussi les tours sans écriture.

---

## Comportement attendu

### Cas nominal

| Tour | Aujourd'hui | Après SF-121-17 |
|------|-------------|-----------------|
| Mode `ANSWER_PLAN`, avec ou sans écriture | tous les contrôles `END_OF_TURN` interrogés | **aucun** contrôle interrogé, le tour se clôt |
| Mode `ACT`, **aucun** fichier écrit | tous les contrôles interrogés | seuls les contrôles **déclarant juger les tours sans écriture** (porte de complétude SF-121-05) sont interrogés |
| Mode `ACT`, au moins un fichier écrit | tous les contrôles interrogés | **inchangé** : tous interrogés |

Le reste du crochet est **inchangé** : premier blocage gagnant (D3 de F-50), contrôle qui lève ignoré
(D2), plafond `MAX_END_OF_TURN_BLOCKS`, rejeu du tour refusé + correction côté utilisateur (D1), bloc
de transcription visible au rechargement, report F-93/SF-93-04 retenu à défaut de blocage, arrêts
subis (interruption, troncature, plafond d'étapes) jamais contrôlés.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Un contrôle lève sur `judgesTurnWithoutWrites()` | Contrôle **ignoré** (repli passant, règle D2 de F-50) — jamais d'échec de tour |
| Contexte de fin de tour `null` | Traité comme « rien écrit » : seuls les contrôles déclarés sont interrogés ; aucune NPE |
| Mode du tour `null` | Vaut `ACT` (défaut historique) — la neutralisation « mode » ne s'applique pas |
| Contrôle enregistré sur un autre point d'accroche | Non concerné : filtré par `kind()` comme avant |

---

## Critères d'acceptation

- [x] CA1 — En mode `ANSWER_PLAN`, **aucun** contrôle `END_OF_TURN` n'est interrogé et le tour se clôt en une passe.
- [x] CA2 — En mode `ACT` sans aucune écriture, un contrôle **ordinaire** (défaut) n'est pas interrogé, même s'il bloquerait.
- [x] CA3 — En mode `ACT` sans aucune écriture, un contrôle **déclarant** juger ces tours est interrogé et peut refuser la clôture.
- [x] CA4 — En mode `ACT` avec au moins une écriture, **tous** les contrôles sont interrogés : non-régression stricte de F-50 / SF-50-02.
- [x] CA5 — `PlanCompletudeCheckpoint` déclare juger les tours sans écriture : SF-121-05 n'est pas régressée (plan inachevé ⇒ clôture refusée, y compris sur un tour de lecture seule).
- [x] CA6 — `GovernanceEndOfTurnCheckpoint` garde le défaut : sur un tour sans écriture, **aucune** résolution de paquet actif n'est faite.
- [x] CA7 — Un contrôle dont `judgesTurnWithoutWrites()` lève est ignoré, sans casser le tour ni bloquer.
- [x] CA8 — Isolation : le contexte reste construit après `requireOwned(userId, workspaceId)`, avec le couple du tour ; aucun accès données nouveau.
- [x] CA9 — Aucun appel au fournisseur ajouté, aucune consigne système modifiée : **cache F-134 intact**.

---

## Périmètre

### Hors scope (explicite)

- Les autres points d'accroche (`AFTER_FILE_WRITE`, `BEFORE_COMMAND`) : inchangés.
- Toute notion d'« écriture » autre que les fichiers (`writtenPaths`) — une commande `bash` mutante
  n'est pas comptée comme écriture ; c'est déjà la sémantique de F-50 et elle n'est pas révisée ici.
- Coupe-circuit de configuration dédié : voir « Notes et décisions » (D3).
- Frontend, endpoints, tables, migration, protocole runner, composant cluster : **aucun**.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma.

### Composants Angular

Aucun.

### Composants backend impactés

| Fichier | Changement |
|---------|-----------|
| `atelier/checkpoint/AtelierCheckpoint.java` | Nouvelle méthode **par défaut** `judgesTurnWithoutWrites()` (défaut `false`) |
| `atelier/checkpoint/AtelierCheckpointRunner.java` | Nouvelle entrée `runEndOfTurn(context, answeringTurn)` portant les deux neutralisations ; `run(kind, context)` inchangé pour les autres crochets |
| `atelier/checkpoint/PlanCompletudeCheckpoint.java` | Déclare `judgesTurnWithoutWrites() == true` |
| `governance/GovernanceEndOfTurnCheckpoint.java` | Documentation du défaut (juge le travail écrit) |
| `atelier/AtelierChatService.java` | Appelle `runEndOfTurn(…, turnMode == ANSWER_PLAN)` au lieu de `run(END_OF_TURN, …)` |

---

## Plan de test

### Tests unitaires — `AtelierCheckpointRunnerTest`

- [x] Mode Réponse/Plan : aucun contrôle interrogé, verdict « passe ».
- [x] Tour sans écriture : contrôle par défaut **non** interrogé.
- [x] Tour sans écriture : contrôle déclaré **interrogé**, son blocage est rendu.
- [x] Tour avec écriture : contrôle par défaut interrogé (non-régression).
- [x] Contexte `null` : aucun plantage, contrôle déclaré tout de même interrogé.
- [x] `judgesTurnWithoutWrites()` qui lève : contrôle ignoré, verdict « passe ».
- [x] Report (F-93) rendu sur un tour avec écriture ; premier blocage prioritaire.

### Tests unitaires — `PlanCompletudeCheckpointTest`

- [x] La porte déclare juger les tours sans écriture.

### Tests d'intégration (boucle) — `AtelierChatServiceEndOfTurnCheckpointTest`

- [x] Mode `ANSWER_PLAN` : un contrôle qui bloquerait n'est jamais interrogé, une seule passe fournisseur.
- [x] Mode `ACT` sans écriture : contrôle par défaut jamais interrogé, réponse rendue telle quelle.
- [x] Mode `ACT` avec écriture : blocage, rejeu du tour et correction déposée — non-régression SF-50-02.
- [x] Tour de lecture seule avec plan inachevé : la porte de complétude refuse toujours (SF-121-05).

### Isolation utilisateur

- [x] Applicable — le contexte de fin de tour continue de porter le couple `(userId, workspaceId)`
      du tour, construit après `requireOwned`. Aucun accès données nouveau ; la neutralisation
      **réduit** les lectures (paquets actifs non résolus sur un tour sans écriture). Couvert par les
      assertions d'isolation existantes (`theCheckpointSeesTheReplyAndTheFilesWrittenDuringTheTurn`).

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants vérifiés |
|--------------|-----------|---------------------|
| Auth / Principal | Non | Aucun changement d'authentification ni de Principal |
| Contexte tenant | Non (lecture seule du couple existant) | `AtelierCheckpointContext` (inchangé), `AtelierChatService` (couple issu de `requireOwned`) |
| Plans / limites | Non | Aucun quota ni gate touché ; `MAX_END_OF_TURN_BLOCKS` inchangé |
| Navigation / routing | Non | Aucun frontend |
| **Points d'accroche F-50** | **Oui** | Tous les implémenteurs de `AtelierCheckpoint` revus : `GovernanceWriteCheckpoint` (AFTER_FILE_WRITE, non concerné), `GovernanceCommandCheckpoint` (BEFORE_COMMAND, non concerné), `GovernanceEndOfTurnCheckpoint` (défaut assumé), `PlanCompletudeCheckpoint` (déclaré). Contrôles de gouvernance `END_OF_TURN` revus un par un : `juge-fin-de-tour` (retiré, no-op), `promotion-dette-bloquante` (retiré, no-op), `integrite-du-poste` (ne part déjà que si le tour a écrit), `juge-independant` (audite `writtenPaths`). |

---

## Dépendances

### Subfeatures bloquantes

- `SF-50-02` (crochet de fin de tour) — done
- `SF-120-02` (mode Réponse/Plan) — done
- `SF-121-05` (porte de complétude) — done

### Questions ouvertes impactées

Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Notes et décisions

- **D1 — Où poser la neutralisation.** Dans le **registre** (`AtelierCheckpointRunner`), pas dans
  chaque contrôle : c'est un comportement de la **boucle**, et le dupliquer dans chaque bean laisserait
  le prochain contrôle ajouté retomber dans l'écart. `AtelierChatService` ne gagne qu'un argument.
- **D2 — Le mode neutralise tout, l'écriture neutralise par contrôle.** En Réponse/Plan aucune
  mutation n'est même possible : rien à juger, y compris le plan (le tour *est* le plan). Sur un tour
  sans écriture en mode Agir, en revanche, la porte de complétude garde du sens — d'où un contrat
  explicite (`judgesTurnWithoutWrites()`), défaut `false` : la norme est de juger **le travail
  produit**, et un contrôle qui juge autre chose le dit.
- **D3 — Pas de coupe-circuit de configuration.** Ce n'est pas une expérimentation mais la correction
  d'un défaut de parité ; et deux leviers existent déjà pour restaurer un blocage au cas par cas :
  l'activation par paquet (F-51) et `app.atelier.plan-completeness-gate` (SF-121-05). Ajouter un
  drapeau imposerait un second constructeur (ou un composant de plus à `AtelierProperties`) au
  registre, ce qui casse le démarrage Spring — piège connu et évité.
- **D4 — `writtenPaths` fait foi.** Une écriture **bloquée** y figure déjà (F-50) : un tour qui a
  tenté d'écrire reste un tour contrôlé. Aucune nouvelle notion d'« écriture » n'est introduite.
- **Gateway-First / Provider-First / Provider Independence** : aucun appel fournisseur, aucun modèle
  en dur, aucune capacité IA réimplémentée — la boucle interroge simplement moins de contrôles.
- **Cache F-134** : consigne système et panoplie d'outils strictement inchangées.
