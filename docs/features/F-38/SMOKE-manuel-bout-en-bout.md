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

> **Révision du 2026-09-08 — le protocole rattrape SF-38-22 → SF-38-27.** Le même défaut qu'en
> septembre s'est reproduit, pour la même raison : le protocole ne compile pas, donc rien ne l'a
> prévenu quand **six subfeatures** de plus sont passées sur `main` les 2026-09-07 et 2026-09-08
> (prérequis Java nommé, chemin Windows avalé par le shell, panne réseau qui se décrit, contrôle de
> vol réseau au démarrage, console de démarrage exacte, élection de l'interpréteur). Toutes viennent
> du **premier lancement chez un client, sur un poste Windows d'entreprise**.
>
> Deux conséquences, dont une aurait produit un **KO faux** :
>
> 1. **Le prérequis d'image monte de la migration 053 à la 063** (`workspaces.runner_shell`).
> 2. **Le contrôle de vol de SF-38-25 s'exécute avant l'appairage** : derrière un proxy qui bloque
>    *tout* le trafic sortant, le runner s'arrête désormais **avant** de tenter quoi que ce soit. Un
>    opérateur qui joue **S5** sans le savoir lirait cet arrêt comme « le repli de transport est
>    cassé » et ouvrirait une subfeature correctif contre un comportement **voulu** — voir la note
>    ajoutée en tête de S5.
>
> **Et un constat de couverture** : SF-38-22, 23, 26 et 27 ne se manifestent **que sous Windows**.
> Joué sur un poste Linux ou macOS, ce protocole ne verrait **aucun** des quatre. Le prérequis P3
> est modifié en conséquence.
>
> **Et une feature entière qui n'y figurait pas** : **F-44** (livrée le 2026-09-07, PR #279/#280)
> donne au poste Windows un **paquet autonome embarquant sa propre JVM** — l'écran d'appairage le
> propose désormais **en premier** sur Windows, et la commande devient `claude-runner.cmd …`, sans
> `java -jar`. Le protocole ne connaissait que le jar : son prérequis **P4** décrivait un
> téléchargement de 2,5 Mo qui n'est plus le chemin par défaut, et **S12** (prérequis Java) n'a
> **aucun sens** sur le paquet autonome — l'y jouer produirait un KO contre une feature qui fait
> exactement son travail.
>
> **Cinq scénarios sont ajoutés** — S12 (prérequis Java, *format jar uniquement*), S13 (chemin
> Windows), S14 (réseau : contrôle de vol et panne lisible), S15 (interpréteur élu et consigne
> accordée), **S16** (le paquet autonome, F-44) —, toujours **à la fin**, selon la même convention de
> numérotation stable.

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
   « la sortie défile-t-elle vraiment au fil de l'eau ? » est humain ;
4. **une vraie console Windows** (ajouté le 2026-09-08) — une JVM ancienne, une page de code
   **cp850**, un `bash.exe` de WSL posé dans `System32`, un chemin `C:\Users\…` avalé par Git Bash :
   quatre situations que la suite de tests **simule** (jeux de caractères, chemins factices) mais que
   seul un poste réel **présente ensemble**. C'est de là que viennent SF-38-22, 23, 26 et 27.

Automatiser cela reviendrait à construire un banc d'essai (VM éphémère + proxy + pilotage
navigateur) plus coûteux et plus fragile que la feature elle-même, pour un parcours joué **une
fois** à la mise en service.

## 2 — Prérequis

| # | Prérequis | Détail |
|---|-----------|--------|
| P1 | Environnement cible | Production `portal.ng-itconsulting.com`. ⚠️ **L'image déployée le 2026-08-30 (`staging-b907947`, migrations 048/049) ne suffit plus** : elle est antérieure à SF-38-15→27. Le smoke doit être joué sur une image contenant **au moins la migration 063** (`workspaces.runner_shell`, SF-38-27) — le seuil était 053 avant le 2026-09-08. **Trois signes à vérifier avant de commencer**, un par lot : l'écran de création de projet propose « **sur ma machine** » (SF-38-16, migration 052) ; le panneau d'autorisation affiche le compte du runner (SF-38-18, migration 053) ; le runner affiche au démarrage les lignes **`Réseau : gateway joignable`** (SF-38-25) et **`Interpréteur : …`** (SF-38-27, migration 063). Sans ces signes, l'image est trop ancienne — **ne pas jouer le protocole** : il rendrait des KO qui ne disent rien du produit. |
| P2 | Compte de test | Un compte avec accès à l'Atelier, **distinct** du compte de production de l'opérateur (le scénario S9 le supprime). |
| P3 | Machine | Poste ou VM **hors cluster**, Java 21 (`java -version` → 21), un projet réel sous une racine dédiée. Un projet avec des **dépendances installées** (`node_modules`, `target`, `.venv`) est préférable : S2 en a besoin. **Windows fortement recommandé** (voir P3bis). |
| P3bis | Système de la machine | **Quatre des six derniers correctifs — SF-38-22, 23, 26, 27 — ne se manifestent que sous Windows** : trace JVM d'une version trop ancienne, chemin `C:\Users\…` avalé par Git Bash, ponctuation rendue `?` sur une console cp850, absence de `ls`/`grep` dans `cmd.exe`. Joué sur Linux ou macOS, le protocole **ne les verrait pas** et rendrait un « tout OK » qui ne couvre pas le poste type d'un client. Le passage de référence se fait donc **sur Windows** ; un passage Unix reste utile mais **S13 et S15 y sont notés « non joué »**, jamais « OK ». Idéalement un poste avec **Git pour Windows** installé (S15 attend l'élection de Git Bash). |
| P4 | Jar | `GET /api/runner/download` depuis l'écran d'appairage (~2,5 Mo). Repli : `./mvnw -pl runner package`. Un **404 au téléchargement n'est pas une panne** : la gateway n'a alors pas empaqueté le jar, et l'écran bascule de lui-même sur la commande de construction. |
| P4bis | Format de téléchargement (**F-44**, ajouté le 2026-09-08) | Depuis F-44, l'écran propose **deux formats** et retient le **paquet autonome Windows** par défaut sur un poste Windows (`GET /api/runner/download/windows`, ~39 Mo, JVM embarquée) ; la commande devient alors `claude-runner.cmd --gateway … --workspace "…" --code …`, **sans `java -jar`**. **Le format retenu se note dans le compte rendu** : il change la commande de S1, rend **S12 sans objet** (il n'y a plus de prérequis Java) et conditionne **S16**. Le passage de référence joue **les deux** : le paquet pour S16, le jar pour S12. Un **404** sur l'un des deux formats n'est pas une panne — la gateway ne l'a pas empaqueté, et l'écran bascule de lui-même. |
| P5 | Réseau contraint (S5 seulement) | Un accès sortant passant par un proxy qui **casse l'`Upgrade`**. À défaut d'un vrai proxy d'entreprise : `HTTPS_PROXY` vers un mandataire configuré pour refuser l'`Upgrade` — **noter dans le compte rendu** que le proxy était simulé, le scénario reste alors *partiel*. |
| P6 | Accès cluster (S6 seulement) | Droit de porter le déploiement backend à **2 replicas** puis de revenir à 1. |

## 3 — Scénarios

**Ordre d'exécution recommandé** : **S16** (choisir le format de téléchargement — il commande la
commande de S1) → **S12** (le runner refuse une JVM trop ancienne ; *format jar seulement*) →
**S14** (le contrôle de vol réseau, qui s'exécute lui aussi avant l'appairage) → **S11** (créer le
projet « sur ma machine ») → **S13** (la commande d'appairage et son chemin Windows) → **S1**
(appairer) → **S15** (l'interpréteur élu) → **S10** (les droits annoncés) → **S2** → **S3** → **S4**
→ **S5** → **S6** → **S7** → **S8** → **S9** (destructif, en dernier).

Les cinq scénarios ajoutés le 2026-09-08 (**S12** à **S16**) portent des numéros de fin mais se
jouent **au début** : ils couvrent ce qui se passe *avant* la première connexion. La numérotation
reste stable pour que les renvois écrits ailleurs continuent de désigner les mêmes scénarios.

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
4. **Lisibilité de la console (SF-38-26)** — c'est le **seul** point du protocole qu'aucun test ne
   peut couvrir : il faut une vraie console Windows. Sur `cmd.exe` ou PowerShell (page de code
   **cp850** en France, vérifiable par `chcp`), relire les lignes de démarrage : **aucun `?`** ne
   doit apparaître à la place d'un caractère, les accents doivent être corrects, et les points de
   suspension rendus `...`. Un `Appairage aupr?s de ...` est un **KO**.
   L'état d'exécution doit par ailleurs n'être annoncé **qu'une fois** (« Commandes : … »), et
   **aucune** ligne ne doit citer `--allow-bash`, qui n'a plus d'effet depuis SF-38-19.
5. Arrêter puis **relancer sans `--code`** : le jeton persisté suffit, pas de réappairage.
6. Rejouer le **même code** : il doit être **refusé** (usage unique).

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
6. **Le refus dit le bon geste (SF-38-26)** : dans ce mode, le message rendu **dans le chat** ne doit
   **pas** conseiller de relancer avec `--allow-bash` — ce drapeau n'a plus d'effet depuis SF-38-19,
   et la restriction l'emporte sur lui. Un message qui le cite envoie l'utilisateur réparer avec un
   geste qui ne répare rien : **KO**.

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
> ⚠️ **À lire avant de jouer S5 — ajouté le 2026-09-08.** Depuis **SF-38-25**, le runner joint la
> gateway **avant l'appairage**. Le scénario ne garde son sens que si le proxy **laisse passer le
> HTTPS ordinaire et casse seulement l'`Upgrade`** : c'est alors le contrôle de vol qui passe
> (`Réseau : gateway joignable`), puis le WebSocket qui échoue, puis le repli qui prend la main.
> Si le proxy bloque **tout** le trafic sortant, le runner s'arrête **avant** d'avoir essayé quoi que
> ce soit, avec le message de configuration de proxy — **ce n'est pas un KO de S5**, c'est S14 qui
> fonctionne, et le proxy qui est mal réglé pour ce scénario. Corriger le proxy et rejouer.

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

### S12 — Le prérequis Java se dit, il ne se devine pas (SF-38-22)
> **Format `jar` uniquement.** Sur le **paquet autonome Windows** (F-44), ce scénario est **sans
> objet** — le paquet embarque sa JVM, aucun Java système n'est consulté : le noter **« non joué
> (paquet autonome) »**, jamais KO. Le jouer suppose donc de télécharger le jar, même si l'écran
> propose le paquet par défaut.
>
> Se joue **en premier** : une JVM trop ancienne interdit tout le reste du protocole.

1. Sur une machine (ou avec un `JAVA_HOME`) portant une **JVM antérieure à 21** — Java 8 ou 11 —,
   lancer le jar.
2. **Attendu** : un message court qui **nomme les deux versions en clair** (« ce poste a Java 8, le
   runner demande Java 21 ») et dit quoi faire. **KO** si la console rend la trace d'origine :
   `UnsupportedClassVersionError`, « class file version 65.0 … up to 52.0 » — deux nombres qu'aucun
   utilisateur ne rattache à une version de Java.
3. Vérifier que le message sort **avant** toute tentative de connexion, et que le processus s'arrête
   proprement plutôt que de laisser une trace de trente lignes.
4. Sur l'**écran d'appairage**, relire la formulation du prérequis : elle doit se lire comme un
   **plancher** (« Java 21 ou plus récent »), pas comme un plafond (« Java 21 suffit »).
5. Repasser sur Java 21 pour la suite du protocole.

### S13 — La commande d'appairage survit au shell Windows (SF-38-23)
> **Non joué** hors Windows. C'est un défaut de *shell*, pas de runner.

1. Sur l'écran d'appairage d'un projet dont la racine est un chemin Windows
   (`C:\Users\<login>\dev\<projet>`), **relire la commande affichée** : la valeur de `--workspace`
   doit être **entre guillemets**. Sans guillemets, c'est un **KO** — la cause du défaut est là.
2. **Copier** la commande et la coller **dans Git Bash** (MINGW64). Elle doit fonctionner telle
   quelle. Sans guillemets, `\U`, `\d` et `\c` sont lus comme des séquences d'échappement et le
   shell livre `C:UsersU66YA96devcagip` — que Windows résout ensuite comme un chemin **relatif**.
3. Contre-épreuve : taper volontairement le chemin **sans guillemets**. **Attendu** : le runner ne se
   contente pas d'afficher un chemin introuvable, il **nomme le problème** — le chemin reçu diffère
   de celui qui a été tapé, le shell l'a transformé, remettre des guillemets. Un message qui affiche
   seulement un chemin que l'utilisateur n'a jamais tapé est un **KO**.
4. Rejouer la même commande dans `cmd.exe` et dans **PowerShell** : elle doit passer dans les trois.

### S14 — Le réseau : contrôle de vol au démarrage, et pannes qui se décrivent (SF-38-24, SF-38-25)
1. **Cas nominal** — machine qui sort normalement : au démarrage, **avant l'appairage**, une seule
   ligne de plus, `Réseau : gateway joignable`. Rien d'autre. Un contrôle de vol bavard sur un réseau
   sain est un défaut d'usage.
2. **Gateway injoignable** — couper le réseau, ou pointer `--gateway` sur un hôte qui ne résout pas.
   **Attendu** : le runner s'arrête **avant** l'appairage, avec un message en trois parties : ce qui
   a échoué (type d'exception **nommé**), la piste (DNS ? proxy obligatoire ?), et **les gestes du
   système courant** — `netsh winhttp show proxy` et les `reg query` sous Windows, `scutil --proxy`
   sur macOS, `env | grep -i proxy` sur Linux, suivis de la bonne syntaxe d'export pour **le shell
   utilisé**. Un message qui donne des commandes Windows sur un poste macOS est un **KO**.
3. **Aucun `null`** (SF-38-24) : quelle que soit la panne provoquée — DNS, TLS intercepté, connexion
   refusée, délai dépassé —, la ligne d'erreur porte **toujours** un type d'exception et, s'il y en a
   une, sa chaîne de causes. Une ligne se terminant par `: null` est un **KO** — c'est exactement le
   défaut d'origine.
4. Rejouer le point 3 sur **deux** chemins réseau au moins : l'appairage **et** la connexion (couper
   après l'appairage réussi).
5. **Faux positif** — le point le plus important : sur un réseau **sain**, le contrôle de vol ne doit
   **jamais** empêcher le démarrage. Un runner qui refuse de partir alors que la gateway répond est
   un **KO bloquant** : il transforme un produit qui marche en produit qui ne démarre pas.

### S15 — L'interpréteur élu, déclaré, et la consigne accordée (SF-38-27)
> **Non joué** hors Windows : sur Unix, `/bin/sh` est élu sans concurrence et le scénario ne prouve
> rien. Le point 4, en revanche, se vérifie partout.

1. Au démarrage, la console annonce l'élection au même titre que le compte et les exclusions —
   `Interpréteur : bash POSIX — C:\Program Files\Git\bin\bash.exe` sur un poste avec Git pour
   Windows.
2. **Le piège WSL** : sur un Windows 10+ sans Git pour Windows mais avec WSL installé, l'élection ne
   doit **pas** retenir le `bash.exe` de `System32` (c'est le lanceur WSL, dont le système de
   fichiers n'est pas celui du projet) ; elle doit descendre sur **PowerShell**. Vérifier ensuite
   qu'une commande s'exécute bien **dans la racine du projet** — un `cwd` `C:\Users\…` inexistant
   côté WSL serait le symptôme du piège non écarté.
3. **La cascade** : désinstaller/masquer successivement bash puis PowerShell (ou renommer le `PATH`)
   → l'élection descend `posix` → `powershell` → `cmd`, et **l'annonce suit** à chaque fois.
4. **La consigne accordée — le cœur du scénario** : dans l'Atelier, en cible `RUNNER`, demander une
   exploration (« liste les fichiers du projet »). **Attendu** : le modèle emploie la syntaxe de
   l'interpréteur **réellement élu** — `ls`/`find`/`grep -n` en `posix`, `Get-ChildItem`/`Select-String`
   en `powershell`, `dir /s /b`/`findstr /s /n` en `cmd`. Une salve de
   `'ls' n'est pas reconnu en tant que commande interne ou externe` suivie de réessais est un **KO** :
   c'est le défaut d'origine, et il **consume le tour en erreurs**.
5. Vérifier que la valeur est bien **portée par le projet** : reconnecter le runner depuis un poste
   d'un autre type (ou en forçant un autre interpréteur) → la consigne suit le nouveau `shell`
   déclaré, elle ne reste pas figée sur le premier appairage.

### S16 — Le paquet autonome Windows, sans prérequis Java (F-44)
> **Non joué** hors Windows x64 : le paquet n'existe que là, et c'est un choix de périmètre assumé —
> un poste macOS ou Linux a déjà un JDK et garde le jar de 2,5 Mo.
>
> **C'est le scénario que seule une vraie machine peut jouer** : la construction croisée est vérifiée
> en intégration continue, mais **rien ne prouve en CI qu'un Windows verrouillé exécute le paquet**.
> C'est précisément la situation qui a produit F-44 — poste d'entreprise en Java 8, JVM imposée par
> la DSI, pas de droits administrateur.

1. Sur l'écran d'appairage, depuis un poste **Windows**, le format proposé **par défaut** est le
   **paquet autonome** (~39 Mo), le jar restant offert en second. Un écran qui ne propose que le jar
   est un **KO** — sur ce poste, c'est le format qui a échoué au premier contact.
2. La **commande affichée** est `claude-runner.cmd --gateway … --workspace "…" --code …` : elle ne
   doit **pas** être préfixée par `java -jar`, qui rappellerait la JVM système — celle-là même qui
   manque. Un `java` dans la commande du paquet autonome est un **KO**.
3. Décompresser le ZIP **sans droits administrateur**, dans un dossier utilisateur, et lancer la
   commande. **Attendu** : le runner démarre et s'appaire, **sur un poste où `java -version` échoue
   ou rend une version antérieure à 21** — c'est toute la promesse de la feature. La vérifier
   explicitement : ouvrir une console, constater l'absence (ou l'ancienneté) du Java système,
   **puis** lancer le paquet.
4. Aucun **installeur**, aucune **invite d'élévation**, aucune écriture hors du dossier décompressé.
5. **Le reste du protocole s'applique tel quel** : une fois appairé, un runner issu du paquet est un
   runner comme un autre. Rejouer au minimum **S1.4** (lisibilité de la console), **S14.1** (contrôle
   de vol) et **S15.1** (interpréteur élu) sous ce format — ce sont les trois annonces de démarrage,
   et elles passent par un lanceur différent.

## 4 — Compte rendu attendu

Une seule ligne par scénario, ajoutée en fin de ce document sous « **5 — Résultats** », avec la date,
l'opérateur, l'image déployée et la nature du proxy (réel / simulé).

**Si tout est OK** → une ligne d'historique dans `docs/PRODUCT_SPEC.md` (« smoke manuel F-38 joué le … »),
rien d'autre : la feature est déjà **Terminée**.

**Si un scénario est KO** → il ne rouvre pas F-38 en bloc : ouvrir une **subfeature correctif**
(**`SF-38-28`…** — les numéros **15 à 27** sont consommés depuis le 2026-09-08 ; le seuil était
`SF-38-22` avant cette date) ciblant précisément le scénario, avec la sortie observée.

Sont **bloquants pour la promesse produit** et passent devant le reste du backlog :

| Scénario | Pourquoi |
|---|---|
| **S5** | Sans repli de transport, le runner ne franchit pas un réseau d'entreprise — la promesse tombe. |
| **S6** | Le HPA `min 1 / max 4` peut créer un second pod tout seul : un relais cassé casse le mode en production, sans action humaine. |
| **S4.5** | Une commande autorisée en groupe et **absente du journal d'audit** : ce que SF-38-20 promet de ne jamais perdre. |
| **S11.4** | Un **chemin absolu** remonté à la gateway : SF-38-15 repose sur le fait qu'elle ne l'apprend pas. |
| **S14.5** | Le **contrôle de vol qui refuse de démarrer sur un réseau sain** : SF-38-25 est une porte posée **avant** tout le reste — un faux positif ne dégrade pas le produit, il l'empêche de partir. |

Les autres KO se traitent au fil de l'eau.

## 5 — Résultats

Colonnes ajoutées le 2026-09-08 : **S12** à **S16**, plus **Système** et **Format**. Les deux
dernières ne sont pas décoratives : un passage hors Windows ne couvre ni S13, ni S15, ni S16, et le
**format** retenu (jar ou paquet autonome) rend S12 sans objet et change la commande de S1. Un compte
rendu qui ne les porte pas ne se relit pas six mois plus tard.

| Date | Opérateur | Système | Format | Image | Proxy | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | S10 | S11 | S12 | S13 | S14 | S15 | S16 | Observations |
|------|-----------|---------|--------|-------|-------|----|----|----|----|----|----|----|----|----|-----|-----|-----|-----|-----|-----|-----|--------------|
| _à planifier_ | | | | | | | | | | | | | | | | | | | | | | |
