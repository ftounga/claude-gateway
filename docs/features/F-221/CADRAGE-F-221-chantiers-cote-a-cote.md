# CADRAGE F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

**Statut :** cadrage du 2026-10-28, livraison par l'agent (gouvernance `CLAUDE.md`, une PR par SF)
**Demande PO :** 2026-10-28
**Dépend de :** F-206 (plusieurs chantiers ouverts, focus), F-209 (suites en deux colonnes), F-219 (autonome, SF-219-05 « avancer jusqu'au blocage »), F-220 (tableau du client), F-84 (le tour vit côté serveur), F-150 (worktrees de `task`)

## Le besoin, dans les mots du PO
« Il y a un véritable problème pour l'exécution de plusieurs chantiers en même temps. J'ai du mal à utiliser l'application, j'ai l'impression qu'il se perd, c'est pas clair. Quand on est dans un terminal et qu'on a deux chantiers qui s'exécutent en même temps, il faudrait deux terminaux : un split vertical, un chantier à gauche, un à droite. Si je suis dans le dashboard et que je lance deux chantiers en même temps, en arrivant dans le sujet il doit m'afficher directement les deux terminaux. Si je suis déjà dans un terminal et que je lance un autre chantier, je veux un overlay qui me demande si je veux lancer un nouveau chantier en parallèle ; si je valide, il split le terminal et lance le chantier à droite, directement, sans phrase dans le chat. Pour les questions de fin (deux colonnes chantier / hors chantier), avec deux chantiers ça ferait quatre colonnes, trop. Arbitre au mieux. Tu peux me challenger. »

## Cause prouvée (prod, 28/10, sujet agenor, workspace `d5f10db2-…`)
Un sujet n'a **qu'un fil** (`atelier_messages` par `workspace_id`, sans chantier) et **qu'un tour vivant** (`LiveTurnRegistry` clefé `(userId, workspaceId)`).
Vérifié dans le code :
- la course autonome du chantier 7 ouvre le tour du sujet (`AutonomyTurnRunner.prepare` → `openIfIdle`) ;
- pendant ce temps, chaque envoi de l'écran passe par `openOrSteer` et **devient une précision de ce tour** (F-84 / SF-84-06) ;
- la planification du chantier 8 est donc entrée dans la course du chantier 7 (« Précisions de l'utilisateur pendant la course », `AutonomyTurnRunner.continuation`) ;
- les deux réponses se sont écrites dans le même fil.

Toutes les clefs d'état du tour de `AtelierChatService` sont aussi par sujet : `interruptedTurns`, `blanketAllowedTurns`, `spendCaps`, `machineOfTurn`, `mutatedTurns`, `turnRunnerCounters` et `mutedOutcomeTurns` (`turnKey(userId, workspaceId)`, 42 usages). Deux tours sur un même sujet se partageraient donc l'interruption, « tout autoriser » et le plafond de dépense.

## Arbitrages validés par le PO (2026-10-28)
1. **Un fil par chantier.** Les messages et le tour sont rattachés à un chantier (`chantier_number` nullable ; `null` = fil commun du sujet). Le contexte d'un tour de chantier contient le commun du sujet et **son** fil, jamais celui de l'autre chantier. Il y a un tour vivant **par chantier**, plus un pour le fil commun. On garde la compaction, le cache (préfixe stable, F-134), le rejeu des traces (`AtelierToolTrace`), le tour côté serveur (F-84), le focus (F-206) et les limites F-70 / F-219. On mesure l'effet sur le cache et la justesse. La justesse passe avant le coût.
2. **Split vertical, deux volets au plus.** Gauche = chantier courant, droite = l'autre. Au-delà, on passe par le tableau du client ou la mosaïque. Fermer un volet ne tue pas le chantier.
3. **Depuis le tableau du client** : deux chantiers du même sujet actifs → le sujet s'ouvre directement en split. Un seul chantier actif → écran normal.
4. **Depuis un terminal**, lancer un autre chantier ouvre l'overlay « Lancer le chantier N en parallèle ? ». Si le PO valide, le split s'ouvre et le chantier démarre à droite **directement**, sans phrase dans le chat, en mode `UNTIL_BLOCKED` quand c'est possible. Sinon, le volet s'ouvre sur le chantier avec ce qui l'empêche de partir.
5. **Suites (F-209)** : en split, chaque volet n'affiche que la colonne de son chantier, et la colonne « hors chantier » disparaît. La consigne le sait pour ce tour. Hors split, rien ne change.
6. **Deux agents sur le même poste** : chaque chantier parallèle qui modifie un dépôt git travaille dans son propre worktree. On réutilise F-150 et la décision « sous-agent isolé par worktree sur le poste », avec un repli sans git.
7. **Responsive** : pas de split sur téléphone, un sélecteur de volet à la place. `DESIGN_SYSTEM.md` s'applique.

## Arbitrages de l'agent (à challenger)
**A1. Le fil d'un message.**
- Le volet porte un fil :
  - `null` dans un sujet Libre ;
  - sinon le chantier qu'il montre. L'écran normal montre le **chantier au focus**.
- Chaque envoi porte donc `chantier`.
- Les clients sans ce champ (MCP, routines, ancien écran) restent sur le fil commun. Leur comportement ne change pas.
- *Pourquoi* : si l'écran normal restait sur le fil commun, « un fil par chantier » ne vaudrait qu'en split, et l'autonome continuerait de relire la planification des autres chantiers.

**A2. Ce que voit un fil (contexte et affichage, même règle).**
- **Fil commun** : tout le sujet, comme aujourd'hui.
- **Fil du chantier N** :
  - les messages antérieurs à l'ouverture de N (`subject_journey_chantiers.opened_at`), tous fils confondus : c'est le passé commun, qui contient la conversation qui a ouvert N ;
  - les messages `null` ;
  - les messages de N.

  Jamais un message d'un autre chantier postérieur à l'ouverture de N.
- **Règle d'or** : ce que l'écran montre est ce que le modèle relit. Aucun écart possible entre les deux.
- Les messages d'avant F-221 sont tous `null`, donc visibles partout : aucun sujet existant ne perd de contexte.

**A3. Ordre stable.** L'ordre stable est gardé par le regroupement par tour :
- un tour = messages consécutifs d'un même fil, de la demande à la réponse ;
- les tours sont ordonnés par leur premier message.

Sur un fil unique, c'est l'identité, octet pour octet : le cache des sujets existants ne bouge pas. C'est vérifié par un test.

**A4. Compaction.**
- Elle reste par sujet : un résumé, une frontière.
- Le résumé est le passé commun. Seuls les tours récents sont filtrés par fil.
- Une compaction par fil multiplierait les appels de résumé et l'état à tenir, pour un passé déjà commun par A2.
- Deux compactions simultanées sont sérialisées par sujet.

**A5. Le focus.**
- Lancer un chantier en autonome ne déplace plus le focus quand un autre fil du sujet est actif.
- Il le déplace encore quand le sujet est calme : c'est le comportement F-219, le chantier lancé est celui que le sujet montre.
- L'overlay lance toujours **sans** déplacer le focus.

**A6. Un chantier autonome par chantier**, et non plus un par sujet. La limite de 4 courses par compte reste. Le refus « sujet occupé » devient « ce chantier tourne déjà ».

**A7. Interrompre un volet n'arrête que son fil.**
- Les commandes en cours sur le poste ne sont annulées en bloc que si aucun autre fil du sujet ne tourne.
- Sinon, le fil interrompu s'arrête à la frontière sûre suivante. L'autre n'est jamais tué.

**A8. Worktree** : la règle de déclenchement.
- Il faut un chantier qui démarre **alors qu'un autre fil du sujet est actif**, sur un projet poste et git.
- Le chantier prend alors **son** worktree (`.atelier-worktrees/…`, branche `atelier/task/<id>`). Il le garde jusqu'à sa clôture ou jusqu'au geste « Rapatrier ».
- À la fin, on committe sur sa branche et on rend un diff résumé (F-150 `worktree_finalize`). La fusion reste un geste explicite : l'agent la propose, rien n'est fusionné en silence.
- Sans git, le volet le dit avant le départ et la consigne du tour aussi : « un autre chantier écrit dans le même dossier ». Le PO décide.

**A9. Les volets et F-70** :
- Un volet ne prend pas de place de terminal : même onglet, même sujet.
- Il prend un flux du pool SSE, comme un onglet. La capacité du pool est vérifiée en SF-221-01.

**A10. Hors périmètre.**
- `recall`, Plume, la prédiction de suite et le bilan lisent tout le sujet : ce sont des outils à la demande ou des lectures, pas le contexte du tour.
- Plus de deux volets.
- La mosaïque (F-83) est inchangée.

## Mesure (justesse avant coût)
- Une colonne `chantier_number` dans `atelier_turn_measures`, pour comparer la part relue et l'écriture de cache par fil et avant/après.
- **Effet attendu sur le cache** :
  - neutre ou meilleur sur un fil : le préfixe d'un chantier ne grossit que de ses tours, alors qu'aujourd'hui il absorbe ceux de l'autre chantier ;
  - une écriture de cache à chaque bascule de fil, puisque chaque fil a son préfixe.
- **Effet attendu sur la justesse** : meilleur. Plus de demandes d'un autre chantier injectées dans une course, plus de réponses croisées.
- Requête de lecture fournie dans la mini-spec SF-221-01, à relire à J+7.

## Découpage (aucune SF > 2 jours)
| SF | Objet | Couche | Risque |
|---|---|---|---|
| SF-221-01 | **Le fil par chantier**. Migration (`atelier_messages.chantier_number`, `atelier_turn_measures.chantier_number`), `chantier` sur envoi / rebranchement / état / précision / interruption / historique, registre clefé par fil, clefs d'état du tour par fil, rejeu filtré (A2, A3), note du parcours et chantier par défaut des outils = le fil, relais entre pods, mesure | back | 🟠 |
| SF-221-02 | **L'autonome par fil**. La course tourne dans le fil N (A6), focus (A5), [Reprendre la main] par chantier, `GET …/chat/lanes` (fils actifs : tour vivant ici ou chez un pair, course autonome) | back | 🟠 |
| SF-221-03 | **L'écran en split**. L'écran normal suit le fil du focus (A1), volet droit autonome (historique, direct, envoi, interruption, autorisations du fil), split automatique à l'arrivée (≥ 2 fils actifs), fermer un volet, téléphone = sélecteur | front | 🟠 |
| SF-221-04 | **L'overlay « en parallèle »**. Interception des gestes de lancement depuis un terminal (tableau des chantiers, « Basculer » d'une suite), départ direct en `UNTIL_BLOCKED` sinon strict sinon volet sur les raisons, sans phrase dans le chat | front (+ `keepFocus`) | 🟢 |
| SF-221-05 | **Les suites en split**. Consigne du tour (dans le **message**) « suites du chantier N uniquement », colonne HORS écartée côté serveur pour un tour de chantier en split, volet = une colonne | back + front | 🟢 |
| SF-221-06 | **Un worktree par chantier parallèle** (A8). Création collante, routage de toute la boucle du fil vers le worktree, rapatriement à la clôture, repli sans git, protection contre le `worktree_reap` | back | 🟠 |

## Préoccupations transversales
- **Contexte tenant** : le registre des tours reste clefé par l'utilisateur, le fil s'ajoute à la clef. Les lectures `atelier_messages` restent filtrées `workspace_id` + `user_id`. Composants : `LiveTurnRegistry`, `AtelierChatController` (stream / attach / turn / steer / interrupt / historique), `RelayTurnSource` / `AtelierRelayController`, `AutonomyService`, `AtelierMcpTurnLauncher`. Ce dernier reste sur le fil commun.
- **Plans / limites** : 4 courses autonomes par compte, inchangé. F-70 inchangé (A9).
- **Navigation** : le split est un état de l'écran du sujet, sans nouvelle route. Les liens existants (tableau du client, cloche, mosaïque) ouvrent le sujet ; le split se décide à l'arrivée.
- **Auth** : aucune.

## Risques
- **Taille de `AtelierChatService`** (10 700 lignes). Le fil est porté par un champ transitoire du `Workspace` chargé pour le tour. On évite ainsi de faire passer un paramètre dans 42 méthodes. Garde : un test par clef d'état.
- **Déploiement** : un pod ancien et un pod neuf se parlent. Le champ `chantier` des requêtes de relais est nullable, donc un pod ancien relaie sur le fil commun.
- **Worktree** : le `worktree_reap` de F-150 supprime les orphelins. Un worktree de chantier ne doit jamais en être un ; c'est vérifié en SF-221-06 avant tout branchement.
