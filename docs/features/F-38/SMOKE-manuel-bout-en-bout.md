# F-38 — Smoke manuel bout en bout (protocole opérateur)

> **Statut : à planifier par le product owner.** Ce document est le **reliquat de F-38**, sorti du
> périmètre automatisable et **parqué** le 2026-09-06. Il ne bloque plus le statut de la feature
> (voir `docs/PRODUCT_SPEC.md`, F-38 **Terminée**) : il constate en conditions réelles ce que la
> suite de tests ne peut pas constater.
>
> Question adressée au PO : **OQ-13** dans `docs/OPEN_QUESTIONS.md`.

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
| P1 | Environnement cible | Production `portal.ng-itconsulting.com` — déjà déployée le 2026-08-30 (image `staging-b907947`, migrations 048/049, `APP_RUNNER_REGISTRY=pg-notify`, connecteur de relais 8081). |
| P2 | Compte de test | Un compte avec accès à l'Atelier, **distinct** du compte de production de l'opérateur (le scénario S9 le supprime). |
| P3 | Machine | Poste ou VM **hors cluster**, Java 21 (`java -version` → 21), un projet réel sous une racine dédiée. |
| P4 | Jar | `GET /api/runner/download` depuis l'écran d'appairage (~2,5 Mo). Repli : `./mvnw -pl runner package`. |
| P5 | Réseau contraint (S5 seulement) | Un accès sortant passant par un proxy qui **casse l'`Upgrade`**. À défaut d'un vrai proxy d'entreprise : `HTTPS_PROXY` vers un mandataire configuré pour refuser l'`Upgrade` — **noter dans le compte rendu** que le proxy était simulé, le scénario reste alors *partiel*. |
| P6 | Accès cluster (S6 seulement) | Droit de porter le déploiement backend à **2 replicas** puis de revenir à 1. |

## 3 — Scénarios

Chaque scénario se solde par **OK / KO / non joué**, avec une observation en une phrase.

### S1 — Appairage et connexion sortante
1. Écran d'appairage → générer un **code à usage unique**, noter son TTL.
2. Sur la machine : `java -jar claude-runner.jar --gateway https://portal.ng-itconsulting.com --workspace <racine> --code <code>`.
3. **Attendu** : le runner affiche son activité en clair, la connexion **sortante** WSS s'établit (aucun port entrant ouvert), l'écran passe à **« runner connecté »** en quelques secondes.
4. Arrêter puis **relancer sans `--code`** : le jeton persisté suffit, pas de réappairage.
5. Rejouer le **même code** : il doit être **refusé** (usage unique).

### S2 — Fichiers, racine et exclusions
1. Dans l'Atelier, workspace en cible **`RUNNER`** : demander la lecture d'un fichier du projet → contenu réel de la machine, **aucun `.zip`** dans le parcours.
2. Demander une écriture → le fichier est modifié **sur la machine**.
3. Demander un chemin **hors racine** (`../../etc/passwd`) → **refus**.
4. Déposer un `.env` et un `.runnerignore` → vérifier que le `.env` est **invisible** à la lecture (liste par défaut non désactivable, D10).

### S3 — `bash` : flux, code retour, délai, interruption
1. Lancer une commande longue et bavarde (`for i in $(seq 1 50); do echo $i; sleep 1; done`) → la sortie **défile ligne à ligne**, elle n'arrive pas en bloc à la fin.
2. Une commande en échec → **code retour non nul** rendu comme tel.
3. Une commande dépassant le délai → rendue comme **délai dépassé**, et **pas** comme une annulation (correctif SF-38-11).
4. Interrompre un tour en cours (bouton d'interruption, F-32) → la commande s'arrête sur la machine, le tour se termine proprement.

### S4 — Porte de confirmation (F-33) non contournable
1. Une commande sensible déclenche la **demande de validation** ; l'exécution attend.
2. **Refuser** → rien ne s'exécute. **Accepter** → la commande part.
3. Vérifier qu'aucun réglage de l'UI ne permet de désactiver la porte **en cible `RUNNER`**.
4. Interrompre pendant l'attente → la demande est **libérée**, pas laissée en suspens.

### S5 — Repli de transport derrière un proxy (le cœur du reliquat)
1. Relancer le runner avec `HTTPS_PROXY` vers un proxy qui **coupe l'`Upgrade`**.
2. **Attendu** : le WebSocket échoue, le runner bascule en **long-polling HTTP** (SF-38-09) et l'écran affiche toujours **« runner connecté »**.
3. Rejouer **S2** et **S3** dans ce mode : lecture, écriture, `bash` **en flux**, interruption.
4. Vérifier le **truststore d'entreprise** si le proxy termine le TLS (SF-38-03).

### S6 — Deux pods (relais inter-pods)
1. Porter le backend à **2 replicas**, s'assurer que le runner est connecté à **un** pod.
2. Ouvrir la session depuis un navigateur ; rejouer **S3** et **S4** jusqu'à obtenir un tour piloté par le pod **qui n'a pas** la socket.
3. **Attendu** : aucun `runner_not_on_this_node` ; le flux, la **décision de la porte**, l'**annulation** et l'**interruption** traversent (SF-38-12/13).
4. Revenir à **1 replica**.

### S7 — `Ctrl-C` et déconnexion
1. `Ctrl-C` sur le runner → arrêt propre (code 0), socket fermée.
2. L'écran repasse à **« déconnecté »** ; un tour lancé alors échoue **explicitement**, sans attente muette.

### S8 — Révocation, coupe-circuit, audit
1. Révoquer le jeton depuis l'écran → le runner perd la main **immédiatement**, un relancement sans code est refusé.
2. Actionner le **coupe-circuit** → plus aucune exécution.
3. Vérifier le **journal d'audit** (`runner_audit`) : commandes **et** lectures, sous le bon `user_id`.

### S9 — Suppression de compte (SF-38-14)
1. Supprimer le compte de test → codes d'appairage, jetons et journal d'audit du runner **disparaissent**.
2. Le runner encore lancé se voit **refuser** la reconnexion.

## 4 — Compte rendu attendu

Une seule ligne par scénario, ajoutée en fin de ce document sous « **5 — Résultats** », avec la date,
l'opérateur, l'image déployée et la nature du proxy (réel / simulé).

**Si tout est OK** → une ligne d'historique dans `docs/PRODUCT_SPEC.md` (« smoke manuel F-38 joué le … »),
rien d'autre : la feature est déjà **Terminée**.

**Si un scénario est KO** → il ne rouvre pas F-38 en bloc : ouvrir une **subfeature correctif**
(`SF-38-15`…) ciblant précisément le scénario, avec la sortie observée. Un KO sur **S5** ou **S6** est
**bloquant pour la promesse produit** (réseau d'entreprise, HPA `min 1 / max 4`) et passe devant le
reste du backlog ; un KO sur S1–S4, S7–S9 se traite au fil de l'eau.

## 5 — Résultats

| Date | Opérateur | Image | Proxy | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | Observations |
|------|-----------|-------|-------|----|----|----|----|----|----|----|----|----|--------------|
| _à planifier_ | | | | | | | | | | | | | |
