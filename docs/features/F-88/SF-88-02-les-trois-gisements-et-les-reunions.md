# Mini-spec — F-88 / SF-88-02 — Les trois gisements et les réunions

## Identifiant

`F-88 / SF-88-02`

## Feature parente

`F-88` — Le volet Teams : les outils de lecture

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-88-02-mentions-recherche-reunions`

---

## Objectif

Compléter le catalogue avec les **cinq outils restants** — `teams_mentions`, `teams_search`,
`teams_find_meetings`, `teams_meeting_transcript`, `teams_meeting_recording` — de sorte que les
**trois gisements d'un engagement** soient tous couverts, et pas seulement les deux faciles.

---

## Les trois gisements, et pourquoi il les faut tous les trois

Le PO a demandé la solution **la plus complète**. Se contenter des mentions livrerait un outil qui
rate précisément ce qui engage l'utilisateur.

| Gisement | Où il se lit | Coût | Outil |
|---|---|---|---|
| **La mention explicite** — « @Francky, peux-tu… » | le **flux d'activité**, que Teams calcule déjà | exact, peu cher | `teams_mentions` |
| **Le nom écrit en clair** — « Francky s'occupe du MFA » | l'**index de Teams**, qui indexe le contenu | une requête | `teams_search` |
| **Ses propres promesses** — « je te l'envoie demain » | **nulle part** : ni mention, ni nom. **Aucune recherche par mot-clé ne les trouve** | il faut **ouvrir** les fils récemment actifs | `teams_find_conversations` + `teams_read_conversation` (SF-88-01), sur les messages dont l'auteur **est** l'utilisateur relié |

**Les deux premiers disent pourquoi ce découpage est le bon** : `teams_mentions` passe par le flux
d'activité et `teams_search` par l'index — on **réutilise ce que Teams calcule déjà** au lieu de
parcourir toutes les conversations. C'est **Provider-First appliqué à l'interface**, et ça fait
tomber le problème d'un ordre de grandeur : **dix à trente fils à ouvrir, pas des dizaines de
conversations**.

**Le troisième reste cher, et c'est assumé** : il n'y a pas de raccourci. SF-88-01 a livré le geste
qui le rend possible ; cette subfeature livre de quoi savoir **quels** fils ouvrir.

---

## Comportement attendu

### `teams_mentions`

Entrée : `from` / `to`, `max_mentions`. Sortie : les mentions visant **l'utilisateur relié**, les
plus récentes d'abord, chacune avec son auteur, son heure, son fil, son aperçu et le lien vers le
message.

Le flux d'activité porte aussi les **réactions** et les **réponses** : l'adaptateur ne retient que
les mentions — ce n'est pas un défaut du flux, c'est un tri.

### `teams_search`

Entrée : `query` (obligatoire), `from` / `to`, `max_results`.

1. Le geste `ask` pose la question **dans le champ de recherche de Teams**, laisse l'index répondre,
   puis **remet le champ tel qu'il était**.
2. Ce que Teams sert en réponse est observé, décodé, fondu au registre.
3. Sortie : les messages qui correspondent, les plus récents d'abord.

**Sans le geste, l'outil ne rend pas une liste vide** : il rend **zéro résultat et un manque nommé**,
en disant à l'utilisateur ce qu'il peut faire (taper la recherche lui-même dans Teams). Une liste
vide silencieuse se lirait « personne n'a écrit votre nom », ce qui serait faux.

### `teams_find_meetings`

Entrée : `query` (sujet ou participant), `from` / `to`, `limit`. Sortie : les réunions observées,
les plus récentes d'abord, avec **`recorded`** et **`transcriptAvailable`** — les deux champs qui
disent si la suite est possible.

### `teams_meeting_transcript`

Entrée : `meeting_id` (obligatoire). Sortie : les répliques horodatées, dans l'ordre, avec leur
locuteur.

**D1 s'applique ici aussi** : la déclaration de portée paraît **une fois par réunion**, avant le
premier traitement des paroles de tiers.

Si aucune transcription n'a été observée : **zéro réplique et un manque nommé**, avec la raison la
plus probable — la réunion n'était pas enregistrée, ou la transcription n'a pas été ouverte dans
Teams.

### `teams_meeting_recording`

Entrée : `meeting_id`. Sortie : la **disponibilité** de l'enregistrement, son adresse de page, la
**destination locale** où il ira, et — c'est le point important — **ce que cet outil ne fait pas**.

**Il ne télécharge pas les octets, et il le dit.** Voir l'arbitrage **A5** : l'adresse signée qui
permettrait de les chercher est **retirée par construction** en SF-87-02 (« l'adresse perd sa chaîne
de requête à l'entrée : un jeton qui n'entre jamais ne peut pas sortir »). Rouvrir cette décision
pour télécharger une vidéo serait rouvrir une décision de sécurité au profit d'une commodité.
L'acquisition des octets appartient à **F-91**, qui télécharge son outillage au premier usage (D3).

### Cas d'erreur (les cinq outils)

| Situation | Comportement |
|---|---|
| Navigateur non relié | **succès** d'outil portant l'état `BROWSER_NOT_DETECTED` et le remède |
| `--no-teams` | succès d'outil disant que le volet est désactivé |
| `query` absent sur `teams_search` | manque nommé `MISSING_FIELD` — jamais une recherche à vide |
| `meeting_id` absent | manque nommé ; aucun résultat |
| Réunion inconnue du registre | **zéro résultat ET un manque nommé** |
| Utilisateur relié inconnu (aucun profil observé) | `teams_mentions` le **dit** : il rend ce que le flux porte, en prévenant qu'il n'a pas pu vérifier que ces mentions visent bien l'utilisateur |

---

## Critères d'acceptation

- [ ] `teams_mentions` rend les mentions du flux d'activité, les plus récentes d'abord, sans les
      réactions ni les réponses.
- [ ] `teams_mentions` dit explicitement quand l'utilisateur relié n'a pas pu être identifié.
- [ ] `teams_search` pose la question dans le champ de Teams **et remet le champ tel qu'il était**.
- [ ] `teams_search` sans geste possible rend **zéro résultat et un manque nommé**, jamais une liste
      vide muette.
- [ ] `teams_search` sans `query` est un manque nommé.
- [ ] `teams_find_meetings` rend `recorded` et `transcriptAvailable`.
- [ ] `teams_meeting_transcript` rend les répliques **dans l'ordre chronologique**, avec locuteur.
- [ ] D1 : la déclaration de portée paraît **une fois par réunion**.
- [ ] `teams_meeting_recording` **dit qu'il ne télécharge pas**, et pourquoi, et nomme la destination.
- [ ] Les cinq outils portent l'enveloppe commune (`text`, `window`, `gaps`, `health`).
- [ ] Aucune commande de débogage nouvelle : `CdpCommands.allowed()` inchangée.
- [ ] Aucun secret ne franchit le rendu (échantillon empoisonné).

---

## Périmètre

### Hors scope (explicite)

- La déclaration des outils au modèle et leur routage par la gateway → **SF-88-03**.
- Le **téléchargement** de l'enregistrement et sa transcription locale → **F-91** (A5).
- L'extraction et l'alignement des captures → **F-90**.
- Tout affichage → **F-89**.
- Écrire dans Teams. **On lit.**

---

## Plan de test

**Unitaires et intégration (runner, `TeamsMentionsAndSearchTest`, `TeamsMeetingToolsTest`)**

1. Mentions : tri, filtrage des réactions, fenêtre respectée.
2. Mentions sans profil observé : la réserve est écrite dans `text`.
3. Recherche : le champ est rempli, la réponse observée est décodée, le champ est remis.
4. Recherche sans champ trouvable : zéro résultat + manque nommé + remède.
5. Recherche sans `query` : manque nommé.
6. Réunions : tri, `recorded`, `transcriptAvailable`, rapprochement par sujet et participant.
7. Transcription : ordre chronologique, locuteurs, D1 une seule fois.
8. Transcription inconnue : zéro réplique + manque nommé.
9. Enregistrement : la phrase « je ne télécharge pas » et la destination.
10. Échantillon empoisonné : aucun secret.
11. Liste blanche CDP inchangée.

**Isolation utilisateur** — sans objet dans le runner (même raison qu'en SF-88-01) ; la garde
gateway est posée par SF-88-03.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | rien côté gateway |
| Contexte tenant | **Non** | SF-88-03 |
| Plans / limites | **Non** | le droit est F-89 (D5) |
| Navigation / routing | **Non** | aucun écran |
| **Liste blanche CDP (sécurité)** | **Oui, et elle ne bouge pas** | `CdpCommands`, verrouillée par test |
| **Vue de l'utilisateur** | **Oui** | le geste `ask` touche le champ de recherche : il le **remet** et le **dit** (`viewport`) |

---

## Notes et décisions

**A5 — `teams_meeting_recording` retrouve et prépare ; il ne télécharge pas, et il le dit.**
Le cadrage écrivait « télécharger l'enregistrement (reste sur la machine) ». **SF-87-02 rend cela
impossible sans rouvrir une décision de sécurité** : l'adresse perd sa chaîne de requête à l'entrée,
donc le volet ne détient **jamais** d'adresse signée exploitable. Les deux issues étaient : rouvrir
ce garde-fou, ou livrer un outil qui **nomme ce qu'il ne fait pas**. On prend la seconde — et
l'acquisition des octets va à **F-91**, qui a déjà le télécharge­ment d'outillage (D3) dans son
périmètre. **Ce qui n'est pas fait est écrit dans le résultat même de l'outil**, pas seulement dans
une note : l'agent ne doit pas pouvoir croire qu'un fichier existe. Réversible.

**A6 — Le geste `ask` dans le champ de recherche.** Sans lui, `teams_search` ne pourrait répondre
que si l'utilisateur avait tapé la requête lui-même — le gisement « nom écrit en clair » serait
perdu, et avec lui la moitié des engagements. Le geste reste **dans la page**, du même genre que le
défilement, et **remet le champ**. Alternative écartée : `Input.dispatchKeyEvent`, refusé par la
liste blanche de SF-87-02. **Fragilité assumée et écrite** : ce geste dépend du DOM, donc il casse
plus vite que le reste — quand il échoue, il **le dit et nomme le remède**, il ne rend jamais une
liste vide. Réversible.

**Limite, écrite et non maquillée** : toujours **aucun compte Teams de test**. Ce qui est prouvé ici
est la traduction, le tri, les manques, la remise en place de la vue — sur des **échantillons
fabriqués** et un **Teams de papier**. Ce qui ne l'est pas : la forme réelle des réponses, et les
sélecteurs du champ de recherche. La **sonde de santé (SF-87-03)** reste responsable de la
confrontation au réel.
