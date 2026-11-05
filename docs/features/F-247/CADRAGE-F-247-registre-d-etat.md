# CADRAGE F-247 — Le registre d'état : Jarvis sait ce qui se passe, poste éteint

**Statut :** cadrage du 2026-11-05 (besoin PO du même jour, proposition validée). Aucun code.
Famille « Jarvis » : F-230 → F-246, **F-247**. Base vérifiée : `origin/main` `7fa391ae`.
**Estimation :** 15 SF, ≈ 24,25 j, aucune au-delà de 2 j.

**Amendements PO du 2026-11-05 (même jour, version 2) :**

1. **Une 4e source** : les **captures de conversations** (§4.6, SF-247-12/13).
2. Le registre est **activé par défaut** pour tous les clients, et on peut le couper client par client (D3).
3. Le registre doit être **« parfait et maximal »** :
   - les constats rédigés en différé deviennent une **SF à part entière** (SF-247-14). Ce n'est plus une réserve ;
   - l'extraction couvre la plus large part des commandes réellement utilisées (SF-247-04/05) ;
   - le relevé lit tout ce qui se lit sans rien modifier chez chaque client (SF-247-08).
4. Toutes les tâches asynchrones passent par **une seule file durable générique, dans Postgres** (§4.0, SF-247-03).

5. **Correction PO : aucune source Vigie, Teams ou Radar.** Le PO ne s'en est jamais servi en 3 mois, et ce code n'a
   pas fait ses preuves. La lecture Teams est retirée du découpage, ainsi que toute dépendance à la Vigie. Les
   **captures** envoyées dans l'Atelier restent : c'est le canal réel du PO pour Teams.
6. **Latence (insistance PO)** : Jarvis vocal doit rester fluide. Le volume du registre n'affecte **jamais** Jarvis
   (portes Z9 à Z13, §6).

Les seuils « zéro ralentissement » restent **bloquants et inchangés**. Les portes Z9 à Z13 s'y ajoutent.

**Amendement PO du 2026-11-05 (version 3, après la mise en production de F-247 15/15) — « Synchroniser » (SF-247-16) :**

7. **Le rattrapage entre dans le périmètre** (il en était exclu, §10). Un bouton « Synchroniser » **par client**, dans
   « Ce que je sais », remplit le registre tout de suite, en trois phases, avec une barre de progression :
   1. **Historique** (sans IA, 0 €, poste éteint possible) : les sorties d'outils déjà gardées (`terminal_json`) des
      sujets du client repassent dans les extracteurs SF-247-04/05, sur une fenêtre bornée (90 j, ramenée à la
      rétention des faits du client). Chaque fait garde **sa date d'origine** ; l'upsert ne remplace jamais un fait
      plus récent ;
   2. **Captures déjà déposées** (runner allumé) : relues sur le poste puis lues par SF-247-12, même plafond € et même
      coût compté, priorité basse ;
   3. **Relevé du jour** (runner allumé et à jour, relevé mis en place) : un passage immédiat.
   Tout passe par la file `async_jobs` (type `STATE_SYNC`, priorité basse, par tranches), cède aux tours (gardes Z4),
   ne bloque rien, est idempotent et annulable. Les portes Z1→Z13 restent **inchangées et bloquantes**.

**Amendement PO du 2026-11-05 (version 4, après la mise en production de SF-247-16) — « Constats sur l'historique »
(SF-247-17) :**

8. **Constat de prod** : la phase Historique a repassé 1 407 réponses de CAGIP → 3 faits, 454 d'EDENRED → 0 fait. Les
   extracteurs ne reconnaissent pas l'usage réel du PO (scripts `python3` maison, `curl` à variables, alias
   `A=~/aws-cli/aws`, `K=…/kubectl-devtools.sh`, sorties JSON/CSV de scripts). Les constats rédigés (SF-247-14) ne
   tournaient que sur les nouveaux tours. **Décision PO (« GO »)** : les appliquer à l'historique, pour un registre
   maximal :
   1. une **4e phase « Constats sur l'historique »** dans « Synchroniser », juste après l'Historique, **sans le poste**,
      dans la même file (`STATE_SYNC`, priorité basse, par tranches d'**un appel**, mêmes gardes Z4), idempotente (une
      sortie déjà constatée est **marquée** par son empreinte et jamais relue), annulable, progression et € dans la
      barre ;
   2. une **enveloppe ponctuelle par passage** (défaut **20 €**, réglable, 0 = pas de constats), **distincte** du
      plafond mensuel de 15 € des constats au fil de l'eau, affichée avant et pendant ; estimation affichée dès
      l'Historique ; arrêt avant l'appel qui pourrait la dépasser ; arrêt au budget Jarvis dur ; coût au budget Jarvis
      (ligne « État »), hors plafond mensuel des constats ;
   3. seules les **sorties utiles** sont lues (ni vides, ni outils de fichiers et de texte, ni couvertes, ni doublons
      exacts, **jamais** une sortie qui touche un identifiant), regroupées par tour ; **justesse avant coût** : la
      consigne `notes-v1` et la garde de citation mot pour mot sont **inchangées** ;
   4. date d'origine de la sortie, jamais d'écrasement par plus ancien, filtre des secrets à l'enregistrement ;
   5. extracteurs gratuits améliorés (alias de binaires et préfixes muets résolus, variables littérales substituées,
      JSON générique `identité + status/state/version/url`), gain **mesuré** sur l'échantillon réel ;
   6. mesure : faits ajoutés, € dépensés, part rejetée par la garde.
   Aucune consigne du cerveau rapide ni du terminal ne change. Portes Z1→Z13 **inchangées et bloquantes**.

## 1. Le besoin (PO, 2026-11-05)

Le runner tourne sur le poste client du PO. Quand ce poste est **éteint, en veille ou déconnecté**, le PO parle à
Jarvis depuis son téléphone. Il veut des réponses aux **questions d'état** :

- le statut d'un ticket, d'une MR ou d'un pipeline ;
- la version déployée ;
- ce qui attend, les besoins, ce qui est bloqué ;
- ce qui se dit dans les conversations Teams, que le PO **capture** dans l'Atelier.

Jarvis peut répondre avec une information **qui n'est pas la plus fraîche**, à condition de **le dire**.

**Ajout PO : les captures de conversations.** Le PO envoie souvent dans l'Atelier des captures de conversations,
surtout Teams. Poste éteint, la question « tu te rappelles de ce que Rémi m'a dit dans telle conversation ? » doit
trouver sa réponse.

**Consigne PO** : un registre **maximal**, activé d'office.

**Écarté par le PO, à ne pas reproposer :** toute copie du dépôt et tout travail hors du poste. F-247 garde des **faits
d'état datés** et des **transcriptions de conversations**. Elle ne garde ni code, ni fichier, ni exécution.

**Exigence forte : zéro ralentissement.** Rien ne doit ralentir l'application ni le travail du PO (§6).

## 2. L'existant, vérifié dans le code

| Brique | Ce qu'elle fait déjà | Ce qui manque pour le besoin |
|---|---|---|
| **Univers Jarvis** (`JarvisUniverseService`, `JarvisUniverse`) | Les clients (un client = un `RunnerHost`), les sujets, les chantiers (phase, étapes, `waitingOn`), les interlocuteurs et l'état `connected` de chaque poste. Tout est calculé **côté serveur**, donc lisible poste éteint. | Ne dit pas **depuis quand** le poste est déconnecté. Ne contient aucun fait qui vit sur le poste (ticket, MR, version). |
| **Contexte du cerveau rapide** (`JarvisFastContextBuilder.build`, dans le **message**) | Des blocs datés « état à HH:MM » : attentes du sujet courant, à envoyer, délégations en cours, derniers échanges, alertes de veille des 24 h. Bornes : `MAX_BLOCK` 2 500, `MAX_TOTAL` 20 000. L'univers sert de préfixe relu (`reference`, SF-239-11). | Aucun bloc sur l'état du poste. Les attentes ne portent que sur le **sujet courant**. |
| **Outils du cerveau rapide** (`JarvisFastBrainPrompt.tools()` : `deleguer`, `pieces`, `agenda`, `montrer`) | Le patron d'un outil en **lecture seule** : lu une fois par réponse, puis ancré (`gate.ground`). Exemple : `agenda`, consigne v16. | Aucun outil pour lire l'état connu ni les conversations. |
| **Veilles F-230** (`atelier/veille/*`) | Une sonde **lecture seule** écrite une fois par l'agent et validée par le PO. Les passages suivants se font **sans IA**, par commande runner (`RunnerToolGateway.bash`, 60 s au plus). Les éléments sont normalisés (`{id, titre, statut, assigné, mis_à_jour, dernier_commentaire}`) et gardés dans `jarvis_watch_snapshots`. Un créneau manqué est noté `MISSED_OFFLINE` et rattrapé une fois. Créneaux en jours ouvrés (`JarvisWatchSlots`), verrou multi-pod par `UPDATE` conditionnel (`claimSlot`), 4 passages simultanés au plus. | Personne ne lit les instantanés, sauf pour calculer le diff. Pas de veille « sans alerte ». **Aucune garde « tour en cours sur ce poste »** : une sonde peut partir en plein tour. Pas de priorité basse. |
| **Runner : activité et parallélisme** (`RunnerActivity.busy()`, F-111 ; `BashTool` avec un sémaphore à N, F-195) | Le runner sait s'il a une commande en vol. Il accepte N commandes bash à la fois. | Pas de **commande de basse priorité** qui cède la place ou se fait interrompre. |
| **Ruptures du runner** (`runner_disconnects`, F-161) | Les causes `ARRET_PROPRE` (`POST /runner/disconnect`), `INACTIVITE` (poste éteint ou en veille) et `SOCKET_*`, datées. | Jarvis ne les voit pas. |
| **Journal du runner** (`runner_audit`) | Chaque appel d'outil : `tool`, `target`, `outcome`, `exit_code`, durée. **Pas la sortie.** Index `(user_id, workspace_id, created_at)`. | — |
| **Transcription d'un tour** (`TerminalTranscript`, `atelier_messages.terminal_json`, F-30) | La commande **et** la sortie de chaque outil, bornées (`max-transcript-chars`) et persistées à la fin du tour. | Personne n'en tire les faits d'état observés. |
| **Données serveur** : attentes (`terminal_actions`, F-175/F-180), besoins (`app_requests`), à envoyer, alertes de veille | En base, donc **lisibles poste éteint**. | Seules les attentes du sujet courant sont montrées. |
| **Opus en mode Jarvis sur un poste éteint** (`AtelierChatService.noteMachine`) | Les outils échouent en `runner_unavailable`, la machine est notée `OFFLINE`, et Opus répond « poste hors ligne ». | C'est le « je ne sais pas » à faire disparaître. Il coûte un tour Opus pour rien. |
| **Vigie, Teams, Radar** (runner `teams_*`, F-99→F-106, F-122) | Lecture Teams par le Chrome managé, et Radar. | **Écartés comme sources** (correction PO) : jamais utilisés en 3 mois (F-234 : 0 sur 30 j), code non éprouvé. Aucune dépendance de F-247 à ces briques. |
| **Captures déposées** (`WorkspaceDepositService`, `atelier_deposited_files`, F-115/F-169/F-201) | Les octets d'une capture collée ou déposée **passent par la passerelle** (`IncomingFile.bytes`), puis sont écrits **sur le poste**, dans `.atelier/entrees/`. Le serveur ne garde que le **chemin**, la taille et le lien au message (`message_id`). Pendant le tour, Opus lit l'image par `read_file`, qui la transmet au fournisseur sous forme de bloc `image` (SF-121-15). Usage mesuré (F-228) : 216 png sur ≈ 254 dépôts, soit ≈ 85 % de captures. | **Le serveur ne garde rien du contenu.** Poste éteint, la capture est illisible (F-228 : « poste hors ligne : erreur explicite »). La seule trace est ce qu'Opus en a peut-être **paraphrasé** dans sa réponse. |
| **Rappel F-162** (`AtelierSemanticRecallService`, `atelier_messages.embedding`, migration 135) | Les embeddings du **texte** des messages, calculés en **asynchrone** (pool dédié, après la réponse). Recherche pgvector sous `user_id` + `workspace_id`, avec repli sur les mots-clés. Clé `APP_EMBEDDING_API_KEY`. | **Ne voit pas le contenu des captures** : le message ne porte que le texte tapé et le chemin. La recherche se limite à **un** sujet. **Jarvis ne l'appelle pas** (aucun usage dans `jarvis/`). |
| **Tâches de fond** | Chaque brique a son propre ordonnanceur : `@Scheduled` + `UPDATE` conditionnel (veilles, routines), pools en mémoire (embeddings F-162). **Aucune file durable** (aucun `SKIP LOCKED` dans le code). | Sans file durable, un redémarrage de pod perd le travail en attente. |
| **Savoir durable** (gouvernance, carte de poste `host_map_files`) | Le savoir **stable** : faits de structure, conventions. | Hors sujet : F-247 garde de l'**état volatil daté**. On ne mélange pas les deux. |

**Conclusion.** Les données serveur sont déjà lisibles poste éteint : il suffit de les **montrer**, sans les copier.
Il manque cinq choses :

1. une mémoire datée de ce qui ne vit **que** sur le poste ;
2. une façon de la remplir **au maximum**, sans rien gêner ;
3. la manière de la **dire** honnêtement ;
4. une **transcription côté serveur** des captures de conversations, que le rappel et Jarvis voient ;
5. une **file durable** pour faire tout cela hors du chemin de la réponse.

## 3. Principes

- **Gateway-First.** La **passerelle** date chaque fait, calcule depuis quand le poste est déconnecté, rattache au
  client et au sujet, et choisit ce qui entre dans le bloc. Le modèle **reçoit** des faits déjà datés. Il n'invente ni
  date ni fraîcheur.
- **Provider-First / Provider Independence.** Les appels modèle ajoutés sont deux : la lecture des captures (SF-12)
  et les constats rédigés (SF-15). Les deux passent par `AIProvider` et relaient ce que le fournisseur sait faire,
  dont la lecture d'image. Pas d'OCR maison.
- **Justesse avant coût.** Un fait d'état n'est enregistré que s'il a été **lu dans une sortie d'outil réelle**. Une
  phrase du modèle seule ne suffit jamais. Les constats rédigés (SF-15) doivent **citer** la sortie qui les prouve, et
  cette citation est vérifiée. Une information ancienne n'est **jamais** présentée comme actuelle (porte de banc à
  100 %).
- **Une seule vérité.** Le registre ne garde **que** ce qui vit sur le poste. Les attentes, besoins, à envoyer,
  chantiers et alertes sont **lus en direct**. Ils ne sont jamais dupliqués.
- **Isolation.** Toute lecture et toute écriture se font sous `user_id` et sous `host_id` (le client).
- **Préfixe stable.** Rien de volatil dans les consignes système. Les blocs vont **dans le message**. Les outils `etat`
  et `conversations` et la consigne v19 changent le préfixe **une seule fois**, en même temps, puis il reste stable.
- **Postgres seul.** Pas de Kafka ni de Redis. Une seule file durable générique (§4.0).
- **Zéro ralentissement** : les critères mesurables du §6 sont des portes, pas des intentions.

## 4. La conception

### 4.0 La file durable générique (`async_jobs`)

Il y a trois producteurs asynchrones : l'extraction après un tour, la lecture des captures et les constats rédigés.
Ils partagent **une seule file en base**. Il n'y a pas d'ordonnanceur par brique.

**Colonnes** :

| Colonne | Rôle |
|---|---|
| `id`, `user_id`, `kind` | `kind` : `STATE_EXTRACT`, `CAPTURE_READ`, `STATE_NOTES`. Liste ouverte : on pourra y ajouter un type sans migration. |
| `priority` | Priorité de traitement. |
| `payload` | `jsonb`, des références seulement (id de message, id de dépôt). |
| `payload_bytes` | `bytea` facultatif, pour l'image d'une capture uniquement. Mis à **null** dès la fin du travail. |
| `status` | `PENDING` → `RUNNING` → `DONE`, `FAILED` ou `DROPPED`. |
| `attempts`, `max_attempts` | Nombre d'essais, et la limite. |
| `available_at` | Date à partir de laquelle le travail peut être pris (sert au recul exponentiel). |
| `lease_owner`, `lease_until` | Le bail multi-pod. |
| `created_at`, `finished_at`, `duration_ms`, `cost_usd`, `error_code` | Suivi et mesure. |

**Prise d'un travail** :

1. `SELECT … WHERE status = 'PENDING' AND available_at <= now() ORDER BY priority, available_at LIMIT n FOR UPDATE
   SKIP LOCKED` ;
2. passage en `RUNNING` avec un bail (`lease_until = now() + 2 min`), dans la même transaction.

Un **faucheur** remet en `PENDING` tout bail expiré, ce qui couvre un pod mort. Après `max_attempts` essais, le
travail passe en `FAILED`. Le recul est exponentiel.

**Index** : un index partiel `(priority, available_at) WHERE status = 'PENDING'`, et un index
`(user_id, kind, created_at)` pour la mesure.

**Bornes** :

- une concurrence par `kind`, configurable : 1 fil pour les extractions, 1 pour les captures, 1 pour les constats ;
- les fils de travail sont des démons à priorité basse ;
- un plafond de travaux en attente par `kind` : au-delà, le nouveau travail est jeté en `DROPPED` et compté ;
- les travaux `DONE` sont purgés après 7 j.

**Sur H2** (profil `dev` et tests) : si `SKIP LOCKED` n'est pas pris en charge, on bascule sur un `UPDATE`
conditionnel, comme `claimSlot`. Le comportement Postgres est testé à part.

Le **relevé** n'entre pas dans cette file. Il garde le planificateur des veilles, déjà verrouillé en multi-pod (D5).

### 4.1 Le registre (`jarvis_state_facts`)

Un fait se compose de : client, objet, attribut, valeur, heure d'observation, source et confiance.

| Colonne | Contenu |
|---|---|
| `id`, `user_id`, `host_id` (le client) | Index `(user_id, host_id, observed_at desc)`. |
| `workspace_id` (nullable) | Le sujet où le fait a été vu. |
| `object_kind` | `TICKET`, `MR`, `PIPELINE`, `DEPLOYMENT`, `RELEASE`, `CLOUD_RESOURCE`, `INFRA_STATE`, `DB_SCHEMA`, `GIT_BRANCH`, `PAGE`, `OTHER`. |
| `object_key` | Un identifiant technique **en clair** et normalisé. Exemples : `JIRA:PROJ-123`, `GITLAB:groupe/projet!42`, `GITHUB:org/repo#17`, `PIPELINE:groupe/projet@main`, `K8S:ctx/ns/deploy`, `HELM:ctx/ns/release`, `ARGO:app`, `AWS:compte/région/arn`, `AZ:abonnement/ressource`, `TF:espace/ressource`, `DB:base/schéma`, `GIT:dépôt@branche`. Index `(user_id, host_id, object_key)`. |
| `attribute` | Une liste fermée et extensible : `status`, `assignee`, `title`, `version`, `image`, `result`, `health`, `sync`, `replicas`, `updated_at`, `blocked_by`, `ahead_behind`, `last_commit`… |
| `value_sealed` | La valeur, **scellée** (`PortraitSeal`, comme `jarvis_calendar_events`), 300 caractères au plus. |
| `previous_value_sealed`, `previous_observed_at` | La valeur précédente, pour dire « passé de X à Y le … ». |
| `observed_at` | **L'heure de la sortie d'outil**, pas l'heure d'écriture. |
| `source`, `source_ref` | `TOUR` (extraction), `NOTE` (constat rédigé), `RELEVE`, `VEILLE`. |
| `confidence` | `HAUTE` : JSON d'API parsé. `MOYENNE` : texte de CLI reconnu par un motif strict, ou constat rédigé dont la citation a été vérifiée. |

**Écriture : un upsert idempotent** sur la clé `(user_id, host_id, object_key, attribute)`.

- Même valeur : seule `observed_at` avance.
- Valeur différente : la valeur actuelle passe en `previous`.
- Fait plus ancien que celui en base : ignoré.
- Une source `HAUTE` n'est jamais écrasée par une source `MOYENNE` plus récente de moins de 5 min.

**Mise à jour en place, historique compact (porte Z11).** Un fait est **une ligne**, mise à jour en place. Le registre
ne grossit qu'avec le nombre d'objets distincts, jamais avec le nombre d'observations. L'historique se limite à la
valeur précédente, dans la même ligne.

**Bornes** : 10 000 faits par client. Au-delà, les plus anciens sont purgés. Une purge nocturne retire les faits non
revus depuis la rétention du client (30 j par défaut).

**Lecture** : seulement par les index `(user_id, host_id, observed_at desc)` et `(user_id, host_id, object_key)`, avec
`LIMIT`. Jamais de parcours complet, jamais de déchiffrement en masse : on ne descelle que les lignes retenues. La porte
est **≤ 10 ms p95 à 100 000 faits** (Z10). Elle est prouvée par un test de charge sur Postgres dans SF-247-02.

**Réglage par client (`jarvis_state_settings`)** :

- `enabled` ;
- les familles ;
- `extract_from_turns` ;
- `notes_enabled` (SF-15) ;
- `releve_enabled` ;
- `captures_enabled` ;
- les rétentions ;
- « effacer tout ».

**Tout est activé par défaut** pour chaque client (D3, décision PO). Couper un client arrête toute écriture et
**purge** ses faits et ses transcriptions.

### 4.2 Source (a) : ce qu'Opus a vu pendant les tours, par extraction déterministe

**Comparaison des mécanismes :**

| Mécanisme | Coût | Justesse | Risque | Verdict |
|---|---|---|---|---|
| **Sortie structurée** : Opus écrit un bloc `<<etat>>` à chaque tour | Des jetons de sortie à **chaque** tour | Opus peut écrire un fait **déduit** | Modifie la consigne du terminal, distrait le raisonnement et allonge le tour | **Écarté** |
| **Extraction déterministe des sorties d'outils** | **0 €**, quelques ms de CPU serveur | **La meilleure possible** : la valeur est celle de l'outil, à l'heure de l'outil | Aucun : elle se fait après la persistance, dans la file | **Retenu** pour toutes les formes reconnues |
| **Constats rédigés en différé** (petit modèle) | Borné et mesuré en € | Bonne si chaque constat **cite** une sortie vérifiée | Aucun sur le tour (dans la file) | **Retenu en complément** pour la longue traîne (§4.7) |

**Fonctionnement :**

1. À la fin du tour, dans la **même transaction** que la persistance de `terminal_json`, une ligne `STATE_EXTRACT` est
   insérée : un seul `INSERT`, sans aller-retour de plus. Si la mesure dépasse la porte Z1, l'insertion passe après le
   commit, sur un fil de remise.
2. Un fil de travail relit les blocs **sous `user_id`** et applique des **extracteurs purs**. Chacun reconnaît à la fois
   une forme de commande et une forme de sortie.
3. Les sorties en erreur, tronquées ou ambiguës sont ignorées. En cas de doute, rien n'est écrit.
4. Upsert.

**Couverture visée : maximale.** SF-247-04 commence par un **inventaire sur 30 jours** des commandes réelles (requête
dans `docs/ops`, classement par fréquence et par forme). On écrit **tous** les extracteurs des formes qui reviennent,
sans en supposer d'autres :

| Famille | Commandes visées (selon l'inventaire) | Faits |
|---|---|---|
| Tickets | `jira` / `acli`, `curl` sur l'API REST Jira (`/rest/api/2|3/issue`, `/search`), Confluence (`/rest/api/content`) | statut, assigné, titre, priorité, sprint, mis à jour, liens bloquants, version de page |
| Revues de code | `glab mr view|list`, `curl` sur l'API GitLab (`/merge_requests`), `gh pr view|list|checks`, `curl` sur l'API GitHub | état, approbations, conflits, pipeline, cible |
| CI | `glab ci status|view`, API `/pipelines` et `/jobs`, `gh run list|view` | résultat, étape en échec, branche, commit |
| Déploiements | `kubectl` / `oc get|describe|rollout status` (deploy, sts, pods, images), `helm list|status|history`, `argocd app get|list` | image et version, réplicas prêts, santé, synchro, révision |
| Cloud | `aws` (ecs describe-services, lambda get-function, cloudformation describe-stacks, rds describe-db-instances…), `az` (webapp show, aks show, deployment show…) | version, statut, dernière mise à jour |
| Infra | `terraform output|show|state list` (jamais `plan` ni `apply`) | sorties, ressources, version |
| Base | `psql` / `liquibase` / `flyway` sur les tables de migrations (`databasechangelog`, `flyway_schema_history`) | version de schéma. Les autres `SELECT` relèvent des constats (§4.7). |
| Git | `git status`, `git log -n`, `git branch -vv`, `git describe` | branche, avance et retard, dernier commit, étiquette |
| API génériques | `curl` / `Invoke-RestMethod` dont la réponse JSON porte des champs d'état reconnus (`status`, `state`, `version`) | `OTHER`, en confiance `MOYENNE` |

Un **taux de couverture** est mesuré : la part des appels `bash` d'état qui ont produit au moins un fait. Les formes
restantes sont listées dans la mesure, pour écrire l'extracteur suivant ou laisser les constats (§4.7) s'en charger.

### 4.3 Source (b) : le relevé automatique, lecture seule, sans IA, maximal

**On réutilise les veilles** (F-230 D7). Un relevé est une veille avec `purpose = STATE` :

- **pas d'alerte ni d'IA**, même sur changement : l'instantané devient des faits ;
- sa portée est le **client** ;
- sa cadence est horaire, de 8 h à 20 h, en jours ouvrés, et se règle par client.

**Les sondes : tout ce qui se lit sans rien modifier.**

1. À la **mise en place** (SF-247-08), un tour d'agent **unique** par client, lancé quand le poste est allumé et
   inactif, fait l'inventaire des moyens de lecture présents sur le poste : CLIs installés et authentifiés, jetons et
   contextes disponibles. Il ne lit **aucun secret**. Il propose **une sonde par famille disponible** : tickets qui me
   concernent, MR/PR ouvertes, pipelines des branches principales, déploiements par environnement, ressources cloud
   suivies, sorties d'infra, version de schéma, état git des dépôts du client. Il les teste une fois chacune.
2. Le PO les valide **toutes en un geste** (« Tout valider »), ou une par une. La validation reste obligatoire : c'est
   la règle F-230 D5 (une commande qui tourne sans humain). Elle est **distincte** de l'activation du registre, qui est
   d'office.
3. Contrôle « lecture seule » : en plus des contrôles F-230 (GET seulement, pas de mutation GraphQL, pas de jeton en
   clair), une **liste de verbes autorisés** par outil (`get`, `list`, `view`, `describe`, `status`, `show`, `log`,
   `output`, `history`). Une sonde qui contient un autre verbe est refusée à l'enregistrement.

Les **veilles existantes** (`purpose = ALERT`) alimentent aussi le registre avec leurs instantanés : c'est la source (c).

**Quand le relevé passe :**

- **à l'heure** ;
- **à l'inactivité** : aucune activité runner depuis 20 min (hors relevés) et un dernier relevé de plus de 30 min. C'est
  l'approximation de « avant la mise en veille » ;
- **au départ propre** : à l'arrêt du runner, seulement si un relevé est dû (plus de 15 min), avec un plafond de 8 s.
  L'arrêt n'attend jamais plus.

Une extinction brutale ou une mise en veille forcée **ne peut pas être captée**. Le relevé horaire borne l'âge de
l'information à environ 1 h, et Jarvis le **dit**.

Les gardes de non-gêne sont au §6 (portes Z4 et Z5).

### 4.4 Source (c) : l'existant, lu en direct

Ces données sont lues en direct par la passerelle quand elle compose le bloc :

- les attentes ouvertes de **tous** les sujets du client ;
- les besoins ;
- les messages à envoyer ;
- les chantiers bloqués ou en attente ;
- les alertes de veille ;
- la dernière rupture du poste (`runner_disconnects`).

### 4.5 Ce que Jarvis en fait

**Le bloc « État connu »**, composé par la passerelle dans le **message** du cerveau rapide. Il est **borné en taille
fixe**, quel que soit le volume du registre : **20 faits** et **2 000 caractères** au plus (porte Z9). Il reste vide si
rien n'est connu. Jarvis **ne lit jamais tout le registre** : le reste n'est accessible que par l'outil `etat`, à la
demande.

**Le bloc est préparé à l'avance, hors réplique (porte Z12).** La passerelle le compose quand le registre change (après
un travail de la file, un relevé, ou un changement de connexion du poste) et le garde dans un cache par utilisateur, en
mémoire, invalidé au changement. Une réplique de Jarvis **lit ce texte prêt** et n'interroge pas le registre. Si le
cache est absent (pod neuf), la réplique part **sans** le bloc, et une composition est lancée à côté. Elle ne l'attend
jamais.

```
## État connu — client CAGIP
Poste déconnecté depuis hier 18:05 (arrêt propre). Dernier relevé : hier 17:42.
- PROJ-123 « Migration SSO » : En revue (vu hier 17:42, relevé) — avant : En cours
- MR projet!42 : ouverte, pipeline échoué (vu hier 16:10, tour)
- Déployé en prod : api 2.14.1 (vu hier 11:03, tour)
- 3 attentes ouvertes (état à 08:12) ; 1 message à envoyer
```

**Composition, déterministe :**

- **Sélection par pertinence** :
  1. le sujet et le chantier en vue ;
  2. le reste du client courant ;
  3. les autres clients au poste déconnecté.

  Dans chaque groupe : ce qui a changé, puis ce qui est bloqué, puis le plus récent. Au total, 20 faits au plus.
- Chaque ligne porte l'heure **et** la source.
- Au-delà de 24 h, la ligne est marquée « ancien ».
- Poste connecté : seuls les faits de plus de 15 min entrent, avec « vu à ». Une question qui exige le frais est alors
  déléguée, comme aujourd'hui.

**L'outil `etat`** du cerveau rapide :

- lecture seule, une fois par réponse ;
- recherche par `object_key`, par famille ou par client, sur index ;
- 20 lignes au plus, déjà datées, lues par index (Z10) ;
- c'est la seule lecture du registre qu'une réplique peut faire, et seulement quand le modèle la demande ;
- résultat ancré par `gate.ground`.

**Poste éteint** :

- une question d'état reçoit sa réponse **du registre**. Elle n'est plus déléguée à Opus. Si le registre ne sait pas,
  Jarvis le dit (« Je n'ai rien de relevé sur PROJ-456 ; le poste est éteint depuis 18 h ») ;
- une demande d'**action** reste déléguée.

**Opus en mode Jarvis** : si le poste visé est hors ligne au départ du tour, le même bloc est joint **au message**
(`JarvisStateNote`, sur le patron de `JarvisOralNote`). La consigne du terminal ne change pas.

**La garde de fraîcheur (D8)** : toute phrase dite qui cite un fait de plus d'1 h sans marque de temps reçoit
« relevé de HH:MM ».

**En présentation** : ni bloc, ni `etat`, ni `conversations`.

### 4.6 Source (d) : les captures de conversations

**Le moment** : la **réception** d'un dépôt d'image (png, jpeg, webp). C'est le seul moment où la passerelle a le
contenu sans le poste.

1. Le dépôt est écrit sur le poste comme aujourd'hui, et sa réponse part **sans attendre**.
2. Un fil de remise insère ensuite le travail `CAPTURE_READ`, avec l'image dans `payload_bytes` (5 Mo au plus). Si le
   pod tombe dans cet intervalle, la capture est perdue, et c'est compté.

**La lecture** : un fil de travail fait **un seul appel** au modèle rapide (`ANTHROPIC_FAST_MODEL`) par `AIProvider`,
avec le bloc `image` et une consigne fixe versionnée. La réponse est un JSON :

```
{ "conversation": true|false, "plateforme": "teams"|"mail"|"autre", "titre": "…",
  "messages": [ { "interlocuteur": "…", "horodatage_visible": "Hier 14:32", "texte": "…" } ] }
```

- `conversation: false` : rien n'est gardé, seul le coût est compté.
- Le texte est **recopié, jamais résumé**. Une garde rejette toute sortie non conforme. En cas de doute, rien n'est
  gardé.
- **La passerelle fixe elle-même, pas le modèle** :
  - le client et le sujet du dépôt ;
  - la date de chaque message : `horodatage_visible` est résolu de façon déterministe à partir de l'heure du dépôt ;
    sans date lisible, c'est l'heure du dépôt, marquée « vers » ;
  - l'interlocuteur, rapproché des interlocuteurs connus (variantes F-180) ; sinon, le nom lu est gardé.
- **L'image est effacée** (`payload_bytes = null`) dès la fin du travail, réussi ou abandonné. Elle n'est jamais
  gardée au-delà.

**Le stockage** :

- `jarvis_conversation_captures` : `user_id`, `host_id`, `workspace_id`, `deposit_id`, `message_id`, plateforme, titre,
  `deposited_at`, statut, coût ;
- `jarvis_capture_lines` : interlocuteur, `said_at`, texte, `embedding vector(1536)`.

Le filtre est `user_id` + `host_id`. Rétention de 90 j, réglable, et purge à la coupure. Le texte est **en clair**,
comme dans `atelier_messages` : le PO l'a déjà confié à l'Atelier, et la recherche plein texte en a besoin (D13).

**Le rappel** :

- les lignes sont vectorisées par le même `EmbeddingProvider` que F-162 ;
- elles **ne sont pas** écrites dans `atelier_messages`, pour ne rien rejouer dans le contexte d'Opus (D14) ;
- `recall` interroge **aussi** les lignes de capture du sujet (union, mêmes bornes, repli sur les mots-clés) ;
- le cerveau rapide gagne l'outil `conversations` : lecture seule, une fois par réponse, ancré, sur **tous les sujets
  du client**, 15 lignes au plus, datées par la passerelle. Exemple : « capture Teams du 4/11, « Migration SSO » —
  Rémi Dupont, hier 14:32 : … ». Recherche par index et par vecteur avec `LIMIT`, même porte Z10.

**Le coût, borné et mesuré en €** :

- un appel par image, avec une sortie de 1 500 jetons au plus ;
- 60 lectures par jour au plus ;
- un plafond propre de **10 €/mois**, réglable, dans le budget Jarvis F-244 (origine `JARVIS`, part `CAPTURE`). Au-delà,
  la lecture s'arrête, le PO est prévenu une fois, et il n'y a jamais d'escalade ;
- le coût réel par capture est mesuré dès la SF, au tarif en vigueur, et le plafond est révisé sur mesure.

### 4.7 Source (e) : les constats rédigés en différé

Ils couvrent la longue traîne, que les extracteurs ne reconnaissent pas : un `SELECT` quelconque, une API maison, une
page lue.

- **Le déclenchement** : après un tour dont **au moins une sortie d'outil d'état n'a produit aucun fait**, un travail
  `STATE_NOTES` entre dans la file.
- **L'entrée** : les seules sorties non couvertes, bornées à 8 000 jetons, avec la commande et la consigne fixe.
- **Le modèle** : le cerveau rapide, par `AIProvider`.
- **La sortie** : une liste de faits `{objet, attribut, valeur, citation}`.
- **La garde de citation, déterministe** : la `citation` doit apparaître **mot pour mot** dans la sortie d'outil
  fournie, et la `valeur` doit y figurer. Sinon, le fait est rejeté et compté. Rien n'est tiré du texte d'Opus seul.
- **Le résultat** : confiance `MOYENNE`, source `NOTE`, `observed_at` = heure de la sortie.
- **Le coût** : un plafond propre de **15 €/mois**, réglable, part `ETAT` du budget F-244, mesuré en €. 200 travaux par
  jour au plus. Arrivé au plafond, on s'arrête sans escalade.


## 5. Les décisions par défaut

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Extraction déterministe** pour toutes les formes reconnues. Pas de sortie structurée d'Opus. Constats rédigés **cités** pour la longue traîne. | Coût nul et justesse maximale d'abord. Le modèle ne vient qu'en complément, sous preuve. |
| D2 | Le registre ne garde **que** l'état du poste. Le reste est lu en direct. | Une seule vérité. |
| D3 | **Activé par défaut pour tous les clients**, toutes sources comprises (décision PO). Le réglage par client **coupe** et purge. | Le PO ne veut pas de geste de plus. On coupe client par client, sans affaiblir une règle globale. |
| D4 | Les valeurs d'état sont **scellées** (`PortraitSeal`). Les clés techniques restent en clair. | Même régime que l'agenda, et une recherche indexée. |
| D5 | Le relevé **réutilise les veilles** (`purpose = STATE`). Ses sondes sont validées par le PO (un geste pour toutes). Il n'entre pas dans la file. | F-230 D5/D7. Le planificateur est déjà verrouillé en multi-pod. |
| D6 | Le départ est capté par l'**inactivité** (20 min) et par l'**arrêt propre** (8 s au plus). L'extinction brutale n'est pas captée, et c'est dit. | Rien d'autre n'est fiable sans hook système. |
| D7 | Les portes du §6 sont **bloquantes** à la review et à J+7. | Exigence PO. |
| D8 | Date et fraîcheur sont calculées par la passerelle. La garde « relevé de HH:MM » s'applique. | Jamais d'info ancienne dite comme actuelle. |
| D9 | Poste éteint, une question d'état reçoit sa réponse du registre. Une action est toujours déléguée. | Moins de tours voués à l'échec. |
| D10 | **Aucune source Vigie, Teams ou Radar** (correction PO). Teams n'entre que par les **captures** envoyées dans l'Atelier. | Briques jamais utilisées en 3 mois et non éprouvées. Le canal réel du PO, ce sont les captures. |
| D11 | En présentation, rien n'est montré. | Règle F-242/F-243. |
| D12 | **Les coupe-circuits** : `APP_JARVIS_STATE_ENABLED` (tout), puis `…_EXTRACT_ENABLED`, `…_NOTES_ENABLED`, `…_RELEVE_ENABLED`, `…_CAPTURES_ENABLED`, `…_BLOCK_ENABLED`. Le relevé d'un poste se désarme seul s'il est préempté plus de 3 fois dans la journée. | Revenir en arrière sans déployer. |
| D13 | Captures : **l'image n'est gardée que le temps de la lecture**, dans la file, puis effacée. La transcription est gardée en clair. Client, sujet, date et interlocuteur sont fixés par la passerelle. Le texte est recopié, jamais résumé. | Provider-First. Justesse. Robustesse : la capture survit à un redémarrage de pod. |
| D14 | Les captures vont dans une table propre, **pas** dans `atelier_messages`. `recall` et le cerveau rapide l'interrogent en union. | Ne rien rejouer dans le contexte d'Opus, ni sa mémoire ni son cache. |
| D15 | **Une seule file durable générique** dans Postgres (`SKIP LOCKED`, baux, faucheur), avec une concurrence par type. Pas de Kafka ni de Redis. | Consigne d'architecture. Aucun travail perdu à un redémarrage. |
| D16 | Plafonds propres : captures 10 €/mois, constats 15 €/mois, réglables, dans le budget Jarvis F-244. | Coût borné et visible en €. |

## 6. Zéro ralentissement : critères mesurables (portes, inchangés)

| # | Critère | Seuil / preuve |
|---|---|---|
| Z1 | **Extraction et constats hors du chemin de la réponse.** | La mise en file coûte ≤ 1 ms au p99 sur la persistance du tour. Test : un fil de travail bloqué ne retarde ni le tour ni le suivant. Aucun appel modèle dans le chemin (vérifié sur le fournisseur simulé). |
| Z1b | **La lecture des captures est hors du chemin du dépôt et du tour.** | `POST …/deposits` : p50 et p90 à ≤ +2 % ou ≤ +20 ms. Test : un fournisseur bloqué ne retarde rien. |
| Z2 | **Durée des tours Opus** (`atelier_turn_measures`). | p50 et p90 à ≤ +2 % sur 7 j, par rapport à la base de SF-247-01. |
| Z3 | **Latence de Jarvis** (`fast_first_token_ms`, `first_sound_ms`). | ≤ +5 % ou ≤ +100 ms. Composition des blocs (`state_block_ms`) ≤ 20 ms au p95. |
| Z4 | **Jamais de relevé pendant un tour sur le même poste.** | (1) Côté serveur : aucun tour vivant connu, et aucune entrée `runner_audit` hors relevé sur les sujets du poste depuis 2 min (requête indexée). (2) Côté runner : une trame `priority: low` est refusée (`busy_deferred`) si une autre commande est en vol. (3) Préemption : la sonde est tuée en ≤ 500 ms (`preempted`), le passage est noté `YIELDED` et repris plus tard. Mesure : **0** chevauchement sur 7 j. |
| Z5 | **Charge runner bornée.** | Une sonde à la fois, 20 s par sonde, 60 s par relevé. Priorité basse : `BelowNormal` sous Windows, `nice 19` ailleurs. CPU ≤ 5 s par relevé (remonté et journalisé). Au plus 1 relevé par heure, plus le départ et l'inactivité. |
| Z6 | **Base bornée.** | Requêtes indexées, vérifiées en revue (y compris la prise dans la file). Les blocs lisent ≤ 60 lignes, les outils ≤ 20. 10 000 faits par client. La file reste bornée par type. Les images sont effacées à la fin du travail. |
| Z7 | **Cache.** | Le préfixe change une seule fois (v19 avec `etat` et `conversations`), puis il est identique à l'octet. Le `cache_write` du cerveau rapide est stable à J+7. |
| Z8 | **Revenir en arrière.** | Coupe-circuits D12, et désarmement automatique testé. |
| Z9 | **Jarvis ne lit jamais tout le registre.** | Le bloc « État connu » est borné à 20 faits et 2 000 caractères, quel que soit le volume. La sélection se fait par pertinence (sujet et chantier en vue, puis les plus récents). Le reste ne passe que par l'outil `etat`, à la demande. Test : le bloc a la même taille maximale à 10, 10 000 et 100 000 faits. |
| Z10 | **La lecture se fait par index.** | ≤ 10 ms p95 pour l'outil `etat`, pour `conversations` et pour la composition du bloc, **à 100 000 faits** (et 100 000 lignes de capture). Test de charge sur Postgres dans SF-247-02 et SF-247-13, plan d'exécution vérifié (aucun parcours séquentiel). |
| Z11 | **Mise à jour en place.** | Upsert sur client + objet + attribut. L'historique tient dans la ligne (valeur précédente seulement), et la purge est nocturne. Le nombre de lignes ne croît qu'avec les objets distincts : test à 1 000 observations d'un même fait, qui donne 1 ligne. |
| Z12 | **Aucun travail du registre dans le chemin d'un tour ou d'une réplique de Jarvis.** | Toute écriture passe par la file. Le bloc est composé à l'avance et lu tout prêt. Test : un registre verrouillé (transaction bloquante simulée) ne retarde ni un tour ni une réplique, et la réplique part sans le bloc. |
| Z13 | **La mesure inclut le premier mot de Jarvis à la voix.** | `first_sound_ms` (premier son dit) et `fast_first_token_ms`, en p50 et p90, avant et après, **en mode vocal** séparément : ≤ +5 % ou ≤ +100 ms. Les mêmes seuils sont vérifiés à part sur les répliques où l'outil `etat` a été appelé. |

Si une porte est dépassée à J+7, le coupe-circuit concerné passe à `false`, et on trouve la cause avant de
réactiver.

## 7. Mesurer la valeur

**La base de départ (SF-247-01, avant tout).** `jarvis_turn_timings` reçoit quatre colonnes :

- `host_offline` ;
- `state_question`, d'après une classification déterministe : verbe ou objet d'état, clé de ticket ou de MR, « où en
  est », « version », « bloqué », « attend », « non lus », « qu'a dit », « m'a dit » ;
- `state_answer` : `UNKNOWN` (un lexique fermé, ou un tour Opus noté `OFFLINE`), `KNOWN_STALE`, `KNOWN_FRESH` ou `NA` ;
- `state_block_ms` (lecture du bloc tout prêt dans la réplique ; la durée de sa composition, hors réplique, est mesurée à part).

**Indicateur principal** : la part de `UNKNOWN` parmi les questions d'état posées poste éteint. La base se mesure sur
7 j **avant** l'activation. La cible à J+14 est **−50 %**.

**Indicateurs secondaires** :

- les tours Opus évités ;
- les faits écrits par source ;
- le taux de couverture des extracteurs ;
- les constats rejetés par la garde de citation ;
- les captures lues ou non lues, et leur coût en € ;
- le premier mot de Jarvis à la voix, avant et après (Z13) ;
- l'âge médian d'un fait cité ;
- les passages `YIELDED` ;
- les travaux `DROPPED` ou `FAILED` par type.

**Portes de justesse :**

| Banc | Contenu | Porte |
|---|---|---|
| `ETAT` | 12 cas : éteint ou allumé, frais, ancien ou absent, deux clients, présentation | 100 %, sans info ancienne dite comme actuelle |
| `CAPTURES` | 10 captures réelles anonymisées | 100 % sur les interlocuteurs et les dates, texte fidèle |
| `NOTES` | 10 sorties | 0 fait sans citation vérifiée |
| CONVERSATION | — | ≥ 90 % sans zéro |

Lecture : `GET /api/admin/jarvis/state/measure` (ADMIN).

## 8. Découpage (15 SF, ≈ 24,25 j)

| SF | Objet | Est. |
|---|---|---|
| **SF-247-01** | **Mesurer d'abord.** Les quatre colonnes de `jarvis_turn_timings` et le classifieur. Les bases des portes Z1b, Z2, Z3 et **Z13 (premier mot à la voix)**, et la charge runner. `GET /api/admin/jarvis/state/measure`. | 1 j |
| **SF-247-02** | **Le registre.** `jarvis_state_facts` et `jarvis_state_settings`, **tout activé par défaut**. Upsert en place (Z11), scellement, bornes, purge, coupure qui purge. Isolation `user_id` + `host_id`. Coupe-circuit général. **Test de charge à 100 000 faits, ≤ 10 ms p95** (Z10). | 2 j |
| **SF-247-03** | **La file durable générique** `async_jobs`. Prise en `SKIP LOCKED`, bail, faucheur, recul, concurrence par type, plafond et `DROPPED`, purge, repli H2. Mesure par type. Test multi-pods (deux consommateurs, aucun doublon). | 1,5 j |
| **SF-247-04** | **Extraction, lot 1.** Inventaire des commandes sur 30 j. Mise en file à la persistance du tour (porte Z1). Extracteurs tickets (Jira, Confluence), revues (GitLab, GitHub) et CI. Taux de couverture. | 2 j |
| **SF-247-05** | **Extraction, lot 2.** Déploiements (`kubectl`/`oc`, `helm`, `argocd`), cloud (`aws`, `az`), infra (`terraform` en lecture), base (tables de migrations), git, API génériques. Toutes les formes de l'inventaire au-dessus du seuil de fréquence. | 2 j |
| **SF-247-06** | **Le runner cède la place.** Trame `priority: low`, refus `busy_deferred`, priorité basse du processus, préemption en ≤ 500 ms, CPU remonté. Capacité `low_priority`, **sans laquelle aucun relevé ne part**. Version runner. | 1,5 j |
| **SF-247-07** | **Le relevé côté passerelle.** `purpose = STATE`, instantanés → faits (veilles existantes comprises), cadence réglable, garde serveur Z4, bornes Z5, `YIELDED`, désarmement automatique. | 2 j |
| **SF-247-08** | **La mise en place du relevé maximal.** Le tour unique d'inventaire des moyens de lecture du poste (poste allumé et inactif). Une sonde par famille disponible, testée. Liste de verbes autorisés. Carte « Tout valider ». `veille_poser(usage: etat)`. | 1,5 j |
| **SF-247-09** | **Le relevé de départ.** Déclencheur d'inactivité. `POST /runner/departure` avec un plafond de 8 s, testé sur le vrai crochet `RunnerShutdown`. | 1 j |
| **SF-247-10** | **Jarvis sait (cerveau rapide).** Le bloc « État connu » borné et préparé à l'avance (Z9, Z12), avec « déconnecté depuis », l'outil `etat`, consigne v19 (avec la déclaration de `conversations`), garde D8, D9, rien en présentation. Banc `ETAT`, et CONVERSATION non régressé. | 2 j |
| **SF-247-11** | **Opus en mode Jarvis, poste éteint.** `JarvisStateNote` dans le message. Consigne du terminal intacte (test à l'octet). | 0,75 j |
| **SF-247-12** | **Lire les captures.** Remise vers la file à la réception, lecture par `AIProvider`, gardes, résolution déterministe, deux tables, effacement de l'image, coût en € et plafond. Porte Z1b. Banc `CAPTURES`. | 2 j |
| **SF-247-13** | **Retrouver les conversations.** Vectorisation (pool F-162), `recall` en union, outil `conversations` (déclaré en v19 dès SF-10, branché ici), rien en présentation, mesure des « qu'a dit X ». Test de charge à 100 000 lignes (Z10). | 1,5 j |
| **SF-247-14** | **Les constats rédigés en différé.** Travail `STATE_NOTES` sur les sorties non couvertes, garde de citation, plafond de 15 €/mois en €. Banc `NOTES`. | 2 j |
| **SF-247-15** | **L'écran.** Dans les réglages de `/jarvis` (pas de nouvelle route) : un volet par client, activé d'office, avec l'interrupteur pour couper, les familles, les sources, les rétentions et « effacer tout ». La vue « Ce que je sais » (faits, conversations, source, âge, effacement d'un élément), la dépense en €. Masquée en présentation. Design system. Banc headless 1366×768 et 360×740. | 1,5 j |
| **SF-247-16** | **Synchroniser** (amendement v3). Par client : historique des tours (0 €), captures déjà déposées (SF-247-12), relevé du jour ; file `STATE_SYNC` par tranches, Z4, annulable, idempotent ; barre orange dans « Ce que je sais ». | 2 j |
| **SF-247-17** | **Constats sur l'historique** (amendement v4). Phase 4 de « Synchroniser » : sorties utiles non couvertes, un appel par tranche, enveloppe ponctuelle 20 € hors plafond mensuel, empreintes, garde de citation inchangée ; extracteurs gratuits pour l'usage réel. | 1,5 j |

**Ordre et parallélisme :**

1. 01 → 02 → 03 (le socle).
2. Ensuite, trois pistes en parallèle :
   - **extraction** : 04 → 05 → 14 ;
   - **relevé** : 06 → 07 → 08 → 09 ;
   - **captures** : 12 → 13.
3. 10 dès 02 + 04, puis 11.
4. 15 en dernier.

**Revues à J+7 et à J+14** : les portes du §6 et les indicateurs du §7.

## 9. Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | Les endpoints Jarvis existants sont réutilisés. La session compagnon `jarvis-compagnon` (SF-232-05) accède aux blocs par le cerveau rapide, comme aujourd'hui. Aucun nouvel endpoint ouvert à cette portée. |
| Contexte tenant | **Oui** | `JarvisStateFactRepository`, `JarvisStateSettingsRepository`, les dépôts des captures et leur recherche vectorielle (`userId` + `hostId`, et `workspaceId` pour `recall`). `async_jobs` (`user_id` porté par chaque travail, relu sous `user_id`). Le crochet de `WorkspaceDepositService` (après `requireOwned`). L'extracteur (`findByIdAndUserId`). Le relevé (`JarvisWatchPlanner` / `JarvisWatchPassage`). `JarvisFastContextBuilder`, les outils `etat` et `conversations`, `recall`, `JarvisStateNote`. La mesure (ADMIN, agrégats). Un test d'isolation à deux utilisateurs et deux clients dans chaque SF backend. |
| Plans / limites | **Oui** | Captures et constats sont comptés dans le budget Jarvis F-244 (origine `JARVIS`, parts `CAPTURE` et `ETAT`), hors quota Gateway, avec des plafonds propres. Impactés : `JarvisBudgetService`, `JarvisUsageService` / `jarvis_usage_days`, l'étiquette `usage_turns`, la ventilation en €. Extraction et relevé n'appellent aucun modèle. |
| Navigation / routing | Non | Le volet s'ajoute aux réglages existants de `/jarvis`. |
| Protocole runner | **Oui** | `BashTool` / `ToolDispatcher` (priorité, refus, préemption), l'annonce des capacités, `RunnerShutdown` (départ), `RunnerToolGateway.bash` (`priority`). Test de contrat : un vieux runner ignore le champ, et la passerelle ne lui envoie pas de relevé. |

## 10. Hors périmètre

- **Toute copie de dépôt et tout travail hors du poste** : écarté par le PO, à ne pas reproposer.
- Toute **action** poste éteint.
- Un historique des valeurs au-delà de la précédente.
- La conservation des **images** au-delà de leur lecture. Tout OCR maison.
- ~~Le rattrapage des captures déposées avant F-247.~~ **Amendé le 2026-11-05 (v3)** : dans le périmètre, par
  « Synchroniser » (SF-247-16), à la demande du PO, client par client.
- La transcription des captures qui ne sont pas des conversations.
- La capture d'une extinction brutale.
- **Toute source Vigie, Teams ou Radar** (correction PO), et toute lecture Teams par le runner.
- Kafka, Redis ou tout ordonnanceur hors Postgres.
- Le déploiement : décision séparée, après vérification de `runner_audit` et des tours en cours.

## 11. Questions PO

**Aucune.** Les défauts D1-D16 sont pris, et le D3 est tranché par le PO. Un point reste à revoir sur mesure,
sans le solliciter : les plafonds de 10 € et de 15 €, une fois le coût réel connu.
