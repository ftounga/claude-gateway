# Mini-spec — F-88 / SF-88-03 — Le catalogue donné à l'agent

## Identifiant

`F-88 / SF-88-03`

## Feature parente

`F-88` — Le volet Teams : les outils de lecture

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-88-03-catalogue-donne-a-lagent`

---

## Objectif

**Donner réellement les sept outils de lecture à l'agent** : les déclarer dans le catalogue
(`TeamsToolCatalog`, la garde posée par F-89 / SF-89-01), les **router** vers la machine, les
**journaliser**, et exiger la **capacité `teams`** — de sorte qu'une demande composée comme
« qu'est-ce qu'on attend de moi ? » puisse enfin s'exécuter.

---

## Ce qui manque exactement

SF-88-01 et SF-88-02 ont livré les huit outils **dans le runner**, éprouvés. Rien ne les déclare au
modèle et rien ne les achemine : à la fin de SF-88-02, **l'agent ne peut appeler que
`teams_status`**. Cette subfeature ferme la chaîne, et elle seule :

```
 buildTools (F-89/SF-89-01, déjà là)  →  TeamsToolCatalog  ←  ICI : les 7 outils de lecture
 AtelierChatService.callRunner        →  RunnerToolGateway ←  ICI : les 7 relais
 RunnerCallDispatcher.capabilityFor   →                      ICI : la capacité « teams »
```

**Aucun bouton n'est ajouté, aucun écran ne bouge.** Le basculement reste celui de F-89.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur parle dans un **terminal Teams** et a le **droit** (F-89 / SF-89-01, D5).
2. `TeamsToolCatalog.toolsFor` rend **huit** outils : `teams_status` (déjà là) plus les sept de
   lecture, chacun avec une description qui dit **ce qu'il coûte** et **ce qu'il ne fait pas**.
3. L'agent les compose librement. « Qu'est-ce qu'on attend de moi ? » appellera les **mentions**,
   puis la **recherche** sur les variantes du nom, puis la **lecture** des fils récemment actifs.
4. Chaque appel part en `tool_call` vers la machine, avec le **délai des outils Teams** (20 s, hérité
   de SF-87-03 ; **60 s** pour la lecture d'un fil, qui fait défiler).
5. Le résultat du runner — l'enveloppe JSON de SF-88-01 — est rendu au modèle **tel quel** : il porte
   déjà la phrase, la fenêtre réellement lue, les manques et la santé.
6. Chaque appel laisse une **ligne de journal d'audit**, qu'il ait abouti ou échoué.

### Ce que l'agent est explicitement invité à faire

Les descriptions **portent la doctrine**, parce que c'est le seul endroit où l'agent la lit :

- le **plafond** est annoncé (7 jours / 500 messages) **et** dit négociable ;
- les **trois gisements** sont nommés dans la description des mentions et de la recherche, avec la
  phrase qui compte : *« ses propres promesses ne contiennent ni mention ni nom — aucune recherche
  ne les trouve, il faut ouvrir les fils récemment actifs »* ;
- chaque résultat porte **ce qui n'a pas pu être lu**, et l'agent est prié de le **répéter** :
  un compte rendu qui tait un trou est un compte rendu faux.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Pas de terminal Teams, ou droit fermé | les outils **ne sont pas donnés** — l'agent n'a pas la capacité, il ne refuse pas (F-89) |
| Machine sans volet Teams (`--no-teams`) | la capacité `teams` n'est pas annoncée → `unsupported_tool` **avant émission** |
| Machine déconnectée | `runner_unavailable`, comme tout outil runner |
| Délai dépassé | `runner_timeout`, `tool_cancel` émis |
| Outil `teams_*` inconnu du relais | l'appel n'est pas émis ; le modèle reçoit « outil inconnu » |
| Terminal de **projet** | aucun outil Teams : la garde de F-89 s'applique, inchangée |

---

## La capacité `teams` — un correctif dans le périmètre

`RunnerCallDispatcher.capabilityFor(tool)` rend aujourd'hui `"bash"` pour la commande et
`"files"` **pour tout le reste** — y compris `teams_status`. Une machine lancée avec `--no-teams`
**annonce** `files` mais pas `teams` : l'appel partait quand même, pour être refusé au bout du fil.

Désormais : tout outil préfixé `teams_` exige la capacité **`teams`**. Le refus redevient
**local et immédiat**, comme pour `bash`. Ce n'est pas un ajout hors périmètre : c'est la garde que
SF-87-03 avait préparée en **annonçant** la capacité, et que personne n'avait encore exigée.

---

## Critères d'acceptation

- [ ] Les **huit** outils sont donnés dans un terminal Teams avec droit ouvert.
- [ ] **Aucun** outil `teams_*` n'est donné sans terminal Teams ou sans droit (garde F-89 intacte).
- [ ] Les sept outils de lecture sont **routés** vers le runner avec leurs paramètres.
- [ ] `teams_read_conversation` a un délai **plus long** que les autres : il fait défiler.
- [ ] Un outil `teams_*` sur une machine qui n'annonce pas la capacité `teams` est refusé
      **avant émission**.
- [ ] Chaque appel Teams laisse une ligne d'audit, avec une **cible lisible** (le fil, la requête, la
      réunion) — **jamais** un contenu de message.
- [ ] Le catalogue déclaré et ce que le runner sait exécuter **ne peuvent pas diverger** : un test
      les compare nom par nom.
- [ ] Un terminal de projet ne reçoit aucun outil Teams (non-régression).
- [ ] Isolation : le `userId` du droit est celui du **tour**, jamais un paramètre client.

---

## Périmètre

### Hors scope (explicite)

- Les blocs d'affichage (carte, moment, liste) → **F-89 / SF-89-02**.
- Le montant de l'option → **À CONFIRMER PAR LE PO** (D5).
- F-90, F-91.

---

## Composants impactés

| Fichier | Changement |
|---|---|
| `backend/.../teams/TeamsToolCatalog.java` | +7 déclarations d'outils |
| `backend/.../runner/exec/RunnerToolGateway.java` | +7 relais, +1 délai |
| `backend/.../atelier/AtelierChatService.java` | routage `callRunner` + cible d'audit |
| `backend/.../runner/channel/RunnerCallDispatcher.java` | `capabilityFor` : `teams_*` → `teams` |

**Aucune table, aucune migration, aucun endpoint, aucun composant Angular.**

---

## Plan de test

**Unitaires / intégration (backend)**

1. `TeamsToolCatalogTest` : huit outils donnés avec droit + terminal Teams ; **zéro** sinon.
2. Anti-divergence : le catalogue déclaré == ce que le runner sait exécuter.
3. `RunnerToolGatewayTest` : chaque relais émet le bon nom d'outil et les bons paramètres ; délai de
   lecture plus long.
4. `RunnerCallDispatcherTest` : `teams_read_conversation` sur une machine sans capacité `teams` →
   `unsupported_tool` **sans émission** ; avec la capacité → émis.
5. `AtelierChatServiceRunnerTargetTest` : un appel `teams_*` est routé, son résultat rendu au modèle,
   sa ligne d'audit écrite avec une cible lisible.
6. Non-régression : un terminal de projet ne reçoit aucun outil Teams.

**Isolation utilisateur** — `toolsFor(userId, workspace)` reçoit le `userId` **du tour** ; un test
vérifie qu'un droit ouvert pour un autre utilisateur ne donne rien.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés et vérifiés |
|---|---|---|
| Auth / Principal | **Non** | aucun endpoint modifié |
| **Contexte tenant** | **Oui** | `TeamsToolCatalog.toolsFor` — le `userId` vient du tour ; test d'isolation |
| **Plans / limites** | **Oui, sans la changer** | `TeamsAccessService.hasAccess` (F-89 / SF-89-01) est le **seul** point de décision ; F-88 n'en ajoute pas |
| Navigation / routing | **Non** | aucun écran |
| **Capacités runner** | **Oui** | `RunnerCallDispatcher.capabilityFor` — `bash` et `files` inchangés, test de non-régression |

---

## Notes et décisions

**A7 — Un délai plus long pour la lecture d'un fil.** 20 s suffisent à observer ; pas à **faire
défiler** quarante écrans en attendant 600 ms à chaque geste. `teams_read_conversation` et
`teams_search` prennent **60 s**. Alternative écartée : allonger le délai de tous les outils Teams —
un `teams_status` qui met une minute à dire « navigateur non détecté » serait une régression de
SF-87-03. Réversible.

**A8 — Le test anti-divergence.** Le catalogue vit dans la gateway, l'exécution dans le runner :
deux dépôts de la même vérité, donc deux vérités un jour. Un test compare la liste déclarée à
`TeamsTools.CATALOG`. Il ne peut pas tourner **à travers** les modules (le runner n'est pas au
classpath de la gateway) : la liste attendue est donc **écrite dans le test**, et sa contrepartie
côté runner est verrouillée par `TeamsGisementsTest`. Deux verrous qui se répondent valent mieux
qu'un accord tacite.
