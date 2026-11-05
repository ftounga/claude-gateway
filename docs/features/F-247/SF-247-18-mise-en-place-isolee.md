# Mini-spec — F-247 / SF-247-18 La mise en place isolée (correctif d'incident)

## Identifiant
`F-247 / SF-247-18`

## Feature parente
`F-247` — Le registre d'état (correctif de SF-247-08, incident prod du 2026-11-05)

## Statut
`done` (2026-11-05, #1631) · **Estimation :** 1 j · **Branche :** `feat/SF-247-18-mise-en-place-isolee` · **Dépend de :** SF-247-08

## Date de création
2026-11-05

## Branche Git
`feat/SF-247-18-mise-en-place-isolee`

## Incident (constaté, journaux et base prod)
- 11:10:23 UTC — le PO lance dans `data-platform` (poste CAGIP) « fais le correctif, déploie-le, génère le message » :
  tour ouvert sur le pod `…-9hnft` (flux web).
- 11:16:39 — dernière commande runner de ce tour avant un silence (raisonnement d'Opus, délégation à un agent nommé).
- 11:16:36 — un nouveau pod `…-vpq5j` démarre (déploiement de SF-247-08).
- 11:19:19 — « Mettre en place » arrive sur `…-vpq5j`. Garde `host_busy` : registre des tours vivants **de ce pod**
  (vide) + journal du runner **depuis 2 min** (dernière commande à 11:16:39, 2 min 40 avant) ⇒ « poste calme ».
  `launchWithBanner` → `openOrSteer` ne trouve aucun tour **sur ce pod** ⇒ ouvre un **second tour** dans le sujet le
  plus récent du client (`data-platform`) : la consigne d'inventaire est écrite comme message USER au milieu du tour
  du PO, et une seconde boucle Opus tourne en parallèle sur le même poste et le même sujet.
- 11:32:50 — poste coupé, le tour du PO s'arrête (28 étapes), sans avoir rendu sa réponse.

**Cause exacte** : la garde ne voyait que les tours vivants **du pod qui reçoit la demande** (registre en mémoire, 2
replicas sous HPA) et une fenêtre de 2 min sans commande runner, insuffisante pendant qu'Opus réfléchit ; la cible
« sujet le plus récent » est précisément le sujet où le PO travaille. Défaut latent en plus, sur le même chemin :
`launchWithBanner` passe par `openOrSteer` — si le tour avait été sur le même pod, la consigne aurait été **déposée
comme précision** dans le tour du PO **avant** que le service ne rende son refus `host_busy`.

## Objectif
Aucun tour déclenché automatiquement (mise en place du relevé, routine) n'écrit jamais dans un sujet où le PO a un tour
en cours ou en attente, quel que soit le pod ; la mise en place tourne dans un sujet dédié au relevé du client.

## Comportement attendu

### Cas nominal
1. **Sujet dédié « Jarvis — relevé »**, un par client (poste) : créé à la première mise en place, réutilisé ensuite ;
   cible `RUNNER` sur ce poste, à la racine du poste (`project_path = ""`, aucun dossier à créer ; l'inventaire est
   l'affaire de la machine). Marqué en base `workspaces.jarvis_releve_host_id = host_id` (UNIQUE : un seul par poste,
   même sous deux créations concurrentes). Les sondes de relevé posées par l'inventaire vivent dans ce sujet.
2. **Rangé discrètement** : absent de la liste des sujets (`GET /api/atelier/workspaces`), des projets du poste
   (`listByHost` : carte du poste, contrôle de doublon, garde de suppression du poste), de l'univers et de l'activité
   de Jarvis ; visible dans la bande des tours du poste pendant qu'il tourne (on peut l'ouvrir) et dans « Ce que je
   sais » (sondes, par leur sujet). Supprimé avec le poste.
3. **Garde stricte, transverse aux pods** (`HostTurnActivity`) — le poste est occupé si, sur **n'importe quel sujet**
   de ce poste (terminal du poste, terminal Teams et sujet dédié compris) :
   - un tour vit sur ce pod (tous fils) ou **chez un pod pair** (résumés relayés F-191) ;
   - une **demande sans réponse** de moins de 60 min existe en base (`atelier_messages` USER sans ASSISTANT postérieur
     sur le même fil) — signal partagé par tous les pods, qui couvre le cas de l'incident même relais muet ;
   - une exécution de routine ou de chantier autonome est `RUNNING` (moins de 6 h) ;
   - une commande runner hors relevés/veilles a eu lieu dans les 2 dernières minutes.
   ⇒ `409 {code: "host_busy"}`, rien n'est écrit nulle part.
4. **Jamais d'injection** : la mise en place passe par `launchIfIdle` (→ `LiveTurnRegistry.openIfIdle`), jamais par
   `openOrSteer` : un tour vivant ⇒ refus, jamais une précision.
5. `JarvisReleveService.quiet` (relevé horaire, « Synchroniser » phases 2/3) emprunte la même garde (sans interroger
   les pairs, pour rester bon marché) : la demande sans réponse en base suffit à couvrir le cas inter-pods.
6. **Routines** (seul autre déclencheur automatique d'un tour Opus) : avant d'ouvrir un tour, le sujet doit être libre
   sur **tous ses fils**, ici **et** chez les pairs, et sans demande sans réponse (`subjectBusy`) ; sinon la routine
   réessaie à la minute suivante (planifiée) ou rend `routine_busy` (manuelle).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Tour en cours dans un **autre** sujet du même poste (ce pod ou un pair) | `host_busy`, aucun message écrit | 409 |
| Demande du PO sans réponse < 60 min sur le poste (tour sur un pod pair) | `host_busy` | 409 |
| Routine / chantier autonome `RUNNING` sur le poste | `host_busy` | 409 |
| Commande runner hors relevé < 2 min | `host_busy` | 409 |
| Tour déjà vivant dans le sujet dédié (course) | `host_busy`, pas de précision | 409 |
| Client d'un autre compte | refus | 404 |
| Relevé coupé / poste éteint / runner ancien | inchangé (SF-247-08) | 409 |

## Critères d'acceptation
- [ ] Tour vivant dans un autre sujet du même poste ⇒ 409 `host_busy`, aucun message ajouté nulle part.
- [ ] Demande USER sans réponse récente dans un sujet du poste ⇒ 409 (cas inter-pods de l'incident).
- [ ] Première mise en place : sujet « Jarvis — relevé » créé (RUNNER, poste, racine) ; seconde : **le même** réutilisé.
- [ ] Le sujet de travail le plus récent n'est jamais la cible ; aucune écriture dans ce sujet.
- [ ] Le lancement n'utilise jamais `openOrSteer` (aucune précision possible).
- [ ] Le sujet dédié n'apparaît ni dans la liste des sujets ni dans les projets du poste ; supprimé avec le poste.
- [ ] Routine planifiée : sujet occupé chez un pair ou demande sans réponse ⇒ pas de tour.
- [ ] Suites complètes backend et frontend vertes.

## Périmètre

### Hors scope (explicite)
- `terminal_ecrire` (MCP) : envoi **humain** depuis un client MCP, la précision y est voulue (F-84 / SF-84-06).
- Chantiers autonomes : lancés par le PO dans **leur** fil (F-221) ; inchangés.
- « Synchroniser » phases 1/4 : n'ouvrent aucun tour et n'écrivent dans aucun sujet (sans poste).
- Écran : aucun changement (le 202 rend déjà `workspaceId`).
- Déplacer les sondes déjà posées dans d'autres sujets : elles y restent (aucune n'a été posée le 2026-11-05).

## Valeurs initiales
Nom du sujet dédié : `Jarvis — relevé`. Fenêtre « demande sans réponse » : 60 min (p99 d'un tour en prod sur 30 j :
24 min, 1 tour > 60 min sur 1 263). Exécution `RUNNING` prise en compte : 6 h.

## Contraintes de validation
Un sujet dédié par poste (UNIQUE `jarvis_releve_host_id`). Aucun autre champ saisi.

## Technique

### Endpoint(s)
`POST /api/jarvis/state/clients/{hostId}/releve/setup` (comportement modifié). `GET /api/atelier/workspaces` (filtre).

### Tables impactées
`workspaces` (+ `jarvis_releve_host_id uuid UNIQUE NULL`). Lectures : `atelier_messages`, `routine_runs`,
`chantier_autonomy_runs`, `runner_audit`.

### Migration Liquibase
`247-18-workspaces-jarvis-releve.xml` — ajout de colonne nullable + contrainte UNIQUE ; rollback : retrait.

### Composants Angular (si applicable)
Aucun.

## Plan de test

### Tests unitaires
- `HostTurnActivityTest` : tour local autre sujet, résumé distant vivant, demande sans réponse, réponse postérieure,
  autre fil, fenêtre dépassée, run RUNNING, audit < 2 min, isolation.
- `RoutinePlannerTest` : sujet occupé selon la garde ⇒ aucun tour préparé.

### Tests d'intégration
- `JarvisReleveSetupIntegrationTest` : tour vivant dans un autre sujet du poste ⇒ 409, aucun message ; demande sans
  réponse ⇒ 409 ; sujet dédié créé une fois puis réutilisé ; le sujet récent n'a reçu aucune écriture ;
  `launchIfIdle` seul appelé (jamais `launchWithBanner`).
- Sujet dédié exclu de la liste des sujets et de `listByHost`.

### Isolation workspace
Toutes les lectures sous `user_id` (+ `host_id`) ; un tour d'un autre compte sur un poste homonyme n'occupe pas ce
poste ; le sujet dédié d'un compte n'est jamais trouvé par un autre.

## Dépendances

### Subfeatures bloquantes
SF-247-08 (Done).

### Questions ouvertes impactées
Aucune.

## Impact — préoccupations transversales
- **Contexte tenant** : `HostTurnActivity` (requêtes `user_id` + `host_id`), `WorkspaceService.openJarvisReleve`
  (`findFirstByUserIdAndJarvisReleveHostId`), `JarvisReleveSetupService`, `RoutinePlanner`.
- Auth / Plans / Navigation : non concernés.

## Notes et décisions
- **Sujet dédié plutôt que fil Jarvis** : le fil Jarvis est `SANDBOX`, sans poste ; il ne peut ni lancer de commande ni
  porter de sonde. Plutôt que le terminal du poste : c'est un terminal de travail du PO (VPN, terraform, clones).
- **Racine du poste** : le runner refuse un dossier de projet inexistant ; la racine existe toujours.
- **Signal en base** plutôt que seul relais entre pods : le relais est « au mieux » (pair muet ⇒ « aucun tour ») ; la
  demande sans réponse est écrite par le pod qui exécute, au premier instant du tour, et lue par tous.
