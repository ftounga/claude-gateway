# Mini-spec — F-98 / SF-98-02 — Le poste ouvert et ses onglets

## Identifiant

`F-98 / SF-98-02`

## Feature parente

`F-98` — La Forge, refondue (cadrage : `CADRAGE-F-98-la-forge-refondue.md` §3 D3 et « Les règles de
détail » ; maquette validée `maquette-forge-refondue.html`)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-98-02-poste-ouvert-onglets`

---

## Objectif

Remplacer la carte empilée du poste ouvert par **un en-tête** (identité, statut, racine, système,
actions) et **quatre onglets** — Projets · Carte · Gouvernance · Activité — qui portent chacun un
résumé sans être ouverts, l'onglet actif étant retenu dans l'URL (`?onglet=`).

---

## Comportement attendu

### Cas nominal

1. **En-tête du poste** (une ligne, qui passe à la ligne si l'écran est étroit) :
   - pastille d'initiales 48 px (`app-host-badge` `lg`), **nom** en titre ;
   - sous le nom : pastille d'état de mission (menu inchangé, F-60), pastille de présence écrite
     (« En ligne » `badge--success` / « Hors ligne » `badge--neutral`), racine en mono, « système ·
     interpréteur », « Runner *version* », « vu il y a … » (F-97), « Administrateur » s'il y a lieu,
     pastille de vie (F-70) ; une donnée non déclarée est **omise**, jamais « inconnu » ;
   - à droite : **Terminal du poste** (+ pastille de vie), **Teams** si le compte a le droit (ouvre le
     terminal Teams comme aujourd'hui — amendement F-106 : pas d'onglet Radar, l'ouverture est
     inchangée), **menu « ··· »** : Couper la liaison…, Supprimer le poste.
2. **Poste « Hébergé »** : icône `cloud`, nom, « Ces projets vivent chez la gateway… », actions
   « Ouvrir un dépôt GitHub » et « Importer une archive .zip » ; **un seul onglet, Projets**.
3. **Onglets** (filet orange sous l'onglet actif) et leur **résumé** :
   - **Projets** `n` — la liste des projets, les dossiers non ouverts et « Ajouter un projet »
     (contenu inchangé, mis en grille par SF-98-03) ;
   - **Carte** — « *n* faits » quand la carte est lue et gouvernée, « hors ligne » quand le poste
     l'est, rien sinon ; contenu : relevé de la carte, fichiers, gain (F-92, F-93), inchangés ;
   - **Gouvernance** — pastille « à appliquer » (`badge--warning`) si une mise à jour de paquet
     attend (F-96), sinon « à corriger » (`badge--error`) s'il y a des erreurs d'intégrité, rien
     sinon ; contenu : le bandeau F-96 (« Une version plus récente de *n* paquet(s) existe. Rien n'a
     été écrit sur cette machine. ») avec le lien « Ouvrir la gouvernance », puis l'intégrité (F-95)
     déplacée depuis la carte ; « Rien à appliquer, aucun constat d'intégrité. » quand il n'y a rien ;
   - **Activité** — ce qui tourne ou la dernière activité du poste, l'aperçu du terminal du poste
     (F-76), et les projets par activité récente avec leur dernier outil.
4. **URL** : `?onglet=carte|gouvernance|activite` ; absent ⇒ Projets. Cliquer un onglet **ajoute**
   une entrée d'historique (retour arrière ramène à l'onglet précédent) et garde le poste. Changer de
   poste depuis la colonne **garde l'onglet** (SF-98-01 préserve les paramètres de requête).
5. **Lecture du compte F-96** : `GET /api/governance/hosts` (existant), **une fois** au chargement
   de la page et à chaque « Rafraîchir » demandé — jamais au sondage de 15 s. Échec **silencieux** :
   pas de pastille, pas de bandeau.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| `?onglet=` inconnu | onglet Projets | — |
| `?onglet=carte` / `gouvernance` / `activite` sur « Hébergé » | onglet Projets (seul onglet) | — |
| Lecture des postes de gouvernance en échec | aucune pastille ni bandeau F-96 ; l'intégrité reste affichée | 5xx / 0 |
| Gateway antérieure sans `outdated` | traité comme 0 | — |
| Carte non encore lue | onglet Carte sans résumé | — |
| Compte sans droit Teams, ou lecture du droit en échec | pas de bouton Teams (fail-closed, F-89) | — |

---

## Critères d'acceptation

- [ ] Le poste ouvert affiche un en-tête (identité, état, racine, système, vu il y a…) et les actions
      Terminal du poste / Teams (si droit) / menu, sans bloc « terminal » dédié.
- [ ] Quatre onglets pour une machine, un seul (Projets) pour « Hébergé ».
- [ ] Chaque onglet porte son résumé sans être ouvert : nombre de projets, « *n* faits » ou « hors
      ligne », « à appliquer » / « à corriger ».
- [ ] `?onglet=carte` ouvre l'onglet Carte ; cliquer un onglet met l'URL à jour ; un onglet inconnu
      ouvre Projets.
- [ ] Le bandeau F-96 et l'intégrité F-95 vivent dans l'onglet Gouvernance ; la carte et son gain dans
      l'onglet Carte ; aucune logique de lecture de ces blocs n'est modifiée (une lecture par page,
      jamais au sondage).
- [ ] Les gestes existants (mission, supprimer, couper la liaison, terminal du poste, Teams, ajouter
      un projet, dossiers, dépôt GitHub, archive) restent atteignables et leurs tests verts.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md` : filet d'onglet `--cg-orange`, pastilles §5.

---

## Périmètre

### Hors scope (explicite)

- La grille de tuiles, le tri, la tuile fantôme (SF-98-03).
- La réécriture des libellés de la carte et la relecture au retour en ligne (SF-98-05).
- Le déménagement du terminal Teams dans la Vigie (F-106).
- Toute évolution fonctionnelle des blocs déplacés : on les range, on ne les change pas.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| onglet | `projets` | lu dans `?onglet=` ; inconnu ou indisponible ⇒ `projets` |
| compte F-96 par poste | absent (0) | lu une fois par page ; échec ⇒ 0 |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `onglet` (URL) | Non | `projets`, `carte`, `gouvernance`, `activite` | minuscules ; autre ⇒ `projets` |

---

## Technique

### Endpoint(s)

Aucun nouveau. Lecture ajoutée côté écran d'un endpoint **existant** : `GET /api/governance/hosts`
(isolé par le JWT, déjà lu par l'écran Gouvernance).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `PostesComponent` — en-tête du poste, onglets, `?onglet=`, lecture du compte F-96.
- `postes/forge-tabs.ts` (nouveau) — fonctions pures : onglets disponibles, onglet effectif, résumés.

### Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés : `PostesComponent` (paramètre de requête
  `onglet`), `ForgeRailComponent` / `selectHost` (préserve la requête, vérifié), anciennes adresses
  `/forge`, `/forge/<id>`, `#poste-<id>` (inchangées, SF-98-01). Aucune route ajoutée.
- Auth / Principal : non. Contexte tenant : non (endpoint existant, JWT). Plans / limites : non.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `forge-tabs.spec.ts` — onglets d'une machine / de « Hébergé », onglet effectif (absent, inconnu,
      indisponible), résumé Carte (faits, hors ligne, rien), résumé Gouvernance (à appliquer, à
      corriger, rien).
- [ ] `PostesComponent` — en-tête (identité, état daté, racine, système, runner, administrateur,
      omissions) ; actions Terminal du poste / Teams / menu dans l'en-tête ; `?onglet=carte` ouvre la
      carte ; clic sur un onglet ⇒ navigation avec `onglet` ; « Hébergé » n'a qu'un onglet ; bandeau
      F-96 et intégrité dans Gouvernance ; compte F-96 lu une fois, relu sur « Rafraîchir », jamais
      au sondage, échec silencieux ; tests existants des blocs adaptés à leur onglet.

### Isolation workspace

- [x] Non applicable — aucune donnée nouvelle ; `GET /api/governance/hosts` part du JWT (isolation
      déjà couverte côté gateway par F-51 / F-96).

---

## Dépendances

### Subfeatures bloquantes

- SF-98-01 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Le compte F-96 vient de `GET /api/governance/hosts`** : c'est la seule source existante du nombre
  de paquets à mettre à jour par poste. Lu comme la carte (une fois par page), pour ne rien ajouter au
  sondage.
- **L'intégrité quitte la carte pour la Gouvernance** : le cadrage la range explicitement là (« bandeau
  F-96 et intégrité F-95 »). Sa lecture reste attachée à celle de la carte (même régime, F-95).
- **Onglet dans l'historique** : un clic d'onglet pousse une entrée, comme le demande le cadrage
  (« un retour arrière ramène au bon endroit »).
