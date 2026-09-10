# Mini-spec — F-49 / SF-49-02 — L'écran des postes

## Identifiant

`F-49 / SF-49-02`

## Feature parente

`F-49` — Vue d'ensemble des postes

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-49-02-ecran-des-postes`

---

## Objectif

Donner à l'utilisateur **un endroit d'où voir toutes ses machines** — connectées ou non, ce qui vit
dessous, ce qui tourne — avec le terminal d'un projet **à un clic**.

---

## Comportement attendu

### Cas nominal

1. L'entrée **Postes** de la barre de navigation ouvre `/postes`.
2. L'écran appelle `GET /api/runner-hosts/overview` et rend **une carte par poste** :
   - **en tête** : le nom libre du poste, une pastille **Connecté** (vert) / **Déconnecté** (gris), et
     le « depuis quand » calculé à l'écran à partir de `lastSeenAt` ;
   - **la fiche machine** : racine déclarée, système, interpréteur élu, et une pastille
     **Administrateur** (orange) si le runner tourne élevé — une ligne omise plutôt qu'un « inconnu »
     quand le runner n'a rien déclaré ;
   - **les projets** rangés dessous : nom, chemin sous la racine, cible d'exécution, dernière
     activité en relatif, dernier outil, et une pastille **Actif** pour ceux qui tournent ;
   - **un bouton par projet** qui ouvre son terminal (`/atelier/{id}`).
3. Un compteur en tête de carte dit **ce qui tourne** : « 2 projets actifs » — ou rien à dire.
4. La vue se **rafraîchit toute seule** toutes les 15 secondes, et affiche l'heure de sa dernière
   mise à jour. Un bouton **Rafraîchir** force la relecture.
5. Le rafraîchissement **s'arrête** quand l'onglet passe en arrière-plan et **reprend** au retour :
   une vue que personne ne regarde n'a aucune raison d'appeler la gateway.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Aucun poste | État vide : « Aucun poste connecté » + explication du geste (connecter une machine depuis un projet) et lien vers l'Atelier |
| Poste sans projet | La carte s'affiche et dit « Aucun projet sous ce poste » |
| Échec réseau au premier chargement | Message d'erreur dans la page + bouton **Réessayer** ; aucune donnée inventée |
| Échec réseau **pendant** un rafraîchissement | La vue précédente **reste affichée** ; un discret « dernière mise à jour à HH:MM » suffit à dire qu'elle vieillit. Pas de page vidée, pas de snackbar toutes les 15 s |
| 403 (pas d'accès Atelier) | Message dédié : la vue des postes fait partie de l'Atelier, avec un lien vers la facturation |
| Réponse sans projet (`projects` absent) | Traité comme une liste vide, jamais une erreur de rendu |

---

## Critères d'acceptation

- [ ] `/postes` est une route **authentifiée**, sous la coquille, et l'entrée « Postes » figure dans
      la barre de navigation.
- [ ] L'écran rend une carte par poste, avec nom, pastille d'état et « depuis quand ».
- [ ] Racine, système et interpréteur sont affichés quand ils existent, **omis** sinon.
- [ ] La pastille « Administrateur » n'apparaît que si `elevated` est vrai.
- [ ] Chaque projet affiche nom, chemin, dernière activité relative, dernier outil, et son état actif.
- [ ] Chaque projet propose un bouton qui navigue vers `/atelier/{id}`.
- [ ] Un poste sans projet affiche un message dédié, pas une liste vide muette.
- [ ] Aucun poste → état vide explicite avec le geste à faire.
- [ ] La vue se rafraîchit toutes les 15 s, s'arrête à la destruction du composant **et** quand
      l'onglet est masqué.
- [ ] Un échec de rafraîchissement ne vide pas la vue déjà affichée.
- [ ] Un 403 affiche le message d'accès Atelier, pas une erreur générique.
- [ ] **Aucun flux SSE / WebSocket n'est ouvert par cet écran** (arbitrage n° 3).
- [ ] Charte : couleurs et polices via les jetons `--cg-*`, espacements multiples de 4 px,
      `mat-card`, pas de `window.alert/confirm`.

---

## Périmètre

### Hors scope (explicite)

- **Agir** sur un poste depuis cet écran : renommer, supprimer, couper, révoquer un jeton, générer un
  code d'appairage. Ces gestes existent déjà dans le dialogue de mise en service ; les dupliquer ici
  demanderait des confirmations destructives et double le risque pour un gain nul à ce stade.
- Agir sur **plusieurs postes** à la fois — hors périmètre F-49.
- Un terminal vivant, ou plusieurs, dans cette page.
- Les projets **non rattachés** à un poste : l'écran de l'Atelier les montre déjà.
- Le journal détaillé d'un projet : il a son écran (F-38 / SF-38-08).

---

## Valeurs initiales

Sans objet — l'écran ne crée aucune entité et n'écrit rien.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

L'écran est en **lecture seule** : aucun formulaire, aucune saisie, donc aucune contrainte de
validation d'entrée. Les seules constantes sont d'affichage :

| Constante | Valeur | Motif |
|---|---|---|
| Période de rafraîchissement | 15 s | Assez vif pour une vue d'état, assez lent pour ne pas peser (4 appels/min et par onglet) |
| Seuil « à l'instant » | < 60 s | En deçà, une durée en secondes ; puis en minutes, puis en heures |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---|---|---|---|
| GET | `/api/runner-hosts/overview` | Oui (JWT) | Utilisateur avec accès Atelier |

Aucun endpoint créé : SF-49-01 l'a livré.

### Tables impactées

Aucune — écran de lecture.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable**

### Composants Angular

- `PostesComponent` (`frontend/src/app/postes/`) — l'écran : cartes de postes, projets, état,
  rafraîchissement.
- `AtelierService` — une méthode de lecture de plus (`runnerHostsOverview()`).
- `atelier.models.ts` — `RunnerHostOverview` et `HostProjectSummary`.
- `app.routes.ts` — route `postes` sous la coquille authentifiée.
- `shell.component.html` — entrée de navigation « Postes ».

### Préoccupation transversale — Navigation / routing

Déclencheur coché : **une route est ajoutée**. Composants impactés, vérifiés un par un :

| Composant | Impact | Vérification |
|---|---|---|
| `app.routes.ts` | Route `postes` ajoutée **dans** le parent pathless authentifié | Les routes existantes ne bougent pas ; `postes` n'est pas un préfixe d'une autre. `app.routes.spec.ts` couvre déjà la forme des routes |
| `shell.component.html` | Une entrée de nav de plus | Les entrées existantes conservent leur `routerLink` et leur `routerLinkActive` |
| `authGuard` | Aucune modification | La route hérite du guard porté par le parent, comme toutes les autres |
| `atelier/:id` | Aucune modification | La navigation vers le terminal emploie la route additive existante (F-30 / SF-30-10) |
| Route `**` | Inchangée, toujours en dernier | `postes` est déclarée avant elle |

---

## Plan de test

### Tests unitaires

- [ ] Rend une carte par poste, avec son nom et son état.
- [ ] Rend les projets d'un poste, ordre du backend conservé.
- [ ] Un poste sans projet affiche le message dédié.
- [ ] Aucun poste → état vide.
- [ ] Le libellé relatif : secondes, minutes, heures ; `null` quand la date manque.
- [ ] Le compteur de projets actifs reprend `activeProjects`.
- [ ] Ouvre `/atelier/{id}` au clic sur le terminal d'un projet.
- [ ] Rafraîchit au bout de 15 s (temps simulé) et cesse à la destruction.
- [ ] Un échec pendant un rafraîchissement conserve la vue précédente.
- [ ] Un échec au premier chargement affiche l'erreur et le bouton Réessayer.
- [ ] Un 403 affiche le message d'accès Atelier.
- [ ] `AtelierService.runnerHostsOverview()` appelle bien `GET /api/runner-hosts/overview`.

### Tests d'intégration

Sans objet côté frontend — l'endpoint est couvert par les tests d'intégration de SF-49-01.

### Isolation workspace

- [x] Applicable — garantie **côté backend** : l'appel ne porte aucun identifiant, la gateway part du
      JWT. L'écran ne peut pas demander la machine d'un autre : il n'a rien à lui passer.

---

## Dépendances

### Subfeatures bloquantes

- `SF-49-01` — **done** (mergée, PR #316)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — Un sondage, pas un flux.** L'arbitrage n° 3 du cadrage est appliqué à la lettre : cet écran
n'ouvre aucun canal. Quinze secondes, et rien du tout quand l'onglet est masqué.

**D2 — L'écran est en lecture seule.** Les gestes de machine restent là où ils sont déjà. Une vue
d'ensemble qui porterait « supprimer » et « couper » sur chaque carte transformerait un écran de
consultation en champ de mines, pour un gain que le dialogue existant couvre.

**D3 — Le « depuis quand » se calcule à l'écran.** La gateway rend des instants ; une durée calculée
au serveur vieillit dans le navigateur et redevient fausse entre deux rafraîchissements.

**D4 — Un échec de rafraîchissement ne vide pas la page.** Une vue d'état qui clignote à chaque
hoquet réseau est pire que la même vue légèrement en retard : on garde l'affichage et on date la
dernière mise à jour.
