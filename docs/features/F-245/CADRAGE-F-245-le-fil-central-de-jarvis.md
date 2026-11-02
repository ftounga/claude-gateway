# CADRAGE F-245 — Le fil central de Jarvis

**Statut :** cadrage du 2026-11-02 (demande PO du même jour). Aucun code. Famille « Jarvis » : F-230 → F-244, **F-245**.
**Estimation :** 5 SF, ≈ 4 j. **Ordre :** après F-244. **SF-245-01 et SF-245-02** précèdent F-242 et F-243, dont les
outils vivent dans le fil Jarvis.

## 1. Demande PO (2026-11-02)

Aujourd'hui, ce que dit le PO part dans le sujet ou le fil ouvert, même un « bonjour » ou un tour d'horizon qui
porte sur plusieurs clients.

Cible :

- **Un fil Jarvis unique**, commun à tout : clients, chantiers, vie personnelle.
- Quand une demande concerne un chantier ou un sujet précis, Jarvis la traite **dans ce chantier** et laisse une
  **ligne-lien** dans son propre fil.
- L'historique de chaque chantier reste **propre**.
- La cible doit être cohérente avec la mémoire du jour (SF-239-09, `JarvisFastMemory`) et le portrait (F-241).
- Il faut signaler l'impact sur le cache.

## 2. Constat : l'existant, vérifié le 2026-11-02 (`origin/main` `2ca051d1`)

| # | Constat | Où |
|---|---|---|
| 1 | `/jarvis` envoie tout dans le **fil commun du sujet choisi sur l'appareil**. Ce choix vient de `?sujet=` ou de `localStorage cg.jarvis.subject`, sans valeur par défaut (D19). **Ce n'est pas** le terminal ouvert dans l'Atelier : rien dans l'Atelier n'écrit ce choix. | `jarvis.component.ts` l. 190, 387-403, 648-655 |
| 2 | Les **réponses du cerveau rapide** (même un « bonjour ») sont **écrites dans le fil commun du sujet courant** (`via = JARVIS`, `brain = FAST`). Sans sujet choisi, rien n'est écrit. | `JarvisFastBrainService.converse` l. 276-280, `JarvisFastExchangeWriter` l. 31-46 |
| 3 | Une délégation (F-235) va par défaut au sujet et au chantier courants. Le modèle peut **nommer un autre sujet** (`sujet`, un UUID vérifié par `findByIdAndUserId`) ou **un chantier** (`chantier`, un entier). Sans sujet, Jarvis demande « Pour quel client ou quel sujet, Monsieur ? » (`ASK_SUBJECT`). | `JarvisFastBrainService.delegation()` l. 1246-1277, `route()` l. 298-329 |
| 4 | Le modèle de données : **client** = poste (`runner_hosts`, ou le client virtuel « Hébergé »), **sujet** = `workspaces`, qui **est** la conversation (`chat_thread_started_at`, `chat_thread_summary`), **chantier** = `subject_journey_chantiers`, **fil d'un chantier** = `atelier_messages.chantier_number` (F-221). | `atelier/Workspace.java`, `atelier/ChantierThread.java` l. 42-60 |
| 5 | **Aucun fil propre à Jarvis** et aucune conversation « système » par utilisateur. Les espaces spéciaux existants (`host_terminal`, `teams_terminal`) sont **par poste**. | `Workspace.java` l. 115-162 |
| 6 | **`JarvisFastMemory` est déjà transversale** : elle se réhydrate depuis **tous** les messages `via = JARVIS` du jour, tous sujets confondus (40 au plus). En somme, le « fil Jarvis » existe déjà comme une **requête**, pas comme un lieu. | `JarvisFastMemoryLoader` l. 31-35 |
| 7 | Le cerveau rapide connaît **tout l'univers** (clients > sujets > chantiers ouverts > contacts, 9 000 caractères au plus) dans son préfixe caché, et les **6 derniers messages** du fil du sujet courant dans le message volatil. Il peut donc déjà router vers un sujet ou un chantier nommé. | `JarvisFastContextBuilder` l. 98-141, 261-284 |
| 8 | Le cache côté Opus : les marques `ephemeral` (1 h) portent sur le système, le dernier outil et le dernier message, plus une marque intermédiaire. Le rejeu reprend le fil du sujet (hors messages `FAST`), filtré par chantier. La compaction se déclenche à 120 000 jetons et garde les 6 derniers messages ; son `replayable()` **ne filtre ni `FAST` ni les chantiers**. | `AnthropicAgentProvider` l. 97, 294, 986, 1007-1039 ; `AtelierChatService.buildReplayMessages` l. 957-981 ; `AtelierCompactionService` l. 612-618 |
| 9 | Les messages `FAST` ne sont **jamais rejoués**. Ils atteignent le tour Opus suivant **du même fil** sous forme de note jointe au message (`withFastExchanges`). | `AtelierChatService` l. 1830-1855 |
| 10 | Il n'existe **aucun message-lien** entre fils. Le plus proche est la carte « [Ouvrir le sujet] » de F-179 (`SubjectHandoff`, outil `ouvrir_sujet`), qui ne vient que du terminal de poste. | `atelier/SubjectHandoff.java` |
| 11 | `/jarvis` **n'affiche aucun historique** : seulement le dernier échange. Côté terminal, il y a la marque « 🎙 via Jarvis » et l'encart « Pendant ton absence, via Jarvis ». Il n'existe **pas de route par chantier** : on passe `?chantier=N` sur l'API et dans l'état de l'écran du sujet. | `jarvis.component.html` l. 159-193, `terminal-jarvis-absence.component.ts`, `app.routes.ts` |

**Conclusion :** le routage vers un sujet ou un chantier **existe déjà** (l'outil `deleguer`). Ce qui manque, c'est
un **lieu** pour tout le reste, une **ligne-lien**, et de **cesser d'écrire** les échanges du cerveau rapide dans le
fil d'un sujet.

## 3. Principes

- **Réutiliser, pas réinventer** : le fil Jarvis est un **espace de travail** (`workspaces`), d'un type spécial, sur
  le patron de `host_terminal` et `teams_terminal`. Il hérite du rejeu, de la compaction, du cache et de l'écran du
  terminal.
- **Préfixe stable** : la consigne système du fil Jarvis est **celle du terminal**, à l'octet près. L'univers et
  l'état des sujets vont **dans le message** (ils sont volatils). La liste d'outils du fil Jarvis est fixe pour ce
  type d'espace.
- **Justesse avant coût** : une demande qui touche un chantier est traitée **dans le chantier**, avec son historique
  et ses outils, jamais « de loin » depuis le fil Jarvis.
- **Isolation `user_id`** : un fil Jarvis par utilisateur, invisible des autres. Une ligne-lien ne pointe que vers un
  sujet possédé (`findByIdAndUserId`).
- **DESIGN_SYSTEM.md** : la palette navy de `/jarvis` (§22).

## 4. Décisions tranchées par défaut

| # | Sujet | Défaut | Pourquoi |
|---|---|---|---|
| D1 | Le lieu | Un espace `workspaces` de type **`jarvis_thread`**, **un par utilisateur**, créé à la première utilisation (unicité `user_id` + type), nommé « Jarvis ». Il est **sans poste** (client « Hébergé »), non supprimable, non renommable. Il est exclu de l'univers des clients (on ne s'y délègue pas « comme à un sujet »). | Patron existant. Rejeu, compaction et cache viennent gratuitement. |
| D2 | Ce qui va dans le fil Jarvis | (a) **Tous** les échanges du cerveau rapide (« bonjour », le point, l'humeur, le perso, le bref des mails), qui **ne s'écrivent plus** dans le sujet courant. (b) Les délégations **sans sujet précis** : un tour d'horizon multi-clients, une question générale ou personnelle, le courrier (F-242), l'agenda (F-243). (c) Les **lignes-liens**. | « Bonjour » ne pollue plus aucun chantier. |
| D3 | Ce qui va dans un sujet ou un chantier | Une délégation qui **concerne un sujet ou un chantier précis**, nommé ou évident (« relance le déploiement d'Helios »), comme aujourd'hui avec `deleguer(sujet, chantier)`. Le sujet choisi sur l'appareil reste un **indice fort** (« le sujet en vue »), mais il ne sert plus de **destination par défaut** des échanges sans tâche. | Le routage existe déjà. On ne change que le défaut. |
| D4 | Le doute | Une demande qui vise **un** sujet sans qu'on sache lequel donne la question actuelle `ASK_SUBJECT`. Une demande **générale** (plusieurs clients, perso, « où en suis-je de la semaine ») va au **fil Jarvis**, sans question. Le critère est donné dans la consigne du cerveau rapide (version suivante), avec des exemples, et il est mesuré au banc. | Justesse : ne pas deviner un chantier, ne pas poser de question inutile. |
| D5 | La ligne-lien | Quand une délégation part vers un sujet ou un chantier, le fil Jarvis reçoit une **ligne-lien** : `↗ Helios · chantier 3 — « relancer le déploiement » · en cours`. À la fin du tour, elle passe à `· fait` et porte la ligne 🎙 de la réponse (≈ 25 mots, F-240). Un toucher ouvre `/atelier/{id}?chantier=3` à la réponse. Stockage : des colonnes `ref_workspace_id`, `ref_chantier_number` et `ref_message_id` sur `atelier_messages`, avec `role = LINK`. | La demande du PO, une trace vers l'historique propre du chantier. |
| D6 | La ligne-lien dans le rejeu | Dans le fil Jarvis, une ligne-lien est rejouée à Opus sous forme d'un **texte court** (« [Transmis à Helios, chantier 3 : relancer le déploiement — fait : <ligne 🎙>] »). Opus sait ainsi ce qui a été confié, **sans** le contenu du chantier. Dans le chantier, rien n'est ajouté : la demande reformulée y figure déjà comme message utilisateur (F-235). | Le fil Jarvis garde le fil. Le chantier reste propre. |
| D7 | Opus dans le fil Jarvis | Un tour d'Opus dans le fil Jarvis reçoit **l'univers** (le même texte que le cerveau rapide, ≤ 9 000 caractères) **dans le message**, jamais dans la consigne. Il a les outils de **lecture** transverses existants (pièces, état des chantiers) et, demain, le courrier et l'agenda. Pour agir dans un chantier, il **ne le fait pas lui-même** : il répond « je le confie à Helios, chantier 3 » et le cerveau rapide route au tour suivant. | Pas d'action « de loin ». Préfixe stable. |
| D8 | L'écran `/jarvis` | Un volet **« Fil »** (replié par défaut) affiche les échanges du jour du fil Jarvis et les lignes-liens, qu'on peut toucher. En **présentation**, le volet est masqué, et les noms de clients des liens aussi. | Le PO voit son fil central. |
| D9 | Le terminal | Le fil Jarvis apparaît **en tête** de la liste des sujets de l'Atelier (épinglé, icône Jarvis). On peut y **taper** ; ce sont alors des tours d'origine `JARVIS` (F-244). | Un seul fil, à la voix comme au clavier. |
| D10 | Mémoire du jour | `JarvisFastMemory` est **inchangée** : elle lit déjà tous les messages `via = JARVIS`. Elle verra désormais les échanges dans le fil Jarvis et les demandes routées dans les sujets. **Les lignes-liens n'y entrent pas**, pour éviter le doublon avec la demande. | Cohérent avec SF-239-09, sans travail. |
| D11 | Le contexte du cerveau rapide | La partie volatile « 6 derniers messages du fil » lit le **fil Jarvis**, plus le fil du **sujet en vue** s'il y en a un (6 + 6 au plus), toujours **dans le message**. | Il entend ce qui vient de se dire, où que ce soit. |
| D12 | L'historique existant | **Aucune migration de messages.** Les « bonjour » passés restent où ils sont. | Rien de risqué pour un gain nul. |

## 5. L'impact sur le cache (demandé par le PO)

- **Le fil Jarvis est-il un préfixe ?** **Oui, pour Opus** : un tour d'Opus dans le fil Jarvis rejoue le fil, comme
  dans tout sujet, avec les mêmes marques de cache. **Non, pour le cerveau rapide** : son préfixe caché reste
  `[univers] → « Noté. » → échanges du jour` (F-239 / F-241), **inchangé**, puisque la mémoire du jour est déjà
  transversale.
- **Gain** : les sujets clients ne reçoivent plus les échanges du cerveau rapide (ils n'étaient pas rejoués, mais
  ils entraient dans la **compaction**, dont `replayable()` ne filtre pas `FAST`), ni les délégations générales.
  Leurs préfixes sont **plus courts et plus stables**.
- **Risque** : le fil Jarvis peut devenir **long et hétérogène** (un fil pour tout). (a) Les messages `FAST` n'y sont
  **pas rejoués** (règle existante). Seuls les tours d'Opus et les lignes-liens (courtes, D6) le sont. (b) La
  compaction (120 000 / 6) s'applique comme ailleurs. **Défaut** : SF-245-05 la corrige pour **exclure `FAST`**, sur
  tous les espaces, puisque c'est une erreur existante. (c) Les tours d'Opus dans le fil Jarvis sont **épars**, donc
  le cache de 1 h est souvent froid : la première écriture de cache coûte, au tarif de 8 $ par million de jetons
  écrits pour Opus 5.5. **Défaut** : on mesure `cache_write / input` sur le fil Jarvis pendant 7 jours avant tout
  levier. Pas de « nouveau départ » quotidien automatique : c'est une variante de F-117 (en cours), à proposer
  seulement si la mesure le justifie.
- **Ce qu'on ne fait pas** : mettre l'univers ou l'état des chantiers dans la consigne système (cela casserait le
  préfixe à chaque changement d'état).

## 6. Données et points d'accès

- `workspaces` : la valeur de type `jarvis_thread` (colonne de type existante ou drapeau, selon le patron
  `host_terminal`), avec une contrainte d'unicité par `user_id`.
- `atelier_messages` : `role = LINK`, et les colonnes `ref_workspace_id`, `ref_chantier_number`, `ref_message_id`,
  `link_status` (`EN_COURS`, `FAIT`, `ECHEC`).
- Points d'accès :
  - `GET /api/jarvis/thread` (crée si besoin, renvoie l'identifiant) ;
  - `GET /api/jarvis/thread/today` (les échanges du jour et les liens) ;
  - les routes de chat existantes pour le reste ;
  - tous filtrés sur le `user_id` du principal.
- Migrations : les **prochaines libres** au moment du dev.

## 7. Découpage — SF-245-01 → SF-245-05 (≈ 4 j)

| SF | Titre | Estimation | Dépend de |
|---|---|---|---|
| SF-245-01 | Le fil Jarvis existe | 0,75 j | — |
| SF-245-02 | Ce qui n'est à personne va au fil Jarvis | 1 j | SF-245-01 |
| SF-245-03 | Le chantier traite, le fil garde la ligne-lien | 1 j | SF-245-02 |
| SF-245-04 | Voir le fil | 0,75 j | SF-245-03 |
| SF-245-05 | Le banc du routage et la mesure du cache | 0,5 j | SF-245-03 |

**Préoccupation transversale « Navigation / routing »** (SF-245-03 et SF-245-04). Composants impactés :

- `app.routes.ts` (`atelier/:id` avec `?chantier=`) ;
- l'écran du sujet (ouverture d'un chantier par paramètre, `terminal-lane-pane`) ;
- la liste des sujets de l'Atelier (épinglage) ;
- le sélecteur de sujet de `/jarvis` (`chooseSubject`, `subjectSwitch`) ;
- `readAlert`.

**Préoccupation « Contexte tenant »** (SF-245-01) : la résolution d'un espace de travail par `findByIdAndUserId`.
Composants impactés :

- `JarvisUniverseService` (exclure `jarvis_thread`) ;
- `JarvisFastContextBuilder` ;
- `RoutineTurnRunner` (pas de routine dans le fil Jarvis) ;
- les écrans de sujets (pas de poste) ;
- `AccountService` (purge).

### SF-245-01 — Le fil Jarvis existe (0,75 j)

**Objectif :** chaque utilisateur a un fil Jarvis unique, créé à la demande, visible en tête de l'Atelier.

- Le type `jarvis_thread` et son unicité. `GET /api/jarvis/thread`. L'exclusion de l'univers et des routines. Non
  supprimable et non renommable. L'épinglage dans la liste (D9). La purge du compte.

**Critères d'acceptation :**
1. Deux appels concurrents ne créent qu'un fil.
2. Le fil n'apparaît pas dans l'univers du cerveau rapide.
3. Il est en tête de l'Atelier.
4. Le supprimer ou le renommer donne 400.
5. L'utilisateur B reçoit 404 sur le fil de A.
6. La consigne système d'un tour dans le fil Jarvis est **identique à l'octet** à celle d'un sujet hébergé (test).

**Plan de test :**
- Unitaires (unicité).
- Intégration : création concurrente, exclusions.
- **Isolation** A/B.
- Test d'égalité de la consigne.
- Spec de la liste.

**Hors périmètre :** le routage.

### SF-245-02 — Ce qui n'est à personne va au fil Jarvis (1 j)

**Objectif :** « bonjour », le tour d'horizon et le perso vivent dans le fil Jarvis. Plus rien ne s'écrit dans un
sujet sans tâche pour lui.

- `JarvisFastExchangeWriter` écrit dans le fil Jarvis.
- Les délégations générales vont au fil Jarvis (D4), en remplaçant `ASK_SUBJECT` quand la demande est générale.
- Le contexte D11. L'univers dans le message des tours d'Opus du fil Jarvis (D7).
- Les tours du fil Jarvis sont `origin = JARVIS` (F-244).
- La consigne du cerveau rapide passe à la **version suivante** au moment du dev.

**Critères d'acceptation :**
1. « Bonjour », avec Helios en vue, ne crée **aucun** message dans Helios.
2. « Fais-moi un tour d'horizon de mes clients » est délégué au fil Jarvis, sans question.
3. « Relance le déploiement » avec Helios en vue est délégué à Helios (comportement actuel).
4. « Relance le déploiement » sans sujet en vue et sans nom donne `ASK_SUBJECT`.
5. L'univers n'est pas dans la consigne système (test d'égalité).
6. Isolation A/B.

**Plan de test :**
- Unitaires du routage.
- Intégration : écriture des échanges, délégation générale, délégation ciblée.
- **Non-régression** de `deleguer(sujet, chantier)`.
- **Isolation**.

**Hors périmètre :** la ligne-lien.

### SF-245-03 — Le chantier traite, le fil garde la ligne-lien (1 j)

**Objectif :** une demande routée vers un chantier y est traitée, et le fil Jarvis en garde une ligne-lien vivante
qui s'ouvre d'un toucher.

- `role = LINK` et les colonnes `ref_*`. Création au départ de la délégation, mise à jour à la fin (fait, échec, ligne
  🎙).
- Le rejeu court dans le fil Jarvis (D6). L'exclusion de la mémoire du jour (D10).
- L'ouverture `/atelier/{id}?chantier=N` à la bonne réponse.

**Critères d'acceptation :**
1. Une délégation vers Helios, chantier 3, crée une ligne-lien `en cours`, puis `fait` avec la ligne 🎙.
2. L'historique du chantier 3 ne contient **que** la demande reformulée et la réponse, sans « bonjour » ni ligne-lien.
3. Un toucher ouvre le chantier 3 à la réponse.
4. Un tour d'Opus suivant dans le fil Jarvis voit « [Transmis à Helios… fait…] ».
5. Une ligne-lien vers un sujet d'autrui est impossible (vérification `findByIdAndUserId`).
6. Isolation A/B.

**Plan de test :**
- Unitaires : rendu du rejeu, états.
- Intégration : le cycle complet d'une délégation, l'échec, l'interruption.
- Navigation (spec du routeur).
- **Isolation**.

**Hors périmètre :** l'écran du fil.

### SF-245-04 — Voir le fil (0,75 j)

**Objectif :** le PO voit son fil du jour dans `/jarvis` et ouvre un chantier depuis une ligne-lien.

- `GET /api/jarvis/thread/today`. Le volet « Fil » (D8).
- La présentation : le volet est masqué, et les noms aussi.
- DESIGN_SYSTEM.md, §22 : une ligne pour le volet et la ligne-lien (couleurs existantes).

**Critères d'acceptation :**
1. Le volet liste les échanges du jour et les liens dans l'ordre.
2. Un toucher sur un lien ouvre le chantier.
3. En présentation, le volet n'apparaît pas.
4. Pas de débordement à 360 × 740.
5. Isolation A/B sur `today`.

**Plan de test :**
- Specs du composant.
- Intégration de l'endpoint et **isolation**.
- Contrôle visuel headless à 1366 × 768 et 360 × 740.

**Hors périmètre :** la recherche dans le fil.

### SF-245-05 — Le banc du routage et la mesure du cache (0,5 j)

**Objectif :** le routage est mesuré au banc, et le cache du fil Jarvis l'est en prod.

- Banc CONVERSATION : **8 scénarios** de routage :
  - un salut avec un sujet en vue ;
  - un tour d'horizon ;
  - du perso ;
  - un chantier nommé ;
  - un chantier évident par le sujet en vue ;
  - une demande ambiguë ;
  - un mail (après F-242) ;
  - « où en est Helios ? » (réponse sans délégation, qui reste dans le fil Jarvis).
- La compaction exclut `FAST` (§5 b).
- Mesure : `cache_write / input` des tours du fil Jarvis contre ceux des sujets, et le nombre de messages `via = JARVIS`
  sans délégation écrits dans un sujet (cible : 0), sur 7 jours.

**Critères d'acceptation :**
1. Le banc passe à 90 % au moins, avec zéro défaut grave (un « bonjour » dans un chantier est un défaut grave).
2. La compaction d'un sujet n'inclut plus de message `FAST` (test).
3. La requête de mesure est documentée dans `docs/ops`.

**Plan de test :**
- Le banc.
- Le test unitaire de `replayable()`.
- **Isolation** de la requête de mesure (par `user_id`).

**Hors périmètre :** un « nouveau départ » automatique (F-117).

## 8. Mesure de réussite

- **Zéro** message `FAST` dans un sujet client après le déploiement.
- Le banc de routage passe à 90 % au moins.
- `cache_write / input` du fil Jarvis mesuré à J+7, avant tout levier.
- Ressenti du PO : « je parle à Jarvis, et mes chantiers restent propres ».

## 9. Points de contact

| Avec | Point | Règle |
|---|---|---|
| **F-240** D9 | « Dis-m'en plus » relit, après un rechargement, la dernière réponse d'Opus **du fil courant**. | Le « fil courant » devient **le fil Jarvis, ou la réponse pointée par la dernière ligne-lien**. À intégrer dans SF-240-04 si elle n'est pas encore codée, sinon dans SF-245-03. |
| **F-240** SF-240-03 | L'écran en trois étages. | Le volet « Fil » est **à part** des trois étages : il montre l'historique, pas la réponse courante. |
| **F-241** | Le portrait, les souvenirs `📌`, `jarvis_allusions`. | Les conversations personnelles vont désormais au **fil Jarvis**, et plus dans un sujet client : c'est un **gain de confidentialité**. Le portrait reste joint au message en mode Jarvis (D6 de F-241), y compris dans le fil Jarvis. « Une allusion par conversation » : la conversation reste la session `/jarvis`, inchangée. |
| **F-239** SF-239-09 | `JarvisFastMemory`. | Inchangée (D10). |
| **F-235** | `deleguer(sujet, chantier)`, `JarvisDelegationRegistry`. | Réutilisés. Le registre porte aussi l'identifiant de la ligne-lien à mettre à jour. |
| **F-221** | Les fils par chantier. | Réutilisés tels quels. |
| **F-179** | `SubjectHandoff`. | Pas de fusion : la ligne-lien est un message, la carte de transmission reste une carte de transcription. |
| **F-117** (en cours) | Le repli d'historique et le « nouveau départ ». | Le fil Jarvis est long par nature : F-117 lui sera utile. Pas de nouveau départ automatique ici. |
| **F-242 / F-243** | Les outils de courrier et d'agenda. | Ils sont offerts **seulement** dans le fil Jarvis. |
| **F-244** | L'origine. | Les tours du fil Jarvis sont `JARVIS`, même tapés. |

## 10. Hors périmètre

La migration des messages passés ; plusieurs fils Jarvis (par thème) ; un fil Jarvis partagé ; Opus qui agit dans
un chantier depuis le fil Jarvis ; un nouveau départ automatique ; la recherche dans le fil.
