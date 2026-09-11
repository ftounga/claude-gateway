# Mini-spec — F-68 / SF-68-01 — La Forge s'ouvre sur les missions

---

## Identifiant

`F-68 / SF-68-01`

## Feature parente

`F-68` — La Forge s'ouvre sur les missions (`docs/PRODUCT_SPEC.md`)

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-68-01-forge-accueil-missions`

---

## Objectif

Faire de la vue des postes la **page d'accueil de la Forge** — l'onglet « Postes » disparaît, aucun
lien existant ne se brise — et poser un **fil d'Ariane** « Forge › CAGIP › mon-projet », chaque
niveau cliquable, pour qu'on sache toujours **où l'on est et chez qui**.

---

## Comportement attendu

### Cas nominal

1. **Un seul onglet.** La barre de navigation porte **Forge** et plus **Postes**. L'entrée
   **Forge** mène à `/forge`, qui rend **exactement** l'écran des postes livré par F-49 /
   SF-49-02 / SF-60-02 : mêmes cartes, même identité client, même état de mission, mêmes projets,
   même repli des missions clôturées. Aucune donnée nouvelle n'est lue.
2. **L'onglet reste allumé pendant qu'on travaille.** L'entrée Forge est marquée active sur
   `/forge` **et** sur `/atelier…` : entrer dans un projet ne fait pas s'éteindre la section d'où
   l'on vient.
3. **Aucun lien ne se brise.** `/postes` **redirige** vers `/forge` (`pathMatch: 'full'`) : un
   onglet resté ouvert ou un lien collé s'ouvre sur la même page. `/atelier`, `/atelier/:id` et
   `/atelier/:id/fichiers` sont **inchangés** (décision F-58).
4. **Le fil d'Ariane dit où l'on est.** Un composant partagé `app-forge-breadcrumb` rend une suite
   de niveaux séparés par « › », toujours amorcée par **Forge** → `/forge` :
   - sur l'accueil de la Forge : « **Forge** » seul, marqué page courante ;
   - sur l'écran des projets : « Forge › **Projets** » ;
   - dans le terminal d'un projet : « Forge › **CAGIP** › **mon-projet** ».
5. **Le fil d'Ariane dit chez qui.** Le niveau du poste **réemploie** `app-host-badge`
   (SF-49-03) : initiales sur la couleur du client, nom écrit à côté — la couleur ne porte jamais
   seule l'information. L'état de mission (F-60) reste rendu par `app-mission-badge`, à côté, et
   seulement quand il n'est pas « En cours » — règle inchangée hors de l'accueil de la Forge.
6. **Chaque niveau est cliquable** (décision PO). Le niveau du poste mène à `/forge` avec le
   fragment `#poste-<id>` ; l'accueil de la Forge **amène cette carte dans le champ de vision** au
   chargement. Le dernier niveau reste un lien vers la page courante, marqué `aria-current="page"`.
7. **Aucun quatrième registre de couleur.** Le fil d'Ariane n'utilise que `--cg-text-secondary`,
   `--cg-text-primary` et `--cg-accent` (survol) — plus, sur le seul niveau du poste, la pastille
   d'identité **existante**. Aucun ton n'est inventé, aucun aplat ajouté.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `/postes` demandé (lien ancien, onglet resté ouvert) | Redirection vers `/forge`, même écran | — |
| Accès à la Forge sans le droit (403 `atelier_forbidden`) | L'écran garde **mot pour mot** son appel à souscrire (F-49 / F-58). Le fil d'Ariane reste affiché : on sait où l'on est même quand la vue est refusée | 403 |
| Gateway muette sur la vue d'ensemble | Message d'échec **inchangé** (F-49), fil d'Ariane toujours présent | 5xx |
| Projet rattaché à **aucun** poste | Le niveau du poste est **omis** — « Forge › mon-projet ». Jamais « aucun poste », qui se lirait comme un défaut | — |
| Poste connu par son nom mais pas par son identifiant | Le niveau du poste reste cliquable, **sans fragment** : il mène à l'accueil de la Forge | — |
| Fragment `#poste-<id>` pointant sur une carte absente (poste supprimé, mission clôturée repliée) | Aucune erreur, aucun saut : la page reste en haut | — |
| Utilisateur non authentifié sur `/forge` | `authGuard` → `/login`, comme toute route de la coquille | 302 (client) |

---

## Critères d'acceptation

- [ ] La barre de navigation n'a **plus** d'entrée « Postes », et l'entrée « Forge » pointe sur `/forge`.
- [ ] `/forge` rend `PostesComponent` sous la coquille authentifiée (`authGuard`).
- [ ] `/postes` redirige vers `/forge` avec `pathMatch: 'full'`.
- [ ] `/atelier`, `/atelier/:id` et `/atelier/:id/fichiers` sont **présentes et inchangées** (non-régression F-58).
- [ ] L'entrée « Forge » est marquée active sur `/forge` **et** sur `/atelier/:id`.
- [ ] `app-forge-breadcrumb` rend toujours « Forge » en premier, lié à `/forge`.
- [ ] Le dernier niveau porte `aria-current="page"` et reste un lien.
- [ ] Dans le terminal, le fil rend le poste avec sa pastille d'identité (initiales + nom + couleur dérivée du nom) et son état de mission quand il n'est pas « En cours ».
- [ ] Un projet sans poste n'affiche **aucun** niveau intermédiaire.
- [ ] Le niveau du poste porte `fragment="poste-<id>"` quand l'identifiant est connu, aucun fragment sinon.
- [ ] L'accueil de la Forge amène la carte visée par le fragment dans le champ de vision, sans erreur quand elle n'existe pas.
- [ ] Les cartes de `/forge` portent `id="poste-<id>"`, et **rien d'autre** n'a changé dans le gabarit des postes (non-régression F-49 / SF-49-03 / SF-60-02).
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md` n'est introduite ; aucun quatrième registre.
- [ ] Isolation `user_id` : **aucun appel nouveau**, aucun identifiant transmis par le client.

---

## Périmètre

### Hors scope (explicite)

- Changer ce que la vue des postes **affiche** (F-49, SF-49-03, SF-60-02) : cartes, tri, données.
- Renommer la route `/atelier` (décision F-58).
- Modifier l'écran d'accueil **de l'application** (`/`, redirection après connexion).
- Supprimer un projet ou un poste (F-69), plafond de terminaux vivants (F-70), pastille
  « connecté » unifiée (F-70), poste virtuel « Hébergé » (F-71).
- Tout changement backend : aucun endpoint, aucune table, aucune migration.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état persisté.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `crumbs[].label` | Oui | — | texte non vide ; un niveau sans libellé n'est pas rendu | Non | `trim()` |
| `crumbs[].link` | Oui | — | commandes de routeur Angular (`['/forge']`, `['/atelier', id]`) | Non | — |
| `crumbs[].fragment` | Non | — | `poste-<uuid>` ou absent | Non | — |
| `crumbs[].hostName` | Non | — | nom du poste ; déclenche la pastille d'identité | Non | — |
| `crumbs[].missionStatus` | Non | — | `ACTIVE` / `PENDING` / `CLOSED` ; absent ⇒ rien d'affiché | Non | — |

Notes :
- Aucune de ces valeurs n'est saisie par l'utilisateur ni transmise à la gateway : elles sont
  construites à l'écran à partir de lectures déjà faites.
- La couleur du poste **n'est jamais transmise** : elle reste dérivée du nom (`host-identity.ts`).

---

## Technique

### Endpoint(s)

Aucun — ni créé, ni modifié, ni appelé de plus.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma.

### Composants Angular

| Composant | Ce qu'il fait |
|---|---|
| `shared/forge-breadcrumb/forge-breadcrumb.component` | **Nouveau.** Rend le fil d'Ariane : « Forge » puis les niveaux reçus, séparés par « › », dernier niveau `aria-current="page"`. Réemploie `app-host-badge` et `app-mission-badge`. |
| `app.routes.ts` | Ajoute `forge` ; `postes` devient une redirection ; `atelier*` inchangées. |
| `layout/shell/shell.component` | Retire l'entrée « Postes » ; « Forge » → `/forge` ; état actif calculé pour couvrir `/atelier…`. |
| `postes/postes.component` | Porte le fil d'Ariane, pose `id="poste-<id>"` sur les cartes, amène au champ de vision la carte visée par le fragment. **Rien d'autre ne change.** |
| `atelier/atelier.component` | Porte le fil d'Ariane sur l'écran des projets ; expose l'identifiant du poste actif au terminal. |
| `atelier/terminal/atelier-terminal.component` | Sa barre de titre devient le fil d'Ariane (Forge › poste › projet). Pastille de poste et pastille de mission **conservées**. |
| `billing/billing.component.html` | Le lien « Gérer mes postes » vise `/forge` (il continuerait de marcher via la redirection ; on le met à jour pour ne pas laisser de lien périmé). |

---

## Plan de test

### Tests unitaires

- [ ] `ForgeBreadcrumbComponent` — rend toujours « Forge » lié à `/forge`, même sans niveau.
- [ ] `ForgeBreadcrumbComponent` — rend les niveaux dans l'ordre, séparés, dernier `aria-current="page"`.
- [ ] `ForgeBreadcrumbComponent` — un niveau porteur d'un nom de poste rend la pastille d'identité (initiales + nom).
- [ ] `ForgeBreadcrumbComponent` — l'état de mission n'apparaît que quand il est fourni.
- [ ] `ForgeBreadcrumbComponent` — un niveau sans libellé n'est pas rendu.
- [ ] `ShellComponent` — l'entrée « Postes » a disparu, « Forge » pointe sur `/forge`.
- [ ] `ShellComponent` — l'entrée « Forge » est active sur `/forge` comme sur `/atelier/xxx`.
- [ ] `app.routes` — `forge` existe sous la route gardée ; `postes` redirige en `pathMatch: 'full'` ; `atelier`, `atelier/:id`, `atelier/:id/fichiers` intactes.
- [ ] `PostesComponent` — le fil d'Ariane est rendu, les cartes portent `id="poste-<id>"`.
- [ ] `PostesComponent` — un fragment connu amène la carte au champ de vision ; un fragment inconnu ne lève rien.
- [ ] `AtelierTerminalComponent` — le fil rend « Forge › poste › projet », et « Forge › projet » sans poste.
- [ ] `AtelierTerminalComponent` — non-régression F-49 / F-60 : pastille d'identité et pastille de mission toujours présentes selon les mêmes règles.

### Tests d'intégration

Sans objet côté API — aucune route serveur n'est touchée. La couverture d'intégration de cette
subfeature est la table de routes (`app.routes.spec.ts`), qui est le contrat de navigation.

### Isolation workspace / `user_id`

- [x] Non applicable — **aucun appel réseau n'est ajouté ni modifié**. Les lectures existantes
  (`GET /api/runner-hosts/overview`, workspaces) ne transportent aucun identifiant : la gateway
  part du JWT et ne rend que les données du porteur. Le fragment `#poste-<id>` est un **ancrage de
  page**, jamais un critère de requête : il ne peut pas servir à désigner le poste d'un autre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-49-02` — l'écran des postes — statut : done
- `SF-49-03` — l'appartenance se voit — statut : done
- `SF-58-01` — le mot vu par l'utilisateur (route `/atelier` conservée) — statut : done
- `SF-60-02` — l'état de mission se voit — statut : done

### Questions ouvertes impactées

- [ ] Aucune. `docs/OPEN_QUESTIONS.md` n'est pas touché.

---

## Notes et décisions

- **D1 — `/forge` canonique, `/postes` redirige.** Réversible en une ligne. L'alternative (garder
  `/postes`) laissait dans la barre d'adresse le mot dont F-68 supprime précisément l'onglet.
- **D2 — Le dernier niveau reste un lien.** Le PO a demandé « chaque niveau cliquable ». Un lien
  vers la page courante est inoffensif et `aria-current="page"` dit aux lecteurs d'écran que c'est
  la page où l'on est.
- **D3 — Le niveau du poste mène à `/forge#poste-<id>`, pas à un écran par client.** Il n'existe
  pas d'écran par client, et en créer un serait ouvrir un périmètre que le PO n'a pas demandé.
  L'ancrage donne la réponse honnête : « chez qui » ramène à la carte du client, dans la vue qui
  les porte toutes.
- **D4 — La barre du terminal *devient* le fil d'Ariane.** Elle affichait déjà le poste puis le
  nom du projet séparés par « / ». En faire un fil ajoute l'ancêtre manquant et rend les niveaux
  cliquables, sans rien retirer ni dupliquer. Les sélecteurs de test de la barre suivent
  (`.terminal-host` → `.forge-crumb__host`), les garanties testées ne changent pas.
- **D5 — L'écran des projets (`/atelier`) n'est pas supprimé.** F-68 déplace la porte d'entrée, il
  ne retire pas l'écran où l'on crée un projet. Le supprimer serait irréversible et hors demande.
