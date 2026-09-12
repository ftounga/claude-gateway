# Mini-spec — F-88 / SF-88-01 — Lire un fil sur une fenêtre de temps

## Identifiant

`F-88 / SF-88-01`

## Feature parente

`F-88` — Le volet Teams : les outils de lecture

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-88-01-lecture-fil-fenetre`

---

## Objectif

Donner à l'agent **deux premiers outils** — `teams_find_conversations` et
`teams_read_conversation` — et, dessous, le **moteur de récolte** qui fait le morceau difficile du
volet : lire un fil **sur une fenêtre de temps** alors que la liste est virtualisée, en faisant
défiler par programme, en **recollant sans doublon ni trou**, et en rendant **toujours** la fenêtre
réellement lue et ce qui n'a pas pu être lu.

---

## Pourquoi celle-ci d'abord

Les six autres outils du catalogue (SF-88-02) sont des **requêtes sur ce qui a été observé** : une
fois le registre et la boucle de récolte en place, chacun tient en quelques dizaines de lignes.
Celui-ci porte **tout le risque** : le défilement, l'attente, le recollement, le plafond (D4). On le
livre seul, et on l'éprouve seul.

---

## Le principe d'acquisition, écrit une fois pour tout F-88

F-87 a posé la doctrine : **on observe le trafic que la page produit déjà**, on ne l'appelle pas.
F-88 en tire trois conséquences, et une seule ouverture.

1. **Un registre d'observation** (`TeamsLedger`). Tout ce que la page a reçu depuis le
   rattachement — liste des conversations, messages, flux d'activité, réunions, transcriptions — est
   décodé par l'adaptateur **au fil de l'eau** et accumulé. C'est **Provider-First** au pied de la
   lettre : Teams a déjà cherché, déjà trié, déjà paginé ; on ne redemande pas ce qu'on a sous les
   yeux.
2. **Un vocabulaire de gestes fermé**, tous par `Runtime.evaluate` — **la seule porte que la liste
   blanche de SF-87-02 a ouverte**, et qui sert déjà au défilement. Trois verbes, et pas un de
   plus : `scroll` (approfondir la liste ouverte), `nudge` (provoquer un rafraîchissement sans
   bouger la vue), `show` (afficher un fil que l'utilisateur n'a pas ouvert).
3. **La liste blanche CDP n'est pas touchée.** Aucune commande ajoutée : ni `Page.navigate`, ni
   `Input.dispatch*`, ni rien qui approche les cookies ou le stockage. Ce que F-87 a scellé reste
   scellé.
4. **Ce qui est fait dans la fenêtre de l'utilisateur est dit, et rendu.** `show` note le fil
   ouvert avant d'agir et le **remet** en fin de lecture ; le résultat porte la phrase
   « ce que j'ai fait dans votre fenêtre Teams ».

---

## Comportement attendu

### Cas nominal — `teams_read_conversation`

Entrée : `conversation_id` (facultatif — à défaut, **le fil ouvert**), `from` / `to` (ISO-8601 ou
`"7d"`, `"3 jours"`…), `max_messages`.

1. La **déclaration de portée (D1)** est émise **une fois** pour ce fil : ce qui sera lu, et où cela
   ira.
2. Si le fil demandé n'est pas celui affiché, geste `show`. Le fil affiché avant est mémorisé.
3. Boucle de récolte, au plus `MAX_SCROLL_GESTURES` (40, borne de F-87) :
   - `collect()` → chaque réponse observée est décodée par l'adaptateur et **fondue** dans le
     registre ;
   - si la fenêtre demandée est couverte (le plus ancien message lu est antérieur à `from`), ou si
     le plafond est atteint, ou si le défilement ne bouge plus → on s'arrête ;
   - sinon geste `scroll`, puis attente de stabilisation (600 ms, borne de F-87).
4. Le fil affiché au départ est remis.
5. Le résultat rend les messages **triés du plus ancien au plus récent**, **dédoublonnés par
   identifiant**, la **fenêtre réellement lue**, les **manques**, et la **santé** de l'adaptateur.

### Cas nominal — `teams_find_conversations`

Entrée : `query` (personne, groupe ou sujet ; facultatif), `limit`.
Sortie : les conversations du registre, **classées par dernière activité**, filtrées par
rapprochement insensible à la casse et aux accents sur le sujet, les participants et l'identifiant.
Une correspondance sur le **nom d'un participant** compte autant qu'une correspondance sur le sujet :
un tête-à-tête n'a pas de sujet.

### Recoller sans doublon ni trou

| Situation | Ce qui est fait |
|---|---|
| Le même message arrive deux fois (pages qui se chevauchent) | fondu **par identifiant** ; la version **la plus récemment observée** gagne (une édition écrase l'originale) |
| **Deux** gestes de suite ne rapportent **aucun** objet nouveau | manque `PAGINATION_STOPPED` — **un trou possible est nommé**, jamais tu. Deux et non un : une page peut rendre la main avant d'avoir reçu la réponse du geste précédent, et crier au trou à chaque latence rendrait l'avertissement inaudible |
| Le défilement ne bouge plus, **et Teams annonce encore des messages avant** (`_metadata.backwardLink`) | manque `SCROLL_EXHAUSTED` + `reachedStartOfConversation` **faux**. C'est Teams lui-même qui tranche entre « on est au début du fil » et « la page refuse de remonter » — sans ce signal, une lecture courte se dirait complète à tort |
| Le plafond est atteint | manque `CAP_REACHED` + la phrase de D4 (« demandez une période explicite ») |
| Le fil demandé n'a jamais été observé et `show` a échoué | **zéro message ET un manque nommé** — jamais une liste vide silencieuse |
| L'adaptateur ne reconnaît rien (`NONE`) | on **refuse** : aucun message rendu, la phrase de la sonde est reprise |

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Navigateur non relié | **succès** d'outil portant l'état `BROWSER_NOT_DETECTED` et le remède (la ligne de commande à coller) — comme `teams_status` |
| `--no-teams` | succès d'outil disant que le volet est désactivé sur cette machine |
| `from` illisible | plafond **par défaut** appliqué (7 jours), et **dit** dans le résultat |
| `max_messages` hors bornes | ramené dans `[1 ; 2000]`, et **dit** |
| Outil `teams_*` inconnu | `unsupported_tool` — refusé, jamais deviné |

---

## Le plafond (D4), et la négociation

- Défaut : **7 jours** ou **500 messages** (`TeamsReadWindow.DEFAULT_*`, posés par SF-87-01).
- **Négociable dans la demande** : `from: "2026-09-01"` ou `from: "21d"` remonte plus loin ;
  `max_messages` monte jusqu'à **2000**.
- Le plafond est **annoncé** dans la description de l'outil **et** rappelé dans le résultat quand il
  mord.
- Le résultat porte **toujours** `window.actualFrom` / `actualTo` : la fenêtre **réellement** lue,
  jamais celle demandée.

---

## Le contrat de sortie (lu par F-89)

Enveloppe JSON commune à **tous** les outils de F-88 — F-89 l'affiche, et ne doit pas avoir à
deviner :

```json
{
  "tool": "teams_read_conversation",
  "linkState": "LINKED",
  "adapter": "v1",
  "conversation": { "id": "…", "label": "…", "kind": "GROUP", "webUrl": "…" },
  "window": { "requestedFrom": "…", "requestedTo": "…", "actualFrom": "…", "actualTo": "…",
              "cap": 500, "capReached": false, "reachedStart": true, "complete": true },
  "health": { "verdict": "FULL", "recognizedFields": 7, "expectedFields": 7,
              "missingFields": [], "observedApiVersions": ["v1"] },
  "gaps": [ { "kind": "MISSING_FIELD", "label": "champ obligatoire absent",
              "where": "…", "detail": "from", "count": 3 } ],
  "messages": [ { "id": "…", "conversationId": "…", "parentId": "",
                  "authorId": "…", "author": "Paul Durand", "self": false,
                  "sentAt": "2026-09-05T09:12:03.412Z", "editedAt": null, "deleted": false,
                  "kind": "TEXT", "text": "…", "mentionsMe": true,
                  "attachments": [ { "name": "…", "type": "…", "bytes": 12 } ],
                  "reactions": [ { "kind": "like", "count": 1 } ], "webUrl": "…" } ],
  "notice": "…",
  "viewport": "…",
  "text": "47 messages lus, 3 non lus, du 5 au 12 septembre. …"
}
```

`text` est **la** phrase que l'agent citera : elle porte le décompte, la fenêtre, le plafond s'il a
mordu, et les manques. `notice` ne paraît qu'**une fois** par fil (D1). `viewport` ne paraît que si
un geste a touché la fenêtre de l'utilisateur.

---

## Critères d'acceptation

- [ ] `teams_read_conversation` rend les messages d'un fil **triés, dédoublonnés**, avec la fenêtre
      **réellement lue**.
- [ ] Deux pages qui se chevauchent ne produisent **aucun doublon** ; la version la plus récemment
      observée d'un message gagne.
- [ ] Un geste de défilement sans message nouveau produit un manque `PAGINATION_STOPPED`.
- [ ] Un défilement qui ne bouge plus avant la fenêtre demandée produit `SCROLL_EXHAUSTED` et
      `complete = false`.
- [ ] Le plafond par défaut (7 jours / 500) est appliqué, **annoncé** dans la description de l'outil,
      et **négociable** par `from` / `max_messages` jusqu'à 2000.
- [ ] Une réponse que l'adaptateur ne reconnaît pas **ne rend aucun message** et dit pourquoi.
- [ ] La déclaration de portée (D1) paraît **une fois** par fil, jamais à chaque tour.
- [ ] `teams_find_conversations` classe par dernière activité et trouve par **nom de participant**
      autant que par sujet.
- [ ] Un fil jamais observé et non affichable rend **zéro message et un manque nommé**.
- [ ] Le geste `show` **remet** le fil qui était affiché.
- [ ] Aucune commande de débogage nouvelle : `CdpCommands.allowed()` est **inchangée**.
- [ ] Aucun cookie, aucun jeton ne franchit le registre (test sur l'échantillon empoisonné).

---

## Périmètre

### Dans cette subfeature

- `TeamsLedger`, `PageGestures`, `TeamsHarvester`, `TeamsScopeNotice`, `TeamsToolResult`.
- Outils `teams_read_conversation`, `teams_find_conversations` **dans le runner**.

### Hors scope (explicite)

- Les six autres outils du catalogue → **SF-88-02**.
- La déclaration des outils au modèle (`buildTools`) et leur routage par la gateway → **SF-88-03** :
  à la fin de cette PR, **l'agent ne reçoit encore aucun outil Teams de lecture**.
- Tout affichage (cartes, blocs, images) → **F-89**.
- Le droit / l'option mensuelle → **F-89** (D5 ; le montant reste **À CONFIRMER PAR LE PO**).
- Écrire dans Teams — répondre, publier, réagir. **On lit.**

---

## Technique

| Élément | Choix |
|---|---|
| Paquet | `fr.claudegateway.runner.teams` — **tout** le savoir Teams y reste (test d'architecture de SF-87-01) |
| Registre | fusion par identifiant dans des `LinkedHashMap`, bornes dures (`MAX_MESSAGES = 5000`, `MAX_CONVERSATIONS = 500`) : un registre qui vit avec la liaison ne doit pas grossir sans fin |
| Gestes | `Runtime.evaluate` **uniquement** ; chaque geste rend un booléen « ça a bougé » |
| Attente | `BrowserLink.SCROLL_SETTLE_MS` (600 ms), injectable — les tests ne dorment pas |
| Dates | `Instant`, UTC ; `from` accepte l'ISO-8601, `"7d"`, `"7 jours"`, `"2026-09-01"` |

---

## Plan de test

**Unitaires (runner, `TeamsLedgerTest`, `TeamsHarvesterTest`, `PageGesturesTest`)**

1. Deux pages qui se chevauchent → un seul exemplaire de chaque message.
2. Le même message réobservé avec un texte modifié → la version récente gagne.
3. Tri du plus ancien au plus récent, quel que soit l'ordre d'arrivée.
4. Fenêtre couverte → `complete = true`, `reachedStart` vrai si la page n'annonce plus de suite.
5. Défilement sans nouveauté → `PAGINATION_STOPPED`.
6. Défilement bloqué → `SCROLL_EXHAUSTED`, `complete = false`.
7. Plafond → `CAP_REACHED` et la phrase de D4.
8. Échantillon `-partial` → messages valides rendus, manques nommés, **aucun message à moitié lu**.
9. Échantillon `-unknown` → aucun message, refus expliqué.
10. Échantillon `-secrets` → aucun jeton, aucun cookie dans le JSON rendu.
11. `show` remet le fil précédent ; `show` en échec → manque nommé, zéro message.
12. D1 : la déclaration paraît une fois, pas deux.
13. `teams_find_conversations` : classement par activité, recherche par participant, par sujet,
    insensible aux accents.

**Intégration (runner, `TeamsToolsTest`)** — bout en bout sur le navigateur de papier :
`teams_read_conversation` sur un fil qui remonte en trois gestes, JSON complet vérifié.

**Isolation utilisateur** — sans objet **dans le runner** : il tourne sur la machine de
l'utilisateur, pour lui seul, et ne voit aucune donnée d'un autre compte. L'isolation `user_id` est
appliquée côté gateway par `requireOwned` sur le poste (F-87 / SF-87-03), et le sera de nouveau par
SF-88-03 pour l'appel d'outil.

---

## Dépendances

- **F-87 livrée** : adaptateur, modèle du domaine, liaison au navigateur, sonde de santé.
- Aucune dépendance sur F-89, qui avance en parallèle et **consomme** l'enveloppe ci-dessus.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | rien côté gateway dans cette SF |
| Contexte tenant | **Non** | le runner n'a pas de tenant ; SF-88-03 posera la garde |
| Plans / limites | **Non** | le droit est F-89 (D5) |
| Navigation / routing | **Non** | aucun écran |
| **Liste blanche CDP (sécurité, F-87)** | **Oui, et elle ne bouge pas** | `CdpCommands` : un test verrouille que `allowed()` est inchangée |

---

## Notes et décisions

**A1 — Un registre plutôt qu'une lecture à la demande.** Alternative écartée : redemander à Teams à
chaque appel. Elle supposerait d'émettre des requêtes nous-mêmes — exactement ce que le cadrage
distingue d'un « client API déguisé ». Réversible.

**A2 — Le geste `show`, et pas `Page.navigate`.** Ouvrir un fil que l'utilisateur n'a pas affiché est
nécessaire au troisième gisement (ses propres promesses, que ni mention ni mot-clé ne trouvent).
Deux voies : ajouter `Page.navigate` à la liste blanche, ou agir **dans la page** par la porte déjà
ouverte. On prend la seconde : la liste blanche est une décision de sécurité scellée en SF-87-02
(« on lit, on ne pilote pas », cookies et stockage hors d'atteinte) et **elle ne se rouvre pas pour
une commodité**. Le geste retenu ne touche ni cookie, ni stockage, ni barre d'adresse ; il est du
même genre que le défilement, déjà autorisé. **Prix assumé et écrit** : il touche la vue de
l'utilisateur — donc il la **remet**, et il le **dit**. Réversible.

**A4 — Trois ajouts *additifs* au modèle de SF-87-01, annoncés à F-89.** Rien n'est retiré ni
renommé ; le contrat que F-89 consomme ne bouge pas.

| Ajout | Pourquoi |
|---|---|
| `TeamsGapKind.CONVERSATION_NOT_REACHED` | un fil qu'on n'a pas su atteindre doit rendre **zéro message et ce manque** ; aucun des sept genres existants ne le disait |
| `TeamsGapKind.NOTHING_OBSERVED` | « Teams n'a rien servi sur ce sujet » n'est **pas** « il n'y a rien » |
| `TeamsAdapter.self(url, body)` | sans identité de l'utilisateur relié, « on m'a mentionné » ne se distingue pas de « on a mentionné quelqu'un », et répondre « non » faute de savoir serait **faux**. Tant qu'aucun profil n'a été observé, `mentionsMe` vaut **`null`**, jamais `false` |

**A3 — Les manques d'acquisition sont des manques, pas des zéros.** Un fil qu'on n'a pas su
atteindre rend `0 message` **et** un manque nommé. C'est la règle qui prime sur toutes les autres :
un compte rendu plausible et faux est pire qu'un refus. Non réversible en esprit (c'est la doctrine
du volet), réversible en code.

**Limite, écrite et non maquillée** : **nous n'avons aucun compte Teams de test**. Rien ici n'est
éprouvé contre un vrai Teams. Ce qui est prouvé : le recollement, le dédoublonnage, le plafond, les
manques, l'absence de secrets — sur des **échantillons fabriqués**
(`src/test/resources/teams/PROVENANCE.md`) et un **navigateur de papier**. Ce qui ne l'est pas : que
la forme supposée soit celle que Microsoft sert, et que les sélecteurs de geste correspondent au DOM
réel. C'est la **sonde de santé (SF-87-03)** qui confrontera l'hypothèse au réel au premier
branchement.
