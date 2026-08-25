# Mini-spec — [F-30 / SF-10] Retour au terminal depuis l'explorateur

---

## Identifiant

`F-30 / SF-10`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-26

## Branche Git

`feat/SF-30-10-retour-terminal-depuis-explorateur`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre de revenir de l'explorateur de fichiers **au projet qu'on quittait**, et d'ouvrir
directement son terminal.

---

## Contexte

L'explorateur (`/atelier/{id}/fichiers`, SF-28-15) porte un bouton « Retour à l'Atelier » qui navigue
vers **`/atelier`, sans identifiant**. Or la route n'accepte aucun paramètre et l'écran Atelier ne lit
rien de l'URL : le retour **perd le projet** et ramène en mode **Assistant**.

Le parcours réel est donc : consulter un fichier → revenir → re-sélectionner son projet → rebasculer en
mode Terminal. Quatre gestes pour revenir là où on était.

Le manque se voit d'autant plus depuis la vue terminal immersive (SF-30-07), où le terminal *est*
l'écran : le quitter pour l'explorateur devient un aller sans retour direct.

---

## Comportement attendu

### Cas nominal

1. La route de l'Atelier accepte un identifiant de projet optionnel : `/atelier/{id}`.
2. Elle accepte aussi un mode optionnel en paramètre de requête : `?mode=terminal`.
3. À l'ouverture avec un identifiant, le projet correspondant est **sélectionné d'emblée** ; avec
   `mode=terminal`, la vue terminal s'ouvre directement.
4. L'explorateur propose deux retours distincts : **« Retour au projet »** (mode Assistant) et
   **« Ouvrir le terminal »**, tous deux ciblant le projet consulté.
5. `/atelier` sans identifiant se comporte **exactement comme aujourd'hui**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Identifiant inconnu ou projet d'un autre utilisateur | Repli sur le comportement actuel (liste des projets), message lisible — jamais d'écran vide |
| `mode` inconnu ou absent | Mode Assistant, comme aujourd'hui |
| Projet supprimé entre-temps | Même repli que l'identifiant inconnu |

---

## Critères d'acceptation

- [ ] `/atelier/{id}` sélectionne le projet demandé
- [ ] `/atelier/{id}?mode=terminal` ouvre en plus la vue terminal
- [ ] `/atelier` **sans** identifiant est strictement inchangé (non-régression)
- [ ] L'explorateur offre « Retour au projet » **et** « Ouvrir le terminal », ciblant le projet consulté
- [ ] Un identifiant inconnu ou non possédé retombe proprement sur la liste, avec un message
- [ ] L'isolation reste garantie côté backend : un identifiant deviné ne donne accès à rien
- [ ] Aucun endpoint créé, aucune table, aucune migration

---

## Périmètre

### Hors scope

- Mémorisation du dernier projet ouvert entre deux visites
- Ouverture de l'explorateur **dans** la vue terminal (panneau latéral) : c'est une autre approche,
  qui remettrait en cause le parti pris immersif de SF-30-07
- Lien direct vers un fichier précis du projet

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Route | Paramètre **optionnel** — `/atelier` doit continuer de fonctionner |
| Mode | `?mode=terminal` uniquement ; toute autre valeur → Assistant |
| Repli | Identifiant invalide → liste des projets + message, jamais d'erreur brute |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `app.routes.ts` | + route `atelier/:id` (l'existante `atelier` est conservée) |
| `atelier/atelier.component.ts` | Lecture de l'identifiant et du mode depuis l'URL |
| `atelier/files/atelier-files.component.html` | Deux boutons de retour ciblant le projet |

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `/atelier/{id}` → le projet est sélectionné
- [ ] `?mode=terminal` → la vue terminal est rendue
- [ ] `/atelier` sans identifiant → comportement actuel (non-régression explicite)
- [ ] Identifiant inconnu → repli sur la liste + message, aucune erreur non gérée
- [ ] L'explorateur produit les deux liens avec le bon identifiant

### Tests d'intégration

Sans objet : navigation cliente, aucun appel réseau nouveau.

### Isolation utilisateur

- [ ] **Non applicable côté frontend** — un identifiant deviné ne donne accès à rien : le chargement du
  projet passe par les endpoints existants, filtrés par `user_id` (`requireOwned`).

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement ; la route reste sous le shell `authGuard`. |
| Contexte tenant | **Non** | Aucun nouveau chemin d'accès : le projet est chargé par les endpoints existants, déjà filtrés. |
| Plans / limites | **Non** | Aucun appel de quota. |
| Navigation / routing | **Oui** | Une route est ajoutée. Composants vérifiés : `app.routes.ts` (`atelier` conservée telle quelle, `atelier/:id` ajoutée **après** elle pour ne pas la masquer ; `atelier/:id/fichiers` inchangée), `authGuard` (inchangé, la route reste sous le shell), `atelier.component` (lit désormais l'URL, sans changer son comportement par défaut), `atelier-files.component` (ses deux liens), et tous les points de navigation existants vers `/atelier` — inchangés puisque la route sans paramètre est conservée. |

---

## Dépendances

- **SF-28-15 (Done)** — l'explorateur.
- **SF-30-07 (Done)** — la vue terminal immersive.

---

## Notes et décisions

- **Deux boutons plutôt qu'un** : revenir au projet et ouvrir le terminal sont deux intentions
  différentes. Un bouton unique obligerait à deviner laquelle, et se tromperait la moitié du temps.
- **Route additive, pas modifiée** : `/atelier` reste valide. Tous les liens existants continuent de
  fonctionner, et rien à migrer.
- **Le mode en paramètre de requête, pas dans le chemin** : c'est une préférence d'affichage, pas une
  ressource — et cela évite d'inventer `/atelier/{id}/terminal`, qui laisserait croire à un écran
  distinct alors que c'est le même, dans un autre mode.
