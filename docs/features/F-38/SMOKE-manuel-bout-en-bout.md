# F-38 — Smoke manuel bout en bout (protocole opérateur)

> **Statut : à planifier par le product owner.** Ce document est le **reliquat de F-38**, sorti du
> périmètre automatisable et **parqué** le 2026-09-06. Il ne bloque plus le statut de la feature
> (voir `docs/PRODUCT_SPEC.md`, F-38 **Terminée**) : il constate en conditions réelles ce que la
> suite de tests ne peut pas constater.
>
> Question adressée au PO : **OQ-13** dans `docs/OPEN_QUESTIONS.md`.

> **Révision du 2026-09-06 (soir) — le protocole est remis au niveau du runner livré.** La première
> version a été écrite le matin, **avant** que le second passage du banc d'essai ne livre
> SF-38-15→21 (source `LOCAL`, écran « sur ma machine », explorateur qui lit la machine,
> `bash` par défaut, autorisations groupées, privilèges déclarés, filtre du bruit de construction).
> Joué tel quel, il aurait produit **au moins un KO faux** : le point S4.3 exigeait qu'**aucun**
> réglage ne puisse desserrer la porte de confirmation, alors que **SF-38-20 a précisément amendé
> cette décision** (D7 de SF-38-08). Les scénarios sont donc corrigés, et deux sont **ajoutés**
> (S10 privilèges, S11 projet « sur ma machine »).
>
> **Numérotation volontairement stable** : S10 et S11 sont *ajoutés à la fin* plutôt qu'insérés à
> leur place logique — S11 est pourtant le tout premier geste de l'opérateur — pour que les renvois
> déjà écrits ailleurs (« un KO sur **S5** ou **S6** est bloquant », `PRODUCT_SPEC.md`,
> `OPEN_QUESTIONS.md`) continuent de désigner les mêmes scénarios. L'**ordre d'exécution**
> recommandé est donné au début du §3.

---

## 1 — Pourquoi ce reliquat n'est pas automatisable

Tout ce qui pouvait être vérifié sans machine tierce l'a été et vit dans la suite de tests :
handshake WebSocket, registre, confinement à la racine, exclusions, garde-fous, journal d'audit,
relais inter-pods (SF-38-12/13, dont un test chronométré prouvant que le flux relayé n'est pas
bufferisé), purge à la suppression de compte (SF-38-14).

Ce qui reste demande **trois choses qu'un test ne fabrique pas** :

1. **une vraie machine** — un poste ou une VM tierce, hors du cluster, avec Java 21 et un projet réel ;
2. **un vrai réseau d'entreprise** — en particulier un proxy sortant qui **refuse ou coupe
   l'`Upgrade` WebSocket**, seule situation qui déclenche honnêtement le repli long-polling de
   SF-38-09 ; un proxy simulé prouve le code, pas le terrain ;
3. **un opérateur** — l'appairage se fait à l'écran, le `Ctrl-C` au clavier, et le jugement
   « la sortie défile-t-elle vraiment au fil de l'eau ? » est humain.

Automatiser cela reviendrait à construire un banc d'essai (VM éphémère + proxy + pilotage
navigateur) plus coûteux et plus fragile que la feature elle-même, pour un parcours joué **une
fois** à la mise en service.

## 2 — Prérequis

| # | Prérequis | Détail |
|---|-----------|--------|
| P1 | Environnement cible | Production `portal.ng-itconsulting.com`. ⚠️ **L'image déployée le 2026-08-30 (`staging-b907947`, migrations 048/049) ne suffit plus** : elle est antérieure à SF-38-15→21. Le smoke doit être joué sur une image contenant **au moins la migration 053** (`workspaces.runner_elevated`). Vérifier avant de commencer : l'écran de création de projet propose « **sur ma machine** » (SF-38-16) et le panneau d'autorisation affiche le compte du runner (SF-38-18). Sans ces deux signes, l'image est trop ancienne — **ne pas jouer le protocole**. |
| P2 | Compte de test | Un compte avec accès à l'Atelier, **distinct** du compte de production de l'opérateur (le scénario S9 le supprime). |
| P3 | Machine | Poste ou VM **hors cluster**, Java 21 (`java -version` → 21), un projet réel sous une racine dédiée. Un projet avec des **dépendances installées** (`node_modules`, `target`, `.venv`) est préférable : S2 en a besoin. |
| P4 | Jar | `GET /api/runner/download` depuis l'écran d'appairage (~2,5 Mo). Repli : `./mvnw -pl runner package`. Un **404 au téléchargement n'est pas une panne** : la gateway n'a alors pas empaqueté le jar, et l'écran bascule de lui-même sur la commande de construction. |
| P5 | Réseau contraint (S5 seulement) | Un accès sortant passant par un proxy qui **casse l'`Upgrade`**. À défaut d'un vrai proxy d'entreprise : `HTTPS_PROXY` vers un mandataire configuré pour refuser l'`Upgrade` — **noter dans le compte rendu** que le proxy était simulé, le scénario reste alors *partiel*. |
| P6 | Accès cluster (S6 seulement) | Droit de porter le déploiement backend à **2 replicas** puis de revenir à 1. |

## 3 — Scénarios

**Ordre d'exécution recommandé** : **S11** (créer le projet « sur ma machine ») → **S1** (appairer) →
**S10** (les droits annoncés) → **S2** → **S3** → **S4** → **S5** → **S6** → **S7** → **S8** →
**S9** (destructif, en dernier).

Chaque scénario se solde par **OK / KO / non joué**, avec une observation en une phrase.

### S1 — Appairage et connexion sortante
1. Écran d'appairage → générer un **code à usage unique**, noter son TTL.
2. **Copier la commande affichée par l'écran** (ne pas la retaper de mémoire) et la coller sur la
   machine. Elle a la forme
   `java -jar claude-runner.jar --gateway https://portal.ng-itconsulting.com/api --workspace <racine> --code <code>`.
   ⚠️ Le suffixe **`/api`** de `--gateway` n'est pas décoratif : sans lui le runner tape le
   frontend, et le tout premier geste rend un `405` déroutant (correctif PR #244). Si la commande
   affichée ne le porte pas, **c'est un KO**.
3. **Attendu** : le runner affiche son activité en clair — gateway, racine, **« Commandes :
   autorisées »** (SF-38-19 : l'exécution est le **défaut**, `--no-bash` la restriction) et le
   compte sous lequel il tourne (S10) —, la connexion **sortante** WSS s'établit (aucun port
   entrant ouvert), l'écran passe à **« runner connecté »** en quelques secondes.
4. Arrêter puis **relancer sans `--code`** : le jeton persisté suffit, pas de réappairage.
5. Rejouer le **même code** : il doit être **refusé** (usage unique).

### S2 — Fichiers, racine, exclusions et explorateur
1. Dans l'Atelier, workspace en cible **`RUNNER`** : demander la lecture d'un fichier du projet → contenu réel de la machine, **aucun `.zip`** dans le parcours.
2. Demander une écriture → le fichier est modifié **sur la machine**.
3. Demander un chemin **hors racine** (`../../etc/passwd`) → **refus**.
4. Déposer un `.env` et un `.runnerignore` → vérifier que le `.env` est **invisible** à la lecture (liste par défaut non désactivable, D10).
5. **Explorateur (SF-38-17)** : ouvrir le panneau de fichiers → il montre l'arborescence **lue à la
   demande sur la machine**, et non une copie ; **rien n'est monté dans le stockage objet**.
6. **Bruit de construction (SF-38-21)** : le projet a des dépendances installées (P3) → l'explorateur
   montre **les fichiers du projet**, pas les milliers d'entrées de `node_modules`/`target`. Ajouter
   une **négation** dans le `.runnerignore` (par ex. `!node_modules`) → le dossier réapparaît : ces
   motifs sont **négociables**, contrairement à la liste de secrets du point 4.
7. Si l'arborescence est **tronquée**, l'écran **le dit** — un projet amputé en silence est un KO.

### S3 — `bash` : flux, code retour, délai, interruption
1. Lancer une commande longue et bavarde (`for i in $(seq 1 50); do echo $i; sleep 1; done`) → la sortie **défile ligne à ligne**, elle n'arrive pas en bloc à la fin.
2. Une commande en échec → **code retour non nul** rendu comme tel.
3. Une commande dépassant le délai → rendue comme **délai dépassé**, et **pas** comme une annulation (correctif SF-38-11).
4. Interrompre un tour en cours (bouton d'interruption, F-32) → la commande s'arrête sur la machine, le tour se termine proprement.
5. Relancer le runner avec **`--no-bash`** → les commandes sont refusées, les outils fichiers restent
   disponibles, et **le runner l'annonce au démarrage** (« Commandes : refusées ») plutôt que de le
   laisser découvrir au premier refus. Repasser sans le drapeau pour la suite du protocole.

### S4 — Porte de confirmation (F-33) : ce qui se desserre, et ce qui ne se desserre jamais
> **Corrigé le 2026-09-06 (soir).** SF-38-20 **amende la décision D7 de SF-38-08** : la porte n'est
> plus absolue, parce qu'une procédure de treize étapes demandait des dizaines de clics et qu'une
> garde qu'on subit finit contournée. Ce qui disparaît est **le clic**, jamais **la trace**.

1. Une commande sensible déclenche la **demande de validation** ; l'exécution attend.
2. **Refuser** → rien ne s'exécute. **Accepter** → la commande part.
3. **« Tout autoriser pour ce message »** : la **première** commande demande **toujours** ; après
   l'acceptation groupée, les suivantes **du même message** partent sans clic ; la marque **ne
   survit pas au message** (message suivant → la demande revient).
4. **« Ne plus demander sur ce projet »** (`agent_ask_before_bash`) : réglage **du projet**, pris en
   connaissance de cause ; le rétablir ensuite.
5. **Ce qui ne bouge pas, et doit être vérifié explicitement** : le **journal d'audit** trace chaque
   commande — y compris celles passées sans clic — et le **coupe-circuit** reste immédiat. Une
   commande absente du journal parce qu'elle a été autorisée en groupe est un **KO bloquant**.
6. Interrompre pendant l'attente → la demande est **libérée**, pas laissée en suspens.

### S5 — Repli de transport derrière un proxy (le cœur du reliquat)
1. Relancer le runner avec `HTTPS_PROXY` vers un proxy qui **coupe l'`Upgrade`**.
2. **Attendu** : le WebSocket échoue, le runner bascule en **long-polling HTTP** (SF-38-09) et l'écran affiche toujours **« runner connecté »**.
3. Rejouer **S2** et **S3** dans ce mode : lecture, écriture, `bash` **en flux**, interruption.
4. Vérifier le **truststore d'entreprise** si le proxy termine le TLS (SF-38-03).
5. Contre-épreuve utile : `--transport polling` force le repli **sans** proxy. Elle sépare deux
   diagnostics qu'un KO brut confond — « le repli est cassé » et « le proxy n'a pas cassé
   l'`Upgrade` ». Elle ne **remplace pas** le scénario : un repli qui marche sur commande ne prouve
   pas qu'il se déclenche tout seul.

### S6 — Deux pods (relais inter-pods)
1. Porter le backend à **2 replicas**, s'assurer que le runner est connecté à **un** pod.
2. Ouvrir la session depuis un navigateur ; rejouer **S3** et **S4** jusqu'à obtenir un tour piloté par le pod **qui n'a pas** la socket.
3. **Attendu** : aucun `runner_not_on_this_node` ; le flux, la **décision de la porte**, la marque
   « tout autoriser pour ce message » (SF-38-20, diffusée aux pairs), l'**annulation** et
   l'**interruption** traversent (SF-38-12/13).
4. Revenir à **1 replica**.

### S7 — `Ctrl-C` et déconnexion
1. `Ctrl-C` sur le runner → arrêt propre (code 0), socket fermée.
2. L'écran repasse à **« déconnecté »** ; un tour lancé alors échoue **explicitement**, sans attente muette.

### S8 — Révocation, coupe-circuit, audit
1. Révoquer le jeton depuis l'écran → le runner perd la main **immédiatement**, un relancement sans code est refusé.
2. Actionner le **coupe-circuit** → plus aucune exécution.
3. Vérifier le **journal d'audit** (`runner_audit`) : commandes **et** lectures, sous le bon `user_id`.

### S9 — Suppression de compte (SF-38-14) — destructif, à jouer en dernier
1. Supprimer le compte de test → codes d'appairage, jetons et journal d'audit du runner **disparaissent**.
2. Le runner encore lancé se voit **refuser** la reconnexion.

### S10 — Les droits sous lesquels le runner agit (SF-38-18)
1. Au démarrage, le runner **annonce le compte** qui l'exécute (uid réel).
2. La même information est rappelée **là où l'on autorise une commande** — c'est le moment où elle
   compte, pas au lancement qu'on a oublié.
3. Lancer le runner **en root** (ou dans un conteneur) : il **prévient** et **démarre quand même** —
   c'est un usage naturel, pas une faute ; l'écran doit le **signaler** au moment d'autoriser.
4. **KO** si l'écran d'autorisation ne dit rien des droits, ou dit le contraire de ce qu'affiche le
   runner.

### S11 — Créer un projet « sur ma machine » (SF-38-15/16/17)
> Le tout premier geste du parcours réel : sans lui, l'opérateur devrait passer par un `.zip` ou un
> dépôt GitHub, détour que SF-38-15 a précisément supprimé.

1. Écran de création de projet → la troisième option « **sur ma machine** » est proposée, à côté de
   l'archive et du dépôt.
2. Créer le projet **en ne donnant qu'un nom** — aucun chemin saisi, aucun téléversement.
3. Parcours en trois temps : **nommer**, **connecter** (code + commande), **attendre** — l'écran dit
   où l'on en est, au lieu d'un « chargement » indéfini.
4. **Attendu, et c'est le point sensible** : la gateway n'apprend **jamais le chemin absolu** du
   projet ; au plus le **nom du dossier**, déclaré par le runner à l'appairage
   (`workspaces.runner_root_name`). Un chemin absolu visible côté serveur — écran, API, journal — est
   un **KO bloquant**.
5. Aucun **stockage objet** n'est alloué pour un tel projet : ses fichiers ne sont ni copiés, ni
   synchronisés.

## 4 — Compte rendu attendu

Une seule ligne par scénario, ajoutée en fin de ce document sous « **5 — Résultats** », avec la date,
l'opérateur, l'image déployée et la nature du proxy (réel / simulé).

**Si tout est OK** → une ligne d'historique dans `docs/PRODUCT_SPEC.md` (« smoke manuel F-38 joué le … »),
rien d'autre : la feature est déjà **Terminée**.

**Si un scénario est KO** → il ne rouvre pas F-38 en bloc : ouvrir une **subfeature correctif**
(**`SF-38-22`…** — les numéros 15 à 21 sont consommés depuis le 2026-09-06) ciblant précisément le
scénario, avec la sortie observée.

Sont **bloquants pour la promesse produit** et passent devant le reste du backlog :

| Scénario | Pourquoi |
|---|---|
| **S5** | Sans repli de transport, le runner ne franchit pas un réseau d'entreprise — la promesse tombe. |
| **S6** | Le HPA `min 1 / max 4` peut créer un second pod tout seul : un relais cassé casse le mode en production, sans action humaine. |
| **S4.5** | Une commande autorisée en groupe et **absente du journal d'audit** : ce que SF-38-20 promet de ne jamais perdre. |
| **S11.4** | Un **chemin absolu** remonté à la gateway : SF-38-15 repose sur le fait qu'elle ne l'apprend pas. |

Les autres KO se traitent au fil de l'eau.

## 5 — Résultats

| Date | Opérateur | Image | Proxy | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | S10 | S11 | Observations |
|------|-----------|-------|-------|----|----|----|----|----|----|----|----|----|-----|-----|--------------|
| _à planifier_ | | | | | | | | | | | | | | | |
