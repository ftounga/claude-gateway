# Mini-spec — F-51 / SF-51-05 — L'écran du catalogue

## Identifiant

`F-51 / SF-51-05`

## Feature parente

`F-51` — Catalogue de gouvernance

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-51-05-ecran-catalogue`

---

## Objectif

Donner l'écran où l'on parcourt le catalogue, retient ce qu'on veut, marque ce qui s'appliquera par
défaut, et active un paquet sur un projet — **après avoir vu ce qu'il va écrire et où**.

---

## Comportement attendu

### Cas nominal

1. `/gouvernance` (dans la coquille authentifiée, entrée dans la barre de navigation) charge le
   catalogue publié et le catalogue personnel.
2. **Catalogue** : une carte par paquet publié. Elle montre le nom, le résumé, la version, les
   **règles** (dépliables), les **contrôles** et **la liste exacte des fichiers** qu'il déposerait —
   chemin + genre. Un bouton **Retenir** / **Ne plus retenir**, et une case **Appliqué par défaut**.
3. **Mes projets** : un sélecteur de projet. Pour le projet choisi, la liste de ce qui y est **actif**
   (version appliquée, état, mention « une version plus récente existe » le cas échéant) et de ce qui
   est **disponible** (retenu mais pas encore actif).
4. **Activer** ouvre d'abord un **dialogue d'annonce** : la liste des chemins, avec pour chacun
   « sera créé » ou « déjà présent, laissé tel quel ». L'activation n'a lieu qu'après confirmation.
5. **Appliquer** rejoue le dépôt d'un paquet actif resté en attente ; **Désactiver** l'éteint, après
   confirmation, en rappelant que **les fichiers déjà déposés restent**.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Sans droit Atelier (403) | Bandeau « la gouvernance fait partie de l'Atelier » + lien vers les formules ; aucun appel en boucle |
| Gateway injoignable | Bandeau « le catalogue n'a pas pu être lu » + bouton Réessayer ; rien n'est perdu |
| Aucun paquet publié | État vide expliquant que l'admin n'en a encore publié aucun |
| Aucun projet | État vide renvoyant vers l'Atelier |
| Activation refusée (409, paquet non retenu) | Message d'erreur en `MatSnackBar`, l'écran reste cohérent |
| Aperçu impossible (machine éteinte) | Le dialogue le **dit** : « le projet n'a pas pu être lu », chaque ligne en « indéterminé », et l'activation reste possible — les règles s'appliqueront, les fichiers attendront |

---

## Critères d'acceptation

- [ ] `/gouvernance` est accessible depuis la barre de navigation, sous la coquille authentifiée.
- [ ] Chaque paquet du catalogue affiche **la liste des chemins** qu'il déposerait.
- [ ] « Retenir » ajoute le paquet au catalogue personnel et la liste se met à jour.
- [ ] La case « appliqué par défaut » est envoyée au backend et son état reflète la réponse.
- [ ] **Activer ouvre l'annonce avant d'écrire** : aucune requête d'activation n'est envoyée tant que
      le dialogue n'est pas confirmé.
- [ ] Le dialogue distingue « sera créé », « déjà présent » et « indéterminé ».
- [ ] Un aperçu impossible affiche l'avertissement et n'empêche pas d'activer.
- [ ] « Désactiver » demande confirmation et rappelle que les fichiers restent.
- [ ] Un 403 affiche le bandeau d'Atelier et **arrête** les chargements.
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; aucune `window.confirm`.

---

## Périmètre

### Hors scope (explicite)

- La **publication** d'un paquet (écran admin) → SF-51-06.
- Éditer le contenu d'un paquet côté utilisateur : un paquet est publié par l'admin.
- Voir le **contenu** des fichiers d'un paquet : l'écran annonce **où**, pas **quoi** — c'est la
  décision de SF-51-01, et elle garde le catalogue léger.

---

## Impacts

### Tables / endpoints

**Aucun changement backend.** L'écran consomme ce que SF-51-01 → SF-51-04 exposent :
`GET /governance/packages`, `GET|PUT|DELETE /governance/selection[/{id}]`,
`GET /workspaces/{id}/governance`, `GET .../preview`, `POST .../{id}`, `POST .../apply`,
`DELETE .../{id}`.

### Composants

| Composant | Changement |
|---|---|
| `core/models/governance.models.ts` | **créé** — le contrat TypeScript des vues backend |
| `core/services/governance.service.ts` | **créé** — un appel par endpoint, rien de plus |
| `governance/governance.component.*` | **créé** — l'écran |
| `governance/deposit-preview-dialog/*` | **créé** — l'annonce avant écriture |
| `app.routes.ts` | route `gouvernance` **dans** la coquille authentifiée |
| `layout/shell/shell.component.html` | une entrée de navigation |

---

## Arbitrages de cette subfeature

| # | Sujet | Décision | Motif | Réversible |
|---|---|---|---|---|
| E1 | Où vit l'écran | Une page à part, `/gouvernance` | La gouvernance se compose une fois et sert partout ; l'enfouir dans le détail d'un projet obligerait à la refaire projet par projet | oui |
| E2 | L'annonce | Un **dialogue** bloquant, jamais un simple texte sur la carte | L'exigence dit « avant activation ». Un texte qu'on peut ne pas lire n'est pas une annonce ; un dialogue qu'il faut confirmer en est une | oui |
| E3 | Aperçu impossible | On **active quand même**, en le disant | Les règles et les contrôles s'appliquent sans disque. Interdire l'activation parce qu'une machine est éteinte punirait l'utilisateur pour un état normal | oui |
| E4 | Choix du projet | Un sélecteur sur la même page | Deux écrans (catalogue / projet) obligeraient à naviguer pour comparer ce qu'on a retenu et ce qu'on applique | oui |

---

## Plan de test minimal

`governance.component.spec.ts` (`HttpTestingController`) :

- Chargement : catalogue + sélection + projets ; rendu des chemins annoncés.
- « Retenir » émet le `PUT` attendu et met la liste à jour.
- **Activer sans confirmer n'émet aucun `POST`** ; confirmer l'émet.
- Le dialogue reçoit bien le plan rendu par `preview`.
- Aperçu impossible (`readable: false`) → avertissement, activation toujours possible.
- 403 → bandeau Atelier, aucun rechargement.
- Erreur réseau → bandeau + bouton Réessayer.
- « Désactiver » confirmé émet le `DELETE`.

`deposit-preview-dialog.component.spec.ts` : rend les trois verdicts, ferme en confirmant / annulant.

### Isolation

L'isolation est **entièrement** portée par la gateway : aucun appel de cet écran ne transporte
d'identifiant d'utilisateur, tous partent du JWT. Le test vérifie qu'aucune URL construite ne contient
d'identifiant d'utilisateur.

---

## Contraintes de validation

Aucune nouvelle : les bornes sont celles du backend. Design system : palette et polices de
`docs/DESIGN_SYSTEM.md`, espacements multiples de 4 px via les variables `--cg-space-*`,
confirmations via `MatDialog`, notifications via `MatSnackBar`.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | non | Aucun changement ; l'écran vit sous l'`authGuard` de la coquille |
| Contexte tenant | non | Aucun identifiant d'utilisateur n'est manipulé côté écran |
| Plans / limites | **oui** (affichage) | Un 403 d'Atelier est rendu comme un état, avec le lien vers les formules — même geste que l'écran des postes (SF-49-02). Aucun gate nouveau |
| **Navigation / routing** | **oui** | Route ajoutée : `gouvernance`, **enfant** de la route pathless authentifiée, un seul segment, disjointe de toutes les existantes (`chat`, `atelier`, `atelier/:id`, `postes`, `documents`, `ask`, `templates`, `reports`, `billing`, `settings`, `profile`, `admin`) — elle n'en masque aucune et aucune ne la masque. Le catch-all `**` reste en dernier. Entrée de navigation ajoutée dans `shell.component.html`, sans toucher aux autres |
