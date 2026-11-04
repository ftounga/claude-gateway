# CADRAGE F-247 — Le registre d'état : Jarvis sait ce qui se passe, poste éteint

**Statut :** cadrage du 2026-11-05 (besoin PO du même jour, proposition validée « à affiner »). Aucun code.
Famille « Jarvis » : F-230 → F-246, **F-247**. Base vérifiée : `origin/main` `7fa391ae`.
**Estimation :** 9 SF, ≈ 13,25 j (aucune au-delà de 2 j), plus 2 SF en réserve sous condition de mesure.

## 1. Le besoin (PO, 2026-11-05)

Le runner tourne sur le poste client du PO. Quand ce poste est **éteint, en veille ou déconnecté**, le PO parle à
Jarvis depuis son téléphone et veut des réponses aux **questions d'état** :

- le statut d'un ticket, d'une MR, d'un pipeline ;
- la version déployée ;
- ce qui attend, les besoins, ce qui est bloqué ;
- les messages Teams non lus.

Jarvis peut répondre avec une information **qui n'est pas la plus fraîche**, à condition de **le dire**.

**Écarté par le PO, à ne pas reproposer :** une copie du dépôt, ou du travail hors du poste. F-247 ne garde que des
**faits d'état datés**. Elle ne garde ni code, ni fichier, ni exécution.

**Exigence forte : zéro ralentissement.** Rien ne doit ralentir l'application ni le travail du PO (§6).

## 2. L'existant, vérifié dans le code

| Brique | Ce qu'elle fait déjà | Ce qui manque pour le besoin |
|---|---|---|
| **Univers Jarvis** (`JarvisUniverseService`, `JarvisUniverse`) | Clients (un client = un `RunnerHost`), sujets, chantiers (phase, étapes, `waitingOn`), interlocuteurs, et `connected` par poste. Tout est calculé **côté serveur** : il reste lisible poste éteint. | Ne dit pas **depuis quand** le poste est déconnecté. Ne contient aucun fait qui vit sur le poste (ticket, MR, version). |
| **Contexte du cerveau rapide** (`JarvisFastContextBuilder.build`, dans le **message**) | Blocs datés « état à HH:MM » : attentes du sujet courant, à envoyer, délégations en cours, derniers échanges, alertes de veille des 24 h. Bornes : `MAX_BLOCK` 2 500, `MAX_TOTAL` 20 000. L'univers sert de préfixe relu (`reference`, SF-239-11). | Aucun bloc d'état du poste. Les attentes ne portent que sur le **sujet courant**. |
| **Outils du cerveau rapide** (`JarvisFastBrainPrompt.tools()` : `deleguer`, `pieces`, `agenda`, `montrer`) | Patron d'un outil en **lecture seule** : lu une fois par réponse, puis ancré (`gate.ground`). Exemple : `agenda`, consigne v16. | Pas d'outil pour lire l'état connu. |
| **Veilles F-230** (`atelier/veille/*`) | Une sonde **lecture seule** écrite une fois par l'agent et validée par le PO. Ensuite, des passages **sans IA**, lancés par commande runner (`RunnerToolGateway.bash`, plafond 60 s). Éléments normalisés `{id, titre, statut, assigné, mis_à_jour, dernier_commentaire}` gardés dans `jarvis_watch_snapshots`. `MISSED_OFFLINE` et rattrapage unique. Créneaux jours ouvrés (`JarvisWatchSlots`), verrou multi-pod (`claimSlot`), 4 passages simultanés au plus. | Les instantanés ne sont **lus par personne** hors du diff. Aucune veille « sans alerte ». **Aucune garde « tour en cours sur ce poste »** : une sonde peut partir pendant un tour. Pas de priorité basse. |
| **Runner : activité et parallélisme** (`RunnerActivity.busy()`, F-111 ; `BashTool`, sémaphore à N, F-195) | Le runner sait s'il a une commande en vol. Il accepte N commandes bash à la fois. | Pas de notion de **commande de basse priorité** qui cède la place ou se fait interrompre. |
| **Ruptures du runner** (`runner_disconnects`, F-161) | `ARRET_PROPRE` (`POST /runner/disconnect`), `INACTIVITE` (poste éteint ou en veille), `SOCKET_*`, avec la date. | Non exposées à Jarvis. |
| **Journal du runner** (`runner_audit`) | Chaque appel d'outil : `tool`, `target`, `outcome`, `exit_code`, durée. **Pas la sortie.** Index `(user_id, workspace_id, created_at)`. | — |
| **Transcription d'un tour** (`TerminalTranscript`, `atelier_messages.terminal_json`, F-30) | Commande **et** sortie de chaque outil, bornées (`max-transcript-chars`), persistées à la fin du tour. | Personne n'en tire les faits d'état observés. |
| **Données serveur** : attentes (`terminal_actions`, F-175/F-180), besoins (`app_requests`), à envoyer, Radar (`radar_*`, F-99→F-104), alertes de veille | Déjà en base. **Déjà lisibles poste éteint.** | Seules les attentes du sujet courant sont montrées. |
| **Opus en mode Jarvis sur un poste éteint** (`AtelierChatService.noteMachine`) | Les outils échouent en `runner_unavailable`, la machine est notée `OFFLINE`, et Opus répond « poste hors ligne ». | C'est le « je ne sais pas » à faire disparaître. Il coûte un tour Opus pour rien. |
| **Teams** (runner `teams_*`, Vigie F-106) | `teams_mentions`, `teams_find_conversations` et `teams_read_conversation`, par Chrome en débogage. | **Aucun compte de non-lus.** Usage mesuré sur 30 j (F-234) : Radar/Teams = 0. |
| **Savoir durable** (gouvernance, carte de poste `host_map_files`) | Le savoir **stable** : faits de structure, conventions. | Hors sujet ici : F-247 garde de l'**état volatil daté**. On ne mélange pas les deux. |

**Conclusion.** Les données serveur sont déjà lisibles poste éteint : il suffit de les **montrer**, sans les copier.
Il manque trois choses :

1. une mémoire datée de ce qui ne vit **que** sur le poste (tickets, MR, pipelines, versions) ;
2. une façon de la remplir qui ne coûte rien et ne gêne rien ;
3. la manière de la **dire** honnêtement.

## 3. Principes

- **Gateway-First.** La **passerelle** date chaque fait, calcule depuis quand le poste est déconnecté et choisit ce
  qui entre dans le bloc. Le modèle **reçoit** des faits déjà datés. Il n'invente ni date ni fraîcheur.
- **Provider-First / Provider Independence.** Pas de nouvel appel modèle en V1. La réserve R2, si elle est un jour
  activée, passe par `AIProvider`.
- **Justesse avant coût.** Un fait n'est enregistré que s'il a été **lu dans une sortie d'outil réelle** (tour ou
  relevé), jamais à partir d'une phrase du modèle (§4.2). Une information ancienne n'est **jamais** présentée comme
  actuelle (§5.3, porte de banc à 100 %).
- **Une seule vérité.** Le registre ne garde **que** ce qui vit sur le poste. Les attentes, besoins, à envoyer, Radar,
  chantiers et alertes sont **lus en direct** dans leurs tables. Ils ne sont jamais dupliqués.
- **Isolation.** Toute lecture et toute écriture se font sous `user_id` et, en plus, sous `host_id` (le client).
- **Préfixe stable.** Rien de volatil dans les consignes système. Le bloc « État connu » va **dans le message**. La
  consigne fixe du cerveau rapide passe une fois en v19, et l'outil `etat` s'ajoute une fois à la liste des outils :
  le préfixe change une seule fois, puis reste stable, comme pour `agenda` en v16.
- **Zéro ralentissement** : critères mesurables au §6. Ce sont des portes, pas des intentions.

## 4. La conception

### 4.1 Le registre (`jarvis_state_facts`)

Un fait = **(client, objet, attribut, valeur, observé_à, source, confiance)**.

| Colonne | Contenu |
|---|---|
| `id`, `user_id`, `host_id` (le client) | `host_id` est obligatoire. Index `(user_id, host_id, observed_at desc)`. |
| `workspace_id` (nullable) | Le sujet où le fait a été vu, s'il est connu. |
| `object_kind` | `TICKET`, `MR`, `PIPELINE`, `DEPLOYMENT`, `TEAMS` (réserve), `OTHER`. |
| `object_key` | Identifiant technique **en clair**, normalisé. Exemples : `JIRA:PROJ-123`, `GITLAB:groupe/projet!42`, `PIPELINE:groupe/projet@main`, `K8S:ns/deploiement`. Index `(user_id, host_id, object_key)`. |
| `attribute` | `status`, `assignee`, `title`, `version`, `result`, `updated_at`, `blocked_by`… (liste fermée). |
| `value_sealed` | La valeur, **scellée** (`PortraitSeal`, comme `jarvis_calendar_events`) et bornée à 300 caractères. |
| `previous_value_sealed`, `previous_observed_at` | La valeur d'avant. Elle sert à dire « passé de X à Y le … ». Pas d'historique au-delà en V1. |
| `observed_at` | **L'heure de la sortie d'outil**. Ce n'est pas l'heure d'écriture. |
| `source`, `source_ref` | `TOUR` (id du message), `RELEVE` ou `VEILLE` (id du passage). |
| `confidence` | `HAUTE` : JSON d'API parsé. `MOYENNE` : texte de CLI reconnu par un motif strict. Aucune autre valeur. |

**Écriture : un upsert idempotent**, avec la clé unique `(user_id, host_id, object_key, attribute)`.

- Si la valeur est la même, seule `observed_at` avance.
- Si elle diffère, la valeur actuelle passe en `previous`.
- Un fait plus ancien que celui déjà en base est **ignoré**. L'ordre d'arrivée ne compte pas.

**Bornes** : 5 000 faits par client. Au-delà, les plus anciens sont purgés. Une purge nocturne retire les faits non
revus depuis la rétention du client (30 j par défaut).

**Réglage par client (`jarvis_state_settings`)** :

- un interrupteur `enabled` ;
- les familles autorisées ;
- `extract_from_turns` (constats des tours) ;
- `releve_enabled` (relevé automatique) ;
- la rétention ;
- un « effacer tout ».

Le réglage est **désactivé par défaut**, client par client (D3). Un client désactivé n'a **rien** côté serveur :
pas d'écriture, et les faits existants sont purgés quand on désactive.

### 4.2 Source (a) : ce qu'Opus a vu pendant les tours

On compare trois mécanismes :

| Mécanisme | Coût | Justesse | Risque pour le reste | Verdict |
|---|---|---|---|---|
| **Sortie structurée** : Opus écrit un bloc `<<etat>>` à chaque tour | Des jetons de sortie à **chaque** tour | Opus peut écrire un fait **déduit**, pas vérifié | Modifie la consigne du terminal. Distrait le raisonnement, ce que la règle « justesse avant coût » interdit. Allonge le tour, alors que l'exigence est zéro ralentissement. | **Écarté** |
| **Petit modèle en différé** sur la fin de tour | ≈ 0,001-0,003 € par tour, à **chaque** tour | Correcte, mais c'est un résumé de résumé, qui peut halluciner | Aucun sur le tour s'il est vraiment différé | **Réserve R2**, sous condition de mesure |
| **Extraction déterministe des sorties d'outils** (`jira`/REST Jira, `glab`/API GitLab, `kubectl`, `helm`, `argocd`) | **0 €**, quelques ms de CPU serveur | **La meilleure possible** : la valeur est celle que l'outil a rendue, à l'heure où il l'a rendue | Aucun : elle est lancée après la persistance du tour, sur un exécuteur à part | **Retenu** |

**Fonctionnement :**

1. À la fin du tour, **après** l'écriture de `terminal_json`, la passerelle **dépose** la référence du message dans
   une file bornée (`offer`, jamais `put`). C'est tout ce qui se passe dans le chemin du tour : moins d'1 ms.
2. Un exécuteur dédié, sur un seul fil en priorité basse, relit les blocs **sous `user_id`**.
3. Il applique des **extracteurs purs**. Chacun reconnaît une forme de commande **et** une forme de sortie. Les sorties
   en erreur, tronquées ou ambiguës sont ignorées. En cas de doute, rien n'est écrit.
4. Il fait l'upsert.

Si la file est pleine, l'élément est jeté et compté. Un fait manqué vaut mieux qu'un tour ralenti.

**Avant d'écrire les extracteurs**, SF-247-03 fait un **inventaire sur 30 jours** des formes de commande réelles en
prod. On compte déjà ≈ 777 appels Jira/Confluence par `bash` sur 30 j. On écrit les extracteurs des formes réellement
utilisées, par ordre de fréquence, sans en supposer d'autres.

### 4.3 Source (b) : le relevé automatique, lecture seule, sans IA

**On réutilise les veilles** (F-230 D7 : « ne pas dupliquer le moteur »). Un relevé est une veille avec
`purpose = STATE`. Il en reprend tout : la sonde écrite **une fois** par l'agent puis validée par le PO, la règle
lecture seule (D5), les créneaux, le verrou multi-pod, la panne notifiée. Il s'en distingue par quatre points :

- **pas d'alerte, ni d'appel IA, même sur changement** : les éléments de l'instantané deviennent des faits (upsert) ;
- **sa cadence** : toutes les heures de 8 h à 20 h, jours ouvrés, avec un réglage par client ;
- **sa portée** : le client, et non un chantier ;
- **ses sondes types**, proposées à l'activation du client et validées une par une par le PO : mes tickets ouverts,
  les MR ouvertes, les derniers pipelines des branches principales, les versions déployées. Elles passent par l'outil
  `veille_poser` existant, avec un nouveau paramètre `usage: etat`.

Les **veilles existantes** (`purpose = ALERT`) alimentent aussi le registre à partir de leurs instantanés : c'est
gratuit, et c'est la source (c).

**Quand un relevé passe-t-il ?**

- **à l'heure** ;
- **à l'inactivité** : aucune activité runner depuis 20 min (hors relevés) et dernier relevé de plus de 30 min. C'est
  l'approximation retenue de « avant la mise en veille » : un portable s'endort après une période d'inactivité ;
- **au départ propre** : à l'arrêt du runner, et seulement si un relevé est dû (dernier relevé de plus de 15 min),
  avec un plafond de 8 s. Au-delà, le runner s'arrête quand même, et l'arrêt n'attend jamais plus.

**Une extinction brutale, une coupure d'alimentation ou une mise en veille forcée ne peuvent pas être captées.** Le
relevé horaire borne alors l'âge du dernier état à environ 1 h au moment de la coupure. Jarvis le **dit** (§5).

**Les gardes de non-gêne** sont détaillées au §6 : ne jamais passer pendant un tour, céder la place, être interrompu,
et rester borné.

### 4.4 Source (c) : l'existant, lu en direct

Ces données sont lues en direct par la passerelle quand elle compose le bloc. Elles ne sont jamais copiées dans le
registre :

- les attentes ouvertes de **tous** les sujets du client, et pas seulement du sujet courant ;
- les besoins ;
- les messages à envoyer ;
- les chantiers bloqués ou en attente (univers) ;
- les engagements Radar ouverts ;
- les alertes de veille ;
- la dernière rupture du poste (`runner_disconnects`).

### 4.5 Ce que Jarvis en fait

**Le bloc « État connu »** est composé par la passerelle, dans le **message** du cerveau rapide. Il est borné à
2 000 caractères et reste vide si rien n'est connu.

```
## État connu — client CAGIP
Poste déconnecté depuis hier 18:05 (arrêt propre). Dernier relevé : hier 17:42.
- PROJ-123 « Migration SSO » : En revue (vu hier 17:42, relevé) — avant : En cours
- MR projet!42 : ouverte, pipeline échoué (vu hier 16:10, tour)
- Déployé en prod : api 2.14.1 (vu hier 11:03, tour)
- 3 attentes ouvertes (état à 08:12) ; 1 message à envoyer
```

**Règles de composition, déterministes :**

- Le client courant passe d'abord, puis les autres clients au poste déconnecté.
- Pour chaque client : ce qui a changé depuis le dernier relevé, puis ce qui est bloqué, puis le reste. 12 lignes au
  plus par client.
- Chaque ligne porte l'heure **et** la source.
- Une ligne de plus de 24 h porte la mention « ancien ».
- Quand le poste est connecté, seuls les faits de plus de 15 min entrent, avec la mention « vu à ». Le cerveau rapide
  délègue alors comme aujourd'hui toute question d'état qui exige le frais.

**L'outil `etat`** donne au cerveau rapide l'accès au reste du registre, en lecture seule :

- il est appelé une fois par réponse ;
- il cherche par `object_key`, par famille ou par client, sur index, et rend 20 lignes au plus ;
- il rend les lignes déjà datées par la passerelle ;
- son résultat est ancré (`gate.ground`), comme celui d'`agenda`.

**Poste éteint**, une question d'état n'est **plus déléguée** à Opus quand le registre répond. Si le registre ne sait
pas, Jarvis le dit (« Je n'ai rien de relevé sur PROJ-456 ; le poste est éteint depuis 18 h ») au lieu de lancer un
tour Opus voué à échouer en `runner_unavailable`.

Le cas d'une demande d'**action** poste éteint ne change pas : elle reste déléguée, et la file existante s'applique.

**Opus en mode Jarvis** : si le poste du sujet visé est hors ligne au départ du tour, le même bloc est joint **au
message** sous forme d'une note `JarvisStateNote`, sur le patron de `JarvisOralNote`. La consigne du terminal reste
intacte.

**En mode présentation**, il n'y a ni bloc ni outil : `etat` ne rend rien, comme pour le courrier et l'agenda
(F-242/F-243).

## 5. Les décisions par défaut (trancher sans solliciter le PO)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Extraction déterministe** des sorties d'outils. Pas de sortie structurée d'Opus. Pas de petit modèle en V1 (réserve R2). | Coût nul, meilleure justesse, aucun effet sur le raisonnement ni sur la durée du tour (§4.2). |
| D2 | Le registre ne garde **que** l'état qui vit sur le poste. Tout ce qui est déjà en base est lu en direct. | Une seule vérité. Pas de désynchronisation. |
| D3 | **Désactivé par défaut, par client**. Jarvis propose de l'activer la première fois qu'il ne sait pas répondre poste éteint (une fois par client). Un geste suffit, et toutes les familles sont cochées. | Des données client sont gardées côté serveur. La décision se prend client par client, jamais en affaiblissant une règle globale. |
| D4 | Les valeurs sont **scellées** (`PortraitSeal`). Les identifiants techniques restent en clair pour l'index. | Même régime que l'agenda. Une recherche indexée sans tout déchiffrer. |
| D5 | Le relevé **réutilise les veilles** (`purpose = STATE`) et leurs sondes validées par le PO. | F-230 D7. Pas de second moteur. |
| D6 | Le départ est capté par **l'inactivité** (20 min) et par **l'arrêt propre** (plafond 8 s). L'extinction brutale n'est pas captée, et c'est dit. | Rien d'autre n'est fiable sans hook système. L'arrêt n'attend jamais au-delà du plafond. |
| D7 | Les **portes de non-ralentissement** du §6 sont bloquantes à la review et à la mesure J+7. | Exigence PO forte. |
| D8 | La date et la fraîcheur sont **calculées par la passerelle**. Une garde déterministe ajoute « relevé de HH:MM » à toute phrase dite qui cite un fait de plus d'1 h sans marque de temps. | Jamais d'info ancienne dite comme actuelle, même si le modèle oublie. |
| D9 | Poste éteint, une question d'état reçoit sa réponse **du registre**, pas d'un tour Opus. Une demande d'action est toujours déléguée. | Moins de tours voués à l'échec. Une action exige le poste. |
| D10 | **Teams en réserve (R1)**. Aucun compte de non-lus n'existe. Le relevé ne lance **jamais** Chrome. | Usage Teams/Radar mesuré à 0 sur 30 j. Lancer Chrome gênerait le PO. |
| D11 | En **présentation**, rien n'est montré. | Même règle que F-242/F-243. |
| D12 | Les **coupe-circuits** : `APP_JARVIS_STATE_ENABLED` (tout), `…_EXTRACT_ENABLED`, `…_RELEVE_ENABLED`, `…_BLOCK_ENABLED`. Plus un désarmement automatique du relevé d'un poste préempté plus de 3 fois dans la journée. | On revient en arrière sans déployer. |

## 6. Zéro ralentissement : les critères mesurables (portes)

| # | Critère | Seuil / preuve |
|---|---|---|
| Z1 | **L'extraction est hors du chemin de la réponse.** | Test : le tour se termine et le message est persisté **avant** toute extraction, et un extracteur bloqué ne retarde rien. Mesure : le temps du dépôt en file est ≤ 1 ms au p99. Aucun appel modèle ajouté, synchrone ou non (vérifié par test sur le fournisseur simulé). |
| Z2 | **Durée des tours Opus** (`atelier_turn_measures`). | p50 et p90 à ≤ +2 % sur 7 j, à volume comparable, par rapport à la base de départ de SF-247-01. |
| Z3 | **Latence de Jarvis** (`jarvis_turn_timings` : `fast_first_token_ms`, `first_sound_ms`). | p50 et p90 à ≤ +5 % ou ≤ +100 ms. La composition du bloc est mesurée (`state_block_ms`) et doit rester ≤ 20 ms au p95. |
| Z4 | **Jamais de relevé pendant un tour sur le même poste.** | Trois gardes : (1) côté serveur, aucun tour vivant connu, et aucune entrée `runner_audit` hors relevé sur les sujets du poste depuis 2 min (requête indexée) ; (2) côté runner, une commande `priority: low` est **refusée** (`busy_deferred`) si une autre commande est en vol (`RunnerActivity`) ; (3) **préemption** : une commande normale qui arrive tue la sonde en ≤ 500 ms (`preempted`), le passage est marqué `YIELDED` et repris au créneau suivant. Mesure : **0** chevauchement relevé/tour dans `runner_audit` sur 7 j. |
| Z5 | **La charge du runner est bornée.** | Une sonde à la fois. 20 s par sonde, 60 s par relevé. Processus en **priorité basse** : `BelowNormal` sous Windows, `nice 19` ailleurs. Temps CPU du processus ≤ 5 s par relevé, remonté par le runner et journalisé. Au plus 1 relevé par heure, plus le départ. |
| Z6 | **La base est bornée.** | Les requêtes passent toutes par un index (vérifié en revue). Le bloc lit ≤ 60 lignes, et l'outil ≤ 20. 5 000 faits au plus par client. L'upsert se fait en une seule requête. |
| Z7 | **Pas d'effet sur le cache.** | Le préfixe change une seule fois (outil `etat` + consigne v19), puis il est identique à l'octet d'un tour à l'autre (test existant étendu). Le `cache_write` du cerveau rapide est stable à J+7. |
| Z8 | **Revenir en arrière.** | Coupe-circuits D12. Le désarmement automatique est testé. |

Si un seuil est dépassé à J+7, le coupe-circuit concerné est mis à `false` et on cherche la cause **avant** toute
réactivation.

## 7. Mesurer la valeur

**La base de départ (SF-247-01, avant tout le reste).** Pour chaque échange Jarvis, `jarvis_turn_timings` reçoit :

- `host_offline` : le poste du sujet visé est-il hors ligne ?
- `state_question` : est-ce une question d'état ? La classification est déterministe : verbe ou objet d'état, clé de
  ticket ou de MR, ou « où en est », « version », « bloqué », « attend », « non lus ».
- `state_answer` :
  - `UNKNOWN` : Jarvis dit ne pas savoir. Il s'agit d'un lexique fermé (« je ne peux pas vérifier », « poste hors
    ligne / éteint / déconnecté », « je n'ai pas l'information »), ou d'un tour Opus où la machine est notée
    `OFFLINE` ;
  - `KNOWN_STALE`, `KNOWN_FRESH` ;
  - `NA`.

**Indicateur principal** : la part de `UNKNOWN` parmi les questions d'état posées poste éteint.

- La base de départ se mesure sur 7 j **avant** l'activation.
- La cible à J+14 est **−50 %**.

**Indicateurs secondaires** :

- les tours Opus évités (poste éteint, question d'état) ;
- les faits écrits par source ;
- l'âge médian du fait cité ;
- les relevés `YIELDED` et les relevés manqués.

**Porte de justesse.** Le banc `ETAT` comprend 12 cas : poste éteint ou allumé, fait frais, ancien ou absent, deux
clients, et la présentation. Il doit passer à **100 % sans info ancienne dite comme actuelle**. Le banc CONVERSATION
doit rester ≥ 90 % sans zéro (porte F-239/F-240).

Lecture : `GET /api/admin/jarvis/state/measure` (ADMIN).

## 8. Le découpage

| SF | Objet | Est. |
|---|---|---|
| **SF-247-01** | **Mesurer d'abord.** Colonnes `host_offline`, `state_question`, `state_answer`, `state_block_ms` dans `jarvis_turn_timings`. Classifieur déterministe. Base de départ des Z2/Z3 (durée des tours, latence) et de la charge runner. `GET /api/admin/jarvis/state/measure`. Rien d'autre. | 1 j |
| **SF-247-02** | **Le registre.** `jarvis_state_facts` et `jarvis_state_settings` (migrations, index du §4.1). Upsert idempotent avec l'ordre par `observed_at`. Scellement. Bornes et purge nocturne. « Effacer tout » et purge à la désactivation. Isolation `user_id` + `host_id`. Coupe-circuit général. Aucun branchement de source. | 1,5 j |
| **SF-247-03** | **Ce qu'Opus a vu.** Inventaire des formes de commande sur 30 j (requête dans `docs/ops`). Extracteurs purs, par ordre de fréquence (Jira, GitLab MR et pipeline, kubectl, helm, argocd, selon l'inventaire). File bornée et exécuteur dédié après la persistance du tour. Porte Z1. | 2 j |
| **SF-247-04** | **Le relevé (gateway).** `purpose = STATE` sur `jarvis_watches`. Instantanés → faits, y compris ceux des veilles existantes. Cadence horaire réglable. Garde serveur Z4-(1). Bornes Z5 côté passerelle. Sondes types proposées par `veille_poser(usage: etat)`. Statut `YIELDED`. Désarmement automatique. | 2 j |
| **SF-247-05** | **Le runner cède la place.** Trame `priority: low`. Refus `busy_deferred` si une commande est en vol. Priorité basse du processus (Windows et autres OS). Préemption en ≤ 500 ms (`preempted`). CPU remonté. Capacité annoncée `low_priority`. **Sans cette capacité, aucun relevé ne part sur ce poste** (zéro gêne avant fraîcheur). Version runner incrémentée. | 1,5 j |
| **SF-247-06** | **Le relevé de départ.** Déclencheur d'inactivité (20 min, relevé de plus de 30 min). Départ propre : `POST /runner/departure` avant le `disconnect`, seulement si un relevé est dû, plafond 8 s, l'arrêt n'attend jamais au-delà (test sur le vrai crochet `RunnerShutdown`, leçon SF-79-02). | 1 j |
| **SF-247-07** | **Jarvis sait (cerveau rapide).** Bloc « État connu » (§4.5) composé par la passerelle, avec « poste déconnecté depuis » tiré de `runner_disconnects`. Outil `etat`. Consigne fixe v19. Garde de fraîcheur D8. Poste éteint : pas de délégation des questions d'état (D9). Rien en présentation. Banc `ETAT` à 100 %. Banc CONVERSATION non régressé. Proposition d'activation D3. | 2 j |
| **SF-247-08** | **Opus en mode Jarvis, poste éteint.** `JarvisStateNote` dans le message si le poste visé est hors ligne au départ. Consigne terminal intacte (test sur l'octet). | 0,75 j |
| **SF-247-09** | **L'écran.** Dans les réglages de `/jarvis` (pas de nouvelle route) : un volet par client avec l'interrupteur, les familles, le relevé, la rétention et « effacer tout ». Une vue « Ce que je sais », en liste datée par client, avec source, âge et l'effacement d'un fait. Masquée en présentation. Design system respecté. Banc headless 1366×768 et 360×740. | 1,5 j |
| *R1 (réserve)* | *Teams : mentions depuis le dernier relevé (`teams_mentions`), seulement si l'onglet Teams en débogage est **déjà** ouvert. Condition : usage Teams mesuré > 0 et au moins une question « non lus » en `UNKNOWN` à J+14.* | *1 j* |
| *R2 (réserve)* | *Constats rédigés en différé par le cerveau rapide (Haiku, par lot, via `AIProvider`, hors chemin). Condition : à J+14, au moins 20 % des `UNKNOWN` restants portent sur un fait **présent dans un tour récent** mais non extrait.* | *1,5 j* |

**Ordre :** 01 → 02 → 03 → 05 → 04 → 06 → 07 → 08 → 09.

- 05 passe avant 04 : la passerelle lit la capacité du runner avant de lancer un relevé.
- 07 peut démarrer dès 02 + 03 avec les seuls faits des tours.

**Revue J+7, puis J+14** : les portes du §6 et l'indicateur du §7. Elle décide des réserves.

## 9. Les préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — Les endpoints Jarvis existants sont réutilisés. La session compagnon `jarvis-compagnon` (SF-232-05) accède au bloc par le cerveau rapide, comme aujourd'hui. Aucun nouvel endpoint pour cette portée. |
| Contexte tenant | **Oui** | `JarvisStateFactRepository` et `JarvisStateSettingsRepository` (toujours `userId` + `hostId`), l'extracteur post-tour (relit le message par `findByIdAndUserId`), le relevé (`JarvisWatchPlanner` / `JarvisWatchPassage`, déjà sous `(userId, hostId)`), `JarvisFastContextBuilder`, l'outil `etat`, `JarvisStateNote`, l'endpoint de mesure (ADMIN, agrégats). Un test d'isolation à deux utilisateurs et deux clients dans chaque SF backend. |
| Plans / limites | Non | Aucun appel modèle, donc ni quota Gateway ni budget Jarvis touché. Le budget Jarvis F-244 ne s'applique qu'à la réserve R2. |
| Navigation / routing | Non | Pas de nouvelle route : le volet s'ajoute aux réglages existants de `/jarvis`. |
| Protocole runner | **Oui** | `BashTool` / `ToolDispatcher` (priorité, refus, préemption), l'annonce des capacités, `RunnerShutdown` (départ), `RunnerToolGateway.bash` (champ `priority`). Un test de contrat : un vieux runner ignore le champ, et la passerelle ne lui envoie pas de relevé. |

## 10. Hors périmètre

- **Toute copie de dépôt et tout travail hors du poste** : écarté par le PO, à ne pas reproposer.
- Toute **action** poste éteint : seule la lecture de l'état connu est concernée.
- Un historique complet des valeurs : seule la précédente est gardée.
- Un compte des messages Teams non lus en V1 (réserve R1).
- La capture d'une extinction brutale ou d'une mise en veille forcée (impossible sans hook système, et dit par
  Jarvis).
- Le déploiement : il fait l'objet d'une décision séparée, après vérification de `runner_audit` et des tours en
  cours.

## 11. Questions PO

**Aucune question bloquante** : les défauts D1-D12 sont pris. Un seul point est à confirmer **à l'usage**, pas
avant : le D3 (activation par client, proposée par Jarvis). Si le PO préfère l'activation d'office pour tous ses
clients, c'est un changement de valeur par défaut, sans développement.
