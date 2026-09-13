# Mini-spec — F-106 / SF-106-02 — La Vigie, l'écran

## Identifiant

`F-106 / SF-106-02`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage (cadrage : `CADRAGE-F-106-la-vigie.md` §3, §4, §6)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-106-02-la-vigie-ecran`

---

## Objectif

Ouvrir la **Vigie** (`/vigie`, `/vigie/:hostRef`) sur la forme maître–détail de la Forge refondue —
bandeau de flotte, colonne des clients, client ouvert et ses quatre onglets — et y **activer un
client de la Forge** ou **connecter un client** sans passer par la Forge.

---

## Comportement attendu

### Cas nominal

1. **Barre du haut** : **Forge** et **Vigie** côte à côte (icône `radar`) ; l'entrée Vigie est
   active sur `/vigie` et `/vigie/...`.
2. **Bandeau de flotte de la Vigie** (même composant visuel que la Forge, `_forge-layout.scss`) :
   titre « Vigie », « ● *N* clients en ligne sur *M* » (missions non clôturées), « *k* relance(s)
   due(s) » (ambre §12, seulement si > 0), « *k* sujet(s) bloqué(s) » (seulement si > 0), la
   synchro la plus récente en mots (« synchro d'hier soir complète », « synchro en cours »,
   « aucune synchro encore »), puis « Rafraîchir » et « Ajouter un client » (action principale).
   Les compteurs du Radar sont lus **une fois par page** (et sur « Rafraîchir »), jamais au sondage ;
   un Radar illisible compte zéro, sans erreur.
3. **Colonne des clients** : le composant **`app-forge-rail` réemployé** (aucune copie), avec les
   mots de la Vigie — « Clients », « Filtrer les clients », « Aucun client ne correspond. »,
   « Ajouter un client » —, sans compte de projets ; la pastille d'attente dit « *k* relance(s) »
   et range le client dans *À regarder*. Groupes : *À regarder › En ligne › Hors ligne › Missions
   clôturées*. Le filtre ne cherche que dans les noms de clients.
4. **Sélection dans l'URL**, comme la Forge : `/vigie/<id>` ; `/vigie` ouvre sans changer d'URL le
   premier client *À regarder*, sinon en ligne, sinon non clôturé, sinon le premier. Une seule route
   à `matcher` : changer de client ne recrée pas l'écran. Sondage de la vue toutes les 15 s,
   suspendu onglet masqué ; présence datée par `HostPresenceService` (F-97).
5. **Client ouvert** : pastille `lg`, nom, état de mission (`app-mission-badge`, lecture seule :
   la mission se change dans la Forge et vaut pour les deux espaces), présence datée ; menu « ··· » :
   « Activer dans la Forge » (si le client n'y est pas), « Retirer de la Vigie… ».
6. **Onglets** `Radar · Conversations · Réunions · Personnes`, filet orange (§16), `?onglet=`
   (défaut `radar`, un clic pousse une entrée d'historique, changer de client garde l'onglet) :
   - **Radar** : **état vide explicite** tant que l'écran du Radar (F-102) n'est pas livré — ce que
     le Radar suivra, et les compteurs déjà connus du client (relances dues, sujets bloqués,
     dernière synchro) ;
   - **Conversations** et **Réunions** : un encart qui dit où ils se trouvent aujourd'hui (le
     terminal Teams, ouvert depuis la Forge) — remplacé par SF-106-03 ;
   - **Personnes** : l'annuaire du client (`GET /radar/hosts/{id}/people`) — nom, fonction,
     dernier échange daté, nombre de sujets ; lu à l'ouverture de l'onglet, une fois par client ;
     vide : « L'annuaire se remplit à chaque synchro du Radar. ».
7. **Ajouter un client** (dialogue) : la liste des clients **qui ne sont pas dans la Vigie** (`GET
   /runner-hosts/spaces`), missions en cours d'abord, chacun avec « Activer » — aucun appairage ;
   dessous, « Connecter un nouveau client » ouvre le parcours d'appairage existant (F-72) en mode
   poste avec `space: 'VIGIE'` : le client naît dans la Vigie seule. Après activation : le client
   est ouvert (`/vigie/<id>`), la vue relue.
8. **Retirer de la Vigie** (dialogue de confirmation) : « Le client reste dans la Forge, avec ses
   projets. Rien n'est supprimé. » + case **« Effacer aussi son Radar »** (irréversible, décochée).
   Retrait (`DELETE …/spaces/VIGIE`) puis, si cochée, purge (`POST /radar/hosts/{id}/purge`,
   `VIGIE_REMOVED`). Un client qui n'est que dans la Vigie : le dialogue dit de l'activer d'abord
   dans la Forge (le dernier espace ne se retire pas) et ne propose pas le geste.
9. **Aucun client dans la Vigie** : encart « Aucun client dans la Vigie » avec « Activer un client
   de la Forge » et « Connecter un client ».

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Compte sans droit Teams (lecture `/teams/access` fausse ou en échec) | encart « La Vigie n'est pas ouverte sur ce compte » avec lien vers la facturation (page de présentation : SF-106-05), aucune lecture de la vue | — |
| Sans accès Forge | encart d'accès existant (souscrire / code d'accès), pas de sondage | 403 |
| Gateway injoignable au premier chargement | encart « L'état des clients n'a pas pu être lu » + Réessayer | — |
| `/vigie/<id>` inconnu ou hors Vigie | sélection par défaut, aucune erreur | — |
| `?onglet=` inconnu | onglet Radar | — |
| Activation refusée (poste supprimé entre-temps) | message d'erreur dans le dialogue, liste relue | 404 |
| Retrait refusé (dernier espace) | message de la gateway, rien ne change | 409 |
| Retrait réussi, purge en échec | le retrait tient ; message « Le client est retiré, mais son Radar n'a pas pu être effacé. » | 4xx/5xx |
| Annuaire illisible | « L'annuaire n'a pas pu être lu. » dans l'onglet, sans bloquer le reste | — |

---

## Critères d'acceptation

- [ ] La barre du haut porte Forge et Vigie ; `/vigie` affiche le bandeau, la colonne (le même
      composant que la Forge) et un seul client ouvert.
- [ ] Le bandeau dit clients en ligne, relances dues, sujets bloqués et la dernière synchro, lus une
      fois par page (jamais au sondage).
- [ ] `/vigie/<id>?onglet=personnes` ouvre ce client sur l'annuaire ; un clic d'onglet met l'URL à
      jour ; onglet inconnu ⇒ Radar.
- [ ] L'onglet Radar montre un état vide explicite, jamais un écran blanc.
- [ ] « Ajouter un client » active un client de la Forge sans appairage, puis l'ouvre ; « Connecter un
      nouveau client » ouvre l'appairage avec `space: 'VIGIE'`.
- [ ] « Retirer de la Vigie » retire, et n'efface le Radar que si la case est cochée.
- [ ] La Forge est inchangée (styles extraits sans perte, tests existants verts).
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Le déménagement du terminal Teams, de la liaison et des réunions (SF-106-03).
- Les passerelles sujet ↔ projet et la conservation du client en changeant d'espace (SF-106-04).
- La page de présentation d'un espace non souscrit et l'essai (SF-106-05).
- L'écran du Radar lui-même : résumé, colonnes, gestes (F-102) ; la page sujet (F-103).
- Changer l'état de mission depuis la Vigie (il reste dans la Forge, commun aux deux espaces).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| onglet | `radar` | `?onglet=` inconnu ⇒ `radar` |
| client ouvert | `hostRef` | à défaut : À regarder › En ligne › non clôturé › premier |
| case « Effacer aussi son Radar » | décochée | un effacement ne se présume pas |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `hostRef` (URL) | Non | identifiant de poste | inconnu ⇒ défaut |
| `onglet` (URL) | Non | `radar`, `conversations`, `reunions`, `personnes` | minuscules |

---

## Technique

### Endpoint(s)

Aucun nouveau. Lus : `GET /api/teams/access`, `GET /api/runner-hosts/overview?space=VIGIE`,
`GET /api/runner-hosts/spaces`, `PUT|DELETE /api/runner-hosts/{id}/spaces/{space}`,
`POST /api/runner-hosts` (`space`), `GET /api/radar/hosts/{id}/commitments?followUpDue=true`,
`…/subjects?state=BLOCKED`, `…/syncs`, `…/people`, `POST …/purge`.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `vigie/vigie.component.*` (nouveau) — l'écran.
- `vigie/vigie-fleet.ts` (nouveau) — onglets, bandeau, synchro en mots, clients importables.
- `vigie/add-client-dialog/*`, `vigie/remove-client-dialog/*` (nouveaux).
- `core/services/vigie.service.ts`, `core/models/vigie.models.ts` (nouveaux).
- `postes/_forge-layout.scss` (nouveau, **extrait** de `postes.component.scss`) — la forme partagée.
- `postes/forge-rail` — entrées de libellés (défauts inchangés) ; `postes/forge-fleet.ts` —
  `awaitingOf` facultatif (défaut inchangé).
- `AtelierService.runnerHostsOverview(space?)`, `createRunnerHost(name, space?)` ;
  `RunnerPairingDialogData.space`.
- `app.routes.ts` (`vigieMatcher`), `ShellComponent` (entrée Vigie).

### Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés et vérifiés : `app.routes.ts` (nouveau
  matcher `vigie`, n'intercepte ni `forge`, ni `forge/voir`, ni `atelier/:id`) ; `ShellComponent`
  (`forgeActive` inchangé, `vigieActive` nouveau) ; `ForgeRailComponent` (sélection émise, la
  navigation reste à l'écran hôte) ; dialogue d'appairage (retour : l'écran relit la vue). Tests de
  route : `/vigie`, `/vigie/<id>`, `/forge/...` inchangés.
- **Plans / limites : oui (lecture).** `GET /teams/access` décide de l'écran ; aucune garde
  nouvelle côté gateway (SF-106-01).
- Auth / Principal : non. Contexte tenant : non (JWT, possession vérifiée par la gateway).

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `vigie-fleet.spec.ts` — onglet effectif, somme du bandeau, synchro en mots (ce soir, hier
      soir, en cours, incomplète, aucune), clients importables (filtre et ordre), libellé relance.
- [ ] `vigie.service.spec.ts` — URLs et verbes des espaces, purge `VIGIE_REMOVED`, compteurs
      (filtre `followUpDue`, erreurs silencieuses → 0).
- [ ] `vigie.component.spec.ts` — sans droit : encart, aucune lecture de la vue ; bandeau ; colonne
      avec les mots de la Vigie ; `/vigie/<id>` ; `?onglet=personnes` lit l'annuaire une fois ;
      Radar vide explicite ; aucun client ⇒ encart ; 403 ⇒ encart d'accès ; activation ⇒
      navigation ; retrait + purge cochée/décochée ; sondage sans relecture des compteurs.
- [ ] `add-client-dialog.component.spec.ts`, `remove-client-dialog.component.spec.ts`.
- [ ] `forge-rail.component.spec.ts` — libellés personnalisés, compte masqué, pastille « relance ».
- [ ] `forge-fleet.spec.ts` — `awaitingOf` personnalisé.
- [ ] `app.routes.spec.ts` — `vigieMatcher` ; `shell.component.spec.ts` — entrée Vigie active.
- [ ] `atelier.service.spec.ts` — `?space=VIGIE`, corps `{name, space}` ; dialogue d'appairage en
      mode poste avec `space`.

### Isolation workspace

- [x] Non applicable côté écran — toutes les lectures partent du JWT ; l'isolation est testée par
      SF-106-01.

---

## Dépendances

### Subfeatures bloquantes

- SF-106-01 — `done` (API des espaces).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **La colonne n'est pas copiée** : `app-forge-rail` gagne des entrées de libellés dont les défauts
  sont ceux de la Forge ; la forme (bandeau, colonne, en-tête, onglets) vit dans
  `_forge-layout.scss`, employé par les deux écrans.
- **Les relances dues font « À regarder »** dans la Vigie, comme les autorisations dans la Forge :
  c'est ce qui y attend l'utilisateur.
- **Les compteurs sont lus par client, une fois par page** : trois lectures par client existantes
  (F-99), plutôt qu'un endpoint agrégé nouveau que l'écran du Radar (F-102) pourra remplacer.
