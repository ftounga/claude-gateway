# Mini-spec — F-106 / SF-106-07 — Un terminal Teams perd ses outils quand son client quitte la Vigie

## Identifiant

`F-106 / SF-106-07`

## Feature parente

`F-106` — La Vigie, l'espace du pilotage

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-106-07-terminal-teams-vigie`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Le terminal Teams n'a ses outils `teams_*` que si son client est **activé dans la Vigie**, vérifié
à **chaque tour** ; sinon aucun outil `teams_*` n'est donné et la consigne système le dit
(« client retiré de la Vigie », reprise de la doctrine SF-89-04).

---

## Comportement attendu

### Cas nominal

Constat (F-106, risque résiduel noté à la livraison de SF-106-05) : un terminal Teams déjà ouvert
garde ses outils `teams_*` si son client est retiré de la Vigie ; seule l'**ouverture** du terminal
est gardée (SF-106-03 : 409 `host_not_in_space`), pas les tours suivants.

La garde du volet Teams vit dans `TeamsToolCatalog` (F-89 / SF-89-01) : elle donne les outils
`teams_*` quand le workspace est un terminal Teams **et** que le compte a le droit Teams. Cette
subfeature y ajoute une **troisième condition cumulative**, sur le modèle de `RadarToolCatalog`
(F-104 / SF-104-01) qui gate déjà ses outils sur `spaces.isActive(userId, hostId, VIGIE)` :

- `toolsFor(userId, workspace)` ne rend le catalogue que si le poste est **activé dans la Vigie**
  (`HostSpaceService.isActive(userId, hostId, ClientSpace.VIGIE)`), en plus du terminal Teams et du
  droit Teams. Sinon : liste vide, en silence — l'agent ne refuse pas, il n'a pas la capacité.
- Comme `buildTools` est rappelé à **chaque tour**, retirer le client de la Vigie prive le tour
  **suivant** de tout outil `teams_*`.
- Nouvelle méthode `isRemovedFromVigie(userId, workspace)` : vrai quand le terminal Teams a le droit
  Teams mais que son poste **n'est pas** activé dans la Vigie. `AtelierChatService.buildSystemPrompt`
  ajoute alors le paragraphe `REMOVED_FROM_VIGIE_NOTICE` (« client retiré de la Vigie »), qui reprend
  la doctrine de `CLOSED_NOTICE` (SF-89-04) : le dire en une phrase, ne pas fouiller la machine à la
  place, indiquer comment le réactiver (le rouvrir/activer dans la Vigie).

### Précédence des deux consignes

- Pas de droit Teams → `isClosedFor` (SF-89-04, inchangé) → `CLOSED_NOTICE` (« Volet Teams non actif »).
- Droit Teams **mais** poste hors Vigie → `isRemovedFromVigie` → `REMOVED_FROM_VIGIE_NOTICE`.

Les deux sont mutuellement exclusives (l'une exige `!hasAccess`, l'autre `hasAccess`).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Poste introuvable ou d'autrui (`HostSpaceService.isActive` lève) | Fermé : aucun outil `teams_*`, sans rien dire de plus (comme `RadarToolCatalog`) |
| `workspace.getHostId()` absent sur un terminal Teams | Traité comme non activé dans la Vigie : aucun outil `teams_*` |
| Catalogue `TeamsToolCatalog.none()` (appelants historiques) | Aucun outil, aucune consultation d'espace |
| Terminal de projet (non Teams) | Inchangé : aucun outil `teams_*`, l'espace n'est même pas consulté |

---

## Critères d'acceptation

- [ ] Terminal Teams + droit Teams + poste activé dans la Vigie → le catalogue `teams_*` est donné (non-régression F-89/F-108).
- [ ] Terminal Teams + droit Teams + poste **retiré** de la Vigie → `toolsFor` rend **la liste vide** (aucun `teams_*`).
- [ ] Terminal Teams + droit Teams + poste retiré de la Vigie → `buildSystemPrompt` contient `REMOVED_FROM_VIGIE_NOTICE` (« client retiré de la Vigie »), et **pas** `CLOSED_NOTICE`.
- [ ] Terminal Teams **sans** droit Teams → comportement SF-89-04 inchangé (`CLOSED_NOTICE`, aucun `teams_*`).
- [ ] `isRemovedFromVigie` est faux sur un terminal de projet et sur `TeamsToolCatalog.none()`.
- [ ] Isolation : l'espace consulté est celui du couple (userId du tour, hostId du terminal) — jamais un paramètre client ; `HostSpaceService.isActive` filtre `user_id` + vérifie la possession.
- [ ] ADMIN : l'administrateur garde ses outils quand son poste est dans la Vigie (l'option est court-circuitée par `AdministratorEntitlement`, mais l'activation Vigie reste requise, comme pour le Radar).
- [ ] Le critère du cadrage : retirer le client de la Vigie → le tour suivant n'a plus d'outil Teams et le dit.

---

## Périmètre

### Hors scope (explicite)

- Garde à l'**exécution** d'un outil `teams_*` (par analogie au guard Radar `isOpenFor` de
  `handleToolCall`) : inutile ici, car les outils sont **reconstruits à chaque tour** et donc jamais
  offerts au tour suivant un retrait ; la garde de catalogue suffit au critère « à chaque tour ».
- L'écran de retrait de la Vigie (déjà livré, SF-106-02 « Retirer de la Vigie ») et le refus 409
  d'ouverture d'un terminal Teams hors Vigie (déjà livré, SF-106-03).
- Le comportement des outils `radar_*` (déjà gardés par la Vigie depuis SF-104-01) et des autres
  volets.
- Toute migration ou changement de schéma.

---

## Technique

### Endpoint(s)

Aucun. Changement interne à la construction de la panoplie et de la consigne système de l'Atelier.

### Tables impactées

Aucune (lecture de `host_spaces` via `HostSpaceService`, déjà en place depuis SF-106-01).

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

Aucun. Le retrait de la Vigie et son écran existent (SF-106-02) ; la consigne système est
côté serveur, invisible côté écran.

### Fichiers touchés

- `backend/.../teams/TeamsToolCatalog.java` — dépendance `HostSpaceService`, garde Vigie dans
  `toolsFor`, méthode `isRemovedFromVigie`, constante `REMOVED_FROM_VIGIE_NOTICE`, `none()` mis à jour.
- `backend/.../atelier/AtelierChatService.java` — `buildSystemPrompt` ajoute la nouvelle consigne
  (branche `else if` après `isClosedFor`).
- Tests : `TeamsToolCatalogTest`, `TeamsReadingCatalogTest`, `AtelierChatServiceTeamsToolsTest`,
  `AtelierChatServiceTeamsBlockTest` (adaptés à la nouvelle condition Vigie).

---

## Préoccupations transversales (analyse d'impact)

- **Contexte tenant** : la garde lit l'espace du couple (userId du tour, hostId du terminal). Aucun
  nouveau moyen de résoudre le tenant ; `HostSpaceService.isActive` vérifie la possession et filtre
  `user_id`. Composants qui résolvent l'espace du terminal Teams et restent cohérents :
  `TeamsToolCatalog` (modifié ici), `RadarToolCatalog` (déjà gate Vigie), `AtelierChatService`
  (branchement des deux catalogues et des consignes). Aucun autre composant ne donne d'outil `teams_*`.
- **Plans / limites** : aucune nouvelle limite ni nouveau plan ; la garde Vigie est une activation
  d'espace (`host_spaces`), pas un entitlement. Le droit Teams (option) reste `TeamsAccessService`.
- **Auth / Principal** : inchangé. ADMIN passe par `AdministratorEntitlement` dans `TeamsAccessService`
  pour l'option ; l'activation Vigie reste requise (parité avec le Radar).
- **Navigation / routing** : aucune route touchée.

---

## Plan de test

### Tests unitaires (`TeamsToolCatalogTest`)

- [ ] terminal Teams + droit + poste dans la Vigie → catalogue donné (contient `STATUS`).
- [ ] terminal Teams + droit + poste **hors** Vigie → `toolsFor` vide.
- [ ] terminal Teams + droit + `HostSpaceService.isActive` lève (poste d'autrui/introuvable) → vide.
- [ ] `isRemovedFromVigie` : vrai (droit + hors Vigie), faux (droit + dans Vigie), faux (terminal de projet), faux (`none()`), faux (sans droit).
- [ ] `REMOVED_FROM_VIGIE_NOTICE` porte « retiré de la Vigie », « Ne cherche pas la réponse sur la machine », et le remède (réactiver dans la Vigie).
- [ ] non-régression : sans droit → vide + `isClosedFor` vrai ; terminal de projet → vide.

### Tests d'intégration (au sens service, `AtelierChatServiceTeamsToolsTest` / `AtelierChatServiceTeamsBlockTest`)

- [ ] `buildTools` : terminal Teams, droit, poste hors Vigie → aucun `teams_*` dans la panoplie ; la panoplie est celle d'avant F-89.
- [ ] `buildSystemPrompt` : terminal Teams, droit, poste hors Vigie → contient `REMOVED_FROM_VIGIE_NOTICE`, pas `CLOSED_NOTICE`.
- [ ] `buildSystemPrompt` : terminal Teams, droit, poste dans Vigie → ni l'une ni l'autre consigne.
- [ ] non-régression SF-89-04 : sans droit → `CLOSED_NOTICE`.

### Isolation utilisateur

- [x] Applicable — l'espace lu est celui du couple (userId du tour, hostId) ; un autre utilisateur / un poste d'autrui ne donne aucun outil. Vérifié par le cas « `isActive` lève » et par le fait que `userId` vient du tour.

---

## Dépendances

### Subfeatures bloquantes

- `SF-106-01` (host_spaces, `HostSpaceService`) — done.
- `SF-104-01` (RadarToolCatalog, modèle de garde Vigie) — done.
- `SF-89-01` / `SF-89-04` (TeamsToolCatalog, CLOSED_NOTICE) — done.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non touché).

---

## Notes et décisions

- **Modèle suivi** : `RadarToolCatalog.isOpenFor` gate déjà les outils Radar sur la Vigie. On aligne
  `TeamsToolCatalog` sur exactement la même garde d'espace, injectée par `HostSpaceService`, avec le
  même try/catch défensif (poste introuvable/d'autrui ⇒ fermé sans bruit).
- **ADMIN** : l'activation Vigie (`host_spaces`) est une décision par-poste de l'utilisateur, pas un
  entitlement ; l'administrateur, qui a l'option Teams par `AdministratorEntitlement`, reste soumis à
  l'activation du poste dans la Vigie, comme pour le Radar. Cohérent avec « le rôle ADMIN a tous les
  droits » (les droits/options), sans réintroduire un outil sur un client explicitement retiré.
- **Pas de garde à l'exécution** : la panoplie est reconstruite à chaque tour ; c'est la garde de
  catalogue qui réalise « à chaque tour ». Ajouter un guard d'exécution serait redondant ici.
