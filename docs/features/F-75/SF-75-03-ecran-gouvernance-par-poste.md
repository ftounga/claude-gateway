# Mini-spec — F-75 / SF-75-03 — L'écran : un poste, des fichiers qu'on ouvre, une vraie confirmation

## Identifiant

`F-75 / SF-75-03`

## Feature parente

`F-75` — La gouvernance s'active par poste, pas par projet

## Statut

`done` — mergée le 2026-09-12 (PR #393)

## Date de création

2026-09-12

## Branche Git

`feat/SF-75-03-ecran-gouvernance-par-poste`

---

## Objectif

Faire de l'écran Gouvernance un écran **par poste**, où l'on ouvre chaque fichier en lecture seule,
où l'on voit le différentiel avec ce qui existe déjà, et où l'activation est une **confirmation**
et non un bouton.

---

## Comportement attendu

### Cas nominal

1. L'écran Gouvernance propose la liste des **postes** (et non des projets) : les postes réels, puis
   « Hébergé » s'il porte des projets. Le poste choisi affiche ses projets — ce sont eux qui
   recevront les fichiers.
2. Les cartes du catalogue restent ce qu'elles sont (retenir / oublier / appliqué par défaut).
3. « Activer sur ce poste » ouvre le dialogue d'annonce. Il liste les fichiers, leur chemin, leur
   nature, et leur sort sur chacun des projets du poste.
4. **Cliquer un fichier l'ouvre** en lecture seule : le contenu apporté par le paquet, dans une zone
   à défilement, police `JetBrains Mono`.
5. Quand le fichier **existe déjà** sur un projet, la visionneuse propose le **différentiel** :
   ligne par ligne, ce que le paquet apporte face à ce qui est en place — et le rappel explicite que
   c'est **l'existant qui sera gardé**. Un projet par onglet quand il y en a plusieurs.
6. Le bouton de confirmation reste **désactivé** tant que le plan n'a pas été chargé, et son libellé
   dit ce qu'il fait : « Activer sur *NomDuPoste* ». Annuler n'envoie rien.
7. Après activation, l'écran rend l'état du poste : paquets actifs, statut, geste « appliquer » si un
   projet n'a pas pu être lu.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Aucun poste | Message d'invitation vers la Forge, pas de liste vide muette |
| Aperçu en échec | Le dialogue ne s'ouvre pas, un `MatSnackBar` le dit, **rien n'est activé** |
| Lecture d'un fichier en échec | La visionneuse le dit à sa place, le reste du dialogue reste utilisable |
| Projet illisible | Bandeau « la machine est peut-être éteinte » ; l'activation reste possible |
| 403 | État « accès refusé », pas de nouvelle tentative en boucle |
| 409 (paquet non retenu) | Message « retenez ce paquet avant de l'activer » |

---

## Critères d'acceptation

- [ ] L'écran choisit un **poste**, jamais un projet ; aucun appel à `/workspaces/{id}/governance`.
- [ ] Le dialogue d'annonce liste les fichiers et, pour chacun, ce qui arrivera sur les projets.
- [ ] Cliquer un fichier ouvre son **contenu** en lecture seule.
- [ ] Quand le fichier existe déjà, le **différentiel** est affiché, et le texte dit que l'existant
      est gardé.
- [ ] La confirmation est explicite, nommée, et désactivée tant que le plan n'est pas là.
- [ ] Annuler n'envoie aucune requête d'activation.
- [ ] Design system : couleurs, polices et espacements conformes à `docs/DESIGN_SYSTEM.md` ; aucun
      quatrième registre de couleur n'est introduit ; aucune `window.confirm`.
- [ ] Aucun appel de l'écran ne transporte d'identifiant d'utilisateur.

---

## Périmètre

### Hors scope (explicite)

- Le contenu des paquets et leur rédaction (F-51 / F-52).
- La modification d'un fichier depuis la visionneuse.
- L'écran d'administration des paquets.

---

## Composants impactés

| Composant | Changement |
|---|---|
| `governance.component.ts/html/scss` | Grain poste, liste des postes, état du poste |
| `deposit-preview-dialog` | Fichiers cliquables, sort par projet, confirmation nommée |
| `governance-file-viewer` (nouveau) | Lecture seule + différentiel par projet |
| `core/services/governance.service.ts` | Routes `/governance/hosts/**`, lecture de fichier |
| `core/models/governance.models.ts` | Modèles poste / plan par projet / comparaison de fichier |
| `shared/…/line-diff.ts` (nouveau) | Différentiel ligne à ligne, côté écran |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non (porté par la gateway) | — |
| Plans / limites | non | — |
| **Navigation / routing** | **non** — la route `/gouvernance` ne change pas ; seul son contenu change | `app.routes.ts` (inchangé), vérifié |

---

## Plan de test minimal

### Unitaires (Karma/Jasmine)

- `governance.component.spec.ts` : charge postes + catalogue ; choisir un poste charge son état ;
  activer demande l'aperçu **avant** d'ouvrir le dialogue ; annuler n'active pas ; 403 → état.
- `deposit-preview-dialog.component.spec.ts` : liste des fichiers ; clic → demande de contenu ;
  confirmation désactivée sans plan ; libellé nommé.
- `line-diff.spec.ts` : lignes ajoutées / retirées / inchangées ; contenus identiques → diff vide.

### Intégration

- Parcours écran complet avec `HttpTestingController` : postes → aperçu → lecture d'un fichier →
  confirmation → état rafraîchi.

### Isolation utilisateur

- Vérifié côté gateway (SF-75-01/02) ; l'écran n'émet aucun identifiant d'utilisateur — test qui
  l'atteste sur les URL appelées.
