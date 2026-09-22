# Cadrage — `explore` parallèle en lecture seule (F-39)

> Extension de la sous-boucle d'exploration **SF-39-14** (`AtelierExploration`). On ne crée pas un
> système multi-agents : on rend **concurrentes** des explorations qui sont déjà **en lecture seule**,
> déjà bornées, et dont **seule la conclusion remonte**. C'est le seul axe « sous-agents » jugé
> clairement utile chez nous (voir §Pourquoi).

## 1. Le problème
Aujourd'hui, quand l'agent principal a besoin de comprendre plusieurs choses **indépendantes** avant
d'agir (« où est géré l'auth ? », « comment sont rangées les migrations ? », « quel service envoie le
mail ? »), il délègue à `explore` — mais **une exploration à la fois, en série**. Dans la boucle
(`AtelierChatService` ~1457) comme dans la sous-boucle (`AtelierExploration.run`), les appels d'un même
tour s'exécutent l'un après l'autre. Trois explorations de 20 s = **60 s** de mur, pendant lesquelles
le tour vit dans le flux (et « quitter l'écran tue le tour »).

## 2. Ce qu'on livre
Quand l'agent émet **plusieurs `explore` dans le même tour** (questions indépendantes), on les exécute
**en parallèle**. Trois explorations de 20 s = **~20 s** de mur. Rien d'autre ne change : lecture seule,
bornes, coût imputé au tour, seules les conclusions reviennent.

## 3. Décisions de conception
- **D1 — Déclenchement naturel, pas de nouvel outil.** On garde l'outil `explore` tel quel. Le
  parallélisme se déclenche quand le modèle émet **N blocs `explore` dans le même tour assistant**
  (comportement natif du modèle, comme Claude Code émet des appels d'outils groupés). La boucle
  principale **isole les appels `explore` du tour** et les lance ensemble ; les autres outils du tour
  restent séquentiels (ordre et effets de bord préservés).
- **D2 — Lecture seule, linchpin de la sûreté.** C'est **parce que** l'exploration ne peut que lire
  (aucune écriture, aucune commande — SF-39-14, décision D2) que la concurrence est **sûre sur un poste
  unique** : pas de conflit d'écriture sur le répertoire de travail réel de l'utilisateur, pas d'invite
  d'autorisation surgie d'un agent invisible. **Cette garantie ne bouge pas.**
- **D3 — Borne de concurrence.** Un plafond de **parallélisme** (défaut proposé : **3**) en plus du
  plafond total d'explorations par message (`maxDelegations`, défaut 3). Nouveau réglage
  `app.atelier.explore-parallelism`. Au-delà, on remplit par vagues (pool borné).
- **D4 — Coût toujours imputé au tour (inchangé, SF-39-14 D4).** Les tokens de **toutes** les
  sous-boucles s'additionnent dans les compteurs du **tour** — déléguer, même en parallèle, ne permet
  jamais de passer sous le plafond par message. Le `AtelierProgressListener` est mis à jour de façon
  thread-safe (compteurs synchronisés).
- **D5 — Isolation des échecs.** Une exploration qui échoue (timeout, tronquée, erreur provider) rend
  **son** erreur comme résultat de **son** `tool_use`, sans tuer les autres.
- **D6 — Ordre & corrélation.** Chaque conclusion est rattachée au `callId` de son `tool_use` ; les
  `tool_result` sont rendus dans l'**ordre des appels**, quel que soit l'ordre de fin.
- **D7 — Interruption & échéance partagées.** Le `BooleanSupplier stop` et la `deadline` du tour sont
  partagés par toutes les sous-boucles (déjà consultés à chaque itération dans `run`).

## 4. Point dur à vérifier (DRAPEAU) — le runner
Les outils de lecture (`read_file`, `list_files`, `search_files`, `grep`, `glob`) sont routés vers le
**runner du poste** (un seul WebSocket, corrélation par `callId`). **Avant de paralléliser côté runner,
vérifier qu'il traite des requêtes de lecture concurrentes** (multiplexées par `callId`).
- Si **oui** : parallélisme complet (pensée du modèle **et** lectures se recouvrent).
- Si **non** (le runner sérialise) : on parallélise quand même les **sous-boucles** (la pensée du
  modèle, la majeure partie du temps de mur se recouvre) et on **sérialise seulement l'accès au runner**
  à sa frontière (petite file interne). Gain moindre mais réel, **zéro risque**. À trancher à la lecture
  du code runner ; par défaut, choisir la voie sûre.

## 5. Découpage
| SF | Titre | Contenu |
|----|-------|---------|
| **SF-39-21** | **Moteur : explorations concurrentes** | La boucle principale isole les `explore` d'un tour et les exécute via un pool borné (`explore-parallelism`). Compteurs de coût thread-safe imputés au tour (D4). Isolation des échecs (D5), ordre par `callId` (D6), stop/deadline partagés (D7). Frontière runner selon le drapeau §4. Réglage `app.atelier.explore-parallelism`. |
| **SF-39-22** *(option)* | **Doctrine : grouper les explorations** | Le system prompt / la description de l'outil apprennent à l'agent à **regrouper en un seul tour** les explorations **indépendantes** (et à ne PAS grouper quand l'une dépend du résultat de l'autre). Sans cette SF, le gain n'apparaît que quand le modèle groupe spontanément. |

**Ordre** : SF-39-21 d'abord (le moteur est utile même sans doctrine explicite, le modèle groupe déjà
parfois) ; SF-39-22 ensuite pour rendre le gain systématique.

## 6. Critères d'acceptation
- Deux `explore` émis dans un même tour s'exécutent **concurremment** (démontré : temps de mur < somme
  des durées ; ordre des `tool_result` conforme aux `callId`).
- La lecture seule est **inviolée** (aucune écriture/commande possible en sous-boucle — test).
- Le coût total (toutes sous-boucles) est **imputé au tour** et respecte le plafond par message (test).
- Un échec d'une exploration **n'affecte pas** les autres (test).
- Le plafond de parallélisme est **respecté** (test avec N > plafond → vagues).
- Isolation `user_id` / cible d'exécution **inchangée**.

## 7. Hors périmètre
- Tout **sous-agent qui écrit ou exécute** (bash). Reste interdit en sous-boucle.
- Le **fan-out multi-agents généralisé** / Managed Agents (`atelier/agent/`) — autre voie, autre débat.
- Le parallélisme **entre outils non-`explore`** d'un tour (on ne touche pas à l'ordre des écritures).

## 8. Préoccupations transversales
- **Plans / limites** : nouveau réglage de parallélisme ; le plafond par message (coût) reste la
  vérité — les compteurs doivent rester exacts sous concurrence (accès synchronisé). Composants :
  `AtelierChatService` (agrégation coût, progress), `AtelierProperties` (réglage), `AtelierExploration`
  (inchangée dans sa logique, appelée en parallèle).
- **Exécution / runner** : voir §4 (drapeau). Composant : la frontière runner de lecture.
- **Concurrence** : compteurs de tokens, `AtelierProgressListener`, agrégation des résultats — tous
  sous accès concurrent : à protéger.

---

## Pourquoi c'est clairement utile (et pas juste « joli »)
1. **Ça attaque la rentabilité par le bon bout.** Le coût d'un tour croît en **N²** sans hygiène de
   contexte. `explore` existe déjà pour ça (les 40 fichiers lus **ne remontent pas**). Le paralléliser
   ne change pas le coût **en tokens** (même travail) mais **raccourcit le mur** — donc réduit la
   fenêtre où « le tour vit dans le flux » et où quitter l'écran le tue.
2. **Gain net, risque quasi nul.** Parce que c'est **lecture seule**, la concurrence ne crée **aucun**
   conflit sur le poste — contrairement à des sous-agents écrivains, impossibles proprement sur un poste
   unique sans isolation par worktree. C'est le seul endroit où « plusieurs agents » est sûr chez nous.
3. **On réutilise l'existant.** `AtelierExploration` ne change pas ; on ne fait que **lancer plusieurs
   fois en parallèle** une brique éprouvée. Peu de code, peu de surface de régression.
4. **Aligné parité Claude Code** sans préempter la décision « construire vs Agent SDK » : on améliore
   une capacité déjà présente, on n'ouvre pas un chantier multi-agents qu'il faudrait peut-être jeter.
